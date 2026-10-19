package com.mxfia19.twitchunblock.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.AuthExpiredException
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Links
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * « Chaîne suivie en live » : vérifié toutes les 15 minutes par WorkManager
 * (même app fermée), notification pour chaque nouveau direct (vignette,
 * « Regarder », « Ne plus notifier cette chaîne »), sauf pour les chaînes
 * coupées dans « Chaînes notifiées ». Avec ntfy actif, c'est lui qui
 * prévient (plus vite, sans doublon). Comme LiveNotifier (iOS).
 */
object LiveNotifier {
    private const val ENABLED = "notif_live"
    private const val KNOWN = "notif_known_live"
    private const val CHANNEL = "lives"
    private const val WORK = "live-check"
    /** Action « Ne plus notifier cette chaîne » (LiveActionReceiver). */
    const val ACTION_MUTE = "com.mxfia19.twitchunblock.LIVE_MUTE"
    const val EXTRA_LOGIN = "login"

    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        enabled = AppState.prefs.getBoolean(ENABLED, false)
        NotifChannels.init()
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
        var accountRead = AppState.user == null
        if (AppState.user != null) {
            try {
                BrowseApi.followedStreams()?.let { streams += it; reached = true; accountRead = true }
            } catch (e: AuthExpiredException) {
                Logger.warn("NOTIF", "Session Twitch expirée")
            }
        }
        val local = AppState.localFollows.toList()
        var localRead = local.isEmpty()
        if (local.isNotEmpty()) {
            val known = streams.map { it.login.lowercase() }.toHashSet()
            BrowseApi.liveByLogins(local)?.let { live ->
                streams += live.filter { it.login.lowercase() !in known }
                reached = true
                localRead = true
            }
        }
        if (!reached) return false
        val before = parseJsonArray(AppState.prefs.getString(KNOWN, null))?.strings()?.toSet()
        val now = streams.map { it.login.lowercase() }.toSet()
        if (notify && before != null && !(Ntfy.enabled && Ntfy.synced) && canNotify(context)) {
            ensureChannel(context)
            // Chaînes coupées dans « Chaînes notifiées » : connues quand même
            // (plus bas), pour ne pas notifier un direct en cours le jour où
            // on les rallume. En parallèle : la vignette lente d'une chaîne ne
            // retarde pas les autres notifications.
            val fresh = streams.filter { it.login.lowercase() !in before && NotifChannels.notifies(it.login) }
            coroutineScope { for (s in fresh) launch { post(context, s) } }
        }
        // Seule la partie relue (compte ou appareil) est remplacée : sinon,
        // le compte injoignable une fois, tous ses directs déjà en cours
        // étaient notifiés comme nouveaux à la vérification suivante.
        val localSet = local.map { it.lowercase() }.toHashSet()
        val kept = before.orEmpty().filter { if (it in localSet) !localRead else !accountRead }
        AppState.prefs.edit().putString(KNOWN, JSONArray((now + kept).toList()).toString()).apply()
        return true
    }

    /** Notification d'une chaîne : une seule à la fois (un nouveau direct
     *  remplace l'ancien), retirée par « Ne plus notifier cette chaîne ». */
    fun notificationId(login: String): Int = "live-${login.lowercase()}".hashCode()

    /** Notification d'un direct : vignette si elle arrive à temps, un groupe
     *  par chaîne, « Regarder » (même lien que la notification) et « Ne plus
     *  notifier cette chaîne » (LiveActionReceiver, sans ouvrir l'app). */
    private suspend fun post(context: Context, s: TwitchStream) {
        val login = s.login.lowercase()
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.APP_SCHEME}://live/$login"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, s.login.hashCode(), open,
                                           PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mute = Intent(context, LiveActionReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_LOGIN, login)
        val mutePi = PendingIntent.getBroadcast(context, login.hashCode(), mute,
                                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = listOf(s.title, s.game).filter { it.isNotEmpty() }.joinToString(" · ")
        val picture = thumbnail(s)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AppState.t("notif_live_title").replace("{u}", s.name))
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup("live-$login")
            .addAction(android.R.drawable.ic_media_play, AppState.t("notif_action_watch"), pi)
            .addAction(android.R.drawable.ic_lock_silent_mode, AppState.t("notif_action_mute"), mutePi)
        if (picture != null) {
            // Repliée : la vignette à droite ; dépliée : en grand, sans la répéter.
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId(login), builder.build())
        } catch (e: SecurityException) {
            Logger.warn("NOTIF", "Notification refusée par Android")
        }
    }

    /** Vignette du direct (640×360), téléchargée hors du fil principal, 6 s
     *  au plus. null (pas encore prête, réseau, image illisible) : la
     *  notification part sans elle. */
    private suspend fun thumbnail(s: TwitchStream): Bitmap? = withContext(Dispatchers.IO) {
        val login = s.login.lowercase()
        val url = if (s.thumbnail.isNotEmpty()) s.thumb(640, 360)
                  else "https://static-cdn.jtvnw.net/previews-ttv/live_user_$login-640x360.jpg"
        val bitmap = try {
            val request = Request.Builder().url(url).build()
            Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                // Direct à peine lancé : Twitch renvoie vers son image « 404 ».
                if (!r.isSuccessful || r.request.url.encodedPath.contains("404_preview")) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) Logger.debug("NOTIF", "Vignette du live indisponible", "@$login")
        bitmap
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

/** Envoi ponctuel de la liste ntfy (chaîne coupée ou rallumée : Ntfy.syncSoon). */
class NtfySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        if (Ntfy.enabled) Ntfy.sync()
        Result.success()
    }
}

