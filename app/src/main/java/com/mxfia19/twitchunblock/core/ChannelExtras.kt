package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Fiche « À propos » d'une chaîne : description, followers, réseaux, panneaux. */
data class ChannelAbout(val description: String, val followers: Int?, val socials: List<Social>, val panels: List<Panel>) {
    data class Social(val title: String, val url: String)
    data class Panel(val id: String, val title: String, val image: String?, val link: String?, val text: String)
}

/** Créneau du planning (ms). Sans fin connue : une heure comptée. */
data class ScheduleSegment(val id: String, val start: Long, val end: Long?, val title: String, val category: String,
                           val recurring: Boolean, val canceled: Boolean)

/** Planning d'une chaîne : créneaux du plus proche au plus lointain, vacances (ms). */
data class ChannelSchedule(val segments: List<ScheduleSegment>, val vacation: Pair<Long, Long>? = null) {
    fun upcoming(now: Long = System.currentTimeMillis()) = segments.filter { (it.end ?: (it.start + 3_600_000)) > now }
    /** Prochain live maintenu (ni annulé ni fini). */
    fun next(now: Long = System.currentTimeMillis()) = upcoming(now).firstOrNull { !it.canceled }
    /** Vacances en cours ou à venir. */
    fun activeVacation(now: Long = System.currentTimeMillis()) = vacation?.takeIf { it.second > now }
    fun isEmpty(now: Long = System.currentTimeMillis()) = upcoming(now).isEmpty() && activeVacation(now) == null

    companion object {
        val EMPTY = ChannelSchedule(emptyList())
    }
}

/**
 * Onglets « Planning » et « À propos » de la page d'une chaîne, comme
 * getChannelSchedule et getChannelAbout (iOS). Planning : Helix avec le
 * compte, sinon GQL public, et l'export iCalendar public s'il ne donne rien.
 */
object ChannelExtras {
    /** Adresse web seulement (ni javascript:, ni mailto:…). */
    fun webUrl(v: String?): String? {
        val s = v?.trim() ?: return null
        val scheme = s.substringBefore(':', "").lowercase()
        return if ((scheme == "http" || scheme == "https") && s.length <= 2000) s else null
    }

    suspend fun about(login: String): ChannelAbout? {
        val q = "query(\$l: String!) { user(login: \$l) { description followers { totalCount } " +
            "channel { socialMedias { id name title url } } " +
            "panels { id type ... on DefaultPanel { title imageURL linkURL description } } } }"
        val u = Gql.request(q, Gql.vars("l" to login.lowercase()))?.obj("user") ?: return null
        val socials = u.obj("channel")?.arr("socialMedias")?.objects().orEmpty().mapNotNull { s ->
            val url = webUrl(s.str("url")) ?: return@mapNotNull null
            val name = s.str("name") ?: ""
            ChannelAbout.Social((s.str("title") ?: "").ifEmpty { name }.ifEmpty { url }, url)
        }
        val panels = u.arr("panels")?.objects().orEmpty().mapNotNull { p ->
            if (p.str("type") != "DEFAULT") return@mapNotNull null
            val title = p.str("title") ?: ""
            val text = p.str("description") ?: ""
            val image = webUrl(p.str("imageURL"))
            if (title.isEmpty() && text.isEmpty() && image == null) return@mapNotNull null
            ChannelAbout.Panel(p.str("id") ?: (title + text).hashCode().toString(), title, image, webUrl(p.str("linkURL")), text)
        }
        return ChannelAbout(u.str("description") ?: "", u.obj("followers")?.int("totalCount"), socials, panels)
    }

    // ── Planning ──
    suspend fun schedule(login: String, channelId: String?): ChannelSchedule? {
        val l = login.lowercase()
        var id = channelId ?: ""
        if (AppState.token != null && id.isNotEmpty()) {
            helixSchedule(id)?.let { log(l, it, "Helix"); return it }
        }
        val fromGql = gqlSchedule(l)
        if (fromGql != null && !fromGql.isEmpty()) {
            log(l, fromGql, "GQL")
            return fromGql
        }
        // GQL refuse la requête ou ne donne rien : l'export iCalendar tranche.
        if (id.isEmpty()) {
            id = Gql.request("query(\$l: String!) { user(login: \$l) { id } }", Gql.vars("l" to l))?.obj("user")?.str("id") ?: ""
        }
        if (id.isNotEmpty()) icalSchedule(id)?.let { log(l, it, "iCalendar"); return it }
        if (fromGql == null) Logger.warn("PLANNING", "Planning de \"$l\" indisponible")
        return fromGql
    }

