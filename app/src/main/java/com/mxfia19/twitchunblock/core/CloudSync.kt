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
        if (!enabled) return
        scope.launch { sync() }
    }

    /** Relecture, fusion, puis envoi si le serveur n'a pas déjà tout. */
    suspend fun sync(push: Boolean = true): Boolean = mutex.withLock {
        val user = AppState.user ?: return@withLock false
        val token = AppState.token ?: return@withLock false
        if (!Features.cloudSync.value) return@withLock false
        val headers = mapOf("Authorization" to "Bearer $token")
        val r = Http.get("${Config.API_URL}/api/sync/get?userId=${user.id}", headers, timeout = 15)
        val json = if (r != null && r.code == 200) parseJson(r.body) else null
        if (json == null) {
            Logger.warn("SYNC", "Relecture impossible", r?.code?.let { "HTTP $it" } ?: "réseau")
            failed = true
            return@withLock false
        }
        if (AppState.user?.id != user.id) return@withLock false
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
        val base = loadBase(user.id)
        val merged = mergeHistory(History.items.toList(), remoteHistory, base?.first)
        val localProgress = AppState.vodPositions().mapValues { it.value / 1000.0 }
        val progress = mergeProgress(localProgress, remoteProgress, base?.second)

        val known = History.items.map { it.key }.toHashSet()
        val revision = History.revision
        History.replace(merged)
        AppState.replaceVodPositions(progress.mapValues { (it.value * 1000).toLong() })
        saveBase(user.id, remoteHistory, remoteProgress)
        val added = merged.count { it.key !in known }
        if (added > 0) Logger.info("SYNC", "Historique relu", "+$added")

        val vodIds = merged.filter { it.type == "vod" }.map { it.term }.toHashSet()
        val sent = progress.filter { it.key in vodIds && it.value.isFinite() }.mapValues { Math.round(it.value).toDouble() }
        val changed = merged.map { it.key }.toSet() != remoteHistory.map { it.key }.toSet() ||
            sent.any { it.value > (remoteProgress[it.key] ?: 0.0) }
        if (!push || !changed) {
            lastSync = System.currentTimeMillis()
            failed = false
            return@withLock true
        }
        val hist = JSONArray()
        merged.forEach { hist.put(it.toJson()) }
        val prog = JSONObject()
        sent.forEach { (id, t) -> prog.put(id, t) }
        val body = JSONObject().put("userId", user.id).put("data", JSONObject().put("history", hist).put("progress", prog))
        val post = Http.postJson("${Config.API_URL}/api/sync/post", body.toString(), headers, timeout = 20)
        if (post == null || post.code !in 200..299) {
            Logger.warn("SYNC", "Envoi refusé", post?.code?.let { "HTTP $it" } ?: "réseau")
            failed = true
            return@withLock false
        }
        // Le serveur a maintenant cette liste ; la progression, il garde la plus avancée.
        val nextProgress = HashMap(remoteProgress)
        sent.forEach { (id, t) -> nextProgress[id] = maxOf(nextProgress[id] ?: 0.0, t) }
        saveBase(user.id, merged, nextProgress)
        if (History.revision != revision) scheduleSoon()
        Logger.success("SYNC", "Historique envoyé", "${merged.size} éléments")
        lastSync = System.currentTimeMillis()
        failed = false
        true
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

    /** Ce que le serveur avait au dernier échange de ce compte : dates des éléments, progression. */
    private fun loadBase(userId: String): Pair<Map<String, Double>, Map<String, Double>>? {
        val o = parseJson(AppState.prefs.getString("sync_base", null)) ?: return null
        if (o.str("user") != userId) return null
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

    private fun saveBase(userId: String, history: List<HistoryItem>, progress: Map<String, Double>) {
        val h = JSONObject()
        history.forEach { h.put(it.key, it.addedAt) }
        val p = JSONObject()
        progress.forEach { (k, v) -> p.put(k, v) }
        AppState.prefs.edit().putString("sync_base", JSONObject().put("user", userId).put("history", h).put("progress", p).toString()).apply()
    }
}
