package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.events.LiveEvents
import com.mxfia19.twitchunblock.events.LiveHype
import com.mxfia19.twitchunblock.events.LivePoll
import com.mxfia19.twitchunblock.events.LivePrediction
import com.mxfia19.twitchunblock.events.PinnedChat
import com.mxfia19.twitchunblock.events.PinnedWatcher
import com.mxfia19.twitchunblock.events.RaidWatcher
import com.mxfia19.twitchunblock.points.ChannelPoints
import com.mxfia19.twitchunblock.points.ChannelReward
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Heure qui avance chaque seconde (comptes à rebours). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private val purple = Color(0xFFA970FF)

/** Couleur d'une issue de prédiction. */
private fun outcomeColor(c: String): Color = when (c.uppercase()) {
    "BLUE" -> Color(0xFF3D7DFF)
    "PINK" -> Color(0xFFF5009B)
    "GRAY", "GREY" -> TU.muted
    else -> TU.primary
}

/** 950, 27.4K, 1.2M. */
private fun compact(v: Int): String {
    fun one(x: Double, unit: String): String {
        val t = Math.floor(x * 10) / 10
        return (if (t == Math.floor(t)) t.toLong().toString() else String.format(java.util.Locale.US, "%.1f", t)) + unit
    }
    return when {
        v >= 1_000_000 -> one(v / 1_000_000.0, "M")
        v >= 1_000 -> one(v / 1_000.0, "K")
        else -> v.toString()
    }
}

private fun ratioText(r: Double): String {
    var s = String.format(java.util.Locale.US, "%.2f", r)
    while (s.endsWith("0")) s = s.dropLast(1)
    if (s.endsWith(".")) s = s.dropLast(1)
    return s
}

/**
 * Au-dessus du chat d'un direct : raid, message épinglé, sondage,
 * prédiction, hype train (chacun se ferme d'un ×), puis la barre des points
 * de chaîne. Comme LiveEventsBanner (iOS).
 */
@Composable
fun LiveExtras(
    points: ChannelPoints,
    events: LiveEvents,
    pinned: PinnedWatcher,
    raids: RaidWatcher,
    onJoinRaid: (String) -> Unit,
    onOpenRewards: () -> Unit,
    onWebLogin: () -> Unit,
) {
    var closed by remember { mutableStateOf(setOf<String>()) }
    val close = { key: String -> closed = closed + key }
    val now = rememberNow()
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
        val raid = raids.raid
        if (raid != null && Features.enableRaids.value && "raid:${raid.id}" !in closed) {
            Banner("🚀", Color(0xFFFF8A3D), onClose = { close("raid:${raid.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${AppState.t("raid_to")} ${raid.targetName} · ${Format.viewers(raid.viewers)}", color = TU.text,
                         fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onJoinRaid(raid.targetLogin) }) { Text(AppState.t("tv_watch_live"), color = TU.primary, fontSize = 13.sp) }
                }
            }
        }
        val pin = pinned.pinned
        if (pin != null && Features.showPinned.value && "pin:${pin.id}" !in closed) {
            Banner("📌", purple, onClose = { close("pin:${pin.id}") }) { PinnedCard(pin, now) }
        }
        if (Features.showLiveEvents.value) {
            val poll = events.poll
            if (poll != null && poll.noticeKey !in closed) {
                Banner("📊", TU.primary, onClose = { close(poll.noticeKey) }) { PollCard(poll, events, now) }
            }
            val pred = events.prediction
            if (pred != null && pred.noticeKey !in closed) {
                Banner("🔮", purple, onClose = { close(pred.noticeKey) }) { PredictionCard(pred, events, points, now, onWebLogin) }
            }
            val hype = events.hype
            if (hype != null && hype.noticeKey !in closed) {
                Banner("🚄", TU.live, onClose = { close(hype.noticeKey) }) { HypeCard(hype, now) }
            }
        }
        PointsBar(points, events, onOpenRewards, onWebLogin)
    }
}

@Composable
private fun Banner(icon: String, tint: Color, onClose: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f)).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, end = 6.dp))
        Box(Modifier.weight(1f)) { content() }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.muted, modifier = Modifier.size(14.dp))
        }
    }
}