    /** Journal : seulement un planning qui a quelque chose à montrer. */
    private fun log(login: String, s: ChannelSchedule, source: String) {
        if (!s.isEmpty()) Logger.info("PLANNING", "Planning de \"$login\" : ${s.upcoming().size} créneaux à venir", source)
    }

    /** Helix, avec le jeton du compte. 404 : pas de planning. */
    private suspend fun helixSchedule(channelId: String): ChannelSchedule? {
        if (!channelId.all { it.isDigit() }) return null
        val r = Auth.helix("https://api.twitch.tv/helix/schedule?broadcaster_id=$channelId&first=25") ?: return null
        if (r.code == 404) return ChannelSchedule.EMPTY
        val d = if (r.code == 200) parseJson(r.body)?.obj("data") else null
        if (d == null) {
            Logger.warn("PLANNING", "Planning refusé par Helix", "HTTP ${r.code}")
            return null
        }
        val segments = d.arr("segments")?.objects().orEmpty().mapNotNull { s ->
            val start = Format.parseDate(s.str("start_time")) ?: return@mapNotNull null
            ScheduleSegment("${s.str("id") ?: ""}-${start / 1000}", start, Format.parseDate(s.str("end_time")),
                            s.str("title") ?: "", s.obj("category")?.str("name") ?: "",
                            s.bool("is_recurring") ?: false,
                            // Annulé par le streamer, ou pendant ses vacances.
                            s.str("canceled_until") != null)
        }
        val v = d.obj("vacation")
        val vs = Format.parseDate(v?.str("start_time"))
        val ve = Format.parseDate(v?.str("end_time"))
        return ChannelSchedule(segments.sortedBy { it.start }, if (vs != null && ve != null) Pair(vs, ve) else null)
    }

    /** GQL public : une requête par semaine (Twitch n'en sert qu'une à la
     *  fois), celle en cours, puis les trois suivantes s'il y a un prochain
     *  live ou des vacances. `repeatEndsAfterCount` à 1 : créneau unique. */
    private suspend fun gqlSchedule(login: String): ChannelSchedule? = coroutineScope {
        val now = System.currentTimeMillis()
        val week = 7 * 86_400_000L
        fun scheduleOf(d: org.json.JSONObject) = d.obj("user")?.obj("channel")?.obj("schedule")
        val first = gqlWeek(login, now, "nextSegment { id } interruption { startAt endAt }") ?: return@coroutineScope null
        val schedule = scheduleOf(first) ?: return@coroutineScope ChannelSchedule.EMPTY
        val weeks = mutableListOf(schedule)
        if (schedule.obj("nextSegment") != null || schedule.obj("interruption") != null) {
            val rest = (1..3).map { k -> async { gqlWeek(login, now + k * week) } }.map { it.await() }
            for (data in rest) {
                if (data == null) return@coroutineScope null
                scheduleOf(data)?.let { weeks += it }
            }
        }
        // Douze heures en arrière (un créneau en cours), quatre semaines devant.
        val from = now - 12 * 3_600_000L
        val to = now + 28 * 86_400_000L
        val seen = HashSet<String>()
        val segments = mutableListOf<ScheduleSegment>()
        for (w in weeks) {
            for (s in w.arr("segments")?.objects().orEmpty()) {
                val start = Format.parseDate(s.str("startAt")) ?: continue
                val end = Format.parseDate(s.str("endAt"))
                if ((end ?: (start + 3_600_000)) <= from || start >= to) continue
                // Un créneau répété garde son id d'une semaine à l'autre : l'heure le distingue.
                val id = "${s.str("id") ?: ""}-${start / 1000}"
                if (!seen.add(id)) continue
                val repeats = s.int("repeatEndsAfterCount")
                segments += ScheduleSegment(id, start, end, s.str("title") ?: "",
                                            s.arr("categories")?.objects()?.firstOrNull()?.str("displayName") ?: "",
                                            repeats != null && repeats != 1, s.bool("isCancelled") ?: false)
            }
        }
        val v = schedule.obj("interruption")
        val vs = Format.parseDate(v?.str("startAt"))
        val ve = Format.parseDate(v?.str("endAt"))
        ChannelSchedule(segments.sortedBy { it.start }, if (vs != null && ve != null) Pair(vs, ve) else null)
    }

