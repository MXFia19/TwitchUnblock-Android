package com.mxfia19.twitchunblock.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.web.WebGql
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class LivePoll(
    val id: String,
    val title: String,
    val choices: List<Choice>,
    val endsAt: Long?,
    val isActive: Boolean,
    val multichoice: Boolean,
) {
    data class Choice(val id: String, val title: String, val votes: Int)

    val total: Int get() = maxOf(1, choices.sumOf { it.votes })
    val winnerId: String?
        get() {
            if (isActive) return null
            val best = choices.maxByOrNull { it.votes } ?: return null
            return if (best.votes > 0) best.id else null
        }
    val noticeKey: String get() = "poll:" + id.ifEmpty { title }
}

data class LivePrediction(
    val id: String,
    val title: String,
    val locked: Boolean,
    val outcomes: List<Outcome>,
    val closesAt: Long? = null,
    val winnerId: String? = null,
    val myOutcomeId: String? = null,
    val myPoints: Int = 0,
    val topBets: Map<String, Int> = emptyMap(),
) {
    data class Outcome(val id: String, val title: String, val color: String, val points: Int, val users: Int)

    val total: Int get() = maxOf(1, outcomes.sumOf { it.points })

    /** Cote d'une issue : 1 point misé en rapporte total / points (mise comprise). */
    fun ratio(points: Int): Double? = if (points > 0) outcomes.sumOf { it.points }.toDouble() / points else null

    val noticeKey: String get() = "pred:" + id.ifEmpty { title }
}

data class LiveHype(
    val id: String,
    val approaching: Boolean,
    val variant: String,        // "regular", "golden", "treasure"
    val level: Int = 1,
    val percent: Double = 0.0,
    val left: Int = 1,
    val endsAt: Long? = null,
    /** Message court du moment : « departed », « level », « ended ». */
    val flash: String? = null,
    val flashLevel: Int = 0,
    val flashPercent: Int = 0,
) {
    val noticeKey: String get() = (if (approaching) "hype-approach" else "hype") + if (id.isEmpty()) "" else ":$id"
}

/**
 * Événements d'un direct, relus toutes les 15 s : série de visionnage,
 * sondage (vote), prédiction (pari avec les points), hype train (5 s
 * pendant une approche). Lectures publiques, et la session web pour voter,
 * parier et voir son pari. Comme LiveEventsService (iOS). Fil principal.
 */
class LiveEvents(private val scope: CoroutineScope) {
    var watchStreak by mutableStateOf(0)
        private set
    var poll by mutableStateOf<LivePoll?>(null)
        private set
    var prediction by mutableStateOf<LivePrediction?>(null)
        private set
    var hype by mutableStateOf<LiveHype?>(null)
        private set
    var votedChoiceIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var voting by mutableStateOf(false)
        private set
    /** « NO_SESSION » ou « REFUSED ». */
    var voteError by mutableStateOf<String?>(null)
    var betting by mutableStateOf(false)
        private set
    /** Code de Twitch, ou « NO_SESSION » / « NETWORK ». */
    var betError by mutableStateOf<String?>(null)

    private var login = ""
    private var channelId = ""
    private var jobs = ArrayList<Job>()
    private var resultUntil = 0L
    private var tokenRefused = false
    private val localBets = HashMap<String, Pair<String, Int>>()
    private var hypeRead = false
    private var hypeGen = 0

    private val token: String? get() = WebSession.token?.takeIf { !WebSession.expired }

    fun start(login: String, channelId: String) {
        stop()
        this.login = login.lowercase()
        this.channelId = channelId
        tokenRefused = false
        jobs.add(scope.launch {
            while (isActive) {
                fetchStreak()
                if (Features.showLiveEvents.value) {
                    fetchPoll()
                    fetchPrediction()
                }
                delay(15_000)
            }
        })
        val gen = ++hypeGen
        hypeRead = false
        jobs.add(scope.launch {
            while (isActive && gen == hypeGen) {
                if (Features.showLiveEvents.value) fetchHype(gen) else hype = null
                delay(if (hype?.approaching == true) 5_000L else 15_000L)
            }
        })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        hypeGen++
        watchStreak = 0
        poll = null
        prediction = null
        hype = null
        votedChoiceIds = emptySet()
        voting = false
        voteError = null
        betting = false
        betError = null
        localBets.clear()
        resultUntil = 0L
    }

