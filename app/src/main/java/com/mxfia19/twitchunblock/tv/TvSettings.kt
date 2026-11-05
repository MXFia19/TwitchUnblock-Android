package com.mxfia19.twitchunblock.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.stringPref
import com.mxfia19.twitchunblock.ui.Choice
import com.mxfia19.twitchunblock.ui.Group
import com.mxfia19.twitchunblock.ui.LinkRow
import com.mxfia19.twitchunblock.ui.TU
import com.mxfia19.twitchunblock.ui.focusCard

/** Accueil de la TV : place de la rangée « Reprendre » (top : au-dessus des
 *  directs, bottom : dessous, hidden : masquée), comme l'Apple TV. */
object TvHomePrefs {
    private val place = stringPref("tv_resume_place", "top")

    val resumePlace: String get() = place.value.takeIf { it in listOf("top", "bottom", "hidden") } ?: "top"

    fun setResumePlace(p: String) = place.set(p)
}

/** Compte (série 6) : chat du lecteur par défaut, place de « Reprendre »,
 *  chaînes et catégories masquées à réafficher. */
@Composable
fun TvMoreSettings() {
    Group(AppState.t("tv_chat")) {
        Choice(AppState.t("tv_chat_default"), listOf("on" to AppState.t("tv_chat_on"), "off" to AppState.t("tv_chat_off")),
               if (AppState.showChat) "on" else "off") { AppState.updateShowChat(it == "on") }
        val place = TvChatPrefs.place
        Choice(AppState.t("tv_chat_side"), listOf("left" to AppState.t("tv_chat_left"), "right" to AppState.t("tv_chat_right")),
               place.side) { TvChatPrefs.setDefaultPlace(place.copy(side = it)) }
        Choice(AppState.t("tv_chat_height"),
               listOf("full" to AppState.t("tv_chat_full"), "top" to AppState.t("tv_chat_top"), "bottom" to AppState.t("tv_chat_bottom")),
               place.height) { TvChatPrefs.setDefaultPlace(place.copy(height = it)) }
        val own = TvChatPrefs.ownCount
        if (own > 0) LinkRow(AppState.t("tv_chat_forget") + " ($own)") { TvChatPrefs.forgetAll() }
        Text(AppState.t("tv_chat_layout_hint"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
    Group(AppState.t("tab_home")) {
        Choice(AppState.t("tv_resume_place"),
               listOf("top" to AppState.t("tv_resume_top"), "bottom" to AppState.t("tv_resume_bottom"),
                      "hidden" to AppState.t("tv_resume_hidden")),
               TvHomePrefs.resumePlace) { TvHomePrefs.setResumePlace(it) }
    }
    HiddenGroup()
}

/** Chaînes et catégories masquées (appui long sur une carte du top ou d'une
 *  catégorie) : un appui les réaffiche. */
@Composable
private fun HiddenGroup() {
    val focus = LocalFocusManager.current
    // La ligne réaffichée disparaît : le focus passe d'abord à une voisine.
    fun leave() {
        if (!focus.moveFocus(FocusDirection.Down)) focus.moveFocus(FocusDirection.Up)
    }
    Group(AppState.t("hidden_title")) {
        Text(AppState.t("hidden_sub"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        if (Hidden.isEmpty) {
            Text(AppState.t("tv_hidden_hint"), color = TU.muted, fontSize = 13.sp,
                 modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        } else {
            HiddenList(::leave)
        }
    }
}

@Composable
private fun HiddenList(leave: () -> Unit) {
    if (Hidden.channels.isNotEmpty()) {
        Label(AppState.t("hidden_channels_n").replace("{n}", "${Hidden.channels.size}"))
        Hidden.channels.toList().forEach { c ->
            UnhideRow(c.name) {
                leave()
                Hidden.unhideChannel(c.login)
            }
        }
    }
    if (Hidden.categories.isNotEmpty()) {
        Label(AppState.t("hidden_categories_n").replace("{n}", "${Hidden.categories.size}"))
        Hidden.categories.toList().forEach { c ->
            UnhideRow(c.name) {
                leave()
                Hidden.unhideCategory(c.id)
            }
        }
    }
    val focus = LocalFocusManager.current
    LinkRow(AppState.t("unhide_all")) {
        focus.moveFocus(FocusDirection.Up)
        Hidden.channels.map { it.login }.forEach { Hidden.unhideChannel(it) }
        Hidden.categories.map { it.id }.forEach { Hidden.unhideCategory(it) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun UnhideRow(name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusCard(onClick, RoundedCornerShape(0.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = TU.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(AppState.t("unhide"), color = TU.primary, fontSize = 14.sp)
    }
}
