package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.VodChat
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay

/**
 * Chat rejoué au rythme de la lecture : celui de la VOD, ou pour un clip
 * celui de sa VOD d'origine, à l'instant du clip (comme l'iPhone). Hors
 * ligne : le chat gardé avec la VOD téléchargée.
 */
@Composable
fun rememberReplayChat(session: PlaybackSession, show: Boolean): VodChat? {
    val scope = rememberCoroutineScope()
    val ready = show && session.state is PlaybackSession.State.Ready
    val vodId = if (ready) session.vodId else null
    val clipSlug = if (ready && vodId == null) (session.request as? PlayRequest.Clip)?.slug else null
    // Clip : sa VOD d'origine et son instant dans la VOD (requête publique).
    var origin by remember(clipSlug) { mutableStateOf<Pair<String, Double>?>(null) }
    LaunchedEffect(clipSlug) { origin = clipSlug?.let { clipOrigin(it) } }
    val id = vodId ?: origin?.first
    val base by rememberUpdatedState(if (vodId != null) 0.0 else origin?.second ?: 0.0)
    val offline = session.isOffline
    val chat = remember(id, offline) {
        id?.let { VodChat(it, scope, if (offline && vodId != null) Downloads.chatFile(it) else null) }
    }
    LaunchedEffect(chat) {
        val c = chat ?: return@LaunchedEffect
        c.start()
        while (true) {
            c.update(base + session.player.currentPosition / 1000.0)
            delay(500)
        }
    }
    return chat
}

/** VOD d'origine d'un clip et son instant (s) ; null si elle n'existe plus. */
private suspend fun clipOrigin(slug: String): Pair<String, Double>? {
    val c = Gql.request("query(\$s: ID!) { clip(slug: \$s) { videoOffsetSeconds video { id } } }", Gql.vars("s" to slug))
        ?.obj("clip") ?: return null
    val id = c.obj("video")?.str("id")?.takeIf { it.isNotEmpty() } ?: return null
    return Pair(id, c.dbl("videoOffsetSeconds") ?: 0.0)
}

/** Messages du chat rejoué (lecture seule), qui suivent le bas, leur position
 *  dans la VOD devant (avec l'heure des réglages). `background` :
 *  transparent quand il est posé sur l'image (paysage). Sans `fontSize` :
 *  tailles, espacement et heure des réglages du chat, comme ChatPanel, et la
 *  fiche des emotes et des badges au toucher (la TV passe les siennes). */
@Composable
fun ReplayChatList(chat: VodChat, modifier: Modifier = Modifier, emoteSize: Int? = null, fontSize: Int? = null,
                   background: Color = TU.bg) {
    val list = rememberLazyListState()
    var card by remember { mutableStateOf<ChatToken.Emote?>(null) }
    // Dernier message en clé : la liste plafonnée (200) garde la même taille.
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.id) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.size - 1)
    }
    val settings = fontSize == null
    val font = fontSize ?: Features.chatFontSize.value
    val emote = emoteSize?.sp ?: (font * 1.7f * ChatPrefs.emoteScale.value / 100f).sp
    val badge = if (settings) (font * 1.19f * ChatPrefs.badgeScale.value / 100f).sp else TextUnit.Unspecified
    val vPad = if (settings) (ChatPrefs.spacing.value / 2f).dp else 3.dp
    // Heure : la position du message dans la VOD (VodChat).
    val timestamp = settings && Features.chatTimestamps.value
    val open = remember { { e: ChatToken.Emote -> card = e } }
    Box(modifier.background(background)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(chat.messages, key = { it.id }) { m ->
                ChatMessageRow(m, emoteSize = emote, fontSize = font.sp, timestamp = timestamp, badgeSize = badge, vPad = vPad,
                               onEmote = if (settings) open else null)
            }
        }
        if (chat.messages.isEmpty()) {
            Text(AppState.t(if (chat.error) "chat_unreachable" else "tv_chat_waiting"), color = TU.muted, fontSize = 13.sp,
                 modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
    }
    card?.let { e -> EmoteCard(e) { card = null } }
}
