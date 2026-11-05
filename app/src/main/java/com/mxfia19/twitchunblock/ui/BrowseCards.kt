package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.ContinueWatching
import com.mxfia19.twitchunblock.core.FollowedCategories
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.Pins
import com.mxfia19.twitchunblock.core.TwitchStream
import kotlinx.coroutines.delay

/** Icônes absentes de material-icons-core (tracés Material, 24 × 24). */
object BrowseIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val pin by lazy {
        icon("pin", "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5" +
            "c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z")
    }
    val explore by lazy {
        icon("explore", "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1z" +
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z")
    }
    val volumeUp by lazy {
        icon("volume_up", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
            "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    }
    val volumeOff by lazy {
        icon("volume_off", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z" +
            "M19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06" +
            "c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06" +
            "c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z")
    }
    val grid by lazy {
        icon("grid", "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9z" +
            "M13,13v8h8v-8H13zM19,19h-4v-4h4V19z")
    }
    val clock by lazy {
        icon("clock", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20" +
            "c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")
    }
}

/**
 * Colonnes des grilles : une carte par ligne sur un téléphone en portrait,
 * davantage dès que la largeur le permet (tablette, écran intérieur d'un
 * pliable, paysage), jusqu'à 5 ou 6 sur une grande tablette.
 */
object BrowseGrid {
    /** Lives, VODs et clips en cartes 16:9. */
    val cards: GridCells = GridCells.Adaptive(220.dp)
    /** Lignes (lives en liste, chaînes suivies) : deux colonnes sur tablette. */
    val rows: GridCells = GridCells.Adaptive(340.dp)

    fun lives(list: Boolean): GridCells = if (list) rows else cards
}

/** Heure courante redessinée chaque minute (durée des directs). */
@Composable
fun rememberMinuteTick(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** « 2h 14min » depuis le début du direct ; null sans date de début. */
fun uptimeText(startedAt: String?, now: Long): String? {
    val start = Format.parseDate(startedAt) ?: return null
    // Horloge de l'appareil en retard sur Twitch : pas de durée négative.
    val s = maxOf(0L, (now - start) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}

@Composable
fun PinMark(size: Dp = 12.dp) {
    Icon(BrowseIcons.pin, contentDescription = AppState.t("pinned_short"), tint = TU.primary, modifier = Modifier.size(size))
}

/** Durée du direct, icône d'horloge comprise. */
@Composable
private fun Uptime(text: String, color: Color, size: Int = 11) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(BrowseIcons.clock, contentDescription = null, tint = color, modifier = Modifier.size((size + 1).dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Direct en carte : miniature (spectateurs, durée), titre, chaîne, jeu. */
@Composable
fun LiveCard(stream: TwitchStream, pinned: Boolean, showGame: Boolean, modifier: Modifier = Modifier,
             onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val now = rememberMinuteTick()
    // Miniature rafraîchie chaque minute (le CDN la garde en cache).
    val stamp = now / 60_000
    Column(modifier.focusCard(onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TU.surfaceHi)) {
            AsyncImage(model = stream.thumb() + "?t=$stamp", contentDescription = null,
                       contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            Pill("● " + Format.viewers(stream.viewers), Modifier.align(Alignment.BottomStart).padding(6.dp))
            uptimeText(stream.startedAt, now)?.let {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Uptime(it, Color.White)
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(stream.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    PinMark()
                    Spacer(Modifier.width(3.dp))
                }
                Text(stream.name, color = TU.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showGame && stream.game.isNotEmpty()) {
                Text(stream.game, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Tags (langue, DropsEnabled…), comme sous les cartes de Twitch.
            TagChips(stream.tags, Modifier.padding(top = 3.dp))
        }
    }
}

/** Direct en ligne, comme sur Twitch : miniature à gauche, puis chaîne,
 *  titre, jeu et durée. */
@Composable
fun LiveRow(stream: TwitchStream, pinned: Boolean, showGame: Boolean, modifier: Modifier = Modifier,
            onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val now = rememberMinuteTick()
    val stamp = now / 60_000
    Row(modifier.fillMaxWidth().focusCard(onClick, onLongClick = onLongClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(TU.surfaceHi)) {
            AsyncImage(model = stream.thumb(320, 180) + "?t=$stamp", contentDescription = null,
                       contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Pill("● " + Format.viewers(stream.viewers), Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    PinMark()
                    Spacer(Modifier.width(3.dp))
                }
                Text(stream.name, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
            if (stream.title.isNotEmpty()) {
                Text(stream.title, color = TU.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val uptime = uptimeText(stream.startedAt, now)
            if ((showGame && stream.game.isNotEmpty()) || uptime != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showGame && stream.game.isNotEmpty()) {
                        Text(stream.game, color = TU.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (uptime != null) Uptime(uptime, TU.muted, 12)
                }
            }
            TagChips(stream.tags, Modifier.padding(top = 1.dp))
        }
    }
}

/**
 * Direct d'une liste du téléphone, en carte ou en ligne (Lives en liste).
 * Appui long : épingler une chaîne suivie (`pinnable`), ou masquer la
 * chaîne ou sa catégorie (`hideable`) — jamais ce qu'on suit ou épingle.
 */
@Composable
fun BrowseStreamItem(stream: TwitchStream, list: Boolean, pinnable: Boolean = false, hideable: Boolean = false,
                     hideCategory: Boolean = true, showGame: Boolean = true, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pinned = Pins.isPinned(stream.login)
    val hideChannel = hideable && !BrowseRules.follows(stream.login)
    val hideGame = hideable && hideCategory && stream.gameId.isNotEmpty() && stream.game.isNotEmpty() &&
        !FollowedCategories.isFollowed(stream.gameId)
    val long: (() -> Unit)? = if (pinnable || hideChannel || hideGame) ({ menu = true }) else null
    Box {
        if (list) LiveRow(stream, pinnable && pinned, showGame, onLongClick = long, onClick = onClick)
        else LiveCard(stream, pinnable && pinned, showGame, onLongClick = long, onClick = onClick)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (pinnable) {
                DropdownMenuItem(text = { Text(AppState.t(if (pinned) "unpin_streamer" else "pin_streamer")) },
                                 leadingIcon = { Icon(BrowseIcons.pin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                 onClick = { menu = false; Pins.toggle(stream.login) })
            }
            if (hideChannel) {
                DropdownMenuItem(text = { Text(AppState.t("hide_channel")) }, onClick = {
                    menu = false
                    Hidden.hideChannel(stream.login, stream.name)
                })
            }
            if (hideGame) {
                DropdownMenuItem(text = { Text(AppState.t("hide_category").replace("{c}", stream.game)) }, onClick = {
                    menu = false
                    Hidden.hideCategory(stream.gameId, stream.game, "")
                })
            }
        }
    }
}

/** Bouton grille / liste des lives (même réglage que dans Paramètres). */
@Composable
fun LayoutToggle() {
    val list = BrowsePrefs.homeList.value
    androidx.compose.material3.IconButton(onClick = { BrowsePrefs.homeList.set(!list) }) {
        Icon(if (list) BrowseIcons.grid else Icons.AutoMirrored.Filled.List,
             contentDescription = AppState.t(if (list) "layout_grid" else "layout_list"), tint = TU.muted)
    }
}

/** VOD de « Continuer à regarder » : appui long pour reprendre ou la
 *  retirer de la liste (sa position de reprise est oubliée ; elle reste
 *  dans Mes VODs). */
@Composable
fun ContinueItem(entry: ContinueWatching.Entry, modifier: Modifier = Modifier, onRemove: () -> Unit, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ContinueCard(entry, modifier, onLongClick = { menu = true }, onClick = onPlay)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t("continue_resume")) }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(text = { Text(AppState.t("continue_remove"), color = TU.danger) },
                             onClick = { menu = false; onRemove() })
        }
    }
}

/** Rangée des catégories suivies (accueil) : appui long pour ne plus
 *  suivre une catégorie de l'appareil. */
@Composable
fun FollowedCategoriesRow(onOpen: (Category) -> Unit) {
    val list = FollowedCategories.sorted()
    if (list.isEmpty()) return
    val compact = BrowsePrefs.compactCategories.value
    Column {
        Text(AppState.t("cust_followed_cats"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
            items(list, key = { "fc-" + it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    CategoryCard(c, Modifier.width(if (compact) 84.dp else 112.dp),
                                 onLongClick = if (FollowedCategories.isFollowedLocally(c.id)) ({ menu = true }) else null) { onOpen(c) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(AppState.t("unfollow")) }, onClick = {
                            menu = false
                            FollowedCategories.toggle(c)
                        })
                    }
                }
            }
        }
    }
}
