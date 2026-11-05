package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Badges du chat (« moderator/1 » → image), globaux et de la chaîne, par
 *  l'API publique (GQL, sans compte). Leur titre (« Abonné depuis 3 mois »)
 *  est gardé par image : la fiche d'un badge touché le retrouve. */
object Badges {
    private val global = ConcurrentHashMap<String, String>()
    private val channels = ConcurrentHashMap<String, Map<String, String>>()
    /** Par image : l'id du badge (« subscriber/3 ») et son titre, sa description. */
    private val ids = ConcurrentHashMap<String, String>()
    private val titles = ConcurrentHashMap<String, Pair<String, String>>()

    fun url(badge: String, channelId: String?): String? =
        (channelId?.let { channels[it]?.get(badge) }) ?: global[badge]

    /** Id du badge d'une image (« subscriber/3 »), s'il est connu. */
    fun idOf(url: String): String? = ids[url]

    /** Titre et description du badge d'une image, s'ils sont connus. */
    fun info(url: String): Pair<String, String>? = titles[url]

    /** Menu ⋯ du chat : tout est oublié, puis relu par le chat. */
    fun reset() {
        global.clear()
        channels.clear()
    }

    private fun parse(list: List<JSONObject>): Map<String, String> {
        val map = HashMap<String, String>()
        for (b in list) {
            val set = b.str("setID") ?: continue
            val v = b.str("version") ?: continue
            val u = b.str("imageURL") ?: continue
            if (!u.startsWith("https://")) continue
            map["$set/$v"] = u
            ids[u] = "$set/$v"
            b.str("title")?.takeIf { it.isNotEmpty() }?.let { titles[u] = Pair(it, b.str("description") ?: "") }
        }
        return map
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        val d = Gql.request("query { badges { setID version title description imageURL(size: DOUBLE) } }") ?: return
        val map = parse(d.arr("badges")?.objects().orEmpty())
        global.putAll(map)
        Logger.success("BADGES", "Badges globaux chargés", "${map.size}")
    }

    suspend fun loadChannel(channelId: String) {
        if (channels.containsKey(channelId)) return
        val d = Gql.request("query(\$id: ID!) { user(id: \$id) { broadcastBadges { setID version title description imageURL(size: DOUBLE) } } }",
                            Gql.vars("id" to channelId)) ?: return
        channels[channelId] = parse(d.obj("user")?.arr("broadcastBadges")?.objects().orEmpty())
    }
}
