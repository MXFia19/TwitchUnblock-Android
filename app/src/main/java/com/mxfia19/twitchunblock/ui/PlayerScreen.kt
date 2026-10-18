package com.mxfia19.twitchunblock.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession

/** Image du lecteur (PlayerView de Media3 et ses commandes). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(session: PlaybackSession, modifier: Modifier = Modifier, onFullscreen: (() -> Unit)? = null,
                 focusOnStart: Boolean = false) {
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
        update = { view -> if (view.player !== session.player) view.player = session.player },
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
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Plein écran en paysage : barres du système masquées (un glissement les rappelle).
    DisposableEffect(landscape) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) {
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
    var chatInLandscape by remember { mutableStateOf(false) }
    val toggleFullscreen = {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                         else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoSurface(session, Modifier.fillMaxSize(), onFullscreen = toggleFullscreen)
                PlayerOverlay(session, Modifier.align(Alignment.Center))
                if (chat != null && AppState.showChat) {
                    IconButton(onClick = { chatInLandscape = !chatInLandscape }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Email, contentDescription = AppState.t("tv_chat"), tint = Color.White)
                    }
                }
            }
            if (chat != null && chatInLandscape) {
                ChatPanel(chat, Modifier.width(330.dp).fillMaxHeight().imePadding()) { nav.push(Screen.Login) }
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
        if (chat != null && AppState.showChat) {
            ChatPanel(chat, Modifier.weight(1f).navigationBarsPadding().imePadding()) { nav.push(Screen.Login) }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoBar(session: PlaybackSession, nav: Navigator) {
    var menu by remember { mutableStateOf(false) }
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
            Text(parts.joinToString(" · "), color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = session.qualities.isNotEmpty()) {
                Text(session.quality?.let { Quality.label(it, AppState.lang) } ?: "—", color = TU.text, fontSize = 13.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (q in session.qualities) {
                    DropdownMenuItem(
                        text = { Text(Quality.label(q, AppState.lang) + if (q == session.quality) "  ✓" else "") },
                        onClick = {
                            menu = false
                            session.selectQuality(q)
                        },
                    )
                }
                session.source?.let { src ->
                    DropdownMenuItem(text = { Text(AppState.t("live_source") + " : " + sourceName(src), color = TU.muted) },
                                     onClick = { menu = false }, enabled = false)
                }
            }
        }
        IconButton(onClick = { nav.pop() }) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text)
        }
    }
}
