package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.ContentScale
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
import com.mxfia19.twitchunblock.chat.Badges
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatClock
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatOptions
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.chat.ChatRules
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.EmoteLists
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.chat.TwitchEmotes
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

/** Violet des notices et des liens du chat. */
private val NoticeTint = Color(0xFFBF94FF)

/** Texte d'un message, emotes et badges en images dans la ligne. `onEmote` :
 *  emote ou badge touché (sa fiche ; appui long sur une emote : favoris,
 *  masquer) ; `onUser` : mention touchée (fiche de la personne) ;
 *  `onOpenChannel` : « Voir la chaîne » d'un raid entrant (sinon sa fiche).
 *  Une emote masquée (EmoteLists) s'affiche comme le mot tapé ; un GIF du
 *  clavier GIF de Twitch est posé sous le texte (ChatMessageRow).
 *  `badgeSize` : sinon 70 % des emotes ; `twelve` : heure en 12 h. */
fun chatLine(m: ChatMessage, emoteSize: TextUnit, timestamp: Boolean = false,
             onEmote: ((ChatToken.Emote) -> Unit)? = null,
             onUser: ((String) -> Unit)? = null,
             badgeSize: TextUnit = TextUnit.Unspecified,
             twelve: Boolean = false,
             onOpenChannel: ((String) -> Unit)? = null): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val badge = if (badgeSize == TextUnit.Unspecified) (emoteSize.value * 0.7f).sp else badgeSize
    val small = (emoteSize.value * 0.45f).sp
    val text = buildAnnotatedString {
        // Premier message de la personne dans ce chat : mis en avant, comme sur Twitch.
        if (m.firstMessage && m.login.isNotEmpty()) {
            withStyle(SpanStyle(color = NoticeTint, fontWeight = FontWeight.SemiBold, fontSize = (emoteSize.value * 0.5f).sp)) {
                append("✨ " + AppState.t("chat_first_message"))
            }
            append("\n")
        }
        if (m.notice != null) {
            withStyle(SpanStyle(color = NoticeTint, fontWeight = FontWeight.SemiBold)) { append(m.notice) }
            // Raid entrant : un lien vers la chaîne qui arrive.
            val raider = m.raider
            val open = onOpenChannel ?: onUser
            if (raider != null && open != null) {
                append("  ")
                withLink(LinkAnnotation.Clickable("raid:$raider",
                    TextLinkStyles(SpanStyle(color = NoticeTint, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)),
                    LinkInteractionListener { open(raider) })) {
                    append(AppState.t("see_channel") + " ›")
                }
            }
            // Sans texte de la personne (abonnement offert, raid) : pas de
            // ligne « Pseudo : » vide sous la notice.
            if (m.tokens.isEmpty()) return@buildAnnotatedString
            if (m.login.isNotEmpty()) append("\n")
        }
        if (m.login.isEmpty()) return@buildAnnotatedString
        // Réponse : à qui et à quoi, sur une ligne au-dessus du message.
        m.replyTo?.let { to ->
            withStyle(SpanStyle(color = TU.muted, fontSize = (emoteSize.value * 0.5f).sp)) {
                append("↩ ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@$to") }
                append(m.replyBody?.let { b -> ": " + if (b.length > 80) b.take(80) + "…" else b } ?: "")
            }
            append("\n")
        }
        if (timestamp) {
            val time = m.timeLabel ?: ChatClock.format(m.time, twelve)
            withStyle(SpanStyle(color = TU.muted, fontSize = small)) { append("$time ") }
        }
        // Repêché dans l'historique : sans ce repère, on croit avoir vu la
        // conversation se dérouler alors qu'elle a eu lieu avant l'arrivée.
        if (m.historical) withStyle(SpanStyle(color = TU.muted, fontSize = small)) { append("🕘 ") }
        m.badges.forEachIndexed { i, url ->
            val id = "b$i"
            inline[id] = InlineTextContent(Placeholder(badge, badge, PlaceholderVerticalAlign.Center)) {
                ChatBadgeImage(url, onEmote)
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
                    // GIF : posé sous le texte, sur sa propre ligne (ChatMessageRow).
                    is ChatToken.Emote -> if (tok.source == "gif") {
                        Unit
                    } else if (EmoteLists.hides(tok.name)) {
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
                    is ChatToken.Link -> withLink(LinkAnnotation.Url(tok.url, TextLinkStyles(SpanStyle(color = NoticeTint, textDecoration = TextDecoration.Underline)))) {
                        append(tok.url)
                    }
                }
            }
        }
    }
    return Pair(text, inline)
}

