package com.mxfia19.twitchunblock.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.VodChat
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay

/** Image du lecteur (PlayerView de Media3 et ses commandes). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(session: PlaybackSession, modifier: Modifier = Modifier, onFullscreen: (() -> Unit)? = null,
                 focusOnStart: Boolean = false, controls: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = session.player
                setShowNextButton(false)
                setShowPreviousButton(false)
                keepScreenOn = true
                setBackgroundColor(android.graphics.Color.BLACK)
                if (onFullscreen != null) setFullscreenButtonClickListener { onFullscreen() }
                if (focusOnStart) {
                    isFocusable = true
                    post { requestFocus() }
                }
            }
        },
        update = { view ->
            if (view.player !== session.player) view.player = session.player
            if (view.useController != controls) {
                view.useController = controls
                if (!controls) view.hideController()
            }
        },
        modifier = modifier.background(Color.Black),
    )
}

/** Chargement (source essayée) ou erreur, par-dessus l'image. */
@Composable
fun PlayerOverlay(session: PlaybackSession, modifier: Modifier = Modifier) {
    when (val s = session.state) {
        is PlaybackSession.State.Loading -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TU.primary)
            val step = s.step
            if (step != null) {
                Text(AppState.t("loading_source").replace("{s}", sourceName(step)), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.padding(top = 8.dp))
            }
        }
        is PlaybackSession.State.Error -> Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(AppState.t(s.key), color = TU.text, fontSize = 15.sp)
            Button(onClick = { session.retry() }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                   modifier = Modifier.padding(top = 10.dp)) { Text(AppState.t("retry")) }
        }
        PlaybackSession.State.Ready -> {}
    }
}

fun sourceName(id: String): String = when (id) {
    "as" -> "Luminous (Asie)"
    "eu" -> "Luminous (Europe)"
    "al" -> "Proxy (Albanie)"
    "twitch" -> "Twitch"
    "worker" -> "Worker"
    else -> id
}

/** Minuterie : libellés des durées proposées (minutes). */
val sleepChoices = listOf(15, 30, 45, 60, 120)

fun sleepLabel(minutes: Int): String = when (minutes) {
    60 -> AppState.t("sleep_hour")
    120 -> AppState.t("sleep_2hours")
    else -> "$minutes ${AppState.t("sleep_minutes")}"
}

/** Chat rejoué d'une VOD : suit la position de lecture. */
@Composable
fun rememberVodChat(session: PlaybackSession, show: Boolean): VodChat? {
    val scope = rememberCoroutineScope()
    val id = if (show && session.state is PlaybackSession.State.Ready) session.vodId else null
    val offline = session.isOffline
    val chat = remember(id, offline) {
        id?.let { VodChat(it, scope, if (offline) Downloads.chatFile(it) else null) }
    }
    LaunchedEffect(chat) {
        val c = chat ?: return@LaunchedEffect
        c.start()
        while (true) {
            c.update(session.player.currentPosition / 1000.0)
            delay(500)
        }
    }
    return chat
}

/** Messages du chat rejoué (lecture seule), qui suivent le bas. */
@Composable
fun VodChatList(chat: VodChat, modifier: Modifier = Modifier, emoteSize: Int = 24, fontSize: Int = 14) {
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.size - 1)
    }
    Box(modifier.background(TU.bg)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(chat.messages, key = { it.id }) { m -> ChatMessageRow(m, emoteSize = emoteSize.sp, fontSize = fontSize.sp) }
        }
        if (chat.messages.isEmpty()) {
            Text(AppState.t(if (chat.error) "chat_unreachable" else "tv_chat_waiting"), color = TU.muted, fontSize = 13.sp,
                 modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
    }
}

