package com.mxfia19.twitchunblock.core

/** Constantes partagées avec l'app iPhone (Constants.swift). */
object Config {
    /** Worker principal (base D1 : sauvegarde, retours, annonces…). */
    const val API_URL = "https://test2.kurzmathis4.workers.dev"
    /** Workers de secours (lives et VODs seulement) : le principal est limité
     *  à 100 000 requêtes par jour (« error code: 1027 » jusqu'à minuit UTC). */
    val API_FALLBACKS = listOf("https://test2-fallback.vxcraftmanpetit.workers.dev")

    const val HELIX_CLIENT_ID = "1e68ku2ehgzy5cy0di3xvfy82sxpf6"
    const val GQL_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    /** Client-ID public de l'app Android de Twitch : pages suivantes des
     *  listes seulement (sans jeton), la page 1 reste sur le web. */
    const val GQL_NEXT_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"

    /** Proxys au format Luminous (/live/<chaîne>) par source, le second en
     *  secours (sondés le 27/10 sur trois directs, sans pré-roll). Asie :
     *  Luminous (Ouzbékistan) puis PerfProd Asie (Maurice) ; Europe :
     *  Luminous Europe puis Europe 2 (eu3 ne répondait plus). */
    val ASIA_LIVE_HOSTS = listOf("as.luminous.dev", "lb-as.cdn-perfprod.com")
    val EUROPE_LIVE_HOSTS = listOf("eu.luminous.dev", "eu2.luminous.dev")
    /** Proxys au format TTV.LOL v1 (Albanie). */
    val TTVLOL_HOSTS = listOf("twitch-al.nadeko.net")
    /** Secours albanais au format Luminous : l'autre serveur de nadeko
     *  (twitch.zzls.xyz n'est qu'une redirection vers lui). */
    val ALBANIA_LIVE_HOSTS = listOf("twitch.nadeko.net")
    /** Sources du direct, dans l'ordre automatique ; le Worker toujours en dernier. */
    val LIVE_SOURCES = listOf("as", "al", "eu", "twitch")
    val LIVE_SOURCE_CHOICES = listOf("auto", "as", "al", "eu", "twitch")

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Safari/537.36"
    val TWITCH_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://www.twitch.tv/",
        "Origin" to "https://www.twitch.tv",
    )

    const val DISCORD_URL = "https://discord.gg/cEsMRdxsVq"
    const val REPO = "MXFia19/TwitchUnblock-Android"
}
