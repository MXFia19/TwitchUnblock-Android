package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texte d'un message sans images (filtres, mots surlignés). */
fun ChatMessage.plainText(): String = tokens.joinToString("") { t ->
    when (t) {
        is ChatToken.Text -> t.text
        is ChatToken.Emote -> t.name
        is ChatToken.Mention -> t.name
        is ChatToken.Link -> t.url
    }
}

/** Message à cacher d'après les réglages du chat (bots, commandes, mots, personnes). */
fun hiddenByFilters(m: ChatMessage): Boolean {
    if (m.login.isEmpty()) return false
    val login = m.login.lowercase()
    if (login in Features.blockedUsers.value) return true
    if (Features.chatHideBots.value && login in Features.knownBots) return true
    // Texte recomposé seulement si un filtre le lit : refiltrés à chaque
    // message, les 300 messages le refaisaient tous.
    val hideCommands = Features.chatHideCommands.value
    val muted = Features.mutedWords.value
    if (!hideCommands && muted.isEmpty()) return false
    val text = m.plainText()
    if (hideCommands && text.trimStart().startsWith("!")) return true
    if (muted.isNotEmpty()) {
        val lower = text.lowercase()
        if (muted.any { lower.contains(it) }) return true
    }
    return false
}

private fun highlighted(m: ChatMessage): Boolean {
    if (m.highlight) return true
    val words = Features.highlightWords.value
    if (words.isEmpty() || m.login.isEmpty()) return false
    val lower = m.plainText().lowercase()
    return words.any { lower.contains(it) }
}

private val clock = SimpleDateFormat("HH:mm", Locale.US)

/** Texte d'un message, emotes et badges en images dans la ligne. */
fun chatLine(m: ChatMessage, emoteSize: TextUnit, timestamp: Boolean = false): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val badgeSize = (emoteSize.value * 0.7f).sp
    val text = buildAnnotatedString {
        if (m.notice != null) {
            withStyle(SpanStyle(color = Color(0xFFBF94FF), fontWeight = FontWeight.SemiBold)) { append(m.notice) }
            if (m.login.isNotEmpty()) append("\n")
        }
        if (m.login.isEmpty()) return@buildAnnotatedString
        if (timestamp) {
            withStyle(SpanStyle(color = TU.muted, fontSize = (emoteSize.value * 0.45f).sp)) { append(clock.format(Date(m.time)) + " ") }
        }
        m.badges.forEachIndexed { i, url ->
            val id = "b$i"
            inline[id] = InlineTextContent(Placeholder(badgeSize, badgeSize, PlaceholderVerticalAlign.Center)) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            appendInlineContent(id, "[b]")
            append(" ")
        }
        val nameColor = Color(m.color)
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(m.name) }
        append(if (m.action) " " else ": ")
        if (m.deleted && !Features.chatKeepDeleted.value) {
            withStyle(SpanStyle(color = TU.muted, fontStyle = FontStyle.Italic)) { append(AppState.t("chat_deleted")) }
            return@buildAnnotatedString
        }
        // Message supprimé mais gardé : barré.
        val base = if (m.deleted) SpanStyle(color = TU.muted, textDecoration = TextDecoration.LineThrough) else SpanStyle()
        withStyle(base) {
            m.tokens.forEachIndexed { i, tok ->
                when (tok) {
                    is ChatToken.Text ->
                        if (m.action) withStyle(SpanStyle(color = nameColor)) { append(tok.text) } else append(tok.text)
                    is ChatToken.Emote -> {
                        val id = "e$i"
                        val width = (emoteSize.value * tok.ratio.coerceIn(0.5f, 4f)).sp
                        inline[id] = InlineTextContent(Placeholder(width, emoteSize, PlaceholderVerticalAlign.Center)) {
                            AsyncImage(model = tok.url, contentDescription = tok.name, modifier = Modifier.fillMaxSize())
                        }
                        appendInlineContent(id, tok.name)
                    }
                    is ChatToken.Mention -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.name) }
                    is ChatToken.Link -> withLink(LinkAnnotation.Url(tok.url, TextLinkStyles(SpanStyle(color = Color(0xFFBF94FF), textDecoration = TextDecoration.Underline)))) {
                        append(tok.url)
                    }
                }
            }
        }
    }
    return Pair(text, inline)
}

