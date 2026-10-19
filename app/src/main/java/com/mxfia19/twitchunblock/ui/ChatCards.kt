package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatProfiles
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format

/**
 * Fiche d'une personne du chat (son message, son pseudo ou une mention
 * touchés) : avatar, nom et badges, création du compte, depuis quand elle
 * suit la chaîne (quand Twitch le dit sans compte), Mentionner, Masquer et
 * Sa chaîne, puis ses derniers messages encore en mémoire. Comme la fiche de
 * MessageSheet (iOS).
 */
@Composable
fun UserCard(login: String, client: ChatClient, onMention: ((String) -> Unit)?, onOpenChannel: ((String) -> Unit)?,
             onClose: () -> Unit) {
    val who = login.lowercase()
    // Ses messages parmi les 300 derniers du chat, dans l'ordre du chat. Relus à
    // chaque message, mais la fiche n'est recomposée que s'ils changent (gros chat).
    val recent by remember(who, client) {
        derivedStateOf { client.messages.filter { it.login.equals(who, ignoreCase = true) && it.tokens.isNotEmpty() }.takeLast(20) }
    }
    val last = recent.lastOrNull()
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
                if (recent.isNotEmpty()) {
                    Text(AppState.t("user_recent"), color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    for (m in recent) key(m.id) { ChatMessageRow(m, emoteSize = 20.sp, fontSize = 13.sp, timestamp = true) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

/**
 * Fiche d'une emote touchée dans le chat : image en grand, nom, source et
 * portée, auteur s'il est connu (pour une emote Twitch : sa chaîne et son
 * type, demandés à Twitch). Comme la fiche d'emote de ChatNotices (iOS).
 */
@Composable
fun EmoteCard(emote: ChatToken.Emote, onClose: () -> Unit) {
    var twitch by remember(emote.twitchId) { mutableStateOf<ChatProfiles.TwitchEmote?>(null) }
    LaunchedEffect(emote.twitchId) { emote.twitchId?.let { twitch = ChatProfiles.twitchEmote(it) } }
    val author = emote.author ?: twitch?.owner
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(model = largeEmoteUrl(emote), contentDescription = emote.name, contentScale = ContentScale.Fit,
                           modifier = Modifier.fillMaxWidth().height(112.dp))
                Text(emote.name, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(top = 12.dp))
                Text(emoteSource(emote, twitch), color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                author?.takeIf { it.isNotEmpty() }?.let { a ->
                    Text(AppState.t("emote_by").replace("{u}", a), color = TU.text, fontSize = 13.sp, textAlign = TextAlign.Center,
                         modifier = Modifier.padding(top = 4.dp))
                }
                if (emote.zeroWidth) {
                    Text(AppState.t(if (emote.source == "ffz") "emote_modifier" else "emote_zero_width"), color = TU.muted,
                         fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

/** « 7TV · Emote de la chaîne » ; pour une emote Twitch, son type dès qu'il est connu. */
private fun emoteSource(e: ChatToken.Emote, twitch: ChatProfiles.TwitchEmote?): String {
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
 * chaîne puis globales ; celles qui commencent ainsi d'abord).
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
        return (first + inside).take(20).map { ChatCompletion(it.name, it.name, it.url) }
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
