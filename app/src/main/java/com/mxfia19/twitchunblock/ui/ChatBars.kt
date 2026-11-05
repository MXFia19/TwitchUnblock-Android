package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.mxfia19.twitchunblock.chat.Badges
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatColor
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.chat.ChatSync
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.LiveSyncEstimator
import com.mxfia19.twitchunblock.chat.RoomModes
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Barres et fenêtres du chat de la série 5 : lecture seule (banni), modes du
// salon et règles, synchro avec la vidéo, couleur du pseudo, aperçu des tailles.

/** Ambre des avertissements du chat (comme tWarning sur l'iPhone). */
private val Warning = Color(0xFFFBBF24)
/** Pastille de la synchro, quand elle s'applique. */
private val SyncTint = Color(0xFF5CE1E6)

/** Lecture seule : Twitch refuse le compte dans ce chat (banni), le chat
 *  continue sans lui. Twitch ne prévient pas d'un déban sur l'IRC, d'où le
 *  bouton qui retente avec le compte. */
@Composable
fun ReadOnlyBar(client: ChatClient, background: Color) {
    Row(Modifier.fillMaxWidth().background(background).background(Warning.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("🚫 " + AppState.t("chat_ban_banner"), color = Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(AppState.t("chat_ban_retry"), color = Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
             modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Warning.copy(alpha = 0.18f))
                 .clickable { client.retryWithAccount() }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Modes du salon qui limitent qui peut écrire, au-dessus du champ : on le
 *  sait avant d'écrire, plutôt qu'en voyant son message refusé. */
@Composable
fun RoomModesLine(m: RoomModes) {
    if (m.isEmpty) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        m.followersMinutes?.let { minutes ->
            ModeChip("👥", if (minutes > 0) AppState.t("chat_mode_followers_min").replace("{d}", RoomModes.duration(minutes))
                          else AppState.t("chat_mode_followers"))
        }
        if (m.subsOnly) ModeChip("★", AppState.t("chat_mode_subs"))
        if (m.emoteOnly) ModeChip("☺", AppState.t("chat_mode_emotes"))
        if (m.slowSeconds > 0) ModeChip("🐢", AppState.t("chat_mode_slow").replace("{s}", m.slowSeconds.toString()))
        if (m.uniqueChat) ModeChip("⧉", AppState.t("chat_mode_unique"))
        if (m.verifiedOnly) ModeChip("✓", AppState.t("chat_mode_verified"))
    }
}

@Composable
private fun ModeChip(icon: String, text: String) {
    Text("$icon $text", color = Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(Warning.copy(alpha = 0.14f))
             .padding(horizontal = 8.dp, vertical = 4.dp))
}

/** Règles de la chaîne, à accepter avant d'écrire (comme sur Twitch). */
@Composable
fun ChatRulesDialog(channel: String, rules: List<String>, onDone: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(AppState.t("chat_rules_title").replace("{c}", channel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (rule in rules) Text(rule, color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
        },
        confirmButton = {
            Button(onClick = { onDone(true) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                Text(AppState.t("chat_rules_accept"), color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(AppState.t("cancel"), color = TU.muted) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

// ── Synchro avec la vidéo ──

/**
 * Retard du chat sur l'image, mesuré sur le lecteur principal toutes les
 * 0,5 s pendant un direct (ChatSync) ; le chat de ce lecteur attend l'image.
 * En calage ou en pause, rien n'est envoyé : la dernière mesure vieillit.
 */
@Composable
fun ChatSyncFeeder(session: PlaybackSession, chat: ChatClient?) {
    DisposableEffect(chat) {
        chat?.syncsWithPlayer = true
        onDispose { }
    }
    LaunchedEffect(session) {
        val estimator = LiveSyncEstimator()
        try {
            while (true) {
                delay(500)
                val p = session.player
                val live = session.isLive && session.state is PlaybackSession.State.Ready && p.isCurrentMediaItemLive
                if (!live) {
                    estimator.reset()
                    ChatSync.reset()
                    continue
                }
                if (!p.isPlaying) continue
                val duration = p.duration
                if (duration == C.TIME_UNSET || duration <= 0) continue
                val behind = (duration - p.currentPosition) / 1000.0
                val offset = p.currentLiveOffset
                val value = estimator.offset(if (offset == C.TIME_UNSET) null else offset / 1000.0, behind)
                // Bien plus loin que le bord (enregistrement rejoué) : pas le
                // retard d'un direct, le chat reste en temps réel.
                if (value > 90) {
                    ChatSync.reset()
                    continue
                }
                ChatSync.update(value)
            }
        } finally {
            ChatSync.reset()
        }
    }
}

/** Pastille du chat quand un retard s'applique (« ⏱ −6 s »). Un toucher coupe
 *  la synchro (chat en temps réel : la pastille reste, barrée) ou la
 *  rallume ; un appui long retouche le retard d'une seconde. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSyncBadge(client: ChatClient, modifier: Modifier = Modifier) {
    if (!client.syncsWithPlayer) return
    val player = ChatSync.shownSeconds ?: return
    val nudge = ChatPrefs.syncNudge.value
    val seconds = ChatSync.chatDelay(player.toDouble(), nudge.toDouble()).roundToInt()
    // Moins d'une seconde sans retouche : rien à dire. Avec une retouche, elle
    // reste, pour pouvoir l'annuler.
    if (seconds < 1 && nudge == 0) return
    val on = ChatPrefs.sync.value
    var menu by remember { mutableStateOf(false) }
    val label = AppState.t(if (on) "chat_sync_on" else "chat_sync_off").replace("{s}", seconds.toString())
    Box(modifier) {
        Text("⏱ −$seconds s", color = if (on) SyncTint else TU.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
             textDecoration = if (on) TextDecoration.None else TextDecoration.LineThrough,
             modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                 .combinedClickable(onClick = {
                     ChatPrefs.sync.set(!on)
                     Logger.info("CHAT", "Chat synchronisé avec la vidéo", if (!on) "activé" else "désactivé")
                 }, onLongClick = { menu = true })
                 .semantics { contentDescription = label }
                 .padding(horizontal = 7.dp, vertical = 3.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(AppState.t("chat_sync_hint"), color = TU.muted, fontSize = 12.sp,
                 modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text(AppState.t("chat_sync_later")) }, onClick = { nudgeSync(1) })
            DropdownMenuItem(text = { Text(AppState.t("chat_sync_earlier")) }, onClick = { nudgeSync(-1) })
            if (nudge != 0) {
                DropdownMenuItem(text = { Text(AppState.t("chat_sync_reset")) }, onClick = {
                    menu = false
                    nudgeSync(0)
                })
            }
        }
    }
}

/** Retouche du retard : ±1 s, ou 0 pour revenir à la mesure seule. */
fun nudgeSync(step: Int) {
    val v = if (step == 0) 0 else (ChatPrefs.syncNudge.value + step).coerceIn(-20, 20)
    ChatPrefs.syncNudge.set(v)
    Logger.info("CHAT", "Retouche de la synchro du chat", (if (v > 0) "+" else "") + "$v s")
}

// ── Couleur du pseudo ──

/** Réglages → Chat : couleur du pseudo (compte connecté). */
@Composable
fun UsernameColorRow() {
    var open by remember { mutableStateOf(false) }
    val signedIn = AppState.user != null
    Row(Modifier.fillMaxWidth().focusCard({ if (signedIn) open = true }, RoundedCornerShape(0.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(AppState.t("menu_color"), color = if (signedIn) TU.text else TU.muted, fontSize = 15.sp)
            Text(AppState.t(if (signedIn) "menu_color_sub" else "menu_needs_login"), color = TU.muted, fontSize = 12.sp)
        }
        Text("›", color = TU.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) UsernameColorDialog { open = false }
}

/** Les couleurs nommées de Twitch ; un toucher l'applique (Helix). */
@Composable
private fun UsernameColorDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("menu_color")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (row in ChatColor.choices.chunked(2)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((name, argb) in row) {
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(TU.surfaceHi)
                                    .clickable(enabled = pending == null) {
                                        pending = name
                                        error = null
                                        scope.launch {
                                            val err = ChatColor.set(name)
                                            pending = null
                                            if (err == null) applied = name else error = err
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(argb)))
                                Text(AppState.t("color_$name"), color = TU.text, fontSize = 13.sp, maxLines = 1,
                                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                if (pending == name) {
                                    CircularProgressIndicator(Modifier.size(14.dp), color = TU.muted, strokeWidth = 2.dp)
                                } else if (applied == name) {
                                    Text("✓", color = TU.success, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                error?.let {
                    Text(AppState.t(it), color = Warning, fontSize = 12.sp, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

// ── Aperçu des tailles ──

/** Deux lignes factices rendues par le vrai ChatMessageRow : ce qu'on voit
 *  ici est ce que donnera le chat. */
@Composable
fun ChatSizePreview() {
    val font = Features.chatFontSize.value
    val emote = (font * 1.7f * ChatPrefs.emoteScale.value / 100f).sp
    val badge = (font * 1.19f * ChatPrefs.badgeScale.value / 100f).sp
    val vPad = (ChatPrefs.spacing.value / 2f).dp
    val me = AppState.user?.displayName
    val samples = remember(me) { previewMessages(me) }
    Column(Modifier.chatPreviewBox()) {
        for (m in samples) {
            ChatMessageRow(m, emoteSize = emote, fontSize = font.sp, timestamp = Features.chatTimestamps.value,
                           badgeSize = badge, vPad = vPad)
        }
    }
}

private fun previewMessages(me: String?): List<ChatMessage> = listOf(
    ChatMessage(id = "preview-1", login = "lewdolas", name = "Lewdolas", color = 0xFFFBBF24.toInt(),
                badges = listOfNotNull(Badges.url("moderator/1", null)),
                tokens = listOf(ChatToken.Text("gg "),
                                ChatToken.Emote("Kappa", "https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/2.0", twitchId = "25"))),
    ChatMessage(id = "preview-2", login = "damonarix", name = "Damonarix", color = 0xFF5CE1E6.toInt(), badges = emptyList(),
                tokens = listOf(ChatToken.Mention("@" + (me ?: "TwitchUnblock")), ChatToken.Text(" 👋"))),
)
