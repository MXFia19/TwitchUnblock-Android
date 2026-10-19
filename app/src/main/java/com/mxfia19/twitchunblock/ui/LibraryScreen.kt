package com.mxfia19.twitchunblock.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.HistoryItem
import com.mxfia19.twitchunblock.core.Lang
import com.mxfia19.twitchunblock.download.Downloads
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import com.mxfia19.twitchunblock.player.PlayRequest

/** Taille lisible (« 1,2 Go », « 350 MB »), unités dans la langue de l'app. */
fun sizeText(bytes: Long): String {
    val (g, m, k) = when (AppState.lang) {
        Lang.FR -> Triple("Go", "Mo", "Ko")
        Lang.RU -> Triple("ГБ", "МБ", "КБ")
        else -> Triple("GB", "MB", "KB")
    }
    return when {
        bytes >= 1_000_000_000L -> String.format("%.1f %s", bytes / 1e9, g)
        bytes >= 1_000_000L -> "${bytes / 1_000_000} $m"
        else -> "${bytes / 1000} $k"
    }
}

/** VODs téléchargées : à regarder hors ligne, avec leur chat. */
@OptIn(UnstableApi::class)
@Composable
fun LibraryScreen(nav: Navigator) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<String?>(null) }
    val items = Downloads.items
    val history = History.vods
    LazyColumn(Modifier.fillMaxSize()) {
        // Dernières VODs lancées (synchronisées avec le compte), avec la reprise.
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(AppState.t("history_vods_title"), Modifier.weight(1f))
                    TextButton(onClick = { History.clear("vod") }) { Text(AppState.t("clear"), color = TU.muted) }
                }
            }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    items(history, key = { "h-" + it.term }) { h -> HistoryVodCard(h, nav) }
                }
            }
        }
        item { SectionTitle(AppState.t("dl_section")) }
        if (items.isEmpty()) {
            item { MessageBox(AppState.t("dl_empty")) }
        } else {
            item {
                Text(sizeText(Downloads.usedBytes()), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        item {
            Toggle(AppState.t("dl_wifi_only"), AppState.t("dl_wifi_only_sub"), Features.dlWifiOnly.value) {
                Downloads.updateWifiOnly(it)
            }
        }
        items(items, key = { it.vodId }) { d ->
            Row(
                Modifier.fillMaxWidth().focusCard({ if (d.completed) nav.play(PlayRequest.Download(d.vodId)) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(TU.surfaceHi)) {
                    AsyncImage(model = d.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                               modifier = Modifier.fillMaxSize())
                    Pill(Format.clock(d.lengthSeconds.toLong()), Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                         overflow = TextOverflow.Ellipsis)
                    Text(listOf(d.channel, qualityName(d.quality), sizeText(d.bytes)).filter { it.isNotEmpty() }.joinToString(" · "),
                         color = TU.muted, fontSize = 12.sp, maxLines = 1)
                    val status = when (d.state) {
                        Download.STATE_COMPLETED -> null
                        Download.STATE_FAILED -> AppState.t("dl_failed")
                        Download.STATE_QUEUED, Download.STATE_RESTARTING -> when {
                            Downloads.paused -> AppState.t("dl_paused")
                            Downloads.waitingForWifi -> AppState.t("dl_waiting_wifi")
                            else -> AppState.t("dl_preparing")
                        }
                        Download.STATE_STOPPED -> AppState.t("dl_paused")
                        else -> "${d.percent.toInt()} %"
                    }
                    if (status != null) {
                        Text(status, color = if (d.failed) TU.danger else TU.primary, fontSize = 12.sp)
                        if (d.state == Download.STATE_DOWNLOADING) {
                            LinearProgressIndicator(progress = { d.percent / 100f }, color = TU.primary, trackColor = TU.line,
                                                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp))
                        }
                    }
                    val chat = Downloads.chatCounts[d.vodId]
                    if (chat != null) {
                        Text(if (chat < 0) AppState.t("dl_chat_pending")
                             else AppState.tn("dl_chat_count", chat).replace("{n}", chat.toString()),
                             color = TU.muted, fontSize = 12.sp)
                    }
                }
                // En échec : relancé ; en pause (ou gestionnaire en pause) : repris.
                val resumable = d.state == Download.STATE_STOPPED || (d.state == Download.STATE_QUEUED && Downloads.paused)
                if (d.failed) {
                    TextButton(onClick = { Downloads.retry(context, d.vodId) }) { Text(AppState.t("retry"), color = TU.primary) }
                } else if (resumable) {
                    TextButton(onClick = { Downloads.resume(context, d.vodId) }) { Text(AppState.t("dl_resume"), color = TU.primary) }
                }
                IconButton(onClick = { confirm = d.vodId }) {
                    Icon(Icons.Filled.Delete, contentDescription = AppState.t("remove"), tint = TU.muted)
                }
            }
        }
    }
    val id = confirm
    if (id != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(AppState.t("dl_delete_confirm")) },
            confirmButton = {
                TextButton(onClick = { Downloads.remove(context, id); confirm = null }) { Text(AppState.t("remove"), color = TU.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(AppState.t("sleep_cancel"), color = TU.text) } },
            containerColor = TU.surface,
            titleContentColor = TU.text,
        )
    }
}

/** VOD de l'historique : miniature, titre, chaîne, reprise. */
@Composable
private fun HistoryVodCard(h: HistoryItem, nav: Navigator) {
    val resume = AppState.vodPosition(h.term)
    Column(Modifier.width(200.dp).focusCard({ nav.play(PlayRequest.Vod(h.term, h.display, h.streamer)) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(TU.surfaceHi)) {
            if (h.thumb != null) {
                AsyncImage(model = h.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (resume > 0) Pill("▶ " + Format.clock(resume / 1000), Modifier.align(Alignment.BottomStart).padding(4.dp))
            IconButton(onClick = { History.remove(h.term) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = AppState.t("delete_vod"), tint = androidx.compose.ui.graphics.Color.White,
                     modifier = Modifier.size(16.dp))
            }
        }
        Text(h.display, color = TU.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        h.streamer?.let { Text(it, color = TU.muted, fontSize = 12.sp, maxLines = 1) }
    }
}

/** Nom de la qualité d'un téléchargement. */
private fun qualityName(key: String): String =
    if (key.isEmpty()) "" else com.mxfia19.twitchunblock.core.Quality.label(key, AppState.lang)
