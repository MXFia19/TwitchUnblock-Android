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

    /** Source « Bulgarie » (bg, en test depuis le 18/11) : le relais
     *  TwitchUnblock d'un VPS à Sofia (LIVE_SOURCE=twitch, README du site)
     *  prend le jeton, la liste maîtresse et les playlists de la vidéo depuis
     *  la Bulgarie (par son IPv6 : Twitch situe son IPv4 en Allemagne), les
     *  segments restant en direct. Comme l'iPhone et le site. */
    const val BULGARIA_RELAY = "https://2-58-32-226.sslip.io"
    /** Proxys retirés le 18/11 (Luminous, PerfProd, nadeko/TTV.LOL) : ils
     *  laissaient passer des centaines de pubs par jour. Un ordre rangé qui en
     *  contient encore reprend l'ordre automatique (Ma source gardée). */
    val RETIRED_LIVE_SOURCES = setOf("as", "pp", "al", "al2", "eu", "eu2")
    /** Source ajoutée → celle qu'elle secourt : elle est essayée juste après
     *  (comme l'app iPhone, le site et le Worker). Aucune aujourd'hui. */
    val LIVE_SOURCE_BACKUP_OF = emptyMap<String, String>()
    /** Sources du direct, dans l'ordre automatique ; le Worker toujours en dernier. */
    val LIVE_SOURCES = listOf("bg", "twitch")
    val LIVE_SOURCE_CHOICES = listOf("auto", "bg", "twitch")
    /** Source « Mon proxy » (série 7, MyProxy.kt) : proposée seulement quand
     *  un proxy est réglé, comme Ma source (« custom »). */
    const val MY_PROXY_SOURCE = "proxy"

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Safari/537.36"
    val TWITCH_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://www.twitch.tv/",
        "Origin" to "https://www.twitch.tv",
    )

    const val DISCORD_URL = "https://discord.gg/cEsMRdxsVq"
    const val REPO = "MXFia19/TwitchUnblock-Android"
}
