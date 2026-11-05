package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * Recherches récentes : les 10 derniers textes cherchés, sur cet appareil
 * seulement (comme searchHistory de l'iPhone), retirables un à un ou tous.
 * Fil principal.
 */
object SearchHistory {
    private const val KEY = "search_history"
    private const val MAX = 10

    val items = mutableStateListOf<String>().apply {
        addAll(parseJsonArray(AppState.prefs.getString(KEY, null))?.strings().orEmpty().take(MAX))
    }

    fun add(text: String) {
        val t = text.trim().take(100)
        if (t.isEmpty()) return
        // La même recherche remonte en tête au lieu de se répéter.
        items.removeAll { it.equals(t, ignoreCase = true) }
        items.add(0, t)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        save()
    }

    fun remove(text: String) {
        items.remove(text)
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    private fun save() {
        AppState.prefs.edit().putString(KEY, JSONArray(items.toList()).toString()).apply()
    }
}