    // ── Vote ──
    suspend fun vote(choiceId: String) {
        val p = poll ?: return
        if (voting || !p.isActive || p.id.isEmpty() || choiceId in votedChoiceIds) return
        if (!p.multichoice && votedChoiceIds.isNotEmpty()) return
        val tok = token
        val viewer = viewerId()
        if (tok == null || viewer == null) {
            voteError = "NO_SESSION"
            return
        }
        voting = true
        voteError = null
        try {
            val input = JSONObject()
                .put("pollID", p.id)
                .put("choiceID", choiceId)
                .put("userID", viewer)
                .put("voteID", UUID.randomUUID().toString().replace("-", "").lowercase())
                .put("tokens", JSONObject.NULL)
            val res = WebGql.mutation(WebGql.persisted("ChannelPollContext_VoteInPoll", JSONObject().put("input", input), VOTE_POLL),
                                      tok, "vote")
            val ok = res?.obj("data")?.has("voteInPoll") == true && WebGql.errors(res).isEmpty()
            if (ok) {
                votedChoiceIds = votedChoiceIds + choiceId
                Logger.success("EVENTS", "Vote enregistré", p.title)
                fetchPoll()
            } else {
                voteError = if (WebGql.errors(res).lowercase().contains("unauthenticated")) "NO_SESSION" else "REFUSED"
            }
        } finally {
            voting = false
        }
    }

    private var cachedViewer: String? = null

    private suspend fun viewerId(): String? {
        cachedViewer?.let { return it }
        val tok = token ?: return null
        val id = WebGql.raw("{ currentUser { id } }", token = tok).data?.obj("currentUser")?.str("id")
        cachedViewer = id
        return id
    }

    // ── Pari ──
    /** Mise sur une issue : 10 points au moins, 250 000 au plus par prédiction. */
    suspend fun predict(outcomeId: String, points: Int): Boolean {
        val p = prediction ?: return false
        if (betting || p.locked || p.winnerId != null || p.id.isEmpty() || points <= 0) return false
        val tok = token
        if (tok == null) {
            betError = "NO_SESSION"
            return false
        }
        betting = true
        betError = null
        try {
            val input = JSONObject()
                .put("eventID", p.id)
                .put("outcomeID", outcomeId)
                .put("points", points)
                .put("transactionID", UUID.randomUUID().toString().replace("-", "").lowercase())
            val res = WebGql.mutation(WebGql.persisted("MakePrediction", JSONObject().put("input", input), MAKE_PREDICTION),
                                      tok, "predict")
            val made = res?.obj("data")?.obj("makePrediction")
            if (made == null) {
                betError = if (WebGql.errors(res).lowercase().contains("unauthenticated")) "NO_SESSION" else "NETWORK"
                return false
            }
            made.obj("error")?.str("code")?.let { code ->
                betError = code
                Logger.warn("EVENTS", "Pari refusé", code)
                return false
            }
            val total = (if (p.myOutcomeId == outcomeId) p.myPoints else 0) + points
            localBets[p.id] = Pair(outcomeId, total)
            prediction = p.copy(myOutcomeId = outcomeId, myPoints = total)
            Logger.success("EVENTS", "Pari enregistré", "$points pts · ${p.title}")
            fetchPrediction()
            return true
        } finally {
            betting = false
        }
    }

    /** Lecture publique : avec la session (champs « self »), sans elle si Twitch la refuse. */
    private suspend fun publicQuery(op: String, variables: JSONObject, hash: String): JSONObject? {
        val tok = token
        if (!tokenRefused && tok != null) {
            val r = WebGql.query(op, variables, hash, tok)
            if (r.data != null) return r.data
            tokenRefused = true
        }
        return WebGql.query(op, variables, hash, null).data
    }

