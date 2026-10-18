package com.mxfia19.twitchunblock.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Réglages, compte et chaînes suivies de l'appareil, gardés dans les
 * préférences de l'app et lus par l'interface (état Compose). À modifier
 * depuis le fil principal.
 */
object AppState {
    private lateinit var sp: SharedPreferences

    /** Préférences de l'app, pour les services qui gardent leurs propres clés. */
    val prefs: SharedPreferences get() = sp

    /** Identifiant aléatoire de l'installation (comptage, sondages, réactions). */
    val installId: String
        get() = sp.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().also {
            sp.edit().putString("install_id", it).apply()
        }

    var lang by mutableStateOf(Lang.EN)
        private set
    /** Qualité préférée (Quality.PREFS). */
    var qualityPref by mutableStateOf("auto")
        private set
    /** Qualité en données mobiles (Quality.CELLULAR_PREFS). */
    var cellularQuality by mutableStateOf("auto")
        private set
    /** Première source du direct essayée (Config.LIVE_SOURCE_CHOICES). */
    var liveSource by mutableStateOf("auto")
        private set
    var lowLatency by mutableStateOf(true)
        private set
    var showChat by mutableStateOf(true)
        private set
    /** Worker personnel (sans « / » final), essayé en premier pour la lecture. */
    var customWorker by mutableStateOf<String?>(null)
        private set
    /** Le son continue quand on quitte l'app ou éteint l'écran (téléphone). */
    var backgroundPlay by mutableStateOf(true)
        private set
    /** Image dans l'image en quittant l'app pendant une lecture. */
    var autoPip by mutableStateOf(true)
        private set

    var token by mutableStateOf<String?>(null)
        private set
    var user by mutableStateOf<TwitchUser?>(null)
        private set

    /** Chaînes suivies sur cet appareil (sans compte), les plus récentes d'abord. */
    val localFollows = mutableStateListOf<String>()

    fun init(context: Context) {
        sp = context.getSharedPreferences("tu_prefs", Context.MODE_PRIVATE)
        lang = Lang.of(sp.getString("lang", null)) ?: Lang.of(Locale.getDefault().language) ?: Lang.EN
        qualityPref = sp.getString("quality_pref", null)?.takeIf { it in Quality.PREFS } ?: "auto"
        cellularQuality = sp.getString("quality_cellular", null)?.takeIf { it in Quality.CELLULAR_PREFS } ?: "auto"
        liveSource = sp.getString("live_source", null)?.takeIf { it in Config.LIVE_SOURCE_CHOICES } ?: "auto"
        lowLatency = sp.getBoolean("low_latency", true)
        showChat = sp.getBoolean("show_chat", true)
        backgroundPlay = sp.getBoolean("background_play", true)
        autoPip = sp.getBoolean("auto_pip", true)
        customWorker = sp.getString("custom_worker", null)?.takeIf { it.isNotBlank() }
        token = sp.getString("token", null)
        user = parseJson(sp.getString("user", null))?.let {
            TwitchUser(it.str("id") ?: "", it.str("login") ?: "", it.str("name") ?: "", it.str("avatar") ?: "")
        }
        localFollows.clear()
        parseJsonArray(sp.getString("local_follows", null))?.strings()?.let { localFollows.addAll(it) }
    }

    fun t(key: String): String = I18n.t(key, lang)
    fun tn(key: String, n: Int, abbreviated: Boolean = false): String = I18n.tn(key, n, lang, abbreviated)

