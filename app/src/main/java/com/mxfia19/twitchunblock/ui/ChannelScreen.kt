package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Clip
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.LiveInfo
import com.mxfia19.twitchunblock.core.Vod
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.ListCursor
import com.mxfia19.twitchunblock.core.NavApi
import com.mxfia19.twitchunblock.core.RecoverableStream
import com.mxfia19.twitchunblock.core.VodApi
import com.mxfia19.twitchunblock.notify.NotifChannels
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.web.TwitchFollow
import com.mxfia19.twitchunblock.web.WebSession
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Page d'une chaîne : direct en cours, VODs, clips. Partagé avec la TV. */
class ChannelModel(val login: String, private val scope: CoroutineScope) {
    var info by mutableStateOf<LiveInfo?>(null)
        private set
    val videos = mutableStateListOf<Vod>()
    var videosLoading by mutableStateOf(false)
        private set
    var videosNext by mutableStateOf<String?>(null)
        private set
    var videosError by mutableStateOf<String?>(null)
        private set
    /** Twitch refuse la suite des VODs (se connecter n'y changerait rien) : une note. */
    var videosRefused by mutableStateOf(false)
        private set
    /** « Chercher dans les anciennes VODs » en cours. */
    var searchingOlder by mutableStateOf(false)
        private set
    val clips = mutableStateListOf<Clip>()
    var clipsLoading by mutableStateOf(false)
        private set
    /** Suite des clips : GQL sans compte, Helix avec le compte si Twitch refuse. */
    var clipsNext by mutableStateOf<ListCursor?>(null)
        private set
    /** Twitch refuse la suite des clips et aucun compte ne prend le relais. */
    var clipsLocked by mutableStateOf(false)
        private set
    var period by mutableStateOf("LAST_WEEK")
        private set
    /** Twitch n'a pas répondu pour les clips : Réessayer, pas « Aucun clip ». */
    var clipsFailed by mutableStateOf(false)
        private set
    private var clipsLoadedFor: String? = null
    /** Diffusions passées retrouvables (VODs retirées) ; null : pas encore lues. */
    var recovered by mutableStateOf<List<RecoverableStream>?>(null)
        private set
    var recoverLoading by mutableStateOf(false)
        private set
    var recoverFailed by mutableStateOf(false)
        private set
    /** Tirer pour actualiser en cours (direct et première page des VODs). */
    var refreshing by mutableStateOf(false)
        private set

    fun loadRecovered() {
        if (recovered != null || recoverLoading) return
        recoverLoading = true
        recoverFailed = false
        scope.launch {
            val list = VodApi.recoverList(login)
            recoverFailed = list == null
            recovered = list
            recoverLoading = false
        }
    }

    fun load() {
        if (info == null) scope.launch { info = BrowseApi.channelInfo(login) }
        // Modèle gardé (retour sur la page) : pas de page de vidéos en plus.
        if (videos.isEmpty()) moreVideos()
    }

    /** Direct et première page des VODs relus (tirer pour actualiser) ; les
     *  VODs déjà là restent si Twitch ne répond pas. */
    fun refresh() {
        if (refreshing || videosLoading) return
        refreshing = true
        videosLoading = true
        scope.launch {
            val fresh = BrowseApi.channelInfo(login)
            if (fresh.error != "network" || info == null) info = fresh
            val page = BrowseApi.channelVideos(login, null)
            if (page.error == null && !page.refused) {
                videos.clear()
                videos.addAll(page.videos.distinctBy { it.id })
                videosNext = page.next
                videosError = null
                videosRefused = false
            }
            videosLoading = false
            refreshing = false
        }
    }

    fun moreVideos() {
        if (videosLoading) return
        if (videos.isNotEmpty() && videosNext == null) return
        videosLoading = true
        scope.launch {
            nextVideos()
            videosLoading = false
        }
    }

