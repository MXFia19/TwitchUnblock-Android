package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mxfia19.twitchunblock.core.AppState
import kotlinx.coroutines.launch

/** Texte d'un message, emotes et badges en images dans la ligne. */
fun chatLine(m: ChatMessage, emoteSize: TextUnit): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val badgeSize = (emoteSize.value * 0.7f).sp
    val text = buildAnnotatedString {
        if (m.notice != null) {
            withStyle(SpanStyle(color = Color(0xFFBF94FF), fontWeight = FontWeight.SemiBold)) { append(m.notice) }
            if (m.login.isNotEmpty()) append("\n")
        }
        if (m.login.isEmpty()) return@buildAnnotatedString
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
        if (m.deleted) {
            withStyle(SpanStyle(color = TU.muted, fontStyle = FontStyle.Italic)) { append(AppState.t("chat_deleted")) }
            return@buildAnnotatedString
        }
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
    return Pair(text, inline)
}

@Composable
fun ChatMessageRow(m: ChatMessage, emoteSize: TextUnit = 24.sp, fontSize: TextUnit = 14.sp) {
    val (text, inline) = remember(m) { chatLine(m, emoteSize) }
    val bg = when {
        m.highlight -> TU.highlight
        m.notice != null -> TU.primaryDim
        else -> Color.Transparent
    }
    Text(
        text, inlineContent = inline, color = TU.text, fontSize = fontSize, lineHeight = (emoteSize.value + 4).sp,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun atBottom(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 2
}

/** Chat d'un direct : les messages (qui suivent le bas, sauf si on remonte)
 *  et la saisie. */
@Composable
fun ChatPanel(client: ChatClient, modifier: Modifier = Modifier, onLogin: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling) stick = atBottom(list) }
    }
    LaunchedEffect(client.messages.size) {
        if (stick && client.messages.isNotEmpty()) list.scrollToItem(client.messages.size - 1)
    }
    Column(modifier.background(TU.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(client.messages, key = { _, m -> m.id }) { _, m -> ChatMessageRow(m) }
            }
            if (!client.connected && client.messages.isEmpty()) {
                Text(AppState.t("chat_connecting"), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.align(Alignment.Center))
            }
            if (!stick) {
                SmallFloatingActionButton(
                    onClick = {
                        stick = true
                        scope.launch { if (client.messages.isNotEmpty()) list.scrollToItem(client.messages.size - 1) }
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
    val send = {
        if (client.send(text)) text = ""
    }
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 500) text = it },
            placeholder = { Text(AppState.t("chat_send_ph"), color = TU.muted) },
            singleLine = true,
            enabled = client.canSend,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line, cursorColor = TU.primary,
                focusedTextColor = TU.text, unfocusedTextColor = TU.text,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { send() }, enabled = client.canSend && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = AppState.t("fb_send"), tint = TU.primary)
        }
    }
}
