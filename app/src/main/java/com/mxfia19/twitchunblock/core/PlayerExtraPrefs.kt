package com.mxfia19.twitchunblock.core

/**
 * Réglages du lecteur ajoutés en série 6. Mêmes valeurs par défaut que
 * l'app iPhone (Réglages → Lecteur).
 */
object PlayerExtraPrefs {
    /** Arrière-plan (écran verrouillé, autre app, sans image dans l'image) :
     *  son seul, beaucoup moins de données. Avec AppState.backgroundPlay ;
     *  l'iPhone en fait un choix à trois (bg_playback). */
    val bgAudioOnly = boolPref("bg_audio_only", false)

    /** Quitter le lecteur (Retour, glissé vers le bas, Réduire) l'ouvre en
     *  image dans l'image au lieu du mini-lecteur, si la vidéo joue. */
    val pipOnLeave = boolPref("pip_on_leave", false)

    /** Pastille du retard du direct, en secondes (« Afficher la latence »). */
    val showLatency = boolPref("show_latency", false)
}
