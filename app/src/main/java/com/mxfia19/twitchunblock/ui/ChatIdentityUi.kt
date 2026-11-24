package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.player.PlayerHolder
import com.mxfia19.twitchunblock.web.ChannelBadges
import com.mxfia19.twitchunblock.web.ChatBadgeChoice
import com.mxfia19.twitchunblock.web.ChatIdentity
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.launch

/** Réglages → Chat : le badge montré à côté de son nom (session web), comme
 *  l'iPhone. Sans session : la rangée le dit, sans rien ouvrir. */
@Composable
fun ChatIdentityRow() {
    var open by remember { mutableStateOf(false) }
    val ready = WebSession.active
    Row(Modifier.fillMaxWidth().focusCard({ if (ready) open = true }, RoundedCornerShape(0.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(AppState.t("chat_identity"), color = TU.text, fontSize = 15.sp)
            Text(AppState.t(if (ready) "chat_identity_sub" else "chat_identity_web"), color = TU.muted, fontSize = 12.sp)
        }
        if (ready) Text("›", color = TU.muted, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) ChatIdentityDialog { open = false }
}

/** Badges du compte : le global, puis celui de la chaîne en lecture (direct
 *  ou VOD, lecteur réduit compris). Celui qui est choisi est coché ; en
 *  toucher un le choisit (sur Twitch aussi). */
@Composable
private fun ChatIdentityDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var badges by remember { mutableStateOf<List<ChatBadgeChoice>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    /** Choix en cours : « global:setID/version » ou « channel:setID/version ». */
    var saving by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    // Chaîne lue à l'ouverture (aucune : une phrase).
    val login = remember { PlayerHolder.current?.channelLogin?.lowercase()?.ifEmpty { null } }
    val name = remember { PlayerHolder.current?.channelName?.ifEmpty { null } ?: login }
    var channel by remember { mutableStateOf<ChannelBadges?>(null) }
    var channelSelected by remember { mutableStateOf<String?>(null) }
    var channelLoading by remember { mutableStateOf(login != null) }
    var channelFailed by remember { mutableStateOf(false) }
    var channelRefused by remember { mutableStateOf(false) }

    suspend fun loadGlobal() {
        loading = true
        failed = false
        val result = ChatIdentity.globalBadges()
        loading = false
        if (result == null) failed = true else { badges = result.first; selected = result.second }
    }
    suspend fun loadChannel() {
        val l = login ?: return
        channelLoading = true
        channelFailed = false
        val result = ChatIdentity.channelBadges(l)
        channelLoading = false
        if (result == null) channelFailed = true else { channel = result; channelSelected = result.selected }
    }
    fun choose(badge: ChatBadgeChoice) {
        if (badge.id == selected || saving != null) return
        scope.launch {
            saving = "global:" + badge.id
            refused = false
            val ok = ChatIdentity.selectGlobal(badge)
            saving = null
            if (ok) selected = badge.id else refused = true
        }
    }
    fun chooseChannel(badge: ChatBadgeChoice) {
        val l = login ?: return
        val c = channel ?: return
        if (badge.id == channelSelected || saving != null) return
        scope.launch {
            saving = "channel:" + badge.id
            channelRefused = false
            val ok = ChatIdentity.selectChannel(badge, c.channelId, l)
            saving = null
            if (ok) channelSelected = badge.id else channelRefused = true
        }
    }
    LaunchedEffect(Unit) { loadGlobal() }
    LaunchedEffect(Unit) { loadChannel() }

    /** Liste d'une section : chargement, échec (réessayer), vide, ou badges. */
    fun LazyListScope.section(scopeKey: String, list: List<ChatBadgeChoice>, chosen: String?, isLoading: Boolean,
                              isFailed: Boolean, emptyKey: String, retry: suspend () -> Unit,
                              pick: (ChatBadgeChoice) -> Unit) {
        when {
            isLoading -> item(key = "$scopeKey-loading") {
                CircularProgressIndicator(color = TU.primary, modifier = Modifier.padding(vertical = 8.dp).size(24.dp))
            }
            isFailed -> item(key = "$scopeKey-failed") {
                Column {
                    Text(AppState.t("chat_identity_load_failed"), color = TU.muted, fontSize = 13.sp)
                    TextButton(onClick = { scope.launch { retry() } }) { Text(AppState.t("retry"), color = TU.primary) }
                }
            }
            list.isEmpty() -> item(key = "$scopeKey-empty") {
                Text(AppState.t(emptyKey), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
            else -> items(list, key = { "$scopeKey:${it.id}" }) { badge ->
                Row(Modifier.fillMaxWidth().clickable(enabled = saving == null) { pick(badge) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = badge.imageUrl, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(badge.title, color = TU.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    when {
                        saving == "$scopeKey:${badge.id}" -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(18.dp))
                        chosen == badge.id -> Text("✓", color = TU.primary, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("chat_identity")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item(key = "global-title") { SectionTitle(AppState.t("chat_identity_global")) }
                section("global", badges, selected, loading, failed, "chat_identity_empty", { loadGlobal() }) { choose(it) }
                item(key = "global-footer") {
                    Text(AppState.t(if (refused) "chat_identity_failed" else "chat_identity_footer"),
                         color = if (refused) TU.danger else TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                item(key = "channel-title") {
                    SectionTitle(if (name != null) AppState.t("chat_identity_channel").replace("{c}", name)
                                 else AppState.t("chat_identity_channel_any"))
                }
                if (login == null) {
                    item(key = "channel-hint") {
                        Text(AppState.t("chat_identity_channel_hint"), color = TU.muted, fontSize = 13.sp)
                    }
                } else {
                    section("channel", channel?.available.orEmpty(), channelSelected, channelLoading, channelFailed,
                            "chat_identity_channel_empty", { loadChannel() }) { chooseChannel(it) }
                    item(key = "channel-footer") {
                        Text(AppState.t(if (channelRefused) "chat_identity_failed" else "chat_identity_channel_footer"),
                             color = if (channelRefused) TU.danger else TU.muted, fontSize = 12.sp,
                             modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
