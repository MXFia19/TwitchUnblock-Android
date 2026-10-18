package com.mxfia19.twitchunblock.drops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mxfia19.twitchunblock.App
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.strings
import com.mxfia19.twitchunblock.notify.LiveNotifier
import com.mxfia19.twitchunblock.web.AccountPlayback
import com.mxfia19.twitchunblock.web.WebGql
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class TimedDrop(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val requiredMinutes: Int,
    val currentMinutes: Int,
    val isClaimed: Boolean,
    val dropInstanceId: String?,
    val hasPreconditionsMet: Boolean,
) {
    val progress: Float
        get() = if (isClaimed) 1f else if (requiredMinutes > 0) minOf(1f, currentMinutes.toFloat() / requiredMinutes) else 0f
    val canClaim: Boolean get() = !isClaimed && dropInstanceId != null && currentMinutes >= requiredMinutes
}

data class DropCampaign(
    val id: String,
    val name: String,
    val gameName: String,
    val boxArt: String,
    val startAt: Long?,
    val endAt: Long?,
    val isUpcoming: Boolean,
    val accountLinkUrl: String?,
    val needsAccountLink: Boolean,
    val drops: List<TimedDrop>,
)

data class DropSession(val channelName: String?, val currentMinutes: Int, val requiredMinutes: Int)

/**
 * Drops (session web) : campagnes en cours et leur progression, campagnes
 * ouvertes, réclamation (automatique si réglée). Comme DropsService (iOS).
 * Fil principal.
 */
object Drops {
    var campaigns by mutableStateOf<List<DropCampaign>>(emptyList())
        private set
    var available by mutableStateOf<List<DropCampaign>>(emptyList())
        private set
    var session by mutableStateOf<DropSession?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var availableLoading by mutableStateOf(false)
        private set
    var availableFailed by mutableStateOf(false)
        private set
    var claiming by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Dernier message (réclamé, échec), et s'il est bon. */
    var notice by mutableStateOf<Pair<String, Boolean>?>(null)

    private var sessionFor: String? = null

    /** Autre session web : rien de l'ancienne ne reste affiché. */
    private fun ensureSession(token: String) {
        if (sessionFor == token) return
        sessionFor = token
        campaigns = emptyList()
        available = emptyList()
        session = null
        loaded = false
        failed = false
        availableFailed = false
        notice = null
    }

    private const val CLAIM_HASH = "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930"

    private const val INVENTORY = "query { currentUser { inventory { dropCampaignsInProgress { " +
        "id name status startAt endAt accountLinkURL self { isAccountConnected } " +
        "game { displayName boxArtURL(width: 144, height: 192) } " +
        "timeBasedDrops { id name requiredMinutesWatched benefitEdges { benefit { name imageAssetURL } } " +
        "self { hasPreconditionsMet currentMinutesWatched isClaimed dropInstanceID } } } } } }"

    private const val DASHBOARD = "query { currentUser { dropCampaigns { " +
        "id name status startAt endAt accountLinkURL self { isAccountConnected } " +
        "game { displayName boxArtURL(width: 144, height: 192) } " +
        "timeBasedDrops { id name requiredMinutesWatched benefitEdges { benefit { name imageAssetURL } } } } } }"

    suspend fun refresh(autoClaim: Boolean = Features.dropsAutoClaim.value) {
        val token = WebSession.token ?: return
        ensureSession(token)
        loading = true
        try {
            val json = WebGql.raw(INVENTORY, token = token).json
            if (WebSession.token != token) return
            val data = json?.obj("data")
            if (data == null) {
                Logger.warn("DROPS", "Inventaire indisponible", WebGql.errors(json))
                failed = true
                return
            }
            failed = false
            loaded = true
            campaigns = data.obj("currentUser")?.obj("inventory")?.arr("dropCampaignsInProgress")?.objects().orEmpty()
                .mapNotNull { campaign(it) }.sortedBy { it.endAt ?: Long.MAX_VALUE }
            val channel = DropsHeartbeat.channelId
            session = if (channel != null) fetchSession(token, channel) else null

            if (!autoClaim) {
                for (c in campaigns) if (!c.needsAccountLink) for (d in c.drops) if (d.canClaim) notify(d, claimed = false)
                return
            }
            var claimedSome = false
            for (c in campaigns) {
                if (c.needsAccountLink) continue
                for (d in c.drops) {
                    if (!d.canClaim) continue
                    if (claim(d)) {
                        claimedSome = true
                        notify(d, claimed = true)
                    } else {
                        notify(d, claimed = false)
                    }
                }
            }
            if (claimedSome) refresh(autoClaim = false)
        } finally {
            loading = false
        }
    }