/**
 * « Ne plus notifier cette chaîne » (action d'une notification de live), sans
 * ouvrir l'app : la chaîne passe dans les coupées de « Chaînes notifiées »,
 * sa notification disparaît, et ntfy suit. Comme LiveNotifier.mute (iOS).
 */
class LiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveNotifier.ACTION_MUTE) return
        val login = intent.getStringExtra(LiveNotifier.EXTRA_LOGIN)?.lowercase() ?: return
        if (!Links.isValidLogin(login)) return
        NotifChannels.setNotify(context, login, false)
        NotificationManagerCompat.from(context).cancel(LiveNotifier.notificationId(login))
        Logger.info("NOTIF", "Chaîne retirée des notifications", "@$login")
    }
}

/**
 * « Chaînes notifiées » : les notifications de live (vérification de
 * WorkManager, ntfy) couvrent les chaînes suivies — compte Twitch et
 * appareil —, moins celles coupées ici (cloche d'une chaîne, action d'une
 * notification, liste des réglages). La liste reste sur l'appareil : le
 * Worker ne reçoit que les chaînes à notifier (Ntfy.sync). Une chaîne suivie
 * plus tard est notifiée d'office. Comme NotifChannels (iOS).
 */
object NotifChannels {
    private const val MUTED = "notif_muted"

    /** Chaînes coupées (pseudos en minuscules). */
    var muted by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Chaînes suivies par le compte Twitch, pour les cloches : relues au plus
     *  une fois par heure (ou données par ntfy, qui vient de les lire). */
    var accountFollows by mutableStateOf<Set<String>>(emptySet())
        private set
    private var accountUser: String? = null
    private var accountAt = 0L
    private var accountLoading = false

    fun init() {
        muted = parseJsonArray(AppState.prefs.getString(MUTED, null))?.strings()?.map { it.lowercase() }?.toSet() ?: emptySet()
    }

    /** Notifications de live actives (Android ou ntfy) : les cloches s'affichent. */
    val active: Boolean get() = LiveNotifier.enabled || Ntfy.enabled

    fun notifies(login: String): Boolean = login.lowercase() !in muted

