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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatClock
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.chat.ChatRules
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.EmoteLists
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Links
import kotlinx.coroutines.launch

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

/** Texte d'un message, emotes et badges en images dans la ligne. `onEmote` :
 *  emote touchée (sa fiche ; appui long : favoris, masquer) ; `onUser` :
 *  mention touchée (fiche de la personne). Une emote masquée (EmoteLists)
 *  s'affiche comme le mot tapé. `badgeSize` : sinon 70 % des emotes ;
 *  `twelve` : heure en 12 h. */
fun chatLine(m: ChatMessage, emoteSize: TextUnit, timestamp: Boolean = false,
             onEmote: ((ChatToken.Emote) -> Unit)? = null,
             onUser: ((String) -> Unit)? = null,
             badgeSize: TextUnit = TextUnit.Unspecified,
             twelve: Boolean = false): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val badge = if (badgeSize == TextUnit.Unspecified) (emoteSize.value * 0.7f).sp else badgeSize
    val text = buildAnnotatedString {
        if (m.notice != null) {
            withStyle(SpanStyle(color = Color(0xFFBF94FF), fontWeight = FontWeight.SemiBold)) { append(m.notice) }
            // Sans texte de la personne (abonnement offert, raid) : pas de
            // ligne « Pseudo : » vide sous la notice.
            if (m.tokens.isEmpty()) return@buildAnnotatedString
            if (m.login.isNotEmpty()) append("\n")
        }
        if (m.login.isEmpty()) return@buildAnnotatedString
        if (timestamp) {
            val time = m.timeLabel ?: ChatClock.format(m.time, twelve)
            withStyle(SpanStyle(color = TU.muted, fontSize = (emoteSize.value * 0.45f).sp)) { append("$time ") }
        }
        m.badges.forEachIndexed { i, url ->
            val id = "b$i"
            inline[id] = InlineTextContent(Placeholder(badge, badge, PlaceholderVerticalAlign.Center)) {
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
                    is ChatToken.Emote -> if (EmoteLists.hides(tok.name)) {
                        // Masquée : le mot tapé, comme un texte ; un toucher ouvre
                        // toujours sa fiche (de quoi la réafficher).
                        val style = if (m.action) SpanStyle(color = nameColor) else SpanStyle()
                        if (onEmote != null) {
                            withLink(LinkAnnotation.Clickable("emote:$i", null, LinkInteractionListener { onEmote(tok) })) {
                                withStyle(style) { append(tok.name) }
                            }
                        } else {
                            withStyle(style) { append(tok.name) }
                        }
                    } else {
                        val id = "e$i"
                        val width = (emoteSize.value * tok.ratio.coerceIn(0.5f, 4f)).sp
                        inline[id] = InlineTextContent(Placeholder(width, emoteSize, PlaceholderVerticalAlign.Center)) {
                            // ChatEmotesUi.kt : toucher, sa fiche ; appui long, favoris et masquer.
                            ChatEmoteImage(tok, onEmote)
                        }
                        appendInlineContent(id, tok.name)
                    }
                    is ChatToken.Mention -> {
                        // Pseudo mentionné : sa fiche au toucher.
                        val login = tok.name.removePrefix("@").trimEnd(',', '.', '!', '?', ':', ';').lowercase()
                        if (onUser != null && Links.isValidLogin(login)) {
                            withLink(LinkAnnotation.Clickable("user:$login", null, LinkInteractionListener { onUser(login) })) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.name) }
                            }
                        } else {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.name) }
                        }
                    }
                    is ChatToken.Link -> withLink(LinkAnnotation.Url(tok.url, TextLinkStyles(SpanStyle(color = Color(0xFFBF94FF), textDecoration = TextDecoration.Underline)))) {
                        append(tok.url)
                    }
                }
            }
        }
    }
    return Pair(text, inline)
}

/** `badgeSize`, `vPad` (demi-espace entre deux messages) : tailles des
 *  réglages du chat ; `shadow` : texte ombré, lisible posé sur l'image. */
@Composable
fun ChatMessageRow(m: ChatMessage, emoteSize: TextUnit = 24.sp, fontSize: TextUnit = 14.sp, timestamp: Boolean = false,
                   onClick: (() -> Unit)? = null, onEmote: ((ChatToken.Emote) -> Unit)? = null,
                   onUser: ((String) -> Unit)? = null, badgeSize: TextUnit = TextUnit.Unspecified,
                   vPad: Dp = 3.dp, shadow: Boolean = false) {
    val keepDeleted = Features.chatKeepDeleted.value
    // Emotes masquées et format de l'heure : la ligne est refaite s'ils changent.
    val hiddenEmotes = EmoteLists.hiddenSet
    val twelve = timestamp && ChatClock.twelve()
    // onEmote et onUser : rappels stables (ChatPanel), hors des clés du cache.
    val (text, inline) = remember(m, timestamp, keepDeleted, emoteSize, badgeSize, hiddenEmotes, twelve) {
        chatLine(m, emoteSize, timestamp, onEmote, onUser, badgeSize, twelve)
    }
    val accent = m.accent?.let { Color(it) }
    val bg = when {
        accent != null -> accent.copy(alpha = 0.16f)
        highlighted(m) -> TU.highlight
        m.notice != null -> TU.primaryDim
        else -> Color.Transparent
    }
    // Ligne assez haute pour la plus grande image (emote ou badge agrandis).
    val tallest = maxOf(emoteSize.value, if (badgeSize == TextUnit.Unspecified) 0f else badgeSize.value)
    Text(
        text, inlineContent = inline, color = TU.text, fontSize = fontSize,
        lineHeight = maxOf(tallest + 4, fontSize.value * 1.3f).sp,
        style = if (shadow) LocalTextStyle.current.copy(shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)) else LocalTextStyle.current,
        modifier = Modifier.fillMaxWidth().background(bg)
            // Annonce : un trait de sa couleur à gauche, comme l'iPhone.
            .then(if (accent != null) Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = vPad),
    )
}

