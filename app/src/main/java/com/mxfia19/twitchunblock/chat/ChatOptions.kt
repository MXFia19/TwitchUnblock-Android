package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.boolPref

/**
 * Réglages du chat de la série 6 (Réglages → Chat). Mêmes clés et valeurs
 * par défaut que l'app iPhone.
 */
object ChatOptions {
    /** Suggestions et correction automatique du clavier dans le champ du
     *  chat : allumées, à couper si le clavier corrige les emotes. */
    val keyboardSuggestions = boolPref("cfg_chat_kbd_suggest", true)
    /** Carte des drops de la chaîne en haut du chat. */
    val dropsCard = boolPref("cfg_drops_notice", true)
    /** Campagnes « Special Events » de Twitch (cartes comme les drops). */
    val specialEvents = boolPref("cfg_special_events", true)
    /** Bouton suivre / ne plus suivre au-dessus du chat (lecteur). */
    val followButton = boolPref("cfg_follow", true)
    /** Paysage : bandeaux du direct repliés en une ligne d'icônes. */
    val landscapeNoticesHidden = boolPref("cfg_land_notices_hidden", false)
}