    /** Une page de VODs de plus ; faux si elle n'a pas été lue (panne, refus). */
    private suspend fun nextVideos(): Boolean {
        val page = BrowseApi.channelVideos(login, videosNext)
        val seen = videos.map { it.id }.toHashSet()
        videos.addAll(page.videos.filter { it.id !in seen }.distinctBy { it.id })
        videosNext = page.next
        videosError = page.error
        videosRefused = page.refused
        return page.error == null && !page.refused
    }

    /** Le filtre par mot-clé ne voit que les VODs chargées : page après page
     *  (5 au plus par toucher, soit 500 VODs), jusqu'à une nouvelle
     *  correspondance, la fin, une panne ou un refus. Comme searchOlderVods (iOS). */
    fun searchOlder(matches: (Vod) -> Boolean) {
        if (videosLoading || searchingOlder || videosNext == null) return
        searchingOlder = true
        videosLoading = true
        scope.launch {
            val before = videos.count(matches)
            for (i in 0 until 5) {
                if (videosNext == null || !nextVideos()) break
                if (videos.count(matches) > before) break
            }
            videosLoading = false
            searchingOlder = false
        }
    }

    fun loadClips(p: String = period) {
        if (clipsLoading) return
        if (p == clipsLoadedFor && clipsNext == null && clips.isNotEmpty()) return
        val fresh = p != clipsLoadedFor
        period = p
        clipsLoading = true
        clipsFailed = false
        scope.launch {
            if (fresh) {
                val page = BrowseApi.clips(login, p, null)
                clips.clear()
                // Rien de lu pour cette période tant que Twitch n'a pas répondu.
                clipsLoadedFor = null
                clipsNext = null
                clipsLocked = false
                if (page != null) {
                    clips.addAll(page.items.distinctBy { it.slug })
                    clipsNext = page.next?.let { ListCursor.Gql(it) }
                    clipsLoadedFor = p
                } else {
                    clipsFailed = true
                }
            } else {
                val c = clipsNext
                if (c != null) {
                    val r = NavApi.more(c, clips, { it.slug }, gql = { BrowseApi.clips(login, p, it) },
                                        helix = { after -> clipsHelix(p, after) })
                    clips.addAll(r.fresh)
                    clipsNext = r.cursor
                    clipsLocked = r.locked
                }
            }
            clipsLoading = false
        }
    }

    /** Connecté depuis le refus : Helix reprend du début. */
    fun clipsWithAccount() {
        if (!clipsLocked) return
        clipsLocked = false
        clipsNext = ListCursor.Helix(null)
        loadClips()
    }

    private suspend fun clipsHelix(p: String, after: String?): com.mxfia19.twitchunblock.core.Page<Clip>? {
        val id = info?.userId ?: NavApi.userId(login) ?: return null
        return NavApi.helixClips(id, p, after)
    }
}

private val periods = listOf("LAST_DAY" to "clips_day", "LAST_WEEK" to "clips_week",
                             "LAST_MONTH" to "clips_month", "ALL_TIME" to "clips_all")