@Composable
fun ChatMessageRow(m: ChatMessage, emoteSize: TextUnit = 24.sp, fontSize: TextUnit = 14.sp, timestamp: Boolean = false,
                   onClick: (() -> Unit)? = null) {
    val keepDeleted = Features.chatKeepDeleted.value
    val (text, inline) = remember(m, timestamp, keepDeleted, emoteSize) { chatLine(m, emoteSize, timestamp) }
    val bg = when {
        highlighted(m) -> TU.highlight
        m.notice != null -> TU.primaryDim
        else -> Color.Transparent
    }
    Text(
        text, inlineContent = inline, color = TU.text, fontSize = fontSize, lineHeight = (emoteSize.value + 4).sp,
        modifier = Modifier.fillMaxWidth().background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun atBottom(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 2
}

/** Chat d'un direct : les messages filtrés (qui suivent le bas, sauf si on
 *  remonte), et la saisie avec ses emotes. Toucher un message : masquer la
 *  personne. */
@Composable
fun ChatPanel(client: ChatClient, modifier: Modifier = Modifier, onLogin: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val visible by remember(client) { derivedStateOf { client.messages.filter { !hiddenByFilters(it) } } }
    val font = Features.chatFontSize.value
    val emote = (font * 1.7f).sp
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling) stick = atBottom(list) }
    }
    LaunchedEffect(visible.size, visible.lastOrNull()?.id) {
        if (stick && visible.isNotEmpty()) list.scrollToItem(visible.size - 1)
    }
    Column(modifier.background(TU.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(visible, key = { _, m -> m.id }) { _, m ->
                    Box {
                        val open = if (m.login.isNotEmpty()) ({ menuFor = m.id }) else null
                        ChatMessageRow(m, emoteSize = emote, fontSize = font.sp, timestamp = Features.chatTimestamps.value,
                                       onClick = open)
                        DropdownMenu(expanded = menuFor == m.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text(AppState.t("hide_user") + " · " + m.name) }, onClick = {
                                menuFor = null
                                Features.blockedUsers.set(Features.blockedUsers.value + m.login)
                            })
                        }
                    }
                }
            }
            if (!client.connected && client.messages.isEmpty()) {
                Text(AppState.t("chat_connecting"), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.align(Alignment.Center))
            }
            if (!stick) {
                SmallFloatingActionButton(
                    onClick = {
                        stick = true
                        scope.launch { if (visible.isNotEmpty()) list.scrollToItem(visible.size - 1) }
                    },
                    containerColor = TU.primary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                ) { Text("↓ " + AppState.t("chat_resume"), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
            }
        }
        ChatInput(client, onLogin)
    }
}

@Composable
private fun ChatInput(client: ChatClient, onLogin: () -> Unit) {
    if (AppState.user == null) {
        Row(Modifier.fillMaxWidth().background(TU.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppState.t("chat_login_to_send"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(AppState.t("tv_login"), color = TU.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.clip(RoundedCornerShape(6.dp)).focusCard(onLogin).padding(6.dp))
        }
        return
    }
    var text by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val send = {
        if (client.send(text)) text = ""
    }
    Column(Modifier.fillMaxWidth().background(TU.surface)) {
        if (picker) {
            EmotePicker(client.roomId) { name ->
                text = (if (text.isEmpty() || text.endsWith(" ")) text else "$text ") + name + " "
            }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker = !picker }) {
                Icon(Icons.Filled.Face, contentDescription = AppState.t("emotes_channel"), tint = if (picker) TU.primary else TU.muted)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 500) text = it },
                placeholder = { Text(AppState.t("chat_send_ph"), color = TU.muted) },
                singleLine = true,
                enabled = client.canSend,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send() }, enabled = client.canSend && text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = AppState.t("fb_send"), tint = TU.primary)
            }
        }
    }
}

/** Emotes 7TV, BTTV et FFZ de la chaîne puis globales : un toucher l'ajoute au message. */
@Composable
private fun EmotePicker(channelId: String?, onPick: (String) -> Unit) {
    val all = remember(channelId) { Emotes.all(channelId).distinctBy { it.name } }
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) all else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxWidth().height(230.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(AppState.t("emote_search_ph"), color = TU.muted) },
            colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
        if (shown.isEmpty()) {
            Text(AppState.t(if (all.isEmpty()) "emotes_loading" else "emotes_no_result"), color = TU.muted, fontSize = 13.sp,
                 modifier = Modifier.padding(12.dp))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
            items(shown, key = { it.name }) { e ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).clickable { onPick(e.name) }.padding(4.dp),
                    contentAlignment = Alignment.Center) {
                    AsyncImage(model = e.url, contentDescription = e.name, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
