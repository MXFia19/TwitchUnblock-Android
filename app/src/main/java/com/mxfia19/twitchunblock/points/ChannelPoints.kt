package com.mxfia19.twitchunblock.points

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.web.WebGql
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChannelReward(
    val id: String,
    val title: String,
    val cost: Int,
    val isEnabled: Boolean,
    val isInStock: Boolean,
    val isPaused: Boolean,
    val isUserInputRequired: Boolean,
    val prompt: String?,
    val backgroundColor: String,
    val imageUrl: String?,
) {
    val canRedeem: Boolean get() = isEnabled && isInStock && !isPaused
}

/**
 * Points de chaîne d'un direct : récompenses (publiques), solde et coffres
 * (session web), présence « minute-watched » pour gagner des points en
 * regardant, coffres réclamés tout seuls, rachats. Comme
 * ChannelPointsService (iOS). `scope` : fil principal.
 */
class ChannelPoints(private val scope: CoroutineScope) {
    var balance by mutableStateOf(0)
        private set
    /** Solde lu au moins une fois pour ce compte et cette chaîne. */
    var balanceKnown by mutableStateOf(false)
        private set
    var rewards by mutableStateOf<List<ChannelReward>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var pendingClaimId by mutableStateOf<String?>(null)
        private set
    /** Dernier gain (+N), affiché un moment. */
    var lastGain by mutableStateOf(0)
        private set
    /** Session web absente ou refusée : connexion nécessaire pour le solde. */
    var needsWebLogin by mutableStateOf(false)
        private set
    var redeeming by mutableStateOf<String?>(null)
        private set

    private var channelLogin = ""
    private var channelId = ""
    private var token = ""
    private var viewerId = ""
    private val jobs = mutableListOf<Job>()
    private val failedClaims = HashSet<String>()
    private val claimAttempts = HashMap<String, Int>()
    private var claiming = false
    private var generation = 0

    fun load(login: String, id: String) {
        stop()
        val gen = generation
        channelLogin = login.lowercase()
        channelId = id
        token = WebSession.token ?: ""
        needsWebLogin = false
        balanceKnown = false
        loading = true
        error = null
        jobs += scope.launch {
            rewards = fetchRewards().sortedBy { it.cost }
            if (token.isEmpty() || WebSession.expired) {
                needsWebLogin = true
                loading = false
                return@launch
            }
            val b = fetchBalance(quiet = false)
            if (b != null) {
                balance = b.first
                balanceKnown = true
                pendingClaimId = b.second?.takeIf { it !in failedClaims }
            }
            loading = false
            if (gen != generation) return@launch
            if (needsWebLogin) return@launch
            Logger.success("POINTS", "Points chargés", "$channelLogin · ${format(balance)} pts · ${rewards.size} récompenses")
            jobs += scope.launch { loop(90_000) { refreshBalance() } }
            jobs += scope.launch { loop(30_000) { checkForBonus() } }
            jobs += scope.launch { presence(gen) }
            if (Features.autoClaimChest.value && pendingClaimId != null) {
                delay(2_000)
                claimBonus()
            }
        }
    }

    fun stop() {
        generation++
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (channelLogin.isNotEmpty()) PointsTracker.end(channelLogin)
    }

    private suspend fun loop(every: Long, work: suspend () -> Unit) {
        while (scope.isActive) {
            delay(every)
            work()
        }
    }

    // ── Solde et coffres ──
    private suspend fun refreshBalance() {
        val b = fetchBalance(quiet = true) ?: return
        balanceKnown = true
        val delta = b.first - balance
        if (delta > 0) {
            Logger.success("POINTS", "Points gagnés", "+$delta · total ${format(b.first)}")
            PointsTracker.addGain(delta, channelLogin)
            balance = b.first
            showGain(delta)
        } else if (delta < 0) {
            balance = b.first
        }
        if (b.second == null && pendingClaimId != null) pendingClaimId = null
        val cid = b.second
        if (pendingClaimId == null && cid != null && cid !in failedClaims) {
            pendingClaimId = cid
            Logger.success("POINTS", "Coffre disponible", channelLogin)
            if (Features.autoClaimChest.value) claimBonus()
        }
    }

    private suspend fun checkForBonus() {
        val pending = pendingClaimId
        if (pending != null) {
            if (Features.autoClaimChest.value && claimAttempts[pending] != null) claimBonus()
            return
        }
        val q = "{ channel(name: \"${Gql.str(channelLogin)}\") { self { communityPoints { availableClaim { id } } } } }"
        val cid = auth(q)?.obj("channel")?.obj("self")?.obj("communityPoints")?.obj("availableClaim")?.str("id") ?: return
        if (cid in failedClaims) return
        pendingClaimId = cid
        Logger.success("POINTS", "Coffre disponible", channelLogin)
        if (Features.autoClaimChest.value) claimBonus()
    }

