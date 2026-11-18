package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.HigherQualities
import com.mxfia19.twitchunblock.core.LiveSources
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.MyProxy
import com.mxfia19.twitchunblock.core.StreamPrefs
import com.mxfia19.twitchunblock.player.VodUnmute

/** Réglages du lecteur : sources et flux du direct (groupe Lecteur). */
@Composable
fun StreamSettings() {
    // Série 6 (partie Média) : ordre complet des sources, comme l'iPhone.
    LiveSourceOrder()
    CustomSourceField()
    // Série 7 : un proxy HTTP(S) à soi, à côté de Ma source.
    MyProxyField()
    Toggle(AppState.t("higher_qualities"), AppState.t("st_hq_sub").replace("{c}", HigherQualities.label),
           StreamPrefs.higherQualities.value) {
        StreamPrefs.higherQualities.set(it)
        Logger.info("SETTINGS", "Qualités supérieures", if (it) "oui" else "non")
    }
    // Son d'origine des passages coupés (VodUnmute), lecture et téléchargements.
    Toggle(AppState.t("restore_muted"), AppState.t("m6_restore_muted_sub"), VodUnmute.enabled.value) {
        VodUnmute.enabled.set(it)
        Logger.info("SETTINGS", "Son d'origine des passages coupés", if (it) "oui" else "non")
    }
    // Chat de la vidéo qui a le son, à côté de la mosaïque en paysage (multi-live).
    Toggle(AppState.t("multi_chat"), AppState.t("m6_multi_chat_sub"), MultiPrefs.chat.value) {
        MultiPrefs.chat.set(it)
    }
}

/** Ordre des sources du direct : essayées l'une après l'autre ; les flèches
 *  les rangent, « Ordre par défaut » remet l'automatique (Ma source en tête). */
@Composable
private fun LiveSourceOrder() {
    val order = LiveSources.order()
    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("live_source_order"), color = TU.text, fontSize = 15.sp)
        Text(AppState.t("live_source_order_sub"), color = TU.muted, fontSize = 12.sp)
        order.forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(TU.surfaceHi)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = TU.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(sourceName(id), color = TU.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = { LiveSources.move(id, -1) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = AppState.t("src_up"), tint = if (i > 0) TU.text else TU.line)
                }
                IconButton(onClick = { LiveSources.move(id, 1) }, enabled = i < order.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = AppState.t("src_down"),
                         tint = if (i < order.lastIndex) TU.text else TU.line)
                }
            }
        }
        if (order != LiveSources.defaultOrder()) {
            TextButton(onClick = { LiveSources.reset() }) { Text(AppState.t("src_reset"), color = TU.primary) }
        }
    }
}

/** Ma source : un serveur luminous-ttv à soi, essayé avant les autres
 *  (comme l'app iPhone et le site). Adresse refusée : on le dit, l'ancienne reste. */
@Composable
private fun CustomSourceField() {
    var text by remember { mutableStateOf(StreamPrefs.customSource.value) }
    var refused by remember { mutableStateOf(false) }
    // Adresse changée ailleurs (import, synchro des réglages) : le champ la
    // reprend, sinon « Enregistrer » remettait l'ancienne.
    LaunchedEffect(StreamPrefs.customSource.value) {
        text = StreamPrefs.customSource.value
        refused = false
    }
    val apply = {
        refused = !StreamPrefs.setCustomSource(text)
        if (!refused) {
            text = StreamPrefs.customSource.value
            Logger.info("SETTINGS", "Ma source", text.ifEmpty { "aucune" })
        }
    }
    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("src_custom"), color = TU.text, fontSize = 15.sp)
        Text(AppState.t("st_custom_sub"), color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                refused = false
            },
            singleLine = true,
            placeholder = { Text("https://…", color = TU.muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                      focusedTextColor = TU.text, unfocusedTextColor = TU.text),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (refused) {
            Text(AppState.t("src_custom_bad"), color = TU.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (text.trim() != StreamPrefs.customSource.value) {
                TextButton(onClick = apply) { Text(AppState.t("save"), color = TU.primary) }
            }
            if (StreamPrefs.customSource.value.isNotEmpty()) {
                TextButton(onClick = {
                    StreamPrefs.setCustomSource("")
                    text = ""
                    refused = false
                    Logger.info("SETTINGS", "Ma source", "aucune")
                }) { Text(AppState.t("remove"), color = TU.danger) }
            }
        }
    }
}

/** Mon proxy (série 7) : un proxy HTTP(S) à soi, hôte:port, identifiant et
 *  mot de passe facultatifs. Quand cette source sert, le jeton, la liste
 *  maîtresse et les playlists de la vidéo passent par lui (MyProxy.kt).
 *  Adresse refusée : on le dit, l'ancienne reste. */
@Composable
private fun MyProxyField() {
    var address by remember { mutableStateOf(MyProxy.address.value) }
    var user by remember { mutableStateOf(MyProxy.user.value) }
    var password by remember { mutableStateOf(MyProxy.password()) }
    var refused by remember { mutableStateOf(false) }
    val saved = MyProxy.address.value
    val changed = address.trim() != saved || user.trim() != MyProxy.user.value || password != MyProxy.password()
    val apply = {
        refused = !MyProxy.set(address, user, password)
        if (!refused) {
            address = MyProxy.address.value
            user = MyProxy.user.value
            password = MyProxy.password()
            // L'adresse du proxy n'est jamais écrite au journal (il part avec les signalements).
            Logger.info("SETTINGS", "Mon proxy", if (MyProxy.configured) "réglé" else "aucun")
        }
    }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line,
                                                  focusedTextColor = TU.text, unfocusedTextColor = TU.text,
                                                  focusedLabelColor = TU.primary, unfocusedLabelColor = TU.muted)
    Column(Modifier.padding(12.dp)) {
        Text(AppState.t("src_myproxy"), color = TU.text, fontSize = 15.sp)
        Text(AppState.t("s7_myproxy_sub"), color = TU.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                refused = false
            },
            singleLine = true,
            placeholder = { Text(AppState.t("s7_myproxy_ph"), color = TU.muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        // Identifiants : seulement avec une adresse (saisie ou enregistrée).
        if (address.isNotBlank() || saved.isNotEmpty()) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                singleLine = true,
                label = { Text(AppState.t("s7_myproxy_user")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = user.isNotBlank(),
                label = { Text(AppState.t("s7_myproxy_pass")) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (refused) {
            Text(AppState.t("s7_myproxy_bad"), color = TU.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (changed) {
                TextButton(onClick = apply) { Text(AppState.t("save"), color = TU.primary) }
            }
            if (saved.isNotEmpty()) {
                TextButton(onClick = {
                    MyProxy.set("", "", "")
                    address = ""
                    user = ""
                    password = ""
                    refused = false
                    Logger.info("SETTINGS", "Mon proxy", "aucun")
                }) { Text(AppState.t("remove"), color = TU.danger) }
            }
        }
    }
}
