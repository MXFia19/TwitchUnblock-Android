package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI

/**
 * Lancement d'une VOD : jeton officiel (usher), sinon les playlists du CDN
 * retrouvées par la miniature de prévisualisation (VODs réservées aux
 * abonnés), sinon le Worker. Comme getM3U8 (iOS). Les passages coupés
 * (« -unmuted.ts », refusés par le CDN) sont réécrits par le lecteur.
 */
object VodApi {
    suspend fun getVod(id: String): Pair<Master, String?> {
        Logger.info("M3U8", "Lancement VOD", id)
        if (id.isNotEmpty() && id.all { it.isDigit() }) {
            val token = LiveApi.accessToken(id, live = false)
            if (token != null) {
                val url = "https://usher.ttvnw.net/vod/$id.m3u8?nauth=${Http.encode(token.first)}" +
                    "&nauthsig=${Http.encode(token.second)}&allow_source=true&allow_audio_only=true" +
                    "&allow_spectre=true&player_backend=mediaplayer"
                val r = Http.get(url, Config.TWITCH_HEADERS, timeout = 8)
                if (r != null && r.code == 200) {
                    val m = parseMaster(r.body, url)
                    if (!m.isEmpty) {
                        Logger.success("M3U8", "[1/3] ${m.links.size} qualités (officiel)")
                        return Pair(m, null)
                    }
                }
                Logger.warn("M3U8", "[1/3] Échec du jeton officiel", "HTTP ${r?.code ?: "réseau"}")
            }
        }
        val sb = storyboard(id)
        if (sb.isNotEmpty()) {
            Logger.success("M3U8", "[2/3] ${sb.size} qualités (CDN)")
            return Pair(Master(sb, emptyMap(), synthesizeMaster(sb, emptyMap())), null)
        }
        val json = Workers.json("/api/get-m3u8?id=$id&proxy=false")
        val links = linksOf(json?.obj("links"))
        if (links.isNotEmpty()) {
            val variants = workerVariants(json?.obj("variants"))
            Logger.success("M3U8", "[3/3] ${links.size} qualités (Worker)")
            return Pair(Master(links, variants, synthesizeMaster(links, variants)), null)
        }
        Logger.error("M3U8", "Toutes les tentatives ont échoué", id)
        return Pair(Master(), "vod_unavailable")
    }

    /** Playlists retrouvées sur le CDN depuis l'adresse des miniatures de
     *  prévisualisation (`seekPreviewsURL`) : …/<dossier>/storyboards/… */
    private suspend fun storyboard(id: String): Map<String, String> = coroutineScope {
        val seek = Gql.raw("query { video(id: \"${Gql.str(id)}\") { seekPreviewsURL } }")
            ?.obj("data")?.obj("video")?.str("seekPreviewsURL")
        if (seek == null) {
            Logger.warn("STORYBOARD", "seekPreviewsURL absent", id)
            return@coroutineScope emptyMap()
        }
        val parts = seek.split("/")
        val index = parts.indexOf("storyboards")
        val host = try { URI(seek).host } catch (e: Exception) { null }
        if (index <= 0 || host == null) return@coroutineScope emptyMap()
        val root = "https://$host/${parts[index - 1]}"
        val qualities = listOf("chunked", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "audio_only")
        val found = qualities.map { q ->
            async {
                val url = "$root/$q/index-dvr.m3u8"
                if (Http.head(url, Config.TWITCH_HEADERS, timeout = 8) == 200) Pair(if (q == "chunked") "Source" else q, url) else null
            }
        }.awaitAll().filterNotNull()
        LinkedHashMap<String, String>().apply { found.forEach { put(it.first, it.second) } }
    }

    /** Titre, chaîne, durée d'une VOD (lien collé, reprise). */
    suspend fun meta(id: String): Vod? {
        val d = Gql.request("query(\$id: ID!) { video(id: \$id) { id title lengthSeconds viewCount publishedAt " +
                            "owner { login displayName } previewThumbnailURL(height: 180, width: 320) } }",
                            Gql.vars("id" to id)) ?: return null
        val v = d.obj("video") ?: return null
        return Vod(v.str("id") ?: id, v.str("title") ?: "", v.str("previewThumbnailURL") ?: "",
                   v.str("publishedAt") ?: "", v.int("lengthSeconds") ?: 0, v.int("viewCount") ?: 0)
    }
}
