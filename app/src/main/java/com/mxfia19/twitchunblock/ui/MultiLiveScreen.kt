package com.mxfia19.twitchunblock.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Multi-live : jusqu'à 4 vidéos côte à côte, directs et VODs mêlés (série 6),
 * un seul avec le son (toucher une vidéo pour l'entendre ; sur le téléphone,
 * le haut-parleur d'une tuile en ajoute d'autres : MultiSound.kt). Chaque
 * tuile est en qualité Auto, plafonnée (capHeight) pour que le débit et le
 * décodeur suivent. Une tuile se tait pendant la pause pub de sa chaîne ; une
 * tuile en échec repart seule au retour du réseau. Partagé avec la TV.
 */
class MultiLiveModel(private val context: Context, private val scope: CoroutineScope) {
    /** `login` : la chaîne (vide pour une VOD ajoutée par lien, tant qu'elle n'a pas démarré). */
    class Tile(val login: String, val session: PlaybackSession, val entry: MultiEntry = MultiEntry(login, login)) {
        /** Clé du son et de la sélection : une VOD n'est pas le direct de sa chaîne. */
        val key: String get() = entry.key
        val isVod: Boolean get() = entry.isVod
        /** Nom affiché : la chaîne d'une VOD une fois sa tuile démarrée. */
        val name: String
            get() = if (!entry.isVod) entry.displayName
                    else session.channelName?.takeIf { it.isNotEmpty() } ?: entry.displayName
        /** « VOD · nom » pour une VOD, le nom pour un direct. */
        val label: String get() = if (entry.isVod) "VOD · $name" else name

        /** Entrée gardée (MultiPicks) : chaîne, titre et vignette d'une VOD complétés. */
        fun current(): MultiEntry {
            if (!entry.isVod) return entry
            return entry.copy(
                login = session.channelLogin ?: entry.login,
                name = session.channelName ?: entry.name,
                title = session.title.ifEmpty { entry.title ?: "" }.ifEmpty { null },
                thumb = session.thumbnail.ifEmpty { entry.thumb ?: "" }.ifEmpty { null },
            )
        }
    }

    val tiles = mutableStateListOf<Tile>()
    var audio by mutableStateOf(0)
        private set
    /** Première chaîne déjà ajoutée : l'écran recomposé (retour de la
     *  connexion) ne la rajoute pas. */
    var started = false
    /** Libéré (mosaïque fermée) : plus rien n'est gardé ni relancé. */
    var released = false
        private set
    /** Directs suivis à proposer. */
    var suggestions by mutableStateOf<List<TwitchStream>>(emptyList())
        private set

    val full: Boolean get() = tiles.size >= MAX

    /** Tuiles qui ont le son et leur volume (MultiSound.kt). */
    val sound = MultiSound()

    /** Début et fin d'une pause pub, sur chaque tuile : volumes reposés. */
    private val adWatch = scope.launch {
        snapshotFlow { tiles.map { it.session.adBreaks.remaining != null } }
            .distinctUntilChanged()
            .collect { applyVolumes() }
    }

    /** Réseau revenu après une coupure : les tuiles en échec repartent seules,
     *  une fois, deux secondes après (comme l'iPhone). */
    private val network = object : ConnectivityManager.NetworkCallback() {
        @Volatile var lost = false

        override fun onLost(network: Network) {
            lost = true
        }

        override fun onAvailable(network: Network) {
            if (!lost) return
            lost = false
            scope.launch {
                delay(2_000)
                retryFailed()
            }
        }
    }
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    init {
        try {
            connectivity?.registerDefaultNetworkCallback(network)
        } catch (e: Exception) {
            Logger.warn("MULTI", "Suivi du réseau indisponible", e.message)
        }
    }

