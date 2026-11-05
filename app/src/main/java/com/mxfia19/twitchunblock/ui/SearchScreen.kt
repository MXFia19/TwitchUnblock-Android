package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.FollowedCategories
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.Links
import com.mxfia19.twitchunblock.core.ListCursor
import com.mxfia19.twitchunblock.core.NavApi
import com.mxfia19.twitchunblock.core.SearchHistory
import com.mxfia19.twitchunblock.core.SearchVod
import com.mxfia19.twitchunblock.core.Suggestion
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Onglets des résultats, comme la page de résultats de Twitch (SearchResultsView, iOS). */
private val searchTabs = listOf("channels", "categories", "lives", "vods")

/**
 * Recherche en onglets : Chaînes (avec abonnés), Catégories (nom exact en
 * tête), Lives (titre, tags, catégorie ou nom) et VODs, plus les liens collés
 * et les catégories du moment. Partagé avec la TV (chaînes et catégories).
 */
class SearchModel(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    /** Résultats de la recherche en cours ; null : pas encore de réponse. */
    var foundChannels by mutableStateOf<List<Suggestion>?>(null)
        private set
    var foundCategories by mutableStateOf<List<Category>?>(null)
        private set
    var streams by mutableStateOf<List<TwitchStream>?>(null)
        private set
    var vods by mutableStateOf<List<SearchVod>?>(null)
        private set
    /** Onglets dont la recherche n'a pas abouti (Twitch n'a pas répondu). */
    var failed by mutableStateOf<Set<String>>(emptySet())
        private set
    var searching by mutableStateOf(false)
        private set
    /** Onglet des résultats affiché (searchTabs). */
    var tab by mutableStateOf("channels")

    /** Chaînes et catégories trouvées (TV). */
    val channels: List<Suggestion> get() = foundChannels.orEmpty()
    val categories: List<Category> get() = foundCategories.orEmpty()

    val top = mutableStateListOf<Category>()
    /** Catégories du top sans les masquées. */
    val visibleTop: List<Category> get() = top.filter { !Hidden.hidesCategory(it.id) }
    var topLoading by mutableStateOf(false)
        private set
    var topFailed by mutableStateOf(false)
        private set
    /** Suite du top ; `topLocked` : Twitch la refuse et aucun compte ne prend le relais. */
    var topCursor by mutableStateOf<ListCursor?>(null)
        private set
    var topLocked by mutableStateOf(false)
        private set
    val topMore: Boolean get() = topCursor != null
    private var job: Job? = null
    /** Numéro de la recherche : les réponses d'une recherche remplacée sont écartées. */
    private var gen = 0

    fun update(text: String) {
        query = text
        job?.cancel()
        val g = ++gen
        val q = text.trim()
        failed = emptySet()
        if (q.isEmpty() || Links.isVod(q) || Links.clipSlug(q) != null) {
            foundChannels = null
            foundCategories = null
            streams = null
            vods = null
            searching = false
            return
        }
        // Les résultats d'avant restent jusqu'aux nouveaux (pas de clignotement à la frappe).
        searching = true
        job = scope.launch {
            delay(400)
            coroutineScope {
                launch {
                    // Chaînes et lives ensemble. Si Twitch refuse la recherche des
                    // lives ou n'en trouve aucun, les chaînes trouvées en direct
                    // les remplacent.
                    val found = async { NavApi.searchStreams(q) }
                    val users = NavApi.searchChannels(q)
                    if (g != gen) return@launch
                    foundChannels = users ?: emptyList()
                    if (users == null) failed = failed + "channels"
                    var lives = found.await()
                    if (lives.isNullOrEmpty() && users != null) {
                        val logins = users.filter { it.isLive }.map { it.login }
                        if (logins.isNotEmpty()) BrowseApi.liveByLogins(logins)?.let { lives = it }
                        else if (lives == null) lives = emptyList()
                    }
                    if (g != gen) return@launch
                    streams = lives.orEmpty().distinctBy { it.login.lowercase() }
                    if (lives == null) failed = failed + "lives"
                }
                launch {
                    val cats = NavApi.searchCategories(q)
                    if (g != gen) return@launch
                    foundCategories = cats ?: emptyList()
                    if (cats == null) failed = failed + "categories"
                }
                launch {
                    val found = NavApi.searchVideos(q)
                    if (g != gen) return@launch
                    vods = found ?: emptyList()
                    if (found == null) failed = failed + "vods"
                }
            }
            if (g == gen) searching = false
        }
    }

    /** La même recherche, relancée (Réessayer, tirer pour actualiser). */
    fun retry() = update(query)

    fun loadTop(force: Boolean = false) {
        if ((top.isNotEmpty() && !force) || topLoading) return
        topLoading = true
        topFailed = false
        scope.launch {
            val p = BrowseApi.topCategories(null)
            if (p != null) {
                top.clear()
                top.addAll(p.items.distinctBy { it.id })
                topCursor = p.next?.let { ListCursor.Gql(it) }
                topLocked = false
            } else {
                topFailed = top.isEmpty()
            }
            topLoading = false
        }
    }

    /** Suite du top : GQL sans compte, Helix avec le compte si Twitch refuse. */
    fun moreTop() {
        val c = topCursor ?: return
        if (topLoading) return
        topLoading = true
        scope.launch {
            val r = NavApi.more(c, top, { it.id }, gql = { BrowseApi.topCategories(it) },
                                helix = { NavApi.helixTopCategories(it) })
            top.addAll(r.fresh)
            topCursor = r.cursor
            topLocked = r.locked
            topLoading = false
        }
    }

    /** Connecté depuis le refus : Helix reprend du début. */
    fun topWithAccount() {
        topCursor = ListCursor.Helix(null)
        topLocked = false
        moreTop()
    }
}

