package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Chat d'une VOD, rejoué au rythme de la lecture. Twitch refuse la suite par
 * curseur (intégrité) : chaque page repart du dernier instant reçu, les
 * doublons du chevauchement sont écartés. Les messages passent dans la liste
 * quand la vidéo atteint leur instant. Hors ligne : le chat gardé avec une
 * VOD téléchargée. Comme VodChatService (iOS).
 */
class VodChat(private val videoId: String, private val scope: CoroutineScope, private val offlineFile: File? = null) {
    val messages = mutableStateListOf<ChatMessage>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set

    private var channelId: String? = null
    private var ready = false
    private val buffer = ArrayList<Pair<Double, ChatMessage>>()
    private val seen = HashMap<String, Double>()
    private var fetchedUntil = -1.0
    private var hasNext = true
    private var lastOffset = -1.0
    private var fetching = false
    private var generation = 0
    private var retryAt = 0L
    private var failures = 0
    /** Hors ligne : tous les commentaires, triés, et le prochain à faire passer. */
    private var offline: List<Pair<Double, ChatMessage>>? = null
    private var offlineNext = 0

    fun start() {
        scope.launch {
            val file = offlineFile
            if (file != null && file.exists()) {
                val saved = parseJson(file.readText())
                channelId = saved?.str("channelId")
                channelId?.let { Emotes.loadChannel(it); Badges.loadChannel(it) }
                Emotes.loadGlobal()
                Badges.loadGlobal()
                offline = saved?.arr("comments")?.objects()?.mapNotNull { build(it) }?.sortedBy { it.first } ?: emptyList()
                Logger.info("VODCHAT", "Chat hors ligne", "${offline?.size ?: 0} messages")
                ready = true
                return@launch
            }
            channelId = owner(videoId)
            Emotes.loadGlobal()
            Badges.loadGlobal()
            channelId?.let { Emotes.loadChannel(it); Badges.loadChannel(it) }
            ready = true
        }
    }

    /** Position de lecture (s), à appeler régulièrement. */
    fun update(position: Double) {
        if (!ready || position < 0 || position.isNaN()) return
        val all = offline
        if (all != null) {
            releaseOffline(all, position)
            return
        }
        // Premier appel, retour en arrière, ou grand saut : on recharge la fenêtre.
        if (lastOffset < 0 || position < lastOffset - 2 || position > lastOffset + 30) {
            lastOffset = position
            seek(position)
            return
        }
        lastOffset = position
        release(position)
        if (hasNext && !fetching && fetchedUntil < position + LOOKAHEAD && System.currentTimeMillis() >= retryAt) {
            val from = if (fetchedUntil < 0) position else fetchedUntil
            scope.launch { load(maxOf(0, from.toInt()), first = false) }
        }
    }

    private fun seek(position: Double) {
        generation++
        val gen = generation
        buffer.clear()
        seen.clear()
        fetchedUntil = -1.0
        hasNext = true
        messages.clear()
        retryAt = 0L
        failures = 0
        error = false
        scope.launch {
            load(position.toInt(), first = true)
            if (gen == generation) release(lastOffset)
        }
    }

    private suspend fun load(offset: Int, first: Boolean) {
        val gen = generation
        fetching = true
        if (first) loading = true
        val page = page(videoId, offset)
        if (gen != generation) return
        fetching = false
        loading = false
        if (page == null) {
            failures++
            error = true
            retryAt = System.currentTimeMillis() + minOf(30_000L, 3_000L shl minOf(failures - 1, 4))
            return
        }
        failures = 0
        error = false
        var fresh = 0
        var newest = fetchedUntil
        for (node in page.first) {
            val id = node.str("id") ?: continue
            if (seen.containsKey(id)) continue
            val at = node.dbl("contentOffsetSeconds") ?: 0.0
            seen[id] = at
            fresh++
            newest = maxOf(newest, at)
            build(node)?.let { buffer += it }
        }
        buffer.sortBy { it.first }
        // Une page sans rien de neuf ferait reboucler sur la même position.
        fetchedUntil = if (fresh > 0) newest else maxOf(fetchedUntil, offset.toDouble()) + 5
        hasNext = page.second || fresh > 0
        if (seen.size > 20_000) {
            val keep = seen.filterValues { it >= fetchedUntil - 600 }
            seen.clear()
            seen.putAll(keep)
        }
    }

