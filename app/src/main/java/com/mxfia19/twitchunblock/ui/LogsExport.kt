package com.mxfia19.twitchunblock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal en texte, en français, la langue où il est écrit : il part surtout
 *  vers le développeur (signalements), qui le recevait traduit en anglais.
 *  L'écran du journal, lui, reste traduit (LogText). Comme l'iPhone. */
object LogExport {
    /** Partage : les dernières lignes seulement au-delà (un texte trop long
     *  ne passe pas dans l'intention de partage d'Android). */
    private const val SHARE_MAX = 300_000

    fun line(e: Logger.Entry, time: SimpleDateFormat): String =
        "${time.format(Date(e.time))} ${e.level.mark} [${e.tag}] ${e.message}" +
            (e.detail?.let { d -> " — $d" } ?: "")

    fun text(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val head = "TwitchUnblock ${BuildConfig.VERSION_NAME} (${BuildConfig.CHANNEL}) · Android ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
        return head + "\n" + Logger.snapshot().joinToString("\n") { line(it, time) }
    }

    fun fileName(): String = "twitchunblock-logs-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".txt"

    fun share(context: Context) {
        val all = text()
        val body = if (all.length > SHARE_MAX) all.takeLast(SHARE_MAX) else all
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, fileName())
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (e: Exception) {
            Logger.warn("SHARE", "Partage impossible", e.javaClass.simpleName)
        }
    }

    /** Fichier .txt choisi (Fichiers, Drive…), écrit hors du fil principal. */
    suspend fun write(context: Context, target: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(target)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            false
        }
    }
}

/** Menu du journal : copier, partager en texte, ou enregistrer un fichier .txt. */
@Composable
fun LogsExportMenu() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    // Texte pris au moment du choix, écrit une fois le fichier choisi.
    var pendingText by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        val body = pendingText
        scope.launch {
            val ok = LogExport.write(context, target, body)
            Toast.makeText(context, AppState.t(if (ok) "export_done" else "err_network"), Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Share, contentDescription = AppState.t("share"), tint = TU.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(AppState.t("btn_copy")) }, onClick = {
                open = false
                clipboard.setText(AnnotatedString(LogExport.text()))
            })
            DropdownMenuItem(text = { Text(AppState.t("logs_share")) }, onClick = {
                open = false
                LogExport.share(context)
            })
            DropdownMenuItem(text = { Text(AppState.t("logs_file")) }, onClick = {
                open = false
                pendingText = LogExport.text()
                saver.launch(LogExport.fileName())
            })
        }
    }
}
