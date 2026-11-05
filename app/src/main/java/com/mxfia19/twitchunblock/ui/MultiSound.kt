package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.mxfia19.twitchunblock.core.AppState
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Plusieurs sons dans le multi-live, comme l'iPhone : toucher une tuile la
 * rend seule à avoir le son (comme avant) ; le haut-parleur d'une autre
 * l'ajoute au mélange ou la coupe, un appui long règle son volume (100 % à
 * 25 %). Seule la tuile principale gère le focus audio : quand elle le perd
 * (appel, autre app), les autres se taisent aussi.
 */
class MultiSound {
    /** Chaînes qui ont le son. */
    var on by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Volume de chaque chaîne (1 sans réglage). */
    var volumes by mutableStateOf<Map<String, Float>>(emptyMap())
        private set
    /** Lecteurs mis en pause par une perte du focus audio. */
    private val focusPaused = mutableSetOf<Player>()

    fun only(login: String) { on = setOf(login) }

    fun add(login: String) { on = on + login }

    fun toggle(login: String) { on = if (login in on) on - login else on + login }

    /** Volume choisi : il rend aussi le son à la tuile. */
    fun setVolume(login: String, v: Float) {
        volumes = volumes + (login to v.coerceIn(0f, 1f))
        on = on + login
    }

    fun forget(login: String, player: Player) {
        on = on - login
        volumes = volumes - login
        focusPaused.remove(player)
    }

    fun volume(login: String): Float = volumes[login] ?: 1f

    /** Volume à poser sur une tuile ; `main` : celle qui gère le focus audio,
     *  `mainPlayer` : son lecteur. */
    fun volumeFor(login: String, main: Boolean, mainPlayer: Player?): Float {
        if (login !in on) return 0f
        if (!main && mainPlayer != null && lostFocus(mainPlayer)) return 0f
        return volume(login)
    }

    private fun lostFocus(p: Player): Boolean =
        p in focusPaused || p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

    /** Suit le focus audio d'un lecteur : `onChange` reposera les volumes. */
    fun watch(player: Player, onChange: () -> Unit) {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) focusPaused += player
                else if (playWhenReady) focusPaused -= player
                onChange()
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = onChange()
        })
    }
}

/** Son d'une tuile du multi-live (téléphone ; pas sur la TV). */
class TileSound(val on: Boolean, val volume: Float, val onToggle: () -> Unit, val onVolume: (Float) -> Unit)

/** Haut-parleur d'une tuile : toucher l'ajoute au mélange ou la coupe, appui
 *  long pour son volume. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileSoundButton(sound: TileSound, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val icon = when {
        !sound.on -> "🔇"
        sound.volume >= 0.99f -> "🔊"
        sound.volume >= 0.5f -> "🔉"
        else -> "🔈"
    }
    Box(modifier) {
        Text(icon, fontSize = 15.sp,
             modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000))
                 .combinedClickable(onClickLabel = AppState.t(if (sound.on) "multi_sound_off" else "multi_sound_on"),
                                    onLongClick = { menu = true }, onClick = sound.onToggle)
                 .padding(horizontal = 8.dp, vertical = 4.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(AppState.t("multi_volume"), color = TU.muted, fontSize = 13.sp) },
                             onClick = {}, enabled = false)
            val percent = NumberFormat.getPercentInstance(Locale.forLanguageTag(AppState.lang.code))
            for (level in listOf(1f, 0.75f, 0.5f, 0.25f)) {
                val checked = sound.on && abs(sound.volume - level) < 0.01f
                DropdownMenuItem(text = { Text(percent.format(level.toDouble()) + if (checked) "  ✓" else "") }, onClick = {
                    menu = false
                    sound.onVolume(level)
                })
            }
        }
    }
}
