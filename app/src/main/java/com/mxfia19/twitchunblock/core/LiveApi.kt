package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Lancement d'un direct : infos de la chaîne (GQL), puis les sources dans
 * l'ordre des réglages — Luminous Asie, proxy albanais (TTV.LOL), Luminous
 * Europe, Twitch officiel — et le Worker en dernier recours. Compte sans
 * pub sur la chaîne (abonné, Turbo ; session web) : son flux d'abord. Comme
 * getLive (iOS).
 */
object LiveApi {
    /** Jeton de lecture anonyme (valeur, signature) d'un direct ou d'une VOD. */
    suspend fun accessToken(id: String, live: Boolean): Pair<String, String>? {
        val field = if (live) "streamPlaybackAccessToken(channelName" else "videoPlaybackAccessToken(id"
        val q = "query { $field: \"${Gql.str(id)}\", params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) { value signature } }"
        val data = Gql.raw(q)?.obj("data")
        val tok = data?.obj(if (live) "streamPlaybackAccessToken" else "videoPlaybackAccessToken")
        val value = tok?.str("value")
        val sig = tok?.str("signature")
        if (value == null || sig == null) {
            Logger.warn("TOKEN", "Jeton vide", id)
            return null
        }
        return Pair(value, sig)
    }

    suspend fun getLive(channel: String, onSource: (String) -> Unit = {}): LiveResult = coroutineScope {
        val login = channel.trim().lowercase()
        // Mis tel quel dans les adresses des sources et du Worker : rien
        // d'autre qu'un pseudo Twitch (ni « ? », ni « & », ni « / »).
        if (!Links.isValidLogin(login)) return@coroutineScope LiveResult(LiveInfo(login, error = "not_found"))
        Logger.info("LIVE", "Lancement du direct", login)
        val q = "query { user(login: \"${Gql.str(login)}\") { id profileImageURL(width: 70) " +
            "stream { title game { name } previewImageURL(width: 320, height: 180) viewersCount createdAt archiveVideo { id } } } }"
        val gqlTask = async { Gql.raw(q) }
        val tokenTask = async { accessToken(login, live = true) }
        // En même temps que le reste : 6 s au plus (jeton d'intégrité compris).
        val accountTask = async {
            if (Features.subAdFree.value) com.mxfia19.twitchunblock.web.AccountPlayback.forLaunch(login)
            else com.mxfia19.twitchunblock.web.AccountPlayback.Result.Unavailable
        }
        val gql = gqlTask.await()
        val token = tokenTask.await()

        // « user » nul sans erreur : la chaîne n'existe pas. Sans réponse
        // lisible, on ne sait rien : les sources sont essayées quand même.
        val data = gql?.obj("data")
        val hasErrors = (gql?.arr("errors")?.length() ?: 0) > 0
        val user = data?.obj("user")
        if (user == null && data != null && data.isJsonNull("user") && !hasErrors) {
            Logger.error("LIVE", "Chaîne introuvable", login)
            accountTask.cancel()
            return@coroutineScope LiveResult(LiveInfo(login, error = "not_found"))
        }
        val avatar = user?.str("profileImageURL")
        val userId = user?.str("id")
        val stream = user?.obj("stream")
        if (user != null && stream == null) {
            Logger.warn("LIVE", "Chaîne hors ligne", login)
            accountTask.cancel()
            return@coroutineScope LiveResult(LiveInfo(login, avatar = avatar, userId = userId, error = "offline"))
        }

        val lowLatency = AppState.lowLatency
        var found = Master()
        var used: String? = null
        var workerInfo: JSONObject? = null
        var subscriber: Boolean? = null
        when (val account = accountTask.await()) {
            is com.mxfia19.twitchunblock.web.AccountPlayback.Result.Ok -> {
                val pat = account.token
                subscriber = pat.subscriber
                if (pat.adFree && !pat.forbidden) {
                    found = usher(login, Pair(pat.value, pat.signature), lowLatency)
                    if (!found.isEmpty) {
                        used = "account"
                        Logger.success("LIVE", "Direct par le compte (sans pub)", "${found.links.size} qualités")
                    } else {
                        Logger.warn("LIVE", "Flux du compte indisponible", "sources habituelles…")
                    }
                } else {
                    Logger.info("LIVE", "Compte pas sans pub sur cette chaîne", "sources habituelles")
                }
            }
            else -> {}
        }
        val steps = AppState.liveSourceSteps()
        Logger.info("LIVE", "Ordre des sources", steps.joinToString(" → ").uppercase())
        for (src in steps) {
            if (!found.isEmpty) break
            onSource(src)
            when (src) {
                "al" -> found = ttvlol(login, lowLatency)
                "twitch" -> if (token != null) found = usher(login, token, lowLatency)
                in Config.LIVE_PROXY_HOSTS -> found = luminous(listOf(Config.LIVE_PROXY_HOSTS.getValue(src)), login, lowLatency)
                else -> {
                    val w = worker(login)
                    found = w.first
                    workerInfo = w.second
                }
            }
            if (!found.isEmpty) used = src
        }

        var title = stream?.str("title") ?: ""
        var game = stream?.obj("game")?.str("name") ?: ""
        var thumbnail = stream?.str("previewImageURL") ?: ""
        var shownAvatar = avatar
        // Twitch n'a rien dit : titre, jeu, miniature et avatar du Worker.
        val info = workerInfo
        if (user == null && info != null) {
            info.str("title")?.takeIf { it.isNotEmpty() }?.let { title = it }
            info.str("game")?.takeIf { it.isNotEmpty() }?.let { game = it }
            info.str("thumbnail")?.takeIf { it.isNotEmpty() }?.let { thumbnail = it }
            info.str("avatar")?.takeIf { it.isNotEmpty() }?.let { shownAvatar = it }
        }
        val error = if (found.isEmpty) (if (user == null) "network" else "no_source") else null
        if (error != null) Logger.error("LIVE", if (user == null) "Ni Twitch ni aucune source n'ont répondu" else "Aucune source n'a répondu", login)
        LiveResult(
            info = LiveInfo(login, title, game, thumbnail, shownAvatar, userId,
                            stream?.int("viewersCount") ?: 0, stream?.str("createdAt"), error = error),
            master = found,
            source = used,
            dvrVideoId = stream?.obj("archiveVideo")?.str("id"),
            subscriber = subscriber,
        )
    }

