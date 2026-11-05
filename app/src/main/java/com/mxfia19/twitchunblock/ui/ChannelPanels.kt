package com.mxfia19.twitchunblock.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.ChannelAbout
import com.mxfia19.twitchunblock.core.ChannelExtras
import com.mxfia19.twitchunblock.core.ChannelSchedule
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.ScheduleSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Planning et « À propos » d'une chaîne (onglets de sa page, téléphone). */
class ChannelExtrasModel(val login: String, private val scope: CoroutineScope) {
    /** Planning : null tant qu'il n'est pas lu (ou si Twitch n'a pas répondu). */
    var schedule by mutableStateOf<ChannelSchedule?>(null)
        private set
    private var scheduleAsked = false
    var about by mutableStateOf<ChannelAbout?>(null)
        private set
    var aboutLoading by mutableStateOf(false)
        private set
    var aboutFailed by mutableStateOf(false)
        private set

    /** Lu à l'ouverture de la page : l'onglet n'apparaît que s'il y a un planning. */
    fun loadSchedule(channelId: String?) {
        if (scheduleAsked) return
        scheduleAsked = true
        scope.launch { schedule = ChannelExtras.schedule(login, channelId) }
    }

    val hasSchedule: Boolean get() = schedule?.isEmpty() == false

    fun loadAbout() {
        if (about != null || aboutLoading) return
        aboutLoading = true
        aboutFailed = false
        scope.launch {
            val a = ChannelExtras.about(login)
            about = a
            aboutFailed = a == null
            aboutLoading = false
        }
    }
}

// ── Dates du planning (jour et heure de l'appareil, langue de l'app) ──
private fun appLocale(): Locale = Locale.forLanguageTag(AppState.lang.code)

private fun pattern(skeleton: String): String =
    android.text.format.DateFormat.getBestDateTimePattern(appLocale(), skeleton)

private fun format(ms: Long, skeleton: String): String = SimpleDateFormat(pattern(skeleton), appLocale()).format(Date(ms))

/** « aujourd'hui », « demain », « mardi » dans la semaine, « mardi 28 oct. » au-delà. */
fun scheduleDay(ms: Long): String {
    val now = Calendar.getInstance()
    val at = Calendar.getInstance().apply { timeInMillis = ms }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(now, at)) return AppState.t("sched_today")
    if (same((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }, at)) return AppState.t("sched_tomorrow")
    return format(ms, if (ms - System.currentTimeMillis() < 6 * 86_400_000L) "EEEE" else "EEEEdMMM")
}

/** Heure en 12 h ou 24 h, comme l'horloge de l'appareil. */
fun scheduleTime(ms: Long, context: Context): String {
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    return format(ms, if (twentyFour) "Hm" else "hm")
}

/** « En vacances jusqu'au 28 oct. », ou « du 20 oct. au 28 oct. » à venir. */
fun vacationText(v: Pair<Long, Long>): String {
    if (v.first <= System.currentTimeMillis()) return AppState.t("sched_vacation_until").replace("{d}", format(v.second, "dMMM"))
    return AppState.t("sched_vacation_range").replace("{a}", format(v.first, "dMMM")).replace("{b}", format(v.second, "dMMM"))
}

/** Ligne d'une chaîne hors ligne : ses vacances en cours, sinon « Prochain
 *  live : mardi · 20:00 ». null sans rien de prévu. */
fun scheduleHeadline(s: ChannelSchedule, context: Context): String? {
    val now = System.currentTimeMillis()
    s.activeVacation(now)?.takeIf { it.first <= now }?.let { return vacationText(it) }
    val next = s.next(now) ?: return null
    return AppState.t("sched_next").replace("{d}", scheduleDay(next.start) + " · " + scheduleTime(next.start, context))
}

@Composable
fun ScheduleHeadline(s: ChannelSchedule) {
    val text = scheduleHeadline(s, LocalContext.current) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Icon(Icons.Filled.DateRange, contentDescription = null, tint = TU.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = TU.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Onglet « Planning » : vacances en tête, puis les 20 prochains créneaux. */
@Composable
fun ScheduleTab(s: ChannelSchedule) {
    val now = System.currentTimeMillis()
    val segments = s.upcoming(now).take(20)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.activeVacation(now)?.let { v ->
            Text("✈ " + vacationText(v), color = TU.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TU.surface).padding(12.dp))
        }
        if (segments.isEmpty()) {
            MessageBox(AppState.t("sched_none"))
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TU.surface)) {
                segments.forEachIndexed { i, seg ->
                    SegmentRow(seg)
                    if (i < segments.lastIndex) HorizontalDivider(color = TU.line, modifier = Modifier.padding(start = 68.dp))
                }
            }
            // Le streamer annonce ses heures dans son fuseau : converties, on le dit une fois.
            Text("🕒 " + AppState.t("sched_local_hint"), color = TU.muted, fontSize = 12.sp)
        }
    }
}

/** Un créneau : le jour façon calendrier, puis titre, heures et durée,
 *  catégorie, répétition ou annulation. */
