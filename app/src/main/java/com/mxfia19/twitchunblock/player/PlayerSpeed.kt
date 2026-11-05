package com.mxfia19.twitchunblock.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Lang
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.PlayerPrefs
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vitesse de la lecture principale du téléphone, comme l'iPhone : 0,25× à 2×
 * sur les VODs (retenue d'une VOD à l'autre) et les clips (repartent à 1×).
 * Un direct joue à 1×, sauf pour rattraper le bord après un recul voulu
 * (double-tap, barre) : la vitesse retenue jusqu'au bord, puis 1×.
 * L'enregistrement d'un direct en cours lu en accéléré repasse à 1× près de
 * son bord, et retrouve sa vitesse une fois la lecture reculée.
 * Ni la TV ni le multi-live : seul PlayerAddons (lecteur du téléphone) l'appelle.
 */
object PlayerSpeed {
    /** Même liste que l'iPhone et le site ; 2× au plus. */
    val all = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private enum class Kind { LIVE, VOD, CLIP }

    private var kind = Kind.LIVE

    /** Vitesse telle qu'elle joue (affichée avec les commandes). */
    var current by mutableStateOf(1f)
        private set

    /** Direct reculé en train d'être rattrapé en accéléré. */
    var catchingUp by mutableStateOf(false)
        private set

    private var clipSpeed = 1f
    /** Bord d'un enregistrement en cours rattrapé : 1× jusqu'à ce que la lecture s'en éloigne. */
    private var edgeHeld = false
    /** Direct reculé exprès : le bord est à rattraper. */
    private var liveHeld = false
    /** Lecture à laquelle la vitesse a été posée : l'écran du lecteur recomposé
     *  (retour d'une fiche chaîne) ne la remet pas à zéro. */
    private var lastRequest: PlayRequest? = null
    private var lastPlayer: WeakReference<Player>? = null
    /** Lecture dont la vitesse a été annoncée au lancement. */
    private var announced: PlayRequest? = null

    /** Vitesse retenue des VODs (réglage), aussi celle du rattrapage d'un direct. */
    val vodSpeed: Float get() = nearest(PlayerPrefs.vodSpeed.value / 100f)

    fun nearest(v: Float): Float = if (!v.isFinite() || v <= 0f) 1f else all.minBy { abs(it - v) }

    /** « 1,5× » : virgule en français, espagnol, russe et italien ; point en
     *  anglais, en japonais et en chinois. */
    fun label(v: Float): String {
        val s = if (v % 1f == 0f) v.toInt().toString() else v.toString()
        val dot = AppState.lang == Lang.EN || AppState.lang == Lang.JA || AppState.lang == Lang.ZH_HANT
        return (if (dot) s else s.replace('.', ',')) + "×"
    }

    /** Nom dans le menu : « Normale » pour 1×. */
    fun name(v: Float): String = if (v == 1f) AppState.t("speed_normal") else label(v)

    /** « Vitesse 1,5× », annoncé au changement. */
    fun flashText(v: Float): String = AppState.t("speed") + " " + name(v)

    /** Vitesses du menu : en direct, de 1× à 2× (rattrapage). */
    fun choices(live: Boolean): List<Float> = if (live) all.filter { it >= 1f } else all

    /** Vitesse cochée dans le menu. */
    val selected: Float
        get() = when (kind) {
            Kind.CLIP -> clipSpeed
            Kind.LIVE -> vodSpeed
            Kind.VOD -> if (edgeHeld) 1f else vodSpeed
        }

    /** Nouvelle lecture (ou la même, l'écran recomposé) : la vitesse qui lui revient. */
    fun onOpen(player: Player, req: PlayRequest) {
        if (req === lastRequest && player === lastPlayer?.get()) {
            apply(player, wanted())
            return
        }
        lastRequest = req
        lastPlayer = WeakReference(player)
        kind = when (req) {
            is PlayRequest.Live -> Kind.LIVE
            is PlayRequest.Clip -> Kind.CLIP
            else -> Kind.VOD
        }
        clipSpeed = 1f
        edgeHeld = false
        liveHeld = false
        catchingUp = false
        apply(player, wanted())
    }

    /** Lecture lancée (image là) à une autre vitesse que 1× : texte à annoncer,
     *  une fois par lecture ; null sinon. Comme l'iPhone. */
    fun launchNotice(req: PlayRequest): String? {
        if (req === announced || req is PlayRequest.Live || current == 1f) return null
        announced = req
        return flashText(current)
    }