    /** Une semaine du planning (celle qui contient `at`) ; null si Twitch n'a
     *  pas répondu ou refuse la requête, même en partie. */
    private suspend fun gqlWeek(login: String, at: Long, extra: String = ""): org.json.JSONObject? {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // Date calculée ici, jamais saisie : écrite dans la requête.
        val q = "query(\$l: String!) { user(login: \$l) { channel { schedule { $extra " +
            "segments(relativeDate: \"${iso.format(Date(at))}\") { id startAt endAt title isCancelled " +
            "repeatEndsAfterCount categories { displayName } } } } } }"
        val json = Gql.raw(q, Gql.vars("l" to login), timeout = 15) ?: return null
        if ((json.arr("errors")?.length() ?: 0) > 0) {
            Logger.warn("GQL", "Requête refusée", Gql.errorSummary(json))
            return null
        }
        return json.obj("data") ?: org.json.JSONObject()
    }

    /** Export iCalendar public de Helix (sans jeton). 404 : pas de planning. */
    private suspend fun icalSchedule(channelId: String): ChannelSchedule? {
        if (!channelId.all { it.isDigit() }) return null
        val bytes = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("https://api.twitch.tv/helix/schedule/icalendar?broadcaster_id=$channelId")
                    .header("User-Agent", Config.USER_AGENT).build()
                Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    when (r.code) {
                        200 -> r.body?.bytes()
                        404 -> ByteArray(0)
                        else -> {
                            Logger.warn("PLANNING", "Export iCalendar refusé", "HTTP ${r.code}")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        } ?: return null
        if (bytes.isEmpty()) return ChannelSchedule.EMPTY
        return parseIcal(unfold(bytes), System.currentTimeMillis())
    }

    /** Lignes repliées (RFC 5545, 3.1) recollées sur les octets, avant le
     *  décodage : un repli peut couper en deux un caractère UTF-8. */
    private fun unfold(raw: ByteArray): String {
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val eol = when {
                raw[i] == 10.toByte() -> 1
                raw[i] == 13.toByte() && i + 1 < raw.size && raw[i + 1] == 10.toByte() -> 2
                else -> 0
            }
            if (eol > 0 && i + eol < raw.size && (raw[i + eol] == 32.toByte() || raw[i + eol] == 9.toByte())) {
                i += eol + 1
                continue
            }
            out.write(raw[i].toInt())
            i++
        }
        return out.toString("UTF-8")
    }

    private class Prop(val params: Map<String, String>, val value: String)

