package com.mxfia19.twitchunblock.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay

/** Icônes du multi-live et du lecteur absentes des icônes de base (tracés Material). */
object MediaIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val pause by lazy { icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val swap by lazy { icon("swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z") }
    val openFull by lazy { icon("open_full", "M21,11V3h-8l3.29,3.29l-10,10L3,13v8h8l-3.29,-3.29l10,-10z") }
    val chat by lazy {
        icon("chat", "M20,2H4C2.9,2 2.01,2.9 2.01,4L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z" +
            "M6,9h12v2H6V9zM14,14H6v-2h8V14zM18,8H6V6h12V8z")
    }
}

/** Commandes d'une tuile du téléphone : les autres tuiles (place, nom) pour
 *  ⇄, échanger, et ouvrir dans le lecteur. */
class TileControls(val others: List<Pair<Int, String>>, val onSwap: (Int) -> Unit, val onOpen: () -> Unit)

/**
 * Par-dessus une tuile du téléphone (série 6), comme l'iPhone : en haut le
 * nom (« VOD »), ⇄ pour changer de place, ouvrir dans le lecteur et retirer ;
 * au milieu « Réessayer » sur une tuile en échec, « Revoir » à la fin d'une
 * VOD, « Lecture » sur un direct arrêté (focus audio perdu) ; en bas le son,
 * la pause pub, et pour une VOD ses commandes (celle qu'on entend) ou un
 * trait de progression.
 */
@Composable
fun PhoneTileOverlay(tile: MultiLiveModel.Tile, hasAudio: Boolean, sound: TileSound?, controls: TileControls,
                     onRemove: (() -> Unit)?) {
    val session = tile.session
    val player = session.player
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var playbackState by remember(player) { mutableStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(play: Boolean, reason: Int) { playWhenReady = play }
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    var position by remember(player) { mutableStateOf(0L) }
    var duration by remember(player) { mutableStateOf(0L) }
    if (tile.isVod) {
        LaunchedEffect(player) {
            while (true) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
                delay(500)
            }
        }
    }
    val ended = playbackState == Player.STATE_ENDED
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tile.isVod) {
                Text("VOD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.padding(end = 5.dp).clip(RoundedCornerShape(4.dp)).background(TU.primary)
                         .padding(horizontal = 4.dp, vertical = 1.dp))
            }
            Text((if (hasAudio && sound == null) "🔊 " else "") + tile.name, color = Color.White, fontSize = 12.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (controls.others.isNotEmpty()) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TileIcon(MediaIcons.swap, AppState.t("multi_move")) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for ((i, name) in controls.others) {
                            DropdownMenuItem(text = { Text(AppState.t("multi_swap").replace("{n}", name)) },
                                             onClick = { menu = false; controls.onSwap(i) })
                        }
                    }
                }
            }
            TileIcon(MediaIcons.openFull, AppState.t("multi_watch"), controls.onOpen)
            if (onRemove != null) TileIcon(Icons.Filled.Close, AppState.t("multi_remove"), onRemove)
        }

        when (val s = session.state) {
            is PlaybackSession.State.Loading -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.align(Alignment.Center))
            is PlaybackSession.State.Error -> Column(Modifier.align(Alignment.Center).padding(8.dp),
                                                     horizontalAlignment = Alignment.CenterHorizontally) {
                val key = when {
                    tile.isVod -> "multi_vod_failed"
                    s.key == "offline_msg" || s.key == "not_found" -> "multi_offline"
                    else -> "multi_failed"
                }
                Text(AppState.t(key), color = TU.muted, fontSize = 13.sp)
                StateButton(AppState.t("multi_retry"), Icons.Filled.Refresh) { session.retry() }
            }
            PlaybackSession.State.Ready -> when {
                ended && tile.isVod -> Column(Modifier.align(Alignment.Center).padding(8.dp).clip(RoundedCornerShape(10.dp))
                                                  .background(Color(0x8C000000)).padding(10.dp),
                                              horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppState.t("multi_vod_ended"), color = Color.White, fontSize = 13.sp)
                    StateButton(AppState.t("multi_replay"), Icons.Filled.Refresh) {
                        player.seekTo(0)
                        player.play()
                    }
                }
                // Direct arrêté par le système (appel, son d'une autre app) : rien d'autre ne le relancerait.
                !tile.isVod && !playWhenReady -> Box(Modifier.align(Alignment.Center)) {
                    StateButton(AppState.t("multi_play"), Icons.Filled.PlayArrow) { player.play() }
                }
                // VOD en pause sans le son : rien ne le dirait autrement.
                tile.isVod && !playWhenReady && !hasAudio -> Box(
                    Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x8C000000)),
                    contentAlignment = Alignment.Center,
                ) { Icon(MediaIcons.pause, contentDescription = AppState.t("multi_pause"), tint = Color.White) }
                playbackState == Player.STATE_BUFFERING -> CircularProgressIndicator(
                    color = Color.White, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
                else -> {}
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sound != null) TileSoundButton(sound, Modifier.padding(6.dp))
                // Pause pub de la chaîne : bandeau d'une ligne (StreamUi.kt).
                if (!tile.isVod) AdBreakBanner(session.adBreaks, compact = true, modifier = Modifier.padding(4.dp))
            }
            if (tile.isVod && duration > 0) {
                if (hasAudio) VodTileControls(player, position, duration, ended, playWhenReady)
                else Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x80000000))) {
                    Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).height(3.dp).background(TU.primary))
                }
            }
        }
    }
}

/** VOD qui a le son : −10 s, lecture/pause (ou « Revoir »), +10 s, barre et temps. */
@Composable
private fun VodTileControls(player: Player, position: Long, duration: Long, ended: Boolean, playing: Boolean) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xBF000000))))
            .padding(top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextIcon("−10", AppState.t("multi_back10")) { player.seekTo(maxOf(0L, player.currentPosition - 10_000)) }
        val label = if (ended) "multi_replay" else if (playing) "multi_pause" else "multi_play"
        Box(Modifier.size(36.dp).clip(CircleShape).clickable {
                when {
                    ended -> { player.seekTo(0); player.play() }
                    playing -> player.pause()
                    else -> player.play()
                }
            }, contentAlignment = Alignment.Center) {
            Icon(if (ended) Icons.Filled.Refresh else if (playing) MediaIcons.pause else Icons.Filled.PlayArrow,
                 contentDescription = AppState.t(label), tint = Color.White, modifier = Modifier.size(20.dp))
        }
        TextIcon("+10", AppState.t("multi_fwd10")) { player.seekTo(minOf(duration, player.currentPosition + 10_000)) }
        Slider(
            value = scrub ?: (position.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { player.seekTo((it * duration).toLong()) }
                scrub = null
            },
            colors = SliderDefaults.colors(thumbColor = TU.primary, activeTrackColor = TU.primary, inactiveTrackColor = Color(0x66FFFFFF)),
            modifier = Modifier.weight(1f).height(28.dp),
        )
        val shown = scrub?.let { (it * duration).toLong() } ?: position
        Text(Format.clock(shown / 1000) + " / " + Format.clock(duration / 1000), color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TileIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.padding(2.dp).size(30.dp).clip(CircleShape).background(Color(0x80000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Bouton « −10 » / « +10 » ; le lecteur d'écran lit le libellé complet. */
@Composable
private fun TextIcon(text: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 38.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StateButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, TU.line, RoundedCornerShape(16.dp))
            .background(Color(0x99000000)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}
