package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.drops.DropCampaign
import com.mxfia19.twitchunblock.drops.Drops
import com.mxfia19.twitchunblock.drops.DropsHeartbeat
import com.mxfia19.twitchunblock.drops.TimedDrop
import com.mxfia19.twitchunblock.points.ChannelPoints
import com.mxfia19.twitchunblock.points.PointsTracker
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.launch

/** Drops : progression des campagnes en cours, réclamation, campagnes ouvertes. Comme DropsView (iOS). */
@Composable
fun DropsScreen(nav: Navigator) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    LaunchedEffect(WebSession.token) {
        if (WebSession.token != null) {
            Drops.refresh()
            Drops.refreshAvailable()
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("drops"), nav) {
            if (WebSession.token != null) {
                TextButton(onClick = { scope.launch { Drops.refresh(); Drops.refreshAvailable() } }) {
                    Text(AppState.t("drops_refresh"), color = TU.primary)
                }
            }
        }
        if (WebSession.token == null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(AppState.t("drops_login"), color = TU.text, fontSize = 14.sp)
                Button(onClick = { nav.push(Screen.WebLogin) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                       modifier = Modifier.padding(top = 10.dp)) { Text(AppState.t("web_banner_btn")) }
            }
        } else {
            DropsList(scope, uri::openUri)
        }
    }
}

