package com.mxfia19.twitchunblock.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.mxfia19.twitchunblock.chat.SavedChat
import com.mxfia19.twitchunblock.chat.VodChat
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Variant
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.player.DownloadPart
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
        /** Partie de la VOD (secondes dans la VOD ; `to` 0 : jusqu'à la fin), 0 et 0 : toute la VOD. */
        val from: Double = 0.0,
        val to: Double = 0.0,
        /** Hauteur et cadence de la qualité, retenues au départ : « 1080p60 (Source) ». */
        val height: Int = 0,
        val fps: Double = 0.0,
        /** Chat gardé avec la vidéo (demandé au départ). */
        val chat: Boolean = true,
    ) {
        val completed: Boolean get() = state == Download.STATE_COMPLETED
        val failed: Boolean get() = state == Download.STATE_FAILED
        /** Une partie seulement : lue depuis son début, sans la reprise de la VOD entière. */
        val isPart: Boolean get() = from > 0 || to > 0
        /** Pour Quality.label : la qualité téléchargée, si sa hauteur est connue. */
        val variants: Map<String, Variant>
            get() = if (height > 0) mapOf(quality.ifEmpty { "Source" } to Variant(height, fps, 0)) else emptyMap()
    }

    /** Choix de la fenêtre de téléchargement (DownloadDialog). */
    data class Options(
        val from: Double = 0.0,
        val to: Double = 0.0,
        val restore: Boolean = true,
        val chat: Boolean = true,
        val height: Int = 0,
        val fps: Double = 0.0,
    )

    lateinit var cache: SimpleCache
        private set
    lateinit var manager: DownloadManager
        private set
    private lateinit var appContext: Context
    /** Dossier des téléchargements (espace libre). */
    private lateinit var dir: File

    val items = mutableStateListOf<Item>()
    /** Chat gardé : nombre de messages, ou -1 pendant sa récupération. */
    val chatCounts = mutableStateMapOf<String, Int>()
    /** Gestionnaire en pause (seul son service le relance) : les
     *  téléchargements à finir attendent « Reprendre ». */
    var paused by mutableStateOf(false)
        private set
    /** « Wi-Fi seulement » en données mobiles (ou sans réseau) : les
     *  téléchargements attendent le Wi-Fi. */
    var waitingForWifi by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    /** Dernier état « en attente du réseau » transmis à DownloadWaiter. */
    private var waitingNetwork = false

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = StandaloneDatabaseProvider(appContext)
        dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
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

            override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) {
                refresh()
            }

            // Réseau perdu ou retrouvé, Wi-Fi seulement : l'attente est affichée.
            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements,
                                                    notMetRequirements: Int) {
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

    /** Retour du réseau, app fermée (DownloadWaiter) : la prochaine ouverture
     *  relance le service, même si l'app tournait déjà. */
    fun allowResume() {
        resumedAtLaunch = false
    }

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
        paused = manager.downloadsPaused
        waitingForWifi = Features.dlWifiOnly.value &&
            (manager.notMetRequirements and Requirements.NETWORK_UNMETERED) != 0
        // En attente du réseau (ou du Wi-Fi) : WorkManager guette son retour, même app fermée.
        val waiting = !manager.downloadsPaused && manager.notMetRequirements != 0 &&
            list.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }
        if (waiting != waitingNetwork) {
            waitingNetwork = waiting
            DownloadWaiter.update(appContext, waiting, Features.dlWifiOnly.value)
        }
        // Progression suivie pendant qu'une VOD se télécharge, pas pendant
        // l'attente (Wi-Fi, pause) : chaque changement d'état relit tout.
        val active = list.any { it.state == Download.STATE_DOWNLOADING }
        if (active && ticker?.isActive != true) {
            ticker = scope.launch {
                while (true) {
                    delay(1000)
                    val current = manager.currentDownloads
                    if (current.none { it.state == Download.STATE_DOWNLOADING }) break
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
            from = meta.dbl("from") ?: 0.0,
            to = meta.dbl("to") ?: 0.0,
            height = meta.int("height") ?: 0,
            fps = meta.dbl("fps") ?: 0.0,
            chat = meta.bool("chat") ?: true,
        )
    }

    /** Lance le téléchargement d'une qualité d'une VOD (sa playlist), toute
     *  la VOD ou une partie (`options`, série 6), et garde son chat. Une
     *  partie est calée sur les segments de la playlist avant de partir : le
     *  chat gardé commence avec l'image. Le fragment de l'adresse (DownloadPart)
     *  dit au lecteur quoi garder, et s'il remet le son des passages coupés. */
    fun start(context: Context, vodId: String, title: String, channel: String, thumbnail: String,
              lengthSeconds: Int, quality: String, playlistUrl: String, options: Options = Options()) {
        val app = context.applicationContext
        scope.launch {
            var part = DownloadPart(options.from, options.to, options.restore)
            if (part.isPart) {
                Http.get(playlistUrl, Config.TWITCH_HEADERS, timeout = 15)?.takeIf { it.code == 200 }?.let { part = part.snapped(it.body) }
            }
            val end = if (part.to > 0) part.to else lengthSeconds.toDouble()
            val length = if (part.isPart) maxOf(0.0, end - part.from).toInt() else lengthSeconds
            val meta = JSONObject().put("title", title).put("channel", channel).put("thumbnail", thumbnail)
                .put("length", length).put("quality", quality).put("restore", part.restore).put("chat", options.chat)
            if (part.isPart) meta.put("from", part.from).put("to", part.to)
            if (options.height > 0) meta.put("height", options.height).put("fps", options.fps)
            val request = DownloadRequest.Builder(vodId, Uri.parse(playlistUrl.substringBefore('#') + "#" + part.fragment))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setData(meta.toString().toByteArray(Charsets.UTF_8))
                .build()
            try {
                DownloadService.sendAddDownload(app, VodDownloadService::class.java, request, false)
            } catch (e: Exception) {
                // App quittée pendant le calage : service refusé en arrière-plan.
                Logger.warn("DOWNLOAD", "Téléchargement non lancé", e.message)
                return@launch
            }
            Logger.info("DOWNLOAD", "Téléchargement lancé",
                        "$vodId · $quality" + if (part.isPart) " · ${part.from.toInt()}–${end.toInt()} s" else "")
            if (options.chat) keepChat(vodId, end, part.from)
        }
    }

    /** Garde le chat de la VOD (de `from` à `end`, secondes) : un seul
     *  enregistrement à la fois (il s'écrit au fil des pages). */
    private fun keepChat(vodId: String, end: Double, from: Double = 0.0) {
        if (chatFile(vodId).exists() || chatCounts[vodId] == -1) return
        chatCounts[vodId] = -1
        scope.launch {
            val n = VodChat.archive(vodId, end, chatFile(vodId), from)
            if (n == null) chatCounts.remove(vodId) else chatCounts[vodId] = n
        }
    }

    /** Reprend un téléchargement en pause (sa raison d'arrêt levée), et le
     *  gestionnaire s'il est lui-même en pause. */
    fun resume(context: Context, vodId: String) {
        DownloadService.sendSetStopReason(context, VodDownloadService::class.java, vodId, Download.STOP_REASON_NONE, false)
        if (manager.downloadsPaused) resumeAll(context)
        Logger.info("DOWNLOAD", "Téléchargement repris", vodId)
    }

    /** Relance un téléchargement en échec avec la même demande : les
     *  segments déjà gardés ne sont pas redemandés. */
    fun retry(context: Context, vodId: String) {
        val download = try { manager.downloadIndex.getDownload(vodId) } catch (e: Exception) { null } ?: return
        DownloadService.sendAddDownload(context, VodDownloadService::class.java, download.request, false)
        if (manager.downloadsPaused) resumeAll(context)
        Logger.info("DOWNLOAD", "Téléchargement relancé", vodId)
        // Chat repris seulement s'il avait été demandé.
        find(vodId)?.let { if (it.chat) keepChat(vodId, it.from + it.lengthSeconds, it.from) }
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

    /** Espace libre là où vont les téléchargements (octets), -1 s'il est illisible. */
    fun freeBytes(): Long = try {
        StatFs((if (dir.exists()) dir else dir.parentFile ?: dir).path).availableBytes
    } catch (e: Exception) {
        -1L
    }

    /** Taille estimée d'un téléchargement (octets) : débit (bits/s) × durée
     *  (s) ; 0 si l'un des deux est inconnu. */
    fun estimate(bandwidth: Int, seconds: Int): Long =
        if (bandwidth <= 0 || seconds <= 0) 0L else bandwidth.toLong() * seconds / 8

    /** « Wi-Fi seulement » (Features.dlWifiOnly) : réseau non facturé exigé
     *  par le gestionnaire. En données mobiles, un téléchargement attend le
     *  Wi-Fi puis repart seul ; app arrêtée entre-temps : à sa prochaine
     *  ouverture, comme après une interruption. */
    fun applyWifiOnly() {
        manager.requirements = Requirements(if (Features.dlWifiOnly.value) Requirements.NETWORK_UNMETERED
                                            else Requirements.NETWORK)
        refresh()
    }

    fun updateWifiOnly(on: Boolean) {
        if (on == Features.dlWifiOnly.value) return
        Features.dlWifiOnly.set(on)
        applyWifiOnly()
        Logger.info("DOWNLOAD", "Téléchargements en Wi-Fi seulement", if (on) "activé" else "désactivé")
    }

    @Suppress("unused")
    private fun jsonArray(list: List<JSONObject>) = JSONArray(list)
}
