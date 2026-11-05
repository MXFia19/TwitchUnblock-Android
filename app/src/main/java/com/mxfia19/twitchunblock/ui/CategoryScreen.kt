package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.mxfia19.twitchunblock.core.ListCursor
import com.mxfia19.twitchunblock.core.NavApi
import com.mxfia19.twitchunblock.core.TopLanguage
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Directs d'une catégorie, par pages de 100, dans la langue choisie
 *  (BrowsePrefs.categoryLang, la même pour toutes). Partagé avec la TV. */
class CategoryModel(val category: Category, private val scope: CoroutineScope) {
    val streams = mutableStateListOf<TwitchStream>()
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** Suite : GQL sans compte, Helix avec le compte si Twitch refuse. */
    var next by mutableStateOf<ListCursor?>(null)
        private set
    /** Twitch refuse la suite et aucun compte ne prend le relais. */
    var locked by mutableStateOf(false)
        private set
    /** Langue des directs affichés (« » : toutes). */
    private var loadedLang: String? = null
    /** Numéro du chargement : une réponse d'avant (autre langue) est écartée. */
    private var gen = 0
    private var started = false

    fun load() {
        if (started && loadedLang == BrowsePrefs.categoryLang.value) return
        reload()
    }

    /** Première page relue (langue changée, tirer pour actualiser) ; la liste
     *  reste jusqu'à la réponse, sauf pour une autre langue. */
    fun reload() {
        started = true
        val lang = BrowsePrefs.categoryLang.value
        val g = ++gen
        if (lang != loadedLang) {
            streams.clear()
            next = null
            locked = false
        }
        loading = true
        failed = false
        scope.launch {
            val page = BrowseApi.streamsByCategory(category.id, lang.ifEmpty { null }, null)
            if (g != gen) return@launch
            if (page == null) {
                failed = streams.isEmpty()
            } else {
                streams.clear()
                streams.addAll(page.items.distinctBy { it.login })
                next = page.next?.let { ListCursor.Gql(it) }
                locked = false
                loadedLang = lang
            }
            loading = false
        }
    }

    fun more() {
        val c = next ?: return
        if (loading) return
        loading = true
        val g = gen
        val lang = (loadedLang ?: "").ifEmpty { null }
        scope.launch {
            val r = NavApi.more(c, streams, { it.login.lowercase() },
                                gql = { BrowseApi.streamsByCategory(category.id, lang, it) },
                                helix = { NavApi.helixStreams(category.id, lang, it) })
            if (g == gen) {
                streams.addAll(r.fresh)
                next = r.cursor
                locked = r.locked
                loading = false
            }
        }
    }

    /** Connecté depuis le refus : Helix reprend du début. */
    fun moreWithAccount() {
        if (!locked) return
        locked = false
        next = ListCursor.Helix(null)
        more()
    }
}

/** Tris des lives d'une catégorie : Twitch les donne par spectateurs
 *  décroissants, les autres sont appliqués ici. Comme StreamSort (iOS). */
private val sorts = listOf("viewers_desc" to "sort_viewers_desc", "viewers_asc" to "sort_viewers_asc", "name" to "sort_name")

private fun sorted(list: List<TwitchStream>, sort: String): List<TwitchStream> = when (sort) {
    "viewers_asc" -> list.sortedBy { it.viewers }
    "name" -> list.sortedBy { it.name.lowercase() }
    else -> list.sortedByDescending { it.viewers }
}

@Composable
fun CategoryScreen(category: Category, nav: Navigator) {
    // Gardé tant que la page reste dans la pile (PhoneApp) : au retour d'un
    // live, les directs déjà chargés et la position restent.
    val model = rememberPageModel("category:" + category.id) { CategoryModel(category, it) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Langue choisie (ici ou dans une autre catégorie) : la liste suit.
    LaunchedEffect(category.id, BrowsePrefs.categoryLang.value) { model.load() }
    var sort by rememberSaveable { mutableStateOf("viewers_desc") }
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
        // Langue et tri.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip()
            for ((key, label) in sorts) SmallChip(AppState.t(label), sort == key) { sort = key }
        }
        RefreshBox(loading = model.loading, onRefresh = { model.reload() }) {
        LazyVerticalGrid(
            columns = BrowseGrid.lives(list),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Chaînes masquées écartées (sauf celles qu'on suit) ; la catégorie,
            // ouverte exprès, ne l'est pas. Son nom ne se répète pas sous chaque direct.
            val all = model.streams.toList()
            val shown = sorted(all.filter { BrowseRules.follows(it.login) || !Hidden.hidesChannel(it.login) }, sort)
            items(shown, key = { it.login }) { s ->
                BrowseStreamItem(s, list, hideable = true, hideCategory = false, showGame = false) {
                    nav.play(PlayRequest.Live(s.login))
                }
            }
            if (all.size > shown.size) item(span = FullSpan) { HiddenFooter(all.size - shown.size, nav) }
            when {
                model.loading && all.isEmpty() -> item(span = FullSpan) { LoadingBox() }
                model.failed -> item(span = FullSpan) {
                    MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.reload() }
                }
                all.isEmpty() && !model.loading -> item(span = FullSpan) { MessageBox(AppState.t("tv_no_live")) }
                model.next != null -> item(span = FullSpan) { MoreButton(model.loading) { model.more() } }
                model.locked && AppState.token != null -> item(span = FullSpan) { MoreButton(model.loading) { model.moreWithAccount() } }
                model.locked -> item(span = FullSpan) { ListEndNote("more_needs_login") }
            }
        }
        }
    }
}

/** Langue des lives : toutes, celle de l'appareil, ou une autre ; gardée
 *  pour toutes les catégories. */
@Composable
private fun LanguageChip() {
    var open by remember { mutableStateOf(false) }
    val current = BrowsePrefs.categoryLang.value
    val device = TopLanguage.device
    Box {
        SmallChip("🌐 " + (if (current.isEmpty()) AppState.t("cat_lang_all") else TopLanguage.name(current)) + " ▾",
                  current.isNotEmpty()) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val pick = { code: String ->
                open = false
                BrowsePrefs.categoryLang.set(code)
            }
            DropdownMenuItem(text = { Text((if (current.isEmpty()) "✓ " else "") + AppState.t("cat_lang_all")) }, onClick = { pick("") })
            DropdownMenuItem(text = { Text((if (current == device) "✓ " else "") + TopLanguage.name(device)) }, onClick = { pick(device) })
            HorizontalDivider()
            for ((code, name) in TopLanguage.codes.filter { it != device }.map { it to TopLanguage.name(it) }.sortedBy { it.second.lowercase() }) {
                DropdownMenuItem(text = { Text((if (current == code) "✓ " else "") + name) }, onClick = { pick(code) })
            }
        }
    }
}