    // ── Réglages ──
    fun updateLang(l: Lang) { lang = l; sp.edit().putString("lang", l.code).apply() }
    fun updateQualityPref(p: String) { qualityPref = p; sp.edit().putString("quality_pref", p).apply() }
    fun updateCellularQuality(p: String) { cellularQuality = p; sp.edit().putString("quality_cellular", p).apply() }
    fun updateLiveSource(s: String) { liveSource = s; sp.edit().putString("live_source", s).apply() }
    fun updateLowLatency(on: Boolean) { lowLatency = on; sp.edit().putBoolean("low_latency", on).apply() }
    fun updateShowChat(on: Boolean) { showChat = on; sp.edit().putBoolean("show_chat", on).apply() }
    fun updateBackgroundPlay(on: Boolean) { backgroundPlay = on; sp.edit().putBoolean("background_play", on).apply() }
    fun updateAutoPip(on: Boolean) { autoPip = on; sp.edit().putBoolean("auto_pip", on).apply() }
    fun updateCustomWorker(url: String?) {
        customWorker = url
        sp.edit().putString("custom_worker", url).apply()
    }

    /** Ordre des sources du direct : la préférée d'abord, le Worker en dernier. */
    fun liveSourceSteps(): List<String> {
        val first = liveSource.takeIf { it in Config.LIVE_SOURCES }
        return listOfNotNull(first) + Config.LIVE_SOURCES.filter { it != first } + "worker"
    }

    /** Workers pour la lecture : le personnel d'abord, puis le principal et ses secours. */
    val lookupBases: List<String>
        get() = (listOfNotNull(customWorker) + Config.API_URL + Config.API_FALLBACKS).distinct()

    // ── Compte ──
    val refreshToken: String? get() = sp.getString("refresh_token", null)

    fun saveTokens(access: String, refresh: String?) {
        token = access
        val editor = sp.edit().putString("token", access)
        if (refresh != null) editor.putString("refresh_token", refresh)
        editor.apply()
    }

    fun updateUser(u: TwitchUser?) {
        user = u
        val json = u?.let {
            JSONObject().put("id", it.id).put("login", it.login).put("name", it.displayName).put("avatar", it.avatar).toString()
        }
        sp.edit().putString("user", json).apply()
        // Session web d'un autre compte : retirée (points, drops, prédictions).
        com.mxfia19.twitchunblock.web.WebSession.checkAccount()
        if (u != null) CloudSync.syncNow()
    }

    fun clearAuth() {
        token = null
        user = null
        sp.edit().remove("token").remove("refresh_token").remove("user").apply()
    }

    // ── Suivis de l'appareil ──
    fun isFollowedLocally(login: String) = localFollows.contains(login.lowercase())

    fun toggleLocalFollow(login: String) {
        val l = login.lowercase()
        if (localFollows.contains(l)) localFollows.remove(l) else localFollows.add(0, l)
        sp.edit().putString("local_follows", JSONArray(localFollows.toList()).toString()).apply()
    }

    // ── Reprise des VODs ──
    /** Position gardée d'une VOD (ms), 0 sans reprise. */
    fun vodPosition(id: String): Long = parseJson(sp.getString("vod_progress", null))?.long(id) ?: 0L

    /** Toutes les positions gardées (ms), pour la synchronisation. */
    fun vodPositions(): Map<String, Long> {
        val all = parseJson(sp.getString("vod_progress", null)) ?: return emptyMap()
        val out = HashMap<String, Long>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            all.long(k)?.let { out[k] = it }
        }
        return out
    }

    /** Positions après une fusion avec le serveur (300 VODs au plus, les plus récentes). */
    fun replaceVodPositions(positions: Map<String, Long>) {
        val all = JSONObject()
        positions.entries.sortedByDescending { it.key.toLongOrNull() ?: 0L }.take(300).forEach { all.put(it.key, it.value) }
        sp.edit().putString("vod_progress", all.toString()).apply()
    }

    /** Garde la position (300 VODs au plus) ; effacée près de la fin. */
    fun saveVodPosition(id: String, positionMs: Long, durationMs: Long) {
        val all = parseJson(sp.getString("vod_progress", null)) ?: JSONObject()
        if (durationMs > 0 && positionMs > durationMs - 60_000) all.remove(id)
        else if (positionMs > 30_000) all.put(id, positionMs)
        while (all.length() > 300) {
            val first = all.keys().next()
            all.remove(first)
        }
        sp.edit().putString("vod_progress", all.toString()).apply()
    }
}
