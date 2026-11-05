package com.mxfia19.twitchunblock.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Fichiers enregistrés hors de l'app (série 6) : clip téléchargé, VOD
 * exportée. « Enregistrer sous » du système (Storage Access Framework) :
 * aucune autorisation, l'endroit au choix (Téléchargements, Drive, carte SD…).
 */
object MediaFiles {
    /** Copies en cours : elles continuent quand on quitte le lecteur. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Nom de fichier sans caractère refusé par les systèmes de fichiers. */
    fun fileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "TwitchUnblock" }

    /**
     * Fenêtre « Enregistrer sous » ; `onPicked` reçoit le fichier choisi
     * (rien si on annule). Enregistrée à part sur l'activité et défaite
     * après la réponse : appelable depuis un menu qui se ferme aussitôt.
     */
    fun saveAs(context: Context, name: String, mime: String, onPicked: (Uri) -> Unit): Boolean {
        val activity = context.findActivity() ?: return false
        val holder = arrayOfNulls<ActivityResultLauncher<String>>(1)
        val launcher = activity.activityResultRegistry.register(
            "tu_save_" + SystemClock.elapsedRealtimeNanos(),
            ActivityResultContracts.CreateDocument(mime),
        ) { uri ->
            holder[0]?.unregister()
            if (uri != null) onPicked(uri)
        }
        holder[0] = launcher
        return try {
            launcher.launch(name)
            true
        } catch (e: Exception) {
            launcher.unregister()
            Logger.warn("FILES", "Fenêtre d'enregistrement indisponible", e.message)
            toast(context, com.mxfia19.twitchunblock.core.AppState.t("m6_save_unavailable"))
            false
        }
    }

    /** Télécharge `url` dans `target` (fichier choisi) ; faux en échec. */
    suspend fun copyUrl(context: Context, url: String, target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
            Http.client.newCall(request).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) {
                    Logger.warn("FILES", "Téléchargement refusé", "HTTP ${r.code}")
                    return@withContext false
                }
                val out = context.contentResolver.openOutputStream(target, "w") ?: return@withContext false
                out.use { body.byteStream().copyTo(it, 64 * 1024) }
            }
            true
        } catch (e: Exception) {
            Logger.warn("FILES", "Téléchargement interrompu", e.message)
            false
        }
    }

    fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}

/** Activité derrière un contexte de Compose (LocalContext). */
fun Context.findActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}
