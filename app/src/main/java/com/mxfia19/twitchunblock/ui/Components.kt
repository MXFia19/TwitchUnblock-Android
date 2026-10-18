package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Hidden
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Category
import com.mxfia19.twitchunblock.core.ChannelBrief
import com.mxfia19.twitchunblock.core.Clip
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.Vod

/** Carte qui se met en avant quand elle a le focus (télécommande, clavier).
 *  `onLongClick` : appui long (masquer…). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusCard(onClick: () -> Unit, shape: RoundedCornerShape = RoundedCornerShape(10.dp),
                       onLongClick: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .scale(if (focused) 1.04f else 1f)
        .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent), shape)
        .clip(shape)
        .combinedClickable(interactionSource = source, indication = null, onLongClick = onLongClick, onClick = onClick)
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(TU.surfaceHi)) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        AppState.t("live_badge"),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(TU.live).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
         modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

/** Direct : miniature, puis titre, chaîne et jeu. */
@Composable
fun StreamCard(stream: TwitchStream, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // Miniature rafraîchie chaque minute (le CDN la garde en cache).
    val stamp = System.currentTimeMillis() / 60_000
    Column(modifier.focusCard(onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TU.surfaceHi)) {
            AsyncImage(model = stream.thumb() + "?t=$stamp", contentDescription = null,
                       contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            Pill("● " + Format.viewers(stream.viewers), Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(stream.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stream.name, color = TU.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stream.game.isNotEmpty()) {
                Text(stream.game, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CategoryCard(category: Category, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(modifier.focusCard(onClick, onLongClick = onLongClick)) {
        AsyncImage(model = category.art(), contentDescription = null, contentScale = ContentScale.Crop,
                   modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(TU.surfaceHi))
        Text(category.name, color = TU.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
        category.viewers?.let {
            Text(AppState.tn("viewers", it, abbreviated = it >= 1000).let { w -> "${Format.viewers(it)} $w" },
                 color = TU.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Direct d'une liste (top, catégorie) : appui long pour masquer la chaîne
 *  ou sa catégorie. */
@Composable
fun HideableStreamCard(stream: TwitchStream, withCategory: Boolean = true, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        StreamCard(stream, onLongClick = { menu = true }, onClick = onClick)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t("hide_channel")) }, onClick = {
                menu = false
                Hidden.hideChannel(stream.login, stream.name)
            })
            if (withCategory && stream.gameId.isNotEmpty() && stream.game.isNotEmpty()) {
                DropdownMenuItem(text = { Text(AppState.t("hide_category").replace("{c}", stream.game)) }, onClick = {
                    menu = false
                    Hidden.hideCategory(stream.gameId, stream.game, "")
                })
            }
        }
    }
}

/** Catégorie du top : appui long pour la masquer. */
@Composable
fun HideableCategoryCard(category: Category, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        CategoryCard(category, onLongClick = { menu = true }, onClick = onClick)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t("hide_this_category")) }, onClick = {
                menu = false
                Hidden.hideCategory(category.id, category.name, category.art())
            })
        }
    }
}

@Composable
fun VodCard(vod: Vod, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val resume = AppState.vodPosition(vod.id)
    Column(modifier.focusCard(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TU.surfaceHi)) {
            AsyncImage(model = vod.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                       modifier = Modifier.fillMaxSize())
            Pill(Format.clock(vod.lengthSeconds.toLong()), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (resume > 0 && vod.lengthSeconds > 0) {
                LinearProgressIndicator(
                    progress = { (resume / 1000f / vod.lengthSeconds).coerceIn(0f, 1f) },
                    color = TU.primary, trackColor = Color(0x66000000),
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Text(vod.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Format.parseDate(vod.publishedAt)?.let {
            Text(Format.shortDate(it), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
fun ClipCard(clip: Clip, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.focusCard(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TU.surfaceHi)) {
            AsyncImage(model = clip.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                       modifier = Modifier.fillMaxSize())
            Pill(Format.clock(clip.durationSeconds.toLong()), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            Pill("▶ " + Format.viewers(clip.viewCount), Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Text(clip.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

/** Chaîne suivie : avatar, nom, direct en cours ou hors ligne depuis. */
@Composable
fun ChannelRow(brief: ChannelBrief, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusCard(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(brief.avatar, 44.dp)
            if (brief.stream != null) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(TU.live).align(Alignment.BottomEnd))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(brief.name, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val s = brief.stream
            val sub = when {
                s != null -> listOf(s.game, "● ${Format.viewers(s.viewers)}").filter { it.isNotEmpty() }.joinToString(" · ")
                brief.lastEnd != null -> AppState.t("offline") + " · " + Format.since(brief.lastEnd)
                else -> AppState.t("offline")
            }
            Text(sub, color = if (s != null) TU.text.copy(alpha = 0.8f) else TU.muted, fontSize = 13.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s != null && s.title.isNotEmpty()) {
                Text(s.title, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TU.primary)
    }
}

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = TU.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) { Text(action) }
        }
    }
}

@Composable
fun MoreButton(loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(28.dp))
        else OutlinedButton(onClick = onClick) { Text(AppState.t("load_more"), color = TU.text) }
    }
}

/** Élément d'une grille sur toute la largeur (titres, boutons). */
val FullSpan: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
    { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
