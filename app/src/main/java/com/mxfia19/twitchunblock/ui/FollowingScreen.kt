package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.ChannelBrief
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
        model.isEmptySetup -> MessageBox(AppState.t("following_empty"), action = AppState.t("tv_login")) { nav.push(Screen.Login) }
        model.loading && model.channels.isEmpty() -> LoadingBox()
        model.failed -> MessageBox(AppState.t("err_network"), action = AppState.t("retry")) { model.load(force = true) }
        model.channels.isEmpty() -> MessageBox(AppState.t("following_none"))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(model.channels, key = { it.login }) { b -> ChannelRow(b) { nav.push(Screen.Channel(b.login)) } }
        }
    }
}
