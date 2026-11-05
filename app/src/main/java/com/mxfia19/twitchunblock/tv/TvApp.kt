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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.ContinueWatching
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.Vod
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.ui.CategoryCard
import com.mxfia19.twitchunblock.ui.CategoryModel
import com.mxfia19.twitchunblock.ui.ChannelHeader
import com.mxfia19.twitchunblock.ui.ChannelModel
import com.mxfia19.twitchunblock.ui.ChannelRow
import com.mxfia19.twitchunblock.ui.ClipCard
import com.mxfia19.twitchunblock.ui.ContinueCard
import com.mxfia19.twitchunblock.ui.FollowingModel
import com.mxfia19.twitchunblock.ui.HomeModel
import com.mxfia19.twitchunblock.ui.LoadingBox
import com.mxfia19.twitchunblock.ui.MessageBox
import com.mxfia19.twitchunblock.ui.SearchModel
import com.mxfia19.twitchunblock.ui.StreamCard
import com.mxfia19.twitchunblock.ui.SuggestionRow
import com.mxfia19.twitchunblock.ui.TU
import com.mxfia19.twitchunblock.ui.VodCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull

sealed class TvScreen {
    object Tabs : TvScreen()
    /** `autoplay` : ouverte avec la touche Lecture (le direct, sinon la dernière vidéo). */
    data class Channel(val login: String, val autoplay: Boolean = false) : TvScreen()
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

    /** « Voir la chaîne » dans le lecteur : il se ferme et la page de la
     *  chaîne s'ouvre, sauf si on en vient (elle est juste dessous) ; Retour
     *  ramène ensuite aux onglets. Comme l'Apple TV. */
    fun channelFromPlayer(login: String) {
        if (stack.last() is TvScreen.Player) stack.removeAt(stack.lastIndex)
        val top = stack.last()
        if (top is TvScreen.Channel && top.login.equals(login, ignoreCase = true)) return
        stack.add(TvScreen.Channel(login))
    }

    /** Page ouverte avec Lecture : sa lecture est partie, elle ne repart pas
     *  au retour du lecteur. */
    fun autoplayed(login: String) {
        val i = stack.indexOfLast { it is TvScreen.Channel && it.login == login && it.autoplay }
        if (i >= 0) stack[i] = TvScreen.Channel(login)
    }
}

/** Onglets : la carte ouverte en dernier (direct, chaîne, catégorie)
 *  reprend le focus au retour, au lieu de la barre d'onglets. */
class TvReturn {
    var key by mutableStateOf<String?>(null)
    val focus = FocusRequester()
}

/** Carte des onglets retenue par `back` (sa clé posée à l'ouverture) : elle
 *  reprend le focus quand l'onglet revient. */
@Composable
private fun Modifier.reopen(back: TvReturn, key: String): Modifier {
    if (back.key != key) return this
    LaunchedEffect(Unit) {
        withFrameNanos { }   // carte placée
        if (runCatching { back.focus.requestFocus() }.isSuccess) back.key = null
    }
    return focusRequester(back.focus)
}

