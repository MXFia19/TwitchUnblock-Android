package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.EmoteLists
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.chat.TwitchEmotes
import com.mxfia19.twitchunblock.core.AppState

/**
 * Sélecteur d'emotes en onglets : favorites (en tête et ouvertes d'abord
 * quand il y en a), « Mes emotes » (celles du compte, rangées par chaîne),
 * la chaîne (ses emotes Twitch follower et d'abonné, puis 7TV, BTTV, FFZ ;
 * celles d'abonné que le compte n'a pas grisées avec un cadenas), puis les
 * globales par source. La recherche couvre tous les onglets. Un toucher
 * ajoute l'emote au message (une verrouillée ouvre sa fiche) ; appui long :
 * sa fiche, les favoris, copier son nom. Comme EmotePickerView (iOS).
 */
@Composable
fun EmotePickerTabs(client: ChatClient, onInfo: (ChatToken.Emote) -> Unit, onPick: (String) -> Unit) {
    val channelId = client.roomId
    val login = AppState.user?.login
    // Une requête publique par chaîne, et les jeux du compte pas encore lus.
    LaunchedEffect(channelId) { channelId?.let { TwitchEmotes.loadChannel(it) } }
    LaunchedEffect(login) { TwitchEmotes.loadUserSets() }
    val emotesVersion = Emotes.version
    val twitchVersion = TwitchEmotes.version
    val thirdParty = remember(channelId, emotesVersion) { Emotes.all(channelId).distinctBy { it.name } }
    val channelThird = remember(channelId, emotesVersion) { Emotes.channelOnly(channelId) }
    val twitchChannel = remember(channelId, login, twitchVersion) { TwitchEmotes.forChannel(channelId, login) }
    val mine = remember(channelId, login, twitchVersion) { TwitchEmotes.mine(channelId, login) }
    val favoriteCodes = EmoteLists.favorites
    val favorites = remember(thirdParty, twitchChannel, mine, favoriteCodes) {
        favoriteEmotes(thirdParty + twitchChannel.first + mine.flatMap { it.emotes }, favoriteCodes)
    }
    val channelTab = remember(twitchChannel, channelThird) { twitchChannel.first + channelThird + twitchChannel.second }
    val globals = remember(emotesVersion) { listOf("7tv" to "7TV", "bttv" to "BTTV", "ffz" to "FFZ").map { (src, label) -> Triple(src, label, Emotes.globalOf(src)) } }
    val tabs = buildList {
        if (favorites.isNotEmpty()) add("fav" to "★ " + AppState.t("emotes_fav"))
        if (mine.isNotEmpty()) add("mine" to AppState.t("emotes_mine"))
        if (channelTab.isNotEmpty()) add("channel" to AppState.t("emotes_channel"))
        for ((src, label, list) in globals) if (list.isNotEmpty()) add(src to label)
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    // La chaîne s'ouvre d'abord ; les favorites avant elle quand il y en a.
    val tab = chosen?.takeIf { c -> tabs.any { it.first == c } }
        ?: tabs.firstOrNull { it.first == "fav" }?.first ?: tabs.firstOrNull { it.first == "channel" }?.first ?: tabs.firstOrNull()?.first
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val pick = { e: Emotes.Emote -> if (e.locked) onInfo(e.asToken()) else onPick(e.name) }
    Column(Modifier.fillMaxWidth().height(270.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(AppState.t("emote_search_ph"), color = TU.muted) },
            colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
        if (q.isEmpty() && tabs.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in tabs) {
                    val on = key == tab
                    Text(label, color = if (on) TU.text else TU.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                         modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) TU.primaryDim else TU.surfaceHi)
                             .clickable { chosen = key }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        if (q.isNotEmpty()) {
            // Recherche dans tous les onglets : les favorites d'abord.
            val found = remember(q, favorites, mine, channelTab, globals) {
                (favorites + mine.flatMap { it.emotes } + channelTab + globals.flatMap { it.third })
                    .distinctBy { it.name }.filter { it.name.contains(q, ignoreCase = true) }
                    .sortedBy { if (it.name in favoriteCodes) 0 else 1 }
            }
            if (found.isEmpty()) {
                Text(AppState.t("emotes_no_result"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
            EmoteGrid { emoteCells("q", found, onInfo, pick) }
        } else if (tabs.isEmpty()) {
            Text(AppState.t("emotes_loading"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        } else {
            EmoteGrid {
                when (tab) {
                    "fav" -> emoteCells("f", favorites, onInfo, pick)
                    "mine" -> mineCells(mine, onInfo, pick)
                    "channel" -> emoteCells("c", channelTab, onInfo, pick)
                    else -> emoteCells("g-$tab", globals.firstOrNull { it.first == tab }?.third.orEmpty(), onInfo, pick)
                }
            }
        }
    }
}

/** « Mes emotes » : une section par chaîne (avatar et nom), puis « Twitch ». */
private fun LazyGridScope.mineCells(mine: List<TwitchEmotes.Section>, onInfo: (ChatToken.Emote) -> Unit,
                                    pick: (Emotes.Emote) -> Unit) {
    for (s in mine) {
        item(key = "h-" + s.id, span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.avatar != null) {
                    AsyncImage(model = s.avatar, contentDescription = null, modifier = Modifier.size(18.dp).clip(CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(s.title, color = TU.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
        }
        emoteCells("m-" + s.id, s.emotes, onInfo, pick)
    }
}

@Composable
private fun EmoteGrid(content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize().padding(4.dp), content = content)
}

private fun LazyGridScope.emoteCells(prefix: String, list: List<Emotes.Emote>, onInfo: (ChatToken.Emote) -> Unit,
                                     pick: (Emotes.Emote) -> Unit) {
    items(list, key = { "$prefix:" + (it.id ?: it.name) + ":" + it.name }) { e -> PickerEmote(e, onInfo) { pick(e) } }
}

/** Fond d'une case verrouillée (emote d'abonné que le compte n'a pas). */
internal fun Modifier.lockedEmote(locked: Boolean): Modifier =
    if (locked) this.background(TU.surfaceHi.copy(alpha = 0.6f), RoundedCornerShape(6.dp)) else this

@Composable
internal fun LockBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(TU.bg), contentAlignment = Alignment.Center) {
        Text("🔒", fontSize = 8.sp)
    }
}
