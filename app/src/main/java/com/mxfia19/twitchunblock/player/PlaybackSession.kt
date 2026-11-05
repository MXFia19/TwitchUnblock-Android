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
import com.mxfia19.twitchunblock.core.estimatedBandwidth
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
    /** Lectures ouvertes (open) : l'image verticale ne vaut que pour celle en cours. */
    var openCount by mutableStateOf(0)
        private set

    /** Pauses pub de la chaîne (StreamUi, bandeau de la TV) : créé au premier
     *  usage. Seule la lecture principale coupe le son (le multi-live gère le
     *  sien, la TV le laisse comme l'Apple TV). */
    val adBreaks by lazy { AdBreakWatcher(player, scope, managesAudio = main) }
    /** Image verticale (Dual Format) du direct en cours. */
    val vertical by lazy { VerticalFeed(this, scope) }

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

    /** Session web changée (autre compte, déconnexion, refus) pendant un
     *  direct : la progression des drops repart avec la nouvelle session (ou
     *  s'arrête), et un direct lu par le compte, signé pour l'ancienne, est
     *  relu (nouvelle session ou sources habituelles). Comme l'iPhone. */
    init {
        if (main) scope.launch {
            val web = com.mxfia19.twitchunblock.web.WebSession
            var last: Pair<String?, Boolean>? = null
            androidx.compose.runtime.snapshotFlow { web.token to web.expired }.collect { now ->
                val before = last
                last = now
                val login = channelLogin
                if (before == null || !isLive || login == null || state !is State.Ready) return@collect
                if (now.first != before.first && source == "account") retry()
                else com.mxfia19.twitchunblock.drops.DropsHeartbeat.start(login, pollSegments = source != "account")
            }
        }
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

    /** Débit d'une qualité (bits/s), annoncé ou estimé : taille d'un téléchargement. */
    fun bandwidth(key: String): Int = estimatedBandwidth(key, master.links.keys, master.variants)

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
        openCount++
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

    /** Chaîne de la lecture : ce qui venait d'une autre chaîne (avatar,
     *  identifiant, spectateurs, abonnement, début du direct) ne reste pas sous
     *  son titre ni dans la notification. Même chaîne : gardé (rembobinage). */
    private fun setChannel(login: String?, name: String?) {
        if (login == null || !login.equals(channelLogin, ignoreCase = true)) {
            avatar = null
            channelId = null
            viewers = 0
            subscriber = null
            startedAt = null
        }
        channelLogin = login
        channelName = name
    }

    private suspend fun resolve(req: PlayRequest) {
        when (req) {
            is PlayRequest.Live -> {
                val r = LiveApi.getLive(req.login) { step -> state = State.Loading(step) }
                setChannel(r.info.login, r.info.login)
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
                setChannel(req.channel, req.channel)
                game = ""
                source = null
                // Téléchargée : lue sur le téléphone, sans chercher de source
                // (pas une partie seulement : la VOD entière reste en ligne).
                val saved = Downloads.find(req.id)
                if (saved != null && saved.completed && !saved.isPart) {
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
                VodApi.metaWithOwner(req.id)?.let { (meta, owner) ->
                    if (title.isEmpty()) title = meta.title
                    thumbnail = meta.thumbnail
                    lengthSeconds = meta.lengthSeconds
                    com.mxfia19.twitchunblock.core.ContinueWatching.keep(meta)
                    // Lien collé ou numéro : la chaîne vient de la VOD (avatar,
                    // « Voir la chaîne », notification, historique, téléchargement).
                    if (owner != null) {
                        if (channelLogin == null) setChannel(owner.login, owner.name)
                        if (avatar == null && owner.login.equals(channelLogin, ignoreCase = true)) avatar = owner.avatar
                    }
                }
                isClip = false
                // Rembobinage : à l'instant visé sur la barre du direct, sinon près de la fin.
                val resume = when {
                    req.startMs >= 0 -> req.startMs
                    req.rewind -> -1L
                    else -> AppState.vodPosition(req.id)
                }
                start(m, resume)
                tickJob = scope.launch { trackProgress(req.id) }
                // Historique (et « Continuer à regarder ») : TV comprise ; le
                // multi-live ne lit que des directs.
                com.mxfia19.twitchunblock.core.History.addVod(req.id, title, thumbnail, channelLogin)
                if (req.rewind && req.startMs < 0) {
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
                val login = item.channel.takeIf { it.isNotEmpty() }
                setChannel(login, login)
                thumbnail = item.thumbnail
                lengthSeconds = item.lengthSeconds
                source = null
                isClip = false
                // Partie d'une VOD : lue depuis son début, sans toucher à la reprise de la VOD entière.
                start(Master(mapOf(item.quality.ifEmpty { "Source" } to item.uri), item.variants),
                      if (item.isPart) 0L else AppState.vodPosition(req.vodId))
                if (!item.isPart) tickJob = scope.launch { trackProgress(req.vodId) }
            }
            is PlayRequest.Recovered -> {
                val st = req.stream
                title = st.title
                setChannel(st.login, st.login)
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
                setChannel(c.broadcasterLogin, c.broadcasterName ?: c.broadcasterLogin)
                // Pas de source pour un clip : le menu qualité ne montre pas celle du direct précédent.
                source = null
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

    /** Liste en cours (image verticale : gardée pour le retour). */
    val currentMaster: Master get() = master

    /** Autre liste du même direct (image verticale, retour) : relancée comme un
     *  changement de qualité, sans chercher les sources. */
    fun swapMaster(m: Master, src: String?) {
        if (!isLive || m.isEmpty) return
        val held = liveHeldMs()
        source = src
        start(m, startMs = -1)
        held?.let { keepLiveDelay(it) }
        // Drops : un jeton anonyme ne compte pas, la pulsation relit les segments.
        val login = channelLogin
        if (main && login != null) com.mxfia19.twitchunblock.drops.DropsHeartbeat.start(login, pollSegments = src != "account")
    }

    /** Qualité choisie dans le lecteur : retenue comme préférence. `remember`
     *  faux : son seul en arrière-plan et son retour (série 6), pas retenus. */
    fun selectQuality(key: String, remember: Boolean = true) {
        if (key == quality) return
        val held = liveHeldMs()
        val pos = if (isLive) -1L else player.currentPosition
        play(key, pos)
        held?.let { keepLiveDelay(it) }
        val pref = Quality.preferenceFor(key, master.links.keys, master.variants)
        if (remember && pref != null && !NetworkStatus.isCellular && main) AppState.updateQualityPref(pref)
        Logger.info("PLAYER", "Qualité choisie", key)
    }

    /** Direct reculé (au-delà du retard normal, 3 s) : ce recul (ms), à garder
     *  au changement de qualité ou d'image ; null sinon. */
    private fun liveHeldMs(): Long? {
        if (!isLive || state !is State.Ready) return null
        return PlayerSpeed.liveBehindMs(player)?.takeIf { it > 3_000 }
    }

    /** Même recul sur le nouveau flux, dès que sa fenêtre est connue (avant
     *  le relevé du rattrapage) : relancé au point par défaut, il repartait au
     *  bord (l'iPhone garde le retard). Le saut passe par PlayerSpeed.onSeek,
     *  comme un recul à la barre. Abandonné après 10 s ou par open(). */
    private fun keepLiveDelay(behindMs: Long) {
        seekJob?.cancel()
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val edge = PlayerSpeed.liveEdgeMs(player) ?: return
                player.removeListener(this)
                player.seekTo(maxOf(0L, edge - behindMs))
            }
        }
        player.addListener(listener)
        seekJob = scope.launch {
            try {
                delay(10_000)
            } finally {
                player.removeListener(listener)
            }
        }
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
        if (id != null && state is State.Ready && !(isOffline && Downloads.find(id)?.isPart == true)) {
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
            "geo_blocked" -> "err_geo_blocked"
            "vod_unavailable" -> "err_vod_unavailable"
            "offline" -> "offline_msg"
            else -> code
        }
    }
}
