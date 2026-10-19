package com.mxfia19.twitchunblock.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.LiveApi
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Master
import com.mxfia19.twitchunblock.core.NetworkStatus
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.core.Usage
import com.mxfia19.twitchunblock.core.VodApi
import com.mxfia19.twitchunblock.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Ce qu'on demande à lire. */
sealed class PlayRequest {
    data class Live(val login: String) : PlayRequest()
    /** `rewind` : enregistrement du direct en cours, lu près de sa fin (rembobiner). */
    data class Vod(val id: String, val title: String = "", val channel: String? = null, val startMs: Long = -1,
                   val rewind: Boolean = false) : PlayRequest()
    data class Clip(val slug: String) : PlayRequest()
    /** VOD téléchargée, lue hors ligne. */
    data class Download(val vodId: String) : PlayRequest()
    /** Diffusion passée retrouvée (VOD retirée ou non listée). */
    data class Recovered(val stream: com.mxfia19.twitchunblock.core.RecoverableStream) : PlayRequest()
}

/**
 * Une lecture (téléphone et TV) : résolution des sources, choix de la
 * qualité (Auto = liste maîtresse, ExoPlayer suit le débit), reprise après
 * une erreur, position des VODs gardée, minuterie. `main` : la lecture
 * principale du téléphone (notification, écran verrouillé, image dans
 * l'image) ; pas les tuiles du multi-live ni la TV. `parent` : fil principal.
 * `audioFocus` faux : tuile du multi-live (handleAudioFocus).
 */
