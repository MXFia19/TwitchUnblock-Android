package com.mxfia19.twitchunblock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Backup
import com.mxfia19.twitchunblock.core.CloudSync
import com.mxfia19.twitchunblock.core.CustomWorker
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Updates
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Réglages de la série 6 qui restent à leur place dans les Réglages :
 * Worker personnel (vérifié, « Tout passer par ce Worker »), Sauvegarde
 * (import « Tout » ou « Chaînes seulement » et son résumé) et À propos
 * (journal des modifications, revoir le tutoriel).
 */

/** Worker personnel : l'adresse est vérifiée avant d'être gardée (la raison
 *  d'un échec est dite), puis « Tout passer par ce Worker ». Comme la carte
 *  des réglages experts de l'iPhone. */
@Composable
fun CustomWorkerField() {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(AppState.customWorker ?: "") }
    var checking by remember { mutableStateOf(false) }
    /** Clé du message de la dernière vérification, et l'adresse vérifiée. */
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var resultFor by remember { mutableStateOf<String?>(null) }
    var noDatabase by remember { mutableStateOf(false) }
    val current = AppState.customWorker

    fun verify() {
        // Une vérification à la fois : la plus lente remettait son adresse.
        if (checking) return
        val raw = text.trim()
        // Trafic en clair bloqué par Android : refusé avec la raison.
        if (raw.lowercase().startsWith("http://")) {
            result = "expert_http_android"
            resultOk = false
            resultFor = normalizeWorker(raw)
            return
        }
        val base = normalizeWorker(raw)
        if (base == null) {
            result = "expert_bad"
            resultOk = false
            resultFor = null
            return
        }
        checking = true
        result = null
        scope.launch {
            val r = CustomWorker.check(base)
            checking = false
            // Adresse changée pendant la vérification : résultat périmé.
            if (normalizeWorker(text) != base) return@launch
            resultFor = base
            result = r.messageKey
            resultOk = r == CustomWorker.Check.OK
            if (r != CustomWorker.Check.OK) {
                Logger.warn("SETTINGS", "Worker personnel refusé", "${r.name.lowercase()} · $base")
                return@launch
            }
            AppState.updateCustomWorker(base)
            text = base
            noDatabase = false
            Logger.info("SETTINGS", "Worker personnel", base)
            // « Tout passer par ce Worker » déjà actif : le nouveau doit avoir sa base D1.
            if (CustomWorker.all.value && CustomWorker.hasDatabase(base) == false && AppState.customWorker == base) {
                CustomWorker.all.set(false)
                noDatabase = true
            }
        }
    }

    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("expert_worker_sub"), color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Résultat d'une autre adresse : effacé dès qu'on la change.
                if (!checking && normalizeWorker(it) != resultFor) result = null
            },
            singleLine = true,
            placeholder = { Text(AppState.t("expert_worker_ph"), color = TU.muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { verify() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = { verify() }, enabled = !checking && text.isNotBlank()) {
                if (checking) {
                    CircularProgressIndicator(color = TU.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(AppState.t("expert_check"), color = TU.primary)
            }
            if (current != null) {
                TextButton(onClick = {
                    AppState.updateCustomWorker(null)
                    CustomWorker.all.set(false)
                    text = ""
                    result = null
                    noDatabase = false
                    Logger.info("SETTINGS", "Worker personnel", "officiels")
                }) { Text(AppState.t("expert_reset"), color = TU.danger) }
            }
        }
        result?.let { Text(AppState.t(it), color = if (resultOk) TU.success else TU.danger, fontSize = 12.sp) }
        // Le Worker en service reste dit après l'échec d'une autre adresse.
        if (current != null && !resultOk) {
            Text(AppState.t("expert_using").replace("{u}", current), color = TU.muted, fontSize = 12.sp,
                 modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (current != null) {
        Toggle(AppState.t("expert_all"), AppState.t("expert_all_sub"), CustomWorker.all.value) { on ->
            CustomWorker.all.set(on)
            noDatabase = false
            Logger.info("SETTINGS", "Tout passer par le Worker personnel", if (on) "oui" else "non")
            if (!on) return@Toggle
            // Sans base D1, sauvegarde, retours et annonces y échoueraient en
            // silence : l'option reste coupée.
            scope.launch {
                if (CustomWorker.hasDatabase(current) == false && CustomWorker.all.value && AppState.customWorker == current) {
                    CustomWorker.all.set(false)
                    noDatabase = true
                    Logger.warn("SETTINGS", "Worker personnel sans base D1", current)
                }
            }
        }
        if (noDatabase) {
            Text(AppState.t("expert_all_no_db"), color = TU.danger, fontSize = 12.sp,
                 modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
        }
    }
}

/** Groupe Sauvegarde : export (avec les chaînes suivies du compte), import
 *  « Tout » ou « Chaînes seulement » suivi de ce qu'il a repris, et la
 *  synchro de l'historique. Fichiers lus et écrits hors du fil principal
 *  (un document Drive pas en cache figeait l'écran). */
@Composable
fun BackupRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Backup.Pending?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            try {
                val text = Backup.export(Backup.accountFollows())
                message = AppState.t(if (Backup.writeTo(context, target, text)) "export_done" else "err_network")
            } finally {
                exporting = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = Backup.readFrom(context, source)?.let { Backup.read(it) }
            if (read == null) {
                message = AppState.t("import_bad")
            } else {
                message = null
                pending = read
            }
        }
    }

    fun applyImport(file: Backup.Pending, scopeOf: Backup.Scope) {
        pending = null
        val s = Backup.apply(file, scopeOf)
        // Dans la langue que l'import a peut-être changée.
        val text = importSummary(s, scopeOf)
        message = text
        summary = text
        if (scopeOf == Backup.Scope.ALL) {
            Logger.info("BACKUP", "Import : ${s.follows} chaîne(s) ajoutée(s)",
                        "tout · ${s.settings} réglage(s) repris, ${s.settingsIgnored} ignoré(s), ${s.continueWatching} vidéo(s) à reprendre")
        } else {
            Logger.info("BACKUP", "Import : ${s.follows} chaîne(s) ajoutée(s)", "chaînes seulement")
        }
    }

    Text(AppState.t("backup_sub"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(12.dp)) {
        OutlinedButton(onClick = { exporter.launch(Backup.fileName()) }, enabled = !exporting) { Text(AppState.t("export_data"), color = TU.text) }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
            Text(AppState.t("import_data"), color = TU.text)
        }
        if (exporting) CircularProgressIndicator(color = TU.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
    message?.let { Text(it, color = TU.primary, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) }
    Toggle(AppState.t("cloud_sync"), AppState.t("cloud_sync_sub"), Features.cloudSync.value) {
        Features.cloudSync.set(it)
        if (it) CloudSync.syncNow()
    }
    CloudSync.lastSync?.let { at ->
        Text(AppState.t("cloud_sync_last").replace("{t}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))),
             color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
    }

    pending?.let { file ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(AppState.t("import_choice_title")) },
            text = { Text(AppState.t("import_choice_msg")) },
            confirmButton = {
                TextButton(onClick = { applyImport(file, Backup.Scope.ALL) }) { Text(AppState.t("import_all"), color = TU.primary) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pending = null }) { Text(AppState.t("cancel"), color = TU.muted) }
                    TextButton(onClick = { applyImport(file, Backup.Scope.CHANNELS_ONLY) }) {
                        Text(AppState.t("import_channels_only"), color = TU.primary)
                    }
                }
            },
            containerColor = TU.surface,
            titleContentColor = TU.text,
            textContentColor = TU.muted,
        )
    }
    summary?.let { text ->
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text(AppState.t("import_sum_title")) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { summary = null }) { Text(AppState.t("close"), color = TU.primary) } },
            containerColor = TU.surface,
            titleContentColor = TU.text,
            textContentColor = TU.text,
        )
    }
}

