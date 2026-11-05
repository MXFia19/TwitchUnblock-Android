package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.chat.EmoteLists
import com.mxfia19.twitchunblock.chat.Emotes
import com.mxfia19.twitchunblock.core.AppState

// Emotes favorites et masquées (EmoteLists) : appui long sur une emote du
// chat ou du sélecteur, boutons de sa fiche, liste des masquées dans les
// Réglages. Comme HiddenEmotes.swift (iOS).

/** Emote d'un message : toucher, sa fiche ; appui long, l'ajouter aux
 *  favoris ou la masquer. Sans `onEmote` (TV, fiches), une image seule. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatEmoteImage(tok: ChatToken.Emote, onEmote: ((ChatToken.Emote) -> Unit)?) {
    if (onEmote == null) {
        AsyncImage(model = tok.url, contentDescription = tok.name, modifier = Modifier.fillMaxSize())
        return
    }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().combinedClickable(onClick = { onEmote(tok) }, onLongClick = { menu = true })) {
        AsyncImage(model = tok.url, contentDescription = tok.name, modifier = Modifier.fillMaxSize())
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val favorite = EmoteLists.isFavorite(tok.name)
            DropdownMenuItem(text = { Text((if (favorite) "★  " else "☆  ") + AppState.t(if (favorite) "emote_fav_remove" else "emote_fav_add")) },
                             onClick = {
                                 menu = false
                                 EmoteLists.toggleFavorite(tok.name, tok.url)
                             })
            val hidden = EmoteLists.hides(tok.name)
            DropdownMenuItem(text = { Text(AppState.t(if (hidden) "emote_unhide" else "emote_hide")) },
                             onClick = {
                                 menu = false
                                 EmoteLists.toggleHidden(tok.name)
                             })
        }
    }
}

/** Favorites utilisables ici, dans l'ordre où elles ont été ajoutées : parmi
 *  les emotes du sélecteur, sinon avec l'image gardée (emotes Twitch). */
fun favoriteEmotes(all: List<Emotes.Emote>, codes: List<String>): List<Emotes.Emote> {
    if (codes.isEmpty()) return emptyList()
    val byName = all.associateBy { it.name }
    return codes.mapNotNull { c -> byName[c] ?: EmoteLists.favoriteUrl(c)?.let { Emotes.Emote(c, it, source = "twitch") } }
}

/** Emote du sélecteur en jeton de message (pour sa fiche). */
fun Emotes.Emote.asToken(): ChatToken.Emote =
    ChatToken.Emote(name, url, ratio, zeroWidth, source.ifEmpty { "twitch" }, author = author, channel = channel)

/** Case du sélecteur : toucher, l'ajouter au message ; appui long, sa fiche,
 *  les favoris ou copier son nom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickerEmote(e: Emotes.Emote, onInfo: (ChatToken.Emote) -> Unit, onPick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = onPick, onLongClick = { menu = true })
            .padding(4.dp),
        contentAlignment = Alignment.Center) {
        AsyncImage(model = e.url, contentDescription = e.name, modifier = Modifier.fillMaxSize())
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t("chat_info_open")) }, onClick = {
                menu = false
                onInfo(e.asToken())
            })
            val favorite = EmoteLists.isFavorite(e.name)
            DropdownMenuItem(text = { Text((if (favorite) "★  " else "☆  ") + AppState.t(if (favorite) "emote_fav_remove" else "emote_fav_add")) },
                             onClick = {
                                 menu = false
                                 EmoteLists.toggleFavorite(e.name, e.url)
                             })
            DropdownMenuItem(text = { Text(AppState.t("chat_info_copy")) }, onClick = {
                menu = false
                clipboard.setText(AnnotatedString(e.name))
            })
        }
    }
}

/** Fiche d'une emote : l'ajouter aux favoris (ou l'en retirer), la masquer
 *  (ou la réafficher). */
@Composable
fun EmoteCardActions(emote: ChatToken.Emote) {
    val favorite = EmoteLists.isFavorite(emote.name)
    val hidden = EmoteLists.hides(emote.name)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(onClick = { EmoteLists.toggleFavorite(emote.name, emote.url) }) {
            Text((if (favorite) "★  " else "☆  ") + AppState.t(if (favorite) "emote_fav_remove" else "emote_fav_add"),
                 color = TU.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { EmoteLists.toggleHidden(emote.name) }, modifier = Modifier.padding(top = 6.dp)) {
            Text(AppState.t(if (hidden) "emote_unhide" else "emote_hide"), color = if (hidden) TU.primary else TU.danger,
                 fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (hidden) {
            Text(AppState.t("emote_hidden_note"), color = TU.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Réglages → Chat : combien d'emotes sont masquées (les dernières en clair),
 *  et la liste pour les réafficher. */
@Composable
fun HiddenEmotesRow() {
    var open by remember { mutableStateOf(false) }
    val list = EmoteLists.hidden
    Row(Modifier.fillMaxWidth().focusCard({ open = true }, RoundedCornerShape(0.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(AppState.t("hidden_emotes"), color = TU.text, fontSize = 15.sp)
            if (list.isEmpty()) {
                Text(AppState.t("hidden_emotes_empty"), color = TU.muted, fontSize = 12.sp)
            } else {
                Text(list.takeLast(6).reversed().joinToString(", "), color = TU.muted, fontSize = 12.sp, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
        }
        if (list.isNotEmpty()) Text("${list.size}", color = TU.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
        Text("›", color = TU.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) HiddenEmotesDialog { open = false }
}

/** Emotes masquées, la plus récente en haut : une à une, ou toutes. */
@Composable
private fun HiddenEmotesDialog(onClose: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val list = EmoteLists.hidden
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("hidden_emotes")) },
        text = {
            if (list.isEmpty()) {
                Text(AppState.t("hidden_emotes_empty"), color = TU.muted, fontSize = 13.sp)
            } else {
                Column {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list.reversed(), key = { it }) { code ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(code, color = TU.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                                     fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                     modifier = Modifier.weight(1f))
                                TextButton(onClick = { EmoteLists.toggleHidden(code) }) {
                                    Text("👁", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                    Text(AppState.t("hidden_emotes_sub"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        dismissButton = {
            if (list.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(AppState.t("hidden_emotes_clear"), color = TU.danger) }
            }
        },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(AppState.t("hidden_emotes_clear")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    EmoteLists.replaceHidden(emptyList())
                }) { Text(AppState.t("hidden_emotes_clear"), color = TU.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(AppState.t("cancel"), color = TU.text) } },
            containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
        )
    }
}

/** Fond de la ligne d'aperçu : réutilisé par les réglages (ChatBars.kt). */
internal fun Modifier.chatPreviewBox(): Modifier =
    fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(TU.bg)
        .padding(vertical = 4.dp)
