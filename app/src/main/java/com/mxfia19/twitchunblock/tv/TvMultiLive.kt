package com.mxfia19.twitchunblock.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.ui.MultiGrid
import com.mxfia19.twitchunblock.ui.MultiLiveModel
import com.mxfia19.twitchunblock.ui.PauseInBackground
import com.mxfia19.twitchunblock.ui.TU

/** Multi-live à la télécommande : la mosaïque, puis une rangée de boutons —
 *  le son de chaque vidéo, ajouter, retirer celle qui a le son. */
@Composable
fun TvMultiLive(first: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { MultiLiveModel(context, scope) }
    var adding by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        first?.let { model.add(it) }
        model.loadSuggestions()
    }
    LaunchedEffect(adding, model.tiles.size) { runCatching { if (adding) listFocus.requestFocus() else focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { model.release() } }
    PauseInBackground(model)
    BackHandler(enabled = adding) { adding = false }
    BackHandler(enabled = !adding) { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            MultiGrid(model, landscape = true, modifier = Modifier.weight(1f).fillMaxWidth(), removable = false)
            Row(Modifier.fillMaxWidth().background(Color(0xE6101010)).padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                model.tiles.forEachIndexed { i, t ->
                    TvButton((if (i == model.audio) "🔊 " else "") + t.login, selected = i == model.audio,
                             modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier) { model.selectAudio(i) }
                }
                if (!model.full) {
                    TvButton("+ " + AppState.t("multi_add_ok"),
                             modifier = if (model.tiles.isEmpty()) Modifier.focusRequester(focus) else Modifier) { adding = true }
                }
                if (model.tiles.isNotEmpty()) {
                    TvButton(AppState.t("multi_remove")) { model.remove(model.audio) }
                }
            }
        }
        if (adding) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(24.dp).width(360.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xF0181818)).padding(16.dp),
            ) {
                Text(AppState.t("multi_add_channel"), color = TU.muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
                val known = model.tiles.map { it.login }.toSet()
                val list = model.suggestions.filter { it.login.lowercase() !in known }
                if (list.isEmpty()) {
                    Text(AppState.t("tv_no_live"), color = TU.muted, fontSize = 14.sp)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.login }) { s ->
                        val source = remember { MutableInteractionSource() }
                        val focused by source.collectIsFocusedAsState()
                        val index = list.indexOf(s)
                        Column(
                            Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(listFocus) else Modifier)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Color.White else Color.Transparent)
                                .border(1.dp, TU.line, RoundedCornerShape(10.dp))
                                .clickable(interactionSource = source, indication = null) {
                                    model.add(s.login)
                                    adding = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(s.name, color = if (focused) Color.Black else TU.text, fontSize = 16.sp)
                            Text(s.game, color = if (focused) Color.DarkGray else TU.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
