package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Timeline
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.AdBreakWatcher
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.displayedTimeMs
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.delay

// Par-dessus l'image d'un direct (série 5) : pause pub de la chaîne, bouton
// de l'image verticale ; sous l'image, la barre de toute la diffusion.

/** Ce qui se pose sur l'image d'un direct, en portrait comme en paysage. */
@Composable
fun BoxScope.StreamVideoOverlay(session: PlaybackSession) {
    // Image verticale (Dual Format) : cherchée une fois le direct parti.
    val login = if (session.isLive && session.state is PlaybackSession.State.Ready) session.channelLogin else null
    LaunchedEffect(login, session.openCount) { if (login != null) session.vertical.lookup(login) }
    // Chat posé sur l'image (paysage) : bandeau et bouton s'en écartent. Le
    // titre cliquable qu'affichent les commandes en paysage (PlayerAddons.kt)
    // couvrait le haut du bandeau : il passe dessous, comme l'avis des passages coupés.
    val inset = LocalChatOverlayInset.current
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val adTop = if (landscape && PlayerUi.controlsVisible) 72.dp else 8.dp
    Box(Modifier.matchParentSize().padding(start = inset.start, end = inset.end)) {
        if (session.isLive) {
            AdBreakBanner(session.adBreaks, modifier = Modifier.align(Alignment.TopCenter).padding(top = adTop, start = 52.dp, end = 52.dp))
        }
        val vertical = session.vertical
        if (vertical.available) {
            val on = vertical.on
            IconButton(onClick = { vertical.toggle() },
                       modifier = Modifier.align(Alignment.TopStart)
                           .semantics { contentDescription = AppState.t(if (on) "vertical_off" else "vertical_format") }) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                    // Le cadre de l'autre image : couché pour revenir, debout pour y aller.
                    Box(Modifier.size(if (on) 16.dp else 10.dp, if (on) 10.dp else 16.dp)
                            .border(2.dp, if (on) TU.primary else Color.White, RoundedCornerShape(2.dp)))
                }
            }
        }
        vertical.notice?.let { text ->
            LaunchedEffect(text) {
                delay(3_000)
                vertical.notice = null
            }
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(16.dp))
                     .background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    // Chromecast (CastUi.kt) : par-dessus le reste pendant la diffusion.
    CastOverlay(session)
}

/**
 * Bandeau d'une pause pub cousue par Twitch : ce qu'est l'écran violet, et
 * dans combien de temps le direct revient ; le son, coupé pendant la pause,
 * se remet d'un toucher. Rien hors pause. `compact` : une ligne, pour les
 * tuiles du multi-live. Comme AdBreakBanner (iOS).
 */
