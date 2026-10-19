package com.mxfia19.twitchunblock.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.web.ClipCreator
import com.mxfia19.twitchunblock.web.ClipOutcome
import com.mxfia19.twitchunblock.web.WebSession

/** Position de la lecture en secondes entières (lien partagé d'une VOD). */
fun shareSeconds(session: PlaybackSession): Long = maxOf(0L, session.player.currentPosition / 1000)

/** Lien partagé depuis le menu du lecteur : la VOD (ou le rembobinage d'un
 *  direct) à la position en cours, la chaîne d'un direct, le clip. null :
 *  VOD téléchargée ou retrouvée (pas de page Twitch). Comme l'iPhone. */
fun shareUrl(session: PlaybackSession): String? = when (val r = session.request) {
    is PlayRequest.Live -> "https://www.twitch.tv/" + r.login.lowercase()
    is PlayRequest.Vod -> {
        val t = shareSeconds(session)
        "https://www.twitch.tv/videos/${r.id}?t=${t / 3600}h${(t % 3600) / 60}m${t % 60}s"
    }
    is PlayRequest.Clip -> "https://clips.twitch.tv/" + r.slug
    else -> null
}

/** Feuille de partage d'Android avec un lien. */
fun shareViaSheet(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    val chooser = Intent.createChooser(send, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        Logger.warn("SHARE", "Partage impossible", e.message)
    }
}

/** Fenêtre du clip (lecteur) : création en cours, puis le lien (copier,
 *  partager), l'erreur (réessayer) ou la session web à connecter — le clip
 *  part alors au même instant, au retour de la connexion. */
@Composable
fun ClipDialog(nav: Navigator) {
    val state = ClipCreator.state
    val outcome = (state as? ClipCreator.State.Finished)?.outcome
    // Session web connectée depuis cette fenêtre : le clip part.
    LaunchedEffect(outcome, WebSession.generation) {
        if (outcome == ClipOutcome.NeedsSession && WebSession.active) ClipCreator.retry()
    }
    if (state == ClipCreator.State.Idle) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(outcome) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { ClipCreator.close() },
        title = { Text(if (outcome is ClipOutcome.Created) AppState.t("clip_done") else AppState.t("clip_create")) },
        text = {
            when (outcome) {
                null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = TU.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text(AppState.t("clip_working"))
                }
                is ClipOutcome.Created -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(outcome.url, color = TU.text, fontWeight = FontWeight.SemiBold)
                    Text(AppState.t("clip_done_sub"), fontSize = 13.sp)
                    if (copied) Text(AppState.t("copied"), color = TU.primary, fontSize = 13.sp)
                }
                else -> Text(clipError(outcome))
            }
        },
        confirmButton = {
            when (outcome) {
                null -> {}
                is ClipOutcome.Created -> Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(outcome.url))
                        copied = true
                    }) { Text(AppState.t("copy_link"), color = TU.primary) }
                    TextButton(onClick = { shareViaSheet(context, outcome.url) }) { Text(AppState.t("share"), color = TU.primary) }
                }
                ClipOutcome.NeedsSession -> TextButton(onClick = { nav.push(Screen.WebLogin) }) {
                    Text(AppState.t("clip_connect"), color = TU.primary)
                }
                else -> TextButton(onClick = { ClipCreator.retry() }) { Text(AppState.t("retry"), color = TU.primary) }
            }
        },
        dismissButton = {
            TextButton(onClick = { ClipCreator.close() }) { Text(AppState.t("close"), color = TU.text) }
        },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.muted,
    )
}

/** Texte d'une issue sans clip. */
private fun clipError(outcome: ClipOutcome?): String = when (outcome) {
    ClipOutcome.NeedsSession -> AppState.t("clip_needs_web")
    ClipOutcome.Offline -> AppState.t("clip_err_offline")
    ClipOutcome.Disabled -> AppState.t("clip_err_disabled")
    ClipOutcome.RateLimited -> AppState.t("clip_err_rate")
    is ClipOutcome.Refused -> AppState.t("clip_err_refused").replace("{c}", outcome.code)
    else -> AppState.t("clip_err_network")
}
