package com.mxfia19.twitchunblock.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NetworkStatus
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.VodUnmute

/** Durée de la VOD lue (s) : celle de Twitch, sinon celle du lecteur ; 0 : inconnue. */
fun vodLengthSeconds(session: PlaybackSession): Int {
    if (session.lengthSeconds > 0) return session.lengthSeconds
    val d = session.player.duration
    return if (d > 0) (d / 1000).toInt() else 0
}

/** « 1:02:03 », « 1h02m03 », « 2.03 » ou « 45 » en secondes ; null si illisible. */
private fun parseClock(text: String): Int? {
    val parts = text.trim().lowercase().split(Regex("[:.,hms\\s]+")).filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        total = total * 60 + n
    }
    return total
}

/**
 * Téléchargement d'une VOD (série 6), comme la fiche de l'iPhone : la qualité
 * (la préférée d'abord, taille estimée), toute la VOD ou une partie (d'un
 * instant à un autre, d'ici, les 10 minutes suivantes, par chapitre), le son
 * d'origine des passages coupés, le chat, le Wi-Fi seulement ; l'espace libre
 * vérifié avant de partir.
 */
@Composable
fun DownloadDialog(session: PlaybackSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vodId = session.vodId ?: return
    val variants = session.currentMaster.variants
    val keys = session.qualities.filter { !Quality.isAuto(it) }
    if (keys.isEmpty()) return
    // Qualité préférée proposée d'abord (Auto : la meilleure, Source).
    val preferred = remember(keys) {
        Quality.preferred(keys, AppState.qualityPref.takeIf { it != "auto" } ?: "source", variants).takeIf { it in keys } ?: keys.first()
    }
    val ordered = remember(keys, preferred) { listOf(preferred) + keys.filter { it != preferred } }
    val length = vodLengthSeconds(session)
    val here = remember { (session.player.currentPosition / 1000).toInt().coerceIn(0, maxOf(0, length - 1)) }
    val chapters = session.chapters

    var quality by remember { mutableStateOf(preferred) }
    var from by remember { mutableStateOf(0) }
    var to by remember { mutableStateOf(length) }
    var mode by remember { mutableStateOf("all") }
    var chapterRange by remember { mutableStateOf<IntRange?>(null) }
    var startText by remember { mutableStateOf(Format.clock(0)) }
    var endText by remember { mutableStateOf(Format.clock(length.toLong())) }
    var restore by remember { mutableStateOf(VodUnmute.enabled.value) }
    var chat by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Downloads.Options?>(null) }

    fun setRange(a: Int, b: Int, m: String) {
        from = a.coerceIn(0, maxOf(0, length - 1))
        to = b.coerceIn(from + 1, maxOf(from + 1, length))
        mode = m
        startText = Format.clock(from.toLong())
        endText = Format.clock(to.toLong())
        error = null
    }

    fun begin(opts: Downloads.Options) {
        val url = session.links[quality] ?: return
        Downloads.start(context, vodId, session.title, session.channelLogin ?: "", session.thumbnail, length, quality, url, opts)
        onDismiss()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { begin(it) }
        pending = null
    }

    val seconds = if (length > 0) maxOf(0, to - from) else 0
    val size = Downloads.estimate(session.bandwidth(quality), seconds)
    val free = Downloads.freeBytes()
    val part = length > 0 && (from > 0 || to < length)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppState.t("dl_download")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(AppState.t("quality"), color = TU.muted, fontSize = 13.sp)
                for (q in ordered) {
                    val s = Downloads.estimate(session.bandwidth(q), seconds)
                    Row(Modifier.fillMaxWidth().clickable { quality = q; error = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = q == quality, onClick = { quality = q; error = null },
                                    colors = RadioButtonDefaults.colors(selectedColor = TU.primary))
                        Text(Quality.label(q, AppState.lang, variants), color = TU.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (s > 0) Text("≈ " + sizeText(s), color = TU.muted, fontSize = 13.sp)
                    }
                }
                if (length > 0) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TabChip(AppState.t("dl_all"), mode == "all") { chapterRange = null; setRange(0, length, "all") }
                        if (here > 0) TabChip(AppState.t("dl_from_here"), mode == "here") { chapterRange = null; setRange(here, length, "here") }
                        TabChip(AppState.t("dl_ten_min"), mode == "ten") { chapterRange = null; setRange(here, here + 600, "ten") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField(AppState.t("dl_start"), startText, Modifier.weight(1f)) { t ->
                            startText = t
                            chapterRange = null
                            mode = "custom"
                            parseClock(t)?.let { from = it.coerceIn(0, length) }
                            error = null
                        }
                        TimeField(AppState.t("dl_end"), endText, Modifier.weight(1f)) { t ->
                            endText = t
                            chapterRange = null
                            mode = "custom"
                            parseClock(t)?.let { to = it.coerceIn(0, length) }
                            error = null
                        }
                    }
                    if (chapters.size > 1) {
                        Text(AppState.t("chapters"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(AppState.t("dl_chapters_sub"), color = TU.muted, fontSize = 12.sp)
                        chapters.forEachIndexed { i, c ->
                            val selected = chapterRange?.contains(i) == true
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) TU.primaryDim else TU.surfaceHi)
                                    .clickable {
                                        // Un chapitre, puis d'autres pour l'étendre : la partie reste d'un seul tenant.
                                        val r = chapterRange
                                        val next = when {
                                            r == null -> i..i
                                            r.first == i && r.last == i -> null
                                            else -> minOf(r.first, i)..maxOf(r.last, i)
                                        }
                                        chapterRange = next
                                        if (next == null) setRange(0, length, "all")
                                        else {
                                            val a = chapters[next.first].start.toInt()
                                            val last = chapters[next.last]
                                            val b = if (next.last == chapters.lastIndex) length else (last.start + last.duration).toInt()
                                            setRange(a, b, "chapters")
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(Format.clock(c.start.toLong()), color = TU.muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                                Text(c.title.ifEmpty { "—" }, color = TU.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                     modifier = Modifier.weight(1f))
                                if (c.duration > 0) Text(Format.duration(c.duration.toInt()), color = TU.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Toggle(AppState.t("dl_restore"), AppState.t("dl_restore_sub"), restore) { restore = it }
                Toggle(AppState.t("dl_chat"), AppState.t("dl_chat_sub"), chat) { chat = it }
                Toggle(AppState.t("dl_wifi_only"), null, Features.dlWifiOnly.value) { Downloads.updateWifiOnly(it) }
                if (Features.dlWifiOnly.value && NetworkStatus.isCellular) {
                    Text(AppState.t("dl_wifi_needed"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                val summary = listOfNotNull(
                    if (seconds > 0) {
                        if (size > 0) AppState.t("dl_summary").replace("{d}", Format.duration(seconds)).replace("{s}", sizeText(size))
                        else Format.duration(seconds)
                    } else null,
                    if (free >= 0) AppState.t("dl_free_space").replace("{f}", sizeText(free)) else null,
                ).joinToString("\n")
                if (summary.isNotEmpty()) {
                    Text(summary, color = TU.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                }
                error?.let { Text(it, color = TU.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (length > 0 && to <= from) {
                    error = AppState.t("m6_dl_range_bad")
                    return@TextButton
                }
                if (size > 0 && free >= 0 && size > free) {
                    Logger.warn("DOWNLOAD", "Pas assez d'espace", "${sizeText(size)} > ${sizeText(free)}")
                    error = AppState.t(if (part) "dl_no_space" else "dl_no_space_vod").replace("{s}", sizeText(size)).replace("{f}", sizeText(free))
                    return@TextButton
                }
                val v = variants[quality]
                val opts = Downloads.Options(
                    from = if (part) from.toDouble() else 0.0,
                    to = if (part && to < length) to.toDouble() else 0.0,
                    restore = restore, chat = chat, height = v?.height ?: 0, fps = v?.fps ?: 0.0,
                )
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pending = opts
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    begin(opts)
                }
            }) { Text(AppState.t("dl_download"), color = TU.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppState.t("cancel"), color = TU.text) } },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.text,
    )
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                  focusedTextColor = TU.text, unfocusedTextColor = TU.text,
                                                  focusedLabelColor = TU.primary, unfocusedLabelColor = TU.muted),
        modifier = modifier,
    )
}
