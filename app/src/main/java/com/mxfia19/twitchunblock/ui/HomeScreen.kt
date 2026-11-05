package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.AuthExpiredException
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.ContinueWatching
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.ListCursor
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NavApi
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.TopLanguage
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.FollowedCategories
import com.mxfia19.twitchunblock.core.Pins
import com.mxfia19.twitchunblock.web.WebSession
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Accueil : directs suivis (compte ou appareil), puis le top. Partagé
 *  avec la TV. */
class HomeModel(private val scope: CoroutineScope) {
    var followed by mutableStateOf<List<TwitchStream>>(emptyList())
        private set
    val top = mutableStateListOf<TwitchStream>()
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    /** Top relu seul (Monde ↔ ma langue). */
    var loadingTop by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var next: ListCursor? = null
    var hasMore by mutableStateOf(false)
        private set
    /** Twitch refuse la suite du top et aucun compte ne prend le relais. */
    var locked by mutableStateOf(false)
        private set
    /** Top « Monde » (toutes les langues) au lieu de la langue choisie ; pas
     *  gardé d'un lancement à l'autre (comme l'iPhone). */
    var world by mutableStateOf(false)
        private set
    private var langPref: String? = Features.topLang
    /** Numéro de la première page du top : une page plus ancienne est écartée. */
    private var topGen = 0
    private var loadedAt = 0L
    private var loadedFor: String? = null
    /** Suivis du compte ou de l'appareil pas lus (Twitch n'a pas répondu) :
     *  l'ancienne liste reste, et le widget n'est pas réécrit. */
    var followedFailed by mutableStateOf(false)
        private set
    /** À qui appartient `followed` (compte, ou appareil). */
    private var followedFor: String? = null

    /** Le top sans les chaînes et catégories masquées (les suivis, eux, ne le
     *  sont jamais : même règle que le téléphone, BrowseRules). Rangée Top de la TV. */
    val visibleTop: List<TwitchStream> get() {
        val followedLogins = followed.map { it.login.lowercase() }.toHashSet()
        return top.filter { Hidden.allows(it, followedLogins) }
    }

    private fun topLang(): String? = if (world) null else TopLanguage.resolved()

    private fun who(): String = (AppState.user?.id ?: AppState.localFollows.joinToString(",")) + "|" + (topLang() ?: "*")

    private fun setNext(c: ListCursor?) {
        next = c
        hasMore = c != null
    }

