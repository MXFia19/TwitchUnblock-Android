package com.mxfia19.twitchunblock.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.chat.ChatOptions
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.events.ChannelFollow
import com.mxfia19.twitchunblock.events.DropsNotice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Bandeaux du direct de la série 6 : carte des drops, pastilles des bandeaux
// fermés, ligne d'icônes des bandeaux repliés (paysage), bouton suivre.
// Comme ChatNotices et ChatView (iOS).

private val NoticePurple = Color(0xFFA970FF)

/** Un bandeau du direct, représenté par une icône quand ils sont repliés. */
enum class NoticeKind(val icon: String) {
    PINNED("📌"), DROPS("🎁"), POLL("📊"), PREDICTION("🔮"), HYPE("🚄"), RAID("🚀")
}

/** Fin d'un sondage ou d'une prédiction encore ouverts, et du hype train :
 *  les comptes à rebours des icônes. */
class NoticeEnds(val poll: Long? = null, val prediction: Long? = null, val hype: Long? = null)

/** Icônes des bandeaux, avec leur compte à rebours. */
@Composable
fun NoticeIcons(kinds: List<NoticeKind>, ends: NoticeEnds, now: Long, spacing: Int = 10) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in kinds) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.icon, fontSize = 11.sp)
                val (end, tint) = when (k) {
                    NoticeKind.POLL -> Pair(ends.poll, TU.primary)
                    NoticeKind.PREDICTION -> Pair(ends.prediction, NoticePurple)
                    NoticeKind.HYPE -> Pair(ends.hype, TU.live)
                    else -> Pair(null, TU.muted)
                }
                if (end != null && end > now) {
                    Text(" " + Format.clock((end - now) / 1000), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Paysage, bandeaux repliés : une ligne d'icônes ; la toucher les déplie. */
@Composable
fun CollapsedNoticesBar(kinds: List<NoticeKind>, ends: NoticeEnds, now: Long, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp).background(TU.surface).clickable(onClick = onExpand).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NoticeIcons(kinds, ends, now)
        Spacer(Modifier.weight(1f))
        Text("▾", color = TU.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(start = 6.dp))
    }
}

/** Paysage, sous les bandeaux dépliés : « Masquer » les replie en une ligne. */
@Composable
fun NoticesHideStrip(onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(end = 6.dp), horizontalArrangement = Arrangement.End) {
        Text(AppState.t("notices_hide") + " ▴", color = TU.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onHide).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Sondage, prédiction ou hype train fermés (×) : une seule pastille, quel
 *  que soit leur nombre, qui les rouvre. */
@Composable
fun ClosedNoticesChip(kinds: List<NoticeKind>, ends: NoticeEnds, now: Long, onReopen: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(TU.surfaceHi).clickable(onClick = onReopen)
            .padding(horizontal = 7.dp, vertical = 3.dp)) {
        NoticeIcons(kinds, ends, now, spacing = 6)
    }
}

/** Message épinglé réduit (×) : sa pastille le rouvre. */
@Composable
fun PinnedChip(onReopen: () -> Unit) {
    Text("📌 " + AppState.t("pinned_short"), color = NoticePurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
         modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(NoticePurple.copy(alpha = 0.15f))
             .clickable(onClick = onReopen).padding(horizontal = 7.dp, vertical = 3.dp))
}

/** Carte « Drops » en haut du chat : la récompense, le temps à regarder,
 *  l'échéance, un lien vers les détails ; fermée, elle ne revient pas pour
 *  cette campagne. */
@Composable
fun DropsCard(c: DropsNotice, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val headline = if (c.rewardName.isNotEmpty() && c.minutes > 0) {
        AppState.t("drops_notice_title").replace("{m}", "${c.minutes}").replace("{r}", c.rewardName)
    } else c.name
    val more = if (c.more > 0) " · " + AppState.tn("drops_notice_more", c.more).replace("{n}", "${c.more}") else ""
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (c.rewardImage != null) {
                AsyncImage(model = c.rewardImage, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(34.dp))
            } else {
                Text("🎁", fontSize = 18.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(headline, color = TU.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(c.name, c.game).filter { it.isNotEmpty() }.joinToString(" · ") + more, color = TU.muted, fontSize = 11.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                c.endsAt?.let { end ->
                    Text(AppState.t("drops_notice_ends").replace("{d}", endDate(end)), color = TU.muted, fontSize = 10.sp)
                    Spacer(Modifier.width(10.dp))
                }
                c.detailsUrl?.let { url ->
                    Text(AppState.t("drops_notice_details"), color = TU.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                         modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { runCatching { uri.openUri(url) } }
                             .padding(vertical = 2.dp))
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.muted, modifier = Modifier.size(14.dp))
        }
    }
}

/** Échéance d'une campagne : « 12 nov., 18:00 » dans la langue de l'app. */
private fun endDate(ms: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag(AppState.lang.code)).format(Date(ms))

/** Suivre / ne plus suivre la chaîne, au-dessus du chat : sur Twitch avec
 *  la session web, sinon sur l'appareil. Ne plus suivre se confirme, et la
 *  même fenêtre peut masquer le bouton (Réglages → Chat). */
@Composable
fun FollowChip(follow: ChannelFollow) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val on = follow.following
    val tint = if (on) TU.danger else TU.primary
    LaunchedEffect(follow.failed) {
        if (follow.failed) Toast.makeText(context, AppState.t("follow_failed"), Toast.LENGTH_SHORT).show()
    }
    Text((if (on) "♥ " else "♡ ") + AppState.t(if (on) "following" else "follow"), color = tint, fontSize = 11.sp,
         fontWeight = FontWeight.Bold, maxLines = 1,
         modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.15f))
             .border(1.dp, tint, RoundedCornerShape(6.dp))
             .clickable(enabled = !follow.busy) { if (on) confirm = true else follow.set(true) }
             .padding(horizontal = 8.dp, vertical = 4.dp))
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(AppState.t("unfollow_confirm").replace("{c}", follow.login)) },
            text = { Text(AppState.t("follow_hide_hint"), color = TU.muted, fontSize = 13.sp) },
            // Action explicite, jamais une bascule : un statut relu entre-temps
            // ne peut pas la changer en abonnement.
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    follow.set(false)
                }) { Text(AppState.t("unfollow"), color = TU.danger) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirm = false
                        ChatOptions.followButton.set(false)
                        Logger.info("CHAT", "Bouton suivre au-dessus du chat", "masqué")
                    }) { Text(AppState.t("follow_hide_button"), color = TU.text) }
                    TextButton(onClick = { confirm = false }) { Text(AppState.t("cancel"), color = TU.muted) }
                }
            },
            containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
        )
    }
}
