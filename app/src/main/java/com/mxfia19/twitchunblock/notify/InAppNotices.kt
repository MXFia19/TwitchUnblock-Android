package com.mxfia19.twitchunblock.notify

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mxfia19.twitchunblock.App
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.feedback.FeedbackWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Un avis de l'app : réponse à un signalement, drop prêt ou réclamé. */
data class InAppNotice(
    val kind: Kind,
    val title: String,
    val body: String,
    val at: Long = System.currentTimeMillis(),
    /** Identifiant d'affichage (un bandeau remplacé par le suivant s'anime). */
    val uid: Long = System.nanoTime(),
) {
    enum class Kind { FEEDBACK, DROP_READY, DROP_CLAIMED }
}

/**
 * Avis internes. App à l'écran : bandeau en haut (AppOverlays), que les
 * notifications soient permises ou non — sans permission, Android n'en
 * montrait rien. App en arrière-plan : notification Android si elle est
 * permise, sinon l'avis attend le retour dans l'app (trois jours au plus).
 * Comme InAppNotices (iOS). Fil principal.
 */
object InAppNotices : Application.ActivityLifecycleCallbacks {
    /** Bandeau affiché, s'il y en a un. */
    var current by mutableStateOf<InAppNotice?>(null)
        private set

    /** Activités démarrées (téléphone, TV) : 0, l'app est en arrière-plan. */
    private var started = 0
    val foreground: Boolean get() = started > 0
    /** Écrans qui savent montrer le bandeau (AppOverlays du téléphone). */
    private var hosts = 0

    private val queue = ArrayDeque<InAppNotice>()
    private var timer: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val PENDING = "inapp_pending"
    /** Page ouverte par une notification touchée (« feedback », « drops »). */
    const val EXTRA_OPEN = "tu_open"

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Bandeau si l'app est à l'écran ; sinon notification Android si elles
     *  sont permises, sinon gardé pour le retour. `tag` : identifiant de la
     *  notification (le même avis ne s'empile pas). */
    fun deliver(notice: InAppNotice, tag: String) {
        if (foreground && hosts > 0 && !com.mxfia19.twitchunblock.ui.PipState.active) {
            show(notice)
            return
        }
        if (post(App.instance, notice, tag)) return
        keepForLater(notice)
    }

    /** Un bandeau à la fois, 5 s chacun ; le même avis n'est pas répété. */
    fun show(notice: InAppNotice) {
        fun same(n: InAppNotice) = n.kind == notice.kind && n.title == notice.title && n.body == notice.body
        if (queue.any { same(it) } || current?.let { same(it) } == true) return
        queue.addLast(notice)
        while (queue.size > 5) queue.removeFirst()
        if (current == null) next()
    }

    fun dismiss() {
        timer?.cancel()
        current = null
        if (queue.isEmpty()) return
        // Le temps que le bandeau parte avant le suivant.
        timer = scope.launch {
            delay(400)
            next()
        }
    }

    private fun next() {
        val n = queue.removeFirstOrNull() ?: return
        current = n
        timer?.cancel()
        timer = scope.launch {
            delay(5_000)
            dismiss()
        }
    }

    /** Écran capable de montrer le bandeau : les avis gardés pendant
     *  l'absence s'affichent dès qu'il est là. */
    fun addHost() {
        hosts++
        if (foreground) showPending()
    }

    fun removeHost() {
        hosts = maxOf(0, hosts - 1)
    }

    // ── Notifications Android ──

    private fun channelOf(kind: InAppNotice.Kind) = if (kind == InAppNotice.Kind.FEEDBACK) "feedback" else "drops"

    /** false : pas permise (autorisation, réglages, canal coupé) ou refusée. */
    private fun post(context: Context, notice: InAppNotice, tag: String): Boolean {
        if (!LiveNotifier.canNotify(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = channelOf(notice.kind)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            if (nm.getNotificationChannel(channel) == null) {
                val name = AppState.t(if (notice.kind == InAppNotice.Kind.FEEDBACK) "fb_mine" else "drops")
                nm.createNotificationChannel(NotificationChannel(channel, name, NotificationManager.IMPORTANCE_DEFAULT))
            }
            // Canal coupé dans les réglages d'Android : rien ne s'afficherait.
            if (nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val open = Intent(context, com.mxfia19.twitchunblock.ui.MainActivity::class.java)
            .putExtra(EXTRA_OPEN, if (notice.kind == InAppNotice.Kind.FEEDBACK) "feedback" else "drops")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, tag.hashCode(), open,
                                           PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(if (notice.kind == InAppNotice.Kind.FEEDBACK) android.R.drawable.sym_action_chat else android.R.drawable.star_on)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(tag.hashCode(), n)
            true
        } catch (e: SecurityException) {
            Logger.warn("NOTIF", "Notification refusée par Android")
            false
        }
    }

    // ── Avis gardés pour le retour ──

    private fun keepForLater(notice: InAppNotice) {
        val list = parseJsonArray(AppState.prefs.getString(PENDING, null)) ?: JSONArray()
        list.put(JSONObject().put("kind", notice.kind.name).put("title", notice.title).put("body", notice.body).put("at", notice.at))
        val kept = JSONArray((maxOf(0, list.length() - 5) until list.length()).map { list.get(it) })
        AppState.prefs.edit().putString(PENDING, kept.toString()).apply()
    }

    /** Retour dans l'app : les avis arrivés en arrière-plan sans pouvoir
     *  notifier (trois jours au plus). */
    private fun showPending() {
        val raw = AppState.prefs.getString(PENDING, null) ?: return
        AppState.prefs.edit().remove(PENDING).apply()
        val now = System.currentTimeMillis()
        for (o in parseJsonArray(raw)?.objects().orEmpty()) {
            val kind = InAppNotice.Kind.entries.firstOrNull { it.name == o.str("kind") } ?: continue
            val at = o.long("at") ?: 0L
            if (now - at > 3 * 86_400_000L) continue
            show(InAppNotice(kind, o.str("title") ?: "", o.str("body") ?: "", at))
        }
    }

    // ── Premier plan ──

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) {
            if (hosts > 0) showPending()
            FeedbackWatch.foregroundChanged(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        if (started == 0) FeedbackWatch.foregroundChanged(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
