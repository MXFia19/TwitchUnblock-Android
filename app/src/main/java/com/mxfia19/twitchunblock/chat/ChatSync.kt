package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Synchro du chat avec la vidéo, comme ChatSync.swift. Les messages de l'IRC
 * arrivent presque en temps réel, l'image du direct avec plusieurs secondes
 * de retard : sans rien faire, le chat commente ce qu'on n'a pas encore vu.
 * ChatClient retient donc chaque message reçu jusqu'à son heure d'arrivée +
 * le retard du chat.
 *
 * Mesure : notre retard sur le direct tel que le voient les autres
 * spectateurs (LiveSyncEstimator), donnée par le lecteur principal
 * (ChatSyncFeeder). Retard du chat = cette mesure − le temps de réaction,
 * + la retouche des réglages, entre 0 et 60 s. Aucune mesure, aucun retard :
 * VODs (leur chat suit déjà la position), multi-live, TV.
 */
object ChatSync {
    /** Temps de réaction des autres spectateurs (s) : ils écrivent un instant
     *  après avoir vu l'image ; retiré, leurs réactions tombent avec elle. */
    private const val REACTION_MARGIN = 2.0
    private const val MAX_DELAY = 60.0
    /** Mesures attendues toutes les 0,5 s : sans nouvelle depuis 1,5 s,
     *  l'image est figée (calage) et son retard grandit avec l'heure… */
    private const val STALE_AFTER = 1.5
    /** … pendant 15 s au plus. Au-delà (pause, échec), retard inconnu. */
    private const val MAX_HOLD = 15.0

    private var measured: Double? = null
    private var measuredAt = 0L
    /** Mesure arrondie à la seconde, pour la pastille du chat : elle ne se
     *  redessine qu'au changement de seconde. */
    var shownSeconds by mutableStateOf<Int?>(null)
        private set
    private var lastLogged: Double? = null

    /** Mesure du lecteur principal (s), à chaque tick d'un direct. */
    fun update(behind: Double) {
        if (!behind.isFinite()) return
        val value = behind.coerceIn(0.0, 3600.0)
        measured = value
        measuredAt = System.currentTimeMillis()
        val seconds = value.roundToInt()
        if (seconds != shownSeconds) shownSeconds = seconds
        // Journal : les vrais changements (calage, retour au direct), pas le bruit.
        val logged = lastLogged
        if (logged == null || abs(logged - value) >= 3) {
            lastLogged = value
            Logger.debug("CHAT", "Synchro du chat", "${value.roundToInt()} s de retard")
        }
    }

    /** Plus de direct à suivre : VOD, chargement, lecteur fermé. */
    fun reset() {
        if (measured == null && shownSeconds == null) return
        measured = null
        shownSeconds = null
        lastLogged = null
    }

    /** Retard de l'image à l'instant `now` (ms) ; null s'il est inconnu. */
    fun playerDelay(now: Long): Double? {
        val m = measured ?: return null
        val frozen = (now - measuredAt) / 1000.0 - STALE_AFTER
        if (frozen <= 0) return m
        return if (frozen > MAX_HOLD) null else m + frozen
    }

    /** Retard du chat (s) pour un retard de l'image et la retouche des réglages. */
    fun chatDelay(player: Double, nudge: Double): Double = (player - REACTION_MARGIN + nudge).coerceIn(0.0, MAX_DELAY)
}

/**
 * Notre retard sur le direct tel que le voient les autres spectateurs, comme
 * LiveSyncEstimator (iOS). La distance au bord de la fenêtre du direct ne
 * suffit pas : entre deux relectures de la liste de lecture, le bord connu
 * du lecteur vieillit. On part donc de la latence totale (heure − horodatage
 * du segment lu, EXT-X-PROGRAM-DATE-TIME), qui reste lisse, et on en retire
 * l'âge du bord le plus frais vu dans la dernière minute. Le délai qu'une
 * chaîne ajoute à sa diffusion, subi par tout le monde, s'annule ainsi.
 */
class LiveSyncEstimator {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun reset() = samples.clear()

    /** `latency` : heure − horodatage du segment lu (null sans horodatage) ;
     *  `behind` : secondes entre la position et la fin de la fenêtre. */
    fun offset(latency: Double?, behind: Double): Double {
        if (!behind.isFinite()) return 0.0
        if (latency == null || !latency.isFinite() || latency <= 0) return max(0.0, behind)
        val now = System.currentTimeMillis()
        // Âge du bord connu du lecteur ; valeurs absurdes (pub, horloge) écartées.
        val edge = latency - behind
        if (edge > -5 && edge < 120) samples.addLast(now to edge)
        while (samples.isNotEmpty() && now - samples.first().first > 60_000) samples.removeFirst()
        // Pas encore assez de recul : la distance au bord, en attendant.
        if (samples.size < 6) return max(0.0, behind)
        // Bas de la fourchette plutôt que le minimum : une mesure aberrante
        // décalerait sinon le chat pendant une minute.
        val sorted = samples.map { it.second }.sorted()
        val freshest = sorted[sorted.size / 10]
        return max(0.0, latency - freshest)
    }
}