    private fun release(upTo: Double) {
        var n = 0
        while (buffer.isNotEmpty() && buffer[0].first <= upTo) {
            messages.add(buffer.removeAt(0).second)
            n++
        }
        if (n > 0) trim()
    }

    private fun releaseOffline(all: List<Pair<Double, ChatMessage>>, position: Double) {
        // Retour en arrière ou grand saut : la liste repart de cet instant.
        if (lastOffset < 0 || position < lastOffset - 2 || position > lastOffset + 30) {
            messages.clear()
            offlineNext = all.indexOfFirst { it.first > position - 30 }.let { if (it < 0) all.size else it }
        }
        lastOffset = position
        var n = 0
        while (offlineNext < all.size && all[offlineNext].first <= position) {
            messages.add(all[offlineNext].second)
            offlineNext++
            n++
        }
        if (n > 0) trim()
    }

    private fun trim() {
        while (messages.size > MAX) messages.removeAt(0)
    }

    private fun build(node: JSONObject): Pair<Double, ChatMessage>? {
        val offset = node.dbl("contentOffsetSeconds") ?: 0.0
        val commenter = node.obj("commenter")
        val login = commenter?.str("login") ?: return null
        val msg = node.obj("message") ?: return null
        val tokens = mutableListOf<ChatToken>()
        for (f in msg.arr("fragments")?.objects().orEmpty()) {
            val text = f.str("text") ?: ""
            val emote = f.obj("emote")
            val emoteId = emote?.str("emoteID") ?: emote?.str("id")?.substringBefore(";")
            if (!emoteId.isNullOrEmpty()) {
                tokens += ChatToken.Emote(text.trim(), "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/2.0")
            } else if (text.isNotEmpty()) {
                tokens += ChatText.tokens(text, null, channelId)
            }
        }
        if (tokens.isEmpty()) return null
        val badges = msg.arr("userBadges")?.objects().orEmpty().mapNotNull { b ->
            val set = b.str("setID") ?: return@mapNotNull null
            val version = b.str("version") ?: return@mapNotNull null
            Badges.url("$set/$version", channelId)
        }
        return Pair(offset, ChatMessage(
            id = node.str("id") ?: "v-$offset-$login",
            login = login,
            name = commenter?.str("displayName") ?: login,
            color = ChatColors.parse(msg.str("userColor"), login),
            badges = badges,
            tokens = tokens,
        ))
    }

    companion object {
        private const val MAX = 200
        private const val LOOKAHEAD = 30.0
        /** Requête persistée du site (« VideoCommentsByOffsetOrCursor ») ; si
         *  Twitch change son empreinte, repli en GraphQL brut. */
        private const val COMMENTS_HASH = "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
        private val deviceId = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

        /** Une page de commentaires à partir d'un instant : (nœuds, suite ?).
         *  null : pas de réponse. */
        suspend fun page(videoId: String, offset: Int): Pair<List<JSONObject>, Boolean>? {
            var comments = persisted(videoId, offset)
            if (comments == null) comments = raw(videoId, offset)
            val c = comments ?: return null
            val nodes = c.arr("edges")?.objects()?.mapNotNull { it.obj("node") } ?: emptyList()
            return Pair(nodes, c.obj("pageInfo")?.bool("hasNextPage") ?: false)
        }

        private suspend fun persisted(videoId: String, offset: Int): JSONObject? {
            val body = JSONArray().put(JSONObject()
                .put("operationName", "VideoCommentsByOffsetOrCursor")
                .put("variables", JSONObject().put("videoID", videoId).put("contentOffsetSeconds", offset))
                .put("extensions", JSONObject().put("persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", COMMENTS_HASH))))
            val r = Http.postJson("https://gql.twitch.tv/gql", body.toString(), mapOf(
                "Client-ID" to Config.GQL_CLIENT_ID, "X-Device-Id" to deviceId, "User-Agent" to Config.USER_AGENT,
            )) ?: return null
            val first = parseJsonArray(r.body)?.optJSONObject(0) ?: parseJson(r.body) ?: return null
            if (first.has("errors")) {
                Logger.warn("VODCHAT", "Requête persistée refusée", Gql.errorSummary(first))
                return null
            }
            return first.obj("data")?.obj("video")?.obj("comments")
        }