@Composable
fun ChannelScreen(login: String, nav: Navigator) {
    val scope = rememberCoroutineScope()
    // Gardés tant que la page reste dans la pile (PhoneApp) : au retour d'un
    // live ou d'un clip, même onglet, même période, rien n'est relu.
    val model = rememberPageModel("channel:$login") { ChannelModel(login, it) }
    // Onglet touché ; vide : le premier affiché (Personnaliser l'app → onglets de la chaîne).
    var picked by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(login) { model.load() }
    // Planning (s'il y en a un) et À propos : ChannelPanels.kt. Temps forts et playlists : ChannelLists.kt.
    val extras = rememberPageModel("extras:$login") { ChannelExtrasModel(login, it) }
    val lists = rememberPageModel("lists:$login") { ChannelListsModel(login, it) }
    // Onglets dans l'ordre choisi ; le planning seulement si la chaîne en a un.
    val tabs = BrowsePrefs.visibleChannelTabs.filter { it != "schedule" || extras.hasSchedule }
    val tab = if (picked in tabs) picked else tabs.first()
    LaunchedEffect(tab) {
        // Clips déjà là (retour sur la page) : pas de page en plus.
        when (tab) {
            "clips" -> if (model.clips.isEmpty()) model.loadClips()
            "recover" -> model.loadRecovered()
            "about" -> extras.loadAbout()
            "highlights" -> lists.loadHighlights()
            "playlists" -> lists.loadPlaylists()
        }
    }
    LaunchedEffect(model.info) { model.info?.let { extras.loadSchedule(it.userId) } }
    // Session web : le cœur suit la chaîne sur le compte Twitch ; sinon sur l'appareil.
    var twitchFollow by remember(login) { mutableStateOf<Boolean?>(null) }
    var twitchId by remember(login) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    var bots by remember { mutableStateOf(false) }
    var offlineChat by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Refus de Twitch (code, vide s'il n'en donne pas) ; null : pas de refus.
    var followError by remember(login) { mutableStateOf<String?>(null) }
    LaunchedEffect(login, WebSession.token) {
        twitchFollow = null
        twitchId = null
        if (WebSession.active) {
            TwitchFollow.status(login)?.let { (following, id) ->
                twitchFollow = following
                twitchId = id
            }
        }
    }
    val sendFollow: (Boolean) -> Unit = { want ->
        val id = twitchId
        if (id != null && !busy) {
            busy = true
            followError = null
            scope.launch {
                val code = TwitchFollow.set(id, want)
                if (code == null) twitchFollow = want else followError = code
                busy = false
            }
        }
    }
    val info = model.info
    val usesTwitch = twitchFollow != null
    val followed = if (usesTwitch) twitchFollow == true else AppState.isFollowedLocally(login)
    Column(Modifier.fillMaxSize()) {
        PageHeader(login, nav) {
            // Cloche : notifications de live de cette chaîne seulement. Suivie sur
            // l'appareil, ou par le compte : le statut lu sur Twitch (session
            // web) fait foi quand il est connu, sinon la liste des suivis.
            NotifBell(login, followed = AppState.isFollowedLocally(login) || (twitchFollow ?: NotifChannels.followed(login)))
            IconButton(onClick = {
                when {
                    !usesTwitch -> AppState.toggleLocalFollow(login)
                    followed -> { confirmUnfollow = true }
                    else -> sendFollow(true)
                }
            }, enabled = !busy) {
                Icon(if (followed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                     contentDescription = AppState.t(if (followed) "unfollow" else "follow"),
                     tint = if (followed) TU.primary else TU.text)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = TU.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // Épingler en tête des suivies : pour une chaîne suivie (ou déjà épinglée).
                    val pinned = com.mxfia19.twitchunblock.core.Pins.isPinned(login)
                    if (pinned || followed || NotifChannels.followed(login)) {
                        DropdownMenuItem(text = { Text(AppState.t(if (pinned) "unpin_streamer" else "pin_streamer")) },
                                         onClick = { menu = false; com.mxfia19.twitchunblock.core.Pins.toggle(login) })
                    }
                    val hidden = Hidden.hidesChannel(login)
                    DropdownMenuItem(text = { Text(AppState.t(if (hidden) "remove" else "hide_channel") + if (hidden) " · " + AppState.t("hidden_badge") else "") },
                                     onClick = {
                                         menu = false
                                         if (hidden) Hidden.unhideChannel(login) else Hidden.hideChannel(login, login)
                                     })
                }
            }
        }
        if (bots) BotCommandsSheet(login, onDismiss = { bots = false })
        if (offlineChat) OfflineChatWindow(login, info?.userId, nav) { offlineChat = false }
        if (confirmUnfollow) {
            AlertDialog(
                onDismissRequest = { confirmUnfollow = false },
                title = { Text(AppState.t("unfollow_confirm").replace("{c}", login)) },
                confirmButton = {
                    TextButton(onClick = { confirmUnfollow = false; sendFollow(false) }) { Text(AppState.t("unfollow"), color = TU.danger) }
                },
                dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text(AppState.t("sleep_cancel"), color = TU.text) } },
                containerColor = TU.surface, titleContentColor = TU.text,
            )
        }
        RefreshBox(loading = model.refreshing, onRefresh = {
            model.refresh()
            // Onglet affiché relu aussi (temps forts, playlists).
            if (tab == "highlights") lists.loadHighlights(force = true)
            if (tab == "playlists") lists.loadPlaylists(force = true)
        }) {
        LazyVerticalGrid(
            columns = BrowseGrid.cards,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = FullSpan) { ChannelHeader(login, info) { nav.play(PlayRequest.Live(login)) } }
            // Suivie sur l'appareil seulement, refus de Twitch, chaîne masquée des listes.
            item(span = FullSpan) {
                ChannelStatusLines(localFollow = !usesTwitch && AppState.isFollowedLocally(login), followError = followError,
                                   hidden = Hidden.hidesChannel(login)) { Hidden.unhideChannel(login) }
            }
            // Commandes des bots (Nightbot, StreamElements…) : aussi hors ligne.
            // Hors ligne, le chat de la chaîne s'ouvre à côté : Twitch le garde
            // ouvert entre deux streams (comme l'iPhone et le site).
            item(span = FullSpan) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (info?.error == "offline") {
                        TextButton(onClick = { offlineChat = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text("💬 " + AppState.t("sec_chat"), color = TU.primary, fontSize = 14.sp)
                        }
                    }
                    TextButton(onClick = { bots = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("🤖 " + AppState.t("bot_commands"), color = TU.primary, fontSize = 14.sp)
                    }
                }
            }
            // Hors ligne : son prochain live prévu, ou ses vacances.
            val schedule = extras.schedule
            if (info?.error == "offline" && schedule != null) item(span = FullSpan) { ScheduleHeadline(schedule) }
            item(span = FullSpan) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in tabs) TabChip(AppState.t(channelTabLabel(key)), tab == key) { picked = key }
                }
            }
            when (tab) {
                "schedule" -> if (schedule != null) item(span = FullSpan) { ScheduleTab(schedule) }
                "about" -> {
                    val about = extras.about
                    when {
                        about != null -> item(span = FullSpan) { AboutTab(about, info?.login ?: login) }
                        extras.aboutFailed -> item(span = FullSpan) {
                            MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { extras.loadAbout() }
                        }
                        else -> item(span = FullSpan) { LoadingBox() }
                    }
                }
                "recover" -> {
                    item(span = FullSpan) { Text(AppState.t("recover_sub"), color = TU.muted, fontSize = 13.sp) }
                    val list = model.recovered
                    when {
                        model.recoverLoading -> item(span = FullSpan) { LoadingBox() }
                        model.recoverFailed || list == null -> item(span = FullSpan) {
                            MessageBox(AppState.t("recover_unavailable"), action = AppState.t("retry")) { model.loadRecovered() }
                        }
                        list.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("recover_empty")) }
                        else -> items(list, key = { "r-" + it.streamId }, span = { GridItemSpan(maxLineSpan) }) { st ->
                            RecoverRow(st) { nav.play(PlayRequest.Recovered(st)) }
                        }
                    }
                }
                "highlights" -> highlightsTab(lists, login, nav)
                "playlists" -> playlistsTab(lists, login, nav)
                "clips" -> {
                    item(span = FullSpan) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((p, key) in periods) TabChip(AppState.t(key), model.period == p) { model.loadClips(p) }
                        }
                    }
                    items(model.clips, key = { "c-" + it.slug }) { c -> ClipCard(c) { nav.play(PlayRequest.Clip(c.slug)) } }
                    when {
                        model.clipsLoading -> item(span = FullSpan) { LoadingBox() }
                        model.clipsFailed && model.clips.isEmpty() -> item(span = FullSpan) {
                            MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.loadClips(model.period) }
                        }
                        model.clips.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("no_clips")) }
                        model.clipsNext != null -> item(span = FullSpan) { MoreButton(false) { model.loadClips() } }
                        // Twitch refuse la suite : le compte la reprend (Helix), sinon une note.
                        model.clipsLocked && AppState.token != null -> item(span = FullSpan) { MoreButton(false) { model.clipsWithAccount() } }
                        model.clipsLocked -> item(span = FullSpan) { ListEndNote("more_needs_login") }
                    }
                }
                else -> {
                    // Filtre par mot-clé : seulement s'il y a de quoi filtrer.
                    if (model.videos.isNotEmpty()) item(key = "vod-filter", span = FullSpan) { VodFilterField(keyword) { keyword = it } }
                    val shown = model.videos.filter { vodMatches(it, keyword) }
                    items(shown, key = { "v-" + it.id }) { v ->
                        VodCard(v, onLongClick = multiVodToggle(v.id, v.title, v.thumbnail, login)) { nav.play(PlayRequest.Vod(v.id, v.title, login)) }
                    }
                    val filtering = keyword.isNotBlank()
                    when {
                        model.videosLoading -> item(span = FullSpan) { LoadingBox() }
                        model.videos.isEmpty() && model.videosError == "network" -> item(span = FullSpan) {
                            MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.moreVideos() }
                        }
                        model.videos.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("tv_no_videos")) }
                        // Le filtre ne voit que les VODs chargées : de quoi chercher plus loin.
                        filtering && model.videosNext != null -> {
                            if (shown.isEmpty()) item(span = FullSpan) { MessageBox(AppState.t("no_result")) }
                            item(span = FullSpan) {
                                MoreButtonText(AppState.t("vods_search_older")) { model.searchOlder { vodMatches(it, keyword) } }
                            }
                        }
                        filtering && shown.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("no_result")) }
                        model.videosNext != null -> item(span = FullSpan) { MoreButton(false) { model.moreVideos() } }
                        model.videosRefused -> item(span = FullSpan) { ListEndNote("more_unavailable") }
                    }
                }
            }
        }
        }
    }
}

