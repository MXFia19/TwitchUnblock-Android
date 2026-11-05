package com.mxfia19.twitchunblock.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.web.TwitchFollow
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bouton suivre au-dessus du chat (lecteur) : sur le compte Twitch avec la
 * session web (mêmes requêtes que la page de la chaîne), sinon sur
 * l'appareil. Comme FollowService (iOS). Fil principal.
 */
class ChannelFollow(private val scope: CoroutineScope) {
    /** Suivie sur Twitch ; null : pas de session web, ou statut pas encore lu
     *  (le suivi de l'appareil s'applique). */
    var onTwitch by mutableStateOf<Boolean?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Twitch a refusé le dernier changement. */
    var failed by mutableStateOf(false)
        private set

    var login by mutableStateOf("")
        private set
    private var channelId: String? = null
    private var job: Job? = null

    /** Suivie (Twitch, ou l'appareil sans session web). */
    val following: Boolean
        get() = onTwitch ?: AppState.isFollowedLocally(login)

    fun load(login: String) {
        stop()
        this.login = login.lowercase()
        if (!WebSession.active) return
        val l = this.login
        job = scope.launch {
            TwitchFollow.status(l)?.let { (on, id) ->
                if (l != this@ChannelFollow.login) return@launch
                onTwitch = on
                channelId = id
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onTwitch = null
        channelId = null
        busy = false
        failed = false
    }

    /** Suit ou ne suit plus ; ne plus suivre se confirme avant (appelant). */
    fun set(follow: Boolean) {
        val l = login
        if (l.isEmpty() || busy) return
        failed = false
        val id = channelId
        if (onTwitch == null || id == null) {
            // Sans session web : suivi de l'appareil, comme la page de la chaîne.
            if (AppState.isFollowedLocally(l) != follow) AppState.toggleLocalFollow(l)
            Logger.info("FOLLOW", if (follow) "Chaîne suivie sur l'appareil" else "Chaîne retirée des suivis de l'appareil", l)
            return
        }
        busy = true
        scope.launch {
            val refused = TwitchFollow.set(id, follow)
            busy = false
            if (l != login) return@launch
            if (refused == null) onTwitch = follow else failed = true
        }
    }
}
