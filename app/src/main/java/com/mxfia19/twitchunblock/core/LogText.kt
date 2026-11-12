package com.mxfia19.twitchunblock.core

import com.mxfia19.twitchunblock.App
import org.json.JSONObject

/**
 * Journal dans la langue de l'app : il reste écrit en français, et à
 * l'affichage (écran Journal, export) ses morceaux français sont remplacés
 * par leur traduction (assets/logs/<langue>.json) ; les nombres, noms et
 * adresses insérés ne changent pas. Le français tel quel ; une langue sans
 * table prend l'anglais (seule table pour l'instant ; l'iPhone a aussi
 * l'espagnol et le russe). Morceaux manquants :
 * node tools/i18n/log-fragments.mjs. Comme LogTranslator (iOS).
 */
object LogText {
    private class Entry(val fr: String, val tr: String, val word: Regex?)

    /** Table chargée, et la langue dont elle est. */
    private var entries: List<Entry> = emptyList()
    private var loadedFor: String? = null
    /** Textes déjà traduits, vidé au-delà de 4 000 (et au changement de langue). */
    private val cache = HashMap<String, String>()

    private fun read(code: String): List<Pair<String, String>>? = try {
        val o = JSONObject(App.instance.assets.open("logs/$code.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().map { it to o.optString(it) }.filter { it.second.isNotEmpty() }.toList()
    } catch (e: Exception) {
        null
    }

    /** Les plus longs d'abord : une phrase passe avant les mots qu'elle contient. */
    private fun load(lang: Lang): List<Entry> {
        if (loadedFor == lang.code) return entries
        val pairs = read(lang.code) ?: read("en") ?: emptyList()
        entries = pairs.sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
            .map { (fr, tr) ->
                // Un seul mot : remplacé seulement s'il est entier (pas dans un pseudo).
                val word = if (fr.contains(' ')) null
                    else Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(fr) + "(?![\\p{L}\\p{N}_])")
                Entry(fr, tr, word)
            }
        loadedFor = lang.code
        cache.clear()
        return entries
    }

    /** Texte du journal dans la langue de l'app (le français tel quel) ;
     *  `lang` : une autre, l'anglais pour ce qui part vers le développeur
     *  (signalements, journal exporté). Une seule table chargée à la fois. */
    @Synchronized
    fun tr(text: String, lang: Lang = AppState.lang): String {
        if (lang == Lang.FR || text.isEmpty()) return text
        val table = load(lang)
        cache[text]?.let { return it }
        var out = text
        for (e in table) {
            if (!out.contains(e.fr)) continue
            out = e.word?.replace(out, Regex.escapeReplacement(e.tr)) ?: out.replace(e.fr, e.tr)
        }
        if (cache.size > 4000) cache.clear()
        cache[text] = out
        return out
    }
}
