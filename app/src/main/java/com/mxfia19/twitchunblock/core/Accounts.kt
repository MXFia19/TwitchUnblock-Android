package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.mutableStateListOf
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plusieurs comptes Twitch sur l'appareil, pour en changer sans se
 * reconnecter (Réglages → Compte Twitch). Se connecter à un autre compte
 * (« Changer de compte ») garde celui d'avant de côté au lieu de
 * l'oublier : ses jetons (chiffrés, Secrets), sa session web, et son
 * historique quand la synchronisation est active — chaque compte garde le
 * sien, comme sur l'iPhone. Le compte en place reste tant que l'autre
 * n'est pas connecté : annuler ne déconnecte rien. Fil principal.
 */
object Accounts {
    /** Compte gardé de côté (le compte en place n'est jamais dans la liste). */
    data class Saved(val user: TwitchUser, val webLogin: String?, val at: Long)

    enum class SwitchResult { DONE, EXPIRED, UNREACHABLE }

    private const val LIST_KEY = "accounts_saved"
    /** Au-delà, le plus ancien est oublié (son jeton révoqué). */
    private const val MAX = 5

    val saved = mutableStateListOf<Saved>()
    private var loaded = false
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun key(id: String, what: String) = "acct_${id}_$what"

    /** Identifiant Twitch (des chiffres) : seul accepté dans les noms de clés. */
    private fun validId(id: String) = id.isNotEmpty() && id.length <= 20 && id.all { it.isDigit() }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        saved.clear()
        parseJsonArray(AppState.prefs.getString(LIST_KEY, null))?.objects()?.forEach { o ->
            val id = o.str("id")?.takeIf { validId(it) } ?: return@forEach
            val user = TwitchUser(id, o.str("login") ?: "", o.str("name") ?: "", o.str("avatar") ?: "")
            saved += Saved(user, o.str("web"), o.long("at") ?: 0L)
        }
    }

    /** Comptes à proposer : sans celui en place. */
    val others: List<Saved>
        get() {
            ensureLoaded()
            val current = AppState.user?.id?.takeIf { AppState.token != null }
            return saved.filter { it.user.id != current }
        }

    private fun saveList() {
        val arr = JSONArray()
        for (s in saved) {
            arr.put(JSONObject().put("id", s.user.id).put("login", s.user.login).put("name", s.user.displayName)
                .put("avatar", s.user.avatar).put("at", s.at).apply { s.webLogin?.let { put("web", it) } })
        }
        AppState.prefs.edit().putString(LIST_KEY, arr.toString()).apply()
    }

    // ── Connexion par code ──

    /** Connexion par code validée (Auth.waitForApproval) : un autre compte
     *  que celui en place le met de côté, puis prend sa place. Compte du
     *  jeton inconnu (réseau) : comme avant, le jeton remplace l'ancien. */
    suspend fun adoptLogin(access: String, refresh: String?) {
        ensureLoaded()
        val user = fetchUser(access)
        if (user == null) {
            Auth.exclusive { storeTokens(access, refresh) }
            Auth.loadUser()
            return
        }
        Auth.exclusive {
            val current = AppState.user
            val parkedCurrent = AppState.token != null && current != null && current.id != user.id && parkCurrent()
            // Ce compte était de côté : ses jetons d'avant ne servent plus
            // (révoqués), sa session web et son historique reviennent.
            val parked = take(user.id)
            parked?.token?.let { if (it != access) revoke(it) }
            storeTokens(access, refresh)
            restore(parked, replaceHistory = parkedCurrent)
            AppState.updateUser(user)
        }
    }

    // ── Changement direct ──

    /** Passe à un compte gardé de côté. Son jeton est vérifié (renouvelé
     *  s'il a expiré) avant de quitter le compte en place : un compte que
     *  Twitch refuse ne déconnecte pas l'autre ; il est alors oublié. */
    suspend fun switchTo(id: String): SwitchResult = withContext(NonCancellable) { switchToInner(id) }

    /** Mené à terme même si l'écran se ferme : un jeton de renouvellement
     *  consommé sans être gardé perdrait le compte. */
    private suspend fun switchToInner(id: String): SwitchResult {
        ensureLoaded()
        if (AppState.token != null && AppState.user?.id == id) return SwitchResult.DONE
        val entry = saved.firstOrNull { it.user.id == id } ?: return SwitchResult.EXPIRED
        val sp = AppState.prefs
        var access = Secrets.read(sp, key(id, "token")) ?: run {
            forget(id, revokeToken = false)
            return SwitchResult.EXPIRED
        }
        var refresh = Secrets.read(sp, key(id, "refresh"))
        val v = Http.get("https://id.twitch.tv/oauth2/validate", mapOf("Authorization" to "OAuth $access"), timeout = 10)
            ?: return SwitchResult.UNREACHABLE
        if (v.code == 401) {
            val r = refresh?.let {
                Http.postForm("https://id.twitch.tv/oauth2/token", mapOf(
                    "client_id" to Config.HELIX_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to it,
                ))
            }
            if (refresh != null && r == null) return SwitchResult.UNREACHABLE
            val j = parseJson(r?.body)
            val renewed = j?.str("access_token")
            if (renewed == null) {
                // Refus explicite seulement : une panne de Twitch n'oublie rien.
                if (r != null && r.code != 400 && r.code != 401) return SwitchResult.UNREACHABLE
                Logger.warn("AUTH", "Compte gardé refusé par Twitch", "@${entry.user.login} — reconnexion nécessaire")
                forget(id, revokeToken = false)
                return SwitchResult.EXPIRED
            }
            access = renewed
            refresh = j?.str("refresh_token") ?: refresh
            // Jeton de renouvellement à usage unique : le nouveau gardé tout de suite.
            val ed = Secrets.put(sp.edit(), key(id, "token"), access)
            refresh?.let { Secrets.put(ed, key(id, "refresh"), it) }
            ed.apply()
        } else if (v.code != 200) {
            return SwitchResult.UNREACHABLE
        } else if (parseJson(v.body)?.str("user_id")?.let { it != id } == true) {
            // Jeton d'un autre compte que celui affiché : jamais utilisé.
            forget(id, revokeToken = false)
            return SwitchResult.EXPIRED
        }
        // Encore là après les requêtes (pas oublié entre-temps).
        if (saved.none { it.user.id == id }) return SwitchResult.EXPIRED
        val token = access
        Auth.exclusive {
            val parkedCurrent = parkCurrent()
            val parked = take(id)
            storeTokens(token, refresh)
            restore(parked, replaceHistory = parkedCurrent)
            AppState.updateUser(entry.user)
        }
        Logger.success("AUTH", "Compte changé", "@${entry.user.login}")
        // Nom et avatar relus (changés sur Twitch depuis ?).
        background.launch { Auth.loadUser() }
        return SwitchResult.DONE
    }

    /** Oublie un compte gardé : ses jetons (révoqués chez Twitch), sa session
     *  web et son historique mis de côté. */
    fun forget(id: String, revokeToken: Boolean = true) {
        ensureLoaded()
        val sp = AppState.prefs
        if (revokeToken) Secrets.read(sp, key(id, "token"))?.let { revoke(it) }
        sp.edit().remove(key(id, "token")).remove(key(id, "refresh")).remove(key(id, "web")).remove(key(id, "stash")).apply()
        val removed = saved.removeAll { it.user.id == id }
        if (removed) {
            saveList()
            Logger.info("AUTH", "Compte gardé oublié", id)
        }
    }

    // ── Mise de côté ──

    private class Parked(val token: String?, val web: String?, val webLogin: String?, val stash: JSONObject?)

    /** Jetons du compte en place. Sans jeton de renouvellement, celui
     *  d'avant (d'un autre compte) est retiré. */
    private fun storeTokens(access: String, refresh: String?) {
        if (refresh == null) AppState.prefs.edit().remove("refresh_token").apply()
        AppState.saveTokens(access, refresh)
    }

    /** Met le compte en place de côté : jetons, session web (la sienne),
     *  historique (synchronisation active : l'appareil repart de celui de
     *  l'autre compte). true si l'historique d'ici a été vidé. */
    private fun parkCurrent(): Boolean {
        val user = AppState.user ?: return false
        val token = AppState.token ?: return false
        if (!validId(user.id)) return false
        // Réglages changés juste avant : partis avec le jeton de ce compte.
        SettingsSync.finalFlush()
        val sp = AppState.prefs
        val ed = Secrets.put(sp.edit(), key(user.id, "token"), token)
        val refresh = AppState.refreshToken
        if (refresh != null) Secrets.put(ed, key(user.id, "refresh"), refresh) else ed.remove(key(user.id, "refresh"))
        // Session web : gardée avec lui si elle est à lui (pseudo inconnu :
        // on la croit à lui, comme WebSession.checkAccount) et pas expirée.
        val web = WebSession.token
        val webLogin = WebSession.login
        val webIsHis = web != null && !WebSession.expired && (webLogin == null || webLogin == user.login.lowercase())
        if (webIsHis) Secrets.put(ed, key(user.id, "web"), web!!) else ed.remove(key(user.id, "web"))
        val sync = Features.cloudSync.value
        if (sync) ed.putString(key(user.id, "stash"), stashJson().toString()) else ed.remove(key(user.id, "stash"))
        ed.apply()
        saved.removeAll { it.user.id == user.id }
        saved.add(0, Saved(user, if (webIsHis) webLogin else null, System.currentTimeMillis()))
        while (saved.size > MAX) {
            val oldest = saved.removeAt(saved.lastIndex)
            Secrets.read(sp, key(oldest.user.id, "token"))?.let { revoke(it) }
            sp.edit().remove(key(oldest.user.id, "token")).remove(key(oldest.user.id, "refresh"))
                .remove(key(oldest.user.id, "web")).remove(key(oldest.user.id, "stash")).apply()
        }
        saveList()
        // L'app passe à l'autre compte : cette session web part avec lui
        // (sans le bandeau « session d'un autre compte »).
        if (web != null) WebSession.clear()
        if (sync) {
            History.replace(emptyList())
            AppState.replaceVodPositions(emptyMap())
            sp.edit().remove("sync_base").apply()
        }
        Logger.info("AUTH", "Compte mis de côté", "@${user.login}")
        return sync
    }

    /** Retire un compte de la liste et rend ce qui était gardé pour lui. */
    private fun take(id: String): Parked? {
        if (saved.none { it.user.id == id }) return null
        val sp = AppState.prefs
        val entry = saved.first { it.user.id == id }
        val parked = Parked(Secrets.read(sp, key(id, "token")), Secrets.read(sp, key(id, "web")), entry.webLogin,
                            parseJson(sp.getString(key(id, "stash"), null)))
        sp.edit().remove(key(id, "token")).remove(key(id, "refresh")).remove(key(id, "web")).remove(key(id, "stash")).apply()
        saved.removeAll { it.user.id == id }
        saveList()
        return parked
    }

    /** Session web et historique gardés du compte qui revient. Historique :
     *  remis tel quel si celui d'ici vient d'être mis de côté, sinon ajouté
     *  à ce qui est là (vu sans compte, comme une connexion ordinaire). */
    private fun restore(parked: Parked?, replaceHistory: Boolean) {
        if (parked == null) return
        val stash = parked.stash
        if (stash != null) {
            val items = stash.arr("history")?.objects().orEmpty().mapNotNull { HistoryItem.from(it) }
            val progress = HashMap<String, Long>()
            stash.obj("progress")?.let { p ->
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    p.long(k)?.let { progress[k] = it }
                }
            }
            if (replaceHistory) {
                History.replace(items)
                AppState.replaceVodPositions(progress)
            } else {
                val byKey = LinkedHashMap<String, HistoryItem>()
                (History.items.toList() + items).forEach { h ->
                    val old = byKey[h.key]
                    if (old == null || h.addedAt > old.addedAt) byKey[h.key] = h
                }
                History.replace(byKey.values.sortedByDescending { it.addedAt }.take(50))
                val merged = HashMap(AppState.vodPositions())
                progress.forEach { (k, v) -> merged[k] = maxOf(merged[k] ?: 0L, v) }
                AppState.replaceVodPositions(merged)
            }
            // Base de la synchronisation (ce que son serveur avait) : ses
            // suppressions faites ici ne reviennent pas.
            val base = stash.str("base")
            if (base != null) AppState.prefs.edit().putString("sync_base", base).apply()
        }
        val web = parked.web
        if (web != null && WebSession.token == null) WebSession.save(web, parked.webLogin)
    }

    /** Historique, reprise des VODs et base de synchronisation d'ici. */
    private fun stashJson(): JSONObject {
        val hist = JSONArray()
        History.items.forEach { hist.put(it.toJson()) }
        val prog = JSONObject()
        AppState.vodPositions().forEach { (k, v) -> prog.put(k, v) }
        return JSONObject().put("history", hist).put("progress", prog).apply {
            AppState.prefs.getString("sync_base", null)?.let { put("base", it) }
        }
    }

    private suspend fun fetchUser(token: String): TwitchUser? {
        val r = Http.get("https://api.twitch.tv/helix/users", Auth.helixHeaders(token)) ?: return null
        if (r.code != 200) return null
        val u = parseJson(r.body)?.arr("data")?.objects()?.firstOrNull() ?: return null
        val id = u.str("id")?.takeIf { validId(it) } ?: return null
        return TwitchUser(id, u.str("login") ?: "", u.str("display_name") ?: "", u.str("profile_image_url") ?: "")
    }

    /** Révocation chez Twitch, sans attendre (échec sans conséquence ici). */
    private fun revoke(token: String) {
        background.launch {
            Http.postForm("https://id.twitch.tv/oauth2/revoke", mapOf("client_id" to Config.HELIX_CLIENT_ID, "token" to token))
        }
    }
}
