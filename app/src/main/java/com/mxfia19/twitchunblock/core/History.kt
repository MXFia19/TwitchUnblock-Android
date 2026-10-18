package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** Chaîne ouverte ou VOD lancée : le format de la sauvegarde du site et de l'app iPhone. */
data class HistoryItem(
    val term: String,
    /** « vod » ou « channel ». */
    val type: String,
    val display: String,
    val thumb: String? = null,
    val streamer: String? = null,
    /** Date d'ajout (ms). */
    val addedAt: Double = 0.0,
    val avatar: String? = null,
) {
    val key: String get() = term.lowercase()

    fun toJson(): JSONObject = JSONObject().put("term", term).put("type", type).put("display", display).apply {
        thumb?.let { put("thumb", it) }
        streamer?.let { put("streamer", it) }
        if (addedAt > 0) put("addedAt", addedAt)
        avatar?.let { put("avatar", it) }
    }

    /** Champs absents repris de l'autre version du même élément. */
    fun filled(other: HistoryItem) = copy(thumb = thumb ?: other.thumb, streamer = streamer ?: other.streamer,
                                          avatar = avatar ?: other.avatar)

    companion object {
        fun from(o: JSONObject): HistoryItem? {
            val term = o.str("term")?.takeIf { it.isNotEmpty() } ?: return null
            val type = o.str("type")?.takeIf { it == "vod" || it == "channel" } ?: return null
            return HistoryItem(term, type, o.str("display") ?: term, o.str("thumb"), o.str("streamer"),
                               o.dbl("addedAt") ?: 0.0, o.str("avatar"))
        }
    }
}

/**
 * Historique : chaînes ouvertes et VODs lancées (50 au plus, la plus récente
 * d'abord), synchronisé avec le compte (CloudSync). Fil principal.
 */
object History {
    val items = mutableStateListOf<HistoryItem>()

    /** Change à chaque modification faite ici (à envoyer au serveur). */
    var revision = 0
        private set

    fun init() {
        items.clear()
        parseJsonArray(AppState.prefs.getString("history", null))?.objects()?.mapNotNull { HistoryItem.from(it) }
            ?.let { items.addAll(it) }
    }

    val vods: List<HistoryItem> get() = items.filter { it.type == "vod" }
    val channels: List<HistoryItem> get() = items.filter { it.type == "channel" }

    fun addChannel(login: String, avatar: String?) {
        if (login.isEmpty()) return
        add(HistoryItem(login.lowercase(), "channel", login, avatar = avatar))
    }

    fun addVod(id: String, title: String, thumb: String?, streamer: String?) {
        if (id.isEmpty()) return
        add(HistoryItem(id, "vod", title.ifEmpty { id }, thumb?.takeIf { it.startsWith("https://") }, streamer))
    }

    private fun add(item: HistoryItem) {
        val old = items.firstOrNull { it.key == item.key }
        items.removeAll { it.key == item.key }
        items.add(0, (if (old != null) item.filled(old) else item).copy(addedAt = System.currentTimeMillis().toDouble()))
        while (items.size > 50) items.removeAt(items.lastIndex)
        changed()
    }

    fun remove(term: String) {
        items.removeAll { it.key == term.lowercase() }
        changed()
    }

    fun clear(type: String) {
        items.removeAll { it.type == type }
        changed()
    }

    /** Résultat d'une fusion avec le serveur (sans le marquer « à envoyer »). */
    fun replace(list: List<HistoryItem>) {
        items.clear()
        items.addAll(list)
        save()
    }

    private fun changed() {
        revision++
        save()
        CloudSync.scheduleSoon()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        AppState.prefs.edit().putString("history", arr.toString()).apply()
    }
}
