package com.mxfia19.twitchunblock.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NetworkStatus
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mode faible latence côté lecteur (série 6), comme LiveLatency.swift.
 * Sans lui, ExoPlayer part à trois durées cibles du bord (~18 s chez Twitch,
 * sans HOLD-BACK) et ne le tient pas : vitesse fixe. On tient le bord du direct
 * seulement par des sauts, jamais par la vitesse (son décalé de l'image sur
 * l'iPhone) :
 *  • démarrage loin du bord → un seul recalage, dès que la lecture part ;
 *  • sortie de la fenêtre du direct → saut près du bord, même après un recul ;
 *  • dérive durable (coupure, pause longue) → saut près du bord si le retard
 *    dépasse la cible de 5 s pendant 4 s, au plus une fois toutes les 30 s ;
 *  • jamais après un recul voulu (barre, −10 s).
 * Cible : trois durées cibles − 2 s, entre 3,5 et 8 s du bord jouable, jamais
 * sous le plancher : le retard affiché (heure − horodatage de l'image) ne
 * passe pas sous 4 s (plus près, la lecture cale). Chaque calage recule la
 * cible de 4 s (16 au plus, rendues 2 s par 2 s après 2 min sans calage), et
 * 4 s de plus en données mobiles, dont le débit varie.
 */
object LiveLatency {
    /** Retard affiché minimal (ms). */
    private const val MIN_DELAY_MS = 4_000L

    /** Avance gagnée après des calages (ms) : gardée d'une source à l'autre,
     *  le débit, lui, n'a pas changé. */
    private var stallMargin = 0L
    private var lastStallAt = 0L

    /** Contrôleur du direct en cours : « Direct » y ramène à la cible. */
    var active: Controller? = null
        private set

    /** État du direct pour la pastille « Direct · −45 s » : retard sur la
     *  position normale (cible ou point par défaut), retard affiché. */
    class Status(val lateMs: Long, val totalMs: Long?, val playing: Boolean)

    /** Tient le bord du direct tant que la coroutine vit (lecture principale). */
    suspend fun run(player: Player) {
        val ctl = Controller(player)
        player.addListener(ctl)
        active = ctl
        try {
            while (true) {
                ctl.tick()
                delay(1_000)
            }
        } finally {
            player.removeListener(ctl)
            if (active === ctl) active = null
        }
    }

    /** Retour au direct : à la cible du mode faible latence s'il tient ce
     *  lecteur, sinon au point par défaut d'ExoPlayer ; sans recharger. */
    fun goLive(player: Player) {
        val ctl = active?.takeIf { it.player === player }
        if (ctl == null || !ctl.goLive()) player.seekToDefaultPosition()
        player.play()
    }

    fun status(player: Player): Status? {
        if (!player.isCurrentMediaItemLive) return null
        val w = window(player) ?: return null
        val dur = w.durationMs
        if (dur == C.TIME_UNSET || dur <= 0) return null
        val behind = dur - player.currentPosition
        val normal = active?.takeIf { it.player === player }?.liveBehind()
            ?: w.defaultPositionMs.takeIf { it != C.TIME_UNSET }?.let { dur - it }
            ?: return null
        val total = player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it > 0 }
        return Status(behind - normal, total, player.playWhenReady)
    }

    private fun window(player: Player): Timeline.Window? {
        val t = player.currentTimeline
        if (t.isEmpty) return null
        return t.getWindow(player.currentMediaItemIndex, Timeline.Window())
    }

    private fun noteStall() {
        stallMargin = min(16_000L, stallMargin + 4_000L)
        lastStallAt = SystemClock.elapsedRealtime()
        Logger.info("LIVE", "Calage du direct : marge sur le bord", "${stallMargin / 1000} s")
    }

    /** Plus de calage depuis 2 min : l'avance gagnée se rend peu à peu. */
    private fun decay(now: Long) {
        if (stallMargin > 0 && now - lastStallAt > 120_000) {
            stallMargin = max(0L, stallMargin - 2_000L)
            lastStallAt = now
        }
    }

    @OptIn(UnstableApi::class)
    class Controller(val player: Player) : Player.Listener {
        /** Recalage du démarrage fait (ou inutile) : une fois par source. */
        private var aligned = false
        /** Recul voulu (barre, −10 s) : la dérive ne ramène plus au bord. */
        private var holding = false
        private var driftSince = 0L
        private var lastJump = 0L
        private var lastFloorSeek = 0L
        /** Dernier saut fait ici : pas pris pour un recul de la personne. */
        private var ownSeekAt = 0L
        private var seekAt = 0L
        /** Recul à garder pour tenir le plancher du retard affiché (ms). */
        private var floorBehind = 0L
        private var playingSince = 0L
        private var wasReady = false

        private fun now() = SystemClock.elapsedRealtime()

        /** Recul visé sur le bord jouable (ms), avec les marges ; jamais plus
         *  que la fenêtre du direct moins 8 s. */
        fun target(): Long? {
            val w = window(player) ?: return null
            val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
            val duration = playlist?.targetDurationUs?.takeIf { it > 0 }?.div(1000) ?: 6_000L
            val holdBack = playlist?.serverControl?.holdBackUs?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000)
            val recommended = holdBack ?: (3 * duration)
            val base = max((recommended - 2_000L).coerceIn(3_500L, 8_000L), floorBehind)
            val wanted = base + stallMargin + if (NetworkStatus.isCellular) 4_000L else 0L
            val dur = w.durationMs
            return if (dur != C.TIME_UNSET && dur - 8_000L > base) min(wanted, dur - 8_000L) else wanted
        }

        /** Recul où « Direct » et les recalages posent la lecture (ms). */
        fun liveBehind(): Long? = target()?.plus(1_000L)

        fun goLive(): Boolean {
            val behind = liveBehind() ?: return false
            holding = false
            return jump(behind, "Retour au direct")
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            seekAt = now()
            driftSince = 0L
            if (seekAt - ownSeekAt < 1_500) return
            val w = window(player) ?: return
            val pos = player.currentPosition
            val def = w.defaultPositionMs
            if (def != C.TIME_UNSET && abs(pos - def) < 1_500) {
                // Point par défaut (« Direct » de la barre, reprise après une
                // erreur, rattrapage fini) : recalé au plus près du bord.
                holding = false
                aligned = false
            } else {
                val dur = w.durationMs
                val t = target() ?: return
                holding = dur != C.TIME_UNSET && dur - pos > t + 3_000
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Calage en pleine lecture (pas juste après un saut) : plus de marge.
            if (playbackState == Player.STATE_BUFFERING && wasReady && player.playWhenReady && now() - seekAt > 2_000) {
                noteStall()
                driftSince = 0L
            }
            wasReady = playbackState == Player.STATE_READY
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Autre source (qualité, image verticale) : recalage à refaire.
            aligned = false
            floorBehind = 0L
            playingSince = 0L
        }

        /** Une fois par seconde, lecture principale d'un direct. */
        fun tick() {
            if (!player.isPlaying || !player.isCurrentMediaItemLive) {
                driftSince = 0L
                return
            }
            val clock = now()
            if (playingSince == 0L) playingSince = clock
            // Le flux a eu le temps de démarrer : seul le recalage du démarrage s'en passe.
            val settled = clock - playingSince >= 3_000
            decay(clock)
            val w = window(player) ?: return
            val dur = w.durationMs
            if (dur == C.TIME_UNSET || dur <= 0) return
            val pos = player.currentPosition
            val behind = dur - pos

            // 1. Plancher. Une horloge en retard donne un retard affiché
            //    inférieur à la distance au bord : le calcul n'a plus de sens.
            val total = player.currentLiveOffset
            if (total != C.TIME_UNSET && total > 0 && total >= behind - 500) {
                // Délai de publication de Twitch = retard affiché − distance au bord.
                val publish = max(0L, total - behind)
                floorBehind = (MIN_DELAY_MS + 500 - publish).coerceIn(0L, 10_000L)
                if (settled && total < MIN_DELAY_MS - 200 && clock - lastFloorSeek > 8_000) {
                    lastFloorSeek = clock
                    ownSeekAt = clock
                    lastJump = clock
                    driftSince = 0L
                    player.seekTo(max(0L, pos - (MIN_DELAY_MS + 500 - total)))
                    Logger.info("LIVE", "Plancher du direct", seconds(total) + " de retard")
                    return
                }
            }

            val target = target() ?: return
            // 2. Recalage du démarrage, une seule fois, dès que la lecture part ;
            //    un recul déjà posé (qualité changée en retard) n'est pas défait.
            if (!aligned) {
                aligned = true
                if (!holding && behind > target + 3_000) {
                    jump(target + 1_000, "Recalage du direct")
                    return
                }
            }
            if (!settled) return

            // 3. Sortie de la fenêtre du direct : les segments vont disparaître.
            if (dur > target + 5_000 && behind > dur - 4_000) {
                holding = false
                jump(target + 1_000, "Sortie de la fenêtre du direct")
                return
            }

            // 4. Dérive durable, sauf après un recul voulu.
            if (holding || behind <= target + 5_000) {
                driftSince = 0L
                return
            }
            if (driftSince == 0L) driftSince = clock
            if (clock - driftSince >= 4_000 && clock - lastJump > 30_000) jump(target + 1_000, "Dérive du direct")
        }

        /** Saut à `behindMs` du bord jouable. */
        private fun jump(behindMs: Long, reason: String): Boolean {
            val w = window(player) ?: return false
            val dur = w.durationMs
            if (dur == C.TIME_UNSET || dur <= 0) return false
            val from = dur - player.currentPosition
            ownSeekAt = now()
            lastJump = ownSeekAt
            driftSince = 0L
            player.seekTo(max(0L, dur - behindMs))
            Logger.info("LIVE", reason, seconds(from) + " du bord → " + seconds(behindMs))
            return true
        }

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)
    }
}
