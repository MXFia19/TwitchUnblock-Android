package com.mxfia19.twitchunblock.ui

import android.content.res.Configuration
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.PlayerExtraPrefs
import com.mxfia19.twitchunblock.core.VodChapter
import com.mxfia19.twitchunblock.events.RaidInfo
import com.mxfia19.twitchunblock.player.LiveLatency
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay
import java.util.WeakHashMap
import kotlin.math.roundToLong

// Commandes du lecteur ajoutées en série 6 : couper le son, recharger,
// réduire ; « Direct · −45 s » et la latence ; chapitres sur la barre et
// chapitre en cours. Comme le lecteur immersif de l'iPhone.

/**
 * Par-dessus l'image du lecteur principal (portrait et paysage) : écran de fin
 * du direct, puis, avec les commandes de Media3, haut-parleur, recharger et
 * réduire en haut à droite (en paysage, à gauche du bouton du chat).
 */
@Composable
fun BoxScope.PlayerTopOverlay(session: PlaybackSession, nav: Navigator, raid: RaidInfo?) {
    LiveEndOverlay(session, nav, raid)
    if (!PlayerUi.controlsVisible) return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inset = LocalChatOverlayInset.current
    val muted = rememberMuted(session.player)
    // Bandeau d'une pause pub en haut au milieu : les boutons passent dessous.
    val top = if (session.isLive && session.adBreaks.remaining != null) 52.dp else 8.dp
    Row(Modifier.align(Alignment.TopEnd).padding(top = top, end = 8.dp + inset.end + if (landscape) 48.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundAction(if (muted) BrowseIcons.volumeOff else BrowseIcons.volumeUp, AppState.t("pb_mute")) {
            session.player.volume = if (muted) 1f else 0f
            PlayerUi.flash(AppState.t(if (muted) "player_unmuted" else "player_muted"))
        }
        RoundAction(Icons.Filled.Refresh, AppState.t("pb_refresh")) { reloadSource(session) }
        val leave = PlayerLeave.action
        if (leave != null) RoundAction(Icons.Filled.KeyboardArrowDown, AppState.t("reduce")) { leave() }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x80000000)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Son coupé (volume à 0), suivi : la pause pub coupe aussi le son du direct. */
@Composable
private fun rememberMuted(player: Player): Boolean {
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                muted = volume == 0f
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return muted
}

/** Recharger la source en pleine lecture : une VOD reprend où elle en est,
 *  un direct relit ses sources. */
private fun reloadSource(session: PlaybackSession) {
    Logger.info("PLAYER", "Source rechargée à la main")
    val r = session.request
    if (r is PlayRequest.Vod && session.state is PlaybackSession.State.Ready) {
        session.open(r.copy(startMs = maxOf(0L, session.player.currentPosition)))
    } else {
        session.retry()
    }
}

/**
 * Dans la colonne d'en haut à gauche (PlayerVideoOverlay, avec les
 * commandes) : en direct, « Direct · −45 s » une fois en retard (pause,
 * coupure, recul) et la latence (réglage) ; un toucher ramène au bord sans
 * recharger. Sur une VOD, le chapitre en cours ; un toucher liste les chapitres.
 */
@Composable
fun PlayerStatusPills(session: PlaybackSession) {
    if (session.isLive) LivePills(session) else ChapterPill(session)
}

@Composable
private fun LivePills(session: PlaybackSession) {
    val player = session.player
    val status by produceState<LiveLatency.Status?>(null, player, session.openCount) {
        while (true) {
            value = LiveLatency.status(player)
            delay(1_000)
        }
    }
    val s = status ?: return
    if (LiveEnd.isEnded(session)) return
    val behind = s.lateMs > 3_000 || !s.playing
    val total = s.totalMs?.takeIf { PlayerExtraPrefs.showLatency.value }
    if (!behind && total == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (behind) {
            StatusPill("⏭ " + AppState.t("back_to_live") + lateLabel(s.lateMs), TU.live.copy(alpha = 0.85f)) {
                Logger.info("PLAYER", "Retour au direct", "${s.lateMs / 1000} s de retard")
                LiveLatency.goLive(player)
            }
        }
        if (total != null) {
            StatusPill("⏱ " + ((total + 500) / 1000) + " s", Color(0x99000000)) { LiveLatency.goLive(player) }
        }
    }
}

/** Retard à côté de « Direct » : « · −45 s », « · −2:05 » ; rien sous 15 s. */
private fun lateLabel(ms: Long): String {
    val s = (maxOf(0L, ms) / 1000.0).roundToLong()
    if (s < 15) return ""
    return if (s >= 60) " · −${s / 60}:${(s % 60).toString().padStart(2, '0')}" else " · −$s s"
}

@Composable
private fun StatusPill(text: String, background: Color, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
         overflow = TextOverflow.Ellipsis,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(background).clickable(onClick = onClick)
             .padding(horizontal = 9.dp, vertical = 4.dp))
}

/** Chapitres de la VOD (liste ouverte par le chapitre en cours). */
object ChapterUi {
    var open by mutableStateOf(false)
}

private fun currentChapter(list: List<VodChapter>, positionMs: Long): Int =
    list.indexOfLast { it.start <= positionMs / 1000.0 + 0.5 }.coerceAtLeast(0)

@Composable
private fun ChapterPill(session: PlaybackSession) {
    val list = session.chapters
    if (list.size < 2) return
    val title = list[currentChapter(list, PlayerUi.positionMs)].title.ifEmpty { "—" }
    StatusPill("☰ " + (if (title.length > 28) title.take(27) + "…" else title), Color(0x99000000)) { ChapterUi.open = true }
}

/** Liste des chapitres (MiniPlayerHost, lecteur en grand) : toucher y saute. */
@Composable
fun ChapterDialogHost(session: PlaybackSession) {
    if (!ChapterUi.open || PipState.active) return
    val list = session.chapters
    val current = currentChapter(list, PlayerUi.positionMs)
    AlertDialog(
        onDismissRequest = { ChapterUi.open = false },
        title = { Text(AppState.t("chapters")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(list) { i, c ->
                    val color = if (i == current) TU.primary else TU.text
                    Row(Modifier.fillMaxWidth().clickable {
                        ChapterUi.open = false
                        session.seekTo((c.start * 1000).toLong())
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.clock(c.start.toLong()), color = if (i == current) TU.primary else TU.muted, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.title.ifEmpty { "—" }, color = color, fontSize = 14.sp,
                             fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                             maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ChapterUi.open = false }) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.text,
    )
}

/** Repères déjà posés sur chaque vue : la mise à jour de VideoSurface les relit souvent. */
private val chapterMarks = WeakHashMap<PlayerView, List<Long>>()

/** Débuts des chapitres sur la barre de Media3, en repères blancs (ceux des
 *  pubs, jaunes, n'existent pas sur une VOD Twitch). Le premier, à 0, n'en a pas. */
@OptIn(UnstableApi::class)
fun PlayerView.showChapterMarks(chapters: List<VodChapter>) {
    val times = chapters.map { (it.start * 1000).toLong() }.filter { it > 0 }
    if (chapterMarks[this] == times) return
    chapterMarks[this] = times
    (findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? DefaultTimeBar)?.let {
        it.setAdMarkerColor(0xE6FFFFFF.toInt())
        it.setPlayedAdMarkerColor(0x99FFFFFF.toInt())
    }
    setExtraAdGroupMarkers(times.toLongArray(), BooleanArray(times.size))
}
