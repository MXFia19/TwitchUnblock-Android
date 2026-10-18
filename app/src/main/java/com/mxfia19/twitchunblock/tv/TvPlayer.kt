package com.mxfia19.twitchunblock.tv

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.ui.ChatMessageRow
import com.mxfia19.twitchunblock.ui.PlayerOverlay
import com.mxfia19.twitchunblock.ui.TU
import com.mxfia19.twitchunblock.ui.sourceName
import kotlinx.coroutines.delay

/** Lecteur TV : image plein écran, chat à droite (réglage), menu qualité
 *  et chat par ↑ ou la touche Menu. */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayer(request: PlayRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { PlaybackSession(context, scope) }
    var menu by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(request) { session.open(request) }
    LaunchedEffect(Unit) {
        delay(6000)
        hint = false
    }
    DisposableEffect(Unit) {
        TvKeys.onPlayerMenu = { menu = true }
        onDispose {
            TvKeys.onPlayerMenu = null
            TvKeys.menuOpen = false
            TvKeys.controllerVisible = false
            session.release()
        }
    }
    TvKeys.menuOpen = menu
    BackHandler(enabled = menu) {
        menu = false
        refocus++
    }
    BackHandler(enabled = !menu) { onClose() }

    val chatLogin = if (session.isLive) session.channelLogin else null
    val chat = remember(chatLogin) { chatLogin?.let { ChatClient(it, scope) } }
    DisposableEffect(chat) {
        chat?.start(session.channelId)
        onDispose { chat?.stop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = session.player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        keepScreenOn = true
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            TvKeys.controllerVisible = visibility == android.view.View.VISIBLE
                        })
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // Après l'attache à la fenêtre : demandé dans factory,
                        // le focus restait ailleurs et la télécommande ne
                        // pilotait pas les commandes du lecteur.
                        post { requestFocus() }
                    }
                },
                update = { view ->
                    if (view.player !== session.player) view.player = session.player
                    if (!menu && refocus > 0) view.post { view.requestFocus() }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (chat != null && AppState.showChat) {
                TvChat(chat, Modifier.width(360.dp).fillMaxHeight())
            }
        }
        PlayerOverlay(session, Modifier.align(Alignment.Center))
        if (hint && session.state is PlaybackSession.State.Ready) {
            Column(Modifier.align(Alignment.TopStart).padding(24.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xB3000000)).padding(14.dp)) {
                Text(session.title.ifEmpty { session.channelName ?: "" }, color = Color.White, fontSize = 18.sp,
                     fontWeight = FontWeight.Bold, maxLines = 1)
                val sub = listOfNotNull(session.channelName, session.game.takeIf { it.isNotEmpty() },
                                        if (session.isLive) "● " + Format.viewers(session.viewers) else null)
                Text(sub.joinToString(" · "), color = TU.muted, fontSize = 14.sp)
                Text(AppState.t("tv_player_hint"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (menu) {
            TvPlayerMenu(session, hasChat = chat != null, modifier = Modifier.align(Alignment.CenterEnd)) {
                menu = false
                refocus++
            }
        }
    }
}

@Composable
private fun TvChat(chat: ChatClient, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.size - 1)
    }
    LazyColumn(state = list, modifier = modifier.background(Color(0xE6000000)).padding(vertical = 8.dp)) {
        items(chat.messages, key = { it.id }) { m -> ChatMessageRow(m, emoteSize = 26.sp, fontSize = 16.sp) }
    }
}

@Composable
private fun TvPlayerMenu(session: PlaybackSession, hasChat: Boolean, modifier: Modifier, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier.padding(24.dp).width(320.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF0181818)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(AppState.t("quality"), color = TU.muted, fontSize = 14.sp)
        session.qualities.forEachIndexed { i, q ->
            TvButton(Quality.label(q, AppState.lang) + if (q == session.quality) "  ✓" else "",
                     selected = q == session.quality,
                     modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
                session.selectQuality(q)
                onDone()
            }
        }
        if (hasChat) {
            TvButton(AppState.t(if (AppState.showChat) "hide_chat" else "tv_chat_show"), modifier = Modifier.fillMaxWidth()) {
                AppState.updateShowChat(!AppState.showChat)
                onDone()
            }
        }
        session.source?.let { Text(AppState.t("live_source") + " : " + sourceName(it), color = TU.muted, fontSize = 13.sp) }
    }
}
