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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/** Lecteur TV : image plein écran, chat posé dessus (place au choix, retenue
 *  par chaîne), menu qualité, chat et chaîne par ↑ ou la touche Menu. */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayer(request: PlayRequest, onMulti: (String) -> Unit = {}, onChannel: (String) -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pas de session média ni d'image dans l'image sur la TV.
    val session = remember { PlaybackSession(context, scope, main = false) }
    var menu by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    // Commandes du lecteur affichées : le chat posé en bas leur laisse la place.
    var controls by remember { mutableStateOf(false) }
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
            // Session « main = false » : release() n'envoie rien. La position
            // gardée de la VOD part sur le compte, comme sur le téléphone.
            com.mxfia19.twitchunblock.core.CloudSync.scheduleSoon()
        }
    }
    // Pas de session média sur la TV : rien ne joue derrière le lanceur.
    // Pause en quittant l'app (Accueil), reprise au retour (au direct).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resume = session.player.playWhenReady
                session.player.pause()
            } else if (event == Lifecycle.Event.ON_START && resume) {
                resume = false
                if (session.isLive) session.player.seekToDefaultPosition()
                session.player.play()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    KeepScreenOn(session)
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
    // Affiché ou masqué, et sa place : ceux de la chaîne, sinon ceux par défaut.
    val chatShown = TvChatPrefs.shown(session.channelLogin)
    val vodChat = com.mxfia19.twitchunblock.ui.rememberVodChat(session, chatShown)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = session.player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        TvKeys.controllerVisible = visibility == android.view.View.VISIBLE
                        controls = visibility == android.view.View.VISIBLE
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
            // Vue retirée : elle lâche le lecteur (écouteurs, activité retenue).
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Chat posé sur l'image, comme l'Apple TV (direct, ou chat rejoué d'une VOD).
        val place = TvChatPrefs.place(session.channelLogin)
        val chatOn = chatShown && (chat != null || vodChat != null)
        if (chatOn) {
            TvChatOverlay(place, bottomInset = if (controls) 100.dp else 0.dp) {
                if (chat != null) TvChat(chat, Modifier.fillMaxSize())
                else if (vodChat != null) {
                    com.mxfia19.twitchunblock.ui.VodChatList(vodChat, Modifier.fillMaxSize(), emoteSize = 26, fontSize = 16,
                                                              background = Color.Transparent)
                }
            }
        }
        // Pause pub de la chaîne : bandeau en haut, à côté d'un chat posé en haut.
        if (session.isLive) {
            val beside = chatOn && place.height != "bottom"
            TvAdBreakBanner(session.adBreaks, Modifier.align(Alignment.TopCenter)
                .padding(top = 28.dp, start = if (beside && place.side == "left") 340.dp else 24.dp,
                         end = if (beside && place.side == "right") 340.dp else 24.dp))
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
            TvPlayerMenu(session, hasChat = chat != null || session.vodId != null, modifier = Modifier.align(Alignment.CenterEnd),
                         onMulti = { login -> menu = false; onMulti(login) },
                         onChannel = { login -> menu = false; onChannel(login) }) {
                menu = false
                refocus++
            }
        }
    }
}

/** Écran allumé pendant la lecture seulement : en pause, à la fin ou sur une
 *  erreur, l'économiseur d'écran de la TV reprend la main. À part du
 *  lecteur : un changement ne relance pas son `update` (focus rendu au
 *  lecteur, qui quitterait le bouton pause de la télécommande). */
@Composable
private fun KeepScreenOn(session: PlaybackSession) {
    val view = LocalView.current
    val playing = session.isPlaying
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun TvChat(chat: ChatClient, modifier: Modifier) {
    val list = rememberLazyListState()
    // Dernier message en clé : la liste plafonnée (300) garde la même taille.
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.id) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.size - 1)
    }
    // Posé sur l'image (fond de TvChatOverlay) ; peu de messages : calés en bas.
    LazyColumn(state = list, modifier = modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
        items(chat.messages, key = { it.id }) { m -> ChatMessageRow(m, emoteSize = 26.sp, fontSize = 16.sp) }
    }
}

@Composable
private fun TvPlayerMenu(session: PlaybackSession, hasChat: Boolean, modifier: Modifier, onMulti: (String) -> Unit,
                         onChannel: (String) -> Unit, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    var sleep by remember { mutableStateOf(false) }
    // Sous-menu Chat : affiché ou masqué, et sa place (TvChatMenu).
    var chatMenu by remember { mutableStateOf(false) }
    LaunchedEffect(sleep, chatMenu) { runCatching { first.requestFocus() } }
    Column(
        modifier.padding(24.dp).width(320.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF0181818)).padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chatMenu) {
            TvChatMenu(session.channelLogin, first, onDone)
        } else if (sleep) {
            Text(AppState.t("sleep_timer"), color = TU.muted, fontSize = 14.sp)
            com.mxfia19.twitchunblock.ui.sleepChoices.forEachIndexed { i, m ->
                TvButton(com.mxfia19.twitchunblock.ui.sleepLabel(m),
                         modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
                    session.setSleepTimer(m)
                    onDone()
                }
            }
            if (session.sleepAt != null) {
                TvButton(AppState.t("sleep_cancel"), modifier = Modifier.fillMaxWidth()) {
                    session.setSleepTimer(null)
                    onDone()
                }
            }
        } else {
            MainMenu(session, hasChat, first, onMulti, onChannel, onDone, onChat = { chatMenu = true }) { sleep = true }
        }
    }
}

@Composable
private fun MainMenu(session: PlaybackSession, hasChat: Boolean, first: FocusRequester, onMulti: (String) -> Unit,
                     onChannel: (String) -> Unit, onDone: () -> Unit, onChat: () -> Unit, onSleep: () -> Unit) {
        // Focus sur le premier bouton du menu, même sans qualités (encore en chargement).
        val firstOn = if (session.qualities.isEmpty()) (if (hasChat) "chat" else "sleep") else "quality"
        Text(AppState.t("quality"), color = TU.muted, fontSize = 14.sp)
        session.qualities.forEachIndexed { i, q ->
            TvButton(Quality.label(q, AppState.lang) + if (q == session.quality) "  ✓" else "",
                     selected = q == session.quality,
                     modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
                session.selectQuality(q)
                keepTvQuality(session, q)
                onDone()
            }
        }
        if (hasChat) {
            val shown = TvChatPrefs.shown(session.channelLogin)
            TvButton(AppState.t("tv_chat") + " : " + AppState.t(if (shown) "tv_chat_on" else "tv_chat_off"),
                     modifier = (if (firstOn == "chat") Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
                onChat()
            }
        }
        TvButton(AppState.t("sleep_timer") + (session.sleepAt?.let { "  ✓" } ?: ""),
                 modifier = (if (firstOn == "sleep") Modifier.focusRequester(first) else Modifier).fillMaxWidth()) {
            onSleep()
        }
        // Page de la chaîne (vidéos, clips, collections) : le lecteur se ferme.
        session.channelLogin?.let { channel ->
            TvButton(AppState.t("tv_open_channel"), modifier = Modifier.fillMaxWidth()) { onChannel(channel) }
        }
        val login = session.channelLogin
        if (session.isLive && login != null) {
            TvButton(AppState.t("multi_add"), modifier = Modifier.fillMaxWidth()) { onMulti(login) }
        }
        session.source?.let { Text(AppState.t("live_source") + " : " + sourceName(it), color = TU.muted, fontSize = 13.sp) }
}
