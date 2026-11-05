package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.FollowedCategories
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Directs d'une catégorie, par pages de 100. Partagé avec la TV. */
class CategoryModel(val category: Category, private val scope: CoroutineScope) {
    val streams = mutableStateListOf<TwitchStream>()
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var next by mutableStateOf<String?>(null)
        private set
    private var started = false

    fun load() {
        if (started) return
        started = true
        more()
    }

    fun more() {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            val page = BrowseApi.streamsByCategory(category.id, null, next)
            if (page == null) {
                failed = streams.isEmpty()
            } else {
                val seen = streams.map { it.login }.toHashSet()
                streams.addAll(page.items.filter { it.login !in seen }.distinctBy { it.login })
                next = if (page.refused) null else page.next
            }
            loading = false
        }
    }
}

@Composable
fun CategoryScreen(category: Category, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val model = remember(category.id) { CategoryModel(category, scope) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(category.id) { model.load() }
    val list = BrowsePrefs.homeList.value
    Column(Modifier.fillMaxSize()) {
        PageHeader(category.name, nav) {
            LayoutToggle()
            // Suivre la catégorie sur l'appareil (rangée de l'accueil). Suivie
            // sur le compte Twitch : elle s'y retire.
            val local = FollowedCategories.isFollowedLocally(category.id)
            val onTwitch = FollowedCategories.isFollowedOnTwitch(category.id)
            IconButton(onClick = {
                if (onTwitch && !local) {
                    android.widget.Toast.makeText(context, AppState.t("br_cat_twitch"), android.widget.Toast.LENGTH_LONG).show()
                } else {
                    FollowedCategories.toggle(category)
                }
            }) {
                Icon(if (local || onTwitch) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                     contentDescription = AppState.t(if (local || onTwitch) "unfollow" else "follow"),
                     tint = if (local || onTwitch) TU.primary else TU.text)
            }
        }
        LazyVerticalGrid(
            columns = BrowseGrid.lives(list),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Chaînes masquées écartées (sauf celles qu'on suit) ; la catégorie,
            // ouverte exprès, ne l'est pas. Son nom ne se répète pas sous chaque direct.
            items(model.streams.filter { BrowseRules.follows(it.login) || !Hidden.hidesChannel(it.login) },
                  key = { it.login }) { s ->
                BrowseStreamItem(s, list, hideable = true, hideCategory = false, showGame = false) {
                    nav.play(PlayRequest.Live(s.login))
                }
            }
            when {
                model.loading -> item(span = FullSpan) { LoadingBox() }
                model.failed -> item(span = FullSpan) {
                    MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.more() }
                }
                model.streams.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("tv_no_live")) }
                model.next != null -> item(span = FullSpan) { MoreButton(false) { model.more() } }
            }
        }
    }
}