    // ── Série de visionnage ──
    private suspend fun fetchStreak() {
        val tok = token ?: return
        val arr = WebGql.query("BatchGetWatchStreaks", JSONObject(), STREAK, tok).data?.arr("batchGetWatchStreaks") ?: return
        watchStreak = arr.objects().firstOrNull { it.obj("channel")?.str("id") == channelId }?.int("value") ?: 0
    }

    // ── Sondage ──
    private suspend fun fetchPoll() {
        val chan = publicQuery("ChannelPollContext_GetViewablePoll", JSONObject().put("login", login), POLL)?.obj("channel") ?: return
        val p = chan.obj("viewablePoll")
        if (p == null) {
            poll = null
            votedChoiceIds = emptySet()
            return
        }
        val choices = p.arr("choices")?.objects().orEmpty().map { c ->
            LivePoll.Choice(c.str("id") ?: "", c.str("title") ?: "", c.int("totalVoters") ?: c.obj("votes")?.int("total") ?: 0)
        }
        val status = (p.str("status") ?: "ACTIVE").uppercase()
        val settings = p.obj("settings")
        val multi = settings?.obj("multichoice")?.bool("isEnabled") ?: settings?.bool("multichoice") ?: false
        val endsAt = p.int("remainingDurationMilliseconds")?.let { System.currentTimeMillis() + it }
            ?: Format.parseDate(p.str("endedAt"))
            ?: run {
                val start = Format.parseDate(p.str("startedAt"))
                val dur = p.int("durationSeconds")
                if (start != null && dur != null) start + dur * 1000L else null
            }
        val fresh = if (choices.isEmpty()) null
                    else LivePoll(p.str("id") ?: "", p.str("title") ?: "", choices, endsAt, status == "ACTIVE", multi)
        if (fresh?.id != poll?.id) {
            votedChoiceIds = emptySet()
            fresh?.let { Logger.success("EVENTS", "Sondage", it.title) }
        }
        poll = fresh
    }

    // ── Prédiction ──
    private suspend fun fetchPrediction() {
        val chan = publicQuery("ChannelPointsPredictionContext", JSONObject().put("count", 1).put("channelLogin", login),
                               PREDICTION)?.obj("community")?.obj("channel") ?: return
        val active = chan.arr("activePredictionEvents")?.objects().orEmpty()
        val lockedList = chan.arr("lockedPredictionEvents")?.objects().orEmpty()
        val ev = active.firstOrNull() ?: lockedList.firstOrNull()
        if (ev == null) {
            val prev = prediction ?: return
            if (prev.winnerId == null && prev.id.isNotEmpty()) {
                val winner = resolvedWinner(prev.id)
                if (winner != null) {
                    prediction = prev.copy(winnerId = winner, closesAt = null)
                    resultUntil = System.currentTimeMillis() + 60_000
                    Logger.success("EVENTS", "Prédiction résolue", prev.title)
                    return
                }
            }
            if (prev.winnerId == null || resultUntil < System.currentTimeMillis()) {
                prediction = null
                resultUntil = 0L
            }
            return
        }
        resultUntil = 0L
        val isLocked = active.isEmpty() || ev.str("status") == "LOCKED"
        val outcomes = ev.arr("outcomes")?.objects().orEmpty().map { o ->
            LivePrediction.Outcome(o.str("id") ?: "", o.str("title") ?: "", o.str("color") ?: "BLUE",
                                   o.int("totalPoints") ?: 0, o.int("totalUsers") ?: 0)
        }
        if (outcomes.isEmpty()) {
            prediction = null
            return
        }
        var closesAt: Long? = null
        if (!isLocked) {
            val created = Format.parseDate(ev.str("createdAt"))
            val window = ev.int("predictionWindowSeconds")
            if (created != null && window != null) closesAt = created + window * 1000L
        }
        val top = HashMap<String, Int>()
        for (o in ev.arr("outcomes")?.objects().orEmpty()) {
            val id = o.str("id") ?: continue
            val best = o.arr("topPredictors")?.objects().orEmpty().mapNotNull { it.int("points") }.maxOrNull() ?: continue
            if (best > 0) top[id] = best
        }
        val id = ev.str("id") ?: ""
        var next = LivePrediction(id, ev.str("title") ?: "", isLocked, outcomes, closesAt, topBets = top)
        val local = localBets[id]
        val mine = myPrediction(id)
        if (mine != null) {
            val pts = if (local != null && local.first == mine.first) maxOf(mine.second, local.second) else mine.second
            next = next.copy(myOutcomeId = mine.first, myPoints = pts)
        } else if (local != null) {
            next = next.copy(myOutcomeId = local.first, myPoints = local.second)
        }
        if (next.title != prediction?.title) Logger.success("EVENTS", "Prédiction", next.title)
        prediction = next
    }

