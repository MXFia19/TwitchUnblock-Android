package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.Logger

/**
 * Personnaliser l'app (Paramètres → Général) : ordre et onglets masqués,
 * onglet au lancement, parties de l'accueil, épinglées, catégories suivies.
 * Comme CustomizeView (iOS), avec des flèches au lieu du glissé.
 */
@Composable
fun CustomizeScreen(nav: Navigator) {
    var confirmReset by remember { mutableStateOf(false) }
    // Onglet de la pile masqué : la pile repart de l'onglet au lancement.
    val fixRoot = {
        val root = nav.stack.firstOrNull()
        if (root != null && BrowseTabs.isTab(root) &&
            BrowsePrefs.visibleTabs.none { BrowseTabs.screen(it) == root }) {
            nav.stack[0] = BrowseTabs.startScreen()
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("cust_title"), nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(AppState.t("br_cust_sub"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Group(AppState.t("cust_tabs")) {
                val tabs = BrowsePrefs.tabs
                tabs.forEachIndexed { i, key -> TabOrderRow(key, i, tabs.size, fixRoot) }
            }
            Text(AppState.t("br_tabs_foot"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Group(AppState.t("cust_launch_tab")) {
                Choice(AppState.t("cust_launch_tab"), BrowsePrefs.visibleTabs.map { it to AppState.t(BrowseTabs.labelKey(it)) },
                       BrowsePrefs.startTab) {
                    BrowsePrefs.launchTab.set(it)
                    Logger.info("SETTINGS", "Onglet au lancement", it)
                }
            }
            Group(AppState.t("tab_home")) {
                for (part in BrowsePrefs.homeParts) {
                    Toggle(AppState.t(homePartKey(part)), null, BrowsePrefs.showsHome(part)) { BrowsePrefs.setHomeShown(part, it) }
                }
                Toggle(AppState.t("cust_pinned_viewers"), AppState.t("cust_pinned_viewers_foot"), BrowsePrefs.pinnedByViewers.value) {
                    BrowsePrefs.pinnedByViewers.set(it)
                }
            }
            Group(AppState.t("cust_followed_cats")) {
                Choice(AppState.t("cust_cat_size"),
                       listOf("normal" to AppState.t("cust_cat_normal"), "compact" to AppState.t("cust_cat_compact")),
                       if (BrowsePrefs.compactCategories.value) "compact" else "normal") {
                    BrowsePrefs.compactCategories.set(it == "compact")
                }
                Choice(AppState.t("cust_cat_sort"),
                       listOf("viewers" to AppState.t("sort_viewers_desc"), "name" to AppState.t("sort_name")),
                       BrowsePrefs.categorySort.value) { BrowsePrefs.categorySort.set(it) }
            }
            Text(AppState.t("br_followed_cats_foot"), color = TU.muted, fontSize = 12.sp,
                 modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Group(AppState.t("cust_reset")) {
                LinkRow(AppState.t("cust_reset")) { confirmReset = true }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(AppState.t("cust_reset")) },
            text = { Text(AppState.t("br_reset_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    BrowsePrefs.reset()
                    fixRoot()
                    Logger.info("SETTINGS", "Disposition d'origine rétablie")
                }) { Text(AppState.t("cust_reset"), color = TU.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(AppState.t("cancel"), color = TU.text) } },
            containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.muted,
        )
    }
}

/** Texte d'une partie de l'accueil (clés passées par une variable : _indirect). */
private fun homePartKey(part: String): String = when (part) {
    "announcement" -> "cust_announcement"
    "continue" -> "continue_watching"
    "followed" -> "home_followed"
    "categories" -> "cust_followed_cats"
    else -> "top_streams"
}

/** Un onglet : affiché ou non, et ses flèches pour le monter ou le descendre. */
@Composable
private fun TabOrderRow(key: String, index: Int, count: Int, fixRoot: () -> Unit) {
    val shown = key !in BrowsePrefs.hiddenTabs.value
    // Le dernier onglet affiché ne se masque pas.
    val locked = shown && BrowsePrefs.visibleTabs.size <= 1
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(BrowseTabs.icon(key), contentDescription = null, tint = if (shown) TU.primary else TU.muted)
        Spacer(Modifier.width(12.dp))
        Text(AppState.t(BrowseTabs.labelKey(key)), color = if (shown) TU.text else TU.muted, fontSize = 15.sp,
             modifier = Modifier.weight(1f))
        IconButton(onClick = { BrowsePrefs.moveTab(key, -1) }, enabled = index > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = AppState.t("br_move_up"),
                 tint = if (index > 0) TU.text else TU.line)
        }
        IconButton(onClick = { BrowsePrefs.moveTab(key, 1) }, enabled = index < count - 1) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = AppState.t("br_move_down"),
                 tint = if (index < count - 1) TU.text else TU.line)
        }
        Switch(checked = shown, enabled = !locked, onCheckedChange = {
            BrowsePrefs.setTabShown(key, it)
            fixRoot()
        }, colors = SwitchDefaults.colors(checkedTrackColor = TU.primary))
    }
}
