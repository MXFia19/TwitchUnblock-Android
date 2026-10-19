package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Lang
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.core.Backup
import com.mxfia19.twitchunblock.core.CloudSync
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.TopLanguage
import com.mxfia19.twitchunblock.core.WordsPref
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.web.WebSession
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: Navigator) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var backupMessage by remember { mutableStateOf<String?>(null) }
    // Déconnexion avec une session web connectée : son retrait est proposé.
    var askLogout by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupMessage = try {
            context.contentResolver.openOutputStream(target)?.use { it.write(Backup.export().toByteArray(Charsets.UTF_8)) }
            AppState.t("export_done")
        } catch (e: Exception) {
            Logger.warn("BACKUP", "Export impossible", e.message)
            AppState.t("err_network")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        val text = try {
            context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        val result = text?.let { Backup.import(it) }
        backupMessage = if (result == null) AppState.t("import_bad") else {
            AppState.t("import_done").replace("%d", result.first.toString()) +
                if (result.second > 0) " " + AppState.t("import_capped").replace("%d", result.second.toString()) else ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("settings"), nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Group(AppState.t("tv_account")) {
                val user = AppState.user
                if (user != null) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(user.avatar, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.displayName, color = TU.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(AppState.t("account_api_sub"), color = TU.muted, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { if (WebSession.token != null) askLogout = true else Auth.logout() }) {
                            Text(AppState.t("tv_logout"), color = TU.text)
                        }
                    }
                } else {
                    Column(Modifier.padding(12.dp)) {
                        Text(AppState.t("login_intro"), color = TU.muted, fontSize = 13.sp)
                        Button(onClick = { nav.push(Screen.Login) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                               modifier = Modifier.padding(top = 8.dp)) { Text(AppState.t("tv_login")) }
                    }
                }
                WebSessionRow(nav)
            }
            Group(AppState.t("settings_twitch_extras")) {
                LinkRow(AppState.t("drops") + " · " + AppState.t("drops_sub")) { nav.push(Screen.Drops) }
                LinkRow(AppState.t("points_tracker") + " · " + AppState.t("points_tracker_sub")) { nav.push(Screen.PointsTracker) }
                Toggle(AppState.t("auto_claim"), AppState.t("auto_claim_sub"), Features.autoClaimChest.value) { Features.autoClaimChest.set(it) }
                Toggle(AppState.t("sub_adfree"), AppState.t("sub_adfree_sub"), Features.subAdFree.value) { Features.subAdFree.set(it) }
            }
            Group(AppState.t("settings_general")) {
                Choice(AppState.t("language"), Lang.entries.map { it.code to it.label }, AppState.lang.code) { code ->
                    Lang.of(code)?.let { AppState.updateLang(it) }
                }
                Choice(AppState.t("top_lang"),
                       listOf("" to AppState.t("lang_auto") + " (" + TopLanguage.name(TopLanguage.device) + ")") +
                           TopLanguage.codes.map { it to TopLanguage.name(it) }.sortedBy { it.second.lowercase() },
                       Features.topLang ?: "",
                       AppState.t("top_lang_sub").replace("{l}", TopLanguage.name(TopLanguage.device))) {
                    Features.updateTopLang(it.ifEmpty { null })
                }
                Toggle(AppState.t("show_recent"), AppState.t("show_recent_sub"), Features.showRecent.value) { Features.showRecent.set(it) }
                LinkRow(AppState.t("hidden_title") + if (Hidden.isEmpty) "" else " (${Hidden.channels.size + Hidden.categories.size})") {
                    nav.push(Screen.Hidden)
                }
            }
            Group(AppState.t("settings_player")) {
                Choice(AppState.t("pref_quality"), Quality.PREFS.map { it to Quality.prefLabel(it, AppState.lang) },
                       AppState.qualityPref) { AppState.updateQualityPref(it) }
                Choice(AppState.t("cellular_quality"), Quality.CELLULAR_PREFS.map { it to Quality.prefLabel(it, AppState.lang) },
                       AppState.cellularQuality, AppState.t("cellular_quality_sub")) { AppState.updateCellularQuality(it) }
                Choice(AppState.t("live_source"), Config.LIVE_SOURCE_CHOICES.map {
                    it to if (it == "auto") AppState.t("source_auto") else sourceName(it)
                }, AppState.liveSource) { AppState.updateLiveSource(it) }
                Toggle(AppState.t("low_latency"), AppState.t("low_latency_sub"), AppState.lowLatency) { AppState.updateLowLatency(it) }
                Toggle(AppState.t("tv_chat_show"), null, AppState.showChat) { AppState.updateShowChat(it) }
                Toggle(AppState.t("background_play"), AppState.t("background_play_sub"), AppState.backgroundPlay) { AppState.updateBackgroundPlay(it) }
                Toggle(AppState.t("auto_pip"), AppState.t("auto_pip_sub"), AppState.autoPip) { AppState.updateAutoPip(it) }
                Toggle(AppState.t("dl_wifi_only"), AppState.t("dl_wifi_only_sub"), Features.dlWifiOnly.value) {
                    Downloads.updateWifiOnly(it)
                }
            }
            Group(AppState.t("settings_chat")) {
                Toggle(AppState.t("cfg_timestamps"), AppState.t("cfg_timestamps_sub"), Features.chatTimestamps.value) { Features.chatTimestamps.set(it) }
                Toggle(AppState.t("cfg_deleted"), AppState.t("cfg_deleted_sub"), Features.chatKeepDeleted.value) { Features.chatKeepDeleted.set(it) }
                Toggle(AppState.t("cfg_recent"), AppState.t("cfg_recent_sub"), Features.chatLoadHistory.value) { Features.chatLoadHistory.set(it) }
                Toggle(AppState.t("hide_bots"), AppState.t("hide_bots_sub"), Features.chatHideBots.value) { Features.chatHideBots.set(it) }
                Toggle(AppState.t("hide_commands"), AppState.t("hide_commands_sub"), Features.chatHideCommands.value) { Features.chatHideCommands.set(it) }
                WordsField(AppState.t("muted_words"), AppState.t("muted_words_sub"), Features.mutedWords)
                WordsField(AppState.t("highlight_words"), AppState.t("highlight_words_sub"), Features.highlightWords)
                if (Features.blockedUsers.value.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(AppState.t("hidden_users").replace("{n}", Features.blockedUsers.value.size.toString()), color = TU.text,
                             fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Features.blockedUsers.set(emptyList()) }) { Text(AppState.t("clear"), color = TU.danger) }
                    }
                }
                Column(Modifier.padding(12.dp)) {
                    Text(AppState.t("cfg_font_size") + " · " + Features.chatFontSize.value, color = TU.text, fontSize = 15.sp)
                    Slider(
                        value = Features.chatFontSize.value.toFloat(),
                        onValueChange = { Features.chatFontSize.set(Math.round(it)) },
                        valueRange = Features.chatFontSize.range.first.toFloat()..Features.chatFontSize.range.last.toFloat(),
                        steps = Features.chatFontSize.range.last - Features.chatFontSize.range.first - 1,
                        colors = SliderDefaults.colors(thumbColor = TU.primary, activeTrackColor = TU.primary),
                    )
                }
            }
            Group(AppState.t("settings_events")) {
                Toggle(AppState.t("cfg_pinned"), AppState.t("cfg_pinned_sub"), Features.showPinned.value) { Features.showPinned.set(it) }
                Toggle(AppState.t("cfg_events"), AppState.t("cfg_events_sub"), Features.showLiveEvents.value) { Features.showLiveEvents.set(it) }
                Toggle(AppState.t("cfg_raids"), AppState.t("cfg_raids_sub"), Features.enableRaids.value) { Features.enableRaids.set(it) }
                Toggle(AppState.t("cfg_streak"), AppState.t("cfg_streak_sub"), Features.showWatchStreak.value) { Features.showWatchStreak.set(it) }
            }
            NotificationSettings()
            Group(AppState.t("backup")) {
                Text(AppState.t("backup_sub"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                    OutlinedButton(onClick = { exporter.launch(Backup.fileName()) }) { Text(AppState.t("export_data"), color = TU.text) }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text(AppState.t("import_data"), color = TU.text)
                    }
                }
                backupMessage?.let { Text(it, color = TU.primary, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) }
                Toggle(AppState.t("cloud_sync"), AppState.t("cloud_sync_sub"), Features.cloudSync.value) {
                    Features.cloudSync.set(it)
                    if (it) CloudSync.syncNow()
                }
                CloudSync.lastSync?.let { at ->
                    Text(AppState.t("cloud_sync_last").replace("{t}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))),
                         color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
                }
            }
            Group(AppState.t("expert_worker")) {
                WorkerField()
            }
            Group(AppState.t("about")) {
                Text("TwitchUnblock ${BuildConfig.VERSION_NAME}" + if (BuildConfig.CHANNEL == "test") " · TU Test" else "",
                     color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                LinkRow(AppState.t("open_github")) { uri.openUri("https://github.com/${Config.REPO}/releases") }
                LinkRow("Discord") { uri.openUri(Config.DISCORD_URL) }
                LinkRow(AppState.t("feedback")) { nav.push(Screen.Feedback) }
                LinkRow(AppState.t("logs")) { nav.push(Screen.Logs) }
            }
        }
    }
    if (askLogout) {
        AlertDialog(
            onDismissRequest = { askLogout = false },
            title = { Text(AppState.t("tv_logout")) },
            text = { Text(AppState.t("logout_web_too")) },
            confirmButton = {
                TextButton(onClick = { askLogout = false; Auth.logout(); WebSession.clear() }) {
                    Text(AppState.t("web_logout"), color = TU.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { askLogout = false; Auth.logout() }) { Text(AppState.t("tv_logout"), color = TU.text) }
            },
            containerColor = TU.surface,
            titleContentColor = TU.text,
            textContentColor = TU.muted,
        )
    }
}

/** Session web : état, connexion, déconnexion ; compte différent signalé. */
@Composable
private fun WebSessionRow(nav: Navigator) {
    val mismatch = WebSession.mismatch
    if (mismatch != null) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(AppState.t("web_mismatch"), color = TU.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(AppState.t("web_mismatch_sub").replace("{w}", mismatch).replace("{a}", AppState.user?.login ?: ""),
                 color = TU.muted, fontSize = 12.sp)
            TextButton(onClick = { WebSession.dismissMismatch() }) { Text(AppState.t("close"), color = TU.primary) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(AppState.t("account_web"), color = TU.text, fontSize = 15.sp)
            val status = when {
                WebSession.token == null -> AppState.t("account_web_sub")
                WebSession.expired -> AppState.t("web_expired")
                else -> AppState.t("web_connected_as").replace("{u}", WebSession.login ?: "?")
            }
            Text(status, color = if (WebSession.expired) TU.danger else TU.muted, fontSize = 12.sp)
        }
        if (WebSession.token == null || WebSession.expired) {
            Button(onClick = { nav.push(Screen.WebLogin) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                Text(AppState.t(if (WebSession.expired) "web_reconnect" else "web_banner_btn"))
            }
        } else {
            OutlinedButton(onClick = { WebSession.clear() }) { Text(AppState.t("web_logout"), color = TU.text) }
        }
    }
}

/** Liste de mots séparés par des virgules (enregistrée à chaque frappe). */
@Composable
private fun WordsField(title: String, subtitle: String, pref: WordsPref) {
    var text by remember { mutableStateOf(pref.text) }
    Column(Modifier.padding(12.dp)) {
        Text(title, color = TU.text, fontSize = 15.sp)
        Text(subtitle, color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                pref.setText(it)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), color = TU.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(TU.surface)) {
        content()
    }
}

@Composable
fun Choice(title: String, options: List<Pair<String, String>>, selected: String, subtitle: String? = null,
           onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().focusCard({ open = true }, RoundedCornerShape(0.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TU.text, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, color = TU.muted, fontSize = 12.sp)
            }
            Text(options.firstOrNull { it.first == selected }?.second ?: selected, color = TU.primary, fontSize = 14.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(label + if (value == selected) "  ✓" else "") },
                                 onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().focusCard({ onChange(!checked) }, RoundedCornerShape(0.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TU.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = TU.muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
               colors = SwitchDefaults.colors(checkedTrackColor = TU.primary))
    }
}

@Composable
fun LinkRow(text: String, onClick: () -> Unit) {
    Text(text, color = TU.primary, fontSize = 15.sp,
         modifier = Modifier.fillMaxWidth().focusCard(onClick, RoundedCornerShape(0.dp)).padding(12.dp))
}

/** Worker personnel : essayé en premier pour la lecture. */
@Composable
private fun WorkerField() {
    var text by remember { mutableStateOf(AppState.customWorker ?: "") }
    // Trafic en clair bloqué par Android : une adresse en http:// échouait
    // sans rien dire. Refusée, avec la raison.
    var http by remember { mutableStateOf(false) }
    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("expert_worker_sub"), color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("https://…workers.dev", color = TU.muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (http) {
            Text(AppState.t("expert_http_android"), color = TU.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = {
                http = text.trim().lowercase().startsWith("http://")
                if (!http) {
                    AppState.updateCustomWorker(normalizeWorker(text))
                    text = AppState.customWorker ?: ""
                }
            }) {
                Text(AppState.t("save"), color = TU.primary)
            }
            if (AppState.customWorker != null) {
                TextButton(onClick = { AppState.updateCustomWorker(null); text = ""; http = false }) { Text(AppState.t("remove"), color = TU.danger) }
            }
        }
    }
}

/** « https:// » ajouté, « / » et « /api » finaux ôtés ; null si ce n'est pas une adresse. */
fun normalizeWorker(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    if (!s.lowercase().startsWith("http://") && !s.lowercase().startsWith("https://")) s = "https://$s"
    s = s.trimEnd('/')
    if (s.lowercase().endsWith("/api")) s = s.dropLast(4)
    val host = try { java.net.URI(s).host } catch (e: Exception) { null }
    return if (host.isNullOrEmpty()) null else s
}

/** QR code d'un lien (connexion sur la TV). */
fun qrBitmap(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp.asImageBitmap()
}

/** Connexion par code : le code, et le lien qui le préremplit. */
@Composable
fun LoginPanel(onDone: () -> Unit, showQr: Boolean) {
    val uri = LocalUriHandler.current
    var code by remember { mutableStateOf<Auth.DeviceCode?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        code = null
        val c = Auth.requestCode()
        if (c == null) {
            failed = true
            return@LaunchedEffect
        }
        code = c
        if (Auth.waitForApproval(c)) onDone() else failed = true
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(AppState.t("tv_login_intro"), color = TU.muted, fontSize = 14.sp)
        val c = code
        when {
            failed -> {
                Text(AppState.t("tv_login_expired"), color = TU.danger, fontSize = 14.sp)
                Button(onClick = { attempt++ }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                    Text(AppState.t("retry"))
                }
            }
            c == null -> CircularProgressIndicator(color = TU.primary)
            else -> {
                if (showQr) {
                    val qr = remember(c.verificationUri) { qrBitmap(c.verificationUri, 360) }
                    Image(qr, contentDescription = null, modifier = Modifier.size(180.dp))
                }
                Text(AppState.t("tv_login_steps") + " twitch.tv/activate " + AppState.t("tv_login_code"),
                     color = TU.text, fontSize = 14.sp)
                Text(c.userCode, color = TU.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (!showQr) {
                    Button(onClick = { uri.openUri(c.verificationUri) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                        Text(AppState.t("open_activate"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(AppState.t("tv_login_waiting"), color = TU.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun LoginScreen(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("tv_login"), nav)
        LoginPanel(onDone = { nav.pop() }, showQr = false)
    }
}

@Composable
fun LogsScreen(nav: Navigator) {
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf(Logger.snapshot().reversed()) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("logs"), nav) {
            TextButton(onClick = {
                val text = Logger.snapshot().joinToString("\n") {
                    "${time.format(Date(it.time))} ${it.level.mark} [${it.tag}] ${it.message}" + (it.detail?.let { d -> " — $d" } ?: "")
                }
                clipboard.setText(AnnotatedString("TwitchUnblock ${BuildConfig.VERSION_NAME} (Android)\n$text"))
            }) { Text(AppState.t("btn_copy"), color = TU.primary) }
            TextButton(onClick = { Logger.clear(); entries = emptyList() }) { Text(AppState.t("logs_clear_btn"), color = TU.danger) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { e ->
                val color = when (e.level) {
                    Logger.Level.ERROR -> TU.danger
                    Logger.Level.WARN -> androidx.compose.ui.graphics.Color(0xFFFFB020)
                    Logger.Level.SUCCESS -> TU.success
                    else -> TU.text
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("${time.format(Date(e.time))} ${e.level.mark} [${e.tag}] ${e.message}", color = color, fontSize = 12.sp)
                    e.detail?.let { Text(it, color = TU.muted, fontSize = 11.sp) }
                }
            }
        }
    }
}