    fun load(force: Boolean = false) {
        // Langue du top changée dans les réglages : retour à « ma langue » (comme l'iPhone).
        if (Features.topLang != langPref) {
            langPref = Features.topLang
            world = false
        }
        val who = who()
        if (!force && loadedFor == who && System.currentTimeMillis() - loadedAt < 120_000 && top.isNotEmpty()) return
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            val owner = AppState.user?.id ?: ""
            val fresh = loadFollowed()
            if (fresh != null) {
                followed = fresh
                followedFor = owner
            } else if (followedFor != owner) {
                // Liste d'un autre compte : pas gardée.
                followed = emptyList()
            }
            followedFailed = fresh == null
            fetchTop(who)
            loading = false
        }
    }

    /** Première page du top ; en cas d'échec, la liste affichée reste. */
    private suspend fun fetchTop(who: String) {
        val g = ++topGen
        val page = BrowseApi.topStreams(topLang(), null)
        if (g != topGen) return
        if (page != null) {
            top.clear()
            top.addAll(page.items.distinctBy { it.login })
            setNext(page.next?.let { ListCursor.Gql(it) })
            locked = false
            refills = 0
            loadedAt = System.currentTimeMillis()
            loadedFor = who
        } else if (top.isEmpty()) {
            failed = true
        }
    }

    /** Top de ma langue ou du monde entier (puces au-dessus du Top). */
    fun setWorld(on: Boolean) {
        if (on == world) return
        world = on
        top.clear()
        setNext(null)
        locked = false
        failed = false
        loadingTop = true
        val who = who()
        scope.launch {
            fetchTop(who)
            loadingTop = false
        }
    }

    /** null : une partie (compte ou appareil) n'a pas été lue. Une liste
     *  vide à la place ferait croire qu'aucune chaîne suivie n'est en direct. */
    private suspend fun loadFollowed(): List<TwitchStream>? {
        if (AppState.user == null) return withLocal(emptyList())
        val account = try {
            if (AppState.token != null) BrowseApi.followedStreams() else null
        } catch (e: AuthExpiredException) {
            // Compte retiré par Auth.renew seulement si Twitch a refusé le
            // renouvellement : un 401 de plus ne déconnecte pas.
            Logger.warn("HOME", "Session Twitch refusée")
            null
        }
        return account?.let { withLocal(it) }
    }

    /** Directs des chaînes suivies sur l'appareil, ajoutés à ceux du compte. */
    private suspend fun withLocal(account: List<TwitchStream>): List<TwitchStream>? {
        val known = account.map { it.login.lowercase() }.toHashSet()
        val local = AppState.localFollows.filter { it !in known }
        val extra = if (local.isEmpty()) emptyList() else BrowseApi.liveByLogins(local) ?: return null
        return (account + extra).distinctBy { it.login.lowercase() }.sortedByDescending { it.viewers }
    }

    /** Suite du top, sans doublons : GQL sans compte ; si Twitch refuse, Helix
     *  avec le compte (repart du début), sinon une note. */
    fun more() {
        val c = next ?: return
        if (loadingMore) return
        loadingMore = true
        val g = topGen
        val lang = topLang()
        scope.launch {
            val r = NavApi.more(c, top, { it.login.lowercase() }, gql = { BrowseApi.topStreams(lang, it) },
                                helix = { NavApi.helixStreams(null, lang, it) })
            // Top relu entre-temps (langue, actualisation) : il est reparti de la 1ʳᵉ page.
            if (g == topGen) {
                top.addAll(r.fresh)
                setNext(r.cursor)
                locked = r.locked
            }
            loadingMore = false
        }
    }

    /** Connecté depuis le refus : Helix reprend du début. */
    fun moreWithAccount() {
        if (!locked) return
        locked = false
        setNext(ListCursor.Helix(null))
        more()
    }

    /** Top complété quand des chaînes ou catégories masquées le raccourcissent :
     *  la page suivante tant qu'il reste moins de 24 lives à montrer (300 lus
     *  au plus). Comme refillTopIfNeeded (iOS). */
    fun refill() {
        if (loading || loadingTop || loadingMore || next == null || Hidden.isEmpty || top.isEmpty() || top.size >= 300) return
        // Une page qui n'apporte rien (panne) n'est pas redemandée sans fin.
        if (visibleTop.size >= 24 || refills >= 5) return
        refills++
        more()
    }
    private var refills = 0
}

