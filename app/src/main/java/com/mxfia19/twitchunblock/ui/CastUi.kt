package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.cast.CastManager
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.player.PlaybackSession

/** Playlist d'une qualité à envoyer au Chromecast : celle choisie, sinon la
 *  plus haute jusqu'en 1080p (ni Auto ni audio seul : le récepteur lit une
 *  seule playlist, et la liste maîtresse d'usher lui est fermée). */
fun castItem(session: PlaybackSession): CastManager.Item? {
    val m = session.currentMaster
    val keys = m.links.keys
    val video = Quality.sort(keys.filter { !Quality.isAudio(it) && !Quality.isAuto(it) }, m.variants)
    fun h(k: String) = Quality.height(k, keys, m.variants)
    val chosen = session.quality?.takeIf { it in video && h(it) <= 1080 }
        ?: video.firstOrNull { h(it) in 1..1080 }
        ?: video.lastOrNull()
        ?: return null
    val url = m.links[chosen] ?: return null
    return CastManager.Item(url, session.title.ifEmpty { session.channelName ?: "Twitch" }, session.channelName, session.avatar)
}

/**
 * Chromecast : la liste des appareils (menu ⋮ du lecteur), puis, pendant la
 * diffusion, l'image du téléphone en pause sous un voile qui dit où le direct
 * se lit. La diffusion suit le lecteur (raid, qualité) ; à sa fin, le direct
 * reprend au bord sur le téléphone.
 */
@Composable
fun BoxScope.CastOverlay(session: PlaybackSession) {
    val context = LocalContext.current
    DisposableEffect(session) {
        CastManager.bind(context) {
            if (session.isLive) session.player.seekToDefaultPosition()
            session.player.play()
        }
        onDispose { CastManager.unbind() }
    }
    val item = if (session.isLive && session.state is PlaybackSession.State.Ready) castItem(session) else null
    val device = CastManager.device
    LaunchedEffect(device, item?.url) {
        if (device != null && item != null) {
            CastManager.load(item)
            session.player.pause()
        }
    }
    if (CastManager.pickerOpen) CastPicker(item)
    if (device == null && !CastManager.connecting) return
    Box(Modifier.matchParentSize().background(Color(0xD9000000)).clickable(enabled = false) {}) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📺 " + if (device != null) AppState.t("st_cast_playing").replace("{d}", device) else AppState.t("st_cast_connecting"),
                 color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (CastManager.failed) {
                Text(AppState.t("st_cast_failed"), color = TU.danger, fontSize = 12.sp, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(top = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (device != null) {
                    TextButton(onClick = { CastManager.togglePlayback() }) {
                        Text(AppState.t(if (CastManager.remotePlaying) "multi_pause" else "multi_play"), color = TU.primary)
                    }
                }
                TextButton(onClick = { CastManager.stop() }) { Text(AppState.t("st_cast_stop"), color = TU.danger) }
            }
        }
    }
}

/** Appareils du réseau Wi-Fi ; un toucher y envoie le direct. */
@Composable
private fun CastPicker(item: CastManager.Item?) {
    AlertDialog(
        onDismissRequest = { CastManager.closePicker() },
        title = { Text(AppState.t("st_cast")) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    CastManager.available == false -> Text(AppState.t("st_cast_unavailable"), fontSize = 14.sp)
                    item == null || CastManager.routes.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = TU.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text(AppState.t("st_cast_searching"), fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    else -> item?.let { target ->
                        for (route in CastManager.routes) {
                            Text("📺  " + route.name, color = TU.text, fontSize = 15.sp,
                                 modifier = Modifier.fillMaxWidth().clickable { CastManager.select(route, target) }.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { CastManager.closePicker() }) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.muted,
    )
}
