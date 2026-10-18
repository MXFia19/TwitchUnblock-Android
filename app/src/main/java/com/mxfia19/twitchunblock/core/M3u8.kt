package com.mxfia19.twitchunblock.core

import java.net.URI

// Lecture d'une liste maîtresse HLS, comme parseMaster (iOS) : mêmes noms de
// qualités (« Source », « 720p60 », « audio_only »…).

/** Valeur d'un attribut entre guillemets (`,NOM="valeur"`). */
private fun attr(line: String, name: String): String? {
    for (sep in listOf(",", ":")) {
        val key = "$sep$name=\""
        val i = line.indexOf(key)
        if (i < 0) continue
        val start = i + key.length
        val end = line.indexOf('"', start)
        if (end >= 0) return line.substring(start, end)
    }
    return null
}

/** Valeur sans guillemets (`,RESOLUTION=1920x1080`). */
private fun plain(line: String, name: String): String? =
    Regex("[:,]" + Regex.escape(name) + "=[^,\"\\s]+").find(line)?.value?.substringAfter("=")

private fun resolve(base: String?, ref: String): String {
    if (ref.startsWith("http")) return ref
    if (base == null) return ref
    return try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }
}

fun parseMaster(content: String, baseUrl: String? = null): Master {
    val lines = content.split("\n")
    // Groupes vidéo nommés « … (source) » : certains proxys appellent la
    // version d'origine « 1080p60 » plutôt que « chunked ».
    val sourceGroups = lines
        .filter { it.startsWith("#EXT-X-MEDIA") && it.lowercase().contains("(source)") }
        .mapNotNull { attr(it, "GROUP-ID") }
        .toSet()
    // Multi-encodage (format IVS) : « Source » seulement pour une variante
    // vidéo seule de son espèce.
    val isIvsSource = { l: String -> attr(l, "IVS-VARIANT-SOURCE") == "source" && l.contains("RESOLUTION=") }
    val singleIvs = lines.count { it.startsWith("#EXT-X-STREAM-INF") && isIvsSource(it) } == 1
    val links = LinkedHashMap<String, String>()
    val variants = LinkedHashMap<String, Variant>()
    for (i in lines.indices) {
        val line = lines[i].trim()
        if (!line.startsWith("#EXT-X-STREAM-INF")) continue
        var quality = "unknown"
        val name = attr(line, "VIDEO") ?: attr(line, "STABLE-VARIANT-ID") ?: attr(line, "IVS-NAME")
        if (name != null) {
            quality = if (name == "chunked" || name in sourceGroups || (singleIvs && isIvsSource(line))) "Source" else name
        } else {
            Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.let { quality = it.groupValues[1] }
        }
        val next = lines.getOrNull(i + 1)?.trim() ?: ""
        if (next.isNotEmpty() && !next.startsWith("#")) {
            links[quality] = resolve(baseUrl, next)
            val height = plain(line, "RESOLUTION")?.substringAfterLast('x')?.toIntOrNull() ?: 0
            variants[quality] = Variant(
                height = height,
                fps = plain(line, "FRAME-RATE")?.toDoubleOrNull() ?: 0.0,
                bandwidth = plain(line, "BANDWIDTH")?.toIntOrNull() ?: 0,
            )
        }
    }
    if (links.isEmpty()) return Master()
    // Le texte, adresses rendues absolues : lu par ExoPlayer depuis une
    // adresse data: (qualité Auto), sans redemander la liste à la source.
    val absolute = lines.joinToString("\n") { l ->
        val t = l.trim()
        if (t.isNotEmpty() && !t.startsWith("#")) resolve(baseUrl, t) else l
    }
    return Master(links, variants, absolute)
}

/** Liste maîtresse fabriquée depuis des liens (Worker, CDN des VODs) : la
 *  qualité Auto existe ainsi pour toutes les sources. Sans l'audio seul. */
fun synthesizeMaster(links: Map<String, String>, variants: Map<String, Variant>): String? {
    val video = links.filterKeys { !Quality.isAudio(it) && !Quality.isAuto(it) }
    if (video.isEmpty()) return null
    val sb = StringBuilder("#EXTM3U\n")
    for ((key, url) in video) {
        val h = Quality.height(key, links.keys, variants)
        val bw = variants[key]?.bandwidth?.takeIf { it > 0 } ?: when {
            h >= 1080 -> 6_000_000
            h >= 720 -> 3_000_000
            h >= 480 -> 1_400_000
            h >= 360 -> 700_000
            else -> 300_000
        }
        val res = if (h > 0) ",RESOLUTION=${h * 16 / 9}x$h" else ""
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$bw$res\n").append(url).append('\n')
    }
    return sb.toString()
}

/** `variants` d'une réponse du Worker ({ "720p60": { h, fps, bw } }). */
fun workerVariants(o: org.json.JSONObject?): Map<String, Variant> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, Variant>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = o.optJSONObject(k) ?: continue
        val h = v.int("h") ?: continue
        out[k] = Variant(h, v.dbl("fps") ?: 0.0, v.int("bw") ?: 0)
    }
    return out
}

/** Liens par qualité d'un objet JSON du Worker ({ "720p60": "https://…" }). */
fun linksOf(o: org.json.JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        o.str(k)?.let { out[k] = it }
    }
    return out
}