private fun atBottom(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 2
}

/** Chat d'un direct : les messages filtrés (qui suivent le bas, sauf si on
 *  remonte), et la saisie avec ses emotes et l'autocomplétion. Toucher un
 *  message ou un pseudo : la fiche de la personne ; une emote : sa fiche.
 *  `onOpenChannel` : « Sa chaîne » dans la fiche (pas en multi-live) ;
 *  `translucent` : posé sur l'image (paysage), fond transparent. */
@Composable
fun ChatPanel(client: ChatClient, modifier: Modifier = Modifier, onOpenChannel: ((String) -> Unit)? = null,
              translucent: Boolean = false, onLogin: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }
    var userCard by remember { mutableStateOf<String?>(null) }
    var emoteCard by remember { mutableStateOf<ChatToken.Emote?>(null) }
    // Saisie gardée dans le client (client.draft : rotation, récompenses) :
    // « Mentionner » (fiche d'une personne) y ajoute le pseudo.
    // Rappels stables : les lignes déjà composées gardent les leurs.
    val openUser = remember { { login: String -> userCard = login } }
    val openEmote = remember { { e: ChatToken.Emote -> emoteCard = e } }
    val visible by remember(client) { derivedStateOf { client.messages.filter { !hiddenByFilters(it) } } }
    val font = Features.chatFontSize.value
    // Tailles des réglages (ChatPrefs) : emotes et badges suivent le texte, puis leur facteur.
    val emote = (font * 1.7f * ChatPrefs.emoteScale.value / 100f).sp
    val badge = (font * 1.19f * ChatPrefs.badgeScale.value / 100f).sp
    val vPad = (ChatPrefs.spacing.value / 2f).dp
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling) stick = atBottom(list) }
    }
    // Messages refaits avec leurs emotes (plus hauts) : recollé en bas aussi.
    LaunchedEffect(visible.size, visible.lastOrNull()?.id, client.assetsRevision) {
        if (stick && visible.isNotEmpty()) list.scrollToItem(visible.size - 1)
    }
    // Posé sur l'image : un dégradé qui assombrit le bas, là où les messages
    // s'accumulent, plutôt qu'un fond opaque.
    val background = if (translucent) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f))))
                     else Modifier.background(TU.bg)
    Column(modifier.then(background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(visible, key = { _, m -> m.id }) { _, m ->
                    val open = if (m.login.isNotEmpty()) ({ userCard = m.login }) else null
                    ChatMessageRow(m, emoteSize = emote, fontSize = font.sp, timestamp = Features.chatTimestamps.value,
                                   onClick = open, onEmote = openEmote, onUser = openUser, badgeSize = badge, vPad = vPad,
                                   shadow = translucent)
                }
            }
            if (!client.connected && client.messages.isEmpty()) {
                Text(AppState.t("chat_connecting"), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.align(Alignment.Center))
            }
            // ChatBars.kt : « ⏱ −6 s » quand le chat attend l'image.
            ChatSyncBadge(client, Modifier.align(Alignment.TopEnd).padding(6.dp))
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
        // Zone d'envoi masquable (Réglages → Chat) : la place va aux messages.
        if (ChatPrefs.showInput.value) ChatInput(client, client.draft, { client.draft = it }, onLogin, translucent, openEmote)
    }
    userCard?.let { login ->
        UserCard(login, client,
                 onMention = if (AppState.user != null) ({ name: String -> client.draft = withMention(client.draft, name) }) else null,
                 onOpenChannel = onOpenChannel, onClose = { userCard = null })
    }
    emoteCard?.let { e -> EmoteCard(e) { emoteCard = null } }
}

