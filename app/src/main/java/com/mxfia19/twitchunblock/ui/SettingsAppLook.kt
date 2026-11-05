package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.Accounts
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.NoAccount
import com.mxfia19.twitchunblock.core.SettingsSync
import kotlinx.coroutines.launch

/** Réglages de l'app : thème, icône, réglages synchronisés
 *  (groupe Général). Les comptes gardés sont dans le groupe Compte
 *  (SavedAccountsRows). */
@Composable
fun AppLookSettings(nav: Navigator) {
    AppearanceRows()
    AppIconRow()
    Toggle(AppState.t("cfg_sync_settings"), AppState.t("ap_sync_settings_sub"), SettingsSync.enabled.value) {
        SettingsSync.toggled(it)
    }
    NoAccountRow()
    // « Reprendre les VODs » (VodResume), comme le site.
    Toggle(AppState.t("a6_vod_progress"), AppState.t("a6_vod_progress_sub"), com.mxfia19.twitchunblock.core.VodResume.enabled) {
        com.mxfia19.twitchunblock.core.VodResume.set(it)
    }
}

/** « Sans compte Twitch » : grisé tant qu'on est connecté (il ne
 *  masquerait rien), avec la raison. */
@Composable
private fun NoAccountRow() {
    val signedIn = NoAccount.signedIn
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(AppState.t("cust_no_account"), color = if (signedIn) TU.muted else TU.text, fontSize = 15.sp)
            Text(AppState.t(if (signedIn) "cust_no_account_signed_in" else "cust_no_account_foot"), color = TU.muted, fontSize = 12.sp)
        }
        Switch(checked = NoAccount.pref.value, onCheckedChange = { NoAccount.pref.set(it) }, enabled = !signedIn,
               colors = SwitchDefaults.colors(checkedTrackColor = TU.primary))
    }
}

/** Couleur d'accent et fond : 7 accents et 3 fonds, comme l'iPhone. */
@Composable
private fun AppearanceRows() {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(AppState.t("accent_color"), color = TU.text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            for (a in AccentTheme.entries) {
                val on = AppTheme.accent == a
                val label = AppState.t(a.labelKey)
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(a.primary)
                        .border(BorderStroke(if (on) 2.5.dp else 0.dp, if (on) Color.White else Color.Transparent), CircleShape)
                        .focusCard({ AppTheme.updateAccent(a) }, RoundedCornerShape(15.dp))
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Text(AppState.t("background_theme"), color = TU.text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in BackgroundTheme.entries) {
                TabChip(AppState.t(b.labelKey), AppTheme.background == b) { AppTheme.updateBackground(b) }
            }
        }
    }
}

/** Icône de l'app au choix (les six de l'iPhone). */
@Composable
private fun AppIconRow() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(AppState.t("app_icon"), color = TU.text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            for (icon in AppIcon.entries) {
                val on = current == icon
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                       modifier = Modifier.focusCard({
                           if (icon == current) return@focusCard
                           error = AppIcons.apply(context, icon)
                           if (error == null) current = icon
                       })) {
                    Image(painterResource(icon.preview), contentDescription = AppState.t(icon.labelKey),
                          modifier = Modifier.size(56.dp).clip(RoundedCornerShape(13.dp))
                              .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) TU.primary else TU.line), RoundedCornerShape(13.dp)))
                    Text(AppState.t(icon.labelKey), color = if (on) TU.text else TU.muted, fontSize = 11.sp,
                         modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        error?.let { Text(AppState.t("ap_icon_failed").replace("{e}", it), color = TU.danger, fontSize = 12.sp) }
        Text(AppState.t("ap_icon_sub"), color = TU.muted, fontSize = 12.sp)
    }
}

/**
 * Groupe Compte : « Changer de compte » (connexion par code, le compte en
 * place reste tant que l'autre n'est pas connecté) et les comptes gardés de
 * côté, pour en changer d'un geste (Accounts).
 */
@Composable
fun SavedAccountsRows(nav: Navigator) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var askForget by remember { mutableStateOf<Accounts.Saved?>(null) }
    if (AppState.user != null && AppState.token != null) {
        Column(Modifier.fillMaxWidth().focusCard({ nav.push(Screen.Login) }, RoundedCornerShape(0.dp)).padding(12.dp)) {
            Text(AppState.t("switch_account"), color = TU.primary, fontSize = 15.sp)
            Text(AppState.t("a6_switch_account_sub"), color = TU.muted, fontSize = 12.sp)
        }
    }
    val others = Accounts.others
    if (others.isNotEmpty()) {
        Text(AppState.t("ap_accounts_other"), color = TU.muted, fontSize = 12.sp,
             modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
        for (a in others) {
            val id = a.user.id
            val login = a.user.login
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(a.user.avatar, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.user.displayName.ifEmpty { login }, color = TU.text, fontSize = 15.sp)
                    Text("@$login", color = TU.muted, fontSize = 12.sp)
                }
                if (busy == id) {
                    CircularProgressIndicator(color = TU.primary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(enabled = busy == null, onClick = {
                        busy = id
                        message = null
                        scope.launch {
                            val result = Accounts.switchTo(id)
                            busy = null
                            message = when (result) {
                                Accounts.SwitchResult.DONE -> null
                                Accounts.SwitchResult.EXPIRED -> AppState.t("ap_account_expired").replace("{u}", login)
                                Accounts.SwitchResult.UNREACHABLE -> AppState.t("err_network")
                            }
                        }
                    }) { Text(AppState.t("ap_account_use"), color = TU.primary) }
                    TextButton(enabled = busy == null, onClick = { askForget = a }) { Text(AppState.t("remove"), color = TU.danger) }
                }
            }
        }
    }
    message?.let {
        Text(it, color = TU.danger, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
    }
    askForget?.let { a ->
        AlertDialog(
            onDismissRequest = { askForget = null },
            title = { Text(AppState.t("remove")) },
            text = { Text(AppState.t("ap_account_forget_q").replace("{u}", a.user.login)) },
            confirmButton = {
                TextButton(onClick = { askForget = null; Accounts.forget(a.user.id) }) { Text(AppState.t("remove"), color = TU.danger) }
            },
            dismissButton = { TextButton(onClick = { askForget = null }) { Text(AppState.t("cancel"), color = TU.text) } },
            containerColor = TU.surface,
            titleContentColor = TU.text,
            textContentColor = TU.muted,
        )
    }
}
