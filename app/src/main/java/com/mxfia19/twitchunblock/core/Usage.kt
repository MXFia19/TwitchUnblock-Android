package com.mxfia19.twitchunblock.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.BuildConfig
import org.json.JSONObject

/**
 * Comptage d'utilisation (/stats) : au plus un signal par heure, avec la
 * langue, le type d'appareil et des compteurs de lectures (source du direct,
 * échecs). Jamais de chaîne ni de titre. Comme UsageService (iOS). Seulement
 * si le Worker connaît la plateforme « android » (sinon il la compterait
 * comme l'iPhone).
 */
object Usage {
    private const val ENABLED = "usage_share"
    private const val LAST = "usage_last_ping"
    private const val EVENTS = "usage_events"
    private const val INTERVAL = 3_600_000L

    var enabled by mutableStateOf(true)
        private set

    fun init() {
        enabled = AppState.prefs.getBoolean(ENABLED, true)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        AppState.prefs.edit().putBoolean(ENABLED, on).apply()
    }

    /** Une lecture de plus (« live_as », « vod_fail »…), envoyée au prochain signal. */
    @Synchronized
    fun count(event: String) {
        val all = parseJson(AppState.prefs.getString(EVENTS, null)) ?: JSONObject()
        all.put(event, (all.int(event) ?: 0) + 1)
        AppState.prefs.edit().putString(EVENTS, all.toString()).apply()
    }

    suspend fun ping(context: Context, force: Boolean = false) {
        if (!enabled) return
        val last = AppState.prefs.getLong(LAST, 0L)
        if (!force && System.currentTimeMillis() - last < INTERVAL) return
        val platforms = parseJson(Http.get("${Config.API_URL}/api/ping", timeout = 10)?.body)?.arr("platforms")?.strings()
        if (platforms == null || "android" !in platforms) return
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
        AppState.token?.let { headers["Authorization"] = "Bearer $it" }
        val r = Http.postJson("${Config.API_URL}/api/ping", body.toString(), headers, timeout = 15) ?: return
        if (r.code == 200) {
            AppState.prefs.edit().putLong(LAST, System.currentTimeMillis()).remove(EVENTS).apply()
            Logger.debug("USAGE", "Signal envoyé")
        } else {
            Logger.warn("USAGE", "Signal refusé", "HTTP ${r.code}")
        }
    }
}
