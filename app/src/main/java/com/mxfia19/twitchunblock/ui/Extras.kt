package com.mxfia19.twitchunblock.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.News
import com.mxfia19.twitchunblock.core.Updates
import com.mxfia19.twitchunblock.core.Usage
import com.mxfia19.twitchunblock.feedback.Feedback
import com.mxfia19.twitchunblock.notify.LiveNotifier
import com.mxfia19.twitchunblock.notify.NotifChannels
import com.mxfia19.twitchunblock.notify.Ntfy
import kotlinx.coroutines.launch

/** Ouvre un lien. Sans app pour l'ouvrir (la plupart des TV n'ont pas de
 *  navigateur), l'adresse s'affiche avec son QR code au lieu de planter. */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val uri = LocalUriHandler.current
    var shown by remember { mutableStateOf<String?>(null) }
    shown?.let { link -> LinkDialog(link) { shown = null } }
    return { link ->
        try {
            uri.openUri(link)
        } catch (e: Exception) {
            shown = link
            Logger.warn("APP", "Aucune app pour ouvrir le lien", link)
        }
    }
}

@Composable
private fun LinkDialog(link: String, onClose: () -> Unit) {
    val qr = remember(link) { runCatching { qrBitmap(link, 360) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null) Image(qr, contentDescription = null, modifier = Modifier.size(180.dp))
                Text(AppState.t("tv_login_steps"), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                Text(link, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) }
        },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.text,
    )
}

/** Nouvelle version disponible : télécharger l'APK, ou plus tard. */
@Composable
fun UpdatePrompt() {
    val open = rememberLinkOpener()
    var hidden by remember { mutableStateOf(false) }
    val u = Updates.available ?: return
    if (hidden || Updates.skipped(u)) return
    AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text(AppState.t("update_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(u.name, fontWeight = FontWeight.SemiBold)
                if (u.notes.isNotBlank()) Text(u.notes, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { hidden = true; open(u.apkUrl) }) { Text(AppState.t("update_open"), color = TU.primary) }
        },
        dismissButton = {
            TextButton(onClick = { hidden = true; Updates.skip(u) }) { Text(AppState.t("update_later"), color = TU.text) }
        },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.text,
    )
}

