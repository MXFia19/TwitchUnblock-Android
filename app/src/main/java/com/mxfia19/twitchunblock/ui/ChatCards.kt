package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.Badges
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatOptions
import com.mxfia19.twitchunblock.chat.ChatProfiles
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.EmoteLists
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format

/**
 * Fiche d'une personne du chat (son message, son pseudo ou une mention
 * touchés) : avatar, nom et badges, création du compte, depuis quand elle
 * suit la chaîne (quand Twitch le dit sans compte), Mentionner, Masquer et
 * Sa chaîne, puis ses derniers messages encore en mémoire. Ouverte depuis un
 * message (`message`) : y répondre (fil de réponse), le copier, et le fil de
 * la conversation dessous. Comme MessageSheet (iOS).
 */
@Composable
fun UserCard(login: String, client: ChatClient, onMention: ((String) -> Unit)?, onOpenChannel: ((String) -> Unit)?,
             onClose: () -> Unit, message: ChatMessage? = null, onEmote: ((ChatToken.Emote) -> Unit)? = null) {
    val who = login.lowercase()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Ses messages parmi les 300 derniers du chat, dans l'ordre du chat. Relus à
    // chaque message, mais la fiche n'est recomposée que s'ils changent (gros chat).
    val mine by remember(who, client) {
        derivedStateOf { client.messages.filter { it.login.equals(who, ignoreCase = true) && it.tokens.isNotEmpty() }.takeLast(20) }
    }
    // Fil de réponses du message touché (vide s'il n'y en a pas).
    val rootId = message?.let { it.threadId ?: it.id }
    val thread by remember(rootId, client) {
        derivedStateOf { rootId?.let { r -> client.threadMessages(r).takeIf { it.size > 1 } }.orEmpty() }
    }
    // Avec un fil, les cinq derniers messages de la personne seulement : le fil reste dessous.
    val recent = if (thread.isNotEmpty()) mine.takeLast(5) else mine
    var replying by remember(message?.id) { mutableStateOf(false) }
    var reply by remember(message?.id) { mutableStateOf("") }
    var replyError by remember(message?.id) { mutableStateOf<String?>(null) }
    val canReply = message != null && client.canSend && !message.id.startsWith("local-") && message.login.isNotEmpty()
    val sendReply = {
        val t = reply.trim()
        if (t.isNotEmpty() && message != null) {
            if (client.send(t, replyTo = message)) {
                reply = ""
                replying = false
                replyError = null
            } else {
                replyError = AppState.t(if (t.length > 500) "reply_too_long" else "reply_not_connected")
            }
        }
    }
    val last = mine.lastOrNull()
    var profile by remember(who) { mutableStateOf<ChatProfiles.Profile?>(null) }
    var followedAt by remember(who) { mutableStateOf<Long?>(null) }
    LaunchedEffect(who) { profile = ChatProfiles.profile(who) }
    LaunchedEffect(who, client.channel) { followedAt = ChatProfiles.followedAt(who, client.channel) }
    val name = last?.name ?: profile?.name ?: who
    val hidden = who in Features.blockedUsers.value
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile?.avatar, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            last?.badges?.forEach { url ->
                                AsyncImage(model = url, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                            }
                            Text(name, color = last?.let { Color(it.color) } ?: TU.text, fontSize = 17.sp,
                                 fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("@$who", color = TU.muted, fontSize = 12.sp)
                    }
                }
                profile?.createdAt?.let {
                    Text(AppState.t("user_created").replace("{d}", Format.shortDate(it)), color = TU.muted, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 10.dp))
                }
                followedAt?.let {
                    Text(AppState.t("user_followed").replace("{d}", Format.shortDate(it)), color = TU.muted, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 2.dp))
                }
                Column(Modifier.padding(top = 6.dp)) {
                    if (canReply && message != null) {
                        LinkRow("↩ " + AppState.t("thread_reply_to") + " @" + message.name) { replying = true }
                    }
                    if (message != null && message.tokens.isNotEmpty()) {
                        LinkRow(AppState.t("copy_message")) {
                            clipboard.setText(AnnotatedString(message.plainText()))
                            android.widget.Toast.makeText(context, AppState.t("msg_copied"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (onMention != null) {
                        // Pseudo affiché s'il ne diffère que par les majuscules, sinon l'identifiant.
                        LinkRow(AppState.t("mention_user")) {
                            onClose()
                            onMention(if (name.equals(who, ignoreCase = true)) name else who)
                        }
                    }
                    LinkRow(AppState.t(if (hidden) "unhide_user" else "hide_user")) {
                        val list = Features.blockedUsers.value
                        Features.blockedUsers.set(if (hidden) list - who else list + who)
                        onClose()
                    }
                    if (onOpenChannel != null) {
                        LinkRow(AppState.t("user_channel")) {
                            onClose()
                            onOpenChannel(who)
                        }
                    }
                }
                if (replying && canReply && message != null) {
                    // Réponse : part avec le tag reply-parent-msg-id, comme sur Twitch.
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = reply, onValueChange = { if (it.length <= 500) reply = it }, singleLine = true,
                            placeholder = { Text(AppState.t("thread_reply_to") + " @" + message.name, color = TU.muted) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send,
                                                              autoCorrectEnabled = ChatOptions.keyboardSuggestions.value),
                            keyboardActions = KeyboardActions(onSend = { sendReply() }),
                            colors = fieldColors(), modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { sendReply() }, enabled = reply.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = AppState.t("fb_send"), tint = TU.primary)
                        }
                    }
                    replyError?.let { Text(it, color = TU.danger, fontSize = 12.sp) }
                }
                if (recent.isNotEmpty()) {
                    Text(AppState.t("user_recent"), color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    for (m in recent) key(m.id) { CardMessageRow(m, message, onEmote) }
                }
                if (thread.isNotEmpty()) {
                    Text(AppState.t("thread_title"), color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    for (m in thread) key("t-" + m.id) { CardMessageRow(m, message, onEmote) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

/** Message dans la fiche : celui d'où l'on vient reste repérable. */
@Composable
private fun CardMessageRow(m: ChatMessage, touched: ChatMessage?, onEmote: ((ChatToken.Emote) -> Unit)?) {
    Box(Modifier.fillMaxWidth().background(if (m.id == touched?.id) TU.primary.copy(alpha = 0.12f) else Color.Transparent)) {
        ChatMessageRow(m, emoteSize = 20.sp, fontSize = 13.sp, timestamp = true, onEmote = onEmote)
    }
}

/**
 * Fiche de ce qu'on touche dans un message : une emote (image en grand, nom
 * exact, source et portée, auteur, nom d'origine 7TV ; la copier, l'ajouter
 * au message, sa page 7TV/BTTV/FFZ ; pour une emote Twitch, son type, sa
 * chaîne avec « Voir la chaîne » et les autres emotes du même palier), un
 * badge (son titre en grand) ou un GIF du clavier GIF de Twitch. `onInsert` :
 * ajouter au message (null si on ne peut pas écrire) ; `onOpenChannel` :
 * « Voir la chaîne » (null : bouton masqué). Comme ChatInfoSheet (iOS).
 */
@Composable
fun EmoteCard(emote: ChatToken.Emote, onInsert: ((String) -> Unit)? = null, onOpenChannel: ((String) -> Unit)? = null,
              onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            // Défilante : avec ses boutons, la fiche dépasse en paysage.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                when (emote.source) {
                    "badge" -> BadgeContent(emote)
                    "gif" -> GifContent(emote)
                    else -> EmoteContent(emote, onInsert?.let { insert -> { name: String -> onClose(); insert(name) } },
                                         onOpenChannel?.let { open -> { login: String -> onClose(); open(login) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

@Composable
private fun EmoteContent(emote: ChatToken.Emote, onInsert: ((String) -> Unit)?, onOpenChannel: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember(emote) { mutableStateOf(false) }
    var twitch by remember(emote.twitchId) { mutableStateOf<ChatProfiles.TwitchEmote?>(null) }
    LaunchedEffect(emote.twitchId) { emote.twitchId?.let { twitch = ChatProfiles.twitchEmote(it) } }
    val author = emote.author ?: if (twitch?.ownerLogin == null) twitch?.owner else null
    AsyncImage(model = largeEmoteUrl(emote), contentDescription = emote.name, contentScale = ContentScale.Fit,
               modifier = Modifier.fillMaxWidth().height(96.dp))
    Text(emote.name, color = TU.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
         textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    Text(emoteSource(emote, twitch), color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    author?.takeIf { it.isNotEmpty() }?.let { a ->
        Text(AppState.t("emote_by").replace("{u}", a), color = TU.text, fontSize = 13.sp, textAlign = TextAlign.Center,
             modifier = Modifier.padding(top = 4.dp))
    }
    emote.original?.let { o ->
        Text(AppState.t("emote_original").replace("{n}", o), color = TU.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
    if (emote.zeroWidth) {
        Text(AppState.t(if (emote.source == "ffz") "emote_modifier" else "emote_zero_width"), color = TU.muted,
             fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
    if (emote.locked) {
        Text("🔒 " + AppState.t("emote_locked"), color = TU.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
             modifier = Modifier.padding(top = 4.dp))
    }
    // Emote Twitch : sa chaîne (en direct ou non) et les emotes de son palier.
    val t = twitch
    val ownerLogin = t?.ownerLogin
    if (t != null && ownerLogin != null) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(TU.surfaceHi)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(t.ownerAvatar, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(t.owner ?: ownerLogin, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (t.ownerLive) {
                Spacer(Modifier.width(6.dp))
                LiveBadge()
            }
            Spacer(Modifier.weight(1f))
            if (onOpenChannel != null) {
                Text(AppState.t("see_channel") + " ›", color = TU.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenChannel(ownerLogin) }.padding(4.dp))
            }
        }
        if (t.type == "SUBSCRIPTIONS" && t.tier != null && t.sameTier.isNotEmpty()) {
            Text(AppState.t("emote_sub_count").replace("{c}", t.owner ?: ownerLogin).replace("{n}", "${t.sameTier.size}"),
                 color = TU.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((xid, token) in t.sameTier.filter { it.first != emote.twitchId }.take(8)) {
                    AsyncImage(model = "https://static-cdn.jtvnw.net/emoticons/v2/$xid/static/dark/2.0", contentDescription = token,
                               modifier = Modifier.size(28.dp))
                }
            }
        }
    }
    emotePage(emote)?.let { (name, url) ->
        Text("↗ " + AppState.t("emote_view_on").replace("{p}", name), color = TU.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(url) } }.padding(4.dp))
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            clipboard.setText(AnnotatedString(emote.name))
            copied = true
        }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
            Text(AppState.t(if (copied) "chat_info_copied" else "chat_info_copy"), fontSize = 13.sp)
        }
        // Emote d'abonné verrouillée : la fiche ne l'ajoute pas plus que la grille.
        if (onInsert != null && !emote.locked) {
            OutlinedButton(onClick = { onInsert(emote.name) }) {
                Text(AppState.t("chat_info_insert"), color = TU.primary, fontSize = 13.sp)
            }
        }
    }
    // ChatEmotesUi.kt : favoris, masquer.
    EmoteCardActions(emote)
}

/** Badge touché : en grand, son titre (« Abonné depuis 3 mois »…) et sa
 *  description ; sans titre connu, le nom de son jeu. */
@Composable
private fun BadgeContent(badge: ChatToken.Emote) {
    val info = Badges.info(badge.url)
    val title = info?.first ?: Badges.idOf(badge.url)?.substringBefore('/')?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() } ?: AppState.t("chat_info_badge")
    // Taille /3 (72 px) : la /2 du chat est floue en grand.
    val large = if (badge.url.startsWith("https://") && badge.url.endsWith("/2")) badge.url.dropLast(1) + "3" else badge.url
    AsyncImage(model = large, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
    Text(title, color = TU.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
         modifier = Modifier.padding(top = 10.dp))
    info?.second?.takeIf { it.isNotEmpty() && it != info.first }?.let {
        Text(it, color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
    Text(AppState.t("chat_info_badge"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

/** GIF du clavier GIF de Twitch : en grand, son titre et son auteur (lus dans
 *  le texte joint, « [Titre GIF by Auteur] »), sa page GIPHY, son lien. */
@Composable
private fun GifContent(gif: ChatToken.Emote) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember(gif) { mutableStateOf(false) }
    val (title, author) = remember(gif.name) { gifParts(gif.name) }
    AsyncImage(model = gif.url, contentDescription = title, contentScale = ContentScale.Fit,
               modifier = Modifier.heightIn(max = 180.dp).widthIn(max = 300.dp))
    Text(title, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
         modifier = Modifier.padding(top = 10.dp))
    Text(AppState.t("chat_gif_source"), color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    author?.let { Text(AppState.t("chat_gif_by").replace("{a}", it), color = TU.muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
    giphyPage(gif.url)?.let { page ->
        Text("↗ " + AppState.t("emote_view_on").replace("{p}", "GIPHY"), color = TU.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(page) } }.padding(4.dp))
    }
    Button(onClick = {
        clipboard.setText(AnnotatedString(gif.url))
        copied = true
    }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary), modifier = Modifier.padding(top = 8.dp)) {
        Text(AppState.t(if (copied) "chat_info_copied" else "copy_link"), fontSize = 13.sp)
    }
}

/** « [Happy Dance GIF by Someone] » → (« Happy Dance », « Someone »). */
private fun gifParts(alt: String): Pair<String, String?> {
    var s = alt.trim()
    if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
    val i = s.lastIndexOf(" GIF by ")
    if (i >= 0) {
        val title = s.substring(0, i).trim()
        val author = s.substring(i + 8).trim()
        return Pair(title.ifEmpty { "GIF" }, author.ifEmpty { null })
    }
    if (s.endsWith(" GIF")) s = s.dropLast(4)
    return Pair(s.ifEmpty { "GIF" }, null)
}

/** Page GIPHY d'un GIF servi par son CDN (…giphy.com/media/<id>/…). */
private fun giphyPage(raw: String): String? {
    val u = try { java.net.URI(raw) } catch (e: Exception) { return null }
    val host = u.host?.lowercase() ?: return null
    if (host != "giphy.com" && !host.endsWith(".giphy.com")) return null
    val parts = (u.path ?: "").split('/').filter { it.isNotEmpty() }
    var i = parts.indexOf("media")
    if (i < 0) return null
    i++
    // Les adresses récentes glissent un segment « v1.… » après « media ».
    if (i < parts.size && parts[i].startsWith("v1.")) i++
    val id = parts.getOrNull(i) ?: return null
    if (id.isEmpty() || !id.all { it.isLetterOrDigit() }) return null
    return "https://giphy.com/gifs/$id"
}

/** Page de l'emote chez 7TV, BTTV ou FFZ (« Voir sur … »). */
private fun emotePage(e: ChatToken.Emote): Pair<String, String>? {
    val id = e.sourceId?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } } ?: return null
    return when (e.source) {
        "7tv" -> Pair("7TV", "https://7tv.app/emotes/$id")
        "bttv" -> Pair("BetterTTV", "https://betterttv.com/emotes/$id")
        "ffz" -> Pair("FrankerFaceZ", "https://www.frankerfacez.com/emoticon/$id")
        else -> null
    }
}

/** « 7TV · Emote de la chaîne » ; pour une emote Twitch, son type dès qu'il est connu. */
private fun emoteSource(e: ChatToken.Emote, twitch: ChatProfiles.TwitchEmote?): String {
    if (e.source == "bttv" && e.personal) return AppState.t("emote_src_bttv_personal")
    if (e.source == "twitch") {
        val tier = twitch?.tier
        return when (twitch?.type) {
            "GLOBALS", "SMILIES" -> AppState.t("emote_type_global")
            "SUBSCRIPTIONS" -> if (tier != null) "Twitch · " + AppState.t("emote_type_sub").replace("{n}", tier.toString())
                               else AppState.t("emote_src_twitch")
            "CHANNEL_POINTS" -> "Twitch · " + AppState.t("emote_type_points")
            else -> AppState.t("emote_src_twitch")
        }
    }
    val brand = when (e.source) {
        "7tv" -> "7TV"
        "bttv" -> "BetterTTV"
        "ffz" -> "FrankerFaceZ"
        else -> e.source
    }
    return brand + " · " + AppState.t(if (e.channel) "emote_scope_channel" else "emote_scope_global")
}

/** Image plus grande que celle du chat, quand la source la propose. */
private fun largeEmoteUrl(e: ChatToken.Emote): String = when {
    e.source == "twitch" && e.url.endsWith("/2.0") -> e.url.removeSuffix("/2.0") + "/3.0"
    e.source == "7tv" -> e.url.replace("/2x.webp", "/4x.webp")
    e.source == "bttv" && e.url.endsWith("/2x") -> e.url.removeSuffix("/2x") + "/3x"
    else -> e.url
}

// ── Autocomplétion de la saisie ──

/** Proposition : `text` remplace le mot en cours ; `image` : celle d'une emote. */
class ChatCompletion(val text: String, val label: String, val image: String? = null)

/** Mot en cours juste avant le curseur : (début, mot) ; null avec une sélection. */
fun currentWord(v: TextFieldValue): Pair<Int, String>? {
    if (!v.selection.collapsed) return null
    val end = v.selection.end.coerceIn(0, v.text.length)
    val start = v.text.lastIndexOf(' ', end - 1) + 1
    return Pair(start, v.text.substring(start, end))
}

/**
 * « @ » propose les pseudos récents du chat (les plus récents d'abord),
 * « : » suivi de 2 lettres les emotes disponibles (7TV, BTTV, FFZ de la
 * chaîne puis globales ; les favorites, puis celles qui commencent ainsi
 * d'abord).
 */
fun chatCompletions(word: String, client: ChatClient): List<ChatCompletion> {
    if (word.startsWith("@")) {
        val q = word.substring(1)
        val me = AppState.user?.login?.lowercase()
        val seen = HashSet<String>()
        val out = ArrayList<ChatCompletion>()
        for (m in client.messages.asReversed()) {
            val l = m.login.lowercase()
            if (l.isEmpty() || l == me || !seen.add(l)) continue
            if (!m.name.startsWith(q, ignoreCase = true) && !l.startsWith(q, ignoreCase = true)) continue
            // Pseudo affiché s'il ne diffère que par les majuscules, sinon l'identifiant.
            out += ChatCompletion("@" + (if (m.name.equals(l, ignoreCase = true)) m.name else l), m.name)
            if (out.size >= 12) break
        }
        return out
    }
    if (word.startsWith(":") && word.length >= 3) {
        val q = word.substring(1)
        val all = Emotes.all(client.roomId).distinctBy { it.name }
        val first = all.filter { it.name.startsWith(q, ignoreCase = true) }
        val inside = all.filter { !it.name.startsWith(q, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
        // Favorites en tête (EmoteLists) ; celles de Twitch, hors de la liste, avec leur image gardée.
        val favorites = EmoteLists.favorites
        val names = all.mapTo(HashSet()) { it.name }
        val twitchFavorites = favoriteEmotes(emptyList(), favorites.filter { it !in names && it.contains(q, ignoreCase = true) })
        val ranked = twitchFavorites + (first + inside).sortedBy { if (it.name in favorites) 0 else 1 }
        return ranked.take(20).map { ChatCompletion(it.name, it.name, it.url) }
    }
    return emptyList()
}

/** Le mot en cours remplacé par la proposition choisie, suivie d'une espace.
 *  Curseur au milieu d'un mot : sa fin est remplacée aussi (sinon « @pse|udo »
 *  donnait « @Pseudo udo »). */
fun completeWord(v: TextFieldValue, text: String): TextFieldValue {
    val (start, _) = currentWord(v) ?: return v
    val cursor = v.selection.end.coerceIn(0, v.text.length)
    val end = v.text.indexOf(' ', cursor).let { if (it < 0) v.text.length else it }
    val after = v.text.substring(end)
    val spaced = after.startsWith(" ")
    val insert = if (spaced) text else "$text "
    val next = v.text.substring(0, start) + insert + after
    if (next.length > 500) return v
    return TextFieldValue(next, TextRange(start + insert.length + if (spaced) 1 else 0))
}

/** « @pseudo » ajouté à la fin de la saisie (Mentionner), curseur après. */
fun withMention(v: TextFieldValue, name: String): TextFieldValue {
    val base = v.text.trimEnd()
    val next = (if (base.isEmpty()) "" else "$base ") + "@$name "
    return if (next.length > 500) v else TextFieldValue(next, TextRange(next.length))
}

/** Propositions au-dessus de la saisie : un toucher remplace le mot en cours. */
@Composable
fun ChatSuggestions(list: List<ChatCompletion>, onPick: (ChatCompletion) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        items(list, key = { it.text }) { c ->
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(TU.surfaceHi).clickable { onPick(c) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                c.image?.let {
                    AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(c.label, color = TU.text, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
