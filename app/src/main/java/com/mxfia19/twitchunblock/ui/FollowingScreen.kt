package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.ChannelBrief
import com.mxfia19.twitchunblock.core.Pins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Chaînes suivies (compte Twitch et appareil) : en direct d'abord, puis
 *  hors ligne de la plus récente à la plus ancienne. Partagé avec la TV. */
class FollowingModel(private val scope: CoroutineScope) {
    var channels by mutableStateOf<List<ChannelBrief>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var loadedAt = 0L
    private var loadedFor: String? = null

    val isEmptySetup: Boolean get() = AppState.user == null && AppState.localFollows.isEmpty()

    fun load(force: Boolean = false) {
        val who = (AppState.user?.id ?: "") + "|" + AppState.localFollows.joinToString(",")
        if (!force && who == loadedFor && System.currentTimeMillis() - loadedAt < 120_000) return
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            val account = if (AppState.user != null) BrowseApi.followedLogins() ?: emptyList() else emptyList()
            val logins = (AppState.localFollows + account.map { it.lowercase() }).distinct()
            val briefs = BrowseApi.channelsByLogins(logins)
            if (briefs == null) {
                failed = channels.isEmpty()
            } else {
                channels = briefs.distinctBy { it.login }.sortedWith(compareByDescending<ChannelBrief> { it.stream != null }
                    .thenByDescending { it.stream?.viewers ?: 0 }
                    .thenByDescending { it.lastEnd ?: 0L })
                loadedAt = System.currentTimeMillis()
                loadedFor = who
            }
            loading = false
        }
    }
}

@Composable
fun FollowingScreen(model: FollowingModel, nav: Navigator) {
    LaunchedEffect(AppState.user?.id, AppState.localFollows.size) { model.load() }
    when {
        // « Sans compte Twitch » : seulement les suivis de l'appareil.
        model.isEmptySetup && com.mxfia19.twitchunblock.core.NoAccount.active -> MessageBox(AppState.t("ap_following_empty_local"))
        model.isEmptySetup -> MessageBox(AppState.t("following_empty"), action = AppState.t("tv_login")) { nav.push(Screen.Login) }
        model.loading && model.channels.isEmpty() -> LoadingBox()
        model.failed -> MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.load(force = true) }
        model.channels.isEmpty() -> MessageBox(AppState.t("following_none"))
        else -> {
            // Épinglées en tête (en direct, puis hors ligne) ; deux colonnes sur tablette.
            val grid = rememberLazyGridState()
            TabTop.Listen(Screen.Following) { grid.animateScrollToItem(0) }
            LazyVerticalGrid(columns = BrowseGrid.rows, state = grid, modifier = Modifier.fillMaxSize()) {
                items(Pins.sortBriefs(model.channels), key = { it.login }) { b -> PinnableChannelRow(b, nav) }
            }
        }
    }
}

/** Chaîne suivie : appui long pour l'épingler en tête des suivies, ou l'en retirer. */
@Composable
private fun PinnableChannelRow(b: ChannelBrief, nav: Navigator) {
    var menu by remember { mutableStateOf(false) }
    val pinned = Pins.isPinned(b.login)
    Box {
        ChannelRow(b, pinned = pinned, onLongClick = { menu = true }) { nav.push(Screen.Channel(b.login)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t(if (pinned) "unpin_streamer" else "pin_streamer")) },
                             onClick = { menu = false; Pins.toggle(b.login) })
        }
    }
}