        private suspend fun raw(videoId: String, offset: Int): JSONObject? {
            val q = "query { video(id: \"${Gql.str(videoId)}\") { comments(contentOffsetSeconds: $offset) { edges { node { " +
                "id contentOffsetSeconds commenter { id login displayName } message { userColor " +
                "userBadges { setID version } fragments { text emote { emoteID } } } } } pageInfo { hasNextPage } } } }"
            val json = Gql.raw(q) ?: return null
            if (json.has("errors")) {
                Logger.warn("VODCHAT", "Commentaires refusés", Gql.errorSummary(json))
                return null
            }
            return json.obj("data")?.obj("video")?.obj("comments")
        }

        /** Chaîne propriétaire de la VOD (emotes et badges de la chaîne). */
        suspend fun owner(videoId: String): String? =
            Gql.request("query(\$id: ID!) { video(id: \$id) { owner { id login } } }", Gql.vars("id" to videoId))
                ?.obj("video")?.obj("owner")?.str("id")

        /** Tout le chat d'une VOD, gardé dans `file` pour le rejouer hors
         *  ligne (téléchargements). Nombre de messages, ou null en échec. */
        suspend fun archive(videoId: String, lengthSeconds: Double, file: File): Int? {
            val channelId = owner(videoId)
            val out = JSONArray()
            val seen = HashSet<String>()
            var position = 0
            var pages = 0
            var failures = 0
            val end = if (lengthSeconds > 0) lengthSeconds else Double.MAX_VALUE
            while (pages < 20_000 && position <= end) {
                val page = page(videoId, position)
                if (page == null) {
                    failures++
                    if (failures > 2) return null
                    delay(failures * 2000L)
                    continue
                }
                failures = 0
                pages++
                var fresh = 0
                var newest = -1.0
                var beyond = false
                for (node in page.first) {
                    val at = node.dbl("contentOffsetSeconds") ?: 0.0
                    if (at > end) { beyond = true; break }
                    val id = node.str("id") ?: continue
                    if (!seen.add(id)) continue
                    fresh++
                    newest = maxOf(newest, at)
                    out.put(compact(node))
                }
                if (beyond || (!page.second && fresh == 0)) break
                position = if (fresh > 0) maxOf(position + 1, newest.toInt()) else position + 5
            }
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("channelId", channelId ?: JSONObject.NULL).put("comments", out).toString())
            Logger.success("DOWNLOAD", "Chat gardé", "$videoId · ${out.length()} messages")
            return out.length()
        }

        /** Le nécessaire d'un commentaire, pour un fichier plus léger. */
        private fun compact(node: JSONObject): JSONObject {
            val commenter = node.obj("commenter")
            val msg = node.obj("message")
            val fragments = JSONArray()
            for (f in msg?.arr("fragments")?.objects().orEmpty()) {
                val e = f.obj("emote")
                val id = e?.str("emoteID") ?: e?.str("id")?.substringBefore(";")
                val o = JSONObject().put("text", f.str("text") ?: "")
                if (id != null) o.put("emote", JSONObject().put("emoteID", id))
                fragments.put(o)
            }
            val badges = JSONArray()
            for (b in msg?.arr("userBadges")?.objects().orEmpty()) {
                badges.put(JSONObject().put("setID", b.str("setID") ?: "").put("version", b.str("version") ?: ""))
            }
            return JSONObject()
                .put("id", node.str("id") ?: "")
                .put("contentOffsetSeconds", node.dbl("contentOffsetSeconds") ?: 0.0)
                .put("commenter", JSONObject().put("login", commenter?.str("login") ?: "")
                    .put("displayName", commenter?.str("displayName") ?: ""))
                .put("message", JSONObject().put("userColor", msg?.str("userColor") ?: JSONObject.NULL)
                    .put("userBadges", badges).put("fragments", fragments))
        }
    }
}
