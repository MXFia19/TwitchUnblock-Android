package com.mxfia19.twitchunblock.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.AuthExpiredException
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Links
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.CoroutineScope

/**
 * Multi-live : jusqu'à 4 directs côte à côte, un seul avec le son (toucher
 * une vidéo pour l'entendre). Chaque tuile est en qualité Auto, plafonnée
 * (720p à 2, 480p à 3 ou 4) pour que le débit et le décodeur suivent.
 * Partagé avec la TV.
 */
class MultiLiveModel(private val context: Context, private val scope: CoroutineScope) {
    class Tile(val login: String, val session: PlaybackSession)

    val tiles = mutableStateListOf<Tile>()
    var audio by mutableStateOf(0)
        private set
    /** Directs suivis à proposer. */
    var suggestions by mutableStateOf<List<TwitchStream>>(emptyList())
        private set

    val full: Boolean get() = tiles.size >= MAX

    fun add(input: String): Boolean {
        val login = Links.login(input)
        if (!Links.isValidLogin(login) || tiles.any { it.login == login } || full) return false
        val session = PlaybackSession(context, scope, main = false).apply { forceAuto = true }
        tiles += Tile(login, session)
        session.open(PlayRequest.Live(login))
        if (tiles.size == 1) audio = 0
        layout()
        return true
    }

    fun remove(index: Int) {
        if (index !in tiles.indices) return
        tiles.removeAt(index).session.release()
        if (audio >= tiles.size) audio = maxOf(0, tiles.size - 1)
        layout()
    }

    fun selectAudio(index: Int) {
        if (index !in tiles.indices) return
        audio = index
        layout()
    }

    private fun layout() {
        val cap = if (tiles.size <= 2) 720 else 480
        tiles.forEachIndexed { i, t ->
            t.session.setMaxHeight(cap)
            t.session.setVolume(if (i == audio) 1f else 0f)
        }
    }

    fun pauseAll() = tiles.forEach { it.session.player.pause() }

    fun playAll() = tiles.forEach { it.session.player.play() }

    suspend fun loadSuggestions() {
        val account = if (AppState.user != null) {
            try { BrowseApi.followedStreams() } catch (e: AuthExpiredException) { null }
        } else null
        val local = BrowseApi.liveByLogins(AppState.localFollows.toList()) ?: emptyList()
        suggestions = ((account ?: emptyList()) + local).distinctBy { it.login }.sortedByDescending { it.viewers }
    }

    fun release() {
        tiles.forEach { it.session.release() }
        tiles.clear()
    }

    companion object {
        const val MAX = 4
    }
}

/** Une tuile : l'image (sans commandes), le nom, le son, le bouton retirer. */
@OptIn(UnstableApi::class)
@Composable
fun MultiTile(tile: MultiLiveModel.Tile, hasAudio: Boolean, modifier: Modifier, onSelect: () -> Unit, onRemove: (() -> Unit)?) {
    Box(
        modifier
            .background(Color.Black)
            .border(2.dp, if (hasAudio) TU.primary else Color.Transparent)
            .clickable(onClick = onSelect),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = tile.session.player
                    keepScreenOn = true
                }
            },
            update = { v -> if (v.player !== tile.session.player) v.player = tile.session.player },
            modifier = Modifier.fillMaxSize(),
        )
        when (val s = tile.session.state) {
            is PlaybackSession.State.Loading -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.align(Alignment.Center))
            is PlaybackSession.State.Error -> Text(AppState.t(if (s.key == "offline_msg" || s.key == "not_found") "multi_offline" else "multi_failed"),
                                                   color = TU.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            PlaybackSession.State.Ready -> {}
        }
        Text(
            (if (hasAudio) "🔊 " else "") + tile.login,
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, contentDescription = AppState.t("multi_remove"), tint = Color.White)
            }
        }
    }
}

/** La mosaïque : 1 en plein, 2 l'une sous l'autre (côte à côte en paysage), 3 ou 4 en 2 × 2. */
@Composable
fun MultiGrid(model: MultiLiveModel, landscape: Boolean, modifier: Modifier, removable: Boolean = true) {
    val tiles = model.tiles
    @Composable
    fun tile(i: Int, m: Modifier) = MultiTile(tiles[i], i == model.audio, m, { model.selectAudio(i) },
                                              if (removable) ({ model.remove(i) }) else null)
    when (tiles.size) {
        0 -> Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text(AppState.t("multi_add_sub"), color = TU.muted, fontSize = 14.sp)
        }
        1 -> tile(0, modifier)
        2 -> if (landscape) Row(modifier) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
             else Column(modifier) { tile(0, Modifier.weight(1f).fillMaxWidth()); tile(1, Modifier.weight(1f).fillMaxWidth()) }
        else -> Column(modifier) {
            Row(Modifier.weight(1f)) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
            Row(Modifier.weight(1f)) {
                tile(2, Modifier.weight(1f).fillMaxSize())
                if (tiles.size > 3) tile(3, Modifier.weight(1f).fillMaxSize()) else Box(Modifier.weight(1f).fillMaxSize().background(Color.Black))
            }
        }
    }
}

/** Pause quand l'app passe en arrière-plan, reprise au retour. */
@Composable
fun PauseInBackground(model: MultiLiveModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> model.pauseAll()
                Lifecycle.Event.ON_START -> model.playAll()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun MultiLiveScreen(initial: String?, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { MultiLiveModel(context, scope) }
    LaunchedEffect(Unit) {
        initial?.let { model.add(it) }
        model.loadSuggestions()
    }
    DisposableEffect(Unit) { onDispose { model.release() } }
    PauseInBackground(model)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val audioLogin = model.tiles.getOrNull(model.audio)?.login
    val chat = remember(audioLogin) { audioLogin?.let { ChatClient(it, scope) } }
    DisposableEffect(chat) {
        chat?.start(model.tiles.getOrNull(model.audio)?.session?.channelId)
        onDispose { chat?.stop() }
    }

    if (landscape) {
        MultiGrid(model, landscape = true, modifier = Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("multi_title"), nav)
        MultiGrid(model, landscape = false,
                  modifier = Modifier.fillMaxWidth().aspectRatio(if (model.tiles.size >= 2) 16f / 18f else 16f / 9f))
        AddRow(model)
        if (chat != null && AppState.showChat) {
            ChatPanel(chat, Modifier.weight(1f).navigationBarsPadding().imePadding()) { nav.push(Screen.Login) }
        }
    }
}

@Composable
private fun AddRow(model: MultiLiveModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(TU.surface).padding(8.dp)) {
        if (model.full) {
            Text(AppState.t("multi_full"), color = TU.muted, fontSize = 13.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text(AppState.t("multi_add_ph"), color = TU.muted, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                              focusedTextColor = TU.text, unfocusedTextColor = TU.text),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (model.add(text)) text = "" }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                       modifier = Modifier.padding(start = 8.dp)) { Text(AppState.t("multi_add_ok")) }
            }
            val known = model.tiles.map { it.login }.toSet()
            val chips = model.suggestions.filter { it.login.lowercase() !in known }
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(chips, key = { it.login }) { s ->
                        TabChip("+ " + s.name, false) { model.add(s.login) }
                    }
                }
            }
        }
        if (model.tiles.size > 1) Text(AppState.t("multi_hint"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
