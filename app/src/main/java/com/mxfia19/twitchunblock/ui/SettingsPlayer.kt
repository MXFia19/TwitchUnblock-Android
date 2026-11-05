package com.mxfia19.twitchunblock.ui

import androidx.compose.runtime.Composable
import com.mxfia19.twitchunblock.core.AppState
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
}