    /** Chaîne suivie sur l'appareil, ou par le compte (dernière liste lue). */
    fun followed(login: String): Boolean {
        val l = login.lowercase()
        return AppState.isFollowedLocally(l) || (AppState.user?.id == accountUser && l in accountFollows)
    }

    /** Une seule chaîne (cloche, action d'une notification, liste) : notifiée ou coupée. */
    fun setNotify(context: Context, login: String, on: Boolean) {
        val l = login.lowercase()
        if (l.isEmpty()) return
        update(context, if (on) muted - l else muted + l)
    }

    /** Plusieurs chaînes à la fois (« Tout activer », « Tout couper »). */
    fun setNotifyAll(context: Context, logins: Collection<String>, on: Boolean) {
        val list = logins.map { it.lowercase() }
        update(context, if (on) muted - list.toSet() else muted + list)
    }

    private fun update(context: Context, next: Set<String>) {
        if (next == muted) return
        muted = next
        AppState.prefs.edit().putString(MUTED, JSONArray(next.sorted()).toString()).apply()
        // ntfy : la liste filtrée part au Worker (l'app peut être fermée).
        if (Ntfy.enabled) Ntfy.syncSoon(context)
    }

    /** Suivis du compte qui viennent d'être lus (ntfy, liste des réglages). */
    fun rememberAccount(logins: List<String>) {
        accountFollows = logins.map { it.lowercase() }.toSet()
        accountUser = AppState.user?.id
        accountAt = System.currentTimeMillis()
    }

    /** Suivis du compte relus sur Twitch, au plus une fois par heure. */
    suspend fun loadAccountFollows() {
        val user = AppState.user?.id ?: return
        if (accountLoading || (user == accountUser && System.currentTimeMillis() - accountAt < 3_600_000L)) return
        accountLoading = true
        try {
            BrowseApi.followedLogins()?.let { rememberAccount(it) }
        } finally {
            accountLoading = false
        }
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
    private const val ACCOUNT_USER = "ntfy_account_user"
    private const val ACCOUNT_AT = "ntfy_account_at"

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

    /** sync() dès que le réseau le permet, par WorkManager : depuis l'action
     *  d'une notification, l'app peut être fermée. Une seule à la fois (la
     *  dernière demandée remplace celle en attente). */
    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<NtfySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ntfy-sync", ExistingWorkPolicy.REPLACE, request)
    }

    /** Chaînes suivies (compte et appareil) renvoyées au Worker quand elles
     *  changent, et au moins une fois par jour (au-delà de 60 jours sans
     *  nouvelles, il oublie le sujet). */
    suspend fun sync(force: Boolean = false): Boolean {
        if (!enabled) return false
        val p = AppState.prefs
        val list = ArrayList<String>(AppState.localFollows)
        if (AppState.user != null) {
            // Suivis du compte (jusqu'à 10 pages Helix) relus au plus une fois
            // par heure, et non à chaque retour dans l'app : entre-temps, la
            // dernière liste lue pour ce compte.
            val user = AppState.user?.id ?: ""
            val recent = p.getString(ACCOUNT_USER, null) == user &&
                System.currentTimeMillis() - p.getLong(ACCOUNT_AT, 0L) < 3_600_000L
            val account = if (force || !recent) BrowseApi.followedLogins() else null
            if (account != null) {
                p.edit().putString(ACCOUNT, JSONArray(account).toString())
                    .putString(ACCOUNT_USER, user).putLong(ACCOUNT_AT, System.currentTimeMillis()).apply()
                NotifChannels.rememberAccount(account)
                list += account
            } else {
                parseJsonArray(p.getString(ACCOUNT, null))?.strings()?.let { list += it }
            }
        }
        // Moins celles coupées dans « Chaînes notifiées » (le hachage change :
        // la nouvelle liste part tout de suite).
        val muted = NotifChannels.muted
        val all = list.map { it.lowercase() }.distinct().filter { it !in muted }.take(500)
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
