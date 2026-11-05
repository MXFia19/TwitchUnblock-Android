package com.mxfia19.twitchunblock.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.ui.TU

// Télécommande (série 6) : touche Lecture sur les cartes, et menu d'appui
// long sur le bouton central (masquer, reprendre, retirer), comme l'Apple TV.

/** Touche Lecture (ou Lecture/Pause) sur une carte : elle lance ce que la
 *  carte montre, comme le bouton central. Appui consommé (bas et haut) : il
 *  ne part pas ailleurs. */
fun Modifier.onPlayKey(action: () -> Unit): Modifier = onKeyEvent { e ->
    val play = e.key == Key.MediaPlayPause || e.key == Key.MediaPlay
    if (play && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) action()
    play
}

/** Action d'un menu d'appui long. `removesCard` : la carte disparaît (masquée,
 *  retirée) ; le focus passe d'abord à une voisine. */
class TvAction(val label: String, val removesCard: Boolean = false, val run: () -> Unit)

class TvMenu(val title: String?, val actions: List<TvAction>)

/** Menus d'appui long d'un écran : celui ouvert, et l'action qui retire la
 *  carte, jouée une fois le menu refermé (TvMenuHost). */
class TvMenus {
    var shown by mutableStateOf<TvMenu?>(null)
    var pending by mutableStateOf<(() -> Unit)?>(null)

    fun open(title: String?, actions: List<TvAction>) {
        shown = TvMenu(title, actions)
    }
}

/** Direct du top ou d'une catégorie : masquer la chaîne, ou sa catégorie
 *  (ce qu'on suit ou épingle reste affiché, BrowseRules). */
fun TvMenus.hideStream(s: TwitchStream, withCategory: Boolean = true) {
    val actions = mutableListOf(TvAction(AppState.t("hide_channel"), removesCard = true) { Hidden.hideChannel(s.login, s.name) })
    if (withCategory && s.gameId.isNotEmpty() && s.game.isNotEmpty()) {
        actions += TvAction(AppState.t("hide_category").replace("{c}", s.game), removesCard = true) {
            Hidden.hideCategory(s.gameId, s.game, "")
        }
    }
    open(s.name, actions)
}

/** Catégorie du top : la masquer. */
fun TvMenus.hideCategory(c: Category) {
    open(c.name, listOf(TvAction(AppState.t("hide_this_category"), removesCard = true) {
        Hidden.hideCategory(c.id, c.name, c.art())
    }))
}

/** Focus passé à une voisine avant de retirer l'élément qui l'a : sinon plus
 *  rien ne l'a, et la télécommande ne pilote plus l'écran. */
fun FocusManager.leaveFocused() {
    listOf(FocusDirection.Right, FocusDirection.Left, FocusDirection.Down, FocusDirection.Up).firstOrNull { moveFocus(it) }
}

/** Fenêtre du menu ouvert, et l'action qui retire la carte une fois le menu
 *  refermé (le focus revenu sur la carte, puis passé à une voisine). */
@Composable
fun TvMenuHost(menus: TvMenus) {
    val focus = LocalFocusManager.current
    menus.shown?.let { menu ->
        TvActionDialog(menu, onDismiss = { menus.shown = null }) { action ->
            if (action.removesCard) menus.pending = action.run else action.run()
        }
    }
    val action = menus.pending
    LaunchedEffect(action) {
        if (action == null) return@LaunchedEffect
        withFrameNanos { }
        focus.leaveFocused()
        action()
        menus.pending = null
    }
}

/** Menu d'une carte : fenêtre centrée, focus sur la première action ; Retour
 *  la referme. */
@Composable
private fun TvActionDialog(menu: TvMenu, onDismiss: () -> Unit, onAction: (TvAction) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(
            Modifier.widthIn(min = 300.dp, max = 460.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0181818))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            menu.title?.takeIf { it.isNotEmpty() }?.let {
                Text(it, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                     modifier = Modifier.padding(bottom = 6.dp))
            }
            menu.actions.forEachIndexed { i, action ->
                TvButton(action.label, modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
                    onDismiss()
                    onAction(action)
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }   // fenêtre attachée
            runCatching { first.requestFocus() }
        }
    }
}
