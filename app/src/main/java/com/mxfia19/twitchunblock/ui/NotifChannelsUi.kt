package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.ChannelBrief
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.notify.NotifChannels

/**
 * Cloche d'une chaîne (sa page, la saisie du chat) : coupe ou rallume ses
 * notifications de live, même réglage que « Chaînes notifiées ». Affichée
 * seulement si les notifications de live (Android ou ntfy) sont activées et
 * la chaîne suivie : `followed` s'il est connu, sinon sur l'appareil ou par
 * le compte. Comme NotifBellButton (iOS).
 */
@Composable
fun NotifBell(login: String, modifier: Modifier = Modifier, followed: Boolean? = null) {
    val context = LocalContext.current
    val active = NotifChannels.active
    // Suivis du compte (relus au plus une fois par heure), seulement quand une
    // cloche peut s'afficher.
    LaunchedEffect(active, AppState.user?.id) { if (active) NotifChannels.loadAccountFollows() }
    val key = login.lowercase()
    if (!active || key.isEmpty() || !(followed ?: NotifChannels.followed(key))) return
    val on = key !in NotifChannels.muted
    IconButton(onClick = {
        NotifChannels.setNotify(context, key, !on)
        Logger.info("NOTIF", if (on) "Cloche : notifications coupées" else "Cloche : notifications activées", "@$key")
    }, modifier = modifier) {
        Icon(if (on) Icons.Filled.Notifications else Icons.Outlined.Notifications,
             contentDescription = AppState.t(if (on) "notif_bell_on" else "notif_bell_off"),
             tint = if (on) TU.primary else TU.muted)
    }
}

/** Réglages → Chaînes notifiées : un interrupteur par chaîne suivie (compte
 *  et appareil), allumé par défaut. Comme NotifChannelsView (iOS). */
@Composable
fun NotifChannelsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var channels by remember { mutableStateOf<List<ChannelBrief>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val logins = ArrayList<String>(AppState.localFollows)
        if (AppState.user != null) {
            // Liste du compte illisible : rien plutôt qu'une liste amputée.
            val account = BrowseApi.followedLogins()
            if (account == null) {
                failed = true
                channels = emptyList()
                return@LaunchedEffect
            }
            NotifChannels.rememberAccount(account)
            logins += account
        }
        val unique = logins.map { it.lowercase() }.distinct()
        // Noms et avatars ; sans réponse de Twitch, les pseudos seuls.
        val infos = BrowseApi.channelsByLogins(unique) ?: unique.map { ChannelBrief(it, it, "", null) }
        channels = infos.map { it.copy(login = it.login.lowercase()) }.distinctBy { it.login }.sortedBy { it.name.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(AppState.t("notif_channels")) },
        text = {
            Column {
                Text(AppState.t("notif_channels_sub_android"), color = TU.muted, fontSize = 12.sp)
                val list = channels
                when {
                    list == null -> CircularProgressIndicator(color = TU.primary,
                                                              modifier = Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally))
                    list.isEmpty() -> Text(AppState.t(if (failed) "notif_channels_error" else "notif_channels_none"),
                                           color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    else -> {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            TextButton(onClick = { NotifChannels.setNotifyAll(context, list.map { it.login }, true) }) {
                                Text(AppState.t("notif_channels_all_on"), color = TU.primary)
                            }
                            TextButton(onClick = { NotifChannels.setNotifyAll(context, list.map { it.login }, false) }) {
                                Text(AppState.t("notif_channels_all_off"), color = TU.primary)
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                            items(list, key = { it.login }) { c ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(c.avatar, 30.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.name, color = TU.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                         modifier = Modifier.weight(1f))
                                    Switch(checked = c.login !in NotifChannels.muted,
                                           onCheckedChange = { NotifChannels.setNotify(context, c.login, it) },
                                           colors = SwitchDefaults.colors(checkedTrackColor = TU.primary))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(AppState.t("close"), color = TU.primary) } },
        containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
    )
}
