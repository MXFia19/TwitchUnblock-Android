package com.mxfia19.twitchunblock.ui

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.News
import com.mxfia19.twitchunblock.core.Updates
import com.mxfia19.twitchunblock.core.Usage
import com.mxfia19.twitchunblock.feedback.Feedback
import com.mxfia19.twitchunblock.notify.Ntfy
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlayerHolder
import com.mxfia19.twitchunblock.tv.TvActivity
import kotlinx.coroutines.launch

fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature("android.software.leanback_only")
}

/** Image dans l'image en cours : le lecteur n'affiche que l'image. */
object PipState {
    var active by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private val navigator = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lancée sur une TV (lanceur qui ignore la catégorie TV) : l'interface TV.
        if (isTelevision(this)) {
            startActivity(Intent(this, TvActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        lifecycleScope.launch { Auth.restore() }
        handleLink(intent)
        setContent {
            TUTheme {
                val nav = remember { navigator }
                PhoneApp(nav)
            }
        }
    }

    /** À chaque retour dans l'app : mise à jour, comptage, annonce,
     *  réponses aux signalements, abonnement ntfy. */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            Updates.check()
            News.refresh()
            Feedback.refresh()
            if (Ntfy.enabled) Ntfy.sync()
            Usage.ping(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** twitchunblock://live/<chaîne> (notification touchée). */
    private fun handleLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.host == "live") {
            val login = uri.pathSegments.firstOrNull()?.lowercase() ?: return
            navigator.play(PlayRequest.Live(login))
        }
    }

    // ── Image dans l'image ──
    /** Une lecture est à l'écran et joue : quitter l'app la passe en image dans l'image. */
    private fun pipWanted(): Boolean {
        val session = PlayerHolder.current ?: return false
        return AppState.autoPip && session.player.playWhenReady && navigator.current is Screen.Player
    }

    /** À appeler quand la lecture démarre ou s'arrête (Android 12 et plus :
     *  passage automatique en image dans l'image). */
    fun updatePip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            setPictureInPictureParams(pipParams())
        } catch (e: Exception) {
            // Image dans l'image coupée par le système pour l'app.
        }
    }

    @RequiresApi(26)
    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipWanted())
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Avant Android 12 : passage manuel en quittant l'app.
        if (Build.VERSION.SDK_INT in 26..30 && pipWanted()) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: Exception) {
                // Refusé par le système.
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.active = isInPictureInPictureMode
        // Fenêtre fermée (glissée hors de l'écran) : l'activité reste arrêtée,
        // la lecture s'arrête aussi.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            PlayerHolder.current?.player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Lecture en arrière-plan coupée dans les réglages : pause en quittant
        // l'app (sauf image dans l'image).
        if (!AppState.backgroundPlay && !isInPictureInPictureMode && !isChangingConfigurations) {
            PlayerHolder.current?.player?.pause()
        }
    }
}