/** Annonce du développeur et sondage, en haut de l'accueil. */
@Composable
fun NewsCards() {
    val scope = rememberCoroutineScope()
    val open = rememberLinkOpener()
    News.announcement?.let { a ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(TU.primaryDim).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text("📣 " + a.title, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = TU.muted, fontSize = 16.sp, modifier = Modifier.focusCard({ News.dismiss() }).padding(horizontal = 6.dp))
            }
            if (a.message.isNotEmpty()) Text(a.message, color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            a.link?.let { link ->
                Text(link, color = TU.primary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(top = 4.dp).focusCard({ open(link) }))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                for (e in News.REACTIONS) {
                    val mine = News.reaction == e
                    Text(e, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (mine) TU.primary else Color(0x22FFFFFF))
                        .focusCard({ scope.launch { News.react(if (mine) null else e) } }, RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
    News.poll?.let { p -> PollCard(p) }
}

@Composable
private fun PollCard(p: News.Poll) {
    val scope = rememberCoroutineScope()
    val picks = remember(p.id, p.mine) { mutableStateListOf<Int>().apply { addAll(p.mine) } }
    val showResults = p.counts != null
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(TU.surface).padding(12.dp)) {
        Text("📊 " + p.question, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(AppState.t(if (p.multi) "poll_multi_hint" else "poll_single_hint") +
                 if (p.ended) " · " + AppState.t("poll_ended") else "",
             color = TU.muted, fontSize = 12.sp)
        val total = (p.counts?.sum() ?: 0).coerceAtLeast(1)
        p.options.forEachIndexed { i, option ->
            val selected = i in picks
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) TU.primaryDim else Color(0x14FFFFFF))
                    .focusCard({
                        if (p.ended) return@focusCard
                        if (p.multi) { if (selected) picks.remove(i) else picks.add(i) } else { picks.clear(); picks.add(i) }
                    }, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Row {
                    Text((if (selected) "● " else "○ ") + option, color = TU.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (showResults) Text("${(p.counts!!.getOrElse(i) { 0 } * 100 / total)} %", color = TU.muted, fontSize = 13.sp)
                }
                if (showResults) {
                    LinearProgressIndicator(progress = { p.counts!!.getOrElse(i) { 0 } / total.toFloat() }, color = TU.primary,
                                            trackColor = Color.Transparent, modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp))
                }
            }
        }
        if (!p.ended) {
            Button(onClick = { scope.launch { News.vote(picks.toList().sorted()) } },
                   enabled = !News.voting && picks.isNotEmpty() && picks.sorted() != p.mine.sorted(),
                   colors = ButtonDefaults.buttonColors(containerColor = TU.primary), modifier = Modifier.padding(top = 8.dp)) {
                Text(AppState.t(if (p.mine.isEmpty()) "poll_vote" else "poll_change"))
            }
        }
        if (showResults) {
            Text(AppState.t("poll_voters").replace("{n}", p.voters.toString()), color = TU.muted, fontSize = 12.sp,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Réglages : notifications de live (natives et ntfy), comptage, mise à jour. */
@Composable
fun NotificationSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var ntfyResult by remember { mutableStateOf<String?>(null) }
    var channelsOpen by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LiveNotifier.setEnabled(context, true)
    }
    Group(AppState.t("notif_section")) {
        Toggle(AppState.t("notif_live"), AppState.t("notif_live_sub_android"), LiveNotifier.enabled) { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && !LiveNotifier.canNotify(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else LiveNotifier.setEnabled(context, on)
        }
        if (LiveNotifier.enabled) {
            TextButton(onClick = { LiveNotifier.test(context) }, modifier = Modifier.padding(start = 4.dp)) {
                Text(AppState.t("notif_test"), color = TU.primary)
            }
        }
        Toggle(AppState.t("ntfy_live"), AppState.t("ntfy_sub_android"), Ntfy.enabled) { on ->
            scope.launch { if (on) Ntfy.enable(context) else Ntfy.disable() }
        }
        if (Ntfy.enabled) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (Ntfy.synced) AppState.tn("ntfy_status", Ntfy.channels).replace("{n}", Ntfy.channels.toString())
                     else AppState.t("ntfy_pending"), color = TU.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { uri.openUri(Ntfy.subscribeUrl) }) { Text(AppState.t("ntfy_subscribe"), color = TU.text) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            ntfyResult = when (Ntfy.test()) {
                                "sent" -> "ntfy_test_sent"
                                "limited" -> "ntfy_limited"
                                else -> "ntfy_failed"
                            }
                        }
                    }) { Text(AppState.t("notif_test"), color = TU.text) }
                }
                ntfyResult?.let { Text(AppState.t(it), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        // Chaînes notifiées : les suivies, moins celles coupées (ici, par leur
        // cloche ou par l'action d'une notification).
        if (LiveNotifier.enabled || Ntfy.enabled) {
            val muted = NotifChannels.muted.size
            Column(Modifier.fillMaxWidth().focusCard({ channelsOpen = true }, RoundedCornerShape(0.dp)).padding(12.dp)) {
                Text(AppState.t("notif_channels"), color = TU.text, fontSize = 15.sp)
                Text(if (muted == 0) AppState.t("notif_channels_all")
                     else AppState.t("notif_channels_muted").replace("{n}", muted.toString()), color = TU.muted, fontSize = 12.sp)
            }
        }
    }
    if (channelsOpen) NotifChannelsDialog { channelsOpen = false }
    Group(AppState.t("privacy")) {
        Toggle(AppState.t("share_usage"), AppState.t("share_usage_sub"), Usage.enabled) { Usage.updateEnabled(it) }
        var checked by remember { mutableStateOf(false) }
        TextButton(onClick = { scope.launch { Updates.check(force = true); checked = true } }, enabled = !Updates.checking,
                   modifier = Modifier.padding(start = 4.dp)) {
            Text(AppState.t("update_check"), color = TU.primary)
        }
        if (checked && Updates.available == null) {
            Text(AppState.t("update_none"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
    }
}

/** Signalements : en envoyer un, et suivre leurs réponses. */
@Composable
fun FeedbackScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("bug") }
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris: List<Uri> ->
        for (u in uris) if (photos.size < 3) Feedback.photoBase64(context, u)?.let { photos += it }
    }
    LaunchedEffect(Unit) { Feedback.refresh() }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("feedback"), nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).imePadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, key) in listOf("bug" to "fb_bug", "idea" to "fb_idea", "other" to "fb_other")) {
                    TabChip(AppState.t(key), kind == k) { kind = k }
                }
            }
            val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                          focusedTextColor = TU.text, unfocusedTextColor = TU.text)
            OutlinedTextField(
                value = message, onValueChange = { if (it.length <= 2000) message = it }, minLines = 4,
                placeholder = { Text(AppState.t("fb_ph_$kind"), color = TU.muted) }, colors = colors,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = contact, onValueChange = { if (it.length <= 100) contact = it }, singleLine = true,
                placeholder = { Text(AppState.t("fb_contact_ph"), color = TU.muted) }, colors = colors,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                               enabled = photos.size < 3) {
                    Text(AppState.t("fb_add_photos") + if (photos.isNotEmpty()) " (${photos.size})" else "", color = TU.text)
                }
                if (photos.isNotEmpty()) {
                    TextButton(onClick = { photos.clear() }) { Text(AppState.t("remove"), color = TU.danger) }
                }
            }
            Text(AppState.t("fb_info_android"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Button(
                onClick = {
                    sending = true
                    result = null
                    scope.launch {
                        val error = Feedback.send(kind, message.trim(), contact.trim(), photos.toList())
                        sending = false
                        result = error ?: "fb_thanks_follow"
                        if (error == null) { message = ""; photos.clear() }
                    }
                },
                enabled = !sending && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                modifier = Modifier.padding(top = 10.dp),
            ) { Text(AppState.t("fb_send")) }
            result?.let { Text(AppState.t(it), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }

            if (Feedback.reports.isNotEmpty()) {
                Text(AppState.t("fb_mine"), color = TU.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                for (r in Feedback.reports) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(TU.surface)
                        .focusCard({ nav.push(Screen.FeedbackThread(r.id)) }, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(AppState.t("fb_${r.kind}"), color = TU.primary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(AppState.t("fb_status_${r.status}") + if (r.unread) "  ●" else "",
                                 color = if (r.unread) TU.live else TU.muted, fontSize = 12.sp)
                        }
                        Text(r.text, color = TU.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Une discussion de signalement : messages, photos, réponse. */
@Composable
fun FeedbackThreadScreen(id: String, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report = Feedback.reports.firstOrNull { it.id == id }
    var reply by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris: List<Uri> ->
        for (u in uris) if (photos.size < 3) Feedback.photoBase64(context, u)?.let { photos += it }
    }
    LaunchedEffect(id) {
        Feedback.refresh()
        Feedback.markSeen(id)
    }
    if (report == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(AppState.t("fb_mine"), nav)
            MessageBox(AppState.t("fb_gone"))
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(AppState.t("fb_mine"), nav)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(report.messages) { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (m.status != null) {
                        Text(AppState.t("fb_status_line").replace("{s}", AppState.t("fb_status_${m.status}")) + " · " +
                                 Format.shortDate(m.at), color = TU.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        Column(
                            Modifier.align(if (m.fromTeam) Alignment.Start else Alignment.End).clip(RoundedCornerShape(12.dp))
                                .background(if (m.fromTeam) TU.surface else TU.primaryDim).padding(10.dp),
                        ) {
                            if (m.text.isNotEmpty()) Text(m.text, color = TU.text, fontSize = 14.sp)
                            for (n in m.photos) {
                                AsyncImage(model = Feedback.photoUrl(report, n), contentDescription = null, contentScale = ContentScale.Fit,
                                           modifier = Modifier.padding(top = 4.dp).size(200.dp))
                            }
                            Text((if (m.fromTeam) AppState.t("fb_team") else AppState.t("fb_you")) + " · " + Format.shortDate(m.at),
                                 color = TU.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (report.closed || report.full) {
            Text(AppState.t(if (report.closed) "fb_closed_note" else "fb_full_note"), color = TU.muted, fontSize = 13.sp,
                 modifier = Modifier.fillMaxWidth().background(TU.surface).padding(12.dp))
        } else {
            Column(Modifier.fillMaxWidth().background(TU.surface).padding(8.dp)) {
                error?.let { Text(AppState.t(it), color = TU.danger, fontSize = 12.sp) }
                OutlinedTextField(
                    value = reply, onValueChange = { if (it.length <= 2000) reply = it },
                    placeholder = { Text(AppState.t("fb_reply_ph"), color = TU.muted) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                              focusedTextColor = TU.text, unfocusedTextColor = TU.text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(AppState.t("fb_add_photos") + if (photos.isNotEmpty()) " (${photos.size})" else "", color = TU.text)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            sending = true
                            error = null
                            scope.launch {
                                val e = Feedback.reply(id, reply.trim(), photos.toList())
                                sending = false
                                error = e
                                if (e == null) { reply = ""; photos.clear() }
                            }
                        },
                        enabled = !sending && (reply.isNotBlank() || photos.isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                    ) { Text(AppState.t("fb_reply_send")) }
                }
            }
        }
    }
}
