package com.mxfia19.twitchunblock.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.ui.TUTheme
import kotlinx.coroutines.launch

/** Touches de la télécommande pour le lecteur : ↑ (commandes masquées),
 *  Menu, Réglages ou Infos ouvrent le menu qualité et chat. */
object TvKeys {
    /** Lecteur à l'écran : appelé pour ouvrir son menu. */
    var onPlayerMenu: (() -> Unit)? = null
    /** Commandes du lecteur visibles (↑ leur sert alors). */
    @Volatile
    var controllerVisible = false
    /** Menu du lecteur ouvert : les touches vont au menu. */
    @Volatile
    var menuOpen = false
}

class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { Auth.restore() }
        setContent { TUTheme { TvApp() } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val menu = TvKeys.onPlayerMenu
        if (menu != null && !TvKeys.menuOpen && event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            val opens = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_INFO ||
                (code == KeyEvent.KEYCODE_DPAD_UP && !TvKeys.controllerVisible)
            if (opens) {
                menu()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
