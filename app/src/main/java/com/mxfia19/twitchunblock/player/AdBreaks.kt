package com.mxfia19.twitchunblock.player

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
//  Pauses pub cousues par Twitch dans le direct (comme AdBreakMonitor, iOS).
//
//  Pendant une pause pub de la chaîne (et au lancement : pré-roll), Twitch
//  remplace le direct, dans la playlist elle-même, par un encart violet
//  « Commercial break in progress » ou par une vraie pub. Aucune source n'y
//  échappe ; seul un compte sans pub sur la chaîne n'en reçoit pas.
//
//  La playlist l'annonce : #EXT-X-DATERANGE CLASS="twitch-stitched-ad", avec
//  START-DATE et DURATION. ExoPlayer garde toutes les balises de la playlist
//  (HlsMediaPlaylist.tags) ; l'heure de l'image affichée vient de
//  EXT-X-PROGRAM-DATE-TIME (début de la fenêtre + position). La playlist a
//  jusqu'à une vingtaine de secondes d'avance sur l'image : se caler sur sa
//  lecture annoncerait la pause trop tôt.
//
//  Les autres classes sont ignorées, `twitch-maf-ad` en tête : une pub que
//  dessine le lecteur de Twitch, sans rien changer à l'image.
// ═══════════════════════════════════════════════════════════════════════════

/** Une pause pub annoncée par la playlist (ms depuis 1970). */
data class AdBreak(val id: String, val start: Long, val end: Long)

/** Heure (ms depuis 1970) de l'image affichée d'un direct, null sans
 *  EXT-X-PROGRAM-DATE-TIME. */
fun displayedTimeMs(player: Player, window: Timeline.Window = Timeline.Window()): Long? {
    val tl = player.currentTimeline
    val index = player.currentMediaItemIndex
    if (tl.isEmpty || index >= tl.windowCount) return null
    val start = tl.getWindow(index, window).windowStartTimeMs
    if (start == C.TIME_UNSET) return null
    return start + player.currentPosition
}

/**
 * Pause pub à l'écran d'un lecteur : secondes restantes (null hors pause),
 * et le son coupé le temps qu'elle dure (`managesAudio`, lecture principale :
 * le multi-live gère le sien) — seulement s'il ne l'était pas déjà, rendu à
 * la fin, au changement d'élément (qualité, source) et au plus tard 5 s après
 * la fin prévue. Relu toutes les 500 ms dans `scope` (celui de la session).
 */
@OptIn(UnstableApi::class)
class AdBreakWatcher(private val player: Player, scope: CoroutineScope, private val managesAudio: Boolean) {
    /** Secondes avant le retour du direct, null hors pause. */
    var remaining by mutableStateOf<Int?>(null)
        private set
    /** Son coupé par nous (bouton « Activer le son » du bandeau). */
    var mutedByUs by mutableStateOf(false)
        private set

    private val breaks = HashMap<String, AdBreak>()
    private val window = Timeline.Window()
    private var lastPlaylist: Any? = null
    private var lastItem: MediaItem? = null
    private var savedVolume = 1f
    /** Pause où le son a été rendu (par la personne, ou le minuteur de
     *  secours) : on ne le recoupe pas. */
    private var spared: String? = null
    private var currentId: String? = null
    private var currentEnd: Long? = null
    /** Minuteur de secours : fin prévue + 5 s, en temps réel. */
    private var deadline: Long? = null

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    /** Le son rendu par la personne pendant cette pause. */
    fun unmute() {
        restore()
        spared = currentId
        Logger.info("PUB", "Son remis pendant la pause pub")
    }

    private fun tick() {
        // Nouvel élément (qualité, source, autre direct) : le son d'abord, et
        // les pauses d'une autre chaîne oubliées (même heure, autre flux).
        val item = player.currentMediaItem
        if (item !== lastItem) {
            restore()
            breaks.clear()
            lastItem = item
            lastPlaylist = null
        }
        val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        if (playlist != null && playlist !== lastPlaylist) {
            lastPlaylist = playlist
            collect(playlist.tags)
        }
        val now = displayedTimeMs(player, window)
        val brk = now?.let { active(it) }
        if (now == null || brk == null) {
            finish()
            return
        }
        val left = maxOf(0L, (brk.end - now + 999) / 1000).toInt()
        if (remaining != left) remaining = left
        if (currentId != brk.id || currentEnd != brk.end) {
            if (currentId != brk.id) Logger.info("PUB", "Pause pub à l'écran", "$left s restantes")
            currentId = brk.id
            // Bloc prolongé par une pub annoncée après coup : secours repoussé.
            currentEnd = brk.end
            deadline = System.currentTimeMillis() + (brk.end - now) + 5_000
        }
        if (!managesAudio) return
        val limit = deadline
        if (limit != null && System.currentTimeMillis() > limit) {
            // Plus long que prévu (lecture figée, durée fausse) : le son revient.
            if (mutedByUs) {
                restore()
                spared = brk.id
            }
            return
        }
        if (mutedByUs) {
            // Son remis par quelqu'un d'autre : on ne le recoupe pas.
            if (player.volume > 0f) {
                mutedByUs = false
                spared = brk.id
            }
        } else if (player.volume > 0f && spared != brk.id) {
            savedVolume = player.volume
            player.volume = 0f
            mutedByUs = true
        }
    }

