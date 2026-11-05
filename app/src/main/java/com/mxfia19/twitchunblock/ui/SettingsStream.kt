package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.HigherQualities
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.StreamPrefs

/** Réglages du lecteur : sources et flux du direct (groupe Lecteur). */
@Composable
fun StreamSettings() {
    CustomSourceField()
    Toggle(AppState.t("higher_qualities"), AppState.t("st_hq_sub").replace("{c}", HigherQualities.label),
           StreamPrefs.higherQualities.value) {
        StreamPrefs.higherQualities.set(it)
        Logger.info("SETTINGS", "Qualités supérieures", if (it) "oui" else "non")
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