/** Badge d'un message : un toucher ouvre sa fiche (titre en grand), quand le
 *  chat en propose une. */
@Composable
private fun ChatBadgeImage(url: String, onEmote: ((ChatToken.Emote) -> Unit)?) {
    if (onEmote == null) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        return
    }
    AsyncImage(model = url, contentDescription = Badges.info(url)?.first,
               modifier = Modifier.fillMaxSize().clickable { onEmote(ChatToken.Emote(Badges.info(url)?.first ?: "", url, source = "badge")) })
}

/** `badgeSize`, `vPad` (demi-espace entre deux messages) : tailles des
 *  réglages du chat ; `shadow` : texte ombré, lisible posé sur l'image ;
 *  `onOpenChannel` : « Voir la chaîne » d'un raid entrant. */
@Composable
fun ChatMessageRow(m: ChatMessage, emoteSize: TextUnit = 24.sp, fontSize: TextUnit = 14.sp, timestamp: Boolean = false,
                   onClick: (() -> Unit)? = null, onEmote: ((ChatToken.Emote) -> Unit)? = null,
                   onUser: ((String) -> Unit)? = null, badgeSize: TextUnit = TextUnit.Unspecified,
                   vPad: Dp = 3.dp, shadow: Boolean = false, onOpenChannel: ((String) -> Unit)? = null) {
    val keepDeleted = Features.chatKeepDeleted.value
    // Emotes masquées et format de l'heure : la ligne est refaite s'ils changent.
    val hiddenEmotes = EmoteLists.hiddenSet
    val twelve = timestamp && ChatClock.twelve()
    // onEmote et onUser : rappels stables (ChatPanel), hors des clés du cache.
    val (text, inline) = remember(m, timestamp, keepDeleted, emoteSize, badgeSize, hiddenEmotes, twelve) {
        chatLine(m, emoteSize, timestamp, onEmote, onUser, badgeSize, twelve, onOpenChannel)
    }
    val accent = m.accent?.let { Color(it) }
    val bg = when {
        accent != null -> accent.copy(alpha = 0.16f)
        highlighted(m) -> TU.highlight
        m.notice != null -> TU.primaryDim
        m.firstMessage -> TU.primary.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    // Ligne assez haute pour la plus grande image (emote ou badge agrandis).
    val tallest = maxOf(emoteSize.value, if (badgeSize == TextUnit.Unspecified) 0f else badgeSize.value)
    val style = if (shadow) LocalTextStyle.current.copy(shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)) else LocalTextStyle.current
    val box = Modifier.fillMaxWidth().background(bg)
        // Annonce : un trait de sa couleur à gauche, comme l'iPhone.
        .then(if (accent != null) Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = vPad)
    // GIF du clavier GIF de Twitch : sous le texte, sur sa propre ligne
    // (dans la ligne, il aurait imposé sa hauteur à tout le message).
    val gifs = if (m.deleted && !keepDeleted) emptyList()
               else m.tokens.filterIsInstance<ChatToken.Emote>().filter { it.source == "gif" }
    if (gifs.isEmpty()) {
        Text(text, inlineContent = inline, color = TU.text, fontSize = fontSize,
             lineHeight = maxOf(tallest + 4, fontSize.value * 1.3f).sp, style = style, modifier = box)
        return
    }
    Column(box) {
        Text(text, inlineContent = inline, color = TU.text, fontSize = fontSize,
             lineHeight = maxOf(tallest + 4, fontSize.value * 1.3f).sp, style = style)
        for (g in gifs) {
            AsyncImage(model = g.url, contentDescription = g.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                       modifier = Modifier.padding(top = 4.dp).heightIn(max = 140.dp).widthIn(max = 240.dp)
                           .clip(RoundedCornerShape(6.dp))
                           .then(if (onEmote != null) Modifier.clickable { onEmote(g) } else Modifier))
        }
    }
}

private fun atBottom(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 2
}

/** Chat d'un direct : les messages filtrés (qui suivent le bas, sauf si on
 *  remonte), et la saisie avec ses emotes et l'autocomplétion. Toucher un
 *  message ou un pseudo : la fiche de la personne (répondre, copier, fil) ;
 *  une emote ou un badge : sa fiche. Menu ⋯ en haut (ChatMenu.kt).
 *  `onOpenChannel` : « Sa chaîne » dans la fiche (pas en multi-live) ;
 *  `translucent` : posé sur l'image (paysage), fond transparent ;
 *  `onExitChatOnly` : ce chat est la fenêtre « chat seul », à refermer. */