@Composable
fun HomeScreen(model: HomeModel, nav: Navigator) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(AppState.user?.id, Features.topLang) { model.load() }
    // Continuer à regarder : VODs entamées, durées demandées pour les nouvelles.
    // Les positions ne sont pas un état observé : celles retirées ici sont
    // écartées aussitôt.
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val resume = ContinueWatching.items().filter { it.id !in removed }
    LaunchedEffect(History.vods.joinToString(",") { it.term }) { ContinueWatching.load() }
    // Catégories suivies : celles du compte (session web) et leur audience.
    LaunchedEffect(WebSession.token, FollowedCategories.local.size) { FollowedCategories.refresh() }
    // Chaîne ou catégorie masquée, page lue : le top se complète.
    LaunchedEffect(Hidden.channels.size, Hidden.categories.size, model.top.size, model.loading, model.loadingTop, model.loadingMore) {
        model.refill()
    }
    val play = { s: TwitchStream -> nav.play(PlayRequest.Live(s.login)) }
    val list = BrowsePrefs.homeList.value
    // Épinglées en tête des suivies ; le widget reprend la même liste.
    val followed = Pins.sortStreams(model.followed)
    // Seulement une liste lue (pas celle, vide, d'avant le chargement ou d'un échec).
    LaunchedEffect(model.followed, model.loading) {
        if (!model.loading && !model.failed && !model.followedFailed) com.mxfia19.twitchunblock.widget.LiveWidget.save(context, model.followed)
    }
    val followedLogins = model.followed.map { it.login.lowercase() }.toHashSet()
    val grid = rememberLazyGridState()
    TabTop.Listen(Screen.Home) { grid.animateScrollToItem(0) }
    // Parties dans l'ordre choisi (Personnaliser l'app), sans les masquées.
    val sections = BrowsePrefs.homeSections.filter { BrowsePrefs.showsHome(it) }
    // Boutons de l'accueil : sur le titre du Top, sinon sur celui des Suivies, sinon seuls.
    val actionsOn = when {
        "top" in sections -> "top"
        "followed" in sections && followed.isNotEmpty() -> "followed"
        else -> ""
    }
    RefreshBox(loading = model.loading, onRefresh = { model.load(force = true) }) {
    LazyVerticalGrid(
        columns = BrowseGrid.lives(list),
        state = grid,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (BrowsePrefs.showsHome("announcement")) item(span = { GridItemSpan(maxLineSpan) }) { NewsCards() }
        if (HomeCards.showsLogin) item(span = { GridItemSpan(maxLineSpan) }) { LoginCard(nav) }
        if (HomeCards.showsWeb) item(span = { GridItemSpan(maxLineSpan) }) { WebSessionCard(nav) }
        if (actionsOn.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) { HomeActions(model, nav) }
            }
        }
        for (part in sections) {
            when (part) {
                "continue" -> if (resume.isNotEmpty()) {
                    sectionTitle(AppState.t("continue_watching"))
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(resume, key = { "cw-" + it.id }) { e ->
                                // Retirer : la position de reprise est oubliée (la VOD reste dans Mes VODs).
                                ContinueItem(e, Modifier.width(220.dp), onRemove = {
                                    AppState.saveVodPosition(e.id, 0, 0)
                                    removed = removed + e.id
                                }) {
                                    nav.play(PlayRequest.Vod(e.id, e.title, e.channel))
                                }
                            }
                        }
                    }
                }
                "followed" -> if (followed.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppState.t("home_followed"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                 modifier = Modifier.weight(1f))
                            if (actionsOn == "followed") HomeActions(model, nav)
                        }
                    }
                    items(followed, key = { "f-" + it.login }) { s ->
                        BrowseStreamItem(s, list, pinnable = true) { play(s) }
                    }
                }
                "categories" -> if (FollowedCategories.sorted().isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { FollowedCategoriesRow { nav.push(Screen.CategoryPage(it)) } }
                }
                "top" -> topSection(model, nav, list, followedLogins, play)
            }
        }
    }
    }
}

private fun LazyGridScope.sectionTitle(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(title, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Top : puces « ma langue » / « Monde », lives sans les masqués (ce qu'on
 *  suit ou épingle n'est jamais masqué), « N masqués ici », suite. */
private fun LazyGridScope.topSection(model: HomeModel, nav: Navigator, list: Boolean, followedLogins: Set<String>,
                                     play: (TwitchStream) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppState.t("top_streams"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            HomeActions(model, nav)
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) { TopLangChips(model) }
    when {
        (model.loading || model.loadingTop) && model.top.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
        model.failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.load(force = true) }
        }
        else -> {
            val shown = model.top.filter { BrowseRules.shows(it, followedLogins) }
            items(shown, key = { "t-" + it.login }) { s ->
                BrowseStreamItem(s, list, hideable = true) { play(s) }
            }
            if (shown.isEmpty() && !model.hasMore) item(span = { GridItemSpan(maxLineSpan) }) { MessageBox(AppState.t("no_live")) }
            // Visible même si tout est masqué : sinon on croirait à une panne.
            if (model.top.size > shown.size) {
                item(span = { GridItemSpan(maxLineSpan) }) { HiddenFooter(model.top.size - shown.size, nav) }
            }
            when {
                model.hasMore -> item(span = { GridItemSpan(maxLineSpan) }) { MoreButton(model.loadingMore) { model.more() } }
                model.locked && AppState.token != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    MoreButton(model.loadingMore) { model.moreWithAccount() }
                }
                model.locked -> item(span = { GridItemSpan(maxLineSpan) }) { ListEndNote("more_needs_login") }
            }
        }
    }
}

/** Boutons de l'accueil : grille ou liste, multi-live, actualiser. */
@Composable
private fun HomeActions(model: HomeModel, nav: Navigator) {
    LayoutToggle()
    androidx.compose.material3.TextButton(onClick = { nav.multi(null) }) {
        Text(AppState.t("multi_title"), color = TU.primary, fontSize = 14.sp)
    }
    IconButton(onClick = { model.load(force = true) }) {
        Icon(Icons.Filled.Refresh, contentDescription = AppState.t("drops_refresh"), tint = TU.muted)
    }
}
