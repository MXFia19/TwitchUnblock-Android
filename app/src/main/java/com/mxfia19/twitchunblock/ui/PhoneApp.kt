package com.mxfia19.twitchunblock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.player.PlayRequest

/** Écrans du téléphone. Les quatre onglets, puis les pages empilées. */
sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Following : Screen()
    object Library : Screen()
    object Settings : Screen()
    object Login : Screen()
    object Logs : Screen()
    object Feedback : Screen()
    object WebLogin : Screen()
    object Drops : Screen()
    object PointsTracker : Screen()
    object Hidden : Screen()
    data class FeedbackThread(val id: String) : Screen()
    data class Channel(val login: String) : Screen()
    data class CategoryPage(val category: Category) : Screen()
    data class Player(val request: PlayRequest) : Screen()
    /** Multi-live, avec une première chaîne (ou vide). */
    data class MultiLive(val first: String?) : Screen()
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun tab(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    /** Une lecture remplace celle en cours (un seul lecteur à la fois). */
    fun play(request: PlayRequest) {
        stack.removeAll { it is Screen.Player || it is Screen.MultiLive }
        stack.add(Screen.Player(request))
    }

    /** Multi-live à la place de la lecture en cours. */
    fun multi(first: String?) {
        stack.removeAll { it is Screen.Player || it is Screen.MultiLive }
        stack.add(Screen.MultiLive(first))
    }
}

@Composable
fun PhoneApp(nav: Navigator) {
    val scope = rememberCoroutineScope()
    val home = remember { HomeModel(scope) }
    val search = remember { SearchModel(scope) }
    val following = remember { FollowingModel(scope) }
    val screen = nav.current
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    UpdatePrompt()
    if (screen is Screen.Player) {
        PlayerScreen(screen.request, nav)
        return
    }
    if (screen is Screen.MultiLive) {
        Box(Modifier.fillMaxSize().background(TU.bg).statusBarsPadding()) { MultiLiveScreen(screen.first, nav) }
        return
    }
    val isTab = screen is Screen.Home || screen is Screen.Search || screen is Screen.Following || screen is Screen.Library
    Scaffold(
        containerColor = TU.bg,
        topBar = { if (isTab) TopBar(screen, nav) },
        bottomBar = {
            if (isTab) {
                NavigationBar(containerColor = TU.surface) {
                    TabItem(Screen.Home, Icons.Filled.Home, "tab_home", screen, nav)
                    TabItem(Screen.Search, Icons.Filled.Search, "tv_search", screen, nav)
                    TabItem(Screen.Following, Icons.Filled.Favorite, "tv_following", screen, nav)
                    TabItem(Screen.Library, Icons.AutoMirrored.Filled.List, "dl_section", screen, nav)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen(home, nav)
                Screen.Search -> SearchScreen(search, nav)
                Screen.Following -> FollowingScreen(following, nav)
                Screen.Library -> LibraryScreen(nav)
                Screen.Settings -> SettingsScreen(nav)
                Screen.Login -> LoginScreen(nav)
                Screen.Logs -> LogsScreen(nav)
                Screen.Feedback -> FeedbackScreen(nav)
                Screen.WebLogin -> WebLoginScreen(nav)
                Screen.Drops -> DropsScreen(nav)
                Screen.PointsTracker -> PointsTrackerScreen(nav)
                Screen.Hidden -> HiddenScreen(nav)
                is Screen.FeedbackThread -> FeedbackThreadScreen(screen.id, nav)
                is Screen.Channel -> ChannelScreen(screen.login, nav)
                is Screen.CategoryPage -> CategoryScreen(screen.category, nav)
                is Screen.Player, is Screen.MultiLive -> {}
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    target: Screen, icon: ImageVector, key: String, current: Screen, nav: Navigator,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { if (current != target) nav.tab(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(AppState.t(key)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = TU.primary, selectedTextColor = TU.primary,
            indicatorColor = TU.primaryDim, unselectedIconColor = TU.muted, unselectedTextColor = TU.muted,
        ),
    )
}

@Composable
private fun TopBar(screen: Screen, nav: Navigator) {
    val title = when (screen) {
        Screen.Search -> AppState.t("tv_search")
        Screen.Following -> AppState.t("tv_following")
        Screen.Library -> AppState.t("dl_section")
        else -> AppState.t("tab_home")
    }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TU.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        // Signaler un bug ou proposer une idée ; pastille : réponse non lue.
        Box {
            IconButton(onClick = { nav.push(Screen.Feedback) }) {
                Icon(Icons.Filled.Create, contentDescription = AppState.t("feedback"), tint = TU.text)
            }
            if (com.mxfia19.twitchunblock.feedback.Feedback.unreadCount > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(9.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(TU.live))
            }
        }
        val user = AppState.user
        if (user != null) {
            Avatar(user.avatar, 32.dp, Modifier.clickable { nav.push(Screen.Settings) })
            Spacer(Modifier.width(8.dp))
        } else {
            IconButton(onClick = { nav.push(Screen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = AppState.t("settings"), tint = TU.text)
            }
        }
    }
}

/** En-tête des pages empilées : retour et titre. */
@Composable
fun PageHeader(title: String, nav: Navigator, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { nav.pop() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppState.t("back"), tint = TU.text)
        }
        Text(title, color = TU.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
             modifier = Modifier.weight(1f))
        actions()
    }
}
