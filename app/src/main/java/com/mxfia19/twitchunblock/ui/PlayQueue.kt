package com.mxfia19.twitchunblock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.Player
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.Vod
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession

/**
 * « Tout lire » (playlist d'une chaîne) : à la fin d'une VOD, la suivante de
 * la file démarre. Une lecture qui n'est pas dans la file la fait oublier.
 * Comme vodQueue et playNextInQueue (MainTabView.swift).
 */
object PlayQueue {
    private var items: List<Vod> = emptyList()
    private var channel: String? = null

    /** Lit la première vidéo de `list` et garde les suivantes. */
    fun start(nav: Navigator, list: List<Vod>, channel: String?) {
        val first = list.firstOrNull() ?: return
        items = list
        this.channel = channel
        Logger.info("PLAYER", "Tout lire", "${list.size} vidéos")
        nav.play(PlayRequest.Vod(first.id, first.title, channel))
    }

    /** Lecture qui suit `request` dans la file ; null en fin de file ou hors
     *  de la file (elle est alors oubliée). */
    fun next(request: PlayRequest?): PlayRequest.Vod? {
        val id = (request as? PlayRequest.Vod)?.takeIf { !it.rewind }?.id
        val i = if (id == null) -1 else items.indexOfFirst { it.id == id }
        if (i < 0 || i + 1 >= items.size) {
            items = emptyList()
            return null
        }
        val n = items[i + 1]
        return PlayRequest.Vod(n.id, n.title, channel)
    }
}

/** Fin d'une VOD de la file dans le lecteur principal : la suivante démarre
 *  (même lecteur, nouvelle lecture dans la pile). À poser là où vit la
 *  session principale (PhoneApp). */
@Composable
fun PlayQueueEffect(session: PlaybackSession?, nav: Navigator) {
    DisposableEffect(session) {
        val s = session
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || s == null) return
                val next = PlayQueue.next(s.request) ?: return
                // Tout de suite, même app en arrière-plan (la composition y est en
                // pause) ; la pile suit, et PhoneApp ne relit pas ce qui est ouvert.
                s.open(next)
                nav.play(next)
            }
        }
        s?.player?.addListener(listener)
        onDispose { s?.player?.removeListener(listener) }
    }
}
