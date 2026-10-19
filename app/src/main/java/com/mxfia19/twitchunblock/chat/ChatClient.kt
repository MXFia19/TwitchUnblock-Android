package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Chat d'une chaîne par l'IRC de Twitch (WebSocket) : anonyme sans compte,
 * au nom du compte sinon (écrire). Messages, abonnements et raids, messages
 * supprimés, historique récent (recent-messages), reconnexion automatique.
 * `scope` : sur le fil principal (l'état est lu par l'interface).
 */
class ChatClient(val channel: String, private val scope: CoroutineScope) {
    val messages = mutableStateListOf<ChatMessage>()
    var connected by mutableStateOf(false)
        private set
    /** Écrire est possible (compte connecté, IRC authentifié). */
    var canSend by mutableStateOf(false)
        private set

    private var channelId: String? = null
    /** Identifiant de la chaîne (emotes), connu au plus tard à ROOMSTATE. */
    val roomId: String? get() = channelId
    private var socket: WebSocket? = null
    private var stopped = false
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var generation = 0
    private var myColor: Int? = null
    private var myBadges: List<String> = emptyList()
    private var myName: String? = null
    /** Jeton de la connexion en cours (null : anonyme). */
    private var usedToken: String? = null
    /** Jeton refusé par l'IRC : renouvelé une fois, sinon chat anonyme. */
    private var renewing = false
    private var renewTried = false
    private var anonymous = false

    fun start(channelIdHint: String?) {
        channelId = channelIdHint
        scope.launch {
            Emotes.loadGlobal()
            Badges.loadGlobal()
        }
        channelIdHint?.let { loadChannelAssets(it) }
        if (com.mxfia19.twitchunblock.core.Features.chatLoadHistory.value) scope.launch { loadHistory() }
        connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        connected = false
        canSend = false
    }

