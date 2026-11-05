package com.mxfia19.twitchunblock.core

import java.net.URI

/** Blocage par pays vu pendant getLive (jetons, usher, Worker) : sa raison. */
class GeoBlockFlag {
    @Volatile var reason: String? = null

    fun note(reason: String) {
        if (this.reason == null) {
            this.reason = reason
            Logger.warn("LIVE", "Twitch : direct bloqué dans ce pays", reason)
        }
    }
}

/**
 * Ce que les sources du direct disent en plus des qualités : blocage par
 * pays, image verticale (Dual Format), Ma source en tête. Comme TwitchAPI
 * (iOS) : tokenGeoBlock, usherGeoBlocked, portraitLive, parsePortraitMaster.
 */
object StreamApi {
    /** Ordre des sources : Ma source en tête quand elle est réglée (comme
     *  l'ordre par défaut de l'iPhone et du site). */
    fun steps(base: List<String>): List<String> =
        if (StreamPrefs.customSourceHost() != null) listOf("custom") + base.filter { it != "custom" } else base

    /**
     * Raison d'un blocage par pays écrite par Twitch dans un jeton de lecture
     * (`geoblock_reason`, ou une lecture refusée dont la raison parle de pays) ;
     * null sinon. Le jeton vaut pour l'adresse qui l'a demandé : celui du
     * téléphone dit si SON pays est bloqué (droits de diffusion d'un événement).
     */
    fun tokenGeoBlock(value: String): String? {
        val flags = parseJson(value) ?: return null
        flags.str("geoblock_reason")?.takeIf { it.isNotEmpty() }?.let { return it }
        val auth = flags.obj("authorization")
        val reason = auth?.str("reason")?.lowercase()
        if (auth?.bool("forbidden") == true && reason != null && (reason.contains("geo") || reason.contains("region"))) {
            return reason
        }
        return null
    }

    /**
     * Refus d'usher pour cause de pays :
     * `[{"url":"…","error":"Content Restricted In Region","error_code":"content_geoblocked",…}]`.
     * Seuls `error_code` et `error` comptent : `url` recopie l'adresse demandée,
     * jeton compris, et le jeton contient toujours `geoblock_reason` (même vide).
     */
    fun usherGeoBlocked(body: String): Boolean {
        val entries = parseJsonArray(body)?.objects() ?: listOfNotNull(parseJson(body))
        return entries.any { e ->
            e.str("error_code")?.lowercase()?.contains("geo") == true ||
                e.str("error")?.lowercase()?.contains("restricted in region") == true
        }
    }

    /** Refus du Worker : `{"error":"…","code":"geo_blocked"}` (404). */
    fun workerGeoBlocked(body: String): Boolean = parseJson(body)?.str("code") == "geo_blocked"

    /**
     * Image verticale d'un direct en « Dual Format » (le streamer envoie aussi
     * une image 9:16). Seule la liste maîtresse v2 d'usher la donne, avec
     * `multigroup_video` ; la v1 (proxys, flux officiel), jamais. Jeton
     * anonyme : cette image vient de Twitch, pauses pub comprises. null : pas
     * d'image verticale, ou Twitch injoignable. Cherchée une fois le direct
     * parti, jamais sur le chemin du lancement.
     */
    suspend fun portraitLive(login: String, lowLatency: Boolean): Master? {
        if (login.isEmpty() || !login.all { it.isLetterOrDigit() || it == '_' }) return null
        val token = LiveApi.accessToken(login, live = true) ?: return null
        val url = "https://usher.ttvnw.net/api/v2/channel/hls/$login.m3u8" +
            "?allow_source=true&allow_audio_only=true&fast_bread=$lowLatency&multigroup_video=true" +
            "&platform=web&player_backend=mediaplayer&playlist_include_framerate=true" +
            "&p=${(0 until 999_999).random()}" +
            "&sig=${Http.encode(token.second)}&token=${Http.encode(token.first)}"
        val r = Http.get(url, timeout = 6) ?: return null
        if (r.code != 200) return null
        val m = parsePortraitMaster(r.body, url)
        if (m.variants.values.none { it.height > 0 }) return null
        Logger.success("LIVE", "Image verticale disponible (Dual Format)", "${m.links.size} qualités")
        return m
    }

    /**
     * Variantes « portrait » d'une liste maîtresse v2 (`IVS-GROUPS="portrait"`),
     * nommées comme les autres (« 720p60 », IVS-NAME), avec pour hauteur le
     * petit côté (720 pour 720x1280) : la qualité préférée s'y retrouve, et
     * rien ne passe pour du 1440p. L'audio seul (« landscape,portrait ») est
     * gardé. `text` : liste maîtresse des seules variantes vidéo verticales
     * (qualité Auto).
     */
    fun parsePortraitMaster(content: String, baseUrl: String? = null): Master {
        val lines = content.split("\n").map { it.trim() }
        val links = LinkedHashMap<String, String>()
        val variants = LinkedHashMap<String, Variant>()
        val auto = StringBuilder("#EXTM3U\n")
        // Groupes déclarés à part (rare en v2) : gardés pour la qualité Auto.
        lines.filter { it.startsWith("#EXT-X-MEDIA:") }.forEach { auto.append(it).append('\n') }
        var videos = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val groups = (quoted(line, "IVS-GROUPS") ?: "").lowercase().split(',')
            if ("portrait" !in groups) continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (next.isEmpty() || next.startsWith("#")) continue
            val name = quoted(line, "IVS-NAME") ?: quoted(line, "STABLE-VARIANT-ID")?.replace("-portrait", "") ?: continue
            val url = resolve(baseUrl, next)
            val sides = bare(line, "RESOLUTION")?.split('x')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            links[name] = url
            variants[name] = Variant(
                height = if (sides.size == 2) sides.minOrNull() ?: 0 else 0,
                fps = bare(line, "FRAME-RATE")?.toDoubleOrNull() ?: 0.0,
                bandwidth = bare(line, "BANDWIDTH")?.toIntOrNull() ?: 0,
            )
            if (sides.size == 2) {
                auto.append(line).append('\n').append(url).append('\n')
                videos++
            }
        }
        return Master(links, variants, if (videos > 0) auto.toString() else null)
    }

    private fun quoted(line: String, name: String): String? =
        Regex("[:,]" + Regex.escape(name) + "=\"([^\"]*)\"").find(line)?.groupValues?.get(1)

    private fun bare(line: String, name: String): String? =
        Regex("[:,]" + Regex.escape(name) + "=([^,\"\\s]+)").find(line)?.groupValues?.get(1)

    private fun resolve(base: String?, ref: String): String {
        if (ref.startsWith("http") || base == null) return ref
        return try { URI(base).resolve(ref).toString() } catch (e: Exception) { ref }
    }
}