    private fun wanted(): Float = when (kind) {
        Kind.LIVE -> if (catchingUp) vodSpeed else 1f
        Kind.CLIP -> clipSpeed
        Kind.VOD -> if (edgeHeld) 1f else vodSpeed
    }

    private fun apply(player: Player, v: Float) {
        current = v
        if (player.playbackParameters.speed != v) player.setPlaybackSpeed(v)
    }

    /** Vitesse choisie dans le menu ⋮ ; vrai si elle s'applique tout de suite
     *  (à annoncer) : en direct, seulement pendant un rattrapage. */
    fun choose(player: Player, v: Float): Boolean {
        val s = nearest(v)
        if (kind == Kind.CLIP) {
            clipSpeed = s
        } else {
            edgeHeld = false
            if (s != vodSpeed) {
                PlayerPrefs.vodSpeed.set((s * 100).roundToInt())
                Logger.info("PLAYER", "Vitesse des VODs", "×$s")
            }
        }
        // Direct : ramenée à 1×, le rattrapage s'arrête là.
        if (kind == Kind.LIVE && catchingUp && s <= 1f) catchingUp = false
        apply(player, wanted())
        return kind != Kind.LIVE || catchingUp
    }

    /** Saut dans la lecture (barre, double-tap, retour au bord) : un direct
     *  reculé de plus de 3 s l'a été exprès. */
    fun onSeek(player: Player) {
        if (kind != Kind.LIVE) return
        val behind = liveBehindMs(player) ?: return
        liveHeld = behind > 3_000
        // Revenu près du bord : plus rien à rattraper.
        if (!liveHeld && catchingUp) {
            catchingUp = false
            apply(player, 1f)
        }
    }

    /** Toutes les 0,5 s (PlayerAddons) : rattrapage d'un direct, bord d'un
     *  enregistrement en cours. Renvoie le texte à annoncer, sinon null. */
    fun tick(player: Player): String? {
        when (kind) {
            Kind.LIVE -> {
                val behind = liveBehindMs(player)
                if (catchingUp) {
                    if (behind != null && behind <= 1_500) {
                        catchingUp = false
                        liveHeld = false
                        apply(player, 1f)
                        // Retard normal du direct repris : celui du recul n'est
                        // plus tenu par le lecteur.
                        player.seekToDefaultPosition()
                        Logger.info("PLAYER", "Direct rattrapé : vitesse normale")
                        return AppState.t("speed_caught_up")
                    }
                } else if (liveHeld && vodSpeed > 1f && player.isPlaying && behind != null && behind > 6_000) {
                    catchingUp = true
                    apply(player, vodSpeed)
                    Logger.info("PLAYER", "Direct reculé : rattrapage en accéléré", "×$vodSpeed")
                    return flashText(vodSpeed)
                }
            }
            Kind.VOD -> {
                // Enregistrement d'un direct en cours (playlist sans fin) : en
                // accéléré, on finit par rattraper son bord.
                val d = player.duration
                val growing = player.isCurrentMediaItemDynamic && d != C.TIME_UNSET && d > 0
                val left = if (growing) d - player.currentPosition else Long.MAX_VALUE
                if (!edgeHeld && growing && vodSpeed > 1f && left < 20_000) {
                    edgeHeld = true
                    apply(player, 1f)
                    Logger.info("PLAYER", "Bord de l'enregistrement rattrapé : vitesse normale")
                    return AppState.t("speed_caught_up")
                }
                // Écart avec le seuil d'au-dessus : pas d'oscillation près du bord.
                if (edgeHeld && left > 45_000) {
                    edgeHeld = false
                    apply(player, vodSpeed)
                    return flashText(vodSpeed)
                }
            }
            Kind.CLIP -> {}
        }
        return null
    }

    /** Retard sur le bord du direct au-delà du retard normal (ms) ; null hors direct. */
    fun liveBehindMs(player: Player): Long? {
        if (!player.isCurrentMediaItemLive) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val w = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        val target = w.liveConfiguration?.targetOffsetMs ?: C.TIME_UNSET
        val offset = player.currentLiveOffset
        if (offset != C.TIME_UNSET && target != C.TIME_UNSET) return offset - target
        val edge = w.defaultPositionMs
        return if (edge == C.TIME_UNSET) null else edge - player.currentPosition
    }

    /** Bord du direct dans la fenêtre (ms), là où le lecteur le tient ; null hors direct. */
    fun liveEdgeMs(player: Player): Long? {
        if (!player.isCurrentMediaItemLive) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val edge = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).defaultPositionMs
        return if (edge == C.TIME_UNSET) null else edge
    }
}