    /** Pauses de la playlist ; une pub annoncée deux fois garde la dernière version. */
    private fun collect(tags: List<String>) {
        for (line in tags) {
            if (!line.startsWith("#EXT-X-DATERANGE") || !line.contains("CLASS=\"$AD_CLASS\"")) continue
            val start = isoMs(attribute(line, "START-DATE")) ?: continue
            // Sans DURATION ni END-DATE (rare) : 30 s ; 200 s au plus (Twitch
            // plafonne une pause à 3 min) : une durée absurde ne coupe pas le son pour de bon.
            val end = minOf(
                isoMs(attribute(line, "END-DATE"))
                    ?: attribute(line, "DURATION")?.toDoubleOrNull()?.let { start + (it * 1000).toLong() }
                    ?: (start + 30_000),
                start + MAX_LENGTH_MS,
            )
            if (end <= start) continue
            val id = attribute(line, "ID") ?: start.toString()
            if (breaks[id] == null) Logger.info("PUB", "Pause pub annoncée par la playlist", "${(end - start) / 1000} s")
            breaks[id] = AdBreak(id, start, end)
        }
        // Les plus anciennes ne servent plus (un direct peut durer des heures).
        val old = System.currentTimeMillis() - 3_600_000
        breaks.entries.removeAll { it.value.end < old }
    }

    /** Pause à l'écran à l'heure `at` : le bloc entier des pubs qui se suivent
     *  sans trou, sous l'identifiant de sa première pub (le son remis par la
     *  personne n'est pas recoupé à la pub suivante). */
    private fun active(at: Long): AdBreak? {
        var block: AdBreak? = null
        for (next in breaks.values.sortedWith(compareBy<AdBreak>({ it.start }, { it.id }))) {
            val b = block
            if (b != null && next.start <= b.end + 1000) {
                if (next.end > b.end) block = AdBreak(b.id, b.start, next.end)
                continue
            }
            if (b != null && b.start <= at && at < b.end) return b
            if (next.start > at) return null
            block = next
        }
        val b = block
        return if (b != null && b.start <= at && at < b.end) b else null
    }

    /** Hors pause : le son rendu, le bandeau retiré. */
    private fun finish() {
        restore()
        if (remaining != null) {
            remaining = null
            Logger.info("PUB", "Fin de la pause pub")
        }
        currentId = null
        currentEnd = null
        deadline = null
        spared = null
    }

    private fun restore() {
        if (!mutedByUs) return
        if (player.volume == 0f) player.volume = savedVolume
        mutedByUs = false
    }

    companion object {
        const val AD_CLASS = "twitch-stitched-ad"
        private const val MAX_LENGTH_MS = 200_000L

        /** Valeur d'une balise (`,NOM="valeur"` ou `,NOM=valeur`) : précédé d'une
         *  virgule ou des deux-points, « DURATION » ne prend pas
         *  « X-TV-TWITCH-AD-POD-FILLED-DURATION ». */
        fun attribute(line: String, name: String): String? {
            val m = Regex("[:,]" + Regex.escape(name) + "=(\"[^\"]*\"|[^,\\s]*)").find(line) ?: return null
            return m.groupValues[1].removeSurrounding("\"")
        }

        /** Date ISO 8601 (« 2026-11-05T12:42:06.732Z », décalage accepté) en ms. */
        fun isoMs(s: String?): Long? {
            if (s == null) return null
            val base = Format.parseDate(s) ?: return null
            var i = 19
            var ms = 0L
            if (i < s.length && s[i] == '.') {
                val digits = s.drop(i + 1).takeWhile { it.isDigit() }
                ms = ("0.$digits".toDoubleOrNull() ?: 0.0).times(1000).toLong()
                i += 1 + digits.length
            }
            val zone = s.drop(i)
            val offset = if (zone.length >= 6 && (zone[0] == '+' || zone[0] == '-')) {
                val h = zone.substring(1, 3).toIntOrNull() ?: 0
                val m = zone.substring(4, 6).toIntOrNull() ?: 0
                (if (zone[0] == '+') 1 else -1) * (h * 3_600_000L + m * 60_000L)
            } else 0L
            return base + ms - offset
        }
    }
}