    /** Une réclamation à la fois ; true si Twitch l'a accordée. */
    suspend fun claimBonus(): Boolean {
        val claimId = pendingClaimId ?: return false
        if (claiming || token.isEmpty()) return false
        claiming = true
        try {
            val body = WebGql.persisted("ClaimCommunityPoints",
                JSONObject().put("input", JSONObject().put("channelID", channelId).put("claimID", claimId)), CLAIM_HASH)
            val claim = WebGql.mutation(body, token, "claimBonus")?.obj("data")?.obj("claimCommunityPoints")
            if (claim == null) {
                // Échec passager : le coffre reste réclamable, abandon au 4e essai.
                val n = (claimAttempts[claimId] ?: 0) + 1
                claimAttempts[claimId] = n
                Logger.warn("POINTS", "Réclamation du coffre échouée", "essai $n/4")
                if (n >= 4) {
                    failedClaims += claimId
                    pendingClaimId = null
                }
                return false
            }
            claim.obj("error")?.str("code")?.let { code ->
                Logger.warn("POINTS", "Réclamation refusée", code)
                failedClaims += claimId
                pendingClaimId = null
                return false
            }
            pendingClaimId = null
            PointsTracker.addChest(channelLogin)
            val pts = claim.int("currentPoints")
            if (pts != null) {
                val gained = pts - balance
                Logger.success("POINTS", "Coffre réclamé", "+$gained")
                PointsTracker.addGain(gained, channelLogin)
                balance = pts
                balanceKnown = true
                showGain(gained)
            } else {
                Logger.success("POINTS", "Coffre réclamé")
                refreshBalance()
            }
            return true
        } finally {
            claiming = false
        }
    }

    private fun showGain(delta: Int) {
        lastGain = delta
        scope.launch {
            delay(3_000)
            if (lastGain == delta) lastGain = 0
        }
    }

    // ── Rachat ──
    suspend fun redeem(reward: ChannelReward, input: String?): Boolean {
        if (redeeming != null) return false
        if (!balanceKnown) {
            error = AppState.t(if (needsWebLogin) "points_connect_title" else "points_network_error")
            return false
        }
        if (balance < reward.cost) {
            val missing = reward.cost - balance
            error = "${AppState.t("points_missing")} ${format(missing)} ${AppState.tn("points_unit", missing)}"
            return false
        }
        redeeming = reward.id
        try {
            // Anti-triche : coût, titre et invite identiques à la récompense.
            val promptText = if (reward.isUserInputRequired) input ?: "" else reward.prompt ?: ""
            val promptField = if (promptText.isEmpty()) "prompt: null" else "prompt: ${JSONObject.quote(promptText)}"
            val mutation = "mutation { redeemCommunityPointsCustomReward(input: { channelID: \"${Gql.str(channelId)}\", " +
                "cost: ${reward.cost}, $promptField, rewardID: \"${Gql.str(reward.id)}\", " +
                "title: ${JSONObject.quote(reward.title)}, transactionID: \"${UUID.randomUUID()}\" }) " +
                "{ redemption { id } error { code } } }"
            val result = WebGql.mutation(WebGql.written(mutation), token, "redeem")
                ?.obj("data")?.obj("redeemCommunityPointsCustomReward")
            if (result == null) {
                error = AppState.t("points_network_error")
                return false
            }
            result.obj("error")?.str("code")?.let { code ->
                Logger.warn("POINTS", "Rachat refusé", code)
                error = redeemError(code)
                return false
            }
            balance = maxOf(0, balance - reward.cost)
            error = null
            Logger.success("POINTS", "Rachat confirmé", reward.title)
            return true
        } finally {
            redeeming = null
        }
    }

    /** Pari placé ailleurs (prédiction) : le solde suit tout de suite. */
    fun spend(points: Int) {
        balance = maxOf(0, balance - points)
    }

    // ── Présence minute-watched ──
    // Twitch ne crédite les points passifs et ne crée les coffres que pour
    // un spectateur : un événement « minute-watched » par minute, comme le
    // site.
    private suspend fun presence(gen: Int) {
        val (bcast, spade, viewer) = coroutineScope {
            val b = async { fetchBroadcastId() }
            val s = async { resolveSpadeUrl() }
            val v = async { if (viewerId.isEmpty()) fetchViewerId() else viewerId }
            Triple(b.await(), s.await(), v.await())
        }
        if (gen != generation) return
        if (bcast == null || spade == null || viewer == null || viewer.isEmpty()) {
            Logger.warn("POINTS", "Présence désactivée",
                        "diffusion ${bcast != null} · spade ${spade != null} · compte ${viewer != null}")
            return
        }
        viewerId = viewer
        Logger.success("POINTS", "Présence active", "minute-watched toutes les 60 s")
        PointsTracker.begin(channelLogin)
        while (scope.isActive && gen == generation) {
            sendMinuteWatched(spade, bcast)
            delay(60_000)
        }
    }

    private suspend fun sendMinuteWatched(spade: String, broadcastId: String) {
        val uid = viewerId.toLongOrNull() ?: return
        val payload = JSONArray().put(JSONObject()
            .put("event", "minute-watched")
            .put("properties", JSONObject()
                .put("channel_id", channelId)
                .put("broadcast_id", broadcastId)
                .put("channel", channelLogin)
                .put("user_id", uid)
                .put("player", "site")
                .put("live", true)))
        val b64 = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val r = Http.postForm(spade, mapOf("data" to b64), timeout = 15)
        if (r != null && r.code in 200..299) PointsTracker.addMinute(channelLogin)
        else Logger.debug("POINTS", "minute-watched non compté", r?.code?.let { "HTTP $it" } ?: "réseau")
    }

