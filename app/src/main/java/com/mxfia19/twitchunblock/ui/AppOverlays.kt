package com.mxfia19.twitchunblock.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.notify.InAppNotice
import com.mxfia19.twitchunblock.notify.InAppNotices

/**
 * Ce qui se pose par-dessus l'app du téléphone, branché d'une ligne dans
 * MainActivity (après PhoneApp) : le bandeau des avis internes (réponse à
 * un signalement, drop) et la visite guidée du premier lancement. Rien ici
 * ne capte les touchers hors du bandeau.
 */
@Composable
fun AppOverlays(nav: Navigator) {
    InAppNoticeHost(nav)
    OnboardingOverlay(nav)
}

/** Notification d'un avis touchée (InAppNotices.EXTRA_OPEN) : la page
 *  qu'elle annonce. true si l'intention en venait. */
fun openFromNotice(intent: Intent?, nav: Navigator): Boolean {
    val what = intent?.getStringExtra(InAppNotices.EXTRA_OPEN) ?: return false
    // Pas rejouée si l'activité est recréée avec la même intention.
    intent.removeExtra(InAppNotices.EXTRA_OPEN)
    when (what) {
        "feedback" -> if (nav.current !is Screen.Feedback) nav.push(Screen.Feedback)
        "drops" -> if (nav.current !is Screen.Drops) nav.push(Screen.Drops)
        else -> return false
    }
    return true
}

/** Bandeau en haut de l'écran : touché, il ouvre ce qu'il annonce ; glissé
 *  vers le haut, il part. Pas pendant l'image dans l'image. */
@Composable
private fun InAppNoticeHost(nav: Navigator) {
    DisposableEffect(Unit) {
        InAppNotices.addHost()
        onDispose { InAppNotices.removeHost() }
    }
    val notice = InAppNotices.current
    // Dernier avis montré, gardé le temps que le bandeau parte.
    val last = remember { arrayOfNulls<InAppNotice>(1) }
    if (notice != null) last[0] = notice
    val shown = notice ?: last[0]
    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = notice != null && !PipState.active,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            shown?.let { NoticeBanner(it, nav) }
        }
    }
}

@Composable
private fun NoticeBanner(notice: InAppNotice, nav: Navigator) {
    val icon = when (notice.kind) {
        InAppNotice.Kind.FEEDBACK -> Icons.Filled.Email
        InAppNotice.Kind.DROP_READY -> Icons.Filled.Star
        InAppNotice.Kind.DROP_CLAIMED -> Icons.Filled.CheckCircle
    }
    val tint = when (notice.kind) {
        InAppNotice.Kind.FEEDBACK -> TU.primary
        InAppNotice.Kind.DROP_READY -> androidx.compose.ui.graphics.Color(0xFFA970FF)
        InAppNotice.Kind.DROP_CLAIMED -> TU.success
    }
    val shape = RoundedCornerShape(16.dp)
    var drag = 0f
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(TU.surface)
            .border(BorderStroke(1.dp, TU.line), shape)
            .pointerInput(notice.uid) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -24f) InAppNotices.dismiss() },
                ) { change, dy ->
                    drag += dy
                    change.consume()
                }
            }
            .clickable {
                InAppNotices.dismiss()
                when (notice.kind) {
                    InAppNotice.Kind.FEEDBACK -> if (nav.current !is Screen.Feedback) nav.push(Screen.Feedback)
                    else -> if (nav.current !is Screen.Drops) nav.push(Screen.Drops)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(notice.title, color = TU.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            if (notice.body.isNotEmpty()) {
                Text(notice.body, color = TU.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