    private suspend fun myPrediction(eventId: String): Pair<String, Int>? {
        val tok = token ?: return null
        if (eventId.isEmpty()) return null
        val ev = "id self { prediction { points outcome { id } } }"
        val q = "query(\$l: String!) { channel(name: \$l) { activePredictionEvents { $ev } lockedPredictionEvents { $ev } } }"
        val chan = WebGql.raw(q, JSONObject().put("l", login), tok).data?.obj("channel") ?: return null
        val events = chan.arr("activePredictionEvents")?.objects().orEmpty() + chan.arr("lockedPredictionEvents")?.objects().orEmpty()
        val e = events.firstOrNull { it.str("id") == eventId } ?: return null
        val pred = e.obj("self")?.obj("prediction") ?: return null
        val pts = pred.int("points") ?: return null
        val oid = pred.obj("outcome")?.str("id") ?: return null
        return Pair(oid, pts)
    }

    private suspend fun resolvedWinner(eventId: String): String? {
        val q = "query(\$l: String!) { channel(name: \$l) { resolvedPredictionEvents(first: 3) { edges { node { id winningOutcome { id } } } } } }"
        val edges = WebGql.raw(q, JSONObject().put("l", login)).data?.obj("channel")?.obj("resolvedPredictionEvents")?.arr("edges")
            ?: return null
        return edges.objects().mapNotNull { it.obj("node") }.firstOrNull { it.str("id") == eventId }?.obj("winningOutcome")?.str("id")
    }

    // ── Hype train ──
    private suspend fun fetchHype(gen: Int) {
        val ht = WebGql.raw(HYPE_QUERY, JSONObject().put("l", login)).data?.obj("user")?.obj("channel")?.obj("hypeTrain") ?: return
        if (gen != hypeGen) return
        val now = System.currentTimeMillis()
        apply(read(ht, now), now)
    }

    private fun read(ht: JSONObject, now: Long): LiveHype? {
        val exec = ht.obj("execution")
        if (exec != null && exec.str("endedAt") == null) {
            val progress = exec.obj("progress")
            val lvl = progress?.obj("level")
            val level = lvl?.int("value") ?: 1
            val goal = progress?.int("goal") ?: 0
            var done = progress?.int("progression")
            if (done == null) {
                val total = progress?.int("total")
                val all = lvl?.int("goal")
                if (total != null && all != null) done = total - (all - goal)
            }
            val ends = progress?.dbl("remainingSeconds")?.let { now + (it * 1000).toLong() } ?: Format.parseDate(exec.str("expiresAt"))
            if (ends == null || ends > now) {
                return LiveHype(exec.str("id") ?: "", approaching = false, variant = variant(exec), level = level,
                                percent = if (goal > 0) ((done ?: 0).toDouble() / goal).coerceIn(0.0, 1.0) else 0.0,
                                endsAt = ends)
            }
        }
        val ap = ht.obj("approaching") ?: return null
        val goal = ap.int("goal") ?: 0
        val steps = ap.arr("eventsRemaining")?.objects().orEmpty()
        val joined = ap.arr("participants")?.length() ?: 0
        val fewest = steps.mapNotNull { it.int("events") }.filter { it > 0 }.minOrNull()
        val big = goal > 5
        val step = steps.firstOrNull { it.int("events") == (if (big) 2 else 1) } ?: steps.firstOrNull { it.int("events") == fewest }
        val ends = step?.dbl("secondsRemaining")?.let { now + (it * 1000).toLong() } ?: Format.parseDate(ap.str("expiresAt"))
        if (ends != null && ends <= now) return null
        return LiveHype(ap.str("id") ?: "", approaching = true, variant = variant(ap), left = fewest ?: maxOf(1, goal - joined), endsAt = ends)
    }

