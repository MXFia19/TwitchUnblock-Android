package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Jeton de lecture d'un direct au nom du compte (session web) : Twitch y
 * écrit si le compte verra des pubs (abonné à la chaîne, Turbo). Sert au
 * direct sans pub des abonnés et aux drops. Comme accountPlaybackToken (iOS).
 */
object AccountPlayback {
    class Token(val value: String, val signature: String, val adFree: Boolean, val subscriber: Boolean?, val forbidden: Boolean)

    sealed class Result {
        class Ok(val token: Token) : Result()
        /** Session web refusée par Twitch (401/403). */
        object Expired : Result()
        object Unavailable : Result()
    }

    /** Les demandes continuent après le délai du lancement : le jeton
     *  d'intégrité fabriqué reste en cache pour le direct suivant. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    suspend fun fetch(login: String, webToken: String, useIntegrity: Boolean = true): Result {
        val q = "query(\$l: String!) { streamPlaybackAccessToken(channelName: \$l, " +
            "params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) { value signature } }"
        val body = WebGql.written(q, JSONObject().put("l", login))
        var r = WebGql.post(body, webToken, timeout = 8)
        if (r.code == 401 || r.code == 403) return Result.Expired
        var tries = 0
        while (useIntegrity && tries < 2 && WebGql.errors(r.json).lowercase().contains("integrity")) {
            tries++
            if (tries > 1) Integrity.invalidate()
            val signed = Integrity.get(webToken) ?: break
            r = WebGql.post(body, webToken, signed, timeout = 8)
            if (r.code == 401 || r.code == 403) return Result.Expired
        }
        val pat = r.data?.obj("streamPlaybackAccessToken")
        val value = pat?.str("value")
        val signature = pat?.str("signature")
        if (value == null || signature == null) {
            Logger.warn("TOKEN", "Jeton du compte indisponible", WebGql.errors(r.json).ifEmpty { null })
            return Result.Unavailable
        }
        // Jeton anonyme (« user_id »: null) : session expirée ou ignorée.
        if (!Regex("\"user_id\":\\d").containsMatchIn(value)) {
            Logger.warn("TOKEN", "Jeton du compte anonyme", "session web expirée ?")
            return Result.Unavailable
        }
        val flags = parseJson(value) ?: JSONObject()
        val hideAds = flags.bool("hide_ads")
        val showAds = flags.bool("show_ads")
        val subscriber = flags.bool("subscriber")
        val forbidden = flags.obj("authorization")?.bool("forbidden") ?: false
        // Jamais déduit de « turbo » seul : une chaîne peut refuser Turbo.
        val adFree = hideAds == true || showAds == false
        Logger.info("TOKEN", "Jeton du compte pour $login",
                    "abonné ${flags.bool("subscriber") ?: "?"} · hide_ads ${hideAds ?: "?"} · show_ads ${showAds ?: "?"}" +
                        if (forbidden) " · lecture refusée" else "")
        return Result.Ok(Token(value, signature, adFree, subscriber, forbidden))
    }

    /** Pour le lancement d'un direct : 6 s au plus en tout. */
    suspend fun forLaunch(login: String): Result {
        val token = WebSession.token ?: return Result.Unavailable
        if (WebSession.expired) return Result.Unavailable
        val work = background.async { fetch(login, token) }
        return withTimeoutOrNull(6_000) { work.await() } ?: run {
            Logger.warn("TOKEN", "Jeton du compte trop lent (6 s)", "sources habituelles")
            Result.Unavailable
        }
    }
}
