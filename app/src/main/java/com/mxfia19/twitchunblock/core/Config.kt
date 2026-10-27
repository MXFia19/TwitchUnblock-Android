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

    /** Proxys au format Luminous (/live/<chaîne>) : un serveur par source
     *  (sondés le 27/10 sur trois directs, sans pré-roll). as = Luminous Asie
     *  (Ouzbékistan), pp = PerfProd Asie (Maurice), al2 = l'autre serveur de
     *  nadeko (twitch.zzls.xyz n'est qu'une redirection vers lui), eu / eu2 =
     *  Luminous Europe et Europe 2 (eu3 ne répondait plus). */
    val LIVE_PROXY_HOSTS = mapOf(
        "as" to "as.luminous.dev",
        "pp" to "lb-as.cdn-perfprod.com",
        "al2" to "twitch.nadeko.net",
        "eu" to "eu.luminous.dev",
        "eu2" to "eu2.luminous.dev",
    )
    /** Proxys au format TTV.LOL v1 (source « al », Albanie). */
    val TTVLOL_HOSTS = listOf("twitch-al.nadeko.net")
    /** Source ajoutée → celle qu'elle secourt : elle est essayée juste après
     *  (comme l'app iPhone, le site et le Worker). */
    val LIVE_SOURCE_BACKUP_OF = mapOf("pp" to "as", "al2" to "al", "eu2" to "eu")
    /** Sources du direct, dans l'ordre automatique ; le Worker toujours en dernier. */
    val LIVE_SOURCES = listOf("as", "pp", "al", "al2", "eu", "eu2", "twitch")
    val LIVE_SOURCE_CHOICES = listOf("auto", "as", "pp", "al", "al2", "eu", "eu2", "twitch")

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Safari/537.36"
    val TWITCH_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://www.twitch.tv/",
        "Origin" to "https://www.twitch.tv",
    )

    const val DISCORD_URL = "https://discord.gg/cEsMRdxsVq"
    const val REPO = "MXFia19/TwitchUnblock-Android"
}