@Composable
private fun DropsList(scope: kotlinx.coroutines.CoroutineScope, openUri: (String) -> Unit) {
    run {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Toggle(AppState.t("drops_progress"), AppState.t("drops_progress_sub"), Features.dropsProgress.value) { Features.dropsProgress.set(it) }
                    Toggle(AppState.t("drops_autoclaim"), AppState.t("drops_autoclaim_sub"), Features.dropsAutoClaim.value) { Features.dropsAutoClaim.set(it) }
                    Toggle(AppState.t("drops_notify"), AppState.t("drops_notify_sub"), Features.dropsNotify.value) { Features.dropsNotify.set(it) }
                    val c = DropsHeartbeat.login ?: ""
                    val status = when {
                        !Features.dropsProgress.value -> AppState.t("drops_status_off")
                        else -> when (DropsHeartbeat.status) {
                            "checking" -> AppState.t("drops_status_checking")
                            "none" -> AppState.t("drops_status_none")
                            "active" -> AppState.t("drops_status_active")
                            "failing" -> AppState.t("drops_status_failing")
                            else -> AppState.t("drops_status_idle")
                        }
                    }
                    Text(status.replace("{c}", c), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Drops.session?.let { s ->
                        Text(AppState.t("drops_now").replace("{c}", s.channelName ?: c) + " · " +
                             AppState.t("drops_minutes").replace("{n}", "${s.currentMinutes}").replace("{t}", "${s.requiredMinutes}"),
                             color = TU.primary, fontSize = 13.sp)
                    }
                    Drops.notice?.let { (text, ok) ->
                        Text(text, color = if (ok) TU.success else TU.danger, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
            item { SectionTitle(AppState.t("drops_in_progress")) }
            when {
                Drops.loading && Drops.campaigns.isEmpty() -> item { LoadingBox() }
                Drops.failed && Drops.campaigns.isEmpty() -> item { MessageBox(AppState.t("drops_error")) }
                Drops.campaigns.isEmpty() -> item { MessageBox(AppState.t("drops_empty")) }
                else -> items(Drops.campaigns, key = { "c-" + it.id }) { c ->
                    CampaignCard(c, progress = true, onLink = openUri) { d -> scope.launch { Drops.claim(d); Drops.refresh(autoClaim = false) } }
                }
            }
            item { SectionTitle(AppState.t("drops_available")) }
            item {
                Text(AppState.t("drops_available_sub"), color = TU.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val inProgress = Drops.campaigns.map { it.id }.toHashSet()
            val others = Drops.available.filter { it.id !in inProgress }
            when {
                Drops.availableLoading && others.isEmpty() -> item { LoadingBox() }
                Drops.availableFailed && others.isEmpty() -> item { MessageBox(AppState.t("drops_error")) }
                others.isEmpty() -> item { MessageBox(AppState.t("drops_available_empty")) }
                else -> items(others, key = { "a-" + it.id }) { c -> CampaignCard(c, progress = false, onLink = openUri) {} }
            }
        }
    }
}

@Composable
private fun CampaignCard(c: DropCampaign, progress: Boolean, onLink: (String) -> Unit, onClaim: (TimedDrop) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
        .background(TU.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = c.boxArt, contentDescription = null, contentScale = ContentScale.Crop,
                       modifier = Modifier.size(width = 45.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)).background(TU.surfaceHi))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.gameName, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(c.name, color = TU.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val date = if (c.isUpcoming) c.startAt?.let { AppState.t("drops_starts").replace("{d}", Format.shortDate(it)) }
                           else c.endAt?.let { AppState.t("drops_ends").replace("{d}", Format.shortDate(it)) }
                if (date != null) Text(date, color = TU.muted, fontSize = 12.sp)
            }
        }
        val link = c.accountLinkUrl
        if (c.needsAccountLink && link != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AppState.t("drops_link"), color = TU.danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onLink(link) }) { Text(AppState.t("drops_link_btn"), color = TU.primary) }
            }
        }
        for (d in c.drops) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = d.imageUrl, contentDescription = null, contentScale = ContentScale.Fit,
                           modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(TU.surfaceHi))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = TU.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (progress) {
                        LinearProgressIndicator(progress = { d.progress }, color = TU.primary, trackColor = TU.line,
                                                modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp))
                        Text(when {
                            d.isClaimed -> AppState.t("drops_claimed")
                            !d.hasPreconditionsMet -> AppState.t("drops_locked")
                            else -> AppState.t("drops_minutes").replace("{n}", "${d.currentMinutes}").replace("{t}", "${d.requiredMinutes}")
                        }, color = TU.muted, fontSize = 11.sp)
                    } else {
                        Text(AppState.t("drops_minutes_req").replace("{t}", "${d.requiredMinutes}"), color = TU.muted, fontSize = 11.sp)
                    }
                }
                if (progress && d.canClaim) {
                    Button(onClick = { onClaim(d) }, enabled = d.id !in Drops.claiming,
                           colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                        if (d.id in Drops.claiming) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        else Text(AppState.t("drops_claim"), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Points de chaîne gagnés en regardant, par chaîne. */
@Composable
fun PointsTrackerScreen(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("points_tracker"), nav) {
            if (PointsTracker.stats.isNotEmpty()) {
                TextButton(onClick = { PointsTracker.clear() }) { Text(AppState.t("clear"), color = TU.danger) }
            }
        }
        if (PointsTracker.stats.isEmpty()) {
            MessageBox(AppState.t("points_tracker_empty"))
        } else {
            TrackerList(nav)
        }
    }
}

@Composable
private fun TrackerList(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        Text(AppState.t("points_tracker_total").replace("{m}", Format.duration(PointsTracker.totalMinutes * 60))
                 .replace("{p}", ChannelPoints.format(PointsTracker.totalPoints)),
             color = TU.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(PointsTracker.stats, key = { it.login }) { s ->
                Row(Modifier.fillMaxWidth().focusCard({ nav.push(Screen.Channel(s.login)) }).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.login, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOf(Format.duration(s.minutes * 60), AppState.tn("points_tracker_chests", s.chests).replace("{n}", "${s.chests}"),
                                    Format.since(s.last)).joinToString(" · "), color = TU.muted, fontSize = 12.sp)
                    }
                    Text("+" + ChannelPoints.format(s.points), color = TU.success, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Chaînes et catégories masquées : un toucher les rend visibles. */
@Composable
fun HiddenScreen(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppState.t("hidden_title"), nav)
        Text(AppState.t("hidden_sub"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (Hidden.isEmpty) MessageBox(AppState.t("hidden_empty")) else HiddenList()
    }
}

@Composable
private fun HiddenList() {
    run {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (Hidden.channels.isNotEmpty()) {
                item { SectionTitle(AppState.t("hidden_channels_n").replace("{n}", "${Hidden.channels.size}")) }
                items(Hidden.channels, key = { "h-" + it.login }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = TU.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Hidden.unhideChannel(c.login) }) { Text(AppState.t("remove"), color = TU.primary) }
                    }
                }
            }
            if (Hidden.categories.isNotEmpty()) {
                item { SectionTitle(AppState.t("hidden_categories_n").replace("{n}", "${Hidden.categories.size}")) }
                items(Hidden.categories, key = { "g-" + it.id }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c.box.isNotEmpty()) {
                            AsyncImage(model = c.box, contentDescription = null, contentScale = ContentScale.Crop,
                                       modifier = Modifier.size(width = 30.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(c.name, color = TU.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Hidden.unhideCategory(c.id) }) { Text(AppState.t("remove"), color = TU.primary) }
                    }
                }
            }
        }
    }
}
