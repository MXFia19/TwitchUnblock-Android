package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

// Minuterie (série 6) : durées proposées ou au choix (1 min à 12 h), temps
// restant, +15 min, et la même pour le multi-live. Comme SleepTimerSheet (iOS).

/** Ce que la minuterie met en pause : la lecture principale ou le multi-live. */
interface SleepTarget {
    /** Heure de la pause (ms), null sans minuterie. */
    val endsAt: Long?
    fun start(minutes: Int)
    fun cancel()
}

/** Minuterie de la lecture principale (PlaybackSession.setSleepTimer). */
class SessionSleep(private val session: PlaybackSession) : SleepTarget {
    override val endsAt: Long? get() = session.sleepAt
    override fun start(minutes: Int) = session.setSleepTimer(minutes)
    override fun cancel() = session.setSleepTimer(null)
}

/** Minuterie du multi-live : ses tuiles en pause à l'heure dite. */
object MultiSleep : SleepTarget {
    private val scope = MainScope()
    private var job: Job? = null
    private var model: WeakReference<MultiLiveModel>? = null
    private var at by mutableStateOf<Long?>(null)
    override val endsAt: Long? get() = at

    /** Multi-live affiché : une minuterie d'un multi-live fermé ne vaut plus. */
    fun attach(m: MultiLiveModel) {
        if (model?.get() === m) return
        cancel()
        model = WeakReference(m)
    }

    override fun start(minutes: Int) {
        job?.cancel()
        val target = model ?: return
        at = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            delay(minutes * 60_000L)
            target.get()?.pauseAll()
            at = null
            Logger.info("PLAYER", "Minuterie : multi-live mis en pause", "$minutes min")
        }
    }

    override fun cancel() {
        job?.cancel()
        job = null
        at = null
    }
}

/** Fenêtre de la durée au choix (SleepDialogHost) : sa minuterie, null fermée. */
object SleepUi {
    var dialog by mutableStateOf<SleepTarget?>(null)
}

/** Temps restant (s), relu deux fois par seconde ; null sans minuterie. */
@Composable
fun rememberSleepLeft(endsAt: Long?): Long? {
    if (endsAt == null) return null
    fun remaining() = maxOf(0L, (endsAt - System.currentTimeMillis() + 999) / 1000)
    val shown by produceState(remaining(), endsAt) {
        while (true) {
            value = remaining()
            delay(500)
        }
    }
    return shown
}

/**
 * Page « Minuterie » d'un menu (⋮ du lecteur, multi-live) : sans minuterie,
 * les durées proposées et « Autre durée… » ; avec, le temps restant, +15 min,
 * Modifier et Annuler.
 */
@Composable
fun SleepMenuItems(target: SleepTarget, onClose: () -> Unit) {
    val left = rememberSleepLeft(target.endsAt)
    if (left != null) {
        DropdownMenuItem(text = { Text(AppState.t("sleep_remaining") + " · " + Format.clock(left), color = TU.muted) },
                         onClick = {}, enabled = false)
        DropdownMenuItem(text = { Text(AppState.t("sleep_add")) }, onClick = {
            onClose()
            target.start(((left + 59) / 60).toInt() + 15)
        })
        DropdownMenuItem(text = { Text(AppState.t("sleep_edit")) }, onClick = {
            onClose()
            SleepUi.dialog = target
        })
        DropdownMenuItem(text = { Text(AppState.t("sleep_cancel"), color = TU.danger) }, onClick = {
            onClose()
            target.cancel()
        })
        return
    }
    for (m in sleepChoices) {
        DropdownMenuItem(text = { Text(sleepLabel(m)) }, onClick = {
            onClose()
            target.start(m)
        })
    }
    DropdownMenuItem(text = { Text(AppState.t("p6_sleep_other")) }, onClick = {
        onClose()
        SleepUi.dialog = target
    })
}

/** Durée au choix, en minutes (1 à 720) ; avec une minuterie en cours, son temps restant. */
@Composable
fun SleepDialogHost() {
    val target = SleepUi.dialog ?: return
    if (PipState.active) return
    val left = rememberSleepLeft(target.endsAt)
    var text by remember(target) { mutableStateOf(left?.let { ((it + 59) / 60).toString() } ?: "") }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..720 }
    val active = left != null
    AlertDialog(
        onDismissRequest = { SleepUi.dialog = null },
        title = { Text(AppState.t("sleep_timer")) },
        text = {
            Column {
                Text(if (left != null) AppState.t("sleep_remaining") + " · " + Format.clock(left) else AppState.t("sleep_timer_sub"),
                     color = TU.muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                    placeholder = { Text(AppState.t("sleep_custom_ph")) },
                    suffix = { Text(AppState.t("sleep_minutes")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = minutes != null, onClick = {
                minutes?.let { target.start(it) }
                SleepUi.dialog = null
            }) { Text(AppState.t(if (active) "sleep_set" else "sleep_start"), color = if (minutes != null) TU.primary else TU.muted) }
        },
        dismissButton = {
            TextButton(onClick = { SleepUi.dialog = null }) { Text(AppState.t("close"), color = TU.muted) }
        },
        containerColor = TU.surface,
        titleContentColor = TU.text,
        textContentColor = TU.text,
    )
}

/** En-tête du multi-live : la lune (et le temps restant) ouvre la minuterie. */
@Composable
fun MultiSleepButton(model: MultiLiveModel) {
    LaunchedEffect(model) { MultiSleep.attach(model) }
    var open by remember { mutableStateOf(false) }
    val left = rememberSleepLeft(MultiSleep.endsAt)
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = AppState.t("sleep_timer") },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(PlayerIcons.bedtime, contentDescription = null, tint = if (left != null) TU.primary else TU.text,
                 modifier = Modifier.size(20.dp))
            if (left != null) Text(" " + Format.clock(left), color = TU.primary, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SleepMenuItems(MultiSleep) { open = false }
        }
    }
}