    /** Transitions (départ, niveau terminé, fin), comparées au relevé précédent. */
    private fun apply(read: LiveHype?, now: Long) {
        val prev = hype
        if (!hypeRead) {
            hypeRead = true
            hype = read
            return
        }
        if (read == null) {
            if (prev == null || prev.flash == "ended") return
            val ends = prev.endsAt
            if (!prev.approaching && prev.level >= 2 && ends != null && now >= ends - 3_000) {
                setFlash(prev.copy(endsAt = null, flash = "ended", flashLevel = prev.level,
                                   flashPercent = (prev.percent * 100).toInt()), 15_000, clearAfter = true)
                Logger.success("EVENTS", "Hype train terminé", "niveau ${prev.level}")
            } else {
                hype = null
            }
            return
        }
        if (!read.approaching) {
            if (prev != null && !prev.approaching && prev.id == read.id) {
                if (read.level > prev.level) {
                    setFlash(read.copy(flash = "level", flashLevel = prev.level), 4_000)
                } else {
                    // Message en cours gardé jusqu'à sa fin.
                    hype = read.copy(flash = prev.flash, flashLevel = prev.flashLevel, flashPercent = prev.flashPercent)
                }
            } else {
                Logger.success("EVENTS", "Hype train parti", "niveau ${read.level}")
                setFlash(read.copy(flash = "departed"), 10_000)
            }
            return
        }
        hype = read
    }

    private fun setFlash(h: LiveHype, duration: Long, clearAfter: Boolean = false) {
        hype = h
        val gen = hypeGen
        scope.launch {
            delay(duration)
            if (gen != hypeGen) return@launch
            val cur = hype ?: return@launch
            if (cur.flash != h.flash || cur.id != h.id) return@launch
            hype = if (clearAfter) null else cur.copy(flash = null)
        }
    }

    private fun variant(d: JSONObject): String = when {
        d.bool("isGoldenKappaTrain") == true -> "golden"
        d.bool("isTreasureTrain") == true -> "treasure"
        else -> "regular"
    }

    companion object {
        private const val STREAK = "7d04fbaa5bb32d193c742caa2c855dfde371a2a8cb25afed5b5c3d45f5ee9278"
        private const val POLL = "e83188a3836c636393df3191665e543a03733d7c51d3ade3d85e42aa46c2bf55"
        private const val PREDICTION = "beb846598256b75bd7c1fe54a80431335996153e358ca9c7837ce7bb83d7d383"
        private const val VOTE_POLL = "1280e27b0f3c7ae60b5714bd569771ea50635778473182e6e959e2dcfcc16e3c"
        private const val MAKE_PREDICTION = "b44682ecc88358817009f20e69d75081b1e58825bb40aa53d5dbadcc17c881d8"

        private const val HYPE_QUERY = "query(\$l: String!) { user(login: \$l) { channel { hypeTrain { " +
            "approaching { id goal participants expiresAt eventsRemaining { events secondsRemaining } isGoldenKappaTrain isTreasureTrain } " +
            "execution { id expiresAt endedAt isGoldenKappaTrain isTreasureTrain " +
            "progress { goal progression total remainingSeconds level { value goal } } } } } } }"
    }
}
