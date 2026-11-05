package com.mxfia19.twitchunblock.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.LiveApi
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.events.RaidInfo
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay

/**
 * Fin d'un direct (série 6), comme liveEnded() du site : un direct arrêté
 * finissait en « Erreur de lecture » après trois relances. Le lecteur est
 * arrêté (plus de relecture de la playlist morte) et l'écran le dit, avec le
 * raid à suivre et « Voir les VODs ».
 */
object LiveEnd {
    /** Lecture finie : identité de la session et numéro de sa lecture (openCount). */
    private var ended by mutableStateOf<Pair<Int, Int>?>(null)

    fun isEnded(session: PlaybackSession): Boolean =
        ended == Pair(System.identityHashCode(session), session.openCount)

    fun mark(session: PlaybackSession) {
        ended = Pair(System.identityHashCode(session), session.openCount)
    }
}

/** Erreurs qui ne disent rien de la fin du direct (déjà hors ligne, introuvable, pays). */
private val NOT_AN_END = setOf("offline_msg", "not_found", "err_geo_blocked")

/**
 * Direct de la lecture principale (en grand ou réduite) : en erreur, fini
 * (fin de playlist) ou bloqué en chargement 12 s, on demande à Twitch s'il
 * est toujours en ligne (au plus toutes les 20 s). Plus de diffusion : fin du
 * direct. Toujours en ligne mais relancé par la chaîne (autre heure de début)
 * après une erreur : la lecture repart une fois sur la nouvelle diffusion.
 */
@Composable
fun LiveEndWatcher(session: PlaybackSession) {
    val live = session.isLive
    LaunchedEffect(session, session.openCount, live) {
        if (!live) return@LaunchedEffect
        val player = session.player
        var stuckSince = 0L
        var checkedAt = 0L
        while (true) {
            delay(2_000)
            val s = session.state
            val error = s is PlaybackSession.State.Error && s.key !in NOT_AN_END
            val ready = s is PlaybackSession.State.Ready
            val finished = ready && player.playbackState == Player.STATE_ENDED
            val buffering = ready && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
            val now = SystemClock.elapsedRealtime()
            if (!buffering) stuckSince = 0L else if (stuckSince == 0L) stuckSince = now
            val stalled = buffering && now - stuckSince >= 12_000
            if (!error && !finished && !stalled) continue
            if (checkedAt != 0L && now - checkedAt < 20_000) continue
            checkedAt = now
            val login = session.channelLogin ?: (session.request as? PlayRequest.Live)?.login ?: continue
            // Réseau absent : on ne sait pas, on redemandera.
            val stats = LiveApi.streamStats(login) ?: continue
            if (stats.second == null) {
                Logger.info("PLAYER", "Direct terminé", login)
                LiveEnd.mark(session)
                session.player.stop()
                com.mxfia19.twitchunblock.drops.DropsHeartbeat.stop()
                return@LaunchedEffect
            }
            if (error && stats.second != session.startedAt) {
                Logger.info("PLAYER", "Direct relancé par la chaîne : lecture reprise", login)
                session.retry()
                return@LaunchedEffect
            }
        }
    }
}

/** Écran de fin par-dessus l'image du lecteur en grand ; `raid` : raid
 *  annoncé pendant le direct (RaidWatcher), gardé pour cet écran. */
@Composable
fun BoxScope.LiveEndOverlay(session: PlaybackSession, nav: Navigator, raid: RaidInfo?) {
    var target by remember(session.openCount) { mutableStateOf<RaidInfo?>(null) }
    LaunchedEffect(raid) { if (raid != null) target = raid }
    if (!LiveEnd.isEnded(session)) return
    val login = session.channelLogin
    // Fond qui garde les touchers : les commandes de Media3 restent dessous.
    Column(Modifier.matchParentSize().background(Color(0xE6000000)).pointerInput(Unit) {}.padding(12.dp),
           horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("📡", fontSize = 24.sp)
        Text(AppState.t("p6_live_ended"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
             textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            target?.let { r ->
                Button(onClick = {
                    Logger.info("PLAYER", "Fin du direct : raid suivi", r.targetLogin)
                    nav.play(PlayRequest.Live(r.targetLogin))
                }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                   contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
                    Text("▶ " + AppState.t("p6_watch_target").replace("{u}", r.targetName), fontSize = 13.sp, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                }
            }
            if (login != null) {
                // Le lecteur fini se ferme ; la fiche de la chaîne liste ses VODs.
                OutlinedButton(onClick = {
                    nav.pop()
                    nav.push(Screen.Channel(login))
                }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
                    Text(AppState.t("p6_see_vods"), color = Color.White, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        TextButton(onClick = { session.retry() }) {
            Text(AppState.t("retry"), color = TU.muted, fontSize = 13.sp)
        }
    }
}
