package com.mxfia19.twitchunblock.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Secrets

/**
 * Session web de Twitch (cookie « auth-token » de twitch.tv, pris dans une
 * page de connexion) : points de chaîne, coffres, prédictions, sondages,
 * drops, suivre sur Twitch, direct sans pub des abonnés. Le jeton de l'API
 * (connexion par code) n'y suffit pas : Twitch rend « communityPoints » nul
 * à un autre Client-ID. Comme TwitchWebAuthManager (iOS). Téléphone
 * seulement, comme l'Apple TV.
 */
object WebSession {
    /** Navigateur annoncé à twitch.tv (page de connexion, jeton d'intégrité)
     *  et aux requêtes GQL qui s'en servent : le même partout. */
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/129.0.0.0 Mobile Safari/537.36"
    const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    var token by mutableStateOf<String?>(null)
        private set
    var login by mutableStateOf<String?>(null)
        private set
    /** Twitch a refusé la session (401) : à rétablir. */
    var expired by mutableStateOf(false)
        private set
    /** Session d'un autre compte que celui de l'app, retirée : son pseudo. */
    var mismatch by mutableStateOf<String?>(null)
        private set

    /** Change à chaque nouvelle session (ou déconnexion) : les services qui
     *  s'en servent repartent de zéro. */
    var generation by mutableStateOf(0)
        private set

    fun init() {
        // Chiffré (Secrets), comme les jetons du compte.
        token = Secrets.read(AppState.prefs, "web_token")?.takeIf { it.length >= 20 }
        login = AppState.prefs.getString("web_login", null)
        checkAccount()
    }

    val active: Boolean get() = token != null && !expired

    /** Identifiant d'appareil de la session web (lectures GQL, jeton du compte). */
    val deviceId: String
        get() = AppState.prefs.getString("twitch_device_id", null) ?: run {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val d = (1..32).map { chars.random() }.joinToString("")
            AppState.prefs.edit().putString("twitch_device_id", d).apply()
            d
        }

    fun save(newToken: String, newLogin: String?) {
        val changed = newToken != token
        token = newToken
        login = newLogin?.lowercase()
        expired = false
        mismatch = null
        Secrets.put(AppState.prefs.edit(), "web_token", newToken).putString("web_login", login).apply()
        if (changed) {
            generation++
            Integrity.invalidate()
        }
        Logger.success("WEB", "Session web enregistrée", "@${login ?: "?"}")
    }

    /** Twitch refuse ce jeton (401) : on le dit, sans l'effacer (la page de
     *  connexion le remplacera). */
    fun markExpired(refused: String) {
        if (refused != token || expired) return
        expired = true
        Logger.warn("WEB", "Session web refusée par Twitch", "à rétablir dans les réglages")
    }

    /** Déconnexion : jeton oublié, cookies et données de twitch.tv effacés. */
    fun clear() {
        if (token != null) Logger.info("WEB", "Session web retirée", login?.let { "@$it" })
        token = null
        login = null
        expired = false
        generation++
        Integrity.invalidate()
        AppState.prefs.edit().remove("web_token").remove("web_login").apply()
        clearCookies()
    }

    fun clearCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            // WebView absente ou en cours de mise à jour : rien à effacer.
        }
    }

    /** Session d'un autre compte que celui de l'app : retirée, pour ne pas
     *  mélanger les comptes (points, drops, prédictions). */
    fun checkAccount() {
        val app = AppState.user?.login?.lowercase() ?: return
        val web = login ?: return
        if (token != null && web != app) {
            Logger.warn("WEB", "Session web d'un autre compte retirée", "@$web ≠ @$app")
            mismatch = web
            clear()
        }
    }

    fun dismissMismatch() { mismatch = null }

    /** Cookies de twitch.tv, par nom. */
    fun cookies(): Map<String, String> {
        val raw = try { CookieManager.getInstance().getCookie("https://www.twitch.tv") } catch (e: Exception) { null }
            ?: return emptyMap()
        val out = HashMap<String, String>()
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            out[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        return out
    }
}
