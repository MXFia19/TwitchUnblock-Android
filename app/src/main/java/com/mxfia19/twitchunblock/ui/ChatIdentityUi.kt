package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
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

/** Badges globaux du compte : celui qui est choisi est coché ; en toucher un
 *  le choisit (sur Twitch aussi). */
@Composable
private fun ChatIdentityDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var badges by remember { mutableStateOf<List<ChatBadgeChoice>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        failed = false
        val result = ChatIdentity.globalBadges()
        loading = false
        if (result == null) failed = true else { badges = result.first; selected = result.second }
    }
    fun choose(badge: ChatBadgeChoice) {
        if (badge.id == selected || saving != null) return
        scope.launch {
            saving = badge.id
            refused = false
            val ok = ChatIdentity.selectGlobal(badge)
            saving = null
            if (ok) selected = badge.id else refused = true
        }
    }
    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("chat_identity")) },
        text = {
            Column {
                when {
                    loading -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(24.dp))
                    failed -> {
                        Text(AppState.t("chat_identity_load_failed"), color = TU.muted, fontSize = 13.sp)
                        TextButton(onClick = { scope.launch { load() } }) { Text(AppState.t("retry"), color = TU.primary) }
                    }
                    badges.isEmpty() -> Text(AppState.t("chat_identity_empty"), color = TU.muted, fontSize = 13.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(badges, key = { it.id }) { badge ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = saving == null) { choose(badge) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = badge.imageUrl, contentDescription = null, modifier = Modifier.size(22.dp))
                                Text(badge.title, color = TU.text, fontSize = 15.sp,
                                     modifier = Modifier.weight(1f).padding(start = 12.dp))
                                when {
                                    saving == badge.id -> CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(18.dp))
                                    selected == badge.id -> Text("✓", color = TU.primary, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
                Text(AppState.t(if (refused) "chat_identity_failed" else "chat_identity_footer"),
                     color = if (refused) TU.danger else TU.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}
