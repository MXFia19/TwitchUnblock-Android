package com.mxfia19.twitchunblock.ui

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.PlayerPrefs
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.PlayerSpeed
import com.mxfia19.twitchunblock.player.VodMarkers
import com.mxfia19.twitchunblock.player.VodMarkersApi
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** État partagé du lecteur principal du téléphone (un seul à la fois) :
 *  bandeau fugace, commandes de Media3 affichées, instant visé sur la barre,
 *  fiches ouvertes, repères de la VOD lue. */
object PlayerUi {
    /** Bandeau au milieu de l'image (±10 s, vitesse, zoom) et sa fin (horloge système). */
    var flashText by mutableStateOf("")
        private set
    var flashUntil by mutableStateOf(0L)
        private set
    /** Commandes de Media3 affichées (GesturePlayerView). */
    var controlsVisible by mutableStateOf(false)
    /** Instant visé sur la barre pendant un glissé (ms), null sinon. */
    var scrubMs by mutableStateOf<Long?>(null)
    /** Position de lecture (ms), relevée toutes les 0,5 s (PlayerAddons). */
    var positionMs by mutableStateOf(0L)
    /** Fiche du titre en entier (toucher le titre). */
    var infoOpen by mutableStateOf(false)
    /** « Aller à une heure » d'une archive (menu ⋮). */
    var gotoOpen by mutableStateOf(false)
    /** Repères de la VOD lue : passages coupés, début de la diffusion. */
    var markers by mutableStateOf<VodMarkers?>(null)

    private var seekTotal = 0
    private var seekAt = 0L

    fun flash(text: String, ms: Long = 700) {
        flashText = text
        flashUntil = SystemClock.uptimeMillis() + ms
    }

    /** Sauts enchaînés additionnés (« +30 s » au troisième) ; un saut dans
     *  l'autre sens repart de zéro. */
    fun seekFlash(seconds: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - seekAt > 1_100 || (seekTotal < 0) != (seconds < 0)) seekTotal = 0
        seekTotal += seconds
        seekAt = now
        flash((if (seekTotal < 0) "−" else "+") + abs(seekTotal) + " s", 900)
    }
}

/** Orange des passages coupés (comme l'iPhone). */
private val MutedOrange = Color(0xFFFFA000)

/**
 * Ajouts du lecteur (série 5), appelé une fois par PlayerScreen : vitesse de
 * la lecture (et rattrapage d'un direct reculé), repères de la VOD, fiches du
 * titre et de « Aller à une heure ».
 */
@Composable
fun PlayerAddons(session: PlaybackSession, nav: Navigator) {
    val player = session.player
    val req = session.request
    LaunchedEffect(player, req) { if (req != null) PlayerSpeed.onOpen(player, req) }
    // VOD lancée à une autre vitesse que 1× : on le dit, une fois l'image là.
    val ready = session.state is PlaybackSession.State.Ready
    LaunchedEffect(req, ready) {
        if (ready && req != null) PlayerSpeed.launchNotice(req)?.let { PlayerUi.flash(it, 1_500) }
    }
    // Recul voulu dans un direct (barre, double-tap) : rattrapé à la vitesse retenue.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo,
                                                 reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) PlayerSpeed.onSeek(player)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            PlayerSpeed.onHidden(player)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            PlayerSpeed.tick(player)?.let { PlayerUi.flash(it, 1_500) }
            PlayerUi.positionMs = player.currentPosition
            delay(500)
        }
    }
    // Repères de la VOD lue (téléchargée comprise) ; rien pour un direct ou un clip.
    val vod = session.vodId
    LaunchedEffect(vod) {
        if (PlayerUi.markers?.id != vod) PlayerUi.markers = null
        if (vod != null && PlayerUi.markers == null) PlayerUi.markers = VodMarkersApi.load(vod)
    }
    if (PipState.active) return
    if (PlayerUi.infoOpen) InfoCard(session, nav)
    if (PlayerUi.gotoOpen) GotoTimeDialog(session)
}

/**
 * Par-dessus l'image du lecteur principal (VideoSurface) : titre en paysage
 * (il ouvre sa fiche), heure réelle d'une archive et vitesse, instant visé sur
 * la barre, passage au son coupé avec « Passer », bandeau fugace.
 */
