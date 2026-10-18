package com.mxfia19.twitchunblock.core

import android.content.Context
import org.json.JSONObject

enum class Lang(val code: String, val label: String) {
    FR("fr", "Français"),
    EN("en", "English"),
    ES("es", "Español"),
    RU("ru", "Русский");

    companion object {
        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code?.lowercase() }
    }
}

/**
 * Textes de l'app, dans les quatre langues de l'app iPhone : assets/i18n,
 * créés depuis ses dictionnaires (tools/i18n/gen-i18n.mjs), plus les textes
 * propres à Android. Même règle qu'iOS : clé absente → anglais → la clé.
 */
object I18n {
    private val tables = HashMap<String, Map<String, String>>()

    fun load(context: Context) {
        for (lang in Lang.entries) {
            val map = HashMap<String, String>()
            try {
                val text = context.assets.open("i18n/${lang.code}.json").bufferedReader().use { it.readText() }
                val o = JSONObject(text)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = o.optString(k)
                }
            } catch (e: Exception) {
                Logger.error("I18N", "Textes illisibles", "${lang.code} — ${e.message}")
            }
            tables[lang.code] = map
        }
    }

    fun t(key: String, lang: Lang): String =
        tables[lang.code]?.get(key) ?: tables["en"]?.get(key) ?: key

    /** Texte accordé au nombre (« 1 spectateur », « 2 зрителя ») : `clé_one`,
     *  ou `clé_few` en russe, quand la langue l'a ; sinon `clé` (pluriel).
     *  Nombre abrégé (« 1.2k ») : pluriel. Comme translatePlural (iOS). */
    fun tn(key: String, n: Int, lang: Lang, abbreviated: Boolean = false): String {
        if (abbreviated) return t(key, lang)
        val a = kotlin.math.abs(n)
        val form = when (lang) {
            Lang.RU -> {
                val d = a % 10
                val h = a % 100
                if (d == 1 && h != 11) "one" else if (d in 2..4 && h !in 12..14) "few" else "many"
            }
            Lang.FR -> if (a < 2) "one" else "other"
            else -> if (a == 1) "one" else "other"
        }
        return tables[lang.code]?.get("${key}_$form") ?: t(key, lang)
    }
}
