package com.mxfia19.twitchunblock.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Export d'une VOD téléchargée en un seul fichier (série 6), à ouvrir dans
 * VLC, à copier ailleurs… Comme l'iPhone : ses segments, lus dans le cache des
 * téléchargements, mis bout à bout. MPEG-TS se met bout à bout tel quel
 * (.ts) ; en fMP4, le segment d'initialisation puis les fragments donnent un
 * MP4 fragmenté (.mp4).
 */
@OptIn(UnstableApi::class)
object DownloadExport {
    /** Exports en cours : VOD → avancement (0 à 1). */
    val progress = mutableStateMapOf<String, Float>()

    private class Plan(val initSegment: String?, val segments: List<String>) {
        val mp4: Boolean get() = initSegment != null || segments.firstOrNull()?.substringBefore('?')?.endsWith(".mp4") == true
    }

    fun export(context: Context, item: Downloads.Item) {
        if (!item.completed || progress.containsKey(item.vodId)) return
        val app = context.applicationContext
        MediaFiles.scope.launch {
            val plan = withContext(Dispatchers.IO) { runCatching { plan(app, item.uri) }.getOrNull() }
            if (plan == null || plan.segments.isEmpty()) {
                Logger.warn("DOWNLOAD", "Export impossible : playlist illisible", item.vodId)
                MediaFiles.toast(context, AppState.t("m6_export_failed"))
                return@launch
            }
            val ext = if (plan.mp4) "mp4" else "ts"
            val name = MediaFiles.fileName(listOf(item.channel, item.title).filter { it.isNotEmpty() }.joinToString(" - ")) + ".$ext"
            MediaFiles.saveAs(context, name, if (plan.mp4) "video/mp4" else "video/mp2t") { target ->
                MediaFiles.scope.launch { write(app, item, plan, target) }
            }
        }
    }

    private suspend fun write(context: Context, item: Downloads.Item, plan: Plan, target: Uri) {
        progress[item.vodId] = 0f
        Logger.info("DOWNLOAD", "Export lancé", "${item.vodId} · ${plan.segments.size} segments")
        val ok = withContext(Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(target, "w") ?: return@withContext false
                out.use { o ->
                    val ds = source(context)
                    plan.initSegment?.let { copy(ds, it, o) }
                    plan.segments.forEachIndexed { i, seg ->
                        copy(ds, seg, o)
                        if (i % 10 == 0) withContext(Dispatchers.Main) { progress[item.vodId] = (i + 1f) / plan.segments.size }
                    }
                }
                true
            } catch (e: Exception) {
                Logger.warn("DOWNLOAD", "Export interrompu", "${item.vodId} — ${e.message}")
                false
            }
        }
        progress.remove(item.vodId)
        if (ok) Logger.success("DOWNLOAD", "VOD exportée", item.vodId)
        MediaFiles.toast(context, AppState.t(if (ok) "export_done" else "m6_export_failed"))
    }

    /** Lecture du cache des téléchargements (le réseau seulement pour ce qui y manquerait). */
    private fun source(context: Context): DataSource =
        CacheDataSource.Factory()
            .setCache(Downloads.cache)
            .setUpstreamDataSourceFactory(PlayerFactory.upstream(context))
            .setCacheWriteDataSinkFactory(null)
            .createDataSource()

    /** Segments de la playlist gardée, adresses résolues comme Media3 (mêmes clés de cache). */
    private fun plan(context: Context, uri: String): Plan {
        val text = read(source(context), uri).toString(Charsets.UTF_8)
        var initSegment: String? = null
        val segments = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP")) {
                if (initSegment == null) Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { initSegment = UriUtil.resolve(uri, it) }
                continue
            }
            if (line.startsWith("#")) continue
            segments += UriUtil.resolve(uri, line)
        }
        return Plan(initSegment, segments)
    }

    private fun read(source: DataSource, uri: String): ByteArray {
        val out = ByteArrayOutputStream()
        source.open(DataSpec(Uri.parse(uri)))
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    private fun copy(source: DataSource, uri: String, out: OutputStream) {
        source.open(DataSpec(Uri.parse(uri)))
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            source.close()
        }
    }
}