@Composable
fun PlayerVideoOverlay(session: PlaybackSession) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ready = session.state is PlaybackSession.State.Ready
    val markers = PlayerUi.markers?.takeIf { it.id == session.vodId }
    val controls = PlayerUi.controlsVisible
    val scrub = PlayerUi.scrubMs
    val start = if (session.isLive) null else markers?.broadcastStart
    // Chat posé sur l'image (paysage) : rien de ceci ne passe dessous.
    val inset = LocalChatOverlayInset.current
    Box(Modifier.fillMaxSize().padding(start = inset.start, end = inset.end)) {
        if (ready && controls && scrub == null) {
            // Bouton de l'image verticale en haut à gauche (StreamUi.kt) : le
            // titre, cliquable et posé par-dessus, s'écarte pour le laisser toucher.
            val edge = if (session.vertical.available) 52.dp else 10.dp
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.7f).padding(start = edge, top = 10.dp, end = 10.dp, bottom = 10.dp),
                   verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // Paysage (pas de barre d'infos) : le titre, souvent coupé, ouvre sa fiche.
                if (landscape) {
                    Column(Modifier.clickable { PlayerUi.infoOpen = true }) {
                        Text(session.title.ifEmpty { session.channelName ?: "" }, color = Color.White, fontSize = 15.sp,
                             fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            session.channelName?.takeIf { session.title.isNotEmpty() },
                            session.game.takeIf { session.isLive && it.isNotEmpty() },
                            if (session.isLive && session.viewers > 0) "● " + Format.viewers(session.viewers) else null,
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Heure réelle de l'instant lu (archive) et vitesse, quand elle n'est pas 1× ;
                // en paysage (pas de barre d'infos), la minuterie.
                val sleep = if (landscape) sleepLeft(session) else null
                val pills = listOfNotNull(
                    start?.let { "🕰 " + timeText(context, it + PlayerUi.positionMs) },
                    PlayerSpeed.current.takeIf { it != 1f }?.let { PlayerSpeed.label(it) },
                    sleep?.let { "⏾ " + Format.clock(it) },
                )
                if (pills.isNotEmpty()) OverlayPill(pills.joinToString(" · "))
                // Série 6 (PlayerControls.kt) : « Direct · −45 s », latence, chapitre en cours.
                PlayerStatusPills(session)
            }
        }
        // Glissé sur la barre : l'instant visé, son heure réelle et son chapitre.
        if (ready && scrub != null) {
            val parts = listOfNotNull(
                Format.clock(scrub / 1000),
                start?.let { timeText(context, it + scrub) },
                chapterAt(session, scrub),
            )
            if (parts.size > 1) OverlayPill(parts.joinToString(" · "), Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        }
        // Passage dont Twitch a coupé le son : on le dit (sinon on croit à une
        // panne) et on propose de le sauter.
        // Position relue seulement s'il y a des passages coupés (pas de
        // recomposition toutes les 0,5 s pendant un direct).
        val ranges = if (ready && !session.isLive) markers?.muted.orEmpty() else emptyList()
        val t = if (ranges.isEmpty()) 0.0 else (scrub ?: PlayerUi.positionMs) / 1000.0
        val muted = ranges.firstOrNull { it.contains(t) }
        if (muted != null) {
            val top = when {
                scrub != null -> 44.dp
                controls -> if (landscape) 72.dp else 40.dp
                else -> 8.dp
            }
            MutedNotice(Modifier.align(Alignment.TopCenter).padding(top = top, start = 8.dp, end = 8.dp)) {
                val d = session.player.duration
                val after = (muted.end * 1000).toLong() + 500
                session.seekTo(if (d != C.TIME_UNSET && d > 0) minOf(after, d) else after)
                Logger.info("PLAYER", "Passage coupé sauté", Format.clock(after / 1000))
            }
        }
        // Bandeau fugace : ±10 s, vitesse, zoom.
        val until = PlayerUi.flashUntil
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(until) {
            val left = until - SystemClock.uptimeMillis()
            if (left > 0) {
                visible = true
                delay(left)
            }
            visible = false
        }
        AnimatedVisibility(visible, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Text(PlayerUi.flashText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000))
                     .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun OverlayPill(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
         modifier = modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000))
             .padding(horizontal = 9.dp, vertical = 3.dp))
}

