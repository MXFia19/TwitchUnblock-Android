package com.mxfia19.twitchunblock.core

import com.mxfia19.twitchunblock.web.WebSession

/**
 * « Sans compte Twitch » (Réglages → Général), pour qui ne se connecte
 * jamais : ce qui demande un compte ou invite à se connecter est masqué
 * (rappels du chat, des points et des prédictions, création de clip,
 * invitation des chaînes suivies, groupes Compte et Twitch des réglages).
 * Jamais en vigueur avec un compte de l'app ou une session web : personne
 * de connecté ne perd rien. Comme UICustomization.hideAccount (iOS).
 */
object NoAccount {
    val pref = boolPref("hide_account", false)

    /** Connecté (compte de l'app ou session web) : le réglage ne masque rien. */
    val signedIn: Boolean get() = AppState.token != null || WebSession.token != null

    /** Masquer ce qui demande un compte. */
    val active: Boolean get() = pref.value && !signedIn
}