    /** Créneaux des quatre prochaines semaines : chaque VEVENT et ses
     *  répétitions (RRULE quotidienne ou hebdomadaire, EXDATE, occurrences
     *  modifiées à part par RECURRENCE-ID). */
    fun parseIcal(text: String, now: Long): ChannelSchedule {
        val from = now - 12 * 3_600_000L
        val to = now + 28 * 86_400_000L
        val events = mutableListOf<Pair<Map<String, Prop>, List<Long>>>()
        var event: MutableMap<String, Prop>? = null
        var exdates = mutableListOf<Long>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (line.isBlank()) continue
            val upper = line.trim().uppercase()
            if (upper == "BEGIN:VEVENT") { event = HashMap(); exdates = mutableListOf(); continue }
            if (upper == "END:VEVENT") { event?.let { events += Pair(it, exdates) }; event = null; continue }
            val e = event ?: continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val parts = line.substring(0, colon).split(';')
            val name = parts[0].uppercase()
            val params = HashMap<String, String>()
            for (p in parts.drop(1)) {
                val kv = p.split('=')
                if (kv.size == 2) params[kv[0].uppercase()] = kv[1].replace("\"", "")
            }
            val value = line.substring(colon + 1)
            if (name == "EXDATE") exdates += value.split(',').mapNotNull { icalDate(it, params["TZID"]) }
            else e[name] = Prop(params, value)
        }
        // Occurrence d'une série modifiée ou annulée à part (même UID, avec
        // RECURRENCE-ID) : elle remplace celle que donnerait la répétition.
        val replaced = HashMap<String, MutableList<Long>>()
        for ((props, _) in events) {
            val r = props["RECURRENCE-ID"] ?: continue
            val uid = props["UID"]?.value ?: continue
            val at = icalDate(r.value, r.params["TZID"]) ?: continue
            replaced.getOrPut(uid) { mutableListOf() } += at
        }
        val segments = mutableListOf<ScheduleSegment>()
        for ((props, ex) in events) {
            val excluded = ex.toMutableList()
            if (props["RECURRENCE-ID"] == null) props["UID"]?.value?.let { replaced[it]?.let { r -> excluded += r } }
            segments += occurrences(props, excluded, from, to)
        }
        return ChannelSchedule(segments.sortedBy { it.start })
    }

    private fun occurrences(e: Map<String, Prop>, exdates: List<Long>, from: Long, to: Long): List<ScheduleSegment> {
        val startProp = e["DTSTART"] ?: return emptyList()
        val start = icalDate(startProp.value, startProp.params["TZID"]) ?: return emptyList()
        val end = e["DTEND"]?.let { icalDate(it.value, it.params["TZID"]) }
        val duration = end?.let { it - start }
        val uid = e["UID"]?.value ?: ""
        val title = icalText(e["SUMMARY"]?.value ?: "")
        val category = icalText(e["CATEGORIES"]?.value ?: "")
        val canceled = e["STATUS"]?.value?.trim()?.uppercase() == "CANCELLED"
        fun segment(at: Long, recurring: Boolean) =
            ScheduleSegment("$uid-${at / 1000}", at, duration?.let { at + it }, title, category, recurring, canceled)
        val rule = HashMap<String, String>()
        for (part in (e["RRULE"]?.value ?: "").split(';')) {
            val kv = part.split('=')
            if (kv.size == 2) rule[kv[0].uppercase()] = kv[1]
        }
        val stepDays = when (rule["FREQ"]?.uppercase()) { "DAILY" -> 1; "WEEKLY" -> 7; else -> 0 }
        if (stepDays == 0) {
            val last = end ?: (start + 3_600_000)
            return if (last > from && start < to) listOf(segment(start, false)) else emptyList()
        }
        // Calendrier du fuseau du créneau : même heure d'une semaine à l'autre,
        // passage à l'heure d'été compris. INTERVAL borné.
        val step = stepDays * (rule["INTERVAL"]?.toIntOrNull() ?: 1).coerceIn(1, 1000)
        val count = rule["COUNT"]?.toIntOrNull()
        val until = rule["UNTIL"]?.let { icalDate(it, startProp.params["TZID"]) }
        val cal = Calendar.getInstance(icalZone(startProp.params["TZID"], startProp.value))
        // Droit vers la fenêtre, sans parcourir des années de répétitions.
        var k = maxOf(0L, (from - start) / (step * 86_400_000L) - 1).toInt()
        val out = mutableListOf<ScheduleSegment>()
        while (out.size < 60) {
            if (count != null && k >= count) break
            cal.timeInMillis = start
            cal.add(Calendar.DAY_OF_MONTH, k * step)
            val at = cal.timeInMillis
            if (at >= to) break
            if (until != null && at > until) break
            val last = duration?.let { at + it } ?: (at + 3_600_000)
            if (last > from && exdates.none { kotlin.math.abs(it - at) < 60_000 }) out += segment(at, true)
            k++
        }
        return out
    }

    /** « 20261020T200000Z » (UTC), « 20261020T200000 » dans le fuseau du TZID, ou « 20261020 ». */
    private fun icalDate(raw: String, tzid: String?): Long? {
        val v = raw.trim()
        val pattern = when {
            v.endsWith("Z") -> "yyyyMMdd'T'HHmmss'Z'"
            v.length == 8 -> "yyyyMMdd"
            else -> "yyyyMMdd'T'HHmmss"
        }
        return try {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = icalZone(tzid, v); isLenient = false }.parse(v)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** Fuseau : UTC pour « …Z », sinon celui du TZID (« /America/New_York »
     *  chez Twitch), UTC à défaut. */
    private fun icalZone(tzid: String?, value: String): TimeZone {
        val utc = TimeZone.getTimeZone("UTC")
        if (value.endsWith("Z") || tzid.isNullOrEmpty()) return utc
        val id = tzid.removePrefix("/")
        fun known(z: String): TimeZone? = TimeZone.getTimeZone(z).takeIf { it.id == z }
        // Préfixe d'éditeur (« /mozilla.org/…/Europe/Paris ») : les deux derniers éléments.
        return known(id) ?: known(id.split('/').takeLast(2).joinToString("/")) ?: utc
    }

    /** Texte iCalendar : « \, », « \; » et « \\ » rendus, retours à la ligne en espaces. */
    private fun icalText(raw: String): String {
        val out = StringBuilder()
        var escaped = false
        for (ch in raw) {
            when {
                escaped -> { out.append(if (ch == 'n' || ch == 'N') ' ' else ch); escaped = false }
                ch == '\\' -> escaped = true
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }
}
