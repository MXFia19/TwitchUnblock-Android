package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Chat gardé avec une VOD téléchargée (vodchat/<id>.json) : une ligne
 * d'en-tête « tuchat⇥2⇥nombre⇥chaîne », puis un commentaire par ligne
 * (« instant⇥json »), dans l'ordre de la vidéo. Écrit au fil des pages et
 * relu par fenêtres de temps : un gros chat (100 000 messages et plus) ne
 * tient jamais en mémoire d'un bloc. À appeler hors du fil principal. Les
 * fichiers d'avant (un seul objet JSON) sont réécrits à leur première lecture.
 */
object SavedChat {
    private const val MAGIC = "tuchat"
    /** Durée d'une fenêtre lue (s), et nombre de lignes au plus. */
    private const val WINDOW = 10.0
    private const val MAX_LINES = 2000

    /** Écriture au fil des pages : les lignes dans « .part », puis le
     *  fichier final (en-tête, lignes), remplacé d'un coup. */
    class Writer(private val file: File) {
        private val part = File(file.path + ".part")
        private val out: java.io.BufferedWriter
        var count = 0
            private set

        init {
            file.parentFile?.mkdirs()
            out = part.bufferedWriter()
        }

        fun add(comment: JSONObject) {
            out.write((comment.dbl("contentOffsetSeconds") ?: 0.0).toString())
            out.write("\t")
            out.write(comment.toString())
            out.write("\n")
            count++
        }

        fun finish(channelId: String?) {
            out.close()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().buffered().use { o ->
                o.write("$MAGIC\t2\t$count\t${channelId ?: ""}\n".toByteArray(Charsets.UTF_8))
                part.inputStream().use { it.copyTo(o) }
            }
            part.delete()
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }

        fun abort() {
            try { out.close() } catch (e: Exception) { }
            part.delete()
        }
    }

    /** Chat prêt à relire : chaîne, instant et place (octet) de chaque ligne. */
    class Index(val file: File, val channelId: String?, val offsets: DoubleArray, val positions: LongArray,
                val end: Long)

    /** En-tête : (nombre de messages, chaîne), ou null (fichier d'avant). */
    private fun header(file: File): Pair<Int, String?>? {
        // Lu octet par octet sur 200 au plus : un fichier d'avant tient sur
        // une seule (très longue) ligne.
        val line = StringBuilder()
        file.inputStream().buffered().use { input ->
            while (line.length < 200) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                line.append(b.toChar())
            }
        }
        val parts = line.split('\t')
        if (parts.size < 3 || parts[0] != MAGIC || parts[1] != "2") return null
        return Pair(parts[2].toIntOrNull() ?: 0, parts.getOrNull(3)?.takeIf { it.isNotEmpty() })
    }

    /** Fichier d'avant (un seul objet JSON, de taille raisonnable puisqu'il
     *  a été écrit d'un bloc) : réécrit au format par lignes. */
    private fun upgrade(file: File): Boolean {
        val saved = parseJson(file.readText()) ?: return false
        val writer = Writer(file)
        try {
            for (c in saved.arr("comments")?.objects().orEmpty()) writer.add(c)
            writer.finish(saved.str("channelId"))
        } catch (e: Exception) {
            writer.abort()
            return false
        }
        return true
    }

    /** Nombre de messages gardés (bibliothèque), null sans chat lisible. */
    fun count(file: File): Int? = try {
        if (!file.exists()) null
        else header(file)?.first ?: if (upgrade(file)) header(file)?.first else null
    } catch (e: Exception) {
        null
    }

    /** Repère les lignes du fichier (une lecture, sans analyser le JSON). */
    fun open(file: File): Index? = try {
        if (!file.exists() || (header(file) == null && !upgrade(file))) null
        else {
            val (count, channelId) = header(file) ?: Pair(0, null)
            var offsets = DoubleArray(maxOf(count, 16))
            var positions = LongArray(offsets.size)
            var n = 0
            var line = 0               // 0 : l'en-tête
            var lineStart = 0L
            var inPrefix = true
            val prefix = StringBuilder()
            var last = 0.0
            var base = 0L
            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    for (i in 0 until read) {
                        val b = buf[i].toInt()
                        if (b == '\n'.code) {
                            line++
                            lineStart = base + i + 1
                            inPrefix = true
                            prefix.setLength(0)
                        } else if (line > 0 && inPrefix) {
                            if (b == '\t'.code) {
                                inPrefix = false
                                if (n == offsets.size) {
                                    offsets = offsets.copyOf(n * 2)
                                    positions = positions.copyOf(n * 2)
                                }
                                // Instants croissants (recherche par dichotomie).
                                last = maxOf(last, prefix.toString().toDoubleOrNull() ?: last)
                                offsets[n] = last
                                positions[n] = lineStart
                                n++
                            } else {
                                prefix.append(b.toChar())
                            }
                        }
                    }
                    base += read
                }
            }
            Index(file, channelId, offsets.copyOf(n), positions.copyOf(n), base)
        }
    } catch (e: Exception) {
        null
    }

    /** Une fenêtre de commentaires : du premier à `from` s ou après, sur
     *  WINDOW s ; et s'il en reste après. null : fichier illisible. */
    fun window(index: Index, from: Double): Pair<List<JSONObject>, Boolean>? = try {
        val offsets = index.offsets
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] < from) lo = mid + 1 else hi = mid
        }
        val start = lo
        if (start >= offsets.size) {
            Pair(emptyList<JSONObject>(), false)
        } else {
            val limit = offsets[start] + WINDOW
            var end = start
            while (end < offsets.size && end - start < MAX_LINES && offsets[end] < limit) end++
            val begin = index.positions[start]
            val stop = if (end < offsets.size) index.positions[end] else index.end
            val bytes = ByteArray((stop - begin).toInt())
            RandomAccessFile(index.file, "r").use {
                it.seek(begin)
                it.readFully(bytes)
            }
            val nodes = String(bytes, Charsets.UTF_8).split('\n').mapNotNull { l ->
                val tab = l.indexOf('\t')
                if (tab < 0) null else parseJson(l.substring(tab + 1))
            }
            Pair(nodes, end < offsets.size)
        }
    } catch (e: Exception) {
        null
    }
}