/** `nav` : portée par TvActivity (liens des notifications). */
@Composable
fun TvApp(nav: TvNav) {
    val scope = rememberCoroutineScope()
    val home = remember { HomeModel(scope) }
    val search = remember { SearchModel(scope) }
    val following = remember { FollowingModel(scope) }
    var tab by remember { mutableStateOf(0) }
    // Onglets : leurs listes (défilement) gardées pendant qu'un direct, une
    // chaîne ou une catégorie est ouvert par-dessus.
    val tabsState = rememberSaveableStateHolder()
    val back = remember { TvReturn() }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    com.mxfia19.twitchunblock.ui.UpdatePrompt()
    Box(Modifier.fillMaxSize().background(TU.bg)) {
        when (val s = nav.stack.last()) {
            TvScreen.Tabs -> Column(Modifier.fillMaxSize()) {
                TvTabBar(tab, back) {
                    // Suivies choisie de nouveau : la liste est relue (comme l'Apple TV).
                    if (it == tab && it == 2) following.load(force = true)
                    tab = it
                }
                tabsState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> TvHome(home, search, nav, back)
                        1 -> TvSearch(search, nav, back)
                        2 -> TvFollowing(following, nav, back)
                        else -> TvAccount()
                    }
                }
            }
            is TvScreen.Channel -> TvChannel(s.login, s.autoplay, nav)
            is TvScreen.CategoryPage -> TvCategory(s.category, nav)
            is TvScreen.Player -> TvPlayer(s.request, onMulti = { nav.push(TvScreen.MultiLive(it)) },
                                           onChannel = { nav.channelFromPlayer(it) }) { nav.pop() }
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
private fun TvTabBar(tab: Int, back: TvReturn, onTab: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Retour d'une carte : elle reprend le focus ; la barre seulement si
        // elle n'a pas pu (liste rechargée sans elle).
        if (back.key != null) {
            delay(800)
            if (back.key == null) return@LaunchedEffect
            back.key = null
        }
        runCatching { first.requestFocus() }
    }
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
private fun TvHome(home: HomeModel, search: SearchModel, nav: TvNav, back: TvReturn) {
    LaunchedEffect(AppState.user?.id) {
        home.load()
        search.loadTop()
    }
    // Reprendre : VODs entamées. Leur rangée au-dessus des directs, dessous ou
    // masquée (Compte), comme l'Apple TV ; retirées d'ici : cachées tout de suite.
    val place = TvHomePrefs.resumePlace
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val resume = if (place == "hidden") emptyList() else ContinueWatching.items().filter { it.id !in removed }
    LaunchedEffect(History.vods.joinToString(",") { it.term }) { ContinueWatching.load() }
    // Appui long : masquer une chaîne ou une catégorie, reprendre ou retirer une VOD.
    val menus = remember { TvMenus() }
    TvMenuHost(menus)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (place == "top") resumeRow(resume, nav, back, menus) { removed = removed + it }
        item { Box(Modifier.padding(horizontal = 40.dp)) { Column { com.mxfia19.twitchunblock.ui.NewsCards() } } }
        if (home.followed.isNotEmpty()) {
            item { RowTitle(AppState.t("home_followed")) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(home.followed, key = { it.login }) { s ->
                        val open = {
                            back.key = "f-" + s.login
                            nav.push(TvScreen.Player(PlayRequest.Live(s.login)))
                        }
                        StreamCard(s, Modifier.width(300.dp).reopen(back, "f-" + s.login).onPlayKey(open), onClick = open)
                    }
                }
            }
        }
        item { RowTitle(AppState.t("top_streams")) }
        item {
            if (home.loading && home.top.isEmpty()) LoadingBox()
            else if (home.failed) MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { home.load(force = true) }
            else LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(home.visibleTop, key = { it.login }) { s ->
                    val open = {
                        back.key = "t-" + s.login
                        nav.push(TvScreen.Player(PlayRequest.Live(s.login)))
                    }
                    StreamCard(s, Modifier.width(300.dp).reopen(back, "t-" + s.login).onPlayKey(open),
                               onLongClick = { menus.hideStream(s) }, onClick = open)
                }
                if (home.hasMore) item { Box(Modifier.width(160.dp)) { com.mxfia19.twitchunblock.ui.MoreButton(home.loadingMore) { home.more() } } }
            }
        }
        if (place == "bottom") resumeRow(resume, nav, back, menus) { removed = removed + it }
        item { RowTitle(AppState.t("top_categories")) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(search.visibleTop, key = { it.id }) { c ->
                    CategoryCard(c, Modifier.width(150.dp).reopen(back, "c-" + c.id), onLongClick = { menus.hideCategory(c) }) {
                        back.key = "c-" + c.id
                        nav.push(TvScreen.CategoryPage(c))
                    }
                }
            }
        }
    }
}

/** Rangée « Reprendre » : le bouton central ou Lecture reprennent la VOD ;
 *  appui long : reprendre, ou la retirer de la liste (sa position est
 *  oubliée), comme l'Apple TV. */
