package com.mxfia19.twitchunblock.player

import com.mxfia19.twitchunblock.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * VOD sans son chez Twitch (même sonde que silence.js sur le site et
 * VodSilence.swift sur l'iPhone).
 *
 * Certaines chaînes n'enregistrent qu'une piste audio vide dans leurs VODs
 * (sans doute la piste VOD d'OBS, activée sans source) : du silence d'un bout
 * à l'autre, sur twitch.tv aussi, et rien à récupérer sur le CDN. Pour le
 * dire au lieu de laisser croire à une panne, on lit le début de trois
 * segments de « audio seul », à 10, 50 et 90 % de la VOD (à côté des passages
 * coupés, muets exprès), et on mesure leurs trames audio : le silence encodé
 * tient en 6 octets par trame, le son en 300 à 700 (160 kb/s). Le 160p et le
 * 360p, à débit constant (128 et 170 octets par trame, son ou pas), ne disent
 * rien : « audio seul » d'abord, l'original (chunked) s'il n'y en a pas.
 * TS : trames ADTS ; fMP4 : tailles des échantillons (boîte trun), « audio
 * seul » seulement — l'original y mêle image et son. Toute lecture d'octet
 * est bornée : un segment tronqué ou bizarre donne « pas de verdict ».
 */
object VodSilence {
    enum class Verdict { SILENT, SOUND, UNKNOWN }

    /** Trame audio de cette taille ou moins : du silence (6 octets en AAC). */
    const val SILENT_FRAME_BYTES = 12
    /** Trames à lire au moins pour juger un segment. */
    private const val MIN_FRAMES = 40
    /** Début de segment lu : ~140 trames de son en TS « audio seul », toute la
     *  boîte moof en fMP4. L'original mêle l'image : il en faut bien plus. */
    private const val PROBE_BYTES = 64 * 1024
    private const val SOURCE_PROBE_BYTES = 1024 * 1024
    private val POINTS = doubleArrayOf(0.1, 0.5, 0.9)
    private val TARGET = Regex("""^(https://[^?#]+/)[^/?#]+/((?:index-dvr|highlight-\d+)\.m3u8)(?:[?#].*)?$""")
    private val MUTED_SEG = Regex("""-(un)?muted\.(ts|mp4)""")

    /** Playlists « audio seul » puis originale d'une VOD, d'après l'adresse
     *  d'une de ses qualités (…/<qualité>/index-dvr.m3u8, ou
     *  highlight-<id>.m3u8). Vide pour une autre adresse (fichier téléchargé…). */
    fun targets(raw: String): List<String> {
        val m = TARGET.matchEntire(raw) ?: return emptyList()
        return listOf("audio_only", "chunked").map { "${m.groupValues[1]}$it/${m.groupValues[2]}" }
    }

    data class Picked(val urls: List<String>, val fmp4: Boolean)

    /** Segments à sonder : à 10, 50 et 90 % de la playlist, à côté des
     *  passages coupés s'il le faut. `fmp4` : playlist à EXT-X-MAP. */
    fun segments(text: String, base: String): Picked {
        val lines = text.split('\n').map { it.trim() }
        val segs = ArrayList<String>()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXTINF") && i + 1 < lines.size && lines[i + 1].isNotEmpty() &&
                !lines[i + 1].startsWith("#")) segs.add(lines[i + 1])
        }
        fun plain(i: Int) = i >= 0 && i < segs.size && !MUTED_SEG.containsMatchIn(segs[i])
        val picked = sortedSetOf<Int>()
        if (segs.isNotEmpty()) {
            for (p in POINTS) {
                val at = (p * (segs.size - 1)).toInt()
                for (d in 0..5) {
                    if (plain(at + d)) { picked.add(at + d); break }
                    if (plain(at - d)) { picked.add(at - d); break }
                }
            }
        }
        val baseUri = try { URI(base) } catch (e: Exception) { null }
        val urls = picked.mapNotNull { i -> try { baseUri?.resolve(segs[i])?.toString() } catch (e: Exception) { null } }
        return Picked(urls, lines.any { it.startsWith("#EXT-X-MAP") })
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff

    /** Tailles des trames audio (en-tête ADTS ôté) d'un début de segment TS ;
     *  null sans piste AAC lisible. */
    fun tsAudioFrameSizes(b: ByteArray): List<Int>? {
        if (b.size < 376 || u8(b, 0) != 0x47) return null
        var pmt = -1
        var audio = -1
        var started = false
        val es = ByteArrayOutputStream(b.size / 2)
        var o = 0
        while (o + 188 <= b.size) {
            val packetEnd = o + 188
            if (u8(b, o) != 0x47) break
            val pusi = (u8(b, o + 1) and 0x40) != 0
            val pid = ((u8(b, o + 1) and 0x1f) shl 8) or u8(b, o + 2)
            val afc = (u8(b, o + 3) shr 4) and 3
            var p = o + 4
            if ((afc and 2) != 0) p += 1 + u8(b, o + 4)
            if ((afc and 1) != 0 && p < packetEnd) {
                if (pid == 0 && pusi) {
                    // Table des programmes : où lire la table du programme (PMT).
                    val t = p + 1 + u8(b, p)
                    if (t + 3 <= packetEnd) {
                        val end = minOf(packetEnd, t + 3 + (((u8(b, t + 1) and 0x0f) shl 8) or u8(b, t + 2)) - 4)
                        var i = t + 8
                        while (i + 4 <= end) {
                            if (((u8(b, i) shl 8) or u8(b, i + 1)) != 0) {
                                pmt = ((u8(b, i + 2) and 0x1f) shl 8) or u8(b, i + 3)
                                break
                            }
                            i += 4
                        }
                    }
                } else if (pid == pmt && pusi && audio < 0) {
                    // Pistes du programme : la première en AAC (type 0x0f).
                    val t = p + 1 + u8(b, p)
                    if (t + 12 <= packetEnd) {
                        val end = minOf(packetEnd, t + 3 + (((u8(b, t + 1) and 0x0f) shl 8) or u8(b, t + 2)) - 4)
                        var i = t + 12 + (((u8(b, t + 10) and 0x0f) shl 8) or u8(b, t + 11))
                        while (i + 5 <= end) {
                            if (u8(b, i) == 0x0f) {
                                audio = ((u8(b, i + 1) and 0x1f) shl 8) or u8(b, i + 2)
                                break
                            }
                            i += 5 + (((u8(b, i + 3) and 0x0f) shl 8) or u8(b, i + 4))
                        }
                    }
                } else if (pid == audio) {
                    if (pusi) {
                        if (p + 9 <= packetEnd && u8(b, p) == 0 && u8(b, p + 1) == 0 && u8(b, p + 2) == 1) {
                            val h = p + 9 + u8(b, p + 8)
                            if (h < packetEnd) es.write(b, h, packetEnd - h)
                            started = true
                        }
                    } else if (started) {
                        es.write(b, p, packetEnd - p)
                    }
                }
            }
            o = packetEnd
        }
        if (audio < 0) return null
        val e = es.toByteArray()
        val sizes = ArrayList<Int>()
        var i = 0
        while (i + 7 <= e.size) {
            if (u8(e, i) != 0xff || (u8(e, i + 1) and 0xf6) != 0xf0) { i++; continue }
            val len = ((u8(e, i + 3) and 0x03) shl 11) or (u8(e, i + 4) shl 3) or (u8(e, i + 5) shr 5)
            if (len < 7) { i++; continue }
            sizes.add(len - (if ((u8(e, i + 1) and 1) != 0) 7 else 9))
            i += len
        }
        return sizes
    }

    private class Box(val type: String, val body: Int, val end: Int)

    /** Tailles des échantillons d'un début de segment fMP4 à une seule piste
     *  (« audio seul ») : moof → traf → tfhd / trun, pour chaque fragment
     *  entier du morceau lu (Twitch en met des dizaines par segment, de 5
     *  échantillons chacun). null si rien de lisible ou s'il y a plusieurs
     *  pistes. */
    fun fmp4SampleSizes(b: ByteArray): List<Int>? {
        if (b.size < 16) return null
        fun u32(o: Int): Long = (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
        fun size32(o: Int): Int = minOf(u32(o), Int.MAX_VALUE.toLong()).toInt()
        /** Boîtes entières de [start, end) : une boîte qui déborde (lecture
         *  partielle) arrête la liste. */
        fun boxes(start: Int, end: Int): List<Box> {
            val out = ArrayList<Box>()
            var o = start
            while (o + 8 <= end) {
                var size = u32(o)
                var hdr = 8
                if (size == 1L) {
                    if (o + 16 > end || u32(o + 8) != 0L) break
                    size = u32(o + 12)
                    hdr = 16
                } else if (size == 0L) {
                    size = (end - o).toLong()
                }
                if (size < hdr || o + size > end) break
                out.add(Box(String(b, o + 4, 4, Charsets.ISO_8859_1), o + hdr, (o + size).toInt()))
                o += size.toInt()
            }
            return out
        }
        val sizes = ArrayList<Int>()
        for (moof in boxes(0, b.size)) {
            if (moof.type != "moof") continue
            val trafs = boxes(moof.body, moof.end).filter { it.type == "traf" }
            if (trafs.size != 1) return null
            var def = 0
            for (x in boxes(trafs[0].body, trafs[0].end)) {
                if (x.body + 8 > x.end) continue
                val flags = (u32(x.body) and 0xffffffL).toInt()
                if (x.type == "tfhd") {
                    var q = x.body + 8
                    if ((flags and 0x01) != 0) q += 8
                    if ((flags and 0x02) != 0) q += 4
                    if ((flags and 0x08) != 0) q += 4
                    if ((flags and 0x10) != 0 && q + 4 <= x.end) def = size32(q)
                } else if (x.type == "trun") {
                    // Plafonné : une boîte abîmée annoncerait des milliards
                    // d'échantillons (taille par défaut, sans rien à lire).
                    val count = minOf(u32(x.body + 4), 100_000L).toInt()
                    var q = x.body + 8
                    if ((flags and 0x01) != 0) q += 4
                    if ((flags and 0x04) != 0) q += 4
                    var n = 0
                    while (n < count) {
                        if ((flags and 0x100) != 0) q += 4
                        if ((flags and 0x200) != 0) {
                            if (q + 4 > x.end) break
                            sizes.add(size32(q))
                            q += 4
                        } else {
                            sizes.add(def)
                        }
                        if ((flags and 0x400) != 0) q += 4
                        if ((flags and 0x800) != 0) q += 4
                        n++
                    }
                }
            }
        }
        return sizes.ifEmpty { null }
    }

    /** true : segment muet ; false : du son ; null : illisible ou trop court. */
    fun verdict(bytes: ByteArray, fmp4: Boolean): Boolean? {
        val sizes = (if (fmp4) fmp4SampleSizes(bytes) else tsAudioFrameSizes(bytes)) ?: return null
        if (sizes.size < MIN_FRAMES) return null
        return sizes.count { it <= SILENT_FRAME_BYTES } >= sizes.size * 0.9
    }

    /** Les `n` premiers octets (Range) ; si le serveur l'ignore, la suite
     *  n'est pas lue. */
    private suspend fun head(url: String, n: Int): ByteArray? = runInterruptible(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).header("Range", "bytes=0-${n - 1}").build()
        } catch (e: IllegalArgumentException) {
            return@runInterruptible null
        }
        val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val input = res.body?.byteStream() ?: return@use null
                val out = ByteArrayOutputStream(n)
                val buf = ByteArray(16 * 1024)
                while (out.size() < n) {
                    val read = input.read(buf, 0, minOf(buf.size, n - out.size()))
                    if (read < 0) break
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            null
        }
    }

    /** SILENT (piste audio vide chez Twitch), SOUND, ou UNKNOWN (rien de
     *  lisible). `links` : les qualités de la VOD (adresses du CDN). */
    suspend fun probe(links: Map<String, String>): Verdict {
        val raw = links.values.sorted().firstOrNull { targets(it).isNotEmpty() } ?: return Verdict.UNKNOWN
        for (target in targets(raw)) {
            val source = target.contains("/chunked/")
            val res = Http.get(target, timeout = 15) ?: continue
            if (res.code != 200) continue
            val (urls, fmp4) = segments(res.body, target)
            if (urls.isEmpty() || (source && fmp4)) continue
            var silent = 0
            for (u in urls) {
                val bytes = head(u, if (source) SOURCE_PROBE_BYTES else PROBE_BYTES) ?: continue
                when (verdict(bytes, fmp4)) {
                    false -> return Verdict.SOUND
                    true -> silent++
                    null -> {}
                }
            }
            if (silent > 0 && silent >= minOf(2, urls.size)) return Verdict.SILENT
        }
        return Verdict.UNKNOWN
    }
}
