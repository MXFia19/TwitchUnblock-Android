package com.mxfia19.twitchunblock.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mxfia19.twitchunblock.core.Config
import java.io.ByteArrayOutputStream

object PlayerFactory {
    /** Lecteur : navigateur de bureau comme User-Agent, playlists réécrites
     *  (passages coupés des VODs), focus audio et pause au débranchement. */
    @OptIn(UnstableApi::class)
    fun build(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(context, http)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RewritingFactory(upstream)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

@OptIn(UnstableApi::class)
private class RewritingFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = PlaylistRewritingDataSource(upstream.createDataSource())
}

/**
 * Les playlists du CDN des VODs listent les passages coupés (droits d'auteur)
 * en « N-unmuted.ts », que le CDN refuse (403) : la lecture s'arrêtait au
 * premier. Seul « N-muted.ts » (son coupé) se lit : chaque playlist .m3u8 est
 * lue en entier puis réécrite. Seulement « -unmuted.ts » : en fMP4,
 * « -unmuted.mp4 » est servi avec le son.
 */
@OptIn(UnstableApi::class)
private class PlaylistRewritingDataSource(private val upstream: DataSource) : DataSource {
    private var buffer: ByteArray? = null
    private var position = 0
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: ""
        if (!path.endsWith(".m3u8") || dataSpec.position != 0L) {
            buffer = null
            return upstream.open(dataSpec)
        }
        val out = ByteArrayOutputStream()
        try {
            upstream.open(dataSpec)
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = upstream.read(chunk, 0, chunk.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, n)
            }
        } finally {
            upstream.close()
        }
        val text = out.toString("UTF-8")
        val bytes = if (text.contains("-unmuted.ts")) text.replace("-unmuted.ts", "-muted.ts").toByteArray(Charsets.UTF_8)
                    else out.toByteArray()
        buffer = bytes
        position = 0
        return bytes.size.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val b = buffer ?: return upstream.read(target, offset, length)
        if (length == 0) return 0
        if (position >= b.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, b.size - position)
        System.arraycopy(b, position, target, offset, n)
        position += n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (buffer == null) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (buffer != null) buffer = null else upstream.close()
    }
}
