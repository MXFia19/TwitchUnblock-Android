package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject

/**
 * Vérification de la session web : elle n'a pas de date d'expiration connue
 * (déconnexion ailleurs, mot de passe changé, révocation), et sans
 * vérification on ne s'en apercevait que quand les points cessaient de
 * répondre. Vérifiée au lancement et à chaque connexion (WebSession.save).
 * Comme validateWebSession et webSessionIdentity (iOS).
 */
object WebSessionCheck {
    sealed class Verdict {
        data class Valid(val id: String, val login: String) : Verdict()
        object Invalid : Verdict()
        /** Panne réseau ou réponse inattendue : on ne tranche pas. */
        object Unknown : Verdict()
    }

    /** Compte de la session, d'après Twitch. Invalid seulement quand Twitch
     *  dit explicitement que le jeton ne vaut rien. */
    suspend fun identity(token: String): Verdict {
        val body = JSONObject().put("query", "query { currentUser { id login } }").toString()
        val r = Http.postJson("https://gql.twitch.tv/gql", body, mapOf(
            "Client-ID" to WebSession.CLIENT_ID,
            "Authorization" to "OAuth $token",
            "User-Agent" to WebSession.UA,
        ), timeout = 15)
        if (r == null) {
            Logger.debug("WEB", "Vérification impossible", "réseau — session conservée")
            return Verdict.Unknown
        }
        if (r.code == 401 || r.code == 403) return Verdict.Invalid
        if (r.code != 200) return Verdict.Unknown
        val json = parseJson(r.body) ?: return Verdict.Unknown
        val data = json.obj("data") ?: return Verdict.Unknown
        val user = data.obj("currentUser")
        if (user == null) {
            // currentUser nul = jeton refusé, même en HTTP 200 ; sauf avec des
            // « errors » : panne passagère de Twitch, la session reste.
            if ((json.arr("errors")?.length() ?: 0) > 0) return Verdict.Unknown
            return Verdict.Invalid
        }
        val id = user.str("id") ?: return Verdict.Unknown
        return Verdict.Valid(id, user.str("login") ?: "")
    }

    /** Vérifie la session en place : expirée si Twitch la refuse (cookies
     *  effacés, pour que « Rétablir » demande une vraie connexion), retirée
     *  si elle est à un autre compte que celui de l'app. Une panne ne change
     *  rien : la couper sur un Wi-Fi capricieux serait pire. */
    suspend fun validate() {
        val token = WebSession.token ?: return
        if (WebSession.expired) return
        val verdict = identity(token)
        // Autre session entre-temps (nouvelle connexion, déconnexion) : ce
        // verdict-là ne la concerne pas.
        if (WebSession.token != token) return
        when (verdict) {
            is Verdict.Unknown -> Unit
            is Verdict.Invalid -> {
                Logger.warn("WEB", "Session web expirée", "refusée par Twitch à la vérification")
                WebSession.markExpired(token)
            }
            is Verdict.Valid -> {
                Logger.debug("WEB", "Session web valide", "@${verdict.login.ifEmpty { "?" }}")
                WebSession.confirmLogin(verdict.login)
                val app = AppState.user
                if (AppState.token != null && app != null && app.id.isNotEmpty() && verdict.id != app.id) {
                    WebSession.refuse(verdict.login.ifEmpty { WebSession.login ?: "?" })
                }
            }
        }
    }
}
