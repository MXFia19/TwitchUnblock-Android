package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Emotes 7TV, BTTV et FFZ (globales et de la chaîne), par nom. Une chaîne
 * prime sur les globales ; 7TV, puis BTTV, puis FFZ. Comme EmoteService (iOS).
 */
object Emotes {
    data class Emote(val name: String, val url: String, val ratio: Float = 1f, val zeroWidth: Boolean = false)

    private val global = ConcurrentHashMap<String, Emote>()
    private val channels = ConcurrentHashMap<String, Map<String, Emote>>()
    private val mutex = Mutex()
    @Volatile
    private var globalLoaded = false

    fun find(name: String, channelId: String?): Emote? =
        (channelId?.let { channels[it]?.get(name) }) ?: global[name]

    /** Toutes les emotes connues pour une chaîne (sélecteur), chaîne d'abord. */
    fun all(channelId: String?): List<Emote> =
        (channelId?.let { channels[it]?.values?.toList() } ?: emptyList()) + global.values.sortedBy { it.name.lowercase() }

    suspend fun loadGlobal() = mutex.withLock {
        if (globalLoaded) return@withLock
        coroutineScope {
            val stv = async { stvSet(parseJson(Http.get("https://7tv.io/v3/emote-sets/global")?.body)?.arr("emotes")?.objects()) }
            val bttv = async { bttvList(parseJsonArray(Http.get("https://api.betterttv.net/3/cached/emotes/global")?.body)?.objects()) }
            val ffz = async { ffzGlobal(parseJson(Http.get("https://api.frankerfacez.com/v1/set/global")?.body)) }
            // Ordre inverse de priorité : la dernière écrite l'emporte.
            val merged = HashMap<String, Emote>()
            ffz.await().forEach { merged[it.name] = it }
            bttv.await().forEach { merged[it.name] = it }
            stv.await().forEach { merged[it.name] = it }
            if (merged.isNotEmpty()) {
                global.putAll(merged)
                globalLoaded = true
                Logger.success("EMOTES", "Emotes globales chargées", "${merged.size}")
            }
        }
    }

    suspend fun loadChannel(channelId: String) {
        if (channels.containsKey(channelId)) return
        coroutineScope {
            val stv = async { stvSet(parseJson(Http.get("https://7tv.io/v3/users/twitch/$channelId")?.body)?.obj("emote_set")?.arr("emotes")?.objects()) }
            val bttv = async {
                val j = parseJson(Http.get("https://api.betterttv.net/3/cached/users/twitch/$channelId")?.body)
                bttvList(j?.arr("channelEmotes")?.objects()) + bttvList(j?.arr("sharedEmotes")?.objects())
            }
            val ffz = async { ffzSets(parseJson(Http.get("https://api.frankerfacez.com/v1/room/id/$channelId")?.body)?.obj("sets"), null) }
            val merged = HashMap<String, Emote>()
            ffz.await().forEach { merged[it.name] = it }
            bttv.await().forEach { merged[it.name] = it }
            stv.await().forEach { merged[it.name] = it }
            channels[channelId] = merged
            Logger.success("EMOTES", "Emotes de la chaîne chargées", "$channelId · ${merged.size}")
        }
    }

    private fun stvSet(list: List<JSONObject>?): List<Emote> = list.orEmpty().mapNotNull { o ->
        val name = o.str("name") ?: return@mapNotNull null
        val data = o.obj("data") ?: return@mapNotNull null
        val host = data.obj("host") ?: return@mapNotNull null
        val base = host.str("url") ?: return@mapNotNull null
        val file = host.arr("files")?.objects()?.firstOrNull()
        val w = file?.int("width") ?: 0
        val h = file?.int("height") ?: 0
        // Drapeau actif dans ce jeu d'emotes : 1 = superposée (zero-width).
        Emote(name, "https:$base/2x.webp", if (w > 0 && h > 0) w.toFloat() / h else 1f, ((o.int("flags") ?: 0) and 1) != 0)
    }

    private fun bttvList(list: List<JSONObject>?): List<Emote> = list.orEmpty().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        val code = o.str("code") ?: return@mapNotNull null
        Emote(code, "https://cdn.betterttv.net/emote/$id/2x")
    }

    private fun ffzGlobal(json: JSONObject?): List<Emote> {
        val defaults = json?.arr("default_sets")?.let { a -> (0 until a.length()).map { a.opt(it).toString() } }?.toSet()
        return ffzSets(json?.obj("sets"), defaults)
    }

    private fun ffzSets(sets: JSONObject?, only: Set<String>?): List<Emote> {
        if (sets == null) return emptyList()
        val out = mutableListOf<Emote>()
        val keys = sets.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (only != null && k !in only) continue
            for (e in sets.obj(k)?.arr("emoticons")?.objects().orEmpty()) {
                val name = e.str("name") ?: continue
                val urls = e.obj("urls") ?: continue
                var url = urls.str("2") ?: urls.str("1") ?: continue
                if (url.startsWith("//")) url = "https:$url"
                val w = e.int("width") ?: 0
                val h = e.int("height") ?: 0
                out += Emote(name, url, if (w > 0 && h > 0) w.toFloat() / h else 1f, e.bool("modifier") == true)
            }
        }
        return out
    }
}
