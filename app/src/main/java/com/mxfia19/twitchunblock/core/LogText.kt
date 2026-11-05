package com.mxfia19.twitchunblock.core

import com.mxfia19.twitchunblock.App
import org.json.JSONObject

/**
 * Journal dans la langue de l'app : il reste écrit en français, et à
 * l'affichage (écran Journal, export) ses morceaux français sont remplacés
 * par leur traduction (assets/logs/en.json) ; les nombres, noms et adresses
 * insérés ne changent pas. Le français tel quel, les autres langues en
 * anglais (seule table pour l'instant ; l'iPhone a aussi l'espagnol et le
 * russe). Fragments manquants : node tools/i18n/log-fragments.mjs. Comme
 * LogTranslator (iOS).
 */
object LogText {
    private class Entry(val fr: String, val tr: String, val word: Regex?)

    private var entries: List<Entry>? = null
    /** Textes déjà traduits, vidé au-delà de 4 000. */
    private val cache = HashMap<String, String>()

    /** Les plus longs d'abord : une phrase passe avant les mots qu'elle contient. */
    private fun load(): List<Entry> {
        entries?.let { return it }
        val pairs = try {
            val o = JSONObject(App.instance.assets.open("logs/en.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().map { it to o.optString(it) }.filter { it.second.isNotEmpty() }.toList()
        } catch (e: Exception) {
            emptyList()
        }
        val out = pairs.sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
            .map { (fr, tr) ->
                // Un seul mot : remplacé seulement s'il est entier (pas dans un pseudo).
                val word = if (fr.contains(' ')) null
                    else Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(fr) + "(?![\\p{L}\\p{N}_])")
                Entry(fr, tr, word)
            }
        entries = out
        return out
    }

    /** Texte du journal dans la langue de l'app (le français tel quel). */
    @Synchronized
    fun tr(text: String): String {
        if (AppState.lang == Lang.FR || text.isEmpty()) return text
        cache[text]?.let { return it }
        var out = text
        for (e in load()) {
            if (!out.contains(e.fr)) continue
            out = e.word?.replace(out, Regex.escapeReplacement(e.tr)) ?: out.replace(e.fr, e.tr)
        }
        if (cache.size > 4000) cache.clear()
        cache[text] = out
        return out
    }
}