    private fun loadChannelAssets(id: String) {
        scope.launch {
            Emotes.loadChannel(id)
            Badges.loadChannel(id)
        }
    }

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        val token = if (anonymous) null else AppState.token
        val login = AppState.user?.login
        usedToken = token
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands twitch.tv/membership")
                if (token != null && login != null) {
                    webSocket.send("PASS oauth:$token")
                    webSocket.send("NICK ${login.lowercase()}")
                } else {
                    webSocket.send("NICK justinfan${Random.nextInt(10000, 99999)}")
                }
                webSocket.send("JOIN #$channel")
                scope.launch {
                    if (gen != generation) return@launch
                    connected = true
                    canSend = token != null && login != null
                    attempts = 0
                    Logger.success("CHAT", "Connecté", "#$channel" + if (canSend) " (compte)" else " (anonyme)")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Lignes découpées ici, hors du fil principal (gros chats) ;
                // le reste lit l'état de l'interface et les emotes : fil principal.
                val lines = text.split("\r\n").mapNotNull { raw -> if (raw.isBlank()) null else Irc.parse(raw) }
                if (lines.isEmpty()) return
                scope.launch { if (gen == generation) handle(webSocket, lines) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (gen == generation) dropped("${t.javaClass.simpleName}: ${t.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (gen == generation) dropped("fermé ($code)") }
            }
        })
    }

    /** Connexion perdue : reprise après 1, 2, 4… 30 s. */
    private fun dropped(why: String) {
        connected = false
        canSend = false
        // Jeton refusé en cours de renouvellement : authRefused reconnecte.
        if (stopped || renewing) return
        attempts++
        val wait = minOf(30_000L, 1000L shl minOf(attempts - 1, 5))
        Logger.warn("CHAT", "Connexion perdue", "$why · nouvel essai dans ${wait / 1000} s")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun reconnectNow() {
        socket?.close(1000, null)
        connect()
    }

    /** Jeton refusé par l'IRC (expiré) : sinon chaque reconnexion le
     *  renvoyait, chat vide sans fin. Renouvelé une fois puis reconnexion ;
     *  à défaut, le chat continue en anonyme (lecture seule). */
    private fun authRefused() {
        val refused = usedToken ?: return
        if (renewing) return
        renewing = true
        reconnectJob?.cancel()
        scope.launch {
            val renewed = !renewTried && Auth.renew(refused) == Auth.Renewal.RENEWED
            renewTried = true
            renewing = false
            if (stopped) return@launch
            if (!renewed) {
                anonymous = true
                Logger.warn("CHAT", "Jeton refusé", "#$channel en anonyme")
            }
            reconnectNow()
        }
    }

    private fun handle(ws: WebSocket, lines: List<IrcLine>) {
        for (line in lines) {
            when (line.command) {
                "PING" -> ws.send("PONG :tmi.twitch.tv")
                "PRIVMSG" -> build(line)?.let { add(it) }
                "USERNOTICE" -> notice(line)
                "CLEARMSG" -> line.tags["target-msg-id"]?.let { id -> markDeleted { it.id == id } }
                "CLEARCHAT" -> {
                    val target = line.trailing?.lowercase()
                    if (target != null) markDeleted { it.login == target }
                    else add(system(AppState.t("chat_cleared")))
                }
                "USERSTATE", "GLOBALUSERSTATE" -> {
                    line.tags["color"]?.let { myColor = ChatColors.parse(it, AppState.user?.login ?: "") }
                    line.tags["badges"]?.let { b -> myBadges = b.split(',').filter { it.contains('/') } }
                    line.tags["display-name"]?.takeIf { it.isNotEmpty() }?.let { myName = it }
                }
                "ROOMSTATE" -> line.tags["room-id"]?.takeIf { it.isNotEmpty() }?.let { id ->
                    if (channelId == null) {
                        channelId = id
                        loadChannelAssets(id)
                    }
                }
                "NOTICE" -> line.trailing?.let { msg ->
                    // Connexion refusée (jeton expiré…) : jeton renouvelé, sinon
                    // le chat reste lisible en anonyme.
                    if (msg.contains("Login authentication failed") || msg.contains("Improperly formatted auth")) {
                        Logger.warn("CHAT", "Authentification refusée", msg)
                        canSend = false
                        authRefused()
                    }
                    add(system(msg))
                }
                "RECONNECT" -> reconnectNow()
            }
        }
    }

    private fun add(m: ChatMessage) {
        // Identifiants uniques (clés de la liste) : un message reçu deux fois est ignoré.
        if (messages.any { it.id == m.id }) return
        messages.add(m)
        while (messages.size > 300) messages.removeAt(0)
    }

    private fun markDeleted(match: (ChatMessage) -> Boolean) {
        for (i in messages.indices) {
            if (match(messages[i]) && !messages[i].deleted) messages[i] = messages[i].copy(deleted = true)
        }
    }

    private fun system(text: String) = ChatMessage(
        id = "sys-${System.nanoTime()}", login = "", name = "", color = 0, badges = emptyList(),
        tokens = emptyList(), notice = text,
    )

    /** Abonnement, raid, cadeau… : le texte du système, et le message de la personne s'il y en a un. */
    private fun notice(line: IrcLine) {
        val systemText = line.tags["system-msg"]?.takeIf { it.isNotEmpty() } ?: return
        val base = build(line)
        add((base ?: system(systemText)).copy(notice = systemText))
    }

    /** Mention de notre pseudo : expression compilée une fois par pseudo, et
     *  non à chaque message. */
    private var mentionRegex: Regex? = null
    private var mentionLogin: String? = null

    private fun mention(me: String): Regex {
        val cached = mentionRegex
        if (cached != null && mentionLogin == me) return cached
        return Regex("(^|[^a-z0-9_])@?" + Regex.escape(me) + "([^a-z0-9_]|$)").also {
            mentionRegex = it
            mentionLogin = me
        }
    }

    private fun build(line: IrcLine): ChatMessage? {
        val login = line.tags["login"] ?: line.nick ?: return null
        var text = line.trailing ?: ""
        var action = false
        // /me : « \u0001ACTION texte\u0001 »
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            action = true
            text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
        }
        if (text.isEmpty() && line.command == "PRIVMSG") return null
        val me = AppState.user?.login?.lowercase()
        val tokens = if (text.isEmpty()) emptyList() else ChatText.tokens(text, line.tags["emotes"], channelId)
        val highlight = me != null && login != me && mention(me).containsMatchIn(text.lowercase())
        return ChatMessage(
            id = line.tags["id"] ?: "m-${System.nanoTime()}",
            login = login,
            name = line.tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login,
            color = ChatColors.parse(line.tags["color"], login),
            badges = (line.tags["badges"] ?: "").split(',').filter { it.contains('/') }.mapNotNull { Badges.url(it, channelId) },
            tokens = tokens,
            action = action,
            highlight = highlight,
            time = line.tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    /** Envoie un message (compte connecté). true s'il est parti. */
    fun send(input: String): Boolean {
        val ws = socket ?: return false
        val text = input.replace('\n', ' ').trim()
        if (text.isEmpty() || !canSend) return false
        if (!ws.send("PRIVMSG #$channel :$text")) return false
        val me = AppState.user ?: return true
        // Twitch ne renvoie pas nos messages : écho local (les commandes
        // « / » autres que /me n'en ont pas).
        if (text.startsWith("/") && !text.startsWith("/me ")) return true
        val action = text.startsWith("/me ")
        val body = if (action) text.removePrefix("/me ") else text
        add(ChatMessage(
            id = "local-${System.nanoTime()}",
            login = me.login,
            name = myName ?: me.displayName,
            color = myColor ?: ChatColors.parse(null, me.login),
            badges = myBadges.mapNotNull { Badges.url(it, channelId) },
            tokens = ChatText.tokens(body, null, channelId),
            action = action,
        ))
        return true
    }

    /** 50 derniers messages (recent-messages.robotty.de), placés avant ceux reçus depuis. */
    private suspend fun loadHistory() {
        val r = Http.get("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=50", timeout = 10) ?: return
        val lines = parseJson(r.body)?.arr("messages")?.strings() ?: return
        val old = lines.mapNotNull { raw -> Irc.parse(raw)?.takeIf { it.command == "PRIVMSG" }?.let { build(it) } }
        if (old.isEmpty()) return
        val known = messages.map { it.id }.toHashSet()
        messages.addAll(0, old.filter { it.id !in known }.distinctBy { it.id })
        while (messages.size > 300) messages.removeAt(0)
    }
}

/** Couleurs des pseudos : celle de Twitch, sinon une couleur par défaut tirée
 *  du pseudo, éclaircie si elle se lit mal sur fond sombre. */
object ChatColors {
    private val defaults = listOf(
        0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFFB22222, 0xFFFF7F50, 0xFF9ACD32, 0xFFFF4500, 0xFF2E8B57,
        0xFFDAA520, 0xFFD2691E, 0xFF5F9EA0, 0xFF1E90FF, 0xFFFF69B4, 0xFF8A2BE2, 0xFF00FF7F,
    ).map { it.toInt() }

    fun parse(hex: String?, login: String): Int {
        val base = hex?.takeIf { it.length == 7 && it.startsWith("#") }?.substring(1)?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            ?: defaults[(login.hashCode() and 0x7FFFFFFF) % defaults.size]
        return readable(base)
    }

    private fun readable(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        if (lum >= 0.45) return c
        val mix = { v: Int -> (v + (255 - v) * 0.45).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }
}

/** Découpe d'un message en morceaux : emotes Twitch (tag « emotes », par
 *  points de code), emotes 7TV/BTTV/FFZ par mot, mentions, liens. */
object ChatText {
    fun tokens(text: String, emotesTag: String?, channelId: String?): List<ChatToken> {
        val cps = text.codePoints().toArray()
        // Plages des emotes Twitch : début → (fin, id).
        val ranges = HashMap<Int, Pair<Int, String>>()
        if (!emotesTag.isNullOrEmpty()) {
            for (part in emotesTag.split('/')) {
                val id = part.substringBefore(':')
                for (r in part.substringAfter(':', "").split(',')) {
                    val a = r.substringBefore('-').toIntOrNull() ?: continue
                    val b = r.substringAfter('-').toIntOrNull() ?: continue
                    if (a in 0..b && b < cps.size) ranges[a] = Pair(b, id)
                }
            }
        }
        val out = mutableListOf<ChatToken>()
        val segment = StringBuilder()
        fun flushSegment() {
            if (segment.isEmpty()) return
            words(segment.toString(), channelId, out)
            segment.setLength(0)
        }
        var i = 0
        while (i < cps.size) {
            val r = ranges[i]
            if (r != null) {
                flushSegment()
                val name = String(cps, i, r.first - i + 1)
                out += ChatToken.Emote(name, "https://static-cdn.jtvnw.net/emoticons/v2/${r.second}/default/dark/2.0")
                i = r.first + 1
            } else {
                segment.appendCodePoint(cps[i])
                i++
            }
        }
        flushSegment()
        return merge(out)
    }

    private fun words(s: String, channelId: String?, out: MutableList<ChatToken>) {
        val parts = s.split(' ')
        for ((index, w) in parts.withIndex()) {
            if (index > 0) out += ChatToken.Text(" ")
            if (w.isEmpty()) continue
            val emote = Emotes.find(w, channelId)
            when {
                emote != null -> out += ChatToken.Emote(emote.name, emote.url, emote.ratio, emote.zeroWidth)
                w.startsWith("@") && w.length > 1 -> out += ChatToken.Mention(w)
                w.startsWith("http://") || w.startsWith("https://") -> out += ChatToken.Link(w)
                else -> out += ChatToken.Text(w)
            }
        }
    }

    private fun merge(list: List<ChatToken>): List<ChatToken> {
        val out = mutableListOf<ChatToken>()
        for (t in list) {
            val last = out.lastOrNull()
            if (t is ChatToken.Text && last is ChatToken.Text) out[out.size - 1] = ChatToken.Text(last.text + t.text)
            else out += t
        }
        return out
    }
}
