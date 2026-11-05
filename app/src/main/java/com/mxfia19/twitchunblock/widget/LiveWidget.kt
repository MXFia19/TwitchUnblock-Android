package com.mxfia19.twitchunblock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.R
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.AuthExpiredException
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.Links
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Pins
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Image réduite (miniature, avatar) en bitmap logiciel : widget, raccourcis. */
internal suspend fun loadBitmap(context: Context, url: String, width: Int, height: Int, circle: Boolean): Bitmap? {
    val req = ImageRequest.Builder(context).data(url).size(width, height).allowHardware(false)
        .transformations(if (circle) CircleCropTransformation() else RoundedCornersTransformation(10f))
        .build()
    return (context.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
}

/**
 * Widget de l'écran d'accueil : les chaînes suivies (compte et appareil) en
 * direct, épinglées en tête. Données gardées dans les préférences, écrites
 * par l'accueil de l'app quand il les lit, et toutes les 15 min (WorkManager)
 * tant qu'un widget est posé ; ⟳ les relit tout de suite.
 */
object LiveWidget {
    private const val KEY = "widget_lives"
    private const val WORK = "live-widget"
    const val ACTION_REFRESH = "com.mxfia19.twitchunblock.WIDGET_REFRESH"

    class Item(val login: String, val name: String, val title: String, val game: String, val viewers: Int,
               val startedAt: String?, val thumb: String)

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LiveWidgetProvider::class.java))

    /** Directs suivis lus par l'app (accueil) ou le widget : gardés, widgets redessinés. */
    fun save(context: Context, streams: List<TwitchStream>) {
        if (ids(context).isEmpty()) return
        val arr = JSONArray()
        Pins.sortStreams(streams).take(30).forEach { s ->
            arr.put(JSONObject().put("login", s.login).put("name", s.name).put("title", s.title).put("game", s.game)
                        .put("viewers", s.viewers).put("startedAt", s.startedAt ?: "").put("thumb", s.thumb(320, 180)))
        }
        AppState.prefs.edit().putString(KEY, JSONObject().put("at", System.currentTimeMillis()).put("items", arr).toString()).apply()
        render(context)
    }

    fun read(): Pair<List<Item>, Long> {
        val o = parseJson(AppState.prefs.getString(KEY, null)) ?: return Pair(emptyList(), 0L)
        val items = o.optJSONArray("items")?.objects().orEmpty().mapNotNull { j ->
            val login = j.str("login")?.takeIf { Links.isValidLogin(it.lowercase()) } ?: return@mapNotNull null
            Item(login.lowercase(), j.str("name") ?: login, j.str("title") ?: "", j.str("game") ?: "", j.int("viewers") ?: 0,
                 j.str("startedAt")?.takeIf { it.isNotEmpty() }, j.str("thumb") ?: "")
        }
        return Pair(items, o.long("at") ?: 0L)
    }

    /** Directs suivis : compte (Helix) et appareil (GQL). null : Twitch n'a pas répondu. */
    suspend fun fetch(): List<TwitchStream>? {
        var reached = false
        val out = mutableListOf<TwitchStream>()
        if (AppState.token != null && AppState.user != null) {
            try {
                BrowseApi.followedStreams()?.let { out += it; reached = true }
            } catch (e: AuthExpiredException) {
                Logger.warn("WIDGET", "Session Twitch refusée")
            }
        }
        val known = out.map { it.login.lowercase() }.toHashSet()
        val local = AppState.localFollows.filter { it !in known }
        if (local.isEmpty()) reached = reached || AppState.user == null
        else BrowseApi.liveByLogins(local)?.let { out += it; reached = true }
        return if (reached) out.distinctBy { it.login.lowercase() } else null
    }

    fun refresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<LiveWidgetWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, request)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LiveWidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private fun flags(mutable: Boolean): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) else PendingIntent.FLAG_IMMUTABLE

    /** Redessine chaque widget posé : en-tête, liste (LiveWidgetService), texte si vide. */
    fun render(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val (items, at) = read()
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_live)
            views.setTextViewText(R.id.widget_title,
                                  AppState.t("br_widget_title") + if (items.isNotEmpty()) " · ${items.size}" else "")
            views.setTextViewText(R.id.widget_time, if (at > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)) else "")
            val setup = AppState.user == null && AppState.localFollows.isEmpty()
            views.setTextViewText(R.id.widget_empty, AppState.t(if (setup) "br_widget_setup" else "br_widget_empty"))
            views.setContentDescription(R.id.widget_refresh, AppState.t("drops_refresh"))
            // Liste : une ligne par direct, fournie par LiveWidgetService.
            val adapter = Intent(context, LiveWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, adapter)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            // Ligne touchée : le lien du direct (comme une notification de live).
            val template = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, 10, template, flags(true)))
            views.setOnClickPendingIntent(R.id.widget_header,
                                          PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), flags(false)))
            val refresh = Intent(context, LiveWidgetProvider::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 12, refresh, flags(false)))
            manager.updateAppWidget(id, views)
        }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }
}

class LiveWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        LiveWidget.render(context)
        LiveWidget.refresh(context)
    }

    override fun onEnabled(context: Context) {
        LiveWidget.schedule(context)
    }

    override fun onDisabled(context: Context) {
        LiveWidget.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == LiveWidget.ACTION_REFRESH) LiveWidget.refresh(context)
    }
}

/** Relecture des directs suivis pour le widget (périodique, ou ⟳). */
class LiveWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val list = LiveWidget.fetch()
        if (list != null) LiveWidget.save(applicationContext, list) else LiveWidget.render(applicationContext)
        Result.success()
    }
}

class LiveWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = LiveWidgetFactory(applicationContext)
}

/** Lignes du widget : miniature, chaîne, titre, jeu · spectateurs · durée. */
class LiveWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<LiveWidget.Item> = emptyList()
    private val thumbs = HashMap<String, Bitmap>()

    override fun onCreate() {}

    /** Appelé hors du fil principal : les miniatures peuvent s'y charger. */
    override fun onDataSetChanged() {
        items = LiveWidget.read().first
        thumbs.clear()
        runBlocking {
            withTimeoutOrNull(10_000) {
                for (it in items.take(20)) {
                    if (it.thumb.startsWith("https://")) {
                        loadBitmap(context, it.thumb + "?t=" + System.currentTimeMillis() / 300_000, 240, 135, circle = false)
                            ?.let { b -> thumbs[it.login] = b }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        items = emptyList()
        thumbs.clear()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_live_row)
        val it = items.getOrNull(position) ?: return views
        val pinned = if (Pins.isPinned(it.login)) "📌 " else ""
        views.setTextViewText(R.id.row_name, pinned + it.name)
        views.setTextViewText(R.id.row_title, it.title)
        val start = Format.parseDate(it.startedAt)
        val uptime = start?.let { s ->
            val sec = maxOf(0L, (System.currentTimeMillis() - s) / 1000)
            if (sec >= 3600) "${sec / 3600}h ${(sec % 3600) / 60}min" else "${sec / 60}min"
        }
        views.setTextViewText(R.id.row_meta, listOfNotNull(it.game.takeIf { g -> g.isNotEmpty() },
                                                           "● " + Format.viewers(it.viewers), uptime).joinToString(" · "))
        val thumb = thumbs[it.login]
        if (thumb != null) {
            views.setImageViewBitmap(R.id.row_thumb, thumb)
            views.setViewVisibility(R.id.row_thumb, View.VISIBLE)
        } else {
            views.setImageViewResource(R.id.row_thumb, R.drawable.widget_thumb_bg)
        }
        views.setOnClickFillInIntent(R.id.row_root,
                                     Intent().setData(Uri.parse("${BuildConfig.APP_SCHEME}://live/${it.login}")))
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.login?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}

/**
 * Raccourcis de l'icône (appui long sur l'icône de l'app) : les dernières
 * chaînes regardées, qui lancent leur direct. Retirés quand « Streamers
 * récents » est coupé.
 */
object AppShortcuts {
    const val MAX = 4

    suspend fun update(context: Context) {
        try {
            if (!Features.showRecent.value) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 }?.coerceAtMost(MAX) ?: MAX
            // Rattachés à l'alias de l'icône en place (seuls les alias sont des
            // lanceurs) : sans lui, Android les gardait sur l'alias d'alors.
            val launcher = com.mxfia19.twitchunblock.ui.AppIcons.current(context).component(context)
            val recent = History.channels.filter { Links.isValidLogin(it.term.lowercase()) }.take(max)
            val list = recent.mapIndexed { i, h ->
                val login = h.term.lowercase()
                val name = h.display.ifEmpty { login }
                val b = ShortcutInfoCompat.Builder(context, "live-$login")
                    .setShortLabel(name.take(25))
                    .setLongLabel(AppState.t("br_shortcut_long").replace("{c}", name))
                    .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.APP_SCHEME}://live/$login"))
                                   .setClass(context, MainActivity::class.java))
                    .setActivity(launcher)
                    .setRank(i)
                h.avatar?.takeIf { it.startsWith("https://") }?.let { url ->
                    loadBitmap(context, url, 192, 192, circle = true)?.let { b.setIcon(IconCompat.createWithBitmap(it)) }
                }
                b.build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        } catch (e: Exception) {
            Logger.warn("SHORTCUTS", "Raccourcis non mis à jour", e.message)
        }
    }
}
