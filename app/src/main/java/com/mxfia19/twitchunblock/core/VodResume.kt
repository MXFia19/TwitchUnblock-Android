package com.mxfia19.twitchunblock.core

/**
 * « Reprendre les VODs » (Réglages → Général), comme vodProgress du site :
 * coupé, aucune position n'est gardée ni montrée sur cet appareil
 * (« Continuer à regarder » vide, les VODs partent du début) et la synchro
 * n'en envoie plus d'ici ; le compte garde les siennes. Rallumé, elles
 * reviennent du compte au prochain échange.
 */
object VodResume {
    private val pref = boolPref("vod_progress_on", true)

    val enabled: Boolean get() = pref.value

    fun set(on: Boolean) {
        if (on == pref.value) return
        pref.set(on)
        if (on) {
            CloudSync.forgetProgressBase()
            CloudSync.syncNow()
        } else {
            AppState.clearVodPositions()
        }
        Logger.info("SETTINGS", "Reprendre les VODs", if (on) "oui" else "non")
    }
}
