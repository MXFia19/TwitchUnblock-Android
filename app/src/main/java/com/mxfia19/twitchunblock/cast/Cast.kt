package com.mxfia19.twitchunblock.cast

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.mxfia19.twitchunblock.core.Logger

/**
 * Options du Cast (déclarées dans le manifeste) : le récepteur par défaut de
 * Google. Ni notification ni session média à lui : celles du lecteur restent
 * les seules (PlaybackService).
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Diffusion d'un direct sur un Chromecast (Google TV, enceinte avec écran…).
 * Le récepteur lit lui-même la playlist d'UNE qualité : celles des directs
 * (playlist.ttvnw.net) et leurs segments sont ouverts à tous (CORS « * »),
 * quelle que soit la source ; la liste maîtresse d'usher ne l'est qu'à
 * twitch.tv, et les VODs (CloudFront) aussi : pas de VOD, ni de qualité
 * Auto. Sans les services Google Play, rien ne s'affiche que le message.
 */
object CastManager {
    /** Ce qu'on envoie : la playlist d'une qualité et de quoi l'afficher. */
    class Item(val url: String, val title: String, val subtitle: String?, val image: String?)

    /** null : pas encore essayé ; faux : Cast indisponible (services Google Play). */
    var available by mutableStateOf<Boolean?>(null)
        private set
    var pickerOpen by mutableStateOf(false)
        private set
    /** Appareils trouvés sur le réseau. */
    var routes by mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList())
        private set
    /** Appareil en cours de diffusion, null sans. */
    var device by mutableStateOf<String?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    /** L'appareil n'a pas pu lire le flux. */
    var failed by mutableStateOf(false)
        private set
    var remotePlaying by mutableStateOf(false)
        private set

    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var session: CastSession? = null
    private var loadedUrl: String? = null
    private var pending: Item? = null
    /** Lecture du téléphone à reprendre quand la diffusion s'arrête. */
    private var onEnded: (() -> Unit)? = null

    private fun context(ctx: Context): CastContext? {
        cast?.let { return it }
        if (available == false) return null
        return try {
            @Suppress("DEPRECATION")
            val c = CastContext.getSharedInstance(ctx.applicationContext)
            cast = c
            available = true
            c.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            // Diffusion encore en cours (app relancée) : reprise.
            c.sessionManager.currentCastSession?.let { attach(it) }
            c
        } catch (e: Exception) {
            Logger.warn("CAST", "Cast indisponible", e.message)
            available = false
            null
        }
    }

    /** Lecteur à l'écran : sa lecture reprend à la fin de la diffusion. */
    fun bind(ctx: Context, resume: () -> Unit) {
        onEnded = resume
        // Seulement si le Cast a déjà servi : pas de services Google Play réveillés pour rien.
        if (cast == null && available == null && device == null) return
        context(ctx)
    }

    fun unbind() {
        onEnded = null
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    /** Liste des appareils : la recherche dure tant qu'elle est ouverte. */
    fun openPicker(ctx: Context) {
        pickerOpen = true
        failed = false
        val c = context(ctx) ?: return
        val sel = c.mergedSelector ?: return
        selector = sel
        val r = MediaRouter.getInstance(ctx.applicationContext)
        router = r
        r.addCallback(sel, routerCallback,
                      MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun closePicker() {
        pickerOpen = false
        router?.removeCallback(routerCallback)
    }

    private fun refresh() {
        val r = router ?: return
        val sel = selector ?: return
        routes = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
    }

    /** Appareil choisi : la session Cast démarre (listener), puis `item` y est lu. */
    fun select(route: MediaRouter.RouteInfo, item: Item) {
        pending = item
        loadedUrl = null
        connecting = true
        Logger.info("CAST", "Connexion à l'appareil", route.name)
        route.select()
        closePicker()
    }

    /** Lit `item` sur l'appareil (une seule fois par adresse) : autre direct,
     *  autre qualité, la diffusion suit le lecteur. */
    fun load(item: Item) {
        val client = session?.remoteMediaClient
        if (client == null) {
            pending = item
            return
        }
        if (item.url == loadedUrl) return
        pending = null
        loadedUrl = item.url
        failed = false
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            item.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            item.image?.let { addImage(WebImage(Uri.parse(it))) }
        }
        // Segments MPEG-TS (H.264 + AAC) : à dire au récepteur, qui attend
        // sinon de l'audio seul dans une playlist HLS.
        val info = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("application/x-mpegurl")
            .setHlsSegmentFormat("ts")
            .setMetadata(meta)
            .build()
        Logger.info("CAST", "Direct envoyé à l'appareil", item.title)
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { r ->
                if (!r.status.isSuccess) {
                    failed = true
                    loadedUrl = null
                    Logger.warn("CAST", "Lecture refusée par l'appareil", r.status.statusCode.toString())
                }
            }
    }

    fun togglePlayback() {
        session?.remoteMediaClient?.togglePlayback()
    }

    /** Fin de la diffusion : la lecture reprend sur le téléphone (ended). */
    fun stop() {
        cast?.sessionManager?.endCurrentSession(true)
    }

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val c = session?.remoteMediaClient ?: return
            remotePlaying = c.isPlaying || c.isBuffering
            if (c.playerState == MediaStatus.PLAYER_STATE_IDLE && c.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                failed = true
                loadedUrl = null
                Logger.warn("CAST", "Erreur de lecture sur l'appareil")
            }
        }
    }

    private fun attach(s: CastSession) {
        if (session !== s) {
            session?.remoteMediaClient?.unregisterCallback(clientCallback)
            session = s
            s.remoteMediaClient?.registerCallback(clientCallback)
        }
        device = s.castDevice?.friendlyName ?: "Chromecast"
        connecting = false
        Logger.success("CAST", "Diffusion sur l'appareil", device)
        pending?.let { load(it) }
    }

    private fun ended() {
        session?.remoteMediaClient?.unregisterCallback(clientCallback)
        val was = device
        session = null
        device = null
        connecting = false
        loadedUrl = null
        pending = null
        remotePlaying = false
        if (was != null) {
            Logger.info("CAST", "Fin de la diffusion", was)
            onEnded?.invoke()
        }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            connecting = true
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            connecting = false
            failed = true
            pending = null
            Logger.warn("CAST", "Connexion à l'appareil refusée", error.toString())
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) = ended()

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)

        override fun onSessionResumeFailed(session: CastSession, error: Int) = ended()

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