@Composable
private fun ChatInput(client: ChatClient, draft: TextFieldValue, onDraft: (TextFieldValue) -> Unit, onLogin: () -> Unit,
                      translucent: Boolean, onEmoteInfo: (ChatToken.Emote) -> Unit) {
    val barColor = if (translucent) Color.Black.copy(alpha = 0.45f) else TU.surface
    if (AppState.user == null) {
        Row(Modifier.fillMaxWidth().background(barColor).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // « Sans compte Twitch » : pas d'invitation à se connecter.
            if (com.mxfia19.twitchunblock.core.NoAccount.active) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            } else {
                Text(AppState.t("chat_login_to_send"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(AppState.t("tv_login"), color = TU.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.clip(RoundedCornerShape(6.dp)).focusCard(onLogin).padding(6.dp))
            }
            // Cloche des notifications de live de la chaîne (suivie, notifications actives).
            NotifBell(client.channel, Modifier.size(40.dp))
        }
        return
    }
    // Compte banni de ce chat (ChatBars.kt) : un bandeau à la place du champ.
    if (client.readOnly) {
        ReadOnlyBar(client, barColor)
        return
    }
    var picker by remember { mutableStateOf(false) }
    // Message retenu le temps d'accepter les règles du chat (ChatBars.kt).
    var awaitingRules by remember { mutableStateOf<String?>(null) }
    val sendNow: (String) -> Unit = { text -> if (client.send(text)) onDraft(TextFieldValue("")) }
    val send: () -> Unit = {
        // Règles du chat pas encore acceptées (comme sur Twitch) : elles
        // d'abord, le message part une fois acceptées.
        if (draft.text.isNotBlank() && client.rules.isNotEmpty() && !ChatRules.accepted(client.channel, client.rules)) {
            awaitingRules = draft.text
        } else {
            sendNow(draft.text)
        }
    }
    // Autocomplétion : « @ » + pseudos récents du chat, « : » + 2 lettres → emotes.
    val word = currentWord(draft)?.second.orEmpty()
    val suggestions = remember(word, client.roomId, Emotes.version) { chatCompletions(word, client) }
    Column(Modifier.fillMaxWidth().background(barColor)) {
        if (picker) {
            EmotePicker(client.roomId, onEmoteInfo) { name ->
                val t = draft.text
                val next = (if (t.isEmpty() || t.endsWith(" ")) t else "$t ") + name + " "
                if (next.length <= 500) onDraft(TextFieldValue(next, TextRange(next.length)))
            }
        }
        if (suggestions.isNotEmpty()) ChatSuggestions(suggestions) { onDraft(completeWord(draft, it.text)) }
        // Modes du salon (followers, abonnés, lent…) : sus avant d'écrire.
        RoomModesLine(client.modes)
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Cloche des notifications de live de la chaîne (suivie, notifications actives).
            NotifBell(client.channel, Modifier.size(40.dp))
            IconButton(onClick = { picker = !picker }) {
                Icon(Icons.Filled.Face, contentDescription = AppState.t("emotes_channel"), tint = if (picker) TU.primary else TU.muted)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.text.length <= 500) onDraft(it) },
                placeholder = { Text(AppState.t("chat_send_ph"), color = TU.muted) },
                singleLine = true,
                enabled = client.canSend,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send() }, enabled = client.canSend && draft.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = AppState.t("fb_send"), tint = TU.primary)
            }
        }
    }
    awaitingRules?.let { text ->
        ChatRulesDialog(client.channel, client.rules) { accepted ->
            awaitingRules = null
            if (accepted) {
                ChatRules.accept(client.channel, client.rules)
                sendNow(text)
            }
        }
    }
}

/** Emotes 7TV, BTTV et FFZ de la chaîne puis globales : un toucher l'ajoute
 *  au message ; appui long : sa fiche, les favoris, copier son nom. Les
 *  favorites d'abord, dans l'ordre où elles ont été ajoutées. */
@Composable
private fun EmotePicker(channelId: String?, onInfo: (ChatToken.Emote) -> Unit, onPick: (String) -> Unit) {
    // Ouvert avant la fin du chargement : refait quand les emotes arrivent.
    val all = remember(channelId, Emotes.version) { Emotes.all(channelId).distinctBy { it.name } }
    val favoriteCodes = EmoteLists.favorites
    val favorites = remember(all, favoriteCodes) { favoriteEmotes(all, favoriteCodes) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else {
        // Recherche : les favorites qui correspondent d'abord (celles de Twitch comprises).
        val names = all.mapTo(HashSet()) { it.name }
        val found = all.filter { it.name.contains(q, ignoreCase = true) }
        favorites.filter { it.name !in names && it.name.contains(q, ignoreCase = true) } +
            found.sortedBy { if (it.name in favoriteCodes) 0 else 1 }
    }
    Column(Modifier.fillMaxWidth().height(230.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(AppState.t("emote_search_ph"), color = TU.muted) },
            colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
        if (shown.isEmpty() && (q.isNotEmpty() || favorites.isEmpty())) {
            Text(AppState.t(if (all.isEmpty() && q.isEmpty()) "emotes_loading" else "emotes_no_result"), color = TU.muted,
                 fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
            if (q.isEmpty() && favorites.isNotEmpty()) {
                item(key = "h-fav", span = { GridItemSpan(maxLineSpan) }) {
                    Text("★ " + AppState.t("emotes_fav"), color = TU.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
                }
                items(favorites, key = { "f:" + it.name }) { e -> PickerEmote(e, onInfo) { onPick(e.name) } }
                item(key = "h-all", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(TU.line))
                }
            }
            items(shown, key = { it.name }) { e -> PickerEmote(e, onInfo) { onPick(e.name) } }
        }
    }
}
