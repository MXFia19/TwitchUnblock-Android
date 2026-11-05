package com.mxfia19.twitchunblock.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.History
import com.mxfia19.twitchunblock.widget.AppShortcuts
import kotlinx.coroutines.flow.MutableSharedFlow

/** Onglets du téléphone : clé (réglages, comme l'iPhone), écran, icône, texte. */
object BrowseTabs {
    fun screen(key: String): Screen = when (key) {
        "discover" -> Screen.Discover
        "search" -> Screen.Search
        "following" -> Screen.Following
        "library" -> Screen.Library
        else -> Screen.Home
    }

    fun icon(key: String): ImageVector = when (key) {
        "discover" -> BrowseIcons.explore
        "search" -> Icons.Filled.Search
        "following" -> Icons.Filled.Favorite
        "library" -> Icons.AutoMirrored.Filled.List
        else -> Icons.Filled.Home
    }

    /** Clé du texte de l'onglet (passée par une variable : _indirect). */
    fun labelKey(key: String): String = when (key) {
        "discover" -> "tab_discover"
        "search" -> "tv_search"
        "following" -> "tv_following"
        "library" -> "dl_section"
        else -> "tab_home"
    }

    fun isTab(s: Screen) = s is Screen.Home || s is Screen.Discover || s is Screen.Search ||
        s is Screen.Following || s is Screen.Library

    /** Onglet ouvert au lancement (Personnaliser l'app). */
    fun startScreen(): Screen = screen(BrowsePrefs.startTab)
}

/** Retour en haut : l'onglet affiché, touché à nouveau, remonte sa liste. */
object TabTop {
    private val events = MutableSharedFlow<Screen>(extraBufferCapacity = 8)

    fun request(tab: Screen) {
        events.tryEmit(tab)
    }

    /** À poser dans l'écran d'un onglet : `action` remonte sa liste. */
    @Composable
    fun Listen(tab: Screen, action: suspend () -> Unit) {
        val current by rememberUpdatedState(action)
        LaunchedEffect(tab) { events.collect { if (it == tab) current() } }
    }
}

/** Colonne d'onglets à gauche d'un écran large (tablette, pliable ouvert,
 *  paysage) : toute la hauteur reste au contenu. Même ordre que la barre. */
@Composable
fun BrowseTabRail(current: Screen, nav: Navigator) {
    NavigationRail(containerColor = TU.surface) {
        for (key in BrowsePrefs.visibleTabs) {
            val target = BrowseTabs.screen(key)
            NavigationRailItem(
                selected = current == target,
                onClick = { if (current != target) nav.tab(target) else TabTop.request(target) },
                icon = { Icon(BrowseTabs.icon(key), contentDescription = null) },
                label = { Text(AppState.t(BrowseTabs.labelKey(key))) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = TU.primary, selectedTextColor = TU.primary,
                    indicatorColor = TU.primaryDim, unselectedIconColor = TU.muted, unselectedTextColor = TU.muted,
                ),
            )
        }
    }
}

/** Effets de la navigation pendant que l'app est ouverte : raccourcis de
 *  l'icône (dernières chaînes regardées), widget des directs suivis. */
@Composable
fun BrowseEffects() {
    val context = LocalContext.current
    val recent = History.channels.take(AppShortcuts.MAX).joinToString(",") { it.term + "|" + (it.avatar ?: "") }
    LaunchedEffect(recent, Features.showRecent.value, AppState.lang) { AppShortcuts.update(context) }
}
