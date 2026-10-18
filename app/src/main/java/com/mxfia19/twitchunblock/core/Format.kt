package com.mxfia19.twitchunblock.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Format {
    /** « 1.2k » au-delà de 1 000, comme l'app iPhone. */
    fun viewers(n: Int): String =
        if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

    /** « 2h 05min » ou « 45min ». */
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}min" else "${m}min"
    }

    /** « 1:02:03 » ou « 2:03 ». */
    fun clock(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Date ISO de Twitch (avec ou sans fractions de seconde) en ms. */
    fun parseDate(s: String?): Long? {
        if (s == null || s.length < 19) return null
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.substring(0, 19))?.time
        } catch (e: Exception) {
            null
        }
    }

    /** Durée depuis une date : « 3 h », « 12 min », « 4 j », dans la langue de l'app. */
    fun since(ms: Long): String {
        val diff = (System.currentTimeMillis() - ms) / 1000
        if (diff < 3600) return AppState.t("minutes_ago").replace("{n}", maxOf(1, diff / 60).toString())
        if (diff < 86400) return AppState.t("hours_ago").replace("{n}", (diff / 3600).toString())
        return AppState.t("days_ago").replace("{n}", (diff / 86400).toString())
    }

    /** Durée du direct depuis son début. */
    fun uptime(startedAt: String?): String? {
        val start = parseDate(startedAt) ?: return null
        return clock((System.currentTimeMillis() - start) / 1000)
    }

    /** Date courte dans la langue de l'app (« 12 oct. 2026 »). */
    fun shortDate(ms: Long): String {
        val f = SimpleDateFormat("d MMM yyyy", Locale(AppState.lang.code))
        return f.format(Date(ms))
    }
}
