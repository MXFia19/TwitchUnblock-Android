package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Connexion en direct de BetterTTV (wss://sockets.betterttv.net/ws, le
 * protocole de son extension) : emotes personnelles des abonnés BTTV Pro
 * (« lookup_user », annoncées quand ils ouvrent le chat, valables pour leurs
 * messages seulement) et emotes ajoutées, renommées ou retirées sur la
 * chaîne pendant le direct. Une seule connexion pour tous les chats ouverts
 * (multi-live compris). Fil principal. Comme BTTVLive (iOS).
 */
object BttvLive {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var socket: WebSocket? = null
    /** Chaînes suivies → nombre de chats ouverts dessus. */
    private val channels = HashMap<String, Int>()
    private var retry = 2_000L
    private var generation = 0
    private var reconnect: Job? = null

    fun join(channelId: String) {
        if (channelId.isEmpty()) return
        val n = (channels[channelId] ?: 0) + 1
        channels[channelId] = n
        if (socket == null) connect() else if (n == 1) send("join_channel", channelId)
    }

    fun leave(channelId: String) {
        val n = channels[channelId] ?: return
        if (n > 1) {
            channels[channelId] = n - 1
            return
        }
        channels.remove(channelId)
        send("part_channel", channelId)
        if (channels.isEmpty()) {
            generation++
            reconnect?.cancel()
            socket?.close(1000, null)
            socket = null
        }
    }

    private fun connect() {
        val gen = ++generation
        val request = Request.Builder().url("wss://sockets.betterttv.net/ws").build()
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.launch {
                    if (gen != generation) return@launch
                    retry = 2_000L
                    for (id in channels.keys) send("join_channel", id)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.launch { if (gen == generation) handle(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.launch { if (gen == generation) dropped() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.launch { if (gen == generation) dropped() }
            }
        })
    }

    /** Coupure : nouvel essai après 2, 4… 60 s, tant qu'un chat est ouvert. */
    private fun dropped() {
        socket = null
        if (channels.isEmpty()) return
        val wait = retry
        retry = minOf(60_000L, retry * 2)
        reconnect?.cancel()
        reconnect = main.launch {
            delay(wait)
            if (socket == null && channels.isNotEmpty()) connect()
        }
    }

    private fun send(name: String, channelId: String) {
        val body = JSONObject().put("name", name).put("data", JSONObject().put("name", "twitch:$channelId"))
        socket?.send(body.toString())
    }

    private fun handle(text: String) {
        val obj = parseJson(text) ?: return
        val d = obj.obj("data") ?: return
        // « twitch:<id de la chaîne> »
        val channelId = d.str("channel")?.substringAfterLast(':').orEmpty()
        when (obj.str("name")) {
            "lookup_user" -> {
                val uid = d.str("providerId") ?: return
                val list = d.arr("emotes")?.objects().orEmpty()
                // Plus Pro, ou plus aucune emote : les siennes disparaissent.
                if (d.bool("pro") != true || list.isEmpty()) {
                    Emotes.removePersonal(uid)
                    return
                }
                Emotes.setPersonal(uid, list)
                Logger.debug("EMOTES", "Emotes personnelles BTTV", "${list.size} pour $uid")
            }
            "emote_create", "emote_update" -> {
                val e = d.obj("emote") ?: return
                if (channelId.isEmpty()) return
                Emotes.upsertChannelBttv(channelId, e)
                Logger.info("EMOTES", "Emote BTTV de la chaîne mise à jour", e.str("code") ?: "")
            }
            "emote_delete" -> {
                val id = d.str("emoteId") ?: return
                if (channelId.isNotEmpty()) Emotes.removeChannelBttv(channelId, id)
            }
        }
    }
}