    /** Une chaîne, ou une VOD (lien …/videos/<id>, « ?t= » compris, ou numéro), comme le site. */
    fun add(input: String): Boolean {
        val text = input.trim()
        if (Links.isVod(text)) {
            val id = Links.vodId(text) ?: return false
            return addEntry(MultiEntry("", "", vodId = id, startMs = Links.vodStart(text)?.let { it * 1000L } ?: -1L))
        }
        val login = Links.login(text)
        if (!Links.isValidLogin(login)) return false
        return addEntry(MultiEntry(login, login))
    }

    fun addEntry(entry: MultiEntry): Boolean {
        if (full || tiles.any { it.key == entry.key }) return false
        val session = PlaybackSession(context, scope, main = false, audioFocus = false).apply { forceAuto = true }
        val tile = Tile(entry.login, session, entry)
        tiles += tile
        sound.watch(session.player) { applyVolumes() }
        val vod = entry.vodId
        // VOD : reprise là où on l'avait laissée (ou à l'instant d'un lien), historique et synchro.
        session.open(if (vod != null) PlayRequest.Vod(vod, entry.title ?: "", entry.login.ifEmpty { null }, entry.startMs)
                     else PlayRequest.Live(entry.login))
        if (tiles.size == 1) {
            audio = 0
            sound.only(tile.key)
        }
        layout()
        return true
    }

    fun remove(index: Int) {
        if (index !in tiles.indices) return
        val wasMain = index == audio
        val gone = tiles.removeAt(index)
        sound.forget(gone.key, gone.session.player)
        gone.session.release()
        // Tuile retirée avant celle qui a le son : le son reste sur la même chaîne.
        if (index < audio) audio--
        if (audio >= tiles.size) audio = maxOf(0, tiles.size - 1)
        // Principale retirée : la nouvelle a le son, comme avant.
        if (wasMain) tiles.getOrNull(audio)?.let { sound.add(it.key) }
        layout()
    }

    fun selectAudio(index: Int) {
        if (index !in tiles.indices) return
        audio = index
        // Toucher une tuile : elle seule a le son, comme avant.
        sound.only(tiles[index].key)
        layout()
    }

    /** ⇄ : deux tuiles échangent leur place (avec la première, une vidéo
     *  passe en grand à trois) ; le son suit sa vidéo. */
    fun swap(i: Int, j: Int) {
        if (i !in tiles.indices || j !in tiles.indices || i == j) return
        val a = tiles[i]
        tiles[i] = tiles[j]
        tiles[j] = a
        audio = when (audio) {
            i -> j
            j -> i
            else -> audio
        }
        layout()
    }

    /** Position (s) de chaque VOD : lien partagé. */
    fun positions(): Map<String, Long> =
        tiles.filter { it.isVod }.associate { it.key to it.session.player.currentPosition / 1000 }

    private fun retryFailed() {
        if (released) return
        val failed = tiles.filter { it.session.state is PlaybackSession.State.Error }
        if (failed.isEmpty()) return
        Logger.info("MULTI", "Réseau revenu : tuiles relancées", failed.joinToString(", ") { it.key })
        failed.forEach { it.session.retry() }
    }

    /** Plafond d'une tuile, comme l'iPhone : 1080p seule, 720p à deux, 480p
     *  au-delà, ou la qualité préférée si elle est plus basse ; en données
     *  mobiles, celle choisie pour elles (Auto : 720p au plus ; audio seul :
     *  la vidéo la plus basse, une tuile ayant besoin d'image). */
    private fun capHeight(): Int {
        val rule = when {
            tiles.size <= 1 -> 1080
            tiles.size == 2 -> 720
            else -> 480
        }
        var want = AppState.qualityPref.toIntOrNull()?.let { minOf(it, rule) } ?: rule
        if (com.mxfia19.twitchunblock.core.NetworkStatus.isCellular) {
            when (val cell = AppState.cellularQuality) {
                "same" -> {}
                "auto" -> want = minOf(want, 720)
                "audio" -> want = 160
                else -> want = minOf(cell.toIntOrNull() ?: want, rule)
            }
        }
        return want
    }

