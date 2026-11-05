package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Texte gardé dans les préférences (identifiant de thème, adresse…). */
class StringPref(private val key: String, private val default: String) {
    var value by mutableStateOf(default)
        private set

    fun load() { value = AppState.prefs.getString(key, null) ?: default }

    fun set(v: String) {
        value = v
        AppState.prefs.edit().putString(key, v).apply()
    }
}

// Réglages ajoutés depuis la série 5 : chacun est lu dès sa création. L'objet
// qui les porte n'est créé qu'au premier accès, après AppState.init (App), et
// n'a donc pas besoin d'être ajouté à Features.load().
fun boolPref(key: String, default: Boolean) = BoolPref(key, default).also { it.load() }
fun intPref(key: String, default: Int, range: IntRange) = IntPref(key, default, range).also { it.load() }
fun wordsPref(key: String, max: Int) = WordsPref(key, max).also { it.load() }
fun stringPref(key: String, default: String) = StringPref(key, default).also { it.load() }