@Composable
fun ChatPanel(client: ChatClient, modifier: Modifier = Modifier, onOpenChannel: ((String) -> Unit)? = null,
              translucent: Boolean = false, onExitChatOnly: (() -> Unit)? = null, onLogin: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }
    // Fiche d'une personne : son pseudo, et le message touché (répondre, copier, fil).
    var userCard by remember { mutableStateOf<Pair<String, ChatMessage?>?>(null) }
    var emoteCard by remember { mutableStateOf<ChatToken.Emote?>(null) }
    var chatOnly by remember { mutableStateOf(false) }
    // Saisie gardée dans le client (client.draft : rotation, récompenses) :
    // « Mentionner » (fiche d'une personne) y ajoute le pseudo.
    // Rappels stables : les lignes déjà composées gardent les leurs.
    val openUser = remember { { login: String -> userCard = Pair(login, null) } }
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
                    val open = if (m.login.isNotEmpty()) ({ userCard = Pair(m.login, m) }) else null
                    ChatMessageRow(m, emoteSize = emote, fontSize = font.sp, timestamp = Features.chatTimestamps.value,
                                   onClick = open, onEmote = openEmote, onUser = openUser, badgeSize = badge, vPad = vPad,
                                   shadow = translucent, onOpenChannel = onOpenChannel)
                }
            }
            if (!client.connected && client.messages.isEmpty()) {
                Text(AppState.t("chat_connecting"), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // ChatBars.kt : « ⏱ −6 s » quand le chat attend l'image.
                ChatSyncBadge(client)
                // ChatMenu.kt : reconnecter, emotes, présents, commandes des bots, chat seul.
                ChatMenuButton(client, chatOnly = onExitChatOnly != null,
                               onChatOnly = { if (onExitChatOnly != null) onExitChatOnly() else chatOnly = true },
                               onUser = openUser,
                               onUseCommand = if (client.canSend) ({ cmd: String -> client.draft = withCommand(client.draft, cmd) }) else null)
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
        // Zone d'envoi masquable (Réglages → Chat) : la place va aux messages.
        if (ChatPrefs.showInput.value) ChatInput(client, client.draft, { client.draft = it }, onLogin, translucent, openEmote)
    }
    userCard?.let { (login, message) ->
        UserCard(login, client,
                 onMention = if (AppState.user != null) ({ name: String -> client.draft = withMention(client.draft, name) }) else null,
                 onOpenChannel = onOpenChannel, onClose = { userCard = null }, message = message, onEmote = openEmote)
    }
    emoteCard?.let { e ->
        EmoteCard(e, onInsert = if (client.canSend) ({ name: String -> client.draft = withEmote(client.draft, name) }) else null,
                  onOpenChannel = onOpenChannel) { emoteCard = null }
    }
    // « Chat seul » : le chat sur tout l'écran, la vidéo continue dessous.
    if (chatOnly && onExitChatOnly == null) ChatOnlyWindow(client, onOpenChannel, onLogin) { chatOnly = false }
}

/** Emote ajoutée à la fin de la saisie, curseur après. */
fun withEmote(v: TextFieldValue, name: String): TextFieldValue {
    val t = v.text
    val next = (if (t.isEmpty() || t.endsWith(" ")) t else "$t ") + name + " "
    return if (next.length > 500) v else TextFieldValue(next, TextRange(next.length))
}

/** Commande d'un bot (« !discord ») : remplace la saisie, curseur après. */
fun withCommand(v: TextFieldValue, cmd: String): TextFieldValue {
    val next = "$cmd "
    return if (next.length > 500) v else TextFieldValue(next, TextRange(next.length))
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
    val suggestions = remember(word, client.roomId, Emotes.version, TwitchEmotes.version) { chatCompletions(word, client) }
    Column(Modifier.fillMaxWidth().background(barColor)) {
        if (picker) {
            // ChatPicker.kt : favorites, « Mes emotes », la chaîne, globales.
            EmotePickerTabs(client, onEmoteInfo) { name ->
                val next = withEmote(draft, name)
                if (next !== draft) onDraft(next)
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
                // Suggestions du clavier (Réglages → Chat) : coupées, le clavier
                // ne corrige plus les emotes (« KEKW », « OMEGALUL »).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send,
                                                  autoCorrectEnabled = ChatOptions.keyboardSuggestions.value),
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
