package com.mxfia19.twitchunblock.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Links
import com.mxfia19.twitchunblock.core.Suggestion
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Recherche (chaînes, catégories, liens collés) et catégories du moment.
 *  Partagé avec la TV. */
class SearchModel(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var channels by mutableStateOf<List<Suggestion>>(emptyList())
        private set
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    val top = mutableStateListOf<Category>()
    var topLoading by mutableStateOf(false)
        private set
    var topMore by mutableStateOf(false)
        private set
    private var topNext: String? = null
    private var job: Job? = null

    fun update(text: String) {
        query = text
        job?.cancel()
        val q = text.trim()
        if (q.isEmpty() || Links.isVod(q) || Links.clipSlug(q) != null) {
            channels = emptyList()
            categories = emptyList()
            searching = false
            return
        }
        searching = true
        job = scope.launch {
            delay(350)
            val users = BrowseApi.searchUsers(q)
            val cats = BrowseApi.searchCategories(q) ?: emptyList()
            channels = users.distinctBy { it.login }
            categories = cats.distinctBy { it.id }
            searching = false
        }
    }

    fun loadTop() {
        if (top.isNotEmpty() || topLoading) return
        topLoading = true
        scope.launch {
            BrowseApi.topCategories(null)?.let { p ->
                top.addAll(p.items.distinctBy { it.id })
                topNext = p.next
                topMore = p.next != null
            }
            topLoading = false
        }
    }

    fun moreTop() {
        val cursor = topNext ?: return
        if (topLoading) return
        topLoading = true
        scope.launch {
            BrowseApi.topCategories(cursor)?.let { p ->
                val seen = top.map { it.id }.toHashSet()
                top.addAll(p.items.filter { it.id !in seen }.distinctBy { it.id })
                topNext = p.next
                topMore = p.next != null && !p.refused
            }
            topLoading = false
        }
    }
}

@Composable
fun SearchScreen(model: SearchModel, nav: Navigator) {
    LaunchedEffect(Unit) { model.loadTop() }
    val q = model.query.trim()
    val vodId = if (Links.isVod(q)) Links.vodId(q) else null
    val clip = Links.clipSlug(q)
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
                when {
                    vodId != null -> nav.play(PlayRequest.Vod(vodId, startMs = (Links.vodStart(q) ?: -1).let { if (it > 0) it * 1000L else -1L }))
                    clip != null -> nav.play(PlayRequest.Clip(clip))
                    Links.isValidLogin(Links.login(q)) -> nav.push(Screen.Channel(Links.login(q)))
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line, cursorColor = TU.primary,
                focusedTextColor = TU.text, unfocusedTextColor = TU.text,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (vodId != null) {
                item(span = FullSpan) { ActionRow(AppState.t("watch_vod") + " " + vodId) { nav.play(PlayRequest.Vod(vodId)) } }
            }
            if (clip != null) {
                item(span = FullSpan) { ActionRow(AppState.t("watch_clip")) { nav.play(PlayRequest.Clip(clip)) } }
            }
            if (q.isEmpty()) {
                item(span = FullSpan) {
                    Text(AppState.t("top_categories"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(vertical = 6.dp))
                }
                items(model.top, key = { "c-" + it.id }) { c -> CategoryCard(c) { nav.push(Screen.CategoryPage(c)) } }
                if (model.topLoading && model.top.isEmpty()) item(span = FullSpan) { LoadingBox() }
                else if (model.topMore) item(span = FullSpan) { MoreButton(model.topLoading) { model.moreTop() } }
            } else if (vodId == null && clip == null) {
                if (model.searching && model.channels.isEmpty()) item(span = FullSpan) { LoadingBox() }
                if (model.channels.isNotEmpty()) {
                    item(span = FullSpan) {
                        Text(AppState.t("channels"), color = TU.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    items(model.channels, key = { "u-" + it.login }, span = { GridItemSpan(maxLineSpan) }) { s ->
                        SuggestionRow(s) { nav.push(Screen.Channel(s.login)) }
                    }
                }
                if (model.categories.isNotEmpty()) {
                    item(span = FullSpan) {
                        Text(AppState.t("categories"), color = TU.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                             modifier = Modifier.padding(top = 8.dp))
                    }
                    items(model.categories, key = { "s-" + it.id }) { c -> CategoryCard(c) { nav.push(Screen.CategoryPage(c)) } }
                }
                if (!model.searching && model.channels.isEmpty() && model.categories.isEmpty()) {
                    item(span = FullSpan) { MessageBox(AppState.t("no_result")) }
                }
            }
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

@Composable
fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusCard(onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.avatar, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (s.isLive) {
                Text(listOfNotNull(s.game, "● " + Format.viewers(s.viewers ?: 0)).joinToString(" · "),
                     color = TU.muted, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (s.isLive) LiveBadge()
    }
}
