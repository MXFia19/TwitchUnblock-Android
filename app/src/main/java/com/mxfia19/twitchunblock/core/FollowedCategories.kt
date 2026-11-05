package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.web.WebGql
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catégories suivies : celles de l'appareil (« followed_categories », même
 * format que la sauvegarde de l'iPhone : id, name, box) et celles du compte
 * Twitch quand la session web est là (lecture seule). Leur audience est
 * demandée par paquets de 15 : Twitch refuse une requête GQL de plus de 15
 * alias. Fil principal.
 */
object FollowedCategories {
    /** Suivies sur l'appareil, la plus récente d'abord (200 au plus). */
    val local = mutableStateListOf<Category>().apply { addAll(read()) }
    /** Suivies sur Twitch (session web), relues au plus toutes les 10 min. */
    var twitch by mutableStateOf<List<Category>>(emptyList())
        private set
    private var twitchFor: String? = null
    private var twitchAt = 0L
    /** Spectateurs par catégorie, relus au plus toutes les 2 min. */
    val viewers = mutableStateMapOf<String, Int>()
    private var viewersAt = 0L

    private fun read(): List<Category> =
        parseJsonArray(AppState.prefs.getString("followed_categories", null))?.objects().orEmpty().mapNotNull { o ->
            val id = o.str("id")?.takeIf { validId(it) } ?: return@mapNotNull null
            val box = o.str("box") ?: ""
            Category(id, (o.str("name") ?: "").take(100), if (box.startsWith("https://") && box.length <= 500) box else "", null)
        }.distinctBy { it.id }.take(200)

    private fun save() {
        val arr = JSONArray()
        local.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("box", it.boxArt)) }
        AppState.prefs.edit().putString("followed_categories", arr.toString()).apply()
    }

    fun validId(id: String) = id.isNotEmpty() && id.length <= 20 && id.all { it.isDigit() }

    fun isFollowedLocally(id: String) = local.any { it.id == id }
    fun isFollowedOnTwitch(id: String) = twitch.any { it.id == id }
    fun isFollowed(id: String) = id.isNotEmpty() && (isFollowedLocally(id) || isFollowedOnTwitch(id))

    fun toggle(c: Category) {
        if (!validId(c.id)) return
        if (isFollowedLocally(c.id)) {
            local.removeAll { it.id == c.id }
            Logger.info("CATEGORIES", "Catégorie plus suivie", c.name)
        } else {
            local.add(0, Category(c.id, c.name.take(100), c.boxArt.takeIf { it.startsWith("https://") } ?: "", null))
            while (local.size > 200) local.removeAt(local.lastIndex)
            Logger.info("CATEGORIES", "Catégorie suivie", c.name)
            c.viewers?.let { viewers[c.id] = it }
        }
        save()
    }

    /** Twitch puis appareil, sans doublon, dans l'ordre choisi (les plus
     *  regardées d'abord, ou de A à Z), avec leur audience. */
    fun sorted(): List<Category> {
        val seen = HashSet<String>()
        val list = (twitch + local).filter { seen.add(it.id) }
            .map { c -> c.copy(viewers = viewers[c.id] ?: c.viewers) }
        return if (BrowsePrefs.categorySort.value == "name") list.sortedBy { it.name.lowercase() }
               else list.sortedByDescending { it.viewers ?: -1 }
    }

    /** Relit les catégories du compte (session web) et l'audience de toutes. */
    suspend fun refresh(force: Boolean = false) {
        val token = WebSession.token
        val now = System.currentTimeMillis()
        if (token == null || !WebSession.active) {
            twitch = emptyList()
            twitchFor = null
        } else if (force || twitchFor != token || now - twitchAt > 600_000) {
            loadTwitch(token)?.let { list ->
                // Session changée pendant la lecture : ce n'est plus la sienne.
                if (WebSession.token == token) {
                    twitch = list
                    twitchFor = token
                    twitchAt = now
                    list.forEach { c -> c.viewers?.let { viewers[c.id] = it } }
                }
            }
        }
        if (!force && now - viewersAt < 120_000 && (twitch + local).all { it.id in viewers }) return
        val ids = (twitch + local).map { it.id }.distinct()
        if (ids.isEmpty()) return
        val counts = audience(ids)
        if (counts.isNotEmpty()) {
            viewers.putAll(counts)
            viewersAt = now
        }
    }

    /** Catégories suivies sur Twitch : demande la session web (Helix n'a pas
     *  d'équivalent). null si indisponible. */
    private suspend fun loadTwitch(token: String): List<Category>? {
        val q = "query { currentUser { followedGames(first: 100, type: ALL) { nodes { id displayName " +
            "boxArtURL(width: 285, height: 380) viewersCount } } } }"
        val r = WebGql.raw(q, null, token)
        val nodes = r.data?.obj("currentUser")?.obj("followedGames")?.arr("nodes")
        if (nodes == null) {
            Logger.warn("CATEGORIES", "Catégories suivies sur Twitch indisponibles", if (r.code == 0) "réseau" else "HTTP ${r.code}")
            return null
        }
        return nodes.objects().mapNotNull { n ->
            val id = n.str("id")?.takeIf { validId(it) } ?: return@mapNotNull null
            Category(id, n.str("displayName") ?: "", n.str("boxArtURL") ?: "", n.int("viewersCount"))
        }
    }

    /** Spectateurs de ces catégories : 15 alias par requête au plus. */
    suspend fun audience(ids: List<String>): Map<String, Int> = coroutineScope {
        val parts = ids.filter { validId(it) }.distinct().chunked(15).map { chunk ->
            async {
                val fields = chunk.mapIndexed { i, id -> "g$i: game(id: \"$id\") { id viewersCount }" }.joinToString(" ")
                val d = Gql.request("query { $fields }") ?: return@async emptyMap<String, Int>()
                val out = HashMap<String, Int>()
                for (i in chunk.indices) {
                    val g = d.obj("g$i") ?: continue
                    val id = g.str("id") ?: continue
                    g.int("viewersCount")?.let { out[id] = it }
                }
                out
            }
        }
        val out = HashMap<String, Int>()
        parts.awaitAll().forEach { out.putAll(it) }
        out
    }
}