    private fun layout() {
        val cap = capHeight()
        tiles.forEachIndexed { i, t ->
            t.session.setMaxHeight(cap)
            if (i != audio) t.session.handleAudioFocus(false)
        }
        // Focus audio : rendu par les autres tuiles, puis pris par celle qui a
        // le son. Pris par chaque tuile, il passait à la dernière ajoutée
        // (muette) et les autres s'arrêtaient.
        tiles.getOrNull(audio)?.session?.handleAudioFocus(true)
        applyVolumes()
    }

    /** Son de chaque tuile : celles de `sound.on`, à leur volume (les autres
     *  se taisent quand la principale perd le focus audio). Pendant la pause
     *  pub de sa chaîne, une tuile se tait (comme l'iPhone) : son bandeau le dit. */
    private fun applyVolumes() {
        val main = tiles.getOrNull(audio)?.session?.player
        tiles.forEachIndexed { i, t ->
            val ad = t.session.adBreaks.remaining != null
            t.session.setVolume(if (ad) 0f else sound.volumeFor(t.key, i == audio, main))
        }
    }

    /** Haut-parleur d'une tuile (téléphone) : son ajouté aux autres, ou coupé. */
    fun toggleSound(index: Int) {
        val t = tiles.getOrNull(index) ?: return
        sound.toggle(t.key)
        applyVolumes()
    }

    /** Volume d'une tuile (appui long sur son haut-parleur) ; il lui rend le son. */
    fun setVolume(index: Int, volume: Float) {
        val t = tiles.getOrNull(index) ?: return
        sound.setVolume(t.key, volume)
        applyVolumes()
    }

    fun soundOf(index: Int): TileSound {
        val key = tiles[index].key
        return TileSound(key in sound.on, sound.volume(key), { toggleSound(index) }, { setVolume(index, it) })
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
        released = true
        try {
            connectivity?.unregisterNetworkCallback(network)
        } catch (e: Exception) {
            // Jamais enregistré (suivi du réseau indisponible).
        }
        adWatch.cancel()
        tiles.forEach { it.session.release() }
        tiles.clear()
    }

    companion object {
        const val MAX = 4
    }
}

/** Une tuile : l'image (sans commandes), puis sur le téléphone ses commandes
 *  (`controls`, MultiTileUi.kt) ; sur la TV, le nom et l'état. */
@OptIn(UnstableApi::class)
@Composable
fun MultiTile(tile: MultiLiveModel.Tile, hasAudio: Boolean, modifier: Modifier, onSelect: () -> Unit, onRemove: (() -> Unit)?,
              sound: TileSound? = null, controls: TileControls? = null) {
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
                }
            },
            update = { v ->
                if (v.player !== tile.session.player) v.player = tile.session.player
                // Écran allumé tant qu'une tuile joue.
                v.keepScreenOn = tile.session.isPlaying
            },
            // Vue retirée (tuile enlevée, rotation) : elle lâche le lecteur.
            onRelease = { v -> v.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (controls != null) {
            PhoneTileOverlay(tile, hasAudio, sound, controls, onRemove)
        } else {
            when (val s = tile.session.state) {
                is PlaybackSession.State.Loading -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.align(Alignment.Center))
                is PlaybackSession.State.Error -> Text(AppState.t(if (s.key == "offline_msg" || s.key == "not_found") "multi_offline" else "multi_failed"),
                                                       color = TU.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                PlaybackSession.State.Ready -> {}
            }
            // Pause pub de la chaîne : bandeau d'une ligne (StreamUi.kt), à droite
            // du haut-parleur sur le téléphone (il en cachait le début).
            AdBreakBanner(tile.session.adBreaks, compact = true,
                          modifier = Modifier.align(Alignment.BottomStart).padding(start = if (sound != null) 48.dp else 4.dp,
                                                                                     top = 4.dp, end = 4.dp, bottom = 4.dp))
            Text(
                (if (hasAudio && sound == null) "🔊 " else "") + tile.label,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Close, contentDescription = AppState.t("multi_remove"), tint = Color.White)
                }
            }
            // Téléphone : haut-parleur de la tuile (plusieurs sons, volume).
            if (sound != null) TileSoundButton(sound, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    }
}