// ── Message épinglé ──
@Composable
private fun PinnedCard(pin: PinnedChat, now: Long) {
    var open by remember(pin.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        if (open) {
            ChatMessageRow(pin.message, fontSize = 13.sp, emoteSize = 20.sp)
        } else {
            Text("${pin.message.name}: ${pin.text}", color = TU.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val parts = mutableListOf(pin.pinnedBy?.let { AppState.t("pinned_by").replace("{u}", it) } ?: AppState.t("pinned"))
        pin.startsAt?.let { s ->
            val m = ((now - s) / 60_000).toInt()
            parts += when {
                m < 1 -> AppState.t("just_now")
                m < 60 -> AppState.t("minutes_ago").replace("{n}", "$m")
                else -> AppState.t("hours_ago").replace("{n}", "${m / 60}")
            }
        }
        pin.endsAt?.let { e ->
            val left = maxOf(1, ((e - now + 59_999) / 60_000).toInt())
            parts += AppState.t("pin_left").replace("{n}", "$left")
        }
        Text(parts.joinToString(" · "), color = TU.muted, fontSize = 11.sp, maxLines = 1)
    }
}

// ── Barre de résultat ──
@Composable
private fun EventBar(label: String, value: Int, total: Int, color: Color, mine: Boolean, winner: Boolean,
                     selected: Boolean, stats: String?, tappable: Boolean, onClick: () -> Unit) {
    val pct = if (total > 0) value.toFloat() / total else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
        .clip(RoundedCornerShape(6.dp))
        .clickable(enabled = tappable, onClick = onClick)
        .background(if (selected) color.copy(alpha = 0.18f) else Color.Transparent)
        .padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (winner) "🏆 " else "") + (if (mine) "✓ " else "") + label, color = TU.text, fontSize = 12.sp,
                 fontWeight = if (mine || winner) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${(pct * 100).toInt()} %", color = TU.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        if (stats != null) Text(stats, color = TU.muted, fontSize = 10.sp)
    }
}

