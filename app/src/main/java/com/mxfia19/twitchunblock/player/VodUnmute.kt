package com.mxfia19.twitchunblock.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.util.UnstableApi
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.boolPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/**
 * Partie d'une VOD téléchargée et son d'origine des passages coupés, dans le
 * fragment de l'adresse de sa playlist (« #tu=<de>,<à>,<son> », secondes ;
 * jamais envoyé au CDN). `to` 0 : jusqu'à la fin.
 */
data class DownloadPart(val from: Double, val to: Double, val restore: Boolean) {
    val isPart: Boolean get() = from > 0 || to > 0

    /** Le segment [start, start + duration) recoupe-t-il la partie ? */
    fun covers(start: Double, duration: Double): Boolean =
        start + duration > from + 0.001 && (to <= 0 || start < to - 0.001)

    val fragment: String
        get() = String.format(Locale.US, "tu=%.3f,%.3f,%d", from, to, if (restore) 1 else 0)

    /** Bornes calées sur les segments de la playlist (le premier et le dernier
     *  gardés) : le chat gardé commence avec l'image. */
    fun snapped(playlist: String): DownloadPart {
        var clock = 0.0
        var pending = 0.0
        var first = -1.0
        var last = -1.0
        for (raw in playlist.split('\n')) {
            val t = raw.trim()
            if (t.startsWith("#EXTINF:")) {
                pending = t.substring(8).substringBefore(',').toDoubleOrNull() ?: 0.0
                continue
            }
            if (t.isEmpty() || t.startsWith("#")) continue
            if (covers(clock, pending)) {
                if (first < 0) first = clock
                last = clock + pending
            }
            clock += pending
            pending = 0.0
        }
        if (first < 0) return this
        // Jusqu'à la fin de la VOD : « à » reste 0.
        val end = if (to <= 0 || last >= clock - 0.001) 0.0 else last
        return copy(from = first, to = end)
    }

    companion object {
        fun parse(fragment: String?): DownloadPart? {
            if (fragment == null || !fragment.startsWith("tu=")) return null
            val p = fragment.removePrefix("tu=").split(',')
            if (p.size < 3) return null
            return DownloadPart(p[0].toDoubleOrNull() ?: 0.0, p[1].toDoubleOrNull() ?: 0.0, p[2] == "1")
        }
    }
}

/**
 * Son des passages coupés (musique protégée), comme VodUnmuteLoader (iOS) :
 * Twitch pointe ces segments vers « N-muted.ts » (muet) ou « N-unmuted.ts »
 * (refusé par le CDN). Un jour ou deux après la diffusion, l'original
 * « N.ts » reste servi : chaque passage coupé est sondé (HEAD) et remis tant
 * qu'il répond, sinon la version muette. En fMP4, « N-unmuted.mp4 » est
 * servi avec le son : gardé tel quel. Les playlists des téléchargements
 * passent par ici aussi (partie d'une VOD, son d'origine ou non :
 * DownloadPart). Appelé sur les fils de chargement de Media3.
 */
@OptIn(UnstableApi::class)
object VodUnmute {
    /** Réglage (Lecteur) : activé par défaut, comme sur l'iPhone. */
    val enabled by lazy { boolPref("restore_muted", true) }

    /** Bilan d'une playlist : passages rétablis (« -unmuted.mp4 » compris),
     *  passages encore muets (secondes, pour la barre de lecture). */
    data class Outcome(val restored: Int, val remaining: List<MutedRange>)

    /** Bilans par dossier de VOD sur le CDN (keyOf), relus par le lecteur. */
    val outcomes = mutableStateMapOf<String, Outcome>()