/** La mosaïque : 1 en plein, 2 l'une sous l'autre (côte à côte en paysage),
 *  3 avec la première en grand, 4 en 2 × 2. `onOpen` (téléphone) : commandes
 *  des tuiles, et ouvrir une vidéo dans le lecteur. */
@Composable
fun MultiGrid(model: MultiLiveModel, landscape: Boolean, modifier: Modifier, removable: Boolean = true,
              onOpen: ((MultiLiveModel.Tile) -> Unit)? = null) {
    val tiles = model.tiles
    @Composable
    fun tile(i: Int, m: Modifier) = MultiTile(
        tiles[i], i == model.audio, m, { model.selectAudio(i) },
        if (removable) ({ model.remove(i) }) else null,
        if (removable) model.soundOf(i) else null,
        if (onOpen != null) TileControls(tiles.indices.filter { it != i }.map { it to tiles[it].label },
                                         { j -> model.swap(i, j) }, { onOpen(tiles[i]) }) else null,
    )
    when (tiles.size) {
        0 -> Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text(AppState.t("multi_add_sub"), color = TU.muted, fontSize = 14.sp)
        }
        1 -> tile(0, modifier)
        2 -> if (landscape) Row(modifier) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
             else Column(modifier) { tile(0, Modifier.weight(1f).fillMaxWidth()); tile(1, Modifier.weight(1f).fillMaxWidth()) }
        // À trois, la première en grand (⇄ pour en choisir une autre), comme l'iPhone.
        3 -> if (landscape) Row(modifier) {
                 tile(0, Modifier.weight(1f).fillMaxSize())
                 Column(Modifier.weight(1f).fillMaxHeight()) {
                     tile(1, Modifier.weight(1f).fillMaxWidth())
                     tile(2, Modifier.weight(1f).fillMaxWidth())
                 }
             } else Column(modifier) {
                 tile(0, Modifier.weight(1f).fillMaxWidth())
                 Row(Modifier.weight(1f)) { tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize()) }
             }
        else -> Column(modifier) {
            Row(Modifier.weight(1f)) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
            Row(Modifier.weight(1f)) { tile(2, Modifier.weight(1f).fillMaxSize()); tile(3, Modifier.weight(1f).fillMaxSize()) }
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

/** `model` : porté par PhoneApp tant que le multi-live est dans la pile ; il
 *  garde ses tuiles sous la connexion ouverte depuis le chat (libérées avant,
 *  seule la première revenait). */
@Composable
fun MultiLiveScreen(model: MultiLiveModel, initial: String?, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(model) {
        if (!model.started) {
            model.started = true
            // Celle demandée (menu du lecteur) d'abord, puis les vidéos choisies
            // depuis les cartes ou gardées de la dernière fois (MultiPicks).
            initial?.let { model.add(it) }
            for (e in MultiPicks.entries.toList()) model.addEntry(e)
        }
        launch { model.loadSuggestions() }
        // La mosaïque tient la sélection à jour (barre du bas, prochaine ouverture).
        snapshotFlow { model.tiles.map { it.current() } }.collect { if (!model.released) MultiPicks.replace(it) }
    }
    // Tuiles en pause sous un autre écran (connexion), reprises au retour.
    DisposableEffect(model) {
        model.playAll()
        onDispose { model.pauseAll() }
    }
    PauseInBackground(model)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Paysage : plein écran, barres du système masquées (un glissement les rappelle), comme le lecteur.
    val activity = context as? Activity
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
    // Chat de la vidéo qui a le son : celui du direct, ou rejoué pour une VOD.
    val audioTile = model.tiles.getOrNull(model.audio)
    val audioLogin = audioTile?.takeIf { !it.isVod }?.login
    val chat = remember(audioLogin) { audioLogin?.let { ChatClient(it, scope) } }
    DisposableEffect(chat) {
        chat?.start(model.tiles.getOrNull(model.audio)?.session?.channelId)
        onDispose { chat?.stop() }
    }
    val vodChat = if (audioTile != null && audioTile.isVod) rememberVodChat(audioTile.session, AppState.showChat) else null
    // Ouvrir une vidéo dans le lecteur : une VOD reprend là où en était sa tuile.
    val open: (MultiLiveModel.Tile) -> Unit = { t ->
        val vod = t.entry.vodId
        nav.play(if (vod != null) PlayRequest.Vod(vod, t.session.title, t.session.channelLogin ?: t.entry.login.ifEmpty { null },
                                                  startMs = t.session.player.currentPosition.coerceAtLeast(0L))
                 else PlayRequest.Live(t.entry.login))
    }

    if (landscape) {
        val hasChat = AppState.showChat && (chat != null || vodChat != null)
        val chatWidth = (LocalConfiguration.current.screenWidthDp * 0.32f).coerceIn(240f, 380f).dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                MultiGrid(model, landscape = true, modifier = Modifier.fillMaxSize(), onOpen = open)
                // Chat montré ou replié (aussi dans Réglages → Lecteur).
                if (hasChat) {
                    Box(Modifier.align(Alignment.CenterEnd).padding(4.dp).size(36.dp).clip(CircleShape)
                            .background(Color(0x99000000)).clickable { MultiPrefs.chat.set(!MultiPrefs.chat.value) },
                        contentAlignment = Alignment.Center) {
                        Icon(MediaIcons.chat, contentDescription = AppState.t("multi_chat"),
                             tint = if (MultiPrefs.chat.value) TU.primary else Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (hasChat && MultiPrefs.chat.value) {
                Box(Modifier.width(chatWidth).fillMaxHeight().background(TU.bg)) {
                    if (chat != null) {
                        ChatPanel(chat, Modifier.fillMaxSize().navigationBarsPadding().imePadding()) { nav.push(Screen.Login) }
                    } else if (vodChat != null) {
                        VodChatList(vodChat, Modifier.fillMaxSize())
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // Série 6 (SleepTimer.kt) : minuterie du multi-live.
        PageHeader(AppState.t("multi_title"), nav) { MultiSleepButton(model) }
        MultiGrid(model, landscape = false,
                  modifier = Modifier.fillMaxWidth().aspectRatio(if (model.tiles.size >= 2) 16f / 18f else 16f / 9f),
                  onOpen = open)
        AddRow(model)
        if (chat != null && AppState.showChat) {
            ChatPanel(chat, Modifier.weight(1f).navigationBarsPadding().imePadding()) { nav.push(Screen.Login) }
        } else if (vodChat != null) {
            VodChatList(vodChat, Modifier.weight(1f).navigationBarsPadding())
        }
    }
}

@Composable
private fun AddRow(model: MultiLiveModel) {
    val context = LocalContext.current
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
            val known = model.tiles.map { it.key }.toSet()
            val chips = model.suggestions.filter { it.login.lowercase() !in known }
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(chips, key = { it.login }) { s ->
                        TabChip("+ " + s.name, false) { model.add(s.login) }
                    }
                }
            }
        }
        if (model.tiles.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.tiles.size > 1) AppState.t("multi_hint") else "", color = TU.muted, fontSize = 12.sp,
                     modifier = Modifier.weight(1f))
                // Lien du site vers cette mosaïque (avec l'instant de chaque VOD), comme le site.
                TextButton(onClick = {
                    shareViaSheet(context, MultiPicks.shareUrl(model.tiles.map { it.current() }, model.positions()))
                }) { Text(AppState.t("share"), color = TU.primary, fontSize = 13.sp) }
            }
        }
    }
}
