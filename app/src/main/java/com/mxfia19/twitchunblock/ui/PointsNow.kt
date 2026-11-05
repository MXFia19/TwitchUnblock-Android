package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.points.ChannelPoints
import com.mxfia19.twitchunblock.points.PointsTracker

/** Suivi des points (Réglages → Points de chaîne) : les chaînes qui
 *  rapportent en ce moment, minutes et points depuis l'ouverture de leur
 *  direct. Rien quand aucune ne rapporte. Comme nowCard (iOS). */
@Composable
fun PointsNowCard() {
    val list = PointsTracker.sessions
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp))
               .background(TU.surface).padding(12.dp)) {
        Text("● " + AppState.t("pt_now"), color = TU.live, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        for (s in list) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.login, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Format.duration(s.minutes * 60) + " · +" + ChannelPoints.format(s.points), color = TU.muted, fontSize = 12.sp)
            }
        }
    }
}