    private val MUTED = Regex("-(un)?muted\\.(ts|mp4)")
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    /** Playlists terminées déjà réécrites (changement de qualité, retour en
     *  arrière, téléchargement de la VOD regardée), avec leur bilan. */
    private val cache = object : LinkedHashMap<String, Pair<String, Outcome?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Outcome?>>?): Boolean = size > 20
    }
    /** Originaux déjà sondés (adresse → servi) : un enregistrement en cours,
     *  relu toutes les quelques secondes, ne refait pas ses HEAD. */
    private val probed = HashMap<String, Boolean>()
    private val summaries = HashMap<String, String>()

    /** Dossier d'une VOD sur le CDN (« hôte/dossier »), le même pour toutes ses qualités. */
    fun keyOf(url: String): String? {
        val u = Uri.parse(url)
        val host = u.host ?: return null
        val folder = u.pathSegments.firstOrNull() ?: return null
        return "$host/$folder"
    }

    private fun enabledNow(): Boolean = try { enabled.value } catch (e: Exception) { true }

    /**
     * Playlist lue par le lecteur ou un téléchargement : telle quelle sans
     * passage coupé ni partie (directs, listes maîtresses), sinon réécrite.
     * `uri` : son adresse, fragment d'un téléchargement compris.
     */
    fun rewrite(text: String, uri: Uri): String {
        val part = DownloadPart.parse(uri.fragment)
        if (part == null && !text.contains("muted.")) return text
        if (!text.contains("#EXTINF")) return text
        val probe = (part?.restore ?: enabledNow()) && recentEnough(uri)
        val key = (if (probe) "" else "muted|") + uri.toString()
        val hit = synchronized(lock) { cache[key] }
        if (hit != null) {
            if (part == null) hit.second?.let { announce(uri, it) }
            return hit.first
        }
        val (out, outcome) = rewriteNow(text, uri, part, probe)
        // Seulement une playlist terminée : celle d'un enregistrement en cours grandit.
        if (text.contains("#EXT-X-ENDLIST")) synchronized(lock) { cache[key] = Pair(out, outcome) }
        if (part == null && outcome != null) announce(uri, outcome)
        return out
    }

    private class Muted(val index: Int, val original: String, val absolute: String, val start: Double, val duration: Double)

    private fun rewriteNow(text: String, uri: Uri, part: DownloadPart?, probe: Boolean): Pair<String, Outcome?> {
        val base = uri.toString().substringBefore('#')
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val firstTag = lines.indexOfFirst { it.startsWith("#EXTINF") }
        if (firstTag < 0) return Pair(text, null)
        val out = ArrayList<String>(lines.size)
        for (i in 0 until firstTag) out += lines[i]
        val muted = mutableListOf<Muted>()
        var kept = 0
        var clock = 0.0
        var pending = 0.0
        val block = mutableListOf<String>()
        // Segment d'initialisation (fMP4) ou clé d'un bloc retiré : reportés
        // sur le premier segment gardé.
        val carry = LinkedHashMap<String, String>()
        for (i in firstTag until lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#")) {
                if (t.startsWith("#EXTINF:")) pending = t.substring(8).substringBefore(',').toDoubleOrNull() ?: 0.0
                block += line
                continue
            }
            val start = clock
            val duration = pending
            clock += pending
            pending = 0.0
            if (part != null && !part.covers(start, duration)) {
                for (tag in block) {
                    if (tag.startsWith("#EXT-X-MAP")) carry["map"] = tag
                    else if (tag.startsWith("#EXT-X-KEY")) carry["key"] = tag
                }
                block.clear()
                continue
            }
            out.addAll(carry.values)
            carry.clear()
            out.addAll(block)
            block.clear()
            var seg = t
            if (seg.contains("-unmuted.mp4")) {
                // Servi avec le son (fMP4) : surtout pas la version muette.
                kept++
            } else {
                val m = MUTED.find(seg)
                if (m != null) {
                    val ext = m.groupValues[2]
                    val original = seg.replaceRange(m.range, ".$ext")
                    // « -unmuted.ts » est refusé par le CDN : la version muette en repli.
                    seg = seg.replaceRange(m.range, "-muted.$ext")
                    muted += Muted(out.size, original, resolve(base, original), start, duration)
                }
            }
            out += seg
        }
        // Balises d'après le dernier segment (#EXT-X-ENDLIST) ; une partie est finie.
        out.addAll(block)
        if (part != null && part.isPart && out.none { it.trim() == "#EXT-X-ENDLIST" }) out += "#EXT-X-ENDLIST"
        if (muted.isEmpty() && kept == 0) return Pair(out.joinToString("\n"), null)

        val available = HashSet<Int>()
        if (probe && muted.isNotEmpty()) {
            val unknown = mutableListOf<Int>()
            synchronized(lock) {
                muted.forEachIndexed { k, m ->
                    when (probed[m.absolute]) {
                        true -> available += k
                        false -> {}
                        null -> unknown += k
                    }
                }
            }
            // Quatre premiers d'abord (si aucun original connu n'est servi) :
            // tous refusés, les originaux sont effacés, inutile de sonder la suite.
            val results = HashMap<String, Boolean>()
            val batch = if (available.isEmpty()) unknown.take(4) else unknown
            val first = exist(batch.map { muted[it].absolute })
            batch.forEachIndexed { n, k ->
                results[muted[k].absolute] = first[n]
                if (first[n]) available += k
            }
            val rest = unknown.drop(batch.size)
            if (rest.isNotEmpty()) {
                if (first.any { it }) {
                    val others = exist(rest.map { muted[it].absolute })
                    rest.forEachIndexed { n, k ->
                        results[muted[k].absolute] = others[n]
                        if (others[n]) available += k
                    }
                } else {
                    for (k in rest) results[muted[k].absolute] = false
                }
            }
            synchronized(lock) {
                if (probed.size > 10_000) probed.clear()
                probed.putAll(results)
            }
        }
        for (k in available) out[muted[k].index] = muted[k].original

        // Passages encore muets, fusionnés, pour la barre de lecture.
        val remaining = mutableListOf<MutedRange>()
        muted.forEachIndexed { k, m ->
            if (k in available) return@forEachIndexed
            val last = remaining.lastOrNull()
            if (last != null && m.start <= last.end + 0.5) remaining[remaining.lastIndex] = MutedRange(last.start, m.start + m.duration)
            else remaining += MutedRange(m.start, m.start + m.duration)
        }
        val summary = "${available.size}/${muted.size} segments rétablis, $kept déjà avec le son (-unmuted.mp4)"
        val logged = synchronized(lock) {
            if (summaries[base] == summary) false
            else {
                if (summaries.size > 50) summaries.clear()
                summaries[base] = summary
                true
            }
        }
        if (logged) Logger.info("UNMUTE", "Passages coupés : $summary", keyOf(base))
        return Pair(out.joinToString("\n"), Outcome(available.size + kept, remaining))
    }

    /** Les originaux sont effacés au bout de quelques jours : la date du direct
     *  est dans le dossier du CDN (…_<chaîne>_<identifiant>_<epoch>). Inconnue : on essaie. */
    private fun recentEnough(uri: Uri): Boolean {
        val folder = uri.pathSegments.firstOrNull() ?: return true
        val epoch = folder.substringAfterLast('_').toLongOrNull() ?: return true
        if (epoch < 1_000_000_000L || epoch > 10_000_000_000L) return true
        return System.currentTimeMillis() / 1000 - epoch < 4 * 86_400
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http")) ref else androidx.media3.common.util.UriUtil.resolve(base, ref)

    /** Le CDN sert-il ces fichiers ? HEAD en parallèle, huit à la fois. */
    private fun exist(urls: List<String>): List<Boolean> {
        if (urls.isEmpty()) return emptyList()
        return try {
            runBlocking(Dispatchers.IO) {
                val gate = Semaphore(8)
                urls.map { u -> async { gate.withPermit { Http.head(u, Config.TWITCH_HEADERS, timeout = 6) == 200 } } }.awaitAll()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            urls.map { false }
        }
    }

    private fun announce(uri: Uri, outcome: Outcome) {
        val key = keyOf(uri.toString()) ?: return
        main.post { if (outcomes[key] != outcome) outcomes[key] = outcome }
    }
}