@Composable
fun PlayerScreen(request: PlayRequest, nav: Navigator) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val session = remember { PlaybackSession(context, scope) }
    LaunchedEffect(request) { session.open(request) }
    DisposableEffect(Unit) {
        onDispose {
            session.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            (activity as? MainActivity)?.updatePip()
        }
    }
    // Android 12 et plus : passage automatique en image dans l'image tant que ça joue.
    LaunchedEffect(session.isPlaying) { (activity as? MainActivity)?.updatePip() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pip = PipState.active
    // Plein écran en paysage : barres du système masquées (un glissement les rappelle).
    DisposableEffect(landscape, pip) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape || pip) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val chatLogin = if (session.isLive) session.channelLogin else null
    val chat = remember(chatLogin) { chatLogin?.let { ChatClient(it, scope) } }
    DisposableEffect(chat) {
        chat?.start(session.channelId)
        onDispose { chat?.stop() }
    }
    val vodChat = rememberVodChat(session, AppState.showChat)
    var chatInLandscape by remember { mutableStateOf(false) }
    val toggleFullscreen = {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                         else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // Image dans l'image : rien que la vidéo.
    if (pip) {
        VideoSurface(session, Modifier.fillMaxSize(), controls = false)
        return
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoSurface(session, Modifier.fillMaxSize(), onFullscreen = toggleFullscreen)
                PlayerOverlay(session, Modifier.align(Alignment.Center))
                if ((chat != null || vodChat != null) && AppState.showChat) {
                    IconButton(onClick = { chatInLandscape = !chatInLandscape }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Email, contentDescription = AppState.t("tv_chat"), tint = Color.White)
                    }
                }
            }
            if (chatInLandscape) {
                if (chat != null) ChatPanel(chat, Modifier.width(330.dp).fillMaxHeight().imePadding()) { nav.push(Screen.Login) }
                else if (vodChat != null) VodChatList(vodChat, Modifier.width(330.dp).fillMaxHeight())
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(TU.bg)) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding().aspectRatio(16f / 9f)) {
            VideoSurface(session, Modifier.fillMaxSize(), onFullscreen = toggleFullscreen)
            PlayerOverlay(session, Modifier.align(Alignment.Center))
        }
        InfoBar(session, nav)
        when {
            chat != null && AppState.showChat ->
                ChatPanel(chat, Modifier.weight(1f).navigationBarsPadding().imePadding()) { nav.push(Screen.Login) }
            vodChat != null -> VodChatList(vodChat, Modifier.weight(1f).navigationBarsPadding())
            else -> Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoBar(session: PlaybackSession, nav: Navigator) {
    var quality by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(session.avatar, 36.dp, Modifier.focusCard({ session.channelLogin?.let { nav.push(Screen.Channel(it)) } }))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session.title.ifEmpty { session.channelName ?: "" }, color = TU.text, fontSize = 14.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = mutableListOf<String>()
            session.channelName?.let { parts += it }
            if (session.isLive) {
                if (session.game.isNotEmpty()) parts += session.game
                parts += "● " + Format.viewers(session.viewers)
                Format.uptime(session.startedAt)?.let { parts += it }
            }
            session.sleepAt?.let { at ->
                parts += "⏾ " + Format.clock(maxOf(0L, (at - System.currentTimeMillis()) / 1000))
            }
            Text(parts.joinToString(" · "), color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { quality = true }, enabled = session.qualities.isNotEmpty()) {
                Text(session.quality?.let { Quality.label(it, AppState.lang) } ?: "—", color = TU.text, fontSize = 13.sp)
            }
            DropdownMenu(expanded = quality, onDismissRequest = { quality = false }) {
                for (q in session.qualities) {
                    DropdownMenuItem(
                        text = { Text(Quality.label(q, AppState.lang) + if (q == session.quality) "  ✓" else "") },
                        onClick = {
                            quality = false
                            session.selectQuality(q)
                        },
                    )
                }
                session.source?.let { src ->
                    DropdownMenuItem(text = { Text(AppState.t("live_source") + " : " + sourceName(src), color = TU.muted) },
                                     onClick = { quality = false }, enabled = false)
                }
            }
        }
        MoreMenu(session, nav)
        IconButton(onClick = { nav.pop() }) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text)
        }
    }
}

/** Menu ⋮ : minuterie, téléchargement (VOD), multi-live (direct). */
@Composable
private fun MoreMenu(session: PlaybackSession, nav: Navigator) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }   // 0 : menu, 1 : minuterie, 2 : qualité du téléchargement
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let { startDownload(context, session, it) }
        pendingDownload = null
    }
    Box {
        IconButton(onClick = { page = 0; open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = TU.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when (page) {
                1 -> {
                    for (m in sleepChoices) {
                        DropdownMenuItem(text = { Text(sleepLabel(m)) }, onClick = { open = false; session.setSleepTimer(m) })
                    }
                    if (session.sleepAt != null) {
                        DropdownMenuItem(text = { Text(AppState.t("sleep_cancel"), color = TU.danger) },
                                         onClick = { open = false; session.setSleepTimer(null) })
                    }
                }
                2 -> {
                    for (q in session.qualities.filter { !Quality.isAuto(it) }) {
                        DropdownMenuItem(text = { Text(Quality.label(q, AppState.lang)) }, onClick = {
                            open = false
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                pendingDownload = q
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startDownload(context, session, q)
                            }
                        })
                    }
                }
                else -> {
                    DropdownMenuItem(
                        text = { Text(AppState.t("sleep_timer") + (session.sleepAt?.let { "  ✓" } ?: "")) },
                        onClick = { page = 1 },
                    )
                    val vod = session.vodId
                    if (vod != null && !session.isOffline && session.state is PlaybackSession.State.Ready) {
                        val saved = Downloads.find(vod)
                        DropdownMenuItem(
                            text = { Text(if (saved != null) AppState.t("dl_section") + "  ✓" else AppState.t("dl_download")) },
                            onClick = { if (saved != null) { open = false; nav.tab(Screen.Library) } else page = 2 },
                        )
                    }
                    if (session.isLive) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(AppState.t("multi_add")) },
                                         onClick = { open = false; nav.multi(session.channelLogin) })
                    }
                }
            }
        }
    }
}

private fun startDownload(context: android.content.Context, session: PlaybackSession, quality: String) {
    val id = session.vodId ?: return
    val url = session.links[quality] ?: return
    Downloads.start(context, id, session.title, session.channelName ?: "", session.thumbnail,
                    session.lengthSeconds, quality, url)
}
