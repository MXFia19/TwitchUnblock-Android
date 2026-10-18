package com.mxfia19.twitchunblock.core

import org.json.JSONArray
import org.json.JSONObject

/** Listes et pages : accueil, catégories, recherche, chaîne, clips (GQL
 *  public), suivis du compte (Helix). Comme TwitchAPI.swift. */
object BrowseApi {
    private fun streamFrom(user: JSONObject, s: JSONObject): TwitchStream? {
        val login = user.str("login") ?: return null
        val game = s.obj("game")
        return TwitchStream(
            id = user.str("id") ?: login,
            login = login,
            name = user.str("displayName") ?: login,
            title = s.str("title") ?: "",
            game = game?.str("displayName") ?: game?.str("name") ?: "",
            viewers = s.int("viewersCount") ?: 0,
            thumbnail = s.str("previewImageURL") ?: "",
            tags = s.arr("freeformTags")?.objects()?.mapNotNull { it.str("name") } ?: emptyList(),
            gameId = game?.str("id") ?: "",
            startedAt = s.str("createdAt"),
        )
    }

    private fun streamsFrom(edges: JSONArray?): List<TwitchStream> =
        edges?.objects()?.mapNotNull { e ->
            val n = e.obj("node")
            val b = n?.obj("broadcaster")
            if (n != null && b != null) streamFrom(b, n) else null
        } ?: emptyList()

    private fun categoryFrom(n: JSONObject): Category? {
        val id = n.str("id") ?: return null
        return Category(id, n.str("displayName") ?: "", n.str("boxArtURL") ?: "", n.int("viewersCount"))
    }

    private fun langs(lang: String?): List<String>? = lang?.let { listOf(it.uppercase().replace("-", "_")) }

    private const val STREAM_FIELDS = "title viewersCount createdAt previewImageURL(width: 440, height: 248) " +
        "game { id displayName } freeformTags { name }"

    // ── Top et catégories ──
    suspend fun topStreams(lang: String?, after: String?): Page<TwitchStream>? {
        val q = "query(\$langs: [Language!], \$after: Cursor) { streams(first: 30, after: \$after, " +
            "options: { broadcasterLanguages: \$langs }) { edges { cursor node { $STREAM_FIELDS " +
            "broadcaster { id login displayName } } } pageInfo { hasNextPage } } }"
        val page = Gql.listPage(q, Gql.vars("langs" to langs(lang)), after) ?: return null
        if (page.second) return Page(emptyList(), refused = true)
        val streams = page.first?.obj("streams") ?: return null
        return Page(streamsFrom(streams.arr("edges")), Gql.nextCursor(streams))
    }

    suspend fun topCategories(after: String?): Page<Category>? {
        val q = "query(\$after: Cursor) { games(first: 100, after: \$after) { edges { cursor node { id displayName " +
            "boxArtURL(width: 285, height: 380) viewersCount } } pageInfo { hasNextPage } } }"
        val page = Gql.listPage(q, Gql.vars(), after) ?: return null
        if (page.second) return Page(emptyList(), refused = true)
        val games = page.first?.obj("games") ?: return null
        val list = games.arr("edges")?.objects()?.mapNotNull { e -> e.obj("node")?.let { categoryFrom(it) } } ?: emptyList()
        return Page(list, Gql.nextCursor(games))
    }

    suspend fun searchCategories(query: String): List<Category>? {
        val q = "query(\$q: String!) { searchCategories(query: \$q, first: 30) { edges { node { id displayName " +
            "boxArtURL(width: 285, height: 380) viewersCount } } } }"
        val d = Gql.request(q, Gql.vars("q" to query)) ?: return null
        return d.obj("searchCategories")?.arr("edges")?.objects()?.mapNotNull { e -> e.obj("node")?.let { categoryFrom(it) } }
            ?: emptyList()
    }

