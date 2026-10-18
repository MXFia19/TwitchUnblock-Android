package com.mxfia19.twitchunblock.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.AuthExpiredException
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * « Chaîne suivie en live » : vérifié toutes les 15 minutes par WorkManager
 * (même app fermée), notification pour chaque nouveau direct. Avec ntfy
 * actif, c'est lui qui prévient (plus vite, sans doublon). Comme
 * LiveNotifier (iOS).
 */
object LiveNotifier {
    private const val ENABLED = "notif_live"
    private const val KNOWN = "notif_known_live"
    private const val CHANNEL = "lives"
    private const val WORK = "live-check"

    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        enabled = AppState.prefs.getBoolean(ENABLED, false)
        if (enabled || Ntfy.enabled) schedule(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        AppState.prefs.edit().putBoolean(ENABLED, on).apply()
        if (on || Ntfy.enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LiveCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, AppState.t("notif_channel_lives"), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** Relit les directs suivis ; notifie les nouveaux si `notify`. */
    suspend fun check(context: Context, notify: Boolean): Boolean {
        if (!enabled) return false
        val streams = mutableListOf<TwitchStream>()
        var reached = false
        if (AppState.user != null) {
            try {
                BrowseApi.followedStreams()?.let { streams += it; reached = true }
            } catch (e: AuthExpiredException) {
                Logger.warn("NOTIF", "Session Twitch expirée")
            }
        }
        val local = AppState.localFollows.toList()
        if (local.isNotEmpty()) {
            val known = streams.map { it.login.lowercase() }.toHashSet()
            BrowseApi.liveByLogins(local)?.let { live ->
                streams += live.filter { it.login.lowercase() !in known }
                reached = true
            }
        }
        if (!reached) return false
        val before = parseJsonArray(AppState.prefs.getString(KNOWN, null))?.strings()?.toSet()
        val now = streams.map { it.login.lowercase() }.toSet()
        if (notify && before != null && !(Ntfy.enabled && Ntfy.synced) && canNotify(context)) {
            ensureChannel(context)
            for (s in streams) if (s.login.lowercase() !in before) post(context, s)
        }
        AppState.prefs.edit().putString(KNOWN, JSONArray(now.toList()).toString()).apply()
        return true
    }

    private fun post(context: Context, s: TwitchStream) {
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.APP_SCHEME}://live/${s.login.lowercase()}"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, s.login.hashCode(), open,
                                           PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = listOf(s.title, s.game).filter { it.isNotEmpty() }.joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AppState.t("notif_live_title").replace("{u}", s.name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("live-${s.login.lowercase()}".hashCode(), n)
        } catch (e: SecurityException) {
            Logger.warn("NOTIF", "Notification refusée par Android")
        }
    }

    /** Notification immédiate, pour vérifier autorisation et affichage. */
    fun test(context: Context): Boolean {
        if (!canNotify(context)) return false
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AppState.t("notif_test_title"))
            .setContentText(AppState.t("notif_test_body"))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(1, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

/** Vérification périodique : lives suivis, et sujet ntfy renvoyé au Worker. */
class LiveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        LiveNotifier.check(applicationContext, notify = true)
        if (Ntfy.enabled) Ntfy.sync()
        Result.success()
    }
}

/**
 * Notifications instantanées par ntfy (app gratuite) : un sujet secret créé
 * par l'app, auquel on s'abonne dans ntfy ; le Worker y publie quand une
 * chaîne suivie passe en live. Toucher la notification ouvre le direct ici.
 * Comme NtfyNotifier (iOS).
 */
object Ntfy {
    private const val ENABLED = "ntfy_enabled"
    private const val TOPIC = "ntfy_topic"
    private const val HASH = "ntfy_hash"
    private const val SYNCED = "ntfy_synced"
    private const val COUNT = "ntfy_channels"
    private const val ACCOUNT = "ntfy_account_follows"

    var enabled by mutableStateOf(false)
        private set
    /** Le Worker a accepté l'abonnement (dernier envoi réussi). */
    var synced by mutableStateOf(false)
        private set
    var channels by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun init() {
        val p = AppState.prefs
        enabled = p.getBoolean(ENABLED, false)
        synced = p.getLong(SYNCED, 0L) > 0
        channels = p.getInt(COUNT, 0)
    }

    /** Sujet secret (« tu- » et 24 caractères au hasard), créé une fois. */
    val topic: String
        get() = AppState.prefs.getString(TOPIC, null) ?: ("tu-" + (1..24).map {
            "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]
        }.joinToString("")).also { AppState.prefs.edit().putString(TOPIC, it).apply() }

    /** Lien qui ouvre l'abonnement dans l'app ntfy. */
    val subscribeUrl: String get() = "https://ntfy.sh/$topic"

    suspend fun enable(context: Context): Boolean {
        enabled = true
        AppState.prefs.edit().putBoolean(ENABLED, true).apply()
        LiveNotifier.schedule(context)
        return sync(force = true)
    }

    suspend fun disable() {
        enabled = false
        synced = false
        AppState.prefs.edit().putBoolean(ENABLED, false).remove(HASH).remove(SYNCED).apply()
        post("/api/push/unsubscribe", JSONObject().put("ntfy", topic))
    }

    /** Chaînes suivies (compte et appareil) renvoyées au Worker quand elles
     *  changent, et au moins une fois par jour (au-delà de 60 jours sans
     *  nouvelles, il oublie le sujet). */
    suspend fun sync(force: Boolean = false): Boolean {
        if (!enabled) return false
        val p = AppState.prefs
        val list = ArrayList<String>(AppState.localFollows)
        if (AppState.user != null) {
            val account = BrowseApi.followedLogins()
            if (account != null) {
                p.edit().putString(ACCOUNT, JSONArray(account).toString()).apply()
                list += account
            } else {
                parseJsonArray(p.getString(ACCOUNT, null))?.strings()?.let { list += it }
            }
        }
        val all = list.map { it.lowercase() }.distinct().take(500)
        val hash = AppState.lang.code + "|" + all.joinToString(",")
        val last = p.getLong(SYNCED, 0L)
        if (!force && hash == p.getString(HASH, null) && System.currentTimeMillis() - last < 86_400_000L && error == null) return true
        val code = post("/api/push/subscribe", JSONObject()
            .put("ntfy", topic).put("channels", JSONArray(all)).put("lang", AppState.lang.code)
            .put("scheme", BuildConfig.APP_SCHEME))
        if (!enabled) return false
        if (code != 200) {
            error = code?.let { "HTTP $it" } ?: "network"
            Logger.warn("NTFY", "Abonnement refusé par le Worker", error)
            return false
        }
        error = null
        synced = true
        channels = all.size
        p.edit().putString(HASH, hash).putLong(SYNCED, System.currentTimeMillis()).putInt(COUNT, all.size).apply()
        return true
    }

    /** Notification de test : "sent", "limited" (ntfy.sh refuse pour l'instant) ou "failed". */
    suspend fun test(): String {
        val r = Http.postJson("${Config.API_URL}/api/push/test", JSONObject()
            .put("ntfy", topic).put("lang", AppState.lang.code).put("scheme", BuildConfig.APP_SCHEME).toString(), timeout = 20)
            ?: return "failed"
        if (r.code == 200) return "sent"
        return if (parseJson(r.body)?.bool("limit") == true) "limited" else "failed"
    }

    private suspend fun post(path: String, body: JSONObject): Int? =
        Http.postJson(Config.API_URL + path, body.toString(), timeout = 20)?.code
}