    suspend fun refreshAvailable() {
        val token = WebSession.token ?: return
        ensureSession(token)
        if (availableLoading) return
        availableLoading = true
        try {
            val body = WebGql.written(DASHBOARD)
            var json = WebGql.post(body, token).json
            if (WebGql.errors(json).lowercase().contains("integrity")) {
                val signed = com.mxfia19.twitchunblock.web.Integrity.get(token)
                if (signed != null) json = WebGql.post(body, token, signed).json
            }
            if (WebSession.token != token) return
            val list = json?.obj("data")?.obj("currentUser")?.arr("dropCampaigns")
            if (list == null) {
                Logger.warn("DROPS", "Campagnes ouvertes indisponibles", WebGql.errors(json))
                availableFailed = true
                return
            }
            availableFailed = false
            val now = System.currentTimeMillis()
            available = list.objects().mapNotNull { campaign(it) }
                .filter { (it.endAt ?: Long.MAX_VALUE) > now }
                .sortedWith(compareBy<DropCampaign> { it.isUpcoming }.thenBy { it.gameName.lowercase() })
        } finally {
            availableLoading = false
        }
    }

    private suspend fun fetchSession(token: String, channelId: String): DropSession? {
        val q = "query(\$c: ID!) { currentUser { dropCurrentSession(channelID: \$c) { " +
            "channel { displayName } currentMinutesWatched requiredMinutesWatched dropID } } }"
        val s = WebGql.raw(q, JSONObject().put("c", channelId), token).data?.obj("currentUser")?.obj("dropCurrentSession") ?: return null
        if (s.str("dropID") == null) return null
        return DropSession(s.obj("channel")?.str("displayName"), s.int("currentMinutesWatched") ?: 0, s.int("requiredMinutesWatched") ?: 0)
    }

    suspend fun claim(drop: TimedDrop): Boolean {
        val token = WebSession.token ?: return false
        val instance = drop.dropInstanceId ?: return false
        if (drop.id in claiming) return false
        claiming = claiming + drop.id
        try {
            Logger.info("DROPS", "Réclamation…", drop.name)
            val body = WebGql.persisted("DropsPage_ClaimDropRewards",
                JSONObject().put("input", JSONObject().put("dropInstanceID", instance)), CLAIM_HASH)
            val json = WebGql.mutation(body, token, "claimDrop")
            val status = json?.obj("data")?.obj("claimDropRewards")?.str("status")
            if (status == "ELIGIBLE_FOR_ALL" || status == "DROP_INSTANCE_ALREADY_CLAIMED") {
                Logger.success("DROPS", "Drop réclamé", drop.name)
                notice = Pair(AppState.t("drops_claim_ok").replace("{d}", drop.name), true)
                return true
            }
            Logger.warn("DROPS", "Réclamation refusée", status ?: WebGql.errors(json).ifEmpty { "réponse vide" })
            notice = Pair(AppState.t("drops_claim_fail"), false)
            return false
        } finally {
            claiming = claiming - drop.id
        }
    }