    suspend fun streamsByCategory(gameId: String, lang: String?, after: String?): Page<TwitchStream>? {
        val q = "query(\$id: ID!, \$langs: [Language!], \$after: Cursor) { game(id: \$id) { streams(first: 100, " +
            "after: \$after, options: { broadcasterLanguages: \$langs }) { edges { cursor node { $STREAM_FIELDS " +
            "broadcaster { id login displayName } } } pageInfo { hasNextPage } } } }"
        val page = Gql.listPage(q, Gql.vars("id" to gameId, "langs" to langs(lang)), after) ?: return null
        if (page.second) return Page(emptyList(), refused = true)
        val streams = page.first?.obj("game")?.obj("streams") ?: return null
        return Page(streamsFrom(streams.arr("edges")), Gql.nextCursor(streams))
    }

    // ── Recherche ──
    suspend fun searchUsers(query: String): List<Suggestion> {
        val q = "query(\$q: String!) { searchUsers(userQuery: \$q, first: 10) { edges { node { login displayName " +
            "profileImageURL(width: 70) stream { viewersCount game { displayName } } } } } }"
        val d = Gql.request(q, Gql.vars("q" to query)) ?: return emptyList()
        val list = d.obj("searchUsers")?.arr("edges")?.objects()?.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val login = n.str("login") ?: return@mapNotNull null
            val s = n.obj("stream")
            Suggestion(login, n.str("displayName") ?: login, n.str("profileImageURL"),
                       s?.let { it.int("viewersCount") ?: 0 }, s?.obj("game")?.str("displayName"))
        } ?: emptyList()
        // Les chaînes en direct d'abord, sans changer l'ordre de Twitch entre elles.
        return list.filter { it.isLive } + list.filter { !it.isLive }
    }

    // ── Page d'une chaîne ──
    suspend fun channelInfo(channel: String): LiveInfo {
        val login = channel.trim().lowercase()
        val q = "query(\$l: String!) { user(login: \$l) { id profileImageURL(width: 150) lastBroadcast { startedAt } " +
            "stream { title game { name } previewImageURL(width: 320, height: 180) viewersCount createdAt } } }"
        val d = Gql.request(q, Gql.vars("l" to login)) ?: return LiveInfo(login, error = "network")
        val user = d.obj("user") ?: return LiveInfo(login, error = "not_found")
        val s = user.obj("stream")
        val lastStart = user.obj("lastBroadcast")?.str("startedAt")
        if (s == null) {
            return LiveInfo(login, avatar = user.str("profileImageURL"), userId = user.str("id"),
                            lastStart = lastStart, error = "offline")
        }
        return LiveInfo(login, s.str("title") ?: "", s.obj("game")?.str("name") ?: "", s.str("previewImageURL") ?: "",
                        user.str("profileImageURL"), user.str("id"), s.int("viewersCount") ?: 0, s.str("createdAt"), lastStart)
    }

    class VideosPage(val videos: List<Vod>, val avatar: String?, val error: String?, val next: String?, val refused: Boolean)

    suspend fun channelVideos(login: String, after: String?): VideosPage {
        val q = "query(\$l: String!, \$after: Cursor) { user(login: \$l) { profileImageURL(width: 150) " +
            "videos(first: 100, type: ARCHIVE, sort: TIME, after: \$after) { edges { cursor node { id title " +
            "previewThumbnailURL(height: 180, width: 320) publishedAt lengthSeconds viewCount } } pageInfo { hasNextPage } } } }"
        val page = Gql.listPage(q, Gql.vars("l" to login.lowercase()), after)
            ?: return VideosPage(emptyList(), null, "network", after, false)
        if (page.second) return VideosPage(emptyList(), null, null, null, true)
        val user = page.first?.obj("user") ?: return VideosPage(emptyList(), null, "not_found", null, false)
        val videos = user.obj("videos")
        val list = videos?.arr("edges")?.objects()?.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val id = n.str("id") ?: return@mapNotNull null
            Vod(id, n.str("title") ?: "", n.str("previewThumbnailURL") ?: "", n.str("publishedAt") ?: "",
                n.int("lengthSeconds") ?: 0, n.int("viewCount") ?: 0)
        } ?: emptyList()
        return VideosPage(list, user.str("profileImageURL"), null, Gql.nextCursor(videos), false)
    }

    /** Clips les plus vus sur la période (LAST_DAY, LAST_WEEK, LAST_MONTH, ALL_TIME). */
    suspend fun clips(login: String, period: String, after: String?): Page<Clip>? {
        val q = "query(\$l: String!, \$p: ClipsPeriod, \$after: Cursor) { user(login: \$l) { clips(first: 100, " +
            "after: \$after, criteria: { period: \$p, sort: VIEWS_DESC }) { edges { cursor node { slug title viewCount " +
            "durationSeconds createdAt thumbnailURL(width: 480, height: 272) curator { displayName } } } " +
            "pageInfo { hasNextPage } } } }"
        val page = Gql.listPage(q, Gql.vars("l" to login.lowercase(), "p" to period), after) ?: return null
        if (page.second) return Page(emptyList(), refused = true)
        val clips = page.first?.obj("user")?.obj("clips") ?: return Page(emptyList())
        val list = clips.arr("edges")?.objects()?.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val slug = n.str("slug") ?: return@mapNotNull null
            Clip(slug, n.str("title") ?: "", n.str("thumbnailURL") ?: "", n.int("viewCount") ?: 0,
                 n.int("durationSeconds") ?: 0, n.str("createdAt") ?: "", n.obj("curator")?.str("displayName"))
        } ?: emptyList()
        return Page(list, Gql.nextCursor(clips))
    }

    /** MP4 signés d'un clip, et sa VOD d'origine. */
    suspend fun clip(slug: String): ClipPlayback? {
        val q = "query(\$s: ID!) { clip(slug: \$s) { title broadcaster { login displayName } video { id } " +
            "videoOffsetSeconds playbackAccessToken(params: { platform: \"web\", playerType: \"site\", " +
            "playerBackend: \"mediaplayer\" }) { signature value } videoQualities { quality frameRate sourceURL } } }"
        val c = Gql.request(q, Gql.vars("s" to slug))?.obj("clip") ?: return null
        val tok = c.obj("playbackAccessToken") ?: return null
        val sig = tok.str("signature") ?: return null
        val value = tok.str("value") ?: return null
        val links = LinkedHashMap<String, String>()
        for (qd in c.arr("videoQualities")?.objects().orEmpty()) {
            val src = qd.str("sourceURL") ?: continue
            if (!src.startsWith("https://")) continue
            val fps = qd.dbl("frameRate") ?: 30.0
            val label = "${qd.str("quality") ?: "?"}p" + if (fps >= 50) Math.round(fps).toString() else ""
            links[label] = src + (if (src.contains("?")) "&" else "?") + "sig=$sig&token=${Http.encode(value)}"
        }
        if (links.isEmpty()) return null
        val b = c.obj("broadcaster")
        return ClipPlayback(links, c.str("title") ?: "Clip", b?.str("login"), b?.str("displayName"),
                            c.obj("video")?.str("id"), c.dbl("videoOffsetSeconds"))
    }

    // ── Suivis sans compte ──
    /** Lesquelles de ces chaînes sont en direct. null : Twitch n'a pas répondu. */
    suspend fun liveByLogins(logins: List<String>): List<TwitchStream>? {
        if (logins.isEmpty()) return emptyList()
        val q = "query(\$l: [String!]) { users(logins: \$l) { id login displayName stream { $STREAM_FIELDS } } }"
        val out = mutableListOf<TwitchStream>()
        for (chunk in logins.chunked(100)) {
            val users = Gql.request(q, Gql.vars("l" to chunk))?.arr("users") ?: return null
            for (u in users.objects()) {
                val s = u.obj("stream") ?: continue
                streamFrom(u, s)?.let { out += it }
            }
        }
        return out.sortedByDescending { it.viewers }
    }

    /** Infos de ces chaînes (avatar, direct éventuel, fin du dernier live). */
    suspend fun channelsByLogins(logins: List<String>): List<ChannelBrief>? {
        if (logins.isEmpty()) return emptyList()
        val q = "query(\$l: [String!]) { users(logins: \$l) { id login displayName profileImageURL(width: 70) " +
            "lastBroadcast { startedAt } videos(first: 1, type: ARCHIVE, sort: TIME) { edges { node { publishedAt lengthSeconds } } } " +
            "stream { $STREAM_FIELDS } } }"
        val out = mutableListOf<ChannelBrief>()
        for (chunk in logins.chunked(100)) {
            val users = Gql.request(q, Gql.vars("l" to chunk))?.arr("users") ?: return null
            for (u in users.objects()) {
                val login = u.str("login") ?: continue
                val stream = u.obj("stream")?.let { streamFrom(u, it) }
                val video = u.obj("videos")?.arr("edges")?.objects()?.firstOrNull()?.obj("node")
                val fromVod = Format.parseDate(video?.str("publishedAt"))?.plus((video?.int("lengthSeconds") ?: 0) * 1000L)
                val started = Format.parseDate(u.obj("lastBroadcast")?.str("startedAt"))
                val lastEnd = listOfNotNull(fromVod, started).maxOrNull()
                out += ChannelBrief(login, u.str("displayName") ?: login, u.str("profileImageURL") ?: "", stream, lastEnd)
            }
        }
        return out
    }

    // ── Compte (Helix) ──
    /** Directs suivis par le compte (300 au plus). Jeton refusé : AuthExpiredException. */
    suspend fun followedStreams(): List<TwitchStream>? {
        val uid = AppState.user?.id ?: return null
        val out = mutableListOf<TwitchStream>()
        val seen = HashSet<String>()
        var cursor: String? = null
        for (page in 0 until 3) {
            var url = "https://api.twitch.tv/helix/streams/followed?user_id=$uid&first=100"
            if (cursor != null) url += "&after=${Http.encode(cursor)}"
            val r = Auth.helix(url)
            if (r == null) {
                if (page == 0) return null
                break
            }
            if (r.code == 401) throw AuthExpiredException()
            if (r.code != 200) {
                if (page == 0) return null
                break
            }
            val json = parseJson(r.body) ?: break
            val arr = json.arr("data")?.objects().orEmpty()
            for (d in arr) {
                val login = d.str("user_login") ?: continue
                if (!seen.add(login)) continue
                out += TwitchStream(
                    id = d.str("id") ?: login, login = login, name = d.str("user_name") ?: login,
                    title = d.str("title") ?: "", game = d.str("game_name") ?: "", viewers = d.int("viewer_count") ?: 0,
                    thumbnail = d.str("thumbnail_url") ?: "",
                    tags = d.arr("tags")?.strings() ?: emptyList(), gameId = d.str("game_id") ?: "",
                    startedAt = d.str("started_at"),
                )
            }
            cursor = json.obj("pagination")?.str("cursor")
            if (cursor.isNullOrEmpty() || arr.isEmpty()) break
        }
        Logger.success("HELIX", "${out.size} directs suivis")
        return out
    }

    /** Toutes les chaînes suivies par le compte (1 000 au plus). */
    suspend fun followedLogins(): List<String>? {
        val uid = AppState.user?.id ?: return null
        val out = mutableListOf<String>()
        var cursor: String? = null
        do {
            var url = "https://api.twitch.tv/helix/channels/followed?user_id=$uid&first=100"
            if (cursor != null) url += "&after=${Http.encode(cursor)}"
            val r = Auth.helix(url) ?: return null
            if (r.code != 200) {
                Logger.warn("HELIX", "Chaînes suivies refusées", "HTTP ${r.code}")
                return null
            }
            val json = parseJson(r.body) ?: return null
            json.arr("data")?.objects()?.forEach { f -> f.str("broadcaster_login")?.let { out += it } }
            cursor = json.obj("pagination")?.str("cursor")
        } while (!cursor.isNullOrEmpty() && out.size < 1000)
        return out
    }
}
