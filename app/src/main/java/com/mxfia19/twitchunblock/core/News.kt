package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Annonce du développeur et sondage en cours (Worker), en haut de
 *  l'accueil, comme sur l'iPhone et le site. */
object News {
    data class Announcement(val id: String, val title: String, val message: String, val link: String?, val until: Long)
    data class Poll(
        val id: String,
        val question: String,
        val options: List<String>,
        val multi: Boolean,
        val until: Long,
        val ended: Boolean,
        val mine: List<Int>,
        val counts: List<Int>?,
        val voters: Int,
    )

    var announcement by mutableStateOf<Announcement?>(null)
        private set
    var poll by mutableStateOf<Poll?>(null)
        private set
    /** Réaction donnée à l'annonce (emoji), gardée sur l'appareil. */
    var reaction by mutableStateOf<String?>(null)
        private set
    var voting by mutableStateOf(false)
        private set

    val REACTIONS = listOf("👍", "❤️", "🔥", "😂", "👎")
    private const val DISMISSED = "news_dismissed"
    private const val REACTED = "news_reaction_"

    suspend fun refresh() {
        val a = parseJson(Http.get("${Config.API_URL}/api/announcement", timeout = 10)?.body)?.obj("announcement")
        announcement = a?.let {
            val id = it.str("id") ?: return@let null
            if (AppState.prefs.getString(DISMISSED, null) == id) return@let null
            Announcement(id, it.str("title") ?: "", it.str("message") ?: "", it.str("link"), it.long("until") ?: 0L)
        }
        reaction = announcement?.let { AppState.prefs.getString(REACTED + it.id, null) }
        loadPoll()
    }

    private suspend fun loadPoll() {
        val r = Http.postJson("${Config.API_URL}/api/poll", JSONObject().put("device", AppState.installId).toString(), timeout = 10)
        poll = parsePoll(parseJson(r?.body))
    }

    private fun parsePoll(j: JSONObject?): Poll? {
        val p = j?.obj("poll") ?: return null
        val results = j.obj("results")
        val ended = p.bool("ended") == true
        // Fini depuis plus d'un jour : plus affiché.
        val until = p.long("until") ?: 0L
        if (ended && System.currentTimeMillis() - until > 86_400_000L) return null
        return Poll(
            id = p.str("id") ?: return null,
            question = p.str("question") ?: "",
            options = p.arr("options")?.strings() ?: emptyList(),
            multi = p.bool("multi") == true,
            until = until,
            ended = ended,
            mine = j.optJSONArray("mine")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
            counts = results?.optJSONArray("counts")?.let { a -> (0 until a.length()).map { a.optInt(it) } },
            voters = results?.int("voters") ?: 0,
        )
    }

    fun dismiss() {
        announcement?.let { AppState.prefs.edit().putString(DISMISSED, it.id).apply() }
        announcement = null
    }

    suspend fun react(emoji: String?) {
        val a = announcement ?: return
        val previous = reaction
        reaction = emoji
        val body = JSONObject().put("id", AppState.installId).put("announcementId", a.id).put("emoji", emoji ?: JSONObject.NULL)
        val r = Http.postJson("${Config.API_URL}/api/announcement/react", body.toString(), timeout = 10)
        if (r?.code == 200) {
            AppState.prefs.edit().putString(REACTED + a.id, emoji).apply()
        } else {
            reaction = previous
        }
    }

    suspend fun vote(choices: List<Int>) {
        val p = poll ?: return
        voting = true
        val body = JSONObject().put("device", AppState.installId).put("pollId", p.id).put("choices", JSONArray(choices))
        val r = Http.postJson("${Config.API_URL}/api/poll/vote", body.toString(), timeout = 15)
        voting = false
        if (r?.code == 200) parsePoll(parseJson(r.body))?.let { poll = it } else Logger.warn("POLL", "Vote refusé", "HTTP ${r?.code ?: "réseau"}")
    }
}