    /** Un drop prêt ou réclamé : une notification, une seule fois. */
    private fun notify(drop: TimedDrop, claimed: Boolean) {
        if (!Features.dropsNotify.value) return
        val mark = "${drop.id}-${if (claimed) "claimed" else "ready"}"
        val seen = parseJsonArray(AppState.prefs.getString("drops_notified", null))?.strings()?.toMutableList() ?: mutableListOf()
        if (mark in seen) return
        seen += mark
        AppState.prefs.edit().putString("drops_notified", JSONArray(seen.takeLast(300)).toString()).apply()
        val context: Context = App.instance
        if (!LiveNotifier.canNotify(context)) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel("drops") == null) {
                nm.createNotificationChannel(NotificationChannel("drops", AppState.t("drops"), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
        val n = NotificationCompat.Builder(context, "drops")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(AppState.t(if (claimed) "drops_notif_claimed" else "drops_notif_ready"))
            .setContentText(drop.name)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(mark.hashCode(), n)
        } catch (e: SecurityException) {
            Logger.warn("DROPS", "Notification refusée par Android")
        }
    }

    private fun campaign(c: JSONObject): DropCampaign? {
        val id = c.str("id") ?: return null
        val game = c.obj("game")
        val drops = c.arr("timeBasedDrops")?.objects().orEmpty().mapNotNull { d ->
            val dropId = d.str("id") ?: return@mapNotNull null
            val me = d.obj("self")
            val benefit = d.arr("benefitEdges")?.objects()?.firstOrNull()?.obj("benefit")
            TimedDrop(
                id = dropId,
                name = benefit?.str("name") ?: d.str("name") ?: "",
                imageUrl = benefit?.str("imageAssetURL"),
                requiredMinutes = d.int("requiredMinutesWatched") ?: 0,
                currentMinutes = me?.int("currentMinutesWatched") ?: 0,
                isClaimed = me?.bool("isClaimed") ?: false,
                dropInstanceId = me?.str("dropInstanceID"),
                hasPreconditionsMet = me?.bool("hasPreconditionsMet") ?: true,
            )
        }
        val link = c.str("accountLinkURL")
        val connected = c.obj("self")?.bool("isAccountConnected") ?: true
        return DropCampaign(
            id = id,
            name = c.str("name") ?: "",
            gameName = game?.str("displayName") ?: "",
            boxArt = game?.str("boxArtURL") ?: "",
            startAt = Format.parseDate(c.str("startAt")),
            endAt = Format.parseDate(c.str("endAt")),
            isUpcoming = c.str("status") == "UPCOMING",
            accountLinkUrl = link,
            needsAccountLink = !connected && !(link ?: "").contains("twitch.tv"),
            drops = drops,
        )
    }
}

/**
 * Progression des drops pendant un direct : Twitch ne compte que si le
 * compte charge lui-même le flux. Si la chaîne a des drops, la liste la
 * plus légère est relue au nom du compte et ses nouveaux segments
 * demandés (HEAD) toutes les 10 s ; l'inventaire est relu toutes les
 * 5 min. Inutile si le lecteur lit déjà le direct par le compte. Comme
 * DropsHeartbeat (iOS).
 */
object DropsHeartbeat {
    /** « idle », « checking », « none », « active », « failing ». */
    var status by mutableStateOf("idle")
        private set
    var login by mutableStateOf<String?>(null)
        private set
    var channelId: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val seen = ArrayList<String>()

    fun start(channel: String, pollSegments: Boolean) {
        stop()
        val token = WebSession.token ?: return
        if (!Features.dropsProgress.value) return
        val l = channel.lowercase()
        login = l
        status = "checking"
        job = scope.launch { run(l, token, pollSegments) }
    }

    fun stop() {
        job?.cancel()
        job = null
        channelId = null
        seen.clear()
        status = "idle"
        login = null
    }