private fun LazyListScope.resumeRow(resume: List<ContinueWatching.Entry>, nav: TvNav, back: TvReturn, menus: TvMenus,
                                    onRemove: (String) -> Unit) {
    if (resume.isEmpty()) return
    item { RowTitle(AppState.t("tv_resume")) }
    item {
        LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(resume, key = { it.id }) { e ->
                val play = {
                    back.key = "cw-" + e.id
                    nav.push(TvScreen.Player(PlayRequest.Vod(e.id, e.title, e.channel)))
                }
                ContinueCard(e, Modifier.width(300.dp).reopen(back, "cw-" + e.id).onPlayKey(play), onLongClick = {
                    menus.open(e.title, listOf(
                        TvAction(AppState.t("tv_resume_play")) { play() },
                        TvAction(AppState.t("tv_resume_remove"), removesCard = true) {
                            AppState.saveVodPosition(e.id, 0, 0)
                            onRemove(e.id)
                        },
                    ))
                }, onClick = play)
            }
        }
    }
}

@Composable
private fun TvSearch(model: SearchModel, nav: TvNav, back: TvReturn) {
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
                // Lecture : le direct, sinon la page de la chaîne et sa dernière vidéo.
                SuggestionRow(s, Modifier.reopen(back, "u-" + s.login).onPlayKey {
                    back.key = "u-" + s.login
                    nav.push(if (s.viewers != null) TvScreen.Player(PlayRequest.Live(s.login)) else TvScreen.Channel(s.login, autoplay = true))
                }) {
                    back.key = "u-" + s.login
                    nav.push(TvScreen.Channel(s.login))
                }
            }
            if (model.categories.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(model.categories, key = { it.id }) { c ->
                            CategoryCard(c, Modifier.width(150.dp).reopen(back, "sc-" + c.id)) {
                                back.key = "sc-" + c.id
                                nav.push(TvScreen.CategoryPage(c))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvFollowing(model: FollowingModel, nav: TvNav, back: TvReturn) {
    // Relue toute seule, comme l'Apple TV : en revenant sur l'onglet (lue il y
    // a plus de 2 min), au retour dans l'app, et toutes les 2 minutes tant
    // qu'elle est affichée ; les directs finis n'y restent pas.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(AppState.user?.id, AppState.localFollows.size, owner) {
        var again = false
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.load(force = again)
            again = true
            while (true) {
                delay(120_000)
                model.load(force = true)
            }
        }
    }
    // Descendue de la barre des onglets : relue si elle date de plus de 2 min.
    var focused by remember { mutableStateOf(false) }
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
            modifier = Modifier.onFocusChanged { f ->
                if (f.hasFocus && !focused) model.load()
                focused = f.hasFocus
            },
        ) {
            items(model.channels, key = { it.login }) { b ->
                ChannelRow(b, Modifier.reopen(back, "fo-" + b.login).onPlayKey {
                    // Lecture : le direct, sinon la page de la chaîne et sa dernière vidéo.
                    back.key = "fo-" + b.login
                    nav.push(if (b.stream != null) TvScreen.Player(PlayRequest.Live(b.login)) else TvScreen.Channel(b.login, autoplay = true))
                }) {
                    back.key = "fo-" + b.login
                    if (b.stream != null) nav.push(TvScreen.Player(PlayRequest.Live(b.login))) else nav.push(TvScreen.Channel(b.login))
                }
            }
        }
    }
}

/** Périodes des clips, mêmes textes que l'Apple TV. */
private val clipPeriods = listOf("LAST_DAY" to "period_day", "LAST_WEEK" to "period_week",
                                 "LAST_MONTH" to "period_month", "ALL_TIME" to "period_all")