/** Une ligne par sorte de chose reprise, comme l'iPhone. */
private fun importSummary(s: Backup.Summary, scope: Backup.Scope): String {
    var follows = AppState.t("import_sum_follows").replace("{n}", s.follows.toString())
    // Au-delà de 300 suivis sur l'appareil, le reste n'est pas pris : dit.
    if (s.followsSkipped > 0) follows += " " + AppState.t("import_capped").replace("%d", s.followsSkipped.toString())
    val lines = mutableListOf(
        follows,
        AppState.t("import_sum_categories").replace("{n}", s.categories.toString()),
        AppState.t("import_sum_pinned").replace("{n}", s.pinned.toString()),
        AppState.t("import_sum_hidden").replace("{n}", s.hidden.toString()),
    )
    if (scope == Backup.Scope.ALL) {
        lines += AppState.t("import_sum_settings").replace("{n}", s.settings.toString())
        if (s.settingsIgnored > 0) lines += AppState.t("import_sum_ignored").replace("{n}", s.settingsIgnored.toString())
        lines += AppState.t("import_sum_continue").replace("{n}", s.continueWatching.toString())
    } else {
        lines += AppState.t("import_sum_channels_only")
    }
    return lines.joinToString("\n")
}

/** À propos : journal des modifications et visite guidée. */
@Composable
fun AboutExtraRows() {
    var changelog by remember { mutableStateOf(false) }
    LinkRow(AppState.t("changelog")) { changelog = true }
    LinkRow(AppState.t("ob_replay")) { Onboarding.replay() }
    if (changelog) ChangelogPage { changelog = false }
}

/** Journal des modifications : chaque version publiée du canal de l'app
 *  (GitHub Releases), avec ses nouveautés ; « Installée » et « Disponible »
 *  d'après le numéro de build. Comme ChangelogView (iOS). */
@Composable
fun ChangelogPage(onClose: () -> Unit) {
    var versions by remember { mutableStateOf<List<Updates.Release>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        versions = null
        val list = Updates.history()
        if (list.isNullOrEmpty()) failed = true else versions = list
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(TU.bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppState.t("back"), tint = TU.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(AppState.t("changelog"), color = TU.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(AppState.t("changelog_sub"), color = TU.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            val list = versions
            when {
                failed -> MessageBox(AppState.t("changelog_failed"), action = AppState.t("changelog_retry")) { attempt++ }
                list == null -> LoadingBox()
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(list, key = { it.code }) { v -> ReleaseCard(v) }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(v: Updates.Release) {
    val installed = BuildConfig.VERSION_CODE
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(TU.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            when {
                v.code == installed -> ReleaseBadge(AppState.t("changelog_installed"), TU.success)
                v.code > installed -> ReleaseBadge(AppState.t("changelog_available"), TU.primary)
            }
            Spacer(Modifier.weight(1f))
            v.date?.let { Text(Format.shortDate(it), color = TU.muted, fontSize = 12.sp) }
        }
        for (item in v.items) {
            Row {
                Text("•  ", color = TU.primary, fontSize = 13.sp)
                Text(item, color = TU.text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ReleaseBadge(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.15f))
             .padding(horizontal = 7.dp, vertical = 2.dp))
}
