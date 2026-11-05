package com.mxfia19.twitchunblock.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Master
import com.mxfia19.twitchunblock.core.StreamApi
import com.mxfia19.twitchunblock.core.StreamPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Image verticale d'un direct en « Dual Format » (StreamApi.portraitLive) :
 * cherchée une fois le direct parti, puis l'une ou l'autre image, comme un
 * changement de qualité (le lecteur et le chat restent). Ne vaut que pour la
 * lecture en cours (`PlaybackSession.openCount`) : un autre direct, un
 * rembobinage ou « Réessayer » la cherchent de nouveau. Le choix est retenu :
 * les directs suivants qui en ont une partent dessus. Comme setVertical (iOS).
 */
class VerticalFeed(private val session: PlaybackSession, private val scope: CoroutineScope) {
    private var gen = -1
    private var portrait by mutableStateOf<Master?>(null)
    private var onFlag by mutableStateOf(false)
    /** Image habituelle et sa source, gardées pour le retour. */
    private var backup: Pair<Master, String?>? = null

    /** Message bref au départ automatique sur l'image verticale (lu puis effacé par l'écran). */
    var notice by mutableStateOf<String?>(null)

    /** Le direct en cours a une image verticale. */
    val available: Boolean
        get() = portrait != null && gen == session.openCount && session.isLive &&
            session.state is PlaybackSession.State.Ready

    /** L'image verticale est à l'écran. */
    val on: Boolean get() = onFlag && gen == session.openCount && session.isLive

    /** Cherchée une fois par lecture (direct prêt). */
    fun lookup(login: String) {
        val g = session.openCount
        if (g == gen) return
        gen = g
        portrait = null
        onFlag = false
        backup = null
        scope.launch {
            val m = StreamApi.portraitLive(login.lowercase(), AppState.lowLatency) ?: return@launch
            if (g != session.openCount || !session.isLive) return@launch
            portrait = m
            if (StreamPrefs.preferVertical.value) {
                set(true)
                if (on) notice = AppState.t("vertical_on")
            }
        }
    }

    /** Bouton « image verticale » : l'une ou l'autre image, et le choix retenu. */
    fun toggle() {
        set(!on)
        StreamPrefs.preferVertical.set(on)
        Logger.info("PLAYER", "Image verticale", if (on) "oui" else "non")
    }

    private fun set(value: Boolean) {
        if (value == on || !available) return
        if (value) {
            val p = portrait ?: return
            backup = Pair(session.currentMaster, session.source)
            // Elle vient de Twitch lui-même (jeton anonyme), pauses pub comprises.
            session.swapMaster(p, "twitch")
            onFlag = true
            Logger.info("PLAYER", "Image verticale (Dual Format)", "${p.links.size} qualités, par Twitch")
        } else {
            backup?.let { (m, src) -> session.swapMaster(m, src) }
            backup = null
            onFlag = false
            Logger.info("PLAYER", "Retour à l'image habituelle")
        }
    }
}
