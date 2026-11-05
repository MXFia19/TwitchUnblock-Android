package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState

/**
 * Tirer pour actualiser (Material 3). L'indicateur ne tourne que pour le
 * chargement lancé par le geste (`loading` qui suit), pas pour les
 * chargements automatiques.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(loading: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var pulled by remember { mutableStateOf(false) }
    // Chargement fini, ou refusé (déjà en cours, liste fraîche) : l'indicateur s'en va.
    LaunchedEffect(pulled, loading) { if (pulled && !loading) pulled = false }
    PullToRefreshBox(
        isRefreshing = pulled && loading,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/** Fin d'une liste que Twitch ne donne pas plus loin (more_unavailable,
 *  more_needs_login). */
@Composable
fun ListEndNote(key: String) {
    Text(AppState.t(key), color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
         modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp))
}

/** « N masqués ici · Gérer » : des éléments d'une liste sont masqués ; un
 *  toucher ouvre la liste des chaînes et catégories masquées. */
@Composable
fun HiddenFooter(count: Int, nav: Navigator) {
    if (count <= 0) return
    Text(AppState.t("hidden_footer").replace("{n}", "$count"), color = TU.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.push(Screen.Hidden) }
             .padding(horizontal = 16.dp, vertical = 12.dp))
}

/** « Masqué · Réafficher » : chaîne ou catégorie masquée des listes, que la
 *  recherche montre quand même ; un toucher la réaffiche. */
@Composable
fun HiddenMark(modifier: Modifier = Modifier, onUnhide: () -> Unit) {
    Text(AppState.t("hidden_badge") + " · " + AppState.t("unhide"), color = TU.muted, fontSize = 12.sp,
         fontWeight = FontWeight.SemiBold, maxLines = 1,
         modifier = modifier.clip(RoundedCornerShape(12.dp)).background(TU.surfaceHi).clickable(onClick = onUnhide)
             .padding(horizontal = 9.dp, vertical = 4.dp))
}

/** Refus de Twitch pour un suivi (limite atteinte, chaîne qui bloque…), avec son code. */
fun followFailedText(code: String): String = AppState.t("follow_failed") + if (code.isEmpty()) "" else " ($code)"

/**
 * Tags d'un live (langue comprise), comme sous les cartes de Twitch : une
 * seule ligne ; ceux qui ne tiennent pas sont omis, pas tronqués (le premier,
 * trop long à lui seul, est coupé). Comme TagChips (iOS).
 */
@Composable
fun TagChips(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Layout(
        content = {
            for (t in tags.take(8)) {
                Text(t, color = TU.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     softWrap = false, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(TU.surfaceHi)
                         .padding(horizontal = 7.dp, vertical = 2.dp))
            }
        },
        modifier = modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val placed = ArrayList<Pair<Placeable, Int>>()
        var x = 0
        for ((i, m) in measurables.withIndex()) {
            val p = m.measure(Constraints(maxWidth = if (i == 0) max else Constraints.Infinity))
            if (i > 0 && x + p.width > max) break
            placed += p to x
            x += p.width + gap
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (x - gap).coerceAtLeast(0)
        layout(width, placed.maxOfOrNull { it.first.height } ?: 0) {
            for ((p, px) in placed) p.placeRelative(px, 0)
        }
    }
}

/** Puce d'un choix (tri, langue), plus petite que TabChip. */
@Composable
fun SmallChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) TU.primary else TU.surfaceHi)
            .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) androidx.compose.ui.graphics.Color.White else TU.text, fontSize = 13.sp,
             fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Sans défilement à l'intérieur (message d'erreur, liste vide) : de quoi tirer quand même. */
@Composable
fun PullableBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}
