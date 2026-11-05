package com.mxfia19.twitchunblock.core

import org.json.JSONObject

/** VOD trouvée par la recherche, avec sa chaîne. */
data class SearchVod(val vod: Vod, val login: String, val name: String, val gameId: String)

/** Playlist (collection) d'une chaîne : ses vidéos (50 au plus) et leur nombre. */
data class Playlist(val id: String, val title: String, val description: String, val total: Int, val videos: List<Vod>)

/** Suite d'une liste : GQL sans compte (Client-ID d'Android) ou Helix avec le
 *  compte (`after` nul : Helix depuis le début). Les deux ne se mélangent pas :
 *  un curseur GQL envoyé à Helix, ou l'inverse, serait refusé. */
sealed class ListCursor {
    data class Gql(val after: String) : ListCursor()
    data class Helix(val after: String?) : ListCursor()
}

/** Pages ajoutées par NavApi.more : éléments nouveaux, suite (la même après une
 *  panne, pour réessayer), `locked` : Twitch refuse la suite et aucun compte ne
 *  peut prendre le relais. */
class MoreResult<T>(val fresh: List<T>, val cursor: ListCursor?, val locked: Boolean)

/**
 * Série 6, navigation : recherche complète (chaînes avec abonnés, lives, VODs,
 * catégories en 570×760), temps forts et playlists d'une chaîne, pages
 * suivantes par le compte (Helix) quand Twitch refuse GQL. Comme TwitchAPI.swift
 * (searchStreamsGQL, searchVideosGQL, getHighlights, getCollections,
 * loadMorePages).
 */
object NavApi {
    private const val STREAM_FIELDS = "title viewersCount createdAt previewImageURL(width: 440, height: 248) " +
        "game { id displayName } freeformTags { name }"

    private fun streamFrom(user: JSONObject, s: JSONObject): TwitchStream? {
        val login = user.str("login") ?: return null
        val game = s.obj("game")
        return TwitchStream(
            id = user.str("id") ?: login, login = login, name = user.str("displayName") ?: login,
            title = s.str("title") ?: "", game = game?.str("displayName") ?: game?.str("name") ?: "",
            viewers = s.int("viewersCount") ?: 0, thumbnail = s.str("previewImageURL") ?: "",
            tags = s.arr("freeformTags")?.objects()?.mapNotNull { it.str("name") } ?: emptyList(),
            gameId = game?.str("id") ?: "", startedAt = s.str("createdAt"),
        )
    }

    private fun vodFrom(v: JSONObject): Vod? {
        val id = v.str("id") ?: return null
        return Vod(id, v.str("title") ?: "", v.str("previewThumbnailURL") ?: "",
                   v.str("publishedAt") ?: v.str("createdAt") ?: "", v.int("lengthSeconds") ?: 0)
    }

    /** Jaquette en 570×760 quelle que soit l'adresse reçue : gabarit
     *  {width}×{height} (Helix) ou taille fixe (GQL, recherche Helix en 52×72). */
    fun sharpBoxArt(url: String): String = url.replace("{width}", "570").replace("{height}", "760")
        .replace(Regex("-\\d+x\\d+(\\.(?:jpe?g|png|webp))$"), "-570x760$1")

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Le nom exact d'abord (« valorant » → VALORANT), puis l'ordre de Twitch. */
    fun rankCategories(list: List<Category>, query: String): List<Category> {
        val q = fold(query)
        return list.filter { fold(it.name) == q } + list.filter { fold(it.name) != q }
    }

