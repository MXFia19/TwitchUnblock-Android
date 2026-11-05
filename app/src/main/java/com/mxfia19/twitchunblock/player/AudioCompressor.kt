package com.mxfia19.twitchunblock.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.mxfia19.twitchunblock.core.PlayerPrefs
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Compresseur audio, comme celui du site (DynamicsCompressorNode réglé comme
 * FrankerFaceZ : seuil −50 dB, genou 40 dB, rapport 12, attaque immédiate,
 * relâche 0,25 s, gain de rattrapage automatique) : les pics de son rabotés,
 * les passages calmes remontés. Dans la sortie son de chaque lecteur
 * (PlayerFactory), directs et VODs ; coupé, le son passe tel quel.
 */
@OptIn(UnstableApi::class)
object AudioCompressor {
    /** Lu par le fil audio des lecteurs ; posé par PlayerPrefs.setCompressor. */
    @Volatile
    var enabled = false

    /** Renderers d'ExoPlayer (ceux par défaut) avec le compresseur dans la sortie son. */
    fun renderers(context: Context): RenderersFactory {
        enabled = PlayerPrefs.audioCompressor.value
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(CompressorProcessor()))
                    .build()
        }
    }
}

/**
 * Compresseur à anticipation de 5 ms (la baisse de gain arrive avant l'attaque
 * d'un son fort, comme le DynamicsCompressorNode), canaux liés (même gain pour
 * tous). PCM 16 bits ou flottant ; tout autre format passe sans lui.
 */
@OptIn(UnstableApi::class)
private class CompressorProcessor : BaseAudioProcessor() {
    private var channels = 0
    private var isFloat = false
    /** Gain appliqué (dB, rattrapage compris), lissé. */
    private var gainDb = 0f
    private var attack = 0f
    private var release = 0f
    private var delay = FloatArray(0)
    private var delayFrames = 1
    private var delayPos = 0
    private var frame = FloatArray(0)
    /** Compresseur en marche (ligne à retard comprise) depuis le dernier vidage. */
    private var running = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val f = inputAudioFormat
        channels = maxOf(1, f.channelCount)
        isFloat = f.encoding == C.ENCODING_PCM_FLOAT
        val rate = if (f.sampleRate > 0) f.sampleRate.toFloat() else 48_000f
        attack = exp(-1f / (0.001f * rate))
        release = exp(-1f / (0.25f * rate))
        delayFrames = maxOf(1, (0.005f * rate).toInt())
        delay = FloatArray(delayFrames * channels)
        delayPos = 0
        frame = FloatArray(channels)
        gainDb = 0f
        running = false
    }

    override fun onReset() {
        delay = FloatArray(0)
        frame = FloatArray(0)
        running = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val on = AudioCompressor.enabled
        // Mis en marche : la ligne à retard repart de zéro (5 ms de silence).
        // Coupé, il reste en marche jusqu'au prochain vidage (saut, autre
        // qualité) en ramenant son gain à 0 dB : pas de son sauté.
        if (on && !running && channels > 0) {
            running = true
            delay.fill(0f)
            delayPos = 0
            gainDb = 0f
        }
        if (!running) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val n = channels
        val frames = size / (n * if (isFloat) 4 else 2)
        repeat(frames) {
            var peak = 0f
            for (c in 0 until n) {
                val s = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                frame[c] = s
                val a = abs(s)
                if (a > peak) peak = a
            }
            val target = if (on) targetGain(peak) else 0f
            gainDb = if (target < gainDb) target + (gainDb - target) * attack
                     else target + (gainDb - target) * release
            val g = if (abs(gainDb) < 0.01f) 1f else 10f.pow(gainDb / 20f)
            val base = delayPos * n
            for (c in 0 until n) {
                val v = (delay[base + c] * g).coerceIn(-1f, 1f)
                delay[base + c] = frame[c]
                if (isFloat) out.putFloat(v) else out.putShort((v * 32767f).toInt().toShort())
            }
            delayPos = if (delayPos + 1 == delayFrames) 0 else delayPos + 1
        }
        // Reste d'une trame incomplète (ne devrait pas arriver) : laissé de côté.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    companion object {
        private const val THRESHOLD = -50f
        private const val KNEE = 40f
        private const val RATIO = 12f

        /** Niveau de sortie (dB) d'un niveau d'entrée : 1:1 sous le seuil,
         *  genou progressif sur 40 dB au-dessus, puis 1:12. */
        private fun curve(x: Float): Float = when {
            x <= THRESHOLD -> x
            x < THRESHOLD + KNEE -> x + (1f / RATIO - 1f) * (x - THRESHOLD) * (x - THRESHOLD) / (2f * KNEE)
            else -> THRESHOLD + KNEE / 2f + KNEE / (2f * RATIO) + (x - THRESHOLD - KNEE) / RATIO
        }

        /** Rattrapage automatique du DynamicsCompressorNode : 0,6 × la
         *  réduction subie à pleine échelle (+16,5 dB ici). */
        private val MAKEUP = -0.6f * curve(0f)

        /** Gain voulu (dB) pour un niveau crête (0 à 1). */
        fun targetGain(peak: Float): Float {
            val x = if (peak > 1e-6f) 20f * log10(peak) else -120f
            return curve(x) - x + MAKEUP
        }
    }
}
