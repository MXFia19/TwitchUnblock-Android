package com.mxfia19.twitchunblock.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Comptage d'utilisation (/stats) : au plus un signal par heure, avec la
 * langue, le type d'appareil et des compteurs de lectures (source du direct,
 * échecs). Jamais de chaîne ni de titre. Comme UsageService (iOS). Seulement
 * si le Worker connaît la plateforme « android » (sinon il la compterait
 * comme l'iPhone). Coupé, l'installation est effacée côté serveur.
 */
object Usage {
    private const val ENABLED = "usage_share"
    private const val LAST = "usage_last_ping"
    private const val EVENTS = "usage_events"
    /** Worker du dernier signal accepté : l'effacement y part aussi (après
     *  « Tout passer par ce Worker », il n'irait qu'au nouveau). */
    private const val PING_BASE = "usage_ping_base"
    /** Workers où l'effacement n'est pas encore confirmé (hors ligne, 429…). */
    private const val FORGET_PENDING = "usage_forget_pending"
    private const val INTERVAL = 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Un échange à la fois : un signal parti avant l'effacement, arrivé
     *  après, réinscrivait l'installation. */
    private val lock = Mutex()

    var enabled by mutableStateOf(true)
        private set

    fun init() {
        enabled = AppState.prefs.getBoolean(ENABLED, true)
    }

    fun updateEnabled(on: Boolean) {
        val was = enabled
        enabled = on
        AppState.prefs.edit().putBoolean(ENABLED, on).apply()
        if (on) {
            // Rallumé : plus rien à effacer.
            AppState.prefs.edit().remove(FORGET_PENDING).apply()
        } else if (was) {
            scope.launch { forget() }
        }
    }

    /** Une lecture de plus (« live_as », « vod_fail »…), envoyée au prochain signal. */
    @Synchronized
    fun count(event: String) {
        if (!enabled) return
        val all = parseJson(AppState.prefs.getString(EVENTS, null)) ?: JSONObject()
        all.put(event, (all.int(event) ?: 0) + 1)
        AppState.prefs.edit().putString(EVENTS, all.toString()).apply()
    }

    /** Au retour dans l'app : un signal (au plus un par heure) ; comptage
     *  coupé, un effacement resté en attente est renvoyé. */
    suspend fun ping(context: Context, force: Boolean = false) {
        if (!enabled) {
            lock.withLock { retryForget() }
            return
        }
        lock.withLock { send(context, force) }
    }

    private suspend fun send(context: Context, force: Boolean) {
        val last = AppState.prefs.getLong(LAST, 0L)
        if (!force && System.currentTimeMillis() - last < INTERVAL) return
        val base = CustomWorker.base
        val platforms = parseJson(Http.get("$base/api/ping", timeout = 10)?.body)?.arr("platforms")?.strings()
        if (platforms == null || "android" !in platforms) return
        // Coupé pendant la relecture : rien ne part.
        if (!enabled) return
        val events = parseJson(AppState.prefs.getString(EVENTS, null))
        val body = JSONObject()
            .put("id", AppState.installId)
            .put("version", BuildConfig.VERSION_NAME)
            .put("platform", "android")
            .put("lang", AppState.lang.code)
            .put("device", if (com.mxfia19.twitchunblock.ui.isTelevision(context)) "tv" else "mobile")
        if (events != null && events.length() > 0) body.put("ev", events)
        val headers = HashMap<String, String>()
        if (BuildConfig.PING_KEY.isNotEmpty()) headers["X-TU-Key"] = BuildConfig.PING_KEY
        // Sans le jeton du compte : le signal reste anonyme (identifiant tiré
        // au hasard), comme l'annonce le réglage.
        val r = Http.postJson("$base/api/ping", body.toString(), headers, timeout = 15) ?: return
        if (r.code == 200) {
            AppState.prefs.edit().putLong(LAST, System.currentTimeMillis()).putString(PING_BASE, base).remove(EVENTS).apply()
            Logger.debug("USAGE", "Signal envoyé")
        } else {
            Logger.warn("USAGE", "Signal refusé", "HTTP ${r.code}")
        }
    }

    /** Comptage coupé : l'installation est effacée au Worker actuel et à
     *  celui du dernier signal ({ id, forget: true }, comme l'iPhone).
     *  Non confirmé : renvoyé au prochain retour dans l'app. */
    private suspend fun forget() = lock.withLock {
        val p = AppState.prefs
        val bases = linkedSetOf(CustomWorker.base)
        p.getString(PING_BASE, null)?.let { bases += it }
        parseJsonArray(p.getString(FORGET_PENDING, null))?.strings()?.let { bases += it }
        p.edit().remove(LAST).remove(EVENTS).putString(FORGET_PENDING, JSONArray(bases.toList()).toString()).apply()
        Logger.info("USAGE", "Retrait du comptage demandé")
        retryForget()
    }

    private suspend fun retryForget() {
        val p = AppState.prefs
        val pending = parseJsonArray(p.getString(FORGET_PENDING, null))?.strings().orEmpty()
        if (pending.isEmpty()) return
        val left = pending.filter { !sendForget(it) }
        // Rallumé entre-temps : plus rien à effacer.
        if (enabled) return
        if (left.isEmpty()) {
            p.edit().remove(FORGET_PENDING).remove(PING_BASE).apply()
            Logger.info("USAGE", "Comptage effacé côté serveur")
        } else {
            p.edit().putString(FORGET_PENDING, JSONArray(left).toString()).apply()
            Logger.warn("USAGE", "Retrait du comptage pas encore confirmé", left.joinToString())
        }
    }

    /** true : effacé, ou refus qui ne changera pas (route absente…) ; false :
     *  à renvoyer (réseau, 429, 5xx). */
    private suspend fun sendForget(base: String): Boolean {
        val body = JSONObject().put("id", AppState.installId).put("forget", true)
        val r = Http.postJson("$base/api/ping", body.toString(), timeout = 15) ?: return false
        return r.code == 200 || (r.code in 400..499 && r.code != 429)
    }
}