    private suspend fun luminous(hosts: List<String>, login: String, lowLatency: Boolean): Master {
        for (host in hosts) {
            Logger.info("LIVE", "Tentative proxy…", host)
            val url = "https://$host/live/$login?allow_source=true&allow_audio_only=true&fast_bread=$lowLatency"
            val r = Http.get(url, Config.TWITCH_HEADERS, timeout = 8)
            if (r != null && r.code == 200) {
                val m = parseMaster(r.body, url)
                if (!m.isEmpty) {
                    Logger.success("LIVE", "Proxy OK ($host)", "${m.links.size} qualités")
                    return m
                }
                Logger.warn("LIVE", "Proxy $host : playlist vide", "serveur suivant…")
            } else {
                Logger.warn("LIVE", "Proxy $host : échec (${r?.code ?: "réseau"})", "serveur suivant…")
            }
        }
        return Master()
    }

    /** Proxy au format TTV.LOL : la requête usher encodée après /playlist/<chaîne>.m3u8. */
    private suspend fun ttvlol(login: String, lowLatency: Boolean): Master {
        val query = Http.encode("allow_source=true&allow_audio_only=true&fast_bread=$lowLatency")
        for (host in Config.TTVLOL_HOSTS) {
            Logger.info("LIVE", "Tentative proxy TTV.LOL…", host)
            val url = "https://$host/playlist/$login.m3u8%3F$query"
            val r = Http.get(url, mapOf("X-Donate-To" to "https://ttv.lol/donate"), timeout = 10)
            if (r == null || r.code != 200 || !r.body.contains("#EXT-X-STREAM-INF")) {
                Logger.warn("LIVE", "Proxy $host indisponible", "source suivante…")
                continue
            }
            val m = parseMaster(r.body, url)
            if (!m.isEmpty) {
                Logger.success("LIVE", "Proxy TTV.LOL OK ($host)", "${m.links.size} qualités")
                return m
            }
        }
        return Master()
    }

    /** Flux officiel de Twitch (avec ses pubs). */
    private suspend fun usher(login: String, token: Pair<String, String>, lowLatency: Boolean): Master {
        if (login.isEmpty() || !login.all { it.isLetterOrDigit() || it == '_' }) return Master()
        Logger.info("LIVE", "Tentative Twitch officiel…")
        val url = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8" +
            "?allow_source=true&allow_audio_only=true&allow_spectre=true" +
            "&fast_bread=$lowLatency&low_latency=$lowLatency&player_backend=mediaplayer" +
            "&playlist_include_framerate=true&segment_preference=4" +
            "&sig=${Http.encode(token.second)}&token=${Http.encode(token.first)}"
        val r = Http.get(url, timeout = 8)
        if (r == null || r.code != 200) return Master()
        val m = parseMaster(r.body, url)
        if (!m.isEmpty) Logger.success("LIVE", "Twitch officiel : ${m.links.size} qualités")
        return m
    }

    /** Worker : sa propre chaîne de sources, en dernier recours. */
    private suspend fun worker(login: String): Pair<Master, JSONObject?> {
        Logger.info("LIVE", "Tentative Worker…")
        val json = Workers.json("/api/get-live?name=$login&proxy=false") ?: return Pair(Master(), null)
        val links = linksOf(json.obj("links"))
        if (links.isEmpty()) return Pair(Master(), json)
        val variants = workerVariants(json.obj("variants"))
        Logger.success("LIVE", "Worker : ${links.size} qualités")
        return Pair(Master(links, variants, synthesizeMaster(links, variants)), json)
    }

    /** Spectateurs et début du direct, sans relancer les sources. */
    suspend fun streamStats(login: String): Pair<Int, String?>? {
        val d = Gql.request("query(\$l: String!) { user(login: \$l) { stream { viewersCount createdAt } } }",
                            Gql.vars("l" to login.lowercase())) ?: return null
        val s = d.obj("user")?.obj("stream") ?: return Pair(0, null)
        return Pair(s.int("viewersCount") ?: 0, s.str("createdAt"))
    }
}
