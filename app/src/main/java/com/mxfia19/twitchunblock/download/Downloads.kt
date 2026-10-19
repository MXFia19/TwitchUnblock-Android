package com.mxfia19.twitchunblock.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.mxfia19.twitchunblock.chat.SavedChat
import com.mxfia19.twitchunblock.chat.VodChat
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.player.PlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * VODs téléchargées pour être regardées hors ligne : gestionnaire de
 * téléchargement de Media3 (HLS, reprise, notification de progression), dans
 * un cache que le lecteur lit en premier. Le chat de la VOD est gardé à côté
 * (chat/<id>.json), rejoué hors ligne. Comme VodDownloads (iOS).
 */
@OptIn(UnstableApi::class)
object Downloads {
    /** Une VOD téléchargée (ou en cours), et ce qu'on en montre. */
    data class Item(
        val vodId: String,
        val title: String,
        val channel: String,
        val thumbnail: String,
        val lengthSeconds: Int,
        val quality: String,
        val uri: String,
        val state: Int,
        val percent: Float,
        val bytes: Long,
    ) {
        val completed: Boolean get() = state == Download.STATE_COMPLETED
        val failed: Boolean get() = state == Download.STATE_FAILED
    }

    lateinit var cache: SimpleCache
        private set
    lateinit var manager: DownloadManager
        private set
    private lateinit var appContext: Context

    val items = mutableStateListOf<Item>()
    /** Chat gardé : nombre de messages, ou -1 pendant sa récupération. */
    val chatCounts = mutableStateMapOf<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = StandaloneDatabaseProvider(appContext)
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
        cache = SimpleCache(dir, NoOpCacheEvictor(), db)
        manager = DownloadManager(appContext, db, cache, PlayerFactory.upstream(appContext), Executors.newFixedThreadPool(3))
        manager.maxParallelDownloads = 2
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) Logger.warn("DOWNLOAD", "Téléchargement en échec", "${download.request.id} — ${finalException.message}")
                else if (download.state == Download.STATE_COMPLETED) Logger.success("DOWNLOAD", "VOD téléchargée", download.request.id)
                refresh()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                refresh()
            }
        })
        refresh()
        // Nombre de messages des chats gardés : lu dans leur en-tête, hors du
        // fil principal (avant : chaque fichier relu en entier au lancement).
        val ids = items.map { it.vodId }
        scope.launch {
            val counts = withContext(Dispatchers.IO) {
                ids.mapNotNull { id -> SavedChat.count(chatFile(id))?.let { id to it } }
            }
            for ((id, n) in counts) if (chatCounts[id] == null) chatCounts[id] = n
        }
    }

    fun chatFile(vodId: String) = File(appContext.filesDir, "vodchat/$vodId.json")

    fun find(vodId: String): Item? = items.firstOrNull { it.vodId == vodId }

    /** Le gestionnaire de Media3 démarre en pause et seul son service le
     *  relance (pas de planificateur) : après un redémarrage ou un arrêt
     *  forcé, les téléchargements restaient « en préparation ». Relancés une
     *  fois par lancement de l'app, s'il en reste à finir. */
    private var resumedAtLaunch = false

    fun resumePending(context: Context) {
        if (resumedAtLaunch) return
        if (items.none { !it.completed && !it.failed }) return
        try {
            resumeAll(context)
            resumedAtLaunch = true
            Logger.info("DOWNLOAD", "Téléchargements repris")
        } catch (e: Exception) {
            // Service refusé (app pas encore au premier plan) : au prochain retour.
            Logger.warn("DOWNLOAD", "Reprise des téléchargements impossible", e.message)
        }
    }

    /** Relit l'état des téléchargements ; suit leur progression chaque seconde. */
    fun refresh() {
        val list = mutableListOf<Item>()
        manager.downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) list += item(c.download)
        }
        // Les plus récents d'abord.
        list.sortByDescending { manager.downloadIndex.getDownload(it.vodId)?.startTimeMs ?: 0L }
        items.clear()
        items.addAll(list)
        val active = list.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        if (active && ticker?.isActive != true) {
            ticker = scope.launch {
                while (true) {
                    delay(1000)
                    val current = manager.currentDownloads
                    if (current.isEmpty()) break
                    for (d in current) {
                        val i = items.indexOfFirst { it.vodId == d.request.id }
                        if (i >= 0) items[i] = item(d)
                    }
                }
            }
        }
    }

    private fun item(d: Download): Item {
        val meta = parseJson(d.request.data.toString(Charsets.UTF_8)) ?: JSONObject()
        return Item(
            vodId = d.request.id,
            title = meta.str("title") ?: d.request.id,
            channel = meta.str("channel") ?: "",
            thumbnail = meta.str("thumbnail") ?: "",
            lengthSeconds = meta.int("length") ?: 0,
            quality = meta.str("quality") ?: "",
            uri = d.request.uri.toString(),
            state = d.state,
            percent = d.percentDownloaded.coerceAtLeast(0f),
            bytes = d.bytesDownloaded,
        )
    }

    /** Lance le téléchargement d'une qualité d'une VOD (sa playlist), et
     *  garde son chat. */
    fun start(context: Context, vodId: String, title: String, channel: String, thumbnail: String,
              lengthSeconds: Int, quality: String, playlistUrl: String) {
        val meta = JSONObject().put("title", title).put("channel", channel).put("thumbnail", thumbnail)
            .put("length", lengthSeconds).put("quality", quality)
        val request = DownloadRequest.Builder(vodId, Uri.parse(playlistUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(meta.toString().toByteArray(Charsets.UTF_8))
            .build()
        DownloadService.sendAddDownload(context, VodDownloadService::class.java, request, false)
        Logger.info("DOWNLOAD", "Téléchargement lancé", "$vodId · $quality")
        // Un seul enregistrement du chat à la fois (il s'écrit au fil des pages).
        if (!chatFile(vodId).exists() && chatCounts[vodId] != -1) {
            chatCounts[vodId] = -1
            scope.launch {
                val n = VodChat.archive(vodId, lengthSeconds.toDouble(), chatFile(vodId))
                if (n == null) chatCounts.remove(vodId) else chatCounts[vodId] = n
            }
        }
    }

    fun remove(context: Context, vodId: String) {
        DownloadService.sendRemoveDownload(context, VodDownloadService::class.java, vodId, false)
        chatFile(vodId).delete()
        chatCounts.remove(vodId)
        Logger.info("DOWNLOAD", "Téléchargement supprimé", vodId)
    }

    fun pauseAll(context: Context) = DownloadService.sendPauseDownloads(context, VodDownloadService::class.java, false)
    fun resumeAll(context: Context) = DownloadService.sendResumeDownloads(context, VodDownloadService::class.java, false)

    /** Place prise par les téléchargements (octets). */
    fun usedBytes(): Long = try { cache.cacheSpace } catch (e: Exception) { 0L }

    @Suppress("unused")
    private fun jsonArray(list: List<JSONObject>) = JSONArray(list)
}
