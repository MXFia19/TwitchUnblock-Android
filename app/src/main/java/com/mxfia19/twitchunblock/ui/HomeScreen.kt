package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.TopLanguage
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
    var failed by mutableStateOf(false)
        private set
    private var next: String? = null
    var hasMore by mutableStateOf(false)
        private set
    private var loadedAt = 0L
    private var loadedFor: String? = null

    /** Le top sans les chaînes et catégories masquées (les suivis, eux, ne le sont jamais). */
    val visibleTop: List<TwitchStream> get() = top.filter { Hidden.allows(it) }

    fun load(force: Boolean = false) {
        val who = (AppState.user?.id ?: AppState.localFollows.joinToString(",")) + "|" + TopLanguage.resolved()
        if (!force && loadedFor == who && System.currentTimeMillis() - loadedAt < 120_000 && top.isNotEmpty()) return
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            followed = loadFollowed()
            val page = BrowseApi.topStreams(TopLanguage.resolved(), null)
            if (page != null) {
                top.clear()
                top.addAll(page.items.distinctBy { it.login })
                next = page.next
                hasMore = page.next != null
                loadedAt = System.currentTimeMillis()
                loadedFor = who
            } else if (top.isEmpty()) {
                failed = true
            }
            loading = false
        }
    }

    private suspend fun loadFollowed(): List<TwitchStream> {
        if (AppState.token != null && AppState.user != null) {
            try {
                BrowseApi.followedStreams()?.let { return withLocal(it) }
            } catch (e: AuthExpiredException) {
                // Compte retiré par Auth.renew seulement si Twitch a refusé le
                // renouvellement : un 401 de plus ne déconnecte pas.
                Logger.warn("HOME", "Session Twitch refusée")
            }
        }
        return withLocal(emptyList())
    }

    /** Directs des chaînes suivies sur l'appareil, ajoutés à ceux du compte. */
    private suspend fun withLocal(account: List<TwitchStream>): List<TwitchStream> {
        val known = account.map { it.login.lowercase() }.toHashSet()
        val local = AppState.localFollows.filter { it !in known }
        val extra = if (local.isEmpty()) emptyList() else BrowseApi.liveByLogins(local) ?: emptyList()
        return (account + extra).distinctBy { it.login.lowercase() }.sortedByDescending { it.viewers }
    }

    fun more() {
        val cursor = next ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            val page = BrowseApi.topStreams(TopLanguage.resolved(), cursor)
            if (page != null) {
                val seen = top.map { it.login }.toHashSet()
                top.addAll(page.items.filter { it.login !in seen }.distinctBy { it.login })
                next = page.next
                hasMore = page.next != null && !page.refused
            }
            loadingMore = false
        }
    }
}

@Composable
fun HomeScreen(model: HomeModel, nav: Navigator) {
    LaunchedEffect(AppState.user?.id, com.mxfia19.twitchunblock.core.Features.topLang) { model.load() }
    // Continuer à regarder : VODs entamées, durées demandées pour les nouvelles.
    val resume = ContinueWatching.items()
    LaunchedEffect(History.vods.joinToString(",") { it.term }) { ContinueWatching.load() }
    val play = { s: TwitchStream -> nav.play(PlayRequest.Live(s.login)) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { NewsCards() }
        if (resume.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(AppState.t("continue_watching"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.padding(top = 8.dp))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(resume, key = { "cw-" + it.id }) { e ->
                        ContinueCard(e, Modifier.width(220.dp)) { nav.play(PlayRequest.Vod(e.id, e.title, e.channel)) }
                    }
                }
            }
        }
        if (model.followed.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(AppState.t("home_followed"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.padding(top = 8.dp))
            }
            items(model.followed, key = { "f-" + it.login }) { s -> StreamCard(s) { play(s) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AppState.t("top_streams"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { nav.multi(null) }) {
                    Text(AppState.t("multi_title"), color = TU.primary, fontSize = 14.sp)
                }
                IconButton(onClick = { model.load(force = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = AppState.t("drops_refresh"), tint = TU.muted)
                }
            }
        }
        when {
            model.loading && model.top.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
            model.failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.load(force = true) }
            }
            else -> {
                items(model.visibleTop, key = { "t-" + it.login }) { s -> HideableStreamCard(s) { play(s) } }
                if (model.hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) { MoreButton(model.loadingMore) { model.more() } }
                }
            }
        }
    }
}
