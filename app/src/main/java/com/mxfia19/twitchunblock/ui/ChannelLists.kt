package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.NavApi
import com.mxfia19.twitchunblock.core.Playlist
import com.mxfia19.twitchunblock.core.Vod
import com.mxfia19.twitchunblock.player.PlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Temps forts et playlists d'une chaîne, lus à l'ouverture de leur onglet
 *  (série 6). Comme highlightsSection et playlistsSection (SearchView.swift). */
class ChannelListsModel(val login: String, private val scope: CoroutineScope) {
    /** null : pas encore lus (ou Twitch n'a pas répondu, `…Failed`). */
    var highlights by mutableStateOf<List<Vod>?>(null)
        private set
    var highlightsFailed by mutableStateOf(false)
        private set
    var playlists by mutableStateOf<List<Playlist>?>(null)
        private set
    var playlistsFailed by mutableStateOf(false)
        private set
    private var highlightsBusy = false
    private var playlistsBusy = false

    fun loadHighlights(force: Boolean = false) {
        if ((highlights != null && !force) || highlightsBusy) return
        highlightsBusy = true
        highlightsFailed = false
        scope.launch {
            val list = NavApi.highlights(login)
            if (list != null) highlights = list else highlightsFailed = highlights == null
            highlightsBusy = false
        }
    }

    fun loadPlaylists(force: Boolean = false) {
        if ((playlists != null && !force) || playlistsBusy) return
        playlistsBusy = true
        playlistsFailed = false
        scope.launch {
            val list = NavApi.collections(login)
            if (list != null) playlists = list else playlistsFailed = playlists == null
            playlistsBusy = false
        }
    }
}

/** Onglet Temps forts : les 50 derniers. */
fun LazyGridScope.highlightsTab(model: ChannelListsModel, login: String, nav: Navigator) {
    val list = model.highlights
    when {
        model.highlightsFailed -> item(span = FullSpan) {
            MessageBox(AppState.t("err_loading"), action = AppState.t("retry")) { model.loadHighlights() }
        }
        list == null -> item(span = FullSpan) { LoadingBox() }
        list.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("no_highlights")) }
        else -> items(list, key = { "h-" + it.id }) { v ->
            VodCard(v) { nav.play(PlayRequest.Vod(v.id, v.title, login)) }
        }
    }
}

/** Onglet Playlists : chacune avec ses vidéos en rangée et « Tout lire ». */
fun LazyGridScope.playlistsTab(model: ChannelListsModel, login: String, nav: Navigator) {
    val list = model.playlists
    when {
        model.playlistsFailed -> item(span = FullSpan) {
            MessageBox(AppState.t("err_loading"), action = AppState.t("retry")) { model.loadPlaylists() }
        }
        list == null -> item(span = FullSpan) { LoadingBox() }
        list.isEmpty() -> item(span = FullSpan) { MessageBox(AppState.t("no_playlists")) }
        else -> items(list, key = { "p-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { p -> PlaylistRow(p, login, nav) }
    }
}

@Composable
private fun PlaylistRow(p: Playlist, login: String, nav: Navigator) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, color = TU.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                     overflow = TextOverflow.Ellipsis)
                val count = AppState.t("videos_count").replace("%d", "${p.total}")
                Text(if (p.description.isEmpty()) count else p.description + " · " + count, color = TU.muted, fontSize = 12.sp,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            // Toute la playlist à la suite (PlayQueue).
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(TU.primary.copy(alpha = 0.18f))
                    .clickable { PlayQueue.start(nav, p.videos, login) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TU.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(AppState.t("play_all"), color = TU.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(p.videos, key = { it.id }) { v ->
                VodCard(v, Modifier.width(200.dp)) { nav.play(PlayRequest.Vod(v.id, v.title, login)) }
            }
        }
    }
}

/** Filtre des VODs par mot-clé (titres des VODs chargées). */
@Composable
fun VodFilterField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        placeholder = { Text(AppState.t("ph_keyword"), color = TU.muted) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TU.muted) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = null, tint = TU.muted) }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line, cursorColor = TU.primary,
            focusedTextColor = TU.text, unfocusedTextColor = TU.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Titre qui contient le mot-clé (sans tenir compte de la casse). */
fun vodMatches(v: Vod, keyword: String): Boolean {
    val k = keyword.trim()
    return k.isEmpty() || v.title.contains(k, ignoreCase = true)
}

/** Sous l'en-tête d'une chaîne : suivie « sur cet appareil » (sans session
 *  web), refus de Twitch, et « Masqué · Réafficher ». */
@Composable
fun ChannelStatusLines(localFollow: Boolean, followError: String?, hidden: Boolean, onUnhide: () -> Unit) {
    if (!localFollow && followError == null && !hidden) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (localFollow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = TU.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(AppState.t("following") + " · " + AppState.t("on_this_device"), color = TU.muted, fontSize = 13.sp)
            }
        }
        // Twitch a refusé (limite de suivis, chaîne qui bloque…) : le cœur garde son état, et on le dit.
        if (followError != null) Text(followFailedText(followError), color = TU.danger, fontSize = 13.sp)
        if (hidden) HiddenMark(onUnhide = onUnhide)
    }
}
