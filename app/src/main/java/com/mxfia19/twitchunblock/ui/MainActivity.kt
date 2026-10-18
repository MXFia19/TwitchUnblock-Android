package com.mxfia19.twitchunblock.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.tv.TvActivity
import kotlinx.coroutines.launch

fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature("android.software.leanback_only")
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
}