    // ── Recherche ──
    /** Chaînes (30), en direct d'abord, avec leurs abonnés. null : Twitch n'a pas répondu. */
    suspend fun searchChannels(query: String): List<Suggestion>? {
        val q = "query(\$q: String!) { searchUsers(userQuery: \$q, first: 30) { edges { node { login displayName " +
            "profileImageURL(width: 70) followers { totalCount } stream { viewersCount game { displayName } } } } } }"
        val edges = Gql.request(q, Gql.vars("q" to query))?.obj("searchUsers")?.arr("edges") ?: return null
        val list = edges.objects().mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val login = n.str("login") ?: return@mapNotNull null
            val s = n.obj("stream")
            Suggestion(login, n.str("displayName") ?: login, n.str("profileImageURL"), s?.let { it.int("viewersCount") ?: 0 },
                       s?.obj("game")?.str("displayName"), followers = n.obj("followers")?.int("totalCount"))
        }.distinctBy { it.login }
        // En direct d'abord, sans changer l'ordre de Twitch entre elles.
        return list.filter { it.isLive } + list.filter { !it.isLive }
    }

    /** Lives dont le titre, les tags, la catégorie ou le nom correspondent (30). */
    suspend fun searchStreams(query: String): List<TwitchStream>? {
        val q = "query(\$q: String!) { searchStreams(userQuery: \$q, first: 30) { edges { node { $STREAM_FIELDS " +
            "broadcaster { id login displayName } } } } }"
        val edges = Gql.request(q, Gql.vars("q" to query))?.obj("searchStreams")?.arr("edges") ?: return null
        return edges.objects().mapNotNull { e ->
            val n = e.obj("node")
            val b = n?.obj("broadcaster")
            if (n != null && b != null) streamFrom(b, n) else null
        }
    }

    /** VODs trouvées (celles de la page de résultats de Twitch) : l'index des
     *  VODs seul (30), puis, si Twitch refuse ce ciblage, l'aperçu des résultats. */
    suspend fun searchVideos(query: String): List<SearchVod>? {
        val fields = "videos { edges { item { ... on Video { id title lengthSeconds publishedAt " +
            "previewThumbnailURL(width: 320, height: 180) owner { login displayName } game { id } } } } }"
        val requests = listOf(
            "query(\$q: String!) { searchFor(userQuery: \$q, platform: \"web\", target: { index: VOD, limit: 30 }) { $fields } }",
            "query(\$q: String!) { searchFor(userQuery: \$q, platform: \"web\") { $fields } }",
        )
        for (q in requests) {
            val found = Gql.request(q, Gql.vars("q" to query))?.obj("searchFor") ?: continue
            // `videos` nul : aucune VOD trouvée.
            val edges = found.obj("videos")?.arr("edges")?.objects().orEmpty()
            return edges.mapNotNull { e ->
                val v = e.obj("item") ?: return@mapNotNull null
                val vod = vodFrom(v) ?: return@mapNotNull null
                val owner = v.obj("owner")
                val login = owner?.str("login") ?: ""
                SearchVod(vod, login, owner?.str("displayName") ?: login, v.obj("game")?.str("id") ?: "")
            }.distinctBy { it.vod.id }
        }
        return null
    }

    /** Catégories (jaquettes nettes), le nom exact en tête. GQL d'abord ; Helix
     *  avec le compte en secours, son audience complétée par GQL. */
    suspend fun searchCategories(query: String): List<Category>? {
        val q = "query(\$q: String!) { searchCategories(query: \$q, first: 30) { edges { node { id displayName " +
            "boxArtURL(width: 570, height: 760) viewersCount } } } }"
        var list = Gql.request(q, Gql.vars("q" to query))?.obj("searchCategories")?.arr("edges")?.objects()?.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val id = n.str("id") ?: return@mapNotNull null
            Category(id, n.str("displayName") ?: "", sharpBoxArt(n.str("boxArtURL") ?: ""), n.int("viewersCount"))
        }
        if (list == null && AppState.token != null) list = helixSearchCategories(query)
        val ranked = rankCategories(list?.distinctBy { it.id } ?: return null, query)
        val missing = ranked.filter { it.viewers == null }.map { it.id }
        if (missing.isEmpty()) return ranked
        val viewers = FollowedCategories.audience(missing)
        return ranked.map { if (it.viewers == null) it.copy(viewers = viewers[it.id]) else it }
    }

    private suspend fun helixSearchCategories(query: String): List<Category>? {
        val r = Auth.helix("https://api.twitch.tv/helix/search/categories?first=50&query=" + Http.encode(query)) ?: return null
        if (r.code != 200) return null
        return parseJson(r.body)?.arr("data")?.objects()?.mapNotNull { helixCategory(it) }
    }

    // ── Page d'une chaîne ──
    /** Temps forts (les 50 plus récents). null : Twitch n'a pas répondu. */
    suspend fun highlights(login: String): List<Vod>? {
        val q = "query(\$l: String!) { user(login: \$l) { videos(first: 50, type: HIGHLIGHT, sort: TIME) { edges { node { " +
            "id title lengthSeconds createdAt previewThumbnailURL(width: 320, height: 180) } } } } }"
        val d = Gql.request(q, Gql.vars("l" to login.lowercase())) ?: return null
        return d.obj("user")?.obj("videos")?.arr("edges")?.objects().orEmpty().mapNotNull { e -> e.obj("node")?.let { vodFrom(it) } }
            .distinctBy { it.id }
    }

    /** Playlists (20 au plus, 50 vidéos chacune), vides exclues. null : pas de réponse. */
    suspend fun collections(login: String): List<Playlist>? {
        val q = "query(\$l: String!) { user(login: \$l) { collections(first: 20) { edges { node { id title description " +
            "items(first: 50) { totalCount edges { node { ... on Video { id title lengthSeconds createdAt " +
            "previewThumbnailURL(width: 320, height: 180) } } } } } } } } }"
        val d = Gql.request(q, Gql.vars("l" to login.lowercase())) ?: return null
        return d.obj("user")?.obj("collections")?.arr("edges")?.objects().orEmpty().mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val id = n.str("id") ?: return@mapNotNull null
            val items = n.obj("items") ?: return@mapNotNull null
            val videos = items.arr("edges")?.objects().orEmpty().mapNotNull { ie -> ie.obj("node")?.let { vodFrom(it) } }
                .distinctBy { it.id }
            if (videos.isEmpty()) return@mapNotNull null
            Playlist(id, n.str("title") ?: "", n.str("description") ?: "", items.int("totalCount") ?: videos.size, videos)
        }
    }

    suspend fun userId(login: String): String? =
        Gql.request("query(\$l: String!) { user(login: \$l) { id } }", Gql.vars("l" to login.lowercase()))?.obj("user")?.str("id")

    // ── Pages suivantes par le compte (Helix) ──
    private fun withAfter(url: String, after: String?) = if (after == null) url else url + "&after=" + Http.encode(after)

    /** Une page Helix : éléments et curseur de la suite. null : pas de compte, panne ou refus. */
    private suspend fun helixPage(url: String): Pair<List<JSONObject>, String?>? {
        val r = Auth.helix(url) ?: return null
        if (r.code != 200) {
            Logger.warn("HELIX", "Page suivante refusée", "HTTP ${r.code}")
            return null
        }
        val json = parseJson(r.body) ?: return null
        val arr = json.arr("data")?.objects().orEmpty()
        return Pair(arr, json.obj("pagination")?.str("cursor")?.takeIf { it.isNotEmpty() && arr.isNotEmpty() })
    }

    private fun helixStream(d: JSONObject): TwitchStream? {
        val login = d.str("user_login") ?: return null
        return TwitchStream(
            id = d.str("user_id") ?: login, login = login, name = d.str("user_name") ?: login,
            title = d.str("title") ?: "", game = d.str("game_name") ?: "", viewers = d.int("viewer_count") ?: 0,
            thumbnail = d.str("thumbnail_url") ?: "", tags = d.arr("tags")?.strings() ?: emptyList(),
            gameId = d.str("game_id") ?: "", startedAt = d.str("started_at"),
        )
    }

    private fun helixCategory(d: JSONObject): Category? {
        val id = d.str("id") ?: return null
        return Category(id, d.str("name") ?: "", sharpBoxArt(d.str("box_art_url") ?: ""), null)
    }

    /** Lives (top, ou d'une catégorie), 100 par page ; `lang` au format Helix (« fr », « zh-hk »). */
    suspend fun helixStreams(gameId: String?, lang: String?, after: String?): Page<TwitchStream>? {
        var url = "https://api.twitch.tv/helix/streams?first=100"
        if (gameId != null) url += "&game_id=" + Http.encode(gameId)
        if (lang != null) url += "&language=" + Http.encode(lang)
        val (arr, next) = helixPage(withAfter(url, after)) ?: return null
        return Page(arr.mapNotNull { helixStream(it) }, next)
    }

    /** Top des catégories ; Helix ne donne pas l'audience : complétée par GQL. */
    suspend fun helixTopCategories(after: String?): Page<Category>? {
        val (arr, next) = helixPage(withAfter("https://api.twitch.tv/helix/games/top?first=100", after)) ?: return null
        val list = arr.mapNotNull { helixCategory(it) }
        val viewers = FollowedCategories.audience(list.map { it.id })
        return Page(list.map { it.copy(viewers = viewers[it.id]) }, next)
    }

    private fun iso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    /** Clips d'une chaîne sur la période (LAST_DAY, LAST_WEEK, LAST_MONTH, ALL_TIME). */
    suspend fun helixClips(broadcasterId: String, period: String, after: String?): Page<Clip>? {
        var url = "https://api.twitch.tv/helix/clips?broadcaster_id=" + Http.encode(broadcasterId) + "&first=100"
        val days = mapOf("LAST_DAY" to 1L, "LAST_WEEK" to 7L, "LAST_MONTH" to 30L)[period]
        if (days != null) {
            // Sans date de fin, Helix s'arrête une semaine après le début.
            val now = System.currentTimeMillis()
            url += "&started_at=" + Http.encode(iso(now - days * 86_400_000L)) + "&ended_at=" + Http.encode(iso(now))
        }
        val (arr, next) = helixPage(withAfter(url, after)) ?: return null
        val list = arr.mapNotNull { c ->
            val slug = c.str("id") ?: return@mapNotNull null
            Clip(slug, c.str("title") ?: "", c.str("thumbnail_url") ?: "", c.int("view_count") ?: 0,
                 (c.dbl("duration") ?: 0.0).toInt(), c.str("created_at") ?: "", c.str("creator_name"))
        }
        return Page(list, next)
    }

    /**
     * Suite d'une liste, sans doublons (les lives bougent d'une page à l'autre).
     * GQL (Client-ID d'Android) ; si Twitch le refuse, Helix avec le compte, qui
     * repart du début : jusqu'à 3 pages pour trouver du neuf. Sans compte (ou
     * `withAccount` faux), la liste s'arrête là. Comme loadMorePages (iOS).
     */
    suspend fun <T> more(cursor: ListCursor, current: List<T>, id: (T) -> String,
                         gql: suspend (String) -> Page<T>?, helix: suspend (String?) -> Page<T>?,
                         withAccount: Boolean = true): MoreResult<T> {
        var c: ListCursor? = cursor
        val seen = current.map(id).toHashSet()
        val fresh = mutableListOf<T>()
        for (round in 0 until 3) {
            val step = c ?: break
            when (step) {
                is ListCursor.Gql -> {
                    val page = gql(step.after) ?: return MoreResult(fresh, c, false)
                    if (page.refused) {
                        if (!withAccount || AppState.token == null) return MoreResult(fresh, null, true)
                        Logger.info("HELIX", "Suite de la liste par le compte")
                        c = ListCursor.Helix(null)
                        continue
                    }
                    fresh += page.items.filter { seen.add(id(it)) }
                    c = page.next?.let { ListCursor.Gql(it) }
                }
                is ListCursor.Helix -> {
                    if (!withAccount || AppState.token == null) return MoreResult(fresh, null, true)
                    val page = helix(step.after) ?: return MoreResult(fresh, c, false)
                    fresh += page.items.filter { seen.add(id(it)) }
                    c = page.next?.let { ListCursor.Helix(it) }
                }
            }
            if (fresh.isNotEmpty()) break
        }
        return MoreResult(fresh, c, false)
    }
}
