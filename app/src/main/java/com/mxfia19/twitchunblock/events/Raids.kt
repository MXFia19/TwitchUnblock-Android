package com.mxfia19.twitchunblock.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.web.WebGql
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class RaidInfo(val id: String, val targetLogin: String, val targetName: String, val viewers: Int)

/**
 * Raids de la chaîne regardée, par Hermes (le temps réel de twitch.tv,
 * sujet « raid.<id> ») : bannière pendant la préparation, puis la chaîne à
 * rejoindre au départ. Le raid est rejoint avec la session web (JoinRaid,
 * comme « Rejoindre » sur twitch.tv) : le bonus de points de raid est
 * gagné aussi. Comme RaidService (iOS). Fil principal.
 */
class RaidWatcher(private val scope: CoroutineScope) {
    var raid by mutableStateOf<RaidInfo?>(null)
        private set
    /** Raid parti : chaîne à rejoindre (remis à null une fois suivi). */
    var joinTarget by mutableStateOf<String?>(null)
    /** Raid rejoint avec la session web (JoinRaid accepté) : le bandeau
     *  annonce le bonus de points. */
    var bonusRaidId by mutableStateOf<String?>(null)
        private set
    /** Raids déjà rejoints : un seul JoinRaid par raid. */
    private val joined = HashSet<String>()

    private var channelId = ""
    private var socket: WebSocket? = null
    private var generation = 0
    private var lastSeen = 0L
    private var keepalive = 15.0
    private var retryDelay = 2_000L
    private var watchdog: Job? = null
    private var reconnect: Job? = null
    private var expiry: Job? = null

    fun start(channelId: String) {
        stop()
        if (channelId.isEmpty()) return
        this.channelId = channelId
        open(ENDPOINT)
        watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                if (socket != null && System.currentTimeMillis() - lastSeen > (keepalive * 2 + 5) * 1000) scheduleReconnect()
            }
        }
    }

    fun stop() {
        generation++
        watchdog?.cancel()
        reconnect?.cancel()
        expiry?.cancel()
        socket?.close(1000, null)
        socket = null
        raid = null
        joinTarget = null
        bonusRaidId = null
        retryDelay = 2_000L
        channelId = ""
    }

    private fun open(address: String) {
        socket?.close(1000, null)
        val gen = ++generation
        lastSeen = System.currentTimeMillis()
        val request = try { Request.Builder().url(address).build() } catch (e: IllegalArgumentException) { return }
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (gen == generation) handle(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (gen != generation) return@launch
                    Logger.warn("RAID", "Connexion perdue", t.message)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (channelId.isEmpty()) return
        socket?.close(1000, null)
        socket = null
        val wait = retryDelay
        retryDelay = minOf(retryDelay * 2, 60_000L)
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(wait)
            if (channelId.isNotEmpty()) open(ENDPOINT)
        }
    }

    private fun handle(ws: WebSocket, text: String) {
        lastSeen = System.currentTimeMillis()
        val json = parseJson(text) ?: return
        when (json.str("type")) {
            "welcome" -> {
                retryDelay = 2_000L
                json.obj("welcome")?.dbl("keepaliveSec")?.let { keepalive = it }
                val sub = JSONObject()
                    .put("type", "subscribe")
                    .put("id", UUID.randomUUID().toString())
                    .put("subscribe", JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("type", "pubsub")
                        .put("pubsub", JSONObject().put("topic", "raid.$channelId")))
                    .put("timestamp", iso())
                ws.send(sub.toString())
            }
            "notification" -> json.obj("notification")?.str("pubsub")?.let { inner(it) }
            "reconnect" -> json.obj("reconnect")?.str("url")?.let { open(it) }
        }
    }

    private fun inner(text: String) {
        val j = parseJson(text) ?: return
        val r = j.obj("raid")
        when (j.str("type")) {
            "raid_update_v2", "raid_update" -> {
                val rr = r ?: return
                val login = rr.str("target_login") ?: return
                val info = RaidInfo(rr.str("id") ?: "", login, rr.str("target_display_name") ?: login, rr.int("viewer_count") ?: 0)
                raid = info
                Logger.success("RAID", "Raid en préparation", "→ $login (${info.viewers})")
                expiry?.cancel()
                expiry = scope.launch {
                    delay(180_000)
                    if (raid?.id == info.id) raid = null
                }
                // Comme « Rejoindre » sur twitch.tv : sans JoinRaid, le raid
                // était suivi mais son bonus de points perdu.
                if (info.id.isNotEmpty()) joinRaid(info.id, login)
            }
            "raid_go_v2", "raid_go" -> {
                val login = r?.str("target_login") ?: raid?.targetLogin ?: return
                Logger.success("RAID", "Raid parti", login)
                joinTarget = login
            }
            "raid_cancel_v2", "raid_cancel" -> {
                Logger.info("RAID", "Raid annulé")
                raid = null
            }
        }
    }

    /** Rejoint le raid annoncé au nom de la session web, une fois par raid.
     *  Mutation signée du jeton d'intégrité (WebGql), lancée à part : le
     *  départ du raid ne l'attend jamais, un échec ne le bloque pas. */
    private fun joinRaid(raidId: String, target: String) {
        if (!joined.add(raidId)) return
        val token = WebSession.token?.takeIf { WebSession.active }
        if (token == null) {
            Logger.info("RAID", "Raid pas rejoint", "pas de session web, pas de bonus")
            return
        }
        val gen = generation
        scope.launch {
            val vars = JSONObject().put("input", JSONObject().put("raidID", raidId))
            var res = WebGql.mutation(WebGql.persisted("JoinRaid", vars, JOIN_RAID_HASH), token, "JoinRaid")
            // Requête enregistrée inconnue de Twitch : la même, écrite en toutes lettres.
            if (WebGql.errors(res).contains("PersistedQueryNotFound")) {
                Logger.info("RAID", "JoinRaid inconnu de Twitch", "nouvel essai en requête écrite")
                res = WebGql.mutation(JSONArray().put(JSONObject().put("operationName", "JoinRaid")
                    .put("query", JOIN_RAID_QUERY).put("variables", vars)).toString(), token, "JoinRaid")
            }
            if (res == null) {
                Logger.warn("RAID", "Raid pas rejoint", "réseau ou jeton d'intégrité")
                return@launch
            }
            // Accepté : `joinRaid` est un objet, sans `error` ni erreur GQL.
            val payload = res.obj("data")?.obj("joinRaid")
            val refusal = payload?.obj("error")?.str("code")
            val errors = WebGql.errors(res)
            if (payload == null || refusal != null || errors.isNotEmpty()) {
                Logger.warn("RAID", "Raid pas rejoint", "code : " + (refusal ?: errors.ifEmpty { "?" }))
                return@launch
            }
            Logger.success("RAID", "Raid rejoint", "bonus de points → $target")
            if (gen == generation || raid?.id == raidId) bonusRaidId = raidId
        }
    }

    private fun iso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    companion object {
        private val ENDPOINT = "wss://hermes.twitch.tv/v1?clientId=${WebSession.CLIENT_ID}"
        /** Requête enregistrée de twitch.tv (reprise de TwitchChannelPointsMiner,
         *  comme l'iPhone) ; écrite en toutes lettres si Twitch ne la connaît pas. */
        private const val JOIN_RAID_HASH = "c6a332a86d1087fbbb1a8623aa01bd1313d2386e7c63be60fdb2d1901f01a4ae"
        private const val JOIN_RAID_QUERY = "mutation JoinRaid(\$input: JoinRaidInput!) { joinRaid(input: \$input) { raidID } }"
    }
}