    private suspend fun run(login: String, token: String, pollSegments: Boolean) {
        var variant: String? = null
        var lastCheck = 0L
        var lastInventory = System.currentTimeMillis()
        var failures = 0
        var offlineHits = 0
        var refusals = 0
        while (scope.isActive) {
            if (WebSession.token != token) {
                stop()
                return
            }
            if (channelId == null && System.currentTimeMillis() - lastCheck > 600_000) {
                lastCheck = System.currentTimeMillis()
                when (val found = dropsChannelId(login)) {
                    null -> {
                        // Pas de réponse : nouvel essai dans 45 s.
                        lastCheck = System.currentTimeMillis() - 600_000 + 45_000
                    }
                    "" -> status = "none"
                    else -> {
                        channelId = found
                        status = "active"
                        Logger.success("DROPS", "Drops sur $login", if (pollSegments) "progression : segments toutes les 10 s" else "progression : flux du lecteur")
                    }
                }
            }
            if (channelId != null) {
                if (pollSegments && variant == null) {
                    variant = lowestVariant(login, token)
                    if (variant == null && offlineHits > 0) offlineHits++ else failures = if (variant == null) failures + 1 else 0
                    status = if (failures > 2 || refusals > 2) "failing" else "active"
                }
                val v = variant
                if (v != null) {
                    when (poll(v)) {
                        0 -> { offlineHits = 0; refusals = 0 }
                        1 -> { variant = null; refusals++ }
                        else -> { variant = null; offlineHits++ }
                    }
                }
                if (offlineHits >= 3) {
                    Logger.info("DROPS", "Live terminé", login)
                    stop()
                    return
                }
                if (System.currentTimeMillis() - lastInventory > 300_000) {
                    lastInventory = System.currentTimeMillis()
                    Drops.refresh()
                }
            }
            val pause = if (channelId == null || !pollSegments || failures > 2 || refusals > 2) 60_000L else 10_000L
            delay(pause)
        }
    }

    /** 0 : bon ; 1 : jeton refusé ; 2 : direct terminé. */
    private suspend fun poll(variant: String): Int {
        val r = Http.get(variant, timeout = 10) ?: return 0
        if (r.code == 404) return 2
        if (r.code != 200) return 1
        val lines = r.body.split("\n")
        val fresh = ArrayList<String>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXTINF") || i + 1 >= lines.size) continue
            val uri = lines[i + 1].trim()
            if (uri.isEmpty() || uri.startsWith("#") || uri in seen) continue
            seen += uri
            fresh += try { java.net.URI(variant).resolve(uri).toString() } catch (e: Exception) { continue }
        }
        while (seen.size > 256) seen.removeAt(0)
        for (url in fresh) {
            val code = Http.head(url, timeout = 10) ?: 0
            if (code == 401 || code == 403) return 1
        }
        return 0
    }

    /** Identifiant de la chaîne si elle a des drops, "" sinon, null sans réponse. */
    private suspend fun dropsChannelId(login: String): String? {
        val q = "query(\$l: String!) { user(login: \$l) { id channel { viewerDropCampaigns { id } } } }"
        val json = WebGql.raw(q, JSONObject().put("l", login)).json ?: return null
        val data = json.obj("data") ?: return null
        val user = data.obj("user") ?: return ""
        val id = user.str("id") ?: return ""
        val list = user.obj("channel")?.arr("viewerDropCampaigns")
        return if (list != null && list.length() > 0) id else ""
    }

    private suspend fun lowestVariant(login: String, token: String): String? {
        val res = AccountPlayback.fetch(login, token)
        val pat = (res as? AccountPlayback.Result.Ok)?.token ?: run {
            Logger.warn("DROPS", "Jeton de lecture indisponible", login)
            return null
        }
        val url = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8?allow_source=true&allow_audio_only=true" +
            "&sig=${Http.encode(pat.signature)}&token=${Http.encode(pat.value)}"
        val r = Http.get(url, timeout = 10)
        if (r == null || r.code != 200) {
            Logger.warn("DROPS", "Liste maîtresse indisponible", login)
            return null
        }
        val lines = r.body.split("\n")
        var last: String? = null
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) last = lines[i + 1].trim()
        }
        return last?.let { try { java.net.URI(url).resolve(it).toString() } catch (e: Exception) { null } }
    }
}
