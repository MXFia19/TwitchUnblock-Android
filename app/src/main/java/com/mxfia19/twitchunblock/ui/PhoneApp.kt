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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope

/** Écrans du téléphone. Les quatre onglets, puis les pages empilées. */
sealed class Screen {
    object Home : Screen()
    object Discover : Screen()
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
    /** Personnaliser l'app : onglets, accueil (SettingsBrowse). */
    object Customize : Screen()
    data class FeedbackThread(val id: String) : Screen()
    data class Channel(val login: String) : Screen()
    data class CategoryPage(val category: Category) : Screen()
    data class Player(val request: PlayRequest) : Screen()
    /** Multi-live, avec une première chaîne (ou vide). */
    data class MultiLive(val first: String?) : Screen()
}

class Navigator {
    // Onglet au lancement : Personnaliser l'app (l'accueil par défaut).
    val stack = mutableStateListOf<Screen>(BrowseTabs.startScreen())
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
        // Même lecture redemandée (notification, widget) : la session reste la
        // même et rien ne la relance ; en erreur ou finie, elle repart.
        val session = PlayerHolder.current
        if (session != null && session.request == request &&
            (session.state is PlaybackSession.State.Error || session.player.playbackState == androidx.media3.common.Player.STATE_ENDED)) {
            session.retry()
        }
    }

    /** Multi-live à la place de la lecture en cours. */
    fun multi(first: String?) {
        stack.removeAll { it is Screen.Player || it is Screen.MultiLive }
        stack.add(Screen.MultiLive(first))
    }
}

@Composable
fun PhoneApp(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { HomeModel(scope) }
    val search = remember { SearchModel(scope) }
    val following = remember { FollowingModel(scope) }
    val screen = nav.current
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Pages de la pile : défilement, onglet et modèles gardés pendant qu'une
    // autre est ouverte par-dessus (comme la pile de l'iPhone), oubliés quand
    // elles en sortent (une page rouverte repart de zéro).
    val pages = rememberSaveableStateHolder()
    val models = remember { HashMap<String, PageModels>() }
    val keys = nav.stack.mapIndexed { i, s -> pageKey(i, s) }
    val stacked = remember { ArrayList<String>() }
    SideEffect {
        for (k in stacked) if (k !in keys) {
            pages.removeState(k)
            models.remove(k)
        }
        stacked.clear()
        stacked.addAll(keys)
    }

    // Lecture principale, portée ici et non par l'écran du lecteur : elle
    // continue sous une fiche chaîne, la connexion ou la session web, et
    // reprend telle quelle au retour (rembobinage compris). Arrêtée quand le
    // lecteur quitte la pile (fermé, onglet, multi-live).
    val playing = nav.stack.filterIsInstance<Screen.Player>().lastOrNull()?.request
    val session = remember(playing != null) { if (playing != null) PlaybackSession(context, scope) else null }
    DisposableEffect(session) { onDispose { session?.release() } }
    // Lecture déjà ouverte par la session (VOD suivante de « Tout lire », lancée
    // même app en arrière-plan) : pas relue.
    LaunchedEffect(session, playing) { if (session != null && playing != null && session.request != playing) session.open(playing) }
    // « Tout lire » : VOD suivante de la playlist à la fin de celle-ci (PlayQueue.kt).
    PlayQueueEffect(session, nav)
    // Multi-live, porté ici aussi : ses tuiles restent (en pause) sous la
    // connexion ouverte depuis son chat, au lieu d'être perdues au retour.
    val multiOpen = nav.stack.any { it is Screen.MultiLive }
    val multi = remember(multiOpen) { if (multiOpen) MultiLiveModel(context, scope) else null }
    DisposableEffect(multi) { onDispose { multi?.release() } }

    UpdatePrompt()
    BrowseEffects()
    if (screen is Screen.Player && session != null) {
        PlayerScreen(session, nav)
        return
    }
    if (screen is Screen.MultiLive && multi != null) {
        Box(Modifier.fillMaxSize().background(TU.bg).statusBarsPadding()) { MultiLiveScreen(multi, screen.first, nav) }
        return
    }
    val isTab = BrowseTabs.isTab(screen)
    // Écran large (tablette, pliable ouvert, paysage) : onglets en colonne à gauche.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    Scaffold(
        containerColor = TU.bg,
        topBar = { if (isTab) TopBar(screen, nav) },
        bottomBar = {
            if (isTab && !wide) {
                NavigationBar(containerColor = TU.surface) {
                    // Ordre et onglets masqués : Personnaliser l'app.
                    for (key in com.mxfia19.twitchunblock.core.BrowsePrefs.visibleTabs) {
                        TabItem(BrowseTabs.screen(key), BrowseTabs.icon(key), BrowseTabs.labelKey(key), screen, nav)
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
          if (isTab && wide) BrowseTabRail(screen, nav)
          Box(Modifier.weight(1f).fillMaxSize()) {
            val key = keys.last()
            pages.SaveableStateProvider(key) {
            CompositionLocalProvider(LocalPageModels provides models.getOrPut(key) { PageModels(scope) }) {
            when (screen) {
                Screen.Home -> HomeScreen(home, nav)
                Screen.Discover -> DiscoverScreen(nav)
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
                Screen.Customize -> CustomizeScreen(nav)
                is Screen.FeedbackThread -> FeedbackThreadScreen(screen.id, nav)
                is Screen.Channel -> ChannelScreen(screen.login, nav)
                is Screen.CategoryPage -> CategoryScreen(screen.category, nav)
                is Screen.Player, is Screen.MultiLive -> {}
            }
            }
            }
          }
        }
    }
}

/** Clé d'une page de la pile : sa place et ce qu'elle montre (texte simple,
 *  gardé dans l'état sauvegardé de l'activité). */
private fun pageKey(index: Int, s: Screen): String = "$index:" + when (s) {
    is Screen.Channel -> "channel:" + s.login
    is Screen.CategoryPage -> "category:" + s.category.id
    is Screen.FeedbackThread -> "thread:" + s.id
    else -> s.javaClass.name
}

/** Modèles d'une page de la pile (chaîne, catégorie), gardés tant qu'elle y
 *  reste : au retour d'un live ou d'un clip, rien n'est relu. Portée de
 *  l'app : un chargement n'est pas coupé quand une page passe dessous. */
class PageModels(val scope: CoroutineScope) {
    val map = HashMap<String, Any>()
}

val LocalPageModels = staticCompositionLocalOf<PageModels?> { null }

/** Modèle d'une page : celui gardé pour sa place dans la pile (téléphone),
 *  sinon créé avec la portée de l'écran (TV). */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T : Any> rememberPageModel(name: String, create: (CoroutineScope) -> T): T {
    val page = LocalPageModels.current
    val own = rememberCoroutineScope()
    return remember(page, name) {
        if (page != null) page.map.getOrPut(name) { create(page.scope) } as T else create(own)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    target: Screen, icon: ImageVector, key: String, current: Screen, nav: Navigator,
) {
    NavigationBarItem(
        selected = current == target,
        // Onglet déjà affiché : sa liste remonte en haut.
        onClick = { if (current != target) nav.tab(target) else TabTop.request(target) },
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
        Screen.Discover -> AppState.t("tab_discover")
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