    private suspend fun fetchViewerId(): String? = auth("{ currentUser { id } }")?.obj("currentUser")?.str("id")

    private suspend fun fetchBroadcastId(): String? =
        Gql.raw("{ user(login: \"${Gql.str(channelLogin)}\") { stream { id } } }")?.obj("data")?.obj("user")?.obj("stream")?.str("id")

    /** Adresse « spade » (statistiques de Twitch), lue dans la page de la chaîne. */
    private suspend fun resolveSpadeUrl(): String? {
        val headers = mapOf("User-Agent" to WebSession.UA)
        val html = Http.get("https://www.twitch.tv/$channelLogin", headers, timeout = 15)?.body ?: return null
        val spadeRe = Regex("\"spade_url\"\\s*:\\s*\"(https:[^\"]+)\"")
        spadeRe.find(html)?.groupValues?.get(1)?.let { return it.replace("\\/", "/") }
        val settings = Regex("(https://[^\"']+?/config/settings\\.[A-Za-z0-9]+\\.js)").find(html)?.groupValues?.get(1)
            ?: return null
        val js = Http.get(settings, headers, timeout = 15)?.body ?: return null
        return spadeRe.find(js)?.groupValues?.get(1)?.replace("\\/", "/")
    }

    // ── Lectures ──
    private suspend fun fetchRewards(): List<ChannelReward> {
        val q = "{ channel(name: \"${Gql.str(channelLogin)}\") { communityPointsSettings { isEnabled customRewards { " +
            "id title cost isEnabled isPaused isInStock isUserInputRequired prompt backgroundColor " +
            "image { url2x } defaultImage { url2x } } } } }"
        val settings = Gql.raw(q)?.obj("data")?.obj("channel")?.obj("communityPointsSettings") ?: return emptyList()
        if (settings.bool("isEnabled") != true) return emptyList()
        return settings.arr("customRewards")?.objects().orEmpty().mapNotNull { r ->
            val id = r.str("id") ?: return@mapNotNull null
            val title = r.str("title") ?: return@mapNotNull null
            val cost = r.int("cost") ?: return@mapNotNull null
            ChannelReward(
                id, title, cost,
                isEnabled = r.bool("isEnabled") ?: false,
                isInStock = r.bool("isInStock") ?: true,
                isPaused = r.bool("isPaused") ?: false,
                isUserInputRequired = r.bool("isUserInputRequired") ?: false,
                prompt = r.str("prompt"),
                backgroundColor = (r.str("backgroundColor") ?: "9147FF").trimStart('#'),
                imageUrl = r.obj("image")?.str("url2x") ?: r.obj("defaultImage")?.str("url2x"),
            )
        }.filter { it.isEnabled }
    }

    /** (solde, coffre) ; null si Twitch n'a pas répondu (le solde affiché reste). */
    private suspend fun fetchBalance(quiet: Boolean): Pair<Int, String?>? {
        val q = "{ channel(name: \"${Gql.str(channelLogin)}\") { self { communityPoints { balance availableClaim { id } } } } }"
        val self = auth(q)?.obj("channel")?.obj("self") ?: run {
            if (!quiet) Logger.warn("POINTS", "Solde illisible")
            return null
        }
        val pts = self.obj("communityPoints") ?: return Pair(0, null)
        return Pair(pts.int("balance") ?: 0, pts.obj("availableClaim")?.str("id"))
    }

    /** Requête écrite avec la session web ; `data`, null sans réponse. Refusée
     *  (401) : la session est à rétablir, les sondages s'arrêtent. */
    private suspend fun auth(query: String): JSONObject? {
        if (token.isEmpty()) return null
        val r = WebGql.raw(query, token = token)
        if (r.code == 401) {
            needsWebLogin = true
            stop()
            return null
        }
        return r.data
    }

    private fun redeemError(code: String): String = when (code.uppercase()) {
        "NOT_ENOUGH_POINTS" -> AppState.t("err_not_enough")
        "REWARD_NOT_FOUND", "NOT_FOUND" -> AppState.t("err_reward_not_found")
        "CHANNEL_POINTS_DISABLED", "DISABLED" -> AppState.t("err_points_disabled")
        "REWARD_NOT_IN_STOCK", "OUT_OF_STOCK" -> AppState.t("points_out_of_stock")
        "ALREADY_CLAIMED" -> AppState.t("err_already_claimed")
        "ON_COOLDOWN", "GLOBAL_COOLDOWN" -> AppState.t("err_cooldown")
        "PROPERTIES_MISMATCH" -> AppState.t("err_properties_mismatch")
        "STREAM_NOT_LIVE" -> AppState.t("err_stream_offline")
        else -> "${AppState.t("points_error")} : $code"
    }

    companion object {
        private const val CLAIM_HASH = "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0"

        fun format(n: Int): String = String.format(java.util.Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ')
    }
}