class PlaybackSession(private val context: Context, parent: CoroutineScope, private val main: Boolean = true,
                      audioFocus: Boolean = true) {
    sealed class State {
        /** `step` : source en cours d'essai (« as », « al », « eu », « twitch », « worker »). */
        data class Loading(val step: String? = null) : State()
        /** `key` : clé du texte de l'erreur. */
        data class Error(val key: String) : State()
        object Ready : State()
    }

    /** Coroutines de la lecture, toutes annulées par release() : la session
     *  peut finir avant `parent` (lecture principale portée par PhoneApp). */
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    val player: ExoPlayer = PlayerFactory.build(context, audioFocus)

    var state by mutableStateOf<State>(State.Loading())
        private set
    var request by mutableStateOf<PlayRequest?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var channelLogin by mutableStateOf<String?>(null)
        private set
    var channelName by mutableStateOf<String?>(null)
        private set
    var channelId by mutableStateOf<String?>(null)
        private set
    var avatar by mutableStateOf<String?>(null)
        private set
    var game by mutableStateOf("")
        private set
    var viewers by mutableStateOf(0)
        private set
    var startedAt by mutableStateOf<String?>(null)
        private set
    var source by mutableStateOf<String?>(null)
        private set
    var qualities by mutableStateOf<List<String>>(emptyList())
        private set
    var quality by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Minuterie : heure de la pause (ms), null sans minuterie. */
    var sleepAt by mutableStateOf<Long?>(null)
        private set
    /** Miniature de la VOD (téléchargement). */
    var thumbnail by mutableStateOf("")
        private set
    var lengthSeconds by mutableStateOf(0)
        private set
    /** Enregistrement du direct en cours (rembobiner), null sans. */
    var dvrVideoId by mutableStateOf<String?>(null)
        private set
    /** Chapitres de la VOD lue. */
    var chapters by mutableStateOf<List<com.mxfia19.twitchunblock.core.VodChapter>>(emptyList())
        private set
    /** Abonné à la chaîne (jeton du compte), null : inconnu. */
    var subscriber by mutableStateOf<Boolean?>(null)
        private set

    /** Multi-live : toujours la qualité Auto, plafonnée à cette hauteur. */
    var forceAuto = false
    private var maxHeight: Int? = null

    private var master = Master()
    private var isClip = false
    private var job: Job? = null
    private var tickJob: Job? = null
    private var sleepJob: Job? = null
    /** Reprise après une erreur, saut « fin − 90 s » du rembobinage : liés à
     *  la source en cours, annulés par open(). */
    private var retryJob: Job? = null
    private var seekJob: Job? = null
    private var errors = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errors = 0
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        })
        if (main) PlayerHolder.attach(context, this)
    }

    val isLive: Boolean get() = request is PlayRequest.Live

    /** Identifiant de la VOD lue (chat rejoué, téléchargement). */
    val vodId: String?
        get() = when (val r = request) {
            is PlayRequest.Vod -> r.id
            is PlayRequest.Download -> r.vodId
            else -> null
        }

    val isOffline: Boolean get() = request is PlayRequest.Download

    /** Liens des qualités (téléchargement d'une VOD). */
    val links: Map<String, String> get() = master.links

    fun open(req: PlayRequest) {
        saveProgress()
        job?.cancel()
        tickJob?.cancel()
        // Reprise et saut annulés, état de l'ancienne source remis à zéro :
        // sinon, erreur sur A puis raid vers B, A repartait pendant que B se
        // résolvait, ou par le menu qualité.
        retryJob?.cancel()
        seekJob?.cancel()
        val sameLive = req is PlayRequest.Live && req.login.lowercase() == channelLogin?.lowercase()
        request = req
        state = State.Loading()
        errors = 0
        chapters = emptyList()
        master = Master()
        qualities = emptyList()
        quality = null
        thumbnail = ""
        lengthSeconds = 0
        // Enregistrement du direct (rembobiner) : gardé pour la même chaîne seulement.
        if (!sameLive) dvrVideoId = null
        player.stop()
        if (main && req !is PlayRequest.Live) com.mxfia19.twitchunblock.drops.DropsHeartbeat.stop()
        job = scope.launch { resolve(req) }
    }

    /** Position (ms) : chapitres. */
    fun seekTo(ms: Long) {
        player.seekTo(maxOf(0L, ms))
    }

    fun retry() {
        request?.let { open(it) }
    }

    private suspend fun resolve(req: PlayRequest) {
        when (req) {
            is PlayRequest.Live -> {
                val r = LiveApi.getLive(req.login) { step -> state = State.Loading(step) }
                channelLogin = r.info.login
                channelName = r.info.login
                avatar = r.info.avatar
                channelId = r.info.userId
                title = r.info.title
                game = r.info.game
                viewers = r.info.viewers
                startedAt = r.info.startedAt
                source = r.source
                if (r.info.error != null) {
                    if (r.info.error != "offline" && r.info.error != "not_found") Usage.count("live_fail")
                    state = State.Error(errorKey(r.info.error))
                    return
                }
                Usage.count("live_" + (r.source ?: "unknown"))
                dvrVideoId = r.dvrVideoId
                subscriber = r.subscriber
                isClip = false
                start(r.master, startMs = -1)
                tickJob = scope.launch { refreshStats(req.login) }
                if (main) {
                    com.mxfia19.twitchunblock.core.History.addChannel(r.info.login, r.info.avatar)
                    // Drops : le flux du compte compte déjà ; sinon ses segments sont demandés à part.
                    com.mxfia19.twitchunblock.drops.DropsHeartbeat.start(r.info.login, pollSegments = r.source != "account")
                }
            }
            is PlayRequest.Vod -> {
                title = req.title
                channelLogin = req.channel
                channelName = req.channel
                game = ""
                source = null
                // Téléchargée : lue sur le téléphone, sans chercher de source.
                val saved = Downloads.find(req.id)
                if (saved != null && saved.completed) {
                    open(PlayRequest.Download(req.id))
                    return
                }
                val (m, error) = VodApi.getVod(req.id)
                if (error != null) {
                    Usage.count("vod_fail")
                    state = State.Error(errorKey(error))
                    return
                }
                Usage.count("vod")
                VodApi.meta(req.id)?.let { meta ->
                    if (title.isEmpty()) title = meta.title
                    thumbnail = meta.thumbnail
                    lengthSeconds = meta.lengthSeconds
                }
                isClip = false
                val resume = when {
                    req.rewind -> -1L
                    req.startMs >= 0 -> req.startMs
                    else -> AppState.vodPosition(req.id)
                }
                start(m, resume)
                tickJob = scope.launch { trackProgress(req.id) }
                if (main) com.mxfia19.twitchunblock.core.History.addVod(req.id, title, thumbnail, channelLogin)
                if (req.rewind) {
                    // Près de la fin de l'enregistrement : là où en est le direct.
                    seekJob = scope.launch {
                        repeat(50) {
                            val d = player.duration
                            if (d > 0) {
                                player.seekTo(maxOf(0L, d - 90_000L))
                                return@launch
                            }
                            delay(300)
                        }
                    }
                }
                val vodId = req.id
                scope.launch {
                    val list = VodApi.chapters(vodId)
                    if (vodId == this@PlaybackSession.vodId) chapters = list
                }
            }
            is PlayRequest.Download -> {
                val item = Downloads.find(req.vodId)
                if (item == null) {
                    state = State.Error("err_vod_unavailable")
                    return
                }
                title = item.title
                channelLogin = item.channel.takeIf { it.isNotEmpty() }
                channelName = channelLogin
                thumbnail = item.thumbnail
                lengthSeconds = item.lengthSeconds
                source = null
                isClip = false
                start(Master(mapOf(item.quality.ifEmpty { "Source" } to item.uri)), AppState.vodPosition(req.vodId))
                tickJob = scope.launch { trackProgress(req.vodId) }
            }
            is PlayRequest.Recovered -> {
                val st = req.stream
                title = st.title
                channelLogin = st.login
                channelName = st.login
                game = st.game
                source = null
                val m = VodApi.recoverResolve(st)
                if (m == null) {
                    state = State.Error("err_vod_unavailable")
                    return
                }
                isClip = false
                start(m, startMs = 0)
            }
            is PlayRequest.Clip -> {
                val c = BrowseApi.clip(req.slug)
                if (c == null) {
                    state = State.Error("err_clip_unavailable")
                    return
                }
                title = c.title
                channelLogin = c.broadcasterLogin
                channelName = c.broadcasterName ?: c.broadcasterLogin
                isClip = true
                start(Master(c.links), startMs = 0)
            }
        }
    }

    private fun start(m: Master, startMs: Long) {
        master = m
        val keys = m.links.keys.toMutableList()
        if (m.text != null) keys += Quality.AUTO
        qualities = Quality.sort(keys, m.variants)
        val key = if (forceAuto && m.text != null) Quality.AUTO
                  else Quality.launchKey(keys, AppState.qualityPref, AppState.cellularQuality, NetworkStatus.isCellular, m.variants)
        Logger.info("PLAYER", "Qualité au lancement", key + if (NetworkStatus.isCellular) " (données mobiles)" else "")
        play(key, startMs)
        state = State.Ready
    }

    private fun play(key: String, startMs: Long) {
        quality = key
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifEmpty { channelName ?: "TwitchUnblock" })
            .setArtist(channelName)
            .apply { avatar?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = if (Quality.isAuto(key) && master.text != null) {
            val data = Base64.encodeToString(master.text!!.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            MediaItem.Builder().setUri("data:application/vnd.apple.mpegurl;base64,$data")
                .setMimeType(MimeTypes.APPLICATION_M3U8).setMediaMetadata(metadata).build()
        } else {
            val url = master.links[key] ?: master.links.values.firstOrNull() ?: return
            MediaItem.Builder().setUri(url)
                .setMimeType(if (isClip) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(metadata).build()
        }
        applyMaxHeight()
        if (startMs > 0) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /** Qualité choisie dans le lecteur : retenue comme préférence. */
    fun selectQuality(key: String) {
        if (key == quality) return
        val pos = if (isLive) -1L else player.currentPosition
        play(key, pos)
        val pref = Quality.preferenceFor(key, master.links.keys, master.variants)
        if (pref != null && !NetworkStatus.isCellular && main) AppState.updateQualityPref(pref)
        Logger.info("PLAYER", "Qualité choisie", key)
    }

    /** Multi-live : hauteur maximale en qualité Auto (null : sans plafond). */
    fun setMaxHeight(height: Int?) {
        maxHeight = height
        applyMaxHeight()
    }

    private fun applyMaxHeight() {
        val h = maxHeight
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if (h != null) h * 16 / 9 + 1 else Int.MAX_VALUE, h ?: Int.MAX_VALUE)
            .build()
    }

    fun setVolume(volume: Float) {
        player.volume = volume
    }

    /** Multi-live : seule la tuile qui a le son gère le focus audio. */
    fun handleAudioFocus(on: Boolean) = PlayerFactory.setAudioFocus(player, on)

    /** Minuterie : pause dans `minutes` minutes (null : annulée). */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            sleepAt = null
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        sleepAt = at
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            sleepAt = null
            Logger.info("PLAYER", "Minuterie : lecture mise en pause", "$minutes min")
        }
    }

    private fun onError(error: PlaybackException) {
        errors++
        Logger.warn("PLAYER", "Erreur de lecture", "${error.errorCodeName} (${errors})")
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || errors <= 3) {
            // Direct trop loin derrière, ou coupure passagère : on reprend.
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(if (errors <= 1) 500L else 2000L)
                if (isLive) player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
            return
        }
        if (!isClip) Usage.count(if (isLive) "live_play_fail" else "vod_play_fail")
        state = State.Error(if (isOffline) "err_vod_unavailable" else "err_playback")
    }

    private suspend fun refreshStats(login: String) {
        while (scope.isActive) {
            delay(60_000)
            LiveApi.streamStats(login)?.let { (v, started) ->
                viewers = v
                if (started != null) startedAt = started
            }
        }
    }

    private suspend fun trackProgress(id: String) {
        while (scope.isActive) {
            delay(10_000)
            if (player.isPlaying) AppState.saveVodPosition(id, player.currentPosition, player.duration)
        }
    }

    private fun saveProgress() {
        val id = vodId
        if (id != null && state is State.Ready) {
            AppState.saveVodPosition(id, player.currentPosition, player.duration)
        }
    }

    fun release() {
        saveProgress()
        job?.cancel()
        tickJob?.cancel()
        sleepJob?.cancel()
        if (main) {
            PlayerHolder.detach(this)
            com.mxfia19.twitchunblock.drops.DropsHeartbeat.stop()
            com.mxfia19.twitchunblock.core.CloudSync.scheduleSoon()
        }
        player.release()
        scope.cancel()
    }

    companion object {
        fun errorKey(code: String): String = when (code) {
            "not_found" -> "not_found"
            "network" -> "err_network"
            "no_source" -> "err_no_source"
            "vod_unavailable" -> "err_vod_unavailable"
            "offline" -> "offline_msg"
            else -> code
        }
    }
}
