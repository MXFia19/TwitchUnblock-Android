package com.mxfia19.twitchunblock.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.BotCommands
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.NoAccount

// Menu ⋯ du chat : les actions qu'on cherche pendant un direct sans les
// avoir en permanence à l'écran. Comme ChatMenuSheet et BotCommands (iOS).

/** Bouton ⋯ en haut du chat et son menu. `chatOnly` : ce chat est déjà la
 *  fenêtre « chat seul » ; `onUseCommand` : commande de bot mise dans la
 *  saisie (sinon copiée). */
@Composable
fun ChatMenuButton(client: ChatClient, chatOnly: Boolean, onChatOnly: () -> Unit, onUser: (String) -> Unit,
                   onUseCommand: ((String) -> Unit)?) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var chatters by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    val signedIn = AppState.user != null
    val done = { label: String -> Toast.makeText(context, "$label ✓", Toast.LENGTH_SHORT).show() }
    Box {
        Box(Modifier.padding(start = 6.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                .clickable { menu = true },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MoreVert, contentDescription = AppState.t("c6_chat_menu"), tint = Color.White,
                 modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text("#${client.channel}", color = TU.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            MenuEntry((if (chatOnly) "✓ " else "") + AppState.t("menu_chat_only"), AppState.t("menu_chat_only_sub")) {
                menu = false
                onChatOnly()
            }
            MenuEntry(AppState.t("menu_reload_emotes"), AppState.t("menu_reload_emotes_sub")) {
                menu = false
                client.reloadAssets()
                done(AppState.t("menu_reload_emotes"))
            }
            MenuEntry(AppState.t("menu_reconnect"), AppState.t("menu_reconnect_sub")) {
                menu = false
                client.reconnect()
                done(AppState.t("menu_reconnect"))
            }
            // Sans compte Twitch : pas de ligne grisée « connexion requise ».
            if (signedIn || !NoAccount.active) {
                MenuEntry(AppState.t("menu_color"), AppState.t(if (signedIn) "menu_color_sub" else "menu_needs_login"),
                          enabled = signedIn) {
                    menu = false
                    colors = true
                }
            }
            MenuEntry(AppState.t("menu_chatters"), AppState.t("menu_chatters_sub")) {
                menu = false
                chatters = true
            }
            MenuEntry(AppState.t("bot_commands"), AppState.t("bot_commands_sub")) {
                menu = false
                commands = true
            }
        }
    }
    if (chatters) ChattersDialog(client, onUser = { login -> chatters = false; onUser(login) }) { chatters = false }
    if (commands) BotCommandsSheet(client.channel, onDismiss = { commands = false }, onUse = onUseCommand)
    // ChatBars.kt : couleur du pseudo (Helix).
    if (colors) UsernameColorDialog { colors = false }
}

@Composable
private fun MenuEntry(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.width(240.dp)) {
                Text(title, color = if (enabled) TU.text else TU.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TU.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}

/** Personnes présentes dans le chat (IRC) : un toucher ouvre leur fiche.
 *  Twitch n'annonce les arrivées que sur les chaînes de taille modeste. */
@Composable
private fun ChattersDialog(client: ChatClient, onUser: (String) -> Unit, onClose: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val all = remember(client.chattersVersion) { client.chatters() }
    val q = filter.trim().lowercase()
    val shown = if (q.isEmpty()) all else all.filter { it.contains(q) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("menu_chatters") + if (all.isNotEmpty()) " · ${all.size}" else "") },
        text = {
            Column {
                if (all.isEmpty()) {
                    Text(AppState.t(if (client.presenceSupported) "menu_chatters_empty" else "menu_chatters_waiting"),
                         color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                                      placeholder = { Text(AppState.t("menu_chatters_search"), color = TU.muted) },
                                      colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 6.dp)) {
                        items(shown, key = { it }) { name ->
                            Text(name, color = TU.text, fontSize = 14.sp,
                                 modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onUser(name) }
                                     .padding(horizontal = 6.dp, vertical = 7.dp))
                        }
                    }
                }
                Text(AppState.t("menu_chatters_note"), color = TU.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

/**
 * Commandes des bots d'une chaîne (Nightbot, StreamElements, Fossabot,
 * Moobot), sans taper « !commands » : du menu ⋯ du chat, ou de la page de la
 * chaîne (en direct ou non). Toucher une commande la met dans la saisie
 * (`onUse`, si on peut écrire), sinon la copie.
 */
@Composable
fun BotCommandsSheet(login: String, onDismiss: () -> Unit, onUse: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var sets by remember(login) { mutableStateOf<List<BotCommands.BotSet>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(login) { sets = BotCommands.all(login) }
    val q = filter.trim().lowercase()
    val shown = sets.orEmpty().mapNotNull { s ->
        if (q.isEmpty()) return@mapNotNull s
        val c = s.commands.filter { it.name.lowercase().contains(q) || it.response.lowercase().contains(q) }
        if (c.isEmpty()) null else BotCommands.BotSet(s.bot, s.icon, c)
    }
    val use = { cmd: String ->
        if (onUse != null) {
            onUse(cmd)
            onDismiss()
        } else {
            clipboard.setText(AnnotatedString(cmd))
            Toast.makeText(context, AppState.t("copied_cmd").replace("{c}", cmd), Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f).clip(RoundedCornerShape(16.dp)).background(TU.bg)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AppState.t("bot_commands"), color = TU.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text) }
            }
            OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                              placeholder = { Text(AppState.t("bot_commands_filter"), color = TU.muted) },
                              colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            when {
                sets == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TU.primary)
                }
                sets.orEmpty().isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                                   horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppState.t("bot_commands_none"), color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(AppState.t("bot_commands_none_msg"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    for (s in shown) {
                        item(key = "h-" + s.bot) {
                            Row(Modifier.fillMaxWidth().background(TU.surface).padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = s.icon, contentDescription = null, modifier = Modifier.size(20.dp).clip(CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(s.bot, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("  ${s.commands.size}", color = TU.muted, fontSize = 12.sp)
                            }
                        }
                        items(s.commands, key = { s.bot + ":" + it.name }) { cmd ->
                            Column(Modifier.fillMaxWidth().clickable { use(cmd.name) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(cmd.name, color = Color(0xFFBF94FF), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                     fontFamily = FontFamily.Monospace)
                                if (cmd.response.isNotEmpty()) {
                                    Text(linkified(cmd.response), color = TU.muted, fontSize = 13.sp, maxLines = 4,
                                         overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Réponse d'un bot, ses liens ouverts au toucher. */
private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in Regex("https?://\\S+").findAll(text)) {
        append(text.substring(last, m.range.first))
        withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = TU.primary, textDecoration = TextDecoration.Underline)))) {
            append(m.value)
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** « Chat seul » : le chat sur tout l'écran (la vidéo continue dessous) ;
 *  son menu ⋯ ou Retour le referme. `notice` : une ligne sous l'en-tête
 *  (chaîne hors ligne, OfflineChat.kt). */
@Composable
fun ChatOnlyWindow(client: ChatClient, onOpenChannel: ((String) -> Unit)?, onLogin: () -> Unit,
                   notice: String? = null, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(TU.bg).imePadding()) {
            Row(Modifier.fillMaxWidth().background(TU.surface).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#${client.channel}", color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text) }
            }
            notice?.let {
                Text(it, color = TU.muted, fontSize = 12.sp,
                     modifier = Modifier.fillMaxWidth().background(TU.surface).padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            }
            ChatPanel(client, Modifier.weight(1f), onOpenChannel = onOpenChannel?.let { open -> { login: String -> onClose(); open(login) } },
                      onExitChatOnly = onClose, onLogin = { onClose(); onLogin() })
        }
    }
}
