package com.mxfia19.twitchunblock.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.ui.CategoryCard
import com.mxfia19.twitchunblock.ui.CategoryModel
import com.mxfia19.twitchunblock.ui.ChannelHeader
import com.mxfia19.twitchunblock.ui.ChannelModel
import com.mxfia19.twitchunblock.ui.ChannelRow
import com.mxfia19.twitchunblock.ui.ClipCard
import com.mxfia19.twitchunblock.ui.FollowingModel
import com.mxfia19.twitchunblock.ui.HomeModel
import com.mxfia19.twitchunblock.ui.LoadingBox
import com.mxfia19.twitchunblock.ui.MessageBox
import com.mxfia19.twitchunblock.ui.SearchModel
import com.mxfia19.twitchunblock.ui.StreamCard
import com.mxfia19.twitchunblock.ui.SuggestionRow
import com.mxfia19.twitchunblock.ui.TU
import com.mxfia19.twitchunblock.ui.VodCard

sealed class TvScreen {
    object Tabs : TvScreen()
    data class Channel(val login: String) : TvScreen()
    data class CategoryPage(val category: Category) : TvScreen()
    data class Player(val request: PlayRequest) : TvScreen()
    data class MultiLive(val first: String?) : TvScreen()
}

class TvNav {
    val stack = mutableStateListOf<TvScreen>(TvScreen.Tabs)
    fun push(s: TvScreen) {
        if (s is TvScreen.Player || s is TvScreen.MultiLive) stack.removeAll { it is TvScreen.Player || it is TvScreen.MultiLive }
        stack.add(s)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun TvApp() {
    val scope = rememberCoroutineScope()
    val nav = remember { TvNav() }
    val home = remember { HomeModel(scope) }
    val search = remember { SearchModel(scope) }
    val following = remember { FollowingModel(scope) }
    var tab by remember { mutableStateOf(0) }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Box(Modifier.fillMaxSize().background(TU.bg)) {
        when (val s = nav.stack.last()) {
            TvScreen.Tabs -> Column(Modifier.fillMaxSize()) {
                TvTabBar(tab) { tab = it }
                when (tab) {
                    0 -> TvHome(home, search, nav)
                    1 -> TvSearch(search, nav)
                    2 -> TvFollowing(following, nav)
                    else -> TvAccount()
                }
            }
            is TvScreen.Channel -> TvChannel(s.login, nav)
            is TvScreen.CategoryPage -> TvCategory(s.category, nav)
            is TvScreen.Player -> TvPlayer(s.request, onMulti = { nav.push(TvScreen.MultiLive(it)) }) { nav.pop() }
            is TvScreen.MultiLive -> TvMultiLive(s.first) { nav.pop() }
        }
    }
}

/** Bouton qui se met en avant au focus (télécommande). */
@Composable
fun TvButton(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Text(
        text,
        color = if (focused) Color.Black else if (selected) TU.text else TU.muted,
        fontSize = 18.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (focused) Color.White else if (selected) TU.primaryDim else Color.Transparent)
            .border(if (selected && !focused) 1.dp else 0.dp, if (selected && !focused) TU.primary else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun TvTabBar(tab: Int, onTab: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val labels = listOf("tab_home", "tv_search", "tv_following", "tv_account")
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("TwitchUnblock", color = TU.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(end = 24.dp))
        labels.forEachIndexed { i, key ->
            TvButton(AppState.t(key), selected = tab == i,
                     modifier = if (i == tab) Modifier.focusRequester(first) else Modifier) { onTab(i) }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, color = TU.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(start = 40.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun TvHome(home: HomeModel, search: SearchModel, nav: TvNav) {
    LaunchedEffect(AppState.user?.id) {
        home.load()
        search.loadTop()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (home.followed.isNotEmpty()) {
            item { RowTitle(AppState.t("home_followed")) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(home.followed, key = { it.login }) { s ->
                        StreamCard(s, Modifier.width(300.dp)) { nav.push(TvScreen.Player(PlayRequest.Live(s.login))) }
                    }
                }
            }
        }
        item { RowTitle(AppState.t("top_streams")) }
        item {
            if (home.loading && home.top.isEmpty()) LoadingBox()
            else if (home.failed) MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { home.load(force = true) }
            else LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(home.top, key = { it.login }) { s ->
                    StreamCard(s, Modifier.width(300.dp)) { nav.push(TvScreen.Player(PlayRequest.Live(s.login))) }
                }
                if (home.hasMore) item { Box(Modifier.width(160.dp)) { com.mxfia19.twitchunblock.ui.MoreButton(home.loadingMore) { home.more() } } }
            }
        }
        item { RowTitle(AppState.t("top_categories")) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(search.top, key = { it.id }) { c ->
                    CategoryCard(c, Modifier.width(150.dp)) { nav.push(TvScreen.CategoryPage(c)) }
                }
            }
        }
    }
}

@Composable
private fun TvSearch(model: SearchModel, nav: TvNav) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        OutlinedTextField(
            value = model.query, onValueChange = { model.update(it) }, singleLine = true,
            placeholder = { Text(AppState.t("ph_search"), color = TU.muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (model.searching && model.channels.isEmpty()) item { LoadingBox() }
            items(model.channels, key = { "u-" + it.login }) { s ->
                SuggestionRow(s) { nav.push(TvScreen.Channel(s.login)) }
            }
            if (model.categories.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(model.categories, key = { it.id }) { c ->
                            CategoryCard(c, Modifier.width(150.dp)) { nav.push(TvScreen.CategoryPage(c)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvFollowing(model: FollowingModel, nav: TvNav) {
    LaunchedEffect(AppState.user?.id) { model.load() }
    when {
        model.isEmptySetup -> MessageBox(AppState.t("tv_need_login"))
        model.loading && model.channels.isEmpty() -> LoadingBox()
        model.failed -> MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.load(force = true) }
        model.channels.isEmpty() -> MessageBox(AppState.t("following_none"))
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(model.channels, key = { it.login }) { b ->
                ChannelRow(b) {
                    if (b.stream != null) nav.push(TvScreen.Player(PlayRequest.Live(b.login))) else nav.push(TvScreen.Channel(b.login))
                }
            }
        }
    }
}

@Composable
private fun TvChannel(login: String, nav: TvNav) {
    val scope = rememberCoroutineScope()
    val model = remember(login) { ChannelModel(login, scope) }
    LaunchedEffect(login) {
        model.load()
        model.loadClips()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        item {
            Box(Modifier.padding(horizontal = 40.dp)) {
                ChannelHeader(login, model.info) { nav.push(TvScreen.Player(PlayRequest.Live(login))) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 40.dp, vertical = 8.dp)) {
                TvButton(AppState.t(if (AppState.isFollowedLocally(login)) "unfollow" else "follow")) {
                    AppState.toggleLocalFollow(login)
                }
            }
        }
        item { RowTitle(AppState.t("tv_videos")) }
        item {
            if (model.videos.isEmpty() && model.videosLoading) LoadingBox()
            else if (model.videos.isEmpty()) MessageBox(AppState.t("tv_no_videos"))
            else LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(model.videos, key = { it.id }) { v ->
                    VodCard(v, Modifier.width(300.dp)) { nav.push(TvScreen.Player(PlayRequest.Vod(v.id, v.title, login))) }
                }
                if (model.videosNext != null) item {
                    Box(Modifier.width(160.dp)) { com.mxfia19.twitchunblock.ui.MoreButton(model.videosLoading) { model.moreVideos() } }
                }
            }
        }
        item { RowTitle(AppState.t("clips")) }
        item {
            if (model.clips.isEmpty()) MessageBox(AppState.t(if (model.clipsLoading) "loading" else "no_clips"))
            else LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(model.clips, key = { it.slug }) { c ->
                    ClipCard(c, Modifier.width(300.dp)) { nav.push(TvScreen.Player(PlayRequest.Clip(c.slug))) }
                }
            }
        }
    }
}

@Composable
private fun TvCategory(category: Category, nav: TvNav) {
    val scope = rememberCoroutineScope()
    val model = remember(category.id) { CategoryModel(category, scope) }
    LaunchedEffect(category.id) { model.load() }
    Column(Modifier.fillMaxSize()) {
        RowTitle(category.name)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(model.streams, key = { it.login }) { s ->
                StreamCard(s) { nav.push(TvScreen.Player(PlayRequest.Live(s.login))) }
            }
            if (model.next != null) item(span = com.mxfia19.twitchunblock.ui.FullSpan) {
                com.mxfia19.twitchunblock.ui.MoreButton(model.loading) { model.more() }
            }
        }
    }
}

@Composable
private fun TvAccount() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp)) {
        item {
            val user = AppState.user
            if (user != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    com.mxfia19.twitchunblock.ui.Avatar(user.avatar, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(user.displayName, color = TU.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(24.dp))
                    TvButton(AppState.t("tv_logout")) { com.mxfia19.twitchunblock.core.Auth.logout() }
                }
            } else {
                com.mxfia19.twitchunblock.ui.LoginPanel(onDone = {}, showQr = true)
            }
        }
        item {
            com.mxfia19.twitchunblock.ui.Group(AppState.t("settings")) {
                com.mxfia19.twitchunblock.ui.Choice(AppState.t("language"),
                    com.mxfia19.twitchunblock.core.Lang.entries.map { it.code to it.label }, AppState.lang.code) { code ->
                    com.mxfia19.twitchunblock.core.Lang.of(code)?.let { AppState.updateLang(it) }
                }
                com.mxfia19.twitchunblock.ui.Choice(AppState.t("pref_quality"),
                    com.mxfia19.twitchunblock.core.Quality.PREFS.map { it to com.mxfia19.twitchunblock.core.Quality.prefLabel(it, AppState.lang) },
                    AppState.qualityPref) { AppState.updateQualityPref(it) }
                com.mxfia19.twitchunblock.ui.Choice(AppState.t("live_source"),
                    com.mxfia19.twitchunblock.core.Config.LIVE_SOURCE_CHOICES.map {
                        it to if (it == "auto") AppState.t("source_auto") else com.mxfia19.twitchunblock.ui.sourceName(it)
                    }, AppState.liveSource) { AppState.updateLiveSource(it) }
                com.mxfia19.twitchunblock.ui.Toggle(AppState.t("low_latency"), null, AppState.lowLatency) { AppState.updateLowLatency(it) }
                com.mxfia19.twitchunblock.ui.Toggle(AppState.t("tv_chat_show"), null, AppState.showChat) { AppState.updateShowChat(it) }
            }
        }
        item {
            Text("TwitchUnblock ${com.mxfia19.twitchunblock.BuildConfig.VERSION_NAME}", color = TU.muted, fontSize = 14.sp,
                 modifier = Modifier.padding(top = 16.dp))
        }
    }
}