@Composable
fun AdBreakBanner(watcher: AdBreakWatcher, compact: Boolean = false, modifier: Modifier = Modifier) {
    val left = watcher.remaining ?: return
    if (compact) {
        Text("📢 " + AppState.t("ad_break_short").replace("{n}", "$left"), color = Color.White, fontSize = 11.sp,
             fontWeight = FontWeight.Bold, maxLines = 1,
             modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xA6000000))
                 .padding(horizontal = 9.dp, vertical = 4.dp))
        return
    }
    // Pas de session web, ou réglage coupé : un abonné ne profite pas de son
    // abonnement sans pub, on le lui dit ; jamais avec « Sans compte Twitch »
    // (invitations masquées, réglages du compte aussi), comme l'iPhone.
    val loginHint = !com.mxfia19.twitchunblock.core.NoAccount.active &&
        (!Features.subAdFree.value || WebSession.token == null)
    Column(modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xB3000000))
               .border(1.dp, TU.primary.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
               .clickable(enabled = watcher.mutedByUs) { watcher.unmute() }
               .padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text("📢 " + AppState.t("ad_break_title"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(AppState.t("ad_break_sub").replace("{n}", "$left"), color = Color.White, fontSize = 12.sp,
             fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
        if (loginHint) {
            Text(AppState.t("ad_break_login_hint"), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                 modifier = Modifier.padding(top = 3.dp))
        }
        if (watcher.mutedByUs) {
            Text("🔇 " + AppState.t("discover_unmute"), color = TU.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Taille de l'image en portrait : 16:9, ou 9:16 (62 % de l'écran au plus,
 *  le chat dessous) pour l'image verticale d'un direct. */
@Composable
fun Modifier.portraitVideoSize(session: PlaybackSession): Modifier {
    if (!session.vertical.on) return this.aspectRatio(16f / 9f)
    val conf = LocalConfiguration.current
    return this.height(minOf(conf.screenWidthDp * 16f / 9f, conf.screenHeightDp * 0.62f).dp)
}

/** Repères de la barre « toute la diffusion », en secondes depuis le début du
 *  direct : l'heure réelle est le seul repère commun au flux du direct et à
 *  son enregistrement. `windowStart`/`windowEnd` : ce que la lecture en cours
 *  sait rejoindre sans changer de flux. */
private class BroadcastSpan(val total: Float, val current: Float, val playheadMs: Long,
                            val windowStart: Float, val windowEnd: Float, val rewind: Boolean)

private fun broadcastSpan(session: PlaybackSession, window: Timeline.Window): BroadcastSpan? {
    if (session.state !is PlaybackSession.State.Ready) return null
    val start = Format.parseDate(session.startedAt) ?: return null
    val player = session.player
    val total = (System.currentTimeMillis() - start) / 1000f
    if (total < 60f) return null
    val pos = player.currentPosition
    val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    if ((session.request as? PlayRequest.Vod)?.rewind == true) {
        // Enregistrement du direct : sa position est déjà comptée depuis le début.
        val d = dur ?: return null
        return BroadcastSpan(total, (pos / 1000f).coerceIn(0f, total), pos, 0f, minOf(total, d / 1000f), rewind = true)
    }
    if (!session.isLive || session.dvrVideoId == null) return null
    val now = displayedTimeMs(player, window) ?: return null
    val current = (now - start) / 1000f
    if (current < 0f || current > total + 60f) return null
    val d = dur ?: pos
    return BroadcastSpan(total, minOf(current, total), pos, maxOf(0f, current - pos / 1000f),
                         minOf(total, current + (d - pos) / 1000f), rewind = false)
}

/**
 * Barre de toute la diffusion d'un direct dont l'enregistrement existe (ou
 * de son rembobinage) : dans la fenêtre du flux on y cherche normalement,
 * avant elle l'enregistrement prend le relais à cet instant ; au bout de la
 * barre (ou « Direct »), retour au direct. Comme broadcastSpan (iOS 1.7.0).
 */
@Composable
fun BroadcastBar(session: PlaybackSession, modifier: Modifier = Modifier) {
    val window = remember { Timeline.Window() }
    var span by remember { mutableStateOf<BroadcastSpan?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    // Repères gelés pendant le glissement : la diffusion avance pendant ce temps.
    var frozen by remember { mutableStateOf<BroadcastSpan?>(null) }
    val rewind = (session.request as? PlayRequest.Vod)?.rewind == true
    val liveDvr = session.isLive && session.dvrVideoId != null
    LaunchedEffect(session, session.openCount, rewind, liveDvr) {
        span = null
        if (!rewind && !liveDvr) return@LaunchedEffect
        while (true) {
            span = broadcastSpan(session, window)
            delay(1_000)
        }
    }
    val live = span ?: return
    val s = frozen ?: live
    val value = (drag ?: s.current).coerceIn(0f, maxOf(s.total, 1f))
    Row(modifier.fillMaxWidth().background(TU.surface).padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(Format.clock(value.toLong()), color = TU.muted, fontSize = 11.sp)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = value,
                onValueChange = { v ->
                    if (frozen == null) frozen = live
                    drag = v
                },
                onValueChangeFinished = {
                    val target = drag
                    val f = frozen
                    drag = null
                    frozen = null
                    if (target != null && f != null) seekBroadcast(session, target, f)
                },
                valueRange = 0f..maxOf(s.total, 1f),
                colors = SliderDefaults.colors(thumbColor = TU.primary, activeTrackColor = TU.primary,
                                               inactiveTrackColor = TU.line),
            )
            // Le tronçon que la lecture rejoint sans changer de flux : au-delà,
            // l'enregistrement (ou le direct) prend le relais, avec son chargement.
            Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 10.dp)) {
                val total = maxOf(s.total, 1f)
                val a = size.width * (s.windowStart / total)
                val b = size.width * (s.windowEnd / total)
                if (b > a) drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset(a, 0f), size = Size(b - a, size.height))
            }
        }
        Text(Format.clock(s.total.toLong()), color = TU.muted, fontSize = 11.sp)
        // Au bord du direct : pastille rouge ; sinon, un toucher y ramène.
        val atEdge = !s.rewind && s.windowEnd - s.current < 10f
        TextButton(onClick = { goLive(session, s.rewind) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("● " + AppState.t("back_to_live"), color = if (atEdge) TU.live else TU.muted, fontSize = 12.sp,
                 fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun goLive(session: PlaybackSession, rewind: Boolean) {
    if (rewind) {
        val channel = (session.request as? PlayRequest.Vod)?.channel ?: return
        Logger.info("PLAYER", "Retour au direct", channel)
        session.open(PlayRequest.Live(channel))
    } else {
        session.player.seekToDefaultPosition()
        session.player.play()
    }
}

/** Dans la fenêtre du flux : on y cherche ; avant : l'enregistrement à cet
 *  instant ; au bout (à 3 s près) : le direct. Le repère est celui de la
 *  prise du curseur (`playheadMs`), la lecture ayant continué pendant le glissement. */
private fun seekBroadcast(session: PlaybackSession, target: Float, s: BroadcastSpan) {
    val player = session.player
    if (s.rewind) {
        if (target >= s.windowEnd - 3f) goLive(session, rewind = true)
        else player.seekTo((target * 1000).toLong())
        return
    }
    when {
        target >= s.windowEnd - 3f -> goLive(session, rewind = false)
        target >= s.windowStart -> player.seekTo(maxOf(0L, s.playheadMs + ((target - s.current) * 1000).toLong()))
        else -> {
            val dvr = session.dvrVideoId ?: return
            Logger.info("PLAYER", "Rembobinage hors fenêtre du direct", "→ ${target.toInt()} s depuis le début")
            session.open(PlayRequest.Vod(dvr, session.title, session.channelLogin, startMs = (target * 1000).toLong(), rewind = true))
        }
    }
}