/** Temps restant de la minuterie (s), relu deux fois par seconde : calculé une
 *  fois à l'affichage, le compte à rebours restait figé. null sans minuterie. */
@Composable
fun sleepLeft(session: PlaybackSession): Long? {
    val at = session.sleepAt ?: return null
    fun remaining() = maxOf(0L, (at - System.currentTimeMillis() + 999) / 1000)
    val shown by produceState(remaining(), at) {
        while (true) {
            value = remaining()
            delay(500)
        }
    }
    return shown
}

@Composable
private fun MutedNotice(modifier: Modifier, onSkip: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(modifier.clip(shape).background(Color(0x9E000000)).border(1.dp, MutedOrange.copy(alpha = 0.6f), shape)
            .height(32.dp).padding(start = 11.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔇 " + AppState.t("vod_muted_here"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(AppState.t("vod_muted_skip") + " ⏭", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.clip(shape).background(Color(0x2EFFFFFF)).clickable(onClick = onSkip)
                 .padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

/** Menu ⋮ du lecteur (MoreMenu) : vitesse, aller à une heure (archive),
 *  compresseur audio. */
@Composable
fun PlayerMenuExtras(session: PlaybackSession, onSpeed: () -> Unit, onClose: () -> Unit) {
    val ready = session.state is PlaybackSession.State.Ready
    if (ready) {
        // En direct : la vitesse de rattrapage (1× au moins).
        val shown = if (session.isLive) maxOf(1f, PlayerSpeed.selected) else PlayerSpeed.selected
        DropdownMenuItem(text = { Text(AppState.t("speed") + "  " + PlayerSpeed.name(shown)) }, onClick = onSpeed)
    }
    val m = PlayerUi.markers
    if (ready && !session.isLive && m != null && m.id == session.vodId && m.broadcastStart != null &&
        vodLengthMs(session, m) > 0) {
        DropdownMenuItem(text = { Text(AppState.t("vod_goto_time")) }, onClick = {
            onClose()
            PlayerUi.gotoOpen = true
        })
    }
    val comp = PlayerPrefs.audioCompressor.value
    DropdownMenuItem(text = { Text(AppState.t("pl_audio_comp") + if (comp) "  ✓" else "") }, onClick = {
        onClose()
        PlayerPrefs.setCompressor(!comp)
    })
}

/** Page « Vitesse » du menu ⋮ : 0,25× à 2× ; en direct, 1× à 2× (rattrapage). */
@Composable
fun SpeedMenuPage(session: PlaybackSession, onClose: () -> Unit) {
    val live = session.isLive
    val selected = PlayerSpeed.selected
    for (v in PlayerSpeed.choices(live)) {
        DropdownMenuItem(
            text = { Text(PlayerSpeed.name(v) + if (abs(v - selected) < 0.01f) "  ✓" else "") },
            onClick = {
                onClose()
                if (PlayerSpeed.choose(session.player, v)) PlayerUi.flash(PlayerSpeed.flashText(PlayerSpeed.current), 1_000)
            },
        )
    }
    if (live) {
        DropdownMenuItem(text = { Text(AppState.t("speed_live_note"), color = TU.muted, fontSize = 12.sp) },
                         onClick = {}, enabled = false)
    }
}

/** Fiche du direct ou de la VOD : le titre en entier (coupé dans la barre),
 *  la catégorie, les spectateurs, la durée du direct, la date de la diffusion,
 *  et la chaîne. */
@Composable
private fun InfoCard(session: PlaybackSession, nav: Navigator) {
    val close = { PlayerUi.infoOpen = false }
    val login = session.channelLogin
    val live = session.isLive
    val rows = buildList {
        if (live) {
            if (session.game.isNotEmpty()) add("🎮  " + session.game)
            val n = session.viewers
            if (n > 0) add("👁  " + Format.viewers(n) + " " + AppState.tn("viewers", n, abbreviated = n >= 1000))
            Format.uptime(session.startedAt)?.let { add("🕒  $it") }
        } else {
            PlayerUi.markers?.takeIf { it.id == session.vodId }?.broadcastStart?.let { add("📅  " + broadcastDate(it)) }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(session.channelName ?: session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Text(session.title.ifEmpty { session.channelName ?: "" }, color = TU.text, fontSize = 17.sp,
                         fontWeight = FontWeight.SemiBold)
                }
                for (r in rows) Text(r, color = TU.text, fontSize = 15.sp)
            }
        },
        confirmButton = {
            if (login != null) {
                TextButton(onClick = {
                    close()
                    nav.push(Screen.Channel(login))
                }) { Text(AppState.t("see_channel"), color = TU.primary) }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(AppState.t("close"), color = TU.text) } },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.muted,
    )
}

/** « Aller à une heure » d'une archive : l'heure réelle d'un moment de la
 *  diffusion, changée en position dans la VOD. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GotoTimeDialog(session: PlaybackSession) {
    val context = LocalContext.current
    val m = PlayerUi.markers?.takeIf { it.id == session.vodId }
    val start = m?.broadcastStart
    val length = vodLengthMs(session, m)
    if (start == null || length <= 0) return
    val close = { PlayerUi.gotoOpen = false }
    val initial = remember(start) {
        Calendar.getInstance().apply { timeInMillis = start + session.player.currentPosition.coerceIn(0L, length) }
    }
    val state = rememberTimePickerState(initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE),
                                        android.text.format.DateFormat.is24HourFormat(context))
    val end = start + length
    // Diffusion passée minuit : le jour avec l'heure.
    val days = !sameDay(start, end)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(AppState.t("vod_goto_time")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(AppState.t("vod_goto_time_sub").replace("{a}", timeText(context, start, days))
                         .replace("{b}", timeText(context, end, days)), fontSize = 13.sp)
                TimeInput(state)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                close()
                val at = timeOfDayOffset(start, length, state.hour, state.minute)
                session.seekTo(at)
                Logger.info("PLAYER", "Aller à une heure", "%02d:%02d → %s".format(state.hour, state.minute, Format.clock(at / 1000)))
            }) { Text(AppState.t("vod_goto_btn"), color = TU.primary) }
        },
        dismissButton = { TextButton(onClick = close) { Text(AppState.t("cancel"), color = TU.text) } },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.muted,
    )
}

/** Durée de la VOD lue (ms) : la plus longue connue (un enregistrement en cours grandit). */
private fun vodLengthMs(session: PlaybackSession, m: VodMarkers?): Long {
    val d = session.player.duration
    return maxOf((m?.length ?: 0) * 1000L, session.lengthSeconds * 1000L, if (d != C.TIME_UNSET && d > 0) d else 0L)
}

/** Position (ms) d'une heure de la journée dans une diffusion : sa première
 *  occurrence depuis le début (le lendemain pour une diffusion passée minuit). */
private fun timeOfDayOffset(start: Long, lengthMs: Long, hour: Int, minute: Int): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = start
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // Avant le début : le lendemain, sauf dans la minute même du début.
    if (c.timeInMillis < start - 60_000) c.add(Calendar.DAY_OF_MONTH, 1)
    return (c.timeInMillis - start).coerceIn(0L, maxOf(0L, lengthMs - 1_000))
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Heure (« 22:15 » ou « 10:15 PM », réglage de l'appareil), avec le jour si demandé. */
private fun timeText(context: Context, ms: Long, withDay: Boolean = false): String {
    val date = Date(ms)
    val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
    return if (withDay) android.text.format.DateFormat.getMediumDateFormat(context).format(date) + " " + time else time
}

/** Date et heure d'une diffusion dans la langue de l'app (« 12 oct. 2026 22:15 »). */
private fun broadcastDate(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                                             Locale.forLanguageTag(AppState.lang.code)).format(Date(ms))

/** Chapitre d'un instant (ms), écourté ; null sans chapitres. */
private fun chapterAt(session: PlaybackSession, ms: Long): String? {
    val list = session.chapters
    if (list.size < 2) return null
    val title = list.lastOrNull { it.start <= ms / 1000.0 + 0.5 }?.title?.takeIf { it.isNotEmpty() } ?: return null
    return if (title.length > 28) title.take(27) + "…" else title
}