// ── Sondage ──
@Composable
private fun PollCard(poll: LivePoll, events: LiveEvents, now: Long) {
    val scope = rememberCoroutineScope()
    var showResults by remember(poll.id) { mutableStateOf(false) }
    val folded = !poll.isActive && !showResults
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !poll.isActive) { showResults = !showResults },
            verticalAlignment = Alignment.CenterVertically) {
            Text(poll.title.ifEmpty { AppState.t("poll_default_title") }, color = TU.text, fontSize = 12.sp,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
            val ends = poll.endsAt
            if (poll.isActive && ends != null && ends > now) {
                Text(Format.clock((ends - now) / 1000), color = TU.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else if (!poll.isActive) {
                Text(AppState.t("poll_ended"), color = TU.muted, fontSize = 11.sp)
            }
        }
        if (folded) {
            val w = poll.choices.firstOrNull { it.id == poll.winnerId }
            if (w != null) Text("🏆 ${w.title} · ${w.votes * 100 / poll.total} %", color = TU.success, fontSize = 11.sp)
        } else {
            for (c in poll.choices) {
                val mine = c.id in events.votedChoiceIds
                val canVote = poll.isActive && !events.voting && !mine && (poll.multichoice || events.votedChoiceIds.isEmpty())
                EventBar(c.title, c.votes, poll.total, TU.primary, mine, poll.winnerId == c.id, false, null, canVote) {
                    scope.launch { events.vote(c.id) }
                }
            }
        }
        val total = poll.choices.sumOf { it.votes }
        val hint = if (poll.isActive) AppState.t(if (poll.multichoice) "poll_multi_hint" else "poll_single_hint") else null
        Text(listOfNotNull("$total ${AppState.tn("poll_votes", total)}", hint).joinToString(" · "), color = TU.muted, fontSize = 10.sp)
        events.voteError?.let { code ->
            Text(AppState.t(if (code == "NO_SESSION") "pred_need_login" else "poll_vote_failed"), color = TU.live, fontSize = 10.sp)
        }
    }
}

// ── Prédiction ──
@Composable
private fun PredictionCard(pred: LivePrediction, events: LiveEvents, points: ChannelPoints, now: Long, onWebLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember(pred.id) { mutableStateOf<String?>(null) }
    var amount by remember(pred.id) { mutableStateOf("") }
    var showBars by remember(pred.id) { mutableStateOf(false) }
    val closes = pred.closesAt
    val open = !pred.locked && pred.winnerId == null && (closes == null || closes > now)
    val needsSession = !WebSession.active || points.needsWebLogin
    val maxBet = maxOf(0, minOf(points.balance, 250_000 - pred.myPoints))
    val awaiting = pred.locked && pred.winnerId == null
    val folded = awaiting && !showBars
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = awaiting) { showBars = !showBars }, verticalAlignment = Alignment.CenterVertically) {
            Text(pred.title.ifEmpty { AppState.t("pred_default_title") } + if (awaiting) " 🔒" else "", color = TU.text,
                 fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
            when {
                closes != null && closes > now && pred.winnerId == null ->
                    Text(Format.clock((closes - now) / 1000), color = purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                pred.winnerId != null -> Text(AppState.t("pred_result"), color = TU.success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                awaiting -> Text(AppState.t("pred_closed") + if (showBars) " ▴" else " ▾", color = TU.muted, fontSize = 11.sp)
            }
        }
        if (!folded) {
            for (o in pred.outcomes) {
                val mine = pred.myOutcomeId == o.id && pred.myPoints > 0
                val tappable = open && !needsSession && (pred.myOutcomeId == null || pred.myPoints == 0 || mine)
                val tint = if (pred.winnerId == null || pred.winnerId == o.id) outcomeColor(o.color) else TU.muted
                val stats = listOfNotNull("◎ " + compact(o.points), pred.ratio(o.points)?.let { "🏆 1:" + ratioText(it) },
                                          "👤 ${o.users}", pred.topBets[o.id]?.let { "👑 " + compact(it) }).joinToString("  ")
                EventBar(o.title, o.points, pred.total, tint, mine, pred.winnerId == o.id, tappable && selected == o.id, stats, tappable) {
                    events.betError = null
                    selected = if (selected == o.id) null else o.id
                }
            }
        }
        val myOutcome = pred.outcomes.firstOrNull { it.id == pred.myOutcomeId }
        if (myOutcome != null && pred.myPoints > 0) {
            val win = if (pred.winnerId == null) pred.ratio(myOutcome.points)?.let { (pred.myPoints * it).toInt() } else null
            val text = AppState.tn("pred_my_bet", pred.myPoints).replace("{n}", ChannelPoints.format(pred.myPoints))
                .replace("{o}", myOutcome.title) +
                (win?.let { " · " + AppState.tn("pred_potential", it).replace("{n}", ChannelPoints.format(it)) } ?: "")
            Text(text, color = TU.success, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            val chosen = pred.outcomes.firstOrNull { it.id == selected }
            when {
                needsSession -> Text(AppState.t("pred_need_login"), color = TU.primary, fontSize = 10.sp,
                                     modifier = Modifier.clickable { onWebLogin() })
                chosen == null -> Text(AppState.t("pred_tap_to_bet"), color = TU.muted, fontSize = 10.sp)
                else -> {
                    val tint = outcomeColor(chosen.color)
                    val value = amount.toIntOrNull() ?: 0
                    val valid = value in 10..maxBet
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(AppState.t("pred_bet_on").replace("{o}", chosen.title), color = tint, fontSize = 11.sp,
                                 fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("${AppState.t("pred_balance")} ${ChannelPoints.format(points.balance)}", color = TU.muted, fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            val presets = listOf(10, 100, 1_000, 10_000).filter { it < maxBet } + if (maxBet >= 10) listOf(maxBet) else emptyList()
                            for (v in presets) {
                                Text(if (v == maxBet) AppState.t("pred_all") else if (v >= 1000) "${v / 1000}k" else "$v",
                                     color = TU.text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                     modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(TU.surfaceHi)
                                         .clickable { amount = "$v" }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { v -> amount = v.filter { it.isDigit() }.take(7) },
                                placeholder = { Text("10", color = TU.muted) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = fieldColors(),
                                modifier = Modifier.weight(1f).height(52.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (events.predict(chosen.id, value)) {
                                            points.spend(value)
                                            amount = ""
                                            selected = null
                                        }
                                    }
                                },
                                enabled = valid && !events.betting,
                                colors = ButtonDefaults.buttonColors(containerColor = tint),
                            ) {
                                if (events.betting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                else Text(AppState.t("pred_bet"))
                            }
                        }
                        val err = events.betError
                        when {
                            err != null -> Text(betError(err), color = TU.live, fontSize = 10.sp)
                            value in 1..9 -> Text(AppState.t("pred_min"), color = TU.muted, fontSize = 10.sp)
                            value > maxBet -> Text(AppState.t(if (maxBet < points.balance) "pred_max" else "err_not_enough"),
                                                   color = TU.live, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun betError(code: String): String {
    val c = code.uppercase()
    return when {
        c == "NO_SESSION" -> AppState.t("pred_need_login")
        c == "NETWORK" -> AppState.t("pred_bet_failed")
        c.contains("NOT_ENOUGH") || c.contains("INSUFFICIENT") -> AppState.t("err_not_enough")
        c.contains("NOT_ACTIVE") || c.contains("LOCKED") || c.contains("CLOSED") || c.contains("ENDED") -> AppState.t("pred_closed")
        c.contains("OUTCOME") || c.contains("MULTIPLE") || c.contains("DIFFERENT") -> AppState.t("pred_one_outcome")
        c.contains("MAX") || c.contains("LIMIT") -> AppState.t("pred_max")
        else -> "${AppState.t("points_error")} : $code"
    }
}

// ── Hype train ──
@Composable
private fun HypeCard(h: LiveHype, now: Long) {
    val name = when (h.variant) {
        "golden" -> AppState.t("hype_golden")
        "treasure" -> AppState.t("hype_treasure")
        else -> AppState.t("hype_train")
    }
    Column(Modifier.fillMaxWidth()) {
        when (h.flash) {
            "departed" -> {
                Text(AppState.t("hype_departed"), color = TU.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(AppState.t("hype_departed_sub"), color = TU.muted, fontSize = 11.sp)
            }
            "level" -> Text(AppState.t("hype_level_done").replace("{n}", "${h.flashLevel}"), color = TU.text,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
            "ended" -> Text(AppState.t("hype_ended").replace("{n}", "${h.flashLevel}").replace("{p}", "${h.flashPercent}"),
                            color = TU.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (h.approaching) "$name · ${AppState.t("hype_incoming")}" else "$name · ${AppState.t("hype_level")} ${h.level}",
                         color = TU.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val ends = h.endsAt
                    if (ends != null && ends > now) {
                        Text(Format.clock((ends - now) / 1000), color = TU.live, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (h.approaching) {
                    Text(AppState.t("hype_kickoff").replace("{n}", "${h.left}"), color = TU.muted, fontSize = 11.sp)
                } else {
                    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(TU.live.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(h.percent.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(TU.live))
                    }
                    Text("${(h.percent * 100).toInt()} %", color = TU.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

// ── Points de chaîne ──
@Composable
private fun PointsBar(points: ChannelPoints, events: LiveEvents, onOpenRewards: () -> Unit, onWebLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    if (points.rewards.isEmpty() && !points.balanceKnown && !points.needsWebLogin) return
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (Features.showWatchStreak.value && events.watchStreak > 0) {
            Text("🔥 ${events.watchStreak}", color = TU.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpenRewards() }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("◎ ", color = purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            when {
                points.needsWebLogin -> Text(AppState.t("points_title"), color = TU.text, fontSize = 13.sp)
                points.balanceKnown -> Text(ChannelPoints.format(points.balance) + " " + AppState.tn("points_unit", points.balance),
                                            color = TU.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else -> Text("…", color = TU.muted, fontSize = 13.sp)
            }
            if (points.lastGain > 0) {
                Text("  +${points.lastGain}", color = TU.success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        when {
            points.needsWebLogin -> TextButton(onClick = onWebLogin) {
                Text(AppState.t(if (WebSession.expired) "web_reconnect" else "web_banner_btn"), color = TU.primary, fontSize = 13.sp)
            }
            points.pendingClaimId != null -> Button(
                onClick = { scope.launch { points.claimBonus() } },
                colors = ButtonDefaults.buttonColors(containerColor = purple),
                modifier = Modifier.height(32.dp),
            ) { Text("🎁 " + AppState.t("points_claim_bonus"), fontSize = 12.sp) }
            else -> TextButton(onClick = onOpenRewards) { Text(AppState.t("points_redeem"), color = TU.primary, fontSize = 13.sp) }
        }
    }
}

/** Récompenses de la chaîne, à la place du chat tant qu'il est ouvert. */
@Composable
fun RewardsPanel(points: ChannelPoints, modifier: Modifier = Modifier, onClose: () -> Unit, onWebLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<ChannelReward?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    Column(modifier.background(TU.bg)) {
        Row(Modifier.fillMaxWidth().background(TU.surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppState.t("points_title"), color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (points.balanceKnown) {
                Text("◎ " + ChannelPoints.format(points.balance), color = purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text) }
        }
        if (points.needsWebLogin) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(AppState.t("points_connect_title"), color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(AppState.t("points_connect_desc"), color = TU.muted, fontSize = 12.sp)
                Button(onClick = onWebLogin, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                       modifier = Modifier.padding(top = 8.dp)) { Text(AppState.t("points_connect_btn")) }
            }
        }
        done?.let { Text(it, color = TU.success, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        points.error?.let { Text(it, color = TU.live, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        when {
            points.loading && points.rewards.isEmpty() -> Text(AppState.t("points_loading"), color = TU.muted, fontSize = 13.sp,
                                                               modifier = Modifier.padding(14.dp))
            points.rewards.isEmpty() -> Text(AppState.t("points_none"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(points.rewards, key = { it.id }) { r ->
                    RewardRow(r, points) {
                        if (r.isUserInputRequired) {
                            asking = r
                        } else {
                            scope.launch {
                                done = null
                                if (points.redeem(r, null)) done = "« ${r.title} » ${AppState.t("points_redeemed")}"
                            }
                        }
                    }
                }
            }
        }
    }
    val r = asking
    if (r != null) {
        var text by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(r.title) },
            text = {
                Column {
                    r.prompt?.takeIf { it.isNotEmpty() }?.let { Text(it, color = TU.muted, fontSize = 13.sp) }
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= 200) text = it },
                                      placeholder = { Text(AppState.t("points_input_ph"), color = TU.muted) },
                                      colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch {
                        done = null
                        if (points.redeem(r, text)) done = "« ${r.title} » ${AppState.t("points_redeemed")}"
                    }
                }, enabled = text.isNotBlank()) { Text(AppState.t("points_redeem"), color = TU.primary) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(AppState.t("sleep_cancel"), color = TU.text) } },
            containerColor = TU.surface, titleContentColor = TU.text, textContentColor = TU.text,
        )
    }
}

@Composable
private fun RewardRow(r: ChannelReward, points: ChannelPoints, onRedeem: () -> Unit) {
    val bg = try { Color(android.graphics.Color.parseColor("#" + r.backgroundColor)) } catch (e: Exception) { purple }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
            if (r.imageUrl != null) {
                AsyncImage(model = r.imageUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("◎ " + ChannelPoints.format(r.cost), color = purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        val status = when {
            r.isPaused -> AppState.t("points_paused")
            !r.isInStock -> AppState.t("points_out_of_stock")
            points.balanceKnown && points.balance < r.cost -> AppState.t("points_insufficient")
            else -> null
        }
        if (status != null) {
            Text(status, color = TU.muted, fontSize = 12.sp)
        } else {
            Button(onClick = onRedeem, enabled = r.canRedeem && points.redeeming == null && points.balanceKnown,
                   colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                if (points.redeeming == r.id) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Text(AppState.t("points_redeem"), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TU.primary, unfocusedBorderColor = TU.line, cursorColor = TU.primary,
    focusedTextColor = TU.text, unfocusedTextColor = TU.text,
)
