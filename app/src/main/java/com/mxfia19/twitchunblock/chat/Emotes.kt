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
    /** `source` : « 7tv », « bttv » ou « ffz » ; `author` : qui l'a créée (si
     *  la source le dit) ; `channel` : emote de la chaîne (sinon globale). */
    data class Emote(val name: String, val url: String, val ratio: Float = 1f, val zeroWidth: Boolean = false,
                     val source: String = "", val author: String? = null, val channel: Boolean = false)

    private val global = ConcurrentHashMap<String, Emote>()
    private val channels = ConcurrentHashMap<String, Map<String, Emote>>()
    /** Fin de validité des emotes de chaque chaîne (ms). */
    private val channelsUntil = ConcurrentHashMap<String, Long>()
    private const val CHANNEL_TTL = 3_600_000L
    private const val RETRY_TTL = 120_000L
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
            val stv = async { stvSet(parseJson(Http.get("https://7tv.io/v3/emote-sets/global")?.body)?.arr("emotes")?.objects(), false) }
            val bttv = async { bttvList(parseJsonArray(Http.get("https://api.betterttv.net/3/cached/emotes/global")?.body)?.objects(), false) }
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

    /** Emotes de la chaîne, gardées une heure ; quelques minutes seulement si
     *  une source n'a pas répondu, et pas du tout si aucune n'a répondu (la
     *  chaîne restait sans emotes jusqu'au redémarrage). */
    suspend fun loadChannel(channelId: String) {
        if (System.currentTimeMillis() < (channelsUntil[channelId] ?: 0L)) return
        coroutineScope {
            val stv = async { answer("https://7tv.io/v3/users/twitch/$channelId")?.let { stvSet(parseJson(it)?.obj("emote_set")?.arr("emotes")?.objects(), true) } }
            val bttv = async {
                answer("https://api.betterttv.net/3/cached/users/twitch/$channelId")?.let {
                    val j = parseJson(it)
                    bttvList(j?.arr("channelEmotes")?.objects(), true) + bttvList(j?.arr("sharedEmotes")?.objects(), true)
                }
            }
            val ffz = async { answer("https://api.frankerfacez.com/v1/room/id/$channelId")?.let { ffzSets(parseJson(it)?.obj("sets"), null, true) } }
            val sources = listOf(ffz.await(), bttv.await(), stv.await())
            val answered = sources.count { it != null }
            if (answered == 0) {
                Logger.warn("EMOTES", "Emotes de la chaîne injoignables", channelId)
                return@coroutineScope
            }
            // Ordre inverse de priorité : la dernière écrite l'emporte.
            val merged = HashMap<String, Emote>()
            for (list in sources) list?.forEach { merged[it.name] = it }
            channels[channelId] = merged
            channelsUntil[channelId] = System.currentTimeMillis() + if (answered == sources.size) CHANNEL_TTL else RETRY_TTL
            Logger.success("EMOTES", "Emotes de la chaîne chargées", "$channelId · ${merged.size}")
        }
    }

    /** Corps de la réponse ; null si la source n'a pas répondu (réseau, 429, 5xx). */
    private suspend fun answer(url: String): String? =
        Http.get(url)?.takeIf { it.code < 500 && it.code != 429 }?.body

    private fun stvSet(list: List<JSONObject>?, channel: Boolean): List<Emote> = list.orEmpty().mapNotNull { o ->
        val name = o.str("name") ?: return@mapNotNull null
        val data = o.obj("data") ?: return@mapNotNull null
        val host = data.obj("host") ?: return@mapNotNull null
        val base = host.str("url") ?: return@mapNotNull null
        val file = host.arr("files")?.objects()?.firstOrNull()
        val w = file?.int("width") ?: 0
        val h = file?.int("height") ?: 0
        val owner = data.obj("owner")
        // Drapeau actif dans ce jeu d'emotes : 1 = superposée (zero-width).
        Emote(name, "https:$base/2x.webp", if (w > 0 && h > 0) w.toFloat() / h else 1f, ((o.int("flags") ?: 0) and 1) != 0,
              "7tv", owner?.str("display_name") ?: owner?.str("username"), channel)
    }

    private fun bttvList(list: List<JSONObject>?, channel: Boolean): List<Emote> = list.orEmpty().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        val code = o.str("code") ?: return@mapNotNull null
        // Auteur : dit pour les emotes partagées seulement.
        val user = o.obj("user")
        Emote(code, "https://cdn.betterttv.net/emote/$id/2x", source = "bttv",
              author = user?.str("displayName") ?: user?.str("name"), channel = channel)
    }

    private fun ffzGlobal(json: JSONObject?): List<Emote> {
        val defaults = json?.arr("default_sets")?.let { a -> (0 until a.length()).map { a.opt(it).toString() } }?.toSet()
        return ffzSets(json?.obj("sets"), defaults, false)
    }

    private fun ffzSets(sets: JSONObject?, only: Set<String>?, channel: Boolean): List<Emote> {
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
                val owner = e.obj("owner")
                out += Emote(name, url, if (w > 0 && h > 0) w.toFloat() / h else 1f, e.bool("modifier") == true,
                             "ffz", owner?.str("display_name") ?: owner?.str("name"), channel)
            }
        }
        return out
    }
}
