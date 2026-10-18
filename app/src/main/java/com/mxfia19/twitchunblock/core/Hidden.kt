package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaînes et catégories masquées : plus dans le top ni dans les catégories
 * (la recherche les trouve toujours, ce qu'on suit n'est jamais masqué).
 * Même format que le site et l'app iPhone (`at` en millisecondes), pour la
 * sauvegarde. Fil principal.
 */
object Hidden {
    data class Channel(val login: String, val name: String, val at: Long)
    data class Cat(val id: String, val name: String, val box: String, val at: Long)

    val channels = mutableStateListOf<Channel>()
    val categories = mutableStateListOf<Cat>()

    fun init() {
        channels.clear()
        categories.clear()
        merge(parseJson(AppState.prefs.getString("hidden_content", null)))
    }

    val isEmpty: Boolean get() = channels.isEmpty() && categories.isEmpty()

    fun hidesChannel(login: String) = channels.any { it.login == login.lowercase() }
    fun hidesCategory(id: String) = id.isNotEmpty() && categories.any { it.id == id }

    /** Un direct qu'on peut montrer dans le top ou une catégorie. */
    fun allows(s: TwitchStream) = !hidesChannel(s.login) && !hidesCategory(s.gameId)

    fun hideChannel(login: String, name: String) {
        val l = login.lowercase()
        if (!Links.isValidLogin(l)) return
        channels.removeAll { it.login == l }
        channels.add(0, Channel(l, name.ifEmpty { l }.take(100), System.currentTimeMillis()))
        while (channels.size > 500) channels.removeAt(channels.lastIndex)
        save()
    }

    fun hideCategory(id: String, name: String, box: String) {
        if (id.isEmpty() || id.length > 20 || !id.all { it.isDigit() }) return
        categories.removeAll { it.id == id }
        categories.add(0, Cat(id, name.take(100), if (box.startsWith("https://")) box else "", System.currentTimeMillis()))
        while (categories.size > 200) categories.removeAt(categories.lastIndex)
        save()
    }

    fun unhideChannel(login: String) {
        channels.removeAll { it.login == login.lowercase() }
        save()
    }

    fun unhideCategory(id: String) {
        categories.removeAll { it.id == id }
        save()
    }

    fun toJson(): JSONObject {
        val ch = JSONArray()
        channels.forEach { ch.put(JSONObject().put("login", it.login).put("name", it.name).put("at", it.at)) }
        val cats = JSONArray()
        categories.forEach { cats.put(JSONObject().put("id", it.id).put("name", it.name).put("box", it.box).put("at", it.at)) }
        return JSONObject().put("channels", ch).put("categories", cats)
    }

    /** Ajoute ceux d'une sauvegarde (le plus récent l'emporte). */
    fun merge(root: JSONObject?) {
        if (root == null) return
        fun stamp(o: JSONObject): Long = o.dbl("at")?.takeIf { it.isFinite() && it >= 0 && it < 1e14 }?.toLong() ?: 0L
        val chans = LinkedHashMap<String, Channel>()
        channels.forEach { chans[it.login] = it }
        for (e in root.arr("channels")?.objects().orEmpty()) {
            val l = e.str("login")?.lowercase() ?: continue
            if (!Links.isValidLogin(l)) continue
            val item = Channel(l, (e.str("name") ?: "").take(100).ifEmpty { l }, stamp(e))
            val old = chans[l]
            if (old != null && old.at >= item.at) continue
            chans[l] = item
        }
        val cats = LinkedHashMap<String, Cat>()
        categories.forEach { cats[it.id] = it }
        for (e in root.arr("categories")?.objects().orEmpty()) {
            val id = e.str("id") ?: continue
            if (id.isEmpty() || id.length > 20 || !id.all { it.isDigit() }) continue
            val box = e.str("box") ?: ""
            val item = Cat(id, (e.str("name") ?: "").take(100), if (box.startsWith("https://") && box.length <= 500) box else "", stamp(e))
            val old = cats[id]
            if (old != null && old.at >= item.at) continue
            cats[id] = item
        }
        channels.clear()
        channels.addAll(chans.values.sortedByDescending { it.at }.take(500))
        categories.clear()
        categories.addAll(cats.values.sortedByDescending { it.at }.take(200))
        save()
    }

    private fun save() {
        AppState.prefs.edit().putString("hidden_content", toJson().toString()).apply()
    }
}
