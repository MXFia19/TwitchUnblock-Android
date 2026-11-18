package com.mxfia19.twitchunblock.core

/**
 * Réglages du lecteur (série 5) : vitesse des VODs, remplir l'écran,
 * compresseur audio. Mêmes valeurs par défaut que l'app iPhone et le site.
 */
object PlayerPrefs {
    /** Vitesse des VODs en centièmes (25 = 0,25×, 200 = 2×), gardée d'une
     *  VOD à l'autre ; sert aussi à rattraper un direct reculé. Même réglage
     *  que l'iPhone et le site (« vodSpeed » dans la sauvegarde). */
    val vodSpeed = intPref("vod_speed", 100, 25..200)

    /** Paysage : l'image remplit l'écran (haut et bas coupés). */
    val fillScreen = boolPref("fill_screen", false)

    /** Compresseur audio (comme celui du site) : pics rabotés, passages calmes remontés. */
    val audioCompressor = boolPref("audio_compressor", false)

    /** Volume (moitié droite) et luminosité (moitié gauche) en glissant
     *  verticalement dans le lecteur, en paysage seulement (série 7,
     *  PlayerGestures). Allumé par défaut : aucun geste vertical n'existait en
     *  paysage. « android_swipeLevels » dans la sauvegarde. */
    val swipeLevels = boolPref("swipe_levels", true)

    fun setCompressor(on: Boolean) {
        audioCompressor.set(on)
        com.mxfia19.twitchunblock.player.AudioCompressor.enabled = on
        Logger.info("PLAYER", "Compresseur audio", if (on) "activé" else "désactivé")
    }
}
