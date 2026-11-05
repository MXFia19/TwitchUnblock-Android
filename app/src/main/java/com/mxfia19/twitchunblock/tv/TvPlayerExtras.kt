package com.mxfia19.twitchunblock.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.NetworkStatus
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.player.AdBreakWatcher
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.ui.TU

/** Pause pub de la chaîne (direct) : ce qu'est l'écran violet, et dans
 *  combien de temps le direct revient, comme l'Apple TV (TVAdBreakBanner).
 *  Jamais le focus ; le son n'est pas coupé (session main = false). */
@Composable
fun TvAdBreakBanner(watcher: AdBreakWatcher, modifier: Modifier = Modifier) {
    val left = watcher.remaining ?: return
    Column(modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000))
               .border(2.dp, TU.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
               .padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text("📢 " + AppState.t("ad_break_title"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(AppState.t("ad_break_sub").replace("{n}", "$left"), color = Color.White, fontSize = 15.sp,
             modifier = Modifier.padding(top = 4.dp))
    }
}

/** Qualité choisie dans le lecteur de la TV : elle devient la préférée, comme
 *  sur l'Apple TV (selectQuality ne la retient que pour le téléphone, la
 *  session de la TV ayant main = false). */
fun keepTvQuality(session: PlaybackSession, key: String) {
    val m = session.currentMaster
    val pref = Quality.preferenceFor(key, m.links.keys, m.variants) ?: return
    if (!NetworkStatus.isCellular) AppState.updateQualityPref(pref)
}
