package com.mxfia19.twitchunblock.points

import androidx.compose.runtime.mutableStateListOf
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suivi des points de chaîne (Réglages → Points de chaîne) : par chaîne, les
 * minutes que Twitch a comptées, les points gagnés en regardant et les
 * coffres réclamés. Gardé sur l'appareil. Comme PointsTracker (iOS).
 */
object PointsTracker {
    data class Stat(val login: String, val minutes: Int, val points: Int, val chests: Int, val last: Long)

    /** Les chaînes, la plus récente d'abord. Fil principal. */
    val stats = mutableStateListOf<Stat>()

    fun init() {
        stats.clear()
        parseJsonArray(AppState.prefs.getString("points_tracker", null))?.objects()?.forEach { o ->
            val login = o.str("login") ?: return@forEach
            stats += Stat(login, o.int("m") ?: 0, o.int("p") ?: 0, o.int("c") ?: 0, o.long("t") ?: 0L)
        }
    }

    fun begin(login: String) = touch(login) { it }

    fun end(login: String) {
        // Rien à clore : chaque minute et chaque gain sont gardés dès qu'ils arrivent.
    }

    fun addMinute(login: String) = touch(login) { it.copy(minutes = it.minutes + 1) }

    fun addGain(points: Int, login: String) {
        if (points > 0) touch(login) { it.copy(points = it.points + points) }
    }

    fun addChest(login: String) = touch(login) { it.copy(chests = it.chests + 1) }

    val totalMinutes: Int get() = stats.sumOf { it.minutes }
    val totalPoints: Int get() = stats.sumOf { it.points }

    fun clear() {
        stats.clear()
        AppState.prefs.edit().remove("points_tracker").apply()
    }

    private fun touch(login: String, change: (Stat) -> Stat) {
        val l = login.lowercase()
        if (l.isEmpty()) return
        val i = stats.indexOfFirst { it.login == l }
        val old = if (i >= 0) stats.removeAt(i) else Stat(l, 0, 0, 0, 0L)
        stats.add(0, change(old).copy(last = System.currentTimeMillis()))
        while (stats.size > 100) stats.removeAt(stats.lastIndex)
        val arr = JSONArray()
        for (s in stats) {
            arr.put(JSONObject().put("login", s.login).put("m", s.minutes).put("p", s.points).put("c", s.chests).put("t", s.last))
        }
        AppState.prefs.edit().putString("points_tracker", arr.toString()).apply()
    }
}
