package com.mxfia19.twitchunblock.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.ui.MainActivity

/**
 * Lecture principale du téléphone : celle que suivent la notification, l'écran
 * verrouillé, le casque et l'image dans l'image. Une seule à la fois (les
 * tuiles du multi-live n'en sont pas).
 */
object PlayerHolder {
    var current by mutableStateOf<PlaybackSession?>(null)
        private set
    private var controller: ListenableFuture<MediaController>? = null

    fun attach(context: Context, session: PlaybackSession) {
        current = session
        PlaybackService.instance?.setPlayer(session.player)
        if (controller == null) {
            // Se connecter au service le crée : il porte la session média.
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            controller = MediaController.Builder(context, token).buildAsync()
        }
    }

    fun detach(session: PlaybackSession) {
        if (current !== session) return
        current = null
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        PlaybackService.instance?.end()
    }
}

/** Service de la session média : notification de lecture et commandes du
 *  système (écran verrouillé, casque, image dans l'image). */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        PlayerHolder.current?.let { setPlayer(it.player) }
    }

    fun setPlayer(player: Player) {
        val s = session
        if (s == null) {
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session = MediaSession.Builder(this, player).setSessionActivity(open).build()
            Logger.info("PLAYER", "Session média créée")
        } else if (s.player !== player) {
            s.player = player
        }
    }

    /** Plus de lecture : la session et la notification disparaissent. */
    fun end() {
        session?.release()
        session = null
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App balayée des récentes : on s'arrête si rien ne joue. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) end()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }
}
