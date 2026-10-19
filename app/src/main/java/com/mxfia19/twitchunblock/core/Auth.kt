package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /** Un seul renouvellement à la fois : Twitch n'accepte qu'une fois un
     *  jeton de renouvellement (client public), un second appel avec le même
     *  était refusé et déconnectait le compte. */
    private val renewal = Mutex()
    /** Révocations, envoyées sans faire attendre la déconnexion. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Nouveau jeton depuis le jeton de renouvellement. `stale` : le jeton
     *  refusé ; déjà remplacé pendant l'attente du verrou, rien à refaire.
     *  Le compte n'est retiré que si Twitch refuse (400, 401), jamais sur
     *  une panne (réseau, 5xx). Mené à terme même si l'appelant est annulé :
     *  le jeton consommé serait perdu. */
    suspend fun renew(stale: String): Renewal = withContext(NonCancellable) {
        renewal.withLock {
            val current = AppState.token ?: return@withLock Renewal.REFUSED
            if (current != stale) return@withLock Renewal.RENEWED
            val refresh = AppState.refreshToken
            if (refresh == null) {
                Logger.warn("AUTH", "Session expirée", "pas de jeton de renouvellement")
                AppState.clearAuth()
                return@withLock Renewal.REFUSED
            }
            val r = Http.postForm("https://id.twitch.tv/oauth2/token", mapOf(
                "client_id" to Config.HELIX_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
            )) ?: return@withLock Renewal.UNREACHABLE
            val j = parseJson(r.body)
            val access = j?.str("access_token")
            if (access == null) {
                // Refus explicite seulement : une panne de Twitch n'efface rien.
                if (r.code != 400 && r.code != 401) return@withLock Renewal.UNREACHABLE
                Logger.warn("AUTH", "Session expirée", "renouvellement refusé (HTTP ${r.code}), reconnexion nécessaire")
                AppState.clearAuth()
                return@withLock Renewal.REFUSED
            }
            if (AppState.token != current) {
                // Déconnecté pendant la requête : le compte ne revient pas avec
                // ce jeton, révoqué aussitôt (reconnecté : le nouveau reste).
                if (AppState.token != null) return@withLock Renewal.RENEWED
                revoke(access)
                return@withLock Renewal.REFUSED
            }
            AppState.saveTokens(access, j?.str("refresh_token"))
            Logger.success("AUTH", "Jeton renouvelé")
            Renewal.RENEWED
        }
    }

    /** Au lancement : jeton vérifié, renouvelé s'il a expiré, compte relu. */
    suspend fun restore() {
        val token = AppState.token ?: return
        val r = Http.get("https://id.twitch.tv/oauth2/validate", mapOf("Authorization" to "OAuth $token"), timeout = 10)
            ?: return   // pas de réseau : la session reste
        // Refus : compte déjà retiré par renew ; panne : la session reste.
        if (r.code == 401 && renew(token) != Renewal.RENEWED) return
        loadUser()
    }

    suspend fun loadUser() {
        val r = helix("https://api.twitch.tv/helix/users") ?: return
        if (r.code != 200) return
        val u = parseJson(r.body)?.arr("data")?.objects()?.firstOrNull() ?: return
        AppState.updateUser(TwitchUser(u.str("id") ?: "", u.str("login") ?: "",
                                       u.str("display_name") ?: "", u.str("profile_image_url") ?: ""))
    }

    /** Déconnexion : jetons effacés de l'appareil, et le jeton révoqué chez
     *  Twitch (sinon valable jusqu'à son expiration). Un renouvellement en
     *  cours (verrou) ne remet pas le compte derrière : voir renew. */
    fun logout() {
        Logger.info("AUTH", "Déconnexion")
        val token = AppState.token
        AppState.clearAuth()
        if (token != null) revoke(token)
    }

    /** Révocation chez Twitch, sans attendre (échec sans conséquence ici). */
    private fun revoke(token: String) {
        background.launch {
            val r = Http.postForm("https://id.twitch.tv/oauth2/revoke",
                                  mapOf("client_id" to Config.HELIX_CLIENT_ID, "token" to token))
            if (r?.code != 200) Logger.warn("AUTH", "Révocation du jeton échouée", r?.code?.let { "HTTP $it" } ?: "réseau")
        }
    }

    /** Requête Helix avec le jeton du compte, renouvelé une fois s'il est
     *  refusé (401). null : pas de compte, ou pas de réponse (renouvellement
     *  injoignable compris : une panne ne passe pas pour un refus). */
    suspend fun helix(url: String): HttpResult? {
        val token = AppState.token ?: return null
        val r = Http.get(url, helixHeaders(token)) ?: return null
        if (r.code != 401) return r
        return when (renew(token)) {
            Renewal.RENEWED -> AppState.token?.let { Http.get(url, helixHeaders(it)) }
            Renewal.REFUSED -> r
            Renewal.UNREACHABLE -> null
        }
    }

    fun helixHeaders(token: String) = mapOf("Authorization" to "Bearer $token", "Client-Id" to Config.HELIX_CLIENT_ID)
}