/** Texte d'un onglet de la page d'une chaîne (clés passées par une variable : _indirect). */
fun channelTabLabel(key: String): String = when (key) {
    "highlights" -> "highlights"
    "playlists" -> "playlists"
    "clips" -> "clips"
    "recover" -> "recover_tab"
    "schedule" -> "sched_tab"
    "about" -> "about"
    else -> "tv_videos"
}

/** Bouton « Charger plus » avec un autre texte (Chercher dans les anciennes VODs). */
@Composable
private fun MoreButtonText(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(text, color = TU.text) }
    }
}

/** Diffusion passée retrouvable : titre, jeu, date, durée. */
@Composable
private fun RecoverRow(s: RecoverableStream, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusCard(onClick).background(TU.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TU.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifEmpty { AppState.t("recover_title") }, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(listOf(s.game, Format.shortDate(s.epoch * 1000), if (s.duration > 0) Format.duration(s.duration) else "",
                        if (s.maxViews > 0) "● " + Format.viewers(s.maxViews) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                 color = TU.muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun ChannelHeader(login: String, info: LiveInfo?, onWatch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(info?.avatar, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(login, color = TU.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val sub = when {
                    info == null -> ""
                    info.error == null -> "● " + AppState.tn("viewers", info.viewers).let { "${Format.viewers(info.viewers)} $it" }
                    info.error == "offline" -> AppState.t("offline") +
                        (Format.parseDate(info.lastStart)?.let { " · " + Format.since(it) } ?: "")
                    else -> AppState.t(com.mxfia19.twitchunblock.player.PlaybackSession.errorKey(info.error ?: ""))
                }
                Text(sub, color = if (info?.error == null) TU.live else TU.muted, fontSize = 13.sp)
            }
        }
        if (info != null && info.error == null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(TU.surface).padding(12.dp),
            ) {
                Text(info.title, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
                if (info.game.isNotEmpty()) Text(info.game, color = TU.muted, fontSize = 13.sp)
                Button(
                    onClick = onWatch,
                    colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppState.t("tv_watch_live"))
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = TU.primary, selectedLabelColor = androidx.compose.ui.graphics.Color.White,
            labelColor = TU.text,
        ),
    )
}
