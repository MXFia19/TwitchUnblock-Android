package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.chat.ChatOptions
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.IntPref
import com.mxfia19.twitchunblock.core.Logger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Réglages du chat ajoutés depuis la série 5 (groupe Chat) : synchro avec
 *  la vidéo, zone d'envoi et suggestions du clavier, tailles avec aperçu,
 *  chat en paysage, bandeaux du direct (drops, Special Events, suivre,
 *  repliés en paysage), emotes masquées, couleur du pseudo. */
@Composable
fun ChatExtraSettings() {
    Toggle(AppState.t("cfg_chat_sync"), AppState.t("cfg_chat_sync_sub"), ChatPrefs.sync.value) {
        ChatPrefs.sync.set(it)
        Logger.info("CHAT", "Chat synchronisé avec la vidéo", if (it) "activé" else "désactivé")
    }
    if (ChatPrefs.sync.value) {
        PrefSlider(AppState.t("chat_sync_adjust"), ChatPrefs.syncNudge, AppState.t("chat_sync_adjust_sub")) { v ->
            if (v == 0) "0 s" else (if (v > 0) "+" else "−") + "${abs(v)} s"
        }
    }
    Toggle(AppState.t("cfg_chat_input"), AppState.t("cfg_chat_input_sub"), ChatPrefs.showInput.value) {
        ChatPrefs.showInput.set(it)
        Logger.info("CHAT", "Zone d'envoi du chat", if (it) "affichée" else "masquée")
    }
    // Coupées : le clavier ne corrige plus les emotes (« KEKW », « OMEGALUL »).
    Toggle(AppState.t("cfg_kbd_suggest"), AppState.t("c6_kbd_suggest_sub"), ChatOptions.keyboardSuggestions.value) {
        ChatOptions.keyboardSuggestions.set(it)
        Logger.info("CHAT", "Suggestions du clavier", if (it) "activées" else "désactivées")
    }

    // Bandeaux au-dessus du chat (série 6).
    SubTitle(AppState.t("cust_chat"))
    Toggle(AppState.t("cfg_drops_notice"), AppState.t("cfg_drops_notice_sub"), ChatOptions.dropsCard.value) {
        ChatOptions.dropsCard.set(it)
        Logger.info("CHAT", "Carte des drops de la chaîne", if (it) "affichée" else "masquée")
    }
    if (ChatOptions.dropsCard.value) {
        Toggle(AppState.t("cust_special_events"), AppState.t("cust_special_events_foot"), ChatOptions.specialEvents.value) {
            ChatOptions.specialEvents.set(it)
            Logger.info("CHAT", "Cartes Special Events", if (it) "affichées" else "masquées")
        }
    }
    Toggle(AppState.t("cfg_follow"), AppState.t("cfg_follow_sub"), ChatOptions.followButton.value) {
        ChatOptions.followButton.set(it)
        Logger.info("CHAT", "Bouton suivre au-dessus du chat", if (it) "affiché" else "masqué")
    }
    Toggle(AppState.t("cfg_land_notices"), AppState.t("cfg_land_notices_sub"), ChatOptions.landscapeNoticesHidden.value) {
        ChatOptions.landscapeNoticesHidden.set(it)
        Logger.info("CHAT", "Bandeaux repliés en paysage", if (it) "oui" else "non")
    }

    // Tailles : un aperçu en direct évite l'aller-retour entre réglages et chat.
    SubTitle(AppState.t("chat_sizing"))
    ChatSizePreview()
    PrefSlider(AppState.t("cfg_msg_spacing"), ChatPrefs.spacing) { "$it dp" }
    PrefSlider(AppState.t("cfg_badge_scale"), ChatPrefs.badgeScale, step = 5) { scaleLabel(it) }
    PrefSlider(AppState.t("cfg_emote_scale"), ChatPrefs.emoteScale, step = 5) { scaleLabel(it) }
    Choice(AppState.t("cfg_time_format"), listOf("auto" to AppState.t("time_auto"), "12" to "12 h", "24" to "24 h"),
           ChatPrefs.timeFormat.value, AppState.t("cfg_time_format_sub")) { ChatPrefs.timeFormat.set(it) }

    // Paysage : largeur (aussi à la poignée du chat) et place sur l'image.
    PrefSlider(AppState.t("cfg_chat_width"), ChatPrefs.widthPercent, AppState.t("cfg_chat_width_sub")) { "$it %" }
    Choice(AppState.t("cfg_chat_overlay_pos"), overlayChoices(), ChatPrefs.overlay.value, AppState.t("cfg_chat_overlay_pos_sub")) {
        ChatPrefs.overlay.set(it)
    }
    LinkRow(AppState.t("reset_defaults")) {
        Features.chatFontSize.set(14)
        Features.chatTimestamps.set(true)
        ChatPrefs.spacing.set(6)
        ChatPrefs.badgeScale.set(100)
        ChatPrefs.emoteScale.set(100)
        ChatPrefs.widthPercent.set(32)
        ChatPrefs.overlay.set("right-full")
        Logger.info("CHAT", "Tailles du chat", "valeurs par défaut")
    }

    // ChatEmotesUi.kt et ChatBars.kt
    HiddenEmotesRow()
    UsernameColorRow()
}

@Composable
private fun SubTitle(text: String) {
    Text(text, color = TU.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 2.dp))
}

/** Curseur d'un nombre des réglages, par pas de `step`, sa valeur dans le titre. */
@Composable
private fun PrefSlider(title: String, pref: IntPref, subtitle: String? = null, step: Int = 1, label: (Int) -> String) {
    val r = pref.range
    Column(Modifier.padding(12.dp)) {
        Text(title + " · " + label(pref.value), color = TU.text, fontSize = 15.sp)
        if (subtitle != null) Text(subtitle, color = TU.muted, fontSize = 12.sp)
        Slider(
            value = pref.value.toFloat(),
            onValueChange = { pref.set((it / step).roundToInt() * step) },
            valueRange = r.first.toFloat()..r.last.toFloat(),
            steps = (r.last - r.first) / step - 1,
            colors = SliderDefaults.colors(thumbColor = TU.primary, activeTrackColor = TU.primary),
        )
    }
}

/** « 1.25× » : facteur des badges et des emotes. */
private fun scaleLabel(percent: Int): String = String.format(Locale.US, "%.2f×", percent / 100f)

/** Chat posé sur l'image : « Droite · Toute la hauteur »… (forme « right-full »). */
private fun overlayChoices(): List<Pair<String, String>> {
    val sides = listOf("left" to "chat_side_left", "right" to "chat_side_right")
    val heights = listOf("full" to "chat_height_full", "top" to "chat_height_top", "bottom" to "chat_height_bottom")
    return sides.flatMap { (side, sideKey) ->
        heights.map { (height, heightKey) -> "$side-$height" to "${AppState.t(sideKey)} · ${AppState.t(heightKey)}" }
    }
}
