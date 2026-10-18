package com.mxfia19.twitchunblock.player

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
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
import com.mxfia19.twitchunblock.core.VodApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Ce qu'on demande à lire. */
sealed class PlayRequest {
    data class Live(val login: String) : PlayRequest()
    data class Vod(val id: String, val title: String = "", val channel: String? = null, val startMs: Long = -1) : PlayRequest()
    data class Clip(val slug: String) : PlayRequest()
}

/**
 * Une lecture (téléphone et TV) : résolution des sources, choix de la
 * qualité (Auto = liste maîtresse, ExoPlayer suit le débit), reprise après
 * une erreur, position des VODs gardée. `scope` : fil principal.
 */
class PlaybackSession(context: Context, private val scope: CoroutineScope) {
    sealed class State {
        /** `step` : source en cours d'essai (« as », « al », « eu », « twitch », « worker »). */
        data class Loading(val step: String? = null) : State()
        /** `key` : clé du texte de l'erreur. */
        data class Error(val key: String) : State()
        object Ready : State()
    }

    val player: ExoPlayer = PlayerFactory.build(context)

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

    private var master = Master()
    private var isClip = false
    private var job: Job? = null
    private var tickJob: Job? = null
    private var errors = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errors = 0
            }
        })
    }

    val isLive: Boolean get() = request is PlayRequest.Live

    fun open(req: PlayRequest) {
        saveProgress()
        job?.cancel()
        tickJob?.cancel()
        request = req
        state = State.Loading()
        errors = 0
        player.stop()
        job = scope.launch { resolve(req) }
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
                    state = State.Error(errorKey(r.info.error))
                    return
                }
                isClip = false
                start(r.master, startMs = -1)
                tickJob = scope.launch { refreshStats(req.login) }
            }
            is PlayRequest.Vod -> {
                title = req.title
                channelLogin = req.channel
                channelName = req.channel
                game = ""
                source = null
                val (m, error) = VodApi.getVod(req.id)
                if (error != null) {
                    state = State.Error(errorKey(error))
                    return
                }
                if (title.isEmpty()) VodApi.meta(req.id)?.let { title = it.title }
                isClip = false
                val resume = if (req.startMs >= 0) req.startMs else AppState.vodPosition(req.id)
                start(m, resume)
                tickJob = scope.launch { trackProgress(req.id) }
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
        val key = Quality.launchKey(keys, AppState.qualityPref, AppState.cellularQuality,
                                    NetworkStatus.isCellular, m.variants)
        Logger.info("PLAYER", "Qualité au lancement", key + if (NetworkStatus.isCellular) " (données mobiles)" else "")
        play(key, startMs)
        state = State.Ready
    }

    private fun play(key: String, startMs: Long) {
        quality = key
        val item = if (Quality.isAuto(key) && master.text != null) {
            val data = Base64.encodeToString(master.text!!.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            MediaItem.Builder().setUri("data:application/vnd.apple.mpegurl;base64,$data")
                .setMimeType(MimeTypes.APPLICATION_M3U8).build()
        } else {
            val url = master.links[key] ?: master.links.values.firstOrNull() ?: return
            MediaItem.Builder().setUri(url)
                .setMimeType(if (isClip) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8).build()
        }
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
        if (pref != null && !NetworkStatus.isCellular) AppState.updateQualityPref(pref)
        Logger.info("PLAYER", "Qualité choisie", key)
    }

    private fun onError(error: PlaybackException) {
        errors++
        Logger.warn("PLAYER", "Erreur de lecture", "${error.errorCodeName} (${errors})")
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || errors <= 3) {
            // Direct trop loin derrière, ou coupure passagère : on reprend.
            scope.launch {
                delay(if (errors <= 1) 500L else 2000L)
                if (isLive) player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
            return
        }
        state = State.Error("err_playback")
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
        val req = request
        if (req is PlayRequest.Vod && state is State.Ready) {
            AppState.saveVodPosition(req.id, player.currentPosition, player.duration)
        }
    }

    fun release() {
        saveProgress()
        job?.cancel()
        tickJob?.cancel()
        player.release()
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
