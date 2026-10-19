package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
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
    val clips = mutableStateListOf<Clip>()
    var clipsLoading by mutableStateOf(false)
        private set
    var clipsNext by mutableStateOf<String?>(null)
        private set
    var period by mutableStateOf("LAST_WEEK")
        private set
    private var clipsLoadedFor: String? = null
    /** Diffusions passées retrouvables (VODs retirées) ; null : pas encore lues. */
    var recovered by mutableStateOf<List<RecoverableStream>?>(null)
        private set
    var recoverLoading by mutableStateOf(false)
        private set
    var recoverFailed by mutableStateOf(false)
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
        if (info != null) return
        scope.launch { info = BrowseApi.channelInfo(login) }
        moreVideos()
    }

    fun moreVideos() {
        if (videosLoading) return
        if (videos.isNotEmpty() && videosNext == null) return
        videosLoading = true
        scope.launch {
            val page = BrowseApi.channelVideos(login, videosNext)
            val seen = videos.map { it.id }.toHashSet()
            videos.addAll(page.videos.filter { it.id !in seen }.distinctBy { it.id })
            videosNext = page.next
            videosError = page.error
            videosLoading = false
        }
    }

    fun loadClips(p: String = period) {
        if (clipsLoading) return
        if (p == clipsLoadedFor && clipsNext == null && clips.isNotEmpty()) return
        val fresh = p != clipsLoadedFor
        period = p
        clipsLoading = true
        scope.launch {
            val page = BrowseApi.clips(login, p, if (fresh) null else clipsNext)
            if (fresh) clips.clear()
            if (page != null) {
                val seen = clips.map { it.slug }.toHashSet()
                clips.addAll(page.items.filter { it.slug !in seen }.distinctBy { it.slug })
                clipsNext = if (page.refused) null else page.next
            }
            clipsLoadedFor = p
            clipsLoading = false
        }
    }
}

private val periods = listOf("LAST_DAY" to "clips_day", "LAST_WEEK" to "clips_week",
                             "LAST_MONTH" to "clips_month", "ALL_TIME" to "clips_all")

@Composable
fun ChannelScreen(login: String, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val model = remember(login) { ChannelModel(login, scope) }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(login) { model.load() }
    LaunchedEffect(tab) {
        if (tab == 1) model.loadClips()
        if (tab == 2) model.loadRecovered()
    }
    // Session web : le cœur suit la chaîne sur le compte Twitch ; sinon sur l'appareil.
    var twitchFollow by remember(login) { mutableStateOf<Boolean?>(null) }
    var twitchId by remember(login) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
            scope.launch {
                if (TwitchFollow.set(id, want) == null) twitchFollow = want
                busy = false
            }
        }
    }
    val info = model.info
    Column(Modifier.fillMaxSize()) {
        PageHeader(login, nav) {
            val usesTwitch = twitchFollow != null
            val followed = if (usesTwitch) twitchFollow == true else AppState.isFollowedLocally(login)
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
                    val hidden = Hidden.hidesChannel(login)
                    DropdownMenuItem(text = { Text(AppState.t(if (hidden) "remove" else "hide_channel") + if (hidden) " · " + AppState.t("hidden_badge") else "") },
                                     onClick = {
                                         menu = false
                                         if (hidden) Hidden.unhideChannel(login) else Hidden.hideChannel(login, login)
                                     })
                }
            }
        }
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = FullSpan) { ChannelHeader(login, info) { nav.play(PlayRequest.Live(login)) } }
            item(span = FullSpan) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabChip(AppState.t("tv_videos"), tab == 0) { tab = 0 }
                    TabChip(AppState.t("clips"), tab == 1) { tab = 1 }
                    TabChip(AppState.t("recover_tab"), tab == 2) { tab = 2 }
                }
            }
            if (tab == 2) {
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
            } else if (tab == 0) {
                items(model.videos, key = { "v-" + it.id }) { v ->
                    VodCard(v) { nav.play(PlayRequest.Vod(v.id, v.title, login)) }
                }
                when {
                    model.videosLoading -> item(span = FullSpan) { LoadingBox() }
                    model.videos.isEmpty() -> item(span = FullSpan) {
                        MessageBox(AppState.t(if (model.videosError == "network") "err_network" else "tv_no_videos"))
                    }
                    model.videosNext != null -> item(span = FullSpan) { MoreButton(false) { model.moreVideos() } }
                }
            } else {
                item(span = FullSpan) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((p, key) in periods) TabChip(AppState.t(key), model.period == p) { model.loadClips(p) }
                    }
                }
                items(model.clips, key = { "c-" + it.slug }) { c -> ClipCard(c) { nav.play(PlayRequest.Clip(c.slug)) } }
                when {
                    model.clipsLoading -> item(span = FullSpan) { LoadingBox() }
                    model.clips.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("no_clips")) }
                    model.clipsNext != null -> item(span = FullSpan) { MoreButton(false) { model.loadClips() } }
                }
            }
        }
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
