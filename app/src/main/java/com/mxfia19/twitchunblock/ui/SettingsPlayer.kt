package com.mxfia19.twitchunblock.ui

import android.os.Build
import androidx.compose.runtime.Composable
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.PlayerExtraPrefs
import com.mxfia19.twitchunblock.core.PlayerPrefs

/** Réglages du lecteur : commandes, affichage et son (groupe Lecteur). */
@Composable
fun PlayerControlsSettings() {
    // Paysage : image jusqu'aux bords (haut et bas coupés). Comme l'iPhone.
    Toggle(AppState.t("fill_screen"), AppState.t("fill_screen_sub"), PlayerPrefs.fillScreen.value) {
        PlayerPrefs.fillScreen.set(it)
    }
    // Comme le site (menu du lecteur) : directs et VODs, multi-live compris.
    Toggle(AppState.t("pl_audio_comp"), AppState.t("pl_audio_comp_sub"), PlayerPrefs.audioCompressor.value) {
        PlayerPrefs.setCompressor(it)
    }
    // Série 6 : arrière-plan en son seul (avec « Lecture en arrière-plan »),
    // PiP en quittant le lecteur, pastille de la latence. Comme l'iPhone.
    if (AppState.backgroundPlay) {
        Toggle(AppState.t("p6_bg_audio_only"), AppState.t("p6_bg_audio_only_sub"), PlayerExtraPrefs.bgAudioOnly.value) {
            PlayerExtraPrefs.bgAudioOnly.set(it)
        }
    }
    if (Build.VERSION.SDK_INT >= 26) {
        Toggle(AppState.t("pip_on_leave"), AppState.t("p6_pip_on_leave_sub"), PlayerExtraPrefs.pipOnLeave.value) {
            PlayerExtraPrefs.pipOnLeave.set(it)
        }
    }
    Toggle(AppState.t("dbg_latency"), AppState.t("dbg_latency_sub"), PlayerExtraPrefs.showLatency.value) {
        PlayerExtraPrefs.showLatency.set(it)
    }
}