@Composable
private fun TvChannel(login: String, autoplay: Boolean, nav: TvNav) {
    val scope = rememberCoroutineScope()
    val model = remember(login) { ChannelModel(login, scope) }
    val clips = remember(login) { TvClips(login, scope) }
    var collections by remember(login) { mutableStateOf<List<TvApi.Playlist>>(emptyList()) }
    LaunchedEffect(login) {
        model.load()
        clips.load()
    }
    // Collections avec la page (une requête) : leurs rangées n'apparaissent
    // qu'avec elles, la plupart des chaînes n'en ont aucune.
    LaunchedEffect(login) { collections = TvApi.collections(login).orEmpty() }
    // Ouverte avec Lecture : le direct s'il a commencé, sinon la dernière
    // vidéo, une fois chargés ; une seule fois (pas au retour du lecteur).
    LaunchedEffect(login, autoplay) {
        if (!autoplay) return@LaunchedEffect
        val info = snapshotFlow { model.info }.filterNotNull().first()
        val request = if (info.error == null) PlayRequest.Live(login) else {
            snapshotFlow { model.videosLoading }.first { !it }
            model.videos.firstOrNull()?.let { PlayRequest.Vod(it.id, it.title, login) }
        }
        nav.autoplayed(login)
        if (request != null) nav.push(TvScreen.Player(request))
    }
    val live = model.info?.error == null && model.info != null
    val watchLive = { nav.push(TvScreen.Player(PlayRequest.Live(login))) }
    val playVod = { v: Vod -> nav.push(TvScreen.Player(PlayRequest.Vod(v.id, v.title, login))) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        item {
            Box(Modifier.padding(horizontal = 40.dp).onPlayKey { if (live) watchLive() }) {
                ChannelHeader(login, model.info) { watchLive() }
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
                    VodCard(v, Modifier.width(300.dp).onPlayKey { playVod(v) }) { playVod(v) }
                }
                if (model.videosNext != null) item {
                    Box(Modifier.width(160.dp)) { com.mxfia19.twitchunblock.ui.MoreButton(model.videosLoading) { model.moreVideos() } }
                }
            }
        }
        // Clips : les plus vus sur 24 h, 7 jours, 30 jours ou depuis toujours.
        item { RowTitle(AppState.t("tv_clips")) }
        item {
            Row(Modifier.padding(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((period, key) in clipPeriods) {
                    TvButton(AppState.t(key), selected = clips.period == period) { if (clips.period != period) clips.load(period) }
                }
            }
        }
        item {
            val list = clips.clips
            if (list == null) {
                if (clips.failed) MessageBox(AppState.t("tv_error"), action = AppState.t("retry")) { clips.load() }
                else LoadingBox()
            } else if (list.isEmpty()) {
                MessageBox(AppState.t("tv_no_clips"))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(list, key = { it.slug }) { c ->
                        val play = { nav.push(TvScreen.Player(PlayRequest.Clip(c.slug))) }
                        ClipCard(c, Modifier.width(300.dp).onPlayKey(play), onClick = play)
                    }
                }
            }
        }
        // Collections : une rangée chacune, avec son titre et son nombre de vidéos.
        collections.forEach { p ->
            item(key = "col-" + p.id) {
                Column {
                    RowTitle(p.title.ifEmpty { AppState.t("tv_collections") })
                    Text(AppState.t("videos_count").replace("%d", "${p.total}"), color = TU.muted, fontSize = 14.sp,
                         modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(p.videos, key = { it.id }) { v ->
                            VodCard(v, Modifier.width(300.dp).onPlayKey { playVod(v) }) { playVod(v) }
                        }
                    }
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
    // Appui long : masquer la chaîne ; masquées écartées (ce qu'on suit reste).
    val menus = remember { TvMenus() }
    TvMenuHost(menus)
    Column(Modifier.fillMaxSize()) {
        RowTitle(category.name)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(model.streams.filter { BrowseRules.follows(it.login) || !Hidden.hidesChannel(it.login) }, key = { it.login }) { s ->
                val open = { nav.push(TvScreen.Player(PlayRequest.Live(s.login))) }
                StreamCard(s, Modifier.onPlayKey(open), onLongClick = { menus.hideStream(s, withCategory = false) }, onClick = open)
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
            }
        }
        // Chat du lecteur (par défaut), place de « Reprendre », masquées.
        item { TvMoreSettings() }
        item {
            Text("TwitchUnblock ${com.mxfia19.twitchunblock.BuildConfig.VERSION_NAME}", color = TU.muted, fontSize = 14.sp,
                 modifier = Modifier.padding(top = 16.dp))
        }
    }
}