@Composable
fun SearchScreen(model: SearchModel, nav: Navigator) {
    LaunchedEffect(Unit) { model.loadTop() }
    val focus = LocalFocusManager.current
    val q = model.query.trim()
    val vodId = if (Links.isVod(q)) Links.vodId(q) else null
    // Lien avec ?t= : la VOD démarre à ce moment (clavier comme ligne touchée).
    val vodStartMs = (Links.vodStart(q) ?: -1).let { if (it > 0) it * 1000L else -1L }
    val clip = Links.clipSlug(q)
    val isText = q.isNotEmpty() && vodId == null && clip == null
    // Résultat ouvert : la recherche rejoint les récentes (comme une validation).
    val keep: () -> Unit = { if (isText) SearchHistory.add(q) }
    val list = BrowsePrefs.homeList.value
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.query,
            onValueChange = { model.update(it) },
            placeholder = { Text(AppState.t("ph_search"), color = TU.muted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TU.muted) },
            trailingIcon = {
                if (model.query.isNotEmpty()) {
                    IconButton(onClick = { model.update("") }) { Icon(Icons.Filled.Clear, contentDescription = null, tint = TU.muted) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                // Lien de chaîne ou « @nom » : sa page ; un texte : les résultats, gardés dans les récentes.
                val channelLink = q.startsWith("@") || q.lowercase().contains("twitch.tv/")
                when {
                    vodId != null -> nav.play(PlayRequest.Vod(vodId, startMs = vodStartMs))
                    clip != null -> nav.play(PlayRequest.Clip(clip))
                    channelLink && Links.isValidLogin(Links.login(q)) -> nav.push(Screen.Channel(Links.login(q)))
                    isText -> {
                        SearchHistory.add(q)
                        focus.clearFocus()
                    }
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line, cursorColor = TU.primary,
                focusedTextColor = TU.text, unfocusedTextColor = TU.text,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (isText) {
            SearchTabsRow(model)
            if (model.searching) LinearProgressIndicator(color = TU.primary, trackColor = TU.surface,
                                                         modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        // Onglet touché à nouveau : la liste remonte en haut.
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        TabTop.Listen(Screen.Search) { grid.animateScrollToItem(0) }
        val columns = when {
            !isText -> GridCells.Adaptive(110.dp)
            model.tab == "lives" -> BrowseGrid.lives(list)
            model.tab == "vods" -> BrowseGrid.cards
            else -> GridCells.Adaptive(110.dp)
        }
        RefreshBox(
            loading = if (isText) model.searching else model.topLoading,
            onRefresh = { if (isText) model.retry() else model.loadTop(force = true) },
        ) {
        LazyVerticalGrid(
            columns = columns,
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (vodId != null) {
                item(span = FullSpan) { ActionRow(AppState.t("watch_vod") + " " + vodId) { nav.play(PlayRequest.Vod(vodId, startMs = vodStartMs)) } }
            }
            if (clip != null) {
                item(span = FullSpan) { ActionRow(AppState.t("watch_clip")) { nav.play(PlayRequest.Clip(clip)) } }
            }
            if (q.isEmpty()) {
                recentSearches(model)
                recentChannels(nav)
                item(span = FullSpan) {
                    Text(AppState.t("top_categories"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(vertical = 6.dp))
                }
                items(model.visibleTop, key = { "c-" + it.id }) { c -> HideableCategoryCard(c) { nav.push(Screen.CategoryPage(c)) } }
                when {
                    model.topLoading && model.top.isEmpty() -> item(span = FullSpan) { LoadingBox() }
                    model.topFailed -> item(span = FullSpan) {
                        MessageBox(AppState.t("err_loading"), action = AppState.t("retry")) { model.loadTop(force = true) }
                    }
                    model.topMore -> item(span = FullSpan) { MoreButton(model.topLoading) { model.moreTop() } }
                    model.topLocked && AppState.token != null -> item(span = FullSpan) { MoreButton(model.topLoading) { model.topWithAccount() } }
                    model.topLocked -> item(span = FullSpan) { ListEndNote("more_needs_login") }
                }
            } else if (isText) {
                when (model.tab) {
                    "categories" -> {
                        val found = model.foundCategories
                        when {
                            found == null -> item(span = FullSpan) { LoadingBox() }
                            found.isEmpty() -> item(span = FullSpan) { EmptyOrFailed(model, "categories") }
                            else -> items(found, key = { "s-" + it.id }) { c ->
                                SearchCategoryCard(c) {
                                    keep()
                                    nav.push(Screen.CategoryPage(c))
                                }
                            }
                        }
                    }
                    "lives" -> {
                        val all = model.streams
                        when {
                            all == null -> item(span = FullSpan) { LoadingBox() }
                            all.isEmpty() -> item(span = FullSpan) { EmptyOrFailed(model, "lives") }
                            else -> {
                                // Lives des chaînes et catégories masquées écartés (sauf suivies).
                                val shown = all.filter { BrowseRules.shows(it) }
                                items(shown, key = { "l-" + it.login }) { s ->
                                    BrowseStreamItem(s, list, hideable = true) {
                                        keep()
                                        nav.play(PlayRequest.Live(s.login))
                                    }
                                }
                                if (all.size > shown.size) item(span = FullSpan) { HiddenFooter(all.size - shown.size, nav) }
                            }
                        }
                    }
                    "vods" -> {
                        val all = model.vods
                        when {
                            all == null -> item(span = FullSpan) { LoadingBox() }
                            all.isEmpty() -> item(span = FullSpan) { EmptyOrFailed(model, "vods") }
                            else -> {
                                val shown = all.filter { showsVod(it) }
                                items(shown, key = { "v-" + it.vod.id }) { v ->
                                    SearchVodCard(v) {
                                        keep()
                                        nav.play(PlayRequest.Vod(v.vod.id, v.vod.title, v.login.ifEmpty { null }))
                                    }
                                }
                                if (all.size > shown.size) item(span = FullSpan) { HiddenFooter(all.size - shown.size, nav) }
                            }
                        }
                    }
                    else -> {
                        val found = model.foundChannels
                        when {
                            found == null -> item(span = FullSpan) { LoadingBox() }
                            found.isEmpty() -> item(span = FullSpan) { EmptyOrFailed(model, "channels") }
                            else -> items(found, key = { "u-" + it.login }, span = { GridItemSpan(maxLineSpan) }) { s ->
                                // Masquée des listes : la recherche la montre, avec de quoi la réafficher.
                                SuggestionRow(s, onUnhide = if (Hidden.hidesChannel(s.login)) ({ Hidden.unhideChannel(s.login) }) else null) {
                                    keep()
                                    nav.push(Screen.Channel(s.login))
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Même règle que les lives : ni la chaîne ni la catégorie masquées, sauf si
 *  on suit l'une ou l'autre. */
private fun showsVod(v: SearchVod): Boolean =
    BrowseRules.follows(v.login) ||
        (!Hidden.hidesChannel(v.login) && (FollowedCategories.isFollowed(v.gameId) || !Hidden.hidesCategory(v.gameId)))

/** Onglets des résultats, avec leur nombre une fois arrivés (« Chaînes 12 »). */
@Composable
private fun SearchTabsRow(model: SearchModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (key in searchTabs) {
            val (title, count) = when (key) {
                "categories" -> AppState.t("categories") to model.foundCategories?.size
                "lives" -> AppState.t("search_tab_lives") to model.streams?.count { BrowseRules.shows(it) }
                "vods" -> AppState.t("vods") to model.vods?.count { showsVod(it) }
                else -> AppState.t("channels") to model.foundChannels?.size
            }
            TabChip(if (count != null && count > 0) "$title $count" else title, model.tab == key) { model.tab = key }
        }
    }
}

/** Onglet vide : Twitch n'a pas répondu (Réessayer), ou rien trouvé. */
@Composable
private fun EmptyOrFailed(model: SearchModel, tab: String) {
    if (tab in model.failed) MessageBox(AppState.t("err_loading"), action = AppState.t("retry")) { model.retry() }
    else MessageBox(AppState.t("no_result"))
}

/** Recherches récentes (10 au plus) : un toucher la relance, ✕ la retire. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.recentSearches(model: SearchModel) {
    val terms = SearchHistory.items.toList()
    if (terms.isEmpty()) return
    item(span = FullSpan) {
        SectionRow(AppState.t("search_recent")) { SearchHistory.clear() }
    }
    items(terms, key = { "rs-$it" }, span = { GridItemSpan(maxLineSpan) }) { term ->
        Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable {
                    SearchHistory.add(term)
                    model.update(term)
                }
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(BrowseIcons.clock, contentDescription = null, tint = TU.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(term, color = TU.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { SearchHistory.remove(term) }) {
                Icon(Icons.Filled.Clear, contentDescription = AppState.t("remove"), tint = TU.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Chaînes regardées récemment : ✕ en retire une, « Effacer » toutes. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.recentChannels(nav: Navigator) {
    val recent = History.channels.take(12)
    if (!com.mxfia19.twitchunblock.core.Features.showRecent.value || recent.isEmpty()) return
    item(span = FullSpan) {
        SectionRow(AppState.t("show_recent")) { History.clear("channel") }
    }
    item(span = FullSpan) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recent.size, key = { "rc-" + recent[it].key }) { i ->
                val h = recent[i]
                Box(Modifier.width(64.dp)) {
                    Column(Modifier.fillMaxWidth().focusCard({ nav.push(Screen.Channel(h.term)) }),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(h.avatar, 52.dp)
                        Text(h.display, color = TU.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(TU.surfaceHi)
                            .clickable { History.remove(h.term) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Clear, contentDescription = AppState.t("remove"), tint = TU.text, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

/** Titre d'une partie, avec « Effacer » à droite. */
@Composable
private fun SectionRow(title: String, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(AppState.t("btn_clear"), color = TU.danger, fontSize = 13.sp) }
    }
}

/** Catégorie trouvée : masquée, atténuée avec « Masqué · Réafficher ». */
@Composable
private fun SearchCategoryCard(c: Category, onClick: () -> Unit) {
    if (!Hidden.hidesCategory(c.id)) {
        HideableCategoryCard(c, onClick)
        return
    }
    Column {
        CategoryCard(c, Modifier.alpha(0.45f), onClick = onClick)
        HiddenMark(Modifier.padding(top = 4.dp)) { Hidden.unhideCategory(c.id) }
    }
}

/** VOD trouvée, avec sa chaîne. */
@Composable
private fun SearchVodCard(v: SearchVod, onClick: () -> Unit) {
    Column {
        VodCard(v.vod, onClick = onClick)
        if (v.name.isNotEmpty()) {
            Text(v.name, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusCard(onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TU.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Chaîne trouvée : en direct (jeu, spectateurs) ou ses abonnés. `onUnhide` :
 *  chaîne masquée des listes, « Masqué · Réafficher ». */
@Composable
fun SuggestionRow(s: Suggestion, modifier: Modifier = Modifier, onUnhide: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().focusCard(onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.avatar, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val followers = s.followers
            if (s.isLive) {
                Text(listOfNotNull(s.game, "● " + Format.viewers(s.viewers ?: 0)).joinToString(" · "),
                     color = TU.muted, fontSize = 13.sp, maxLines = 1)
            } else if (followers != null) {
                Text(Format.viewers(followers) + " " + AppState.tn("followers", followers, abbreviated = followers >= 1000),
                     color = TU.muted, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (onUnhide != null) {
            HiddenMark(Modifier.padding(start = 6.dp), onUnhide)
        }
        if (s.isLive) {
            Spacer(Modifier.width(6.dp))
            LiveBadge()
        }
    }
}
