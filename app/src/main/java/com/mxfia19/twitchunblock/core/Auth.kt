package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.delay

/**
 * Compte Twitch par code (Device Code Flow), sur le téléphone comme sur la
 * TV : l'app affiche un code, on le valide sur twitch.tv/activate (le lien
 * le préremplit). Même client public que l'app iPhone et l'Apple TV ; le
 * jeton de renouvellement évite de se reconnecter quand le jeton expire.
 */
object Auth {
    /** Suivis (Helix), chat (lire, écrire), couleur du pseudo. */
    const val SCOPES = "user:read:follows chat:read chat:edit user:manage:chat_color"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        /** Lien qui préremplit le code (QR code de la TV, bouton du téléphone). */
        val verificationUri: String,
        val interval: Int,
        val expiresAt: Long,
    )

    enum class Renewal { RENEWED, REFUSED, UNREACHABLE }

    suspend fun requestCode(): DeviceCode? {
        val r = Http.postForm("https://id.twitch.tv/oauth2/device",
                              mapOf("client_id" to Config.HELIX_CLIENT_ID, "scopes" to SCOPES)) ?: return null
        val j = parseJson(r.body) ?: return null
        val device = j.str("device_code") ?: return null
        val user = j.str("user_code") ?: return null
        val uri = j.str("verification_uri") ?: "https://www.twitch.tv/activate"
        return DeviceCode(device, user, uri, j.int("interval") ?: 5,
                          System.currentTimeMillis() + (j.int("expires_in") ?: 1800) * 1000L)
    }

    /** Attend la validation du code. true une fois connecté ; false si le
     *  code expire ou est refusé (annulable : coroutine annulée). */
    suspend fun waitForApproval(code: DeviceCode): Boolean {
        var interval = maxOf(code.interval, 1).toLong()
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval * 1000)
            val r = Http.postForm("https://id.twitch.tv/oauth2/token", mapOf(
                "client_id" to Config.HELIX_CLIENT_ID,
                "scopes" to SCOPES,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            val j = parseJson(r?.body)
            val access = j?.str("access_token")
            if (access != null) {
                AppState.saveTokens(access, j.str("refresh_token"))
                loadUser()
                Logger.success("AUTH", "Connecté par code", AppState.user?.login)
                return true
            }
            val message = j?.str("message")?.lowercase() ?: ""
            if (message.contains("slow_down")) {
                interval += 5
            } else if (message.isNotEmpty() && !message.contains("authorization_pending")) {
                Logger.warn("AUTH", "Connexion par code refusée", message)
                return false
            }
        }
        return false
    }

    /** Nouveau jeton depuis le jeton de renouvellement. */
    suspend fun renew(): Renewal {
        val refresh = AppState.refreshToken ?: return Renewal.REFUSED
        val r = Http.postForm("https://id.twitch.tv/oauth2/token", mapOf(
            "client_id" to Config.HELIX_CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
        )) ?: return Renewal.UNREACHABLE
        val j = parseJson(r.body)
        val access = j?.str("access_token")
        if (access == null) {
            // Refus explicite seulement : une panne de Twitch n'efface rien.
            return if (r.code in 400..499) Renewal.REFUSED else Renewal.UNREACHABLE
        }
        AppState.saveTokens(access, j.str("refresh_token"))
        Logger.success("AUTH", "Jeton renouvelé")
        return Renewal.RENEWED
    }

    /** Au lancement : jeton vérifié, renouvelé s'il a expiré, compte relu. */
    suspend fun restore() {
        val token = AppState.token ?: return
        val r = Http.get("https://id.twitch.tv/oauth2/validate", mapOf("Authorization" to "OAuth $token"), timeout = 10)
            ?: return   // pas de réseau : la session reste
        if (r.code == 401) {
            when (renew()) {
                Renewal.RENEWED -> {}
                Renewal.REFUSED -> {
                    Logger.warn("AUTH", "Session expirée", "reconnexion nécessaire")
                    AppState.clearAuth()
                    return
                }
                Renewal.UNREACHABLE -> return
            }
        }
        loadUser()
    }

    suspend fun loadUser() {
        val r = helix("https://api.twitch.tv/helix/users") ?: return
        if (r.code != 200) return
        val u = parseJson(r.body)?.arr("data")?.objects()?.firstOrNull() ?: return
        AppState.updateUser(TwitchUser(u.str("id") ?: "", u.str("login") ?: "",
                                       u.str("display_name") ?: "", u.str("profile_image_url") ?: ""))
    }

    fun logout() {
        Logger.info("AUTH", "Déconnexion")
        AppState.clearAuth()
    }

    /** Requête Helix avec le jeton du compte, renouvelé une fois s'il est
     *  refusé (401). null : pas de compte, ou pas de réponse. */
    suspend fun helix(url: String): HttpResult? {
        val token = AppState.token ?: return null
        val r = Http.get(url, helixHeaders(token)) ?: return null
        if (r.code != 401) return r
        if (renew() != Renewal.RENEWED) return r
        val fresh = AppState.token ?: return r
        return Http.get(url, helixHeaders(fresh))
    }

    fun helixHeaders(token: String) = mapOf("Authorization" to "Bearer $token", "Client-Id" to Config.HELIX_CLIENT_ID)
}