@Composable
private fun SegmentRow(seg: ScheduleSegment) {
    val context = LocalContext.current
    val title = seg.title.ifEmpty { seg.category.ifEmpty { AppState.t("sched_untitled") } }
    val day = scheduleDay(seg.start).replaceFirstChar { it.titlecase(appLocale()) }
    var whenText = day + " · " + scheduleTime(seg.start, context)
    val end = seg.end
    if (end != null && end > seg.start) {
        val minutes = ((end - seg.start) / 60_000).toInt()
        val h = minutes / 60
        val m = minutes % 60
        val length = when {
            h == 0 -> "$m ${AppState.t("min")}"
            m > 0 -> "$h ${AppState.t("hour")} " + "%02d".format(m)
            else -> "$h ${AppState.t("hour")}"
        }
        whenText += " – " + scheduleTime(end, context) + " · " + length
    }
    Row(Modifier.fillMaxWidth().alpha(if (seg.canceled) 0.6f else 1f).padding(12.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp)).background(TU.surfaceHi)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(format(seg.start, "EEE").uppercase(appLocale()), color = TU.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1)
            Text(format(seg.start, "d"), color = TU.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                 textDecoration = if (seg.canceled) TextDecoration.LineThrough else null)
            Text(whenText, color = TU.muted, fontSize = 12.sp, maxLines = 2)
            // Catégorie sous le titre, si le titre n'est pas déjà elle.
            if (seg.category.isNotEmpty() && title != seg.category) {
                Text(seg.category, color = TU.primary, fontSize = 12.sp, maxLines = 1)
            }
            if (seg.recurring || seg.canceled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (seg.recurring) Text("↻ " + AppState.t("sched_weekly"), color = TU.muted, fontSize = 12.sp)
                    if (seg.canceled) Text("✕ " + AppState.t("sched_canceled"), color = TU.danger, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── À propos ──
private val markdownLink = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
private val boldText = Regex("""\*\*([^*\n]+)\*\*""")
private val bareUrl = Regex("""https?://[^\s<>()\[\]"']+""")

/** Texte avec ses liens web (adresses nues ; en Markdown aussi les liens
 *  [texte](adresse) et le **gras**). Rien d'autre qu'http(s). */
fun richText(raw: String, markdown: Boolean, linkColor: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
    val listener = LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.let { open(it.url) } }
    var i = 0
    while (i < raw.length) {
        val md = if (markdown) markdownLink.find(raw, i) else null
        val b = if (markdown) boldText.find(raw, i) else null
        val u = bareUrl.find(raw, i)
        val next = listOfNotNull(md, b, u).minByOrNull { it.range.first } ?: break
        append(raw.substring(i, next.range.first))
        when {
            next === md -> withLink(LinkAnnotation.Url(next.groupValues[2], styles, listener)) { append(next.groupValues[1]) }
            next === b -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(next.groupValues[1]) }
            else -> {
                // Ponctuation collée à la fin d'une adresse : hors du lien.
                val url = next.value.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, styles, listener)) { append(url) }
                append(next.value.substring(url.length))
            }
        }
        i = next.range.last + 1
    }
    if (i < raw.length) append(raw.substring(i))
}

/** Texte d'un panneau (Markdown de Twitch) : citations et titres simplifiés,
 *  puces en « • », liens et gras. */
fun panelText(raw: String, linkColor: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val lines = raw.replace("\r\n", "\n").split('\n')
    lines.forEachIndexed { i, source ->
        var line = source.replace(Regex("""^\s*>\s?"""), "")
        val heading = Regex("""^#{1,6}\s+""").containsMatchIn(line)
        if (heading) line = line.replace(Regex("""^#{1,6}\s+"""), "")
        line = line.replace(Regex("""^\s*[-*+]\s+"""), "• ")
        val part = richText(line, markdown = true, linkColor = linkColor, open = open)
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TU.text)) { append(part) } else append(part)
        if (i < lines.lastIndex) append("\n")
    }
}

/** Onglet « À propos » : description, followers, réseaux, panneaux du streamer. */
@Composable
fun AboutTab(about: ChannelAbout, name: String) {
    val open = rememberLinkOpener()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TU.surface).padding(12.dp),
               verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppState.t("about_channel").replace("{u}", name), color = TU.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            about.followers?.let { f ->
                Text(Format.viewers(f) + " " + AppState.tn("followers", f, abbreviated = f >= 1000), color = TU.muted, fontSize = 13.sp)
            }
            if (about.description.isEmpty()) {
                Text(AppState.t("about_empty"), color = TU.muted, fontSize = 13.sp)
            } else {
                Text(richText(about.description, markdown = false, linkColor = TU.primary, open = open), color = TU.text, fontSize = 14.sp)
            }
            if (about.socials.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in about.socials) {
                        Text("🔗 " + s.title, color = TU.text, fontSize = 13.sp, maxLines = 1,
                             modifier = Modifier.focusCard({ open(s.url) }, RoundedCornerShape(16.dp)).background(TU.surfaceHi)
                                 .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        for (p in about.panels) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TU.surface).padding(12.dp),
                   verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.image?.let { img ->
                    // L'image du panneau mène à son lien, comme sur Twitch.
                    val link = p.link
                    AsyncImage(model = img, contentDescription = p.title.ifEmpty { null }, contentScale = ContentScale.FillWidth,
                               modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                   .then(if (link != null) Modifier.focusCard({ open(link) }) else Modifier))
                }
                if (p.title.isNotEmpty()) Text(p.title, color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (p.text.isNotEmpty()) Text(panelText(p.text, TU.primary, open), color = TU.muted, fontSize = 13.sp)
            }
        }
    }
}
