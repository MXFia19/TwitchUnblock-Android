package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Historique et reprise des VODs synchronisés avec le compte Twitch, par le
 * Worker (/api/sync/get et /api/sync/post), comme l'app iPhone et le site :
 * même format, même fusion. Rien ne part sans une relecture réussie juste
 * avant ; une suppression faite ici ne revient pas du serveur.
 */
object CloudSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private var pending: Job? = null

    /** Dernier échange réussi (ms), null : jamais. */
    var lastSync by mutableStateOf<Long?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    val enabled: Boolean get() = Features.cloudSync.value && AppState.token != null && AppState.user != null

    /** Envoi groupé quelques secondes après un changement. */
    fun scheduleSoon() {
        if (!enabled) return
        pending?.cancel()
        pending = scope.launch {
            delay(5_000)
            sync()
        }
    }

    fun syncNow() {
        // Réglages synchronisés (SettingsSync) : relus au même rythme.
        SettingsSync.refreshIfStale()
        if (!enabled) return
        scope.launch { sync() }
    }

    /** Relecture, fusion, puis envoi si le serveur n'a pas déjà tout. */
    suspend fun sync(push: Boolean = true): Boolean = mutex.withLock {
        val user = AppState.user ?: return@withLock false
        val token = AppState.token ?: return@withLock false
        if (!Features.cloudSync.value) return@withLock false
        exchange(user, token, push, null)
    }

    /** Données d'ici prises au moment de la déconnexion (dernier envoi). */
    private class Snapshot(val history: List<HistoryItem>, val progress: Map<String, Double>,
                           val base: Pair<Map<String, Double>, Map<String, Double>>?)

    /** Déconnexion (Auth.logout) : ce qui attendait part sur le compte qui
     *  s'en va, avec son jeton et les données d'ici prises maintenant ; rien
     *  de son serveur n'entre plus ici. Comme finalCloudFlush sur iOS. */
    fun finalFlush(): Job? {
        if (!enabled) return null
        val user = AppState.user ?: return null
        val token = AppState.token ?: return null
        pending?.cancel()
        val snapshot = Snapshot(History.items.toList(), AppState.vodPositions().mapValues { it.value / 1000.0 },
                                loadBase(user.id, CustomWorker.base))
        return scope.launch { mutex.withLock { exchange(user, token, true, snapshot) } }
    }

    /** Un échange. `snapshot` : dernier envoi d'un compte déconnecté, qui ne
     *  change rien ici. */
    private suspend fun exchange(user: TwitchUser, token: String, push: Boolean, snapshot: Snapshot?): Boolean {
        val live = snapshot == null
        val headers = mapOf("Authorization" to "Bearer $token")
        // Worker de la synchro : le personnel avec « Tout passer par ce Worker ».
        val api = CustomWorker.base
        val r = Http.get("$api/api/sync/get?userId=${user.id}", headers, timeout = 15)
        val json = if (r != null && r.code == 200) parseJson(r.body) else null
        if (json == null) {
            Logger.warn("SYNC", "Relecture impossible", r?.code?.let { "HTTP $it" } ?: "réseau")
            if (live) failed = true
            return false
        }
        if (live && AppState.user?.id != user.id) return false
        val remoteHistory = json.arr("history")?.objects().orEmpty().mapNotNull { HistoryItem.from(it) }
        val remoteProgress = HashMap<String, Double>()
        json.obj("progress")?.let { p ->
            val keys = p.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val v = (p.opt(id) as? Number)?.toDouble() ?: continue
                if (v.isFinite() && v > 0) remoteProgress[id] = minOf(v, 7 * 24 * 3600.0)
            }
        }
        val base = if (snapshot != null) snapshot.base else loadBase(user.id, api)
        val merged = mergeHistory(snapshot?.history ?: History.items.toList(), remoteHistory, base?.first)
        val localProgress = snapshot?.progress ?: AppState.vodPositions().mapValues { it.value / 1000.0 }
        val progress = mergeProgress(localProgress, remoteProgress, base?.second)

        val revision = History.revision
        if (live) {
            val known = History.items.map { it.key }.toHashSet()
            History.replace(merged)
            AppState.replaceVodPositions(progress.mapValues { (it.value * 1000).toLong() })
            saveBase(user.id, remoteHistory, remoteProgress, api)
            val added = merged.count { it.key !in known }
            if (added > 0) Logger.info("SYNC", "Historique relu", "+$added")
        }

        val vodIds = merged.filter { it.type == "vod" }.map { it.term }.toHashSet()
        val sent = progress.filter { it.key in vodIds && it.value.isFinite() }.mapValues { Math.round(it.value).toDouble() }
        val changed = merged.map { it.key }.toSet() != remoteHistory.map { it.key }.toSet() ||
            sent.any { it.value > (remoteProgress[it.key] ?: 0.0) }
        if (!push || !changed) {
            if (live) {
                lastSync = System.currentTimeMillis()
                failed = false
            }
            return true
        }
        val hist = JSONArray()
        merged.forEach { hist.put(it.toJson()) }
        val prog = JSONObject()
        sent.forEach { (id, t) -> prog.put(id, t) }
        val body = JSONObject().put("userId", user.id).put("data", JSONObject().put("history", hist).put("progress", prog))
        val post = Http.postJson("$api/api/sync/post", body.toString(), headers, timeout = 20)
        if (post == null || post.code !in 200..299) {
            Logger.warn("SYNC", "Envoi refusé", post?.code?.let { "HTTP $it" } ?: "réseau")
            if (live) failed = true
            return false
        }
        // Le serveur a maintenant cette liste ; la progression, il garde la plus avancée.
        val nextProgress = HashMap(remoteProgress)
        sent.forEach { (id, t) -> nextProgress[id] = maxOf(nextProgress[id] ?: 0.0, t) }
        Logger.success("SYNC", "Historique envoyé", "${merged.size} éléments")
        if (!live) {
            // Toujours déconnecté : sa base suit ce dernier envoi (reprise s'il revient).
            if (AppState.token == null && parseJson(AppState.prefs.getString("sync_base", null))?.str("user") == user.id) {
                saveBase(user.id, merged, nextProgress, api)
            }
            return true
        }
        saveBase(user.id, merged, nextProgress, api)
        if (History.revision != revision) scheduleSoon()
        lastSync = System.currentTimeMillis()
        failed = false
        return true
    }

    /**
     * Fusion de l'historique d'ici et du serveur. Tout ce qui est ici reste ;
     * ce qui manque ici mais que la base connaissait (sans ajout là-bas
     * depuis) a été supprimé ici et ne revient pas. Sans base : union.
     */
    private fun mergeHistory(local: List<HistoryItem>, remote: List<HistoryItem>, base: Map<String, Double>?): List<HistoryItem> {
        val out = LinkedHashMap<String, HistoryItem>()
        local.forEach { out[it.key] = it }
        for (r in remote) {
            val l = out[r.key]
            if (l != null) {
                out[r.key] = if (r.addedAt > l.addedAt) r.filled(l) else l.filled(r)
                continue
            }
            val b = base?.get(r.key)
            if (b != null && r.addedAt <= b + 1) continue
            out[r.key] = r
        }
        return out.values.sortedByDescending { it.addedAt }.take(50)
    }

    private fun mergeProgress(local: Map<String, Double>, remote: Map<String, Double>, base: Map<String, Double>?): Map<String, Double> {
        val out = HashMap(local)
        for ((id, r) in remote) {
            val l = local[id]
            val b = base?.get(id)
            if (l != null) {
                out[id] = if (b != null && abs(r - b) < 1 && abs(l - b) >= 1 && l >= 30) l else maxOf(l, r)
            } else if (b == null || abs(r - b) >= 1) {
                out[id] = r
            }
        }
        return out
    }

    /** Ce que le serveur avait au dernier échange de ce compte : dates des
     *  éléments, progression. Celle d'un autre Worker ne vaut rien ici (sans
     *  « worker » : le principal, seul utilisé avant) : réunion simple. */
    private fun loadBase(userId: String, api: String): Pair<Map<String, Double>, Map<String, Double>>? {
        val o = parseJson(AppState.prefs.getString("sync_base", null)) ?: return null
        if (o.str("user") != userId) return null
        if ((o.str("worker") ?: Config.API_URL) != api) return null
        fun map(name: String): Map<String, Double> {
            val m = HashMap<String, Double>()
            val obj = o.obj(name) ?: return m
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                (obj.opt(k) as? Number)?.let { m[k] = it.toDouble() }
            }
            return m
        }
        return Pair(map("history"), map("progress"))
    }

    private fun saveBase(userId: String, history: List<HistoryItem>, progress: Map<String, Double>, api: String) {
        val h = JSONObject()
        history.forEach { h.put(it.key, it.addedAt) }
        val p = JSONObject()
        progress.forEach { (k, v) -> p.put(k, v) }
        AppState.prefs.edit().putString("sync_base", JSONObject().put("user", userId).put("worker", api)
            .put("history", h).put("progress", p).toString()).apply()
    }

    /** « Reprendre les VODs » rallumé : la base oublie les positions, pour
     *  que celles du compte reviennent au prochain échange (sinon prises
     *  pour des suppressions faites ici). */
    fun forgetProgressBase() {
        val o = parseJson(AppState.prefs.getString("sync_base", null)) ?: return
        o.put("progress", JSONObject())
        AppState.prefs.edit().putString("sync_base", o.toString()).apply()
    }
}
