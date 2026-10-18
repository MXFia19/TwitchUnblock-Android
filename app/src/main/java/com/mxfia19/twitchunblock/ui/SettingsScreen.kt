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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: Navigator) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("settings"), nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Group(AppState.t("tv_account")) {
                val user = AppState.user
                if (user != null) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(user.avatar, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(user.displayName, color = TU.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { Auth.logout() }) { Text(AppState.t("tv_logout"), color = TU.text) }
                    }
                } else {
                    Column(Modifier.padding(12.dp)) {
                        Text(AppState.t("login_intro"), color = TU.muted, fontSize = 13.sp)
                        Button(onClick = { nav.push(Screen.Login) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                               modifier = Modifier.padding(top = 8.dp)) { Text(AppState.t("tv_login")) }
                    }
                }
            }
            Group(AppState.t("settings_general")) {
                Choice(AppState.t("language"), Lang.entries.map { it.code to it.label }, AppState.lang.code) { code ->
                    Lang.of(code)?.let { AppState.updateLang(it) }
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
            }
            Group(AppState.t("expert_worker")) {
                WorkerField()
            }
            Group(AppState.t("about")) {
                Text("TwitchUnblock ${BuildConfig.VERSION_NAME}" + if (BuildConfig.CHANNEL == "test") " · TU Test" else "",
                     color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                LinkRow(AppState.t("open_github")) { uri.openUri("https://github.com/${Config.REPO}/releases") }
                LinkRow("Discord") { uri.openUri(Config.DISCORD_URL) }
                LinkRow(AppState.t("logs")) { nav.push(Screen.Logs) }
            }
        }
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
private fun LinkRow(text: String, onClick: () -> Unit) {
    Text(text, color = TU.primary, fontSize = 15.sp,
         modifier = Modifier.fillMaxWidth().focusCard(onClick, RoundedCornerShape(0.dp)).padding(12.dp))
}

/** Worker personnel : essayé en premier pour la lecture. */
@Composable
private fun WorkerField() {
    var text by remember { mutableStateOf(AppState.customWorker ?: "") }
    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("expert_worker_sub"), color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("https://…workers.dev", color = TU.muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = { AppState.updateCustomWorker(normalizeWorker(text)); text = AppState.customWorker ?: "" }) {
                Text(AppState.t("save"), color = TU.primary)
            }
            if (AppState.customWorker != null) {
                TextButton(onClick = { AppState.updateCustomWorker(null); text = "" }) { Text(AppState.t("remove"), color = TU.danger) }
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
