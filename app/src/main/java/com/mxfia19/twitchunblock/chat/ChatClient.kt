package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
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
    /** Lecture seule : Twitch refuse le compte dans ce salon (banni, NOTICE
     *  msg_banned au JOIN) ; le chat continue sans compte, rien ne s'envoie. */
    var readOnly by mutableStateOf(false)
        private set
    /** Modes du salon qui limitent qui peut écrire (ROOMSTATE, réglages GQL). */
    var modes by mutableStateOf(RoomModes())
        private set
    /** Règles du chat de la chaîne, à accepter avant le premier message. */
    var rules by mutableStateOf<List<String>>(emptyList())
        private set
    /** Chat du lecteur principal : les messages reçus attendent que l'image
     *  les rejoigne (ChatSync). Faux en multi-live et sur la TV. */
    var syncsWithPlayer by mutableStateOf(false)
    /** Message en cours d'écriture : gardé ici, il survit à la rotation et au
     *  panneau des récompenses (ChatPanel y est appelé ailleurs dans l'arbre). */
    var draft by mutableStateOf(TextFieldValue(""))
    /** Augmenté quand les messages déjà affichés sont refaits (emotes et
     *  badges arrivés après eux) : le chat se recolle en bas. */
    var assetsRevision by mutableStateOf(0)
        private set
    /** Personnes présentes (liste NAMES puis JOIN/PART de l'IRC) : Twitch ne
     *  les envoie que sur les chaînes de taille modeste. */
    private val present = HashSet<String>()
    var chattersVersion by mutableStateOf(0)
        private set
    /** Twitch a envoyé au moins une liste de noms pour ce salon. */
    var presenceSupported by mutableStateOf(false)
        private set
    /** Chaîne suivie en direct par BetterTTV (emotes ajoutées, BTTV Pro). */
    private var bttvChannel: String? = null

    /** État : le sélecteur d'emotes suit l'identifiant donné par ROOMSTATE. */
    private var channelId by mutableStateOf<String?>(null)
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
    /** Dernière reconnexion sur un PART ou un CLEARCHAT à notre nom (une par 15 s). */
    private var lastRejoin = 0L
    private var rejoinJob: Job? = null

    /** Message reçu en attente du retard du chat, avec son heure d'arrivée
     *  (la modération peut encore le marquer supprimé). */
    private class Pending(var message: ChatMessage, val arrivedAt: Long)
    /** File de la synchro, dans l'ordre d'arrivée. Jamais nos messages ni les
     *  lignes de l'app : affichés tout de suite. */
    private val pending = ArrayDeque<Pending>()
    /** Sortie de la file au fil du temps ; arrêtée quand elle est vide. */
    private var releaseJob: Job? = null
    /** Retard appliqué à la file (lissé), sa dernière mise à jour et la
     *  vitesse du rattrapage en cours (s regagnées par seconde). */
    private var appliedDelay = 0.0
    private var appliedAt = 0L
    private var catchUpRate = 0.0

    fun start(channelIdHint: String?) {
        channelId = channelIdHint
        // Salon déjà refusé à ce compte pendant cette séance : directement
        // sans compte, le bandeau tout de suite.
        if (readOnlyKey()?.let { it in readOnlyKeys } == true) {
            readOnly = true
            anonymous = true
        }
        // Emotes et badges en parallèle, sans retarder l'historique ni l'IRC :
        // les messages arrivés avant eux sont refaits à chaque chargement fini.
        scope.launch {
            Emotes.loadGlobal()
            refreshAssets()
        }
        scope.launch {
            Badges.loadGlobal()
            refreshAssets()
        }
        channelIdHint?.let {
            loadChannelAssets(it)
            followBttv(it)
        }
        if (Features.chatLoadHistory.value) scope.launch { loadHistory() }
        // Règles et modes : utiles seulement à qui peut écrire.
        if (AppState.user != null) scope.launch { loadChatSettings() }
        connect()
    }

    fun stop() {
        stopped = true
        bttvChannel?.let { BttvLive.leave(it) }
        bttvChannel = null
        reconnectJob?.cancel()
        rejoinJob?.cancel()
        releaseJob?.cancel()
        pending.clear()
        socket?.close(1000, null)
        socket = null
        connected = false
        canSend = false
    }

    /** Bandeau « Réessayer avec mon compte » : Twitch ne dit rien d'un déban
     *  sur l'IRC. Encore banni : le JOIN remet la lecture seule. */
    fun retryWithAccount() {
        readOnlyKey()?.let { readOnlyKeys.remove(it) }
        readOnly = false
        anonymous = false
        attempts = 0
        Logger.info("CHAT", "Nouvel essai avec le compte", "#$channel")
        reconnectNow()
    }

    /** Menu ⋯ : « Reconnecter le chat », sans quitter le direct. */
    fun reconnect() {
        if (stopped) return
        attempts = 0
        Logger.info("CHAT", "Reconnexion demandée", "#$channel")
        reconnectNow()
    }

    /** Menu ⋯ : emotes et badges oubliés puis relus ; les messages déjà là
     *  sont refaits avec eux. */
    fun reloadAssets() {
        if (stopped) return
        Emotes.reset()
        Badges.reset()
        TwitchEmotes.reset()
        scope.launch {
            Emotes.loadGlobal()
            refreshAssets()
        }
        scope.launch {
            Badges.loadGlobal()
            refreshAssets()
        }
        channelId?.let { loadChannelAssets(it) }
        Logger.success("CHAT", "Emotes et badges rechargés", "#$channel")
    }

    /** Personnes présentes, par ordre alphabétique. */
    fun chatters(): List<String> = present.sorted()

    /** Fil de réponses dont la racine est `rootId`, du plus ancien au plus récent. */
    fun threadMessages(rootId: String): List<ChatMessage> =
        messages.filter { it.id == rootId || it.threadId == rootId }.sortedBy { it.time }

    /** Emotes BetterTTV en direct de cette chaîne (une seule à la fois). */
    private fun followBttv(id: String) {
        if (stopped || bttvChannel == id) return
        bttvChannel?.let { BttvLive.leave(it) }
        bttvChannel = id
        BttvLive.join(id)
    }

    private fun readOnlyKey(): String? {
        val login = AppState.user?.login?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return "$login#${channel.lowercase()}"
    }

    /** Twitch refuse le compte (banni) : le salon est rouvert sans lui, comme
     *  un spectateur déconnecté. Comme enterReadOnly (iOS). */
    private fun enterReadOnly() {
        if (readOnly) return
        readOnly = true
        canSend = false
        anonymous = true
        readOnlyKey()?.let { readOnlyKeys.add(it) }
        add(system(AppState.t("chat_ban_switched")))
        Logger.warn("CHAT", "Compte refusé dans #$channel (banni)", "lecture seule, sans compte")
        reconnectNow()
    }

    /** PART ou CLEARCHAT sans durée à notre nom : signal ambigu (ban, coupure).
     *  On rejoint avec le compte ; si le ban est réel, le msg_banned du JOIN
     *  fait passer en lecture seule. Une fois par 15 s au plus. */
    private fun rejoinWithAccount(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRejoin < 15_000) {
            if (rejoinJob?.isActive == true) return
            val wait = 15_000 - (now - lastRejoin)
            rejoinJob = scope.launch {
                delay(wait)
                if (!stopped && !readOnly) rejoinWithAccount(reason)
            }
            return
        }
        lastRejoin = now
        Logger.warn("CHAT", "$reason à notre nom → #$channel", "reconnexion avec le compte")
        reconnectNow()
    }

    private fun loadChannelAssets(id: String) {
        scope.launch {
            Emotes.loadChannel(id)
            if (id == channelId) refreshAssets()
        }
        scope.launch {
            Badges.loadChannel(id)
            if (id == channelId) refreshAssets()
        }
    }

    /** Emotes 7TV/BTTV/FFZ et badges des messages déjà là (historique,
     *  premiers reçus, file de la synchro), refaits avec ce qui est chargé
     *  et l'identifiant de la chaîne en cours. */
    private fun refreshAssets() {
        if (stopped) return
        val id = channelId
        fun redo(m: ChatMessage): ChatMessage {
            if (m.login.isEmpty()) return m
            val tokens = ChatText.retokenize(m.tokens, id, m.userId)
            val badges = m.badgeIds.mapNotNull { Badges.url(it, id) }
            return if (tokens == m.tokens && badges == m.badges) m else m.copy(tokens = tokens, badges = badges)
        }
        var changed = false
        for (i in messages.indices) {
            val m = messages[i]
            val next = redo(m)
            if (next !== m) {
                messages[i] = next
                changed = true
            }
        }
        for (p in pending) p.message = redo(p.message)
        if (changed) assetsRevision++
    }

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        // Présents relus à chaque connexion (liste NAMES) : ceux partis pendant
        // une coupure n'y restent pas.
        if (present.isNotEmpty()) {
            present.clear()
            chattersVersion++
        }
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
                    Logger.success("CHAT", "Connecté", "#$channel" + when {
                        canSend -> " (compte)"
                        readOnly -> " (lecture seule, compte refusé)"
                        else -> " (anonyme)"
                    })
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
        // Troisième échec d'affilée (VPN, réseau filtré…) : dit dans le chat,
        // avec « Reconnecter le chat » du menu ⋯, au lieu de rester figé.
        if (attempts == 3) add(system(AppState.t("chat_unreachable")))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun reconnectNow() {
        // Une reprise programmée ouvrirait sinon une seconde connexion.
        reconnectJob?.cancel()
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
                "PRIVMSG" -> build(line)?.let { publish(it) }
                "USERNOTICE" -> notice(line)
                "CLEARMSG" -> line.tags["target-msg-id"]?.let { id -> markDeleted { it.id == id } }
                "CLEARCHAT" -> clearChat(line)
                "USERSTATE", "GLOBALUSERSTATE" -> {
                    line.tags["color"]?.let { myColor = ChatColors.parse(it, AppState.user?.login ?: "") }
                    line.tags["badges"]?.let { b -> myBadges = b.split(',').filter { it.contains('/') } }
                    line.tags["display-name"]?.takeIf { it.isNotEmpty() }?.let { myName = it }
                    // Jeux d'emotes du compte : « Mes emotes », emotes d'abonné
                    // verrouillées, écho de nos messages.
                    val login = AppState.user?.login
                    val sets = line.tags["emote-sets"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    if (login != null && usedToken != null && !sets.isNullOrEmpty()) {
                        scope.launch { TwitchEmotes.setUserSets(sets, login) }
                    }
                }
                "353" -> {
                    // Liste des présents à l'arrivée : « = #salon :nom1 nom2… ».
                    line.trailing?.split(' ')?.filter { it.isNotEmpty() }?.let { names ->
                        // Sans les spectateurs anonymes (justinfan…), dont nous sans compte.
                        present.addAll(names.map { it.lowercase() }.filter { !it.startsWith("justinfan") })
                        presenceSupported = true
                        chattersVersion++
                    }
                }
                "366" -> presenceSupported = true
                "JOIN" -> (line.tags["login"] ?: line.nick)?.lowercase()?.takeIf { !it.startsWith("justinfan") }?.let { who ->
                    if (present.add(who)) chattersVersion++
                }
                "ROOMSTATE" -> {
                    roomState(line.tags)
                    line.tags["room-id"]?.takeIf { it.isNotEmpty() }?.let { id ->
                        // Twitch fait foi : l'identifiant donné à l'ouverture
                        // peut être celui d'une autre chaîne (session passée
                        // d'une VOD à un direct) ; emotes et badges refaits.
                        if (channelId != id) {
                            if (channelId != null) Logger.warn("CHAT", "Identifiant de #$channel corrigé", "$channelId → $id")
                            channelId = id
                            loadChannelAssets(id)
                            refreshAssets()
                        }
                        followBttv(id)
                    }
                }
                "NOTICE" -> handleNotice(line)
                "PART" -> {
                    // Twitch nous sort du salon (ban en plein direct, coupure) : on
                    // y retourne avec le compte, le JOIN dira s'il est banni.
                    val who = (line.tags["login"] ?: line.nick)?.lowercase()
                    val me = AppState.user?.login?.lowercase()
                    if (who != null && who == me && usedToken != null && !readOnly) rejoinWithAccount("PART")
                    else if (who != null && present.remove(who)) chattersVersion++
                }
                "RECONNECT" -> reconnectNow()
            }
        }
    }

    private fun handleNotice(line: IrcLine) {
        val msg = line.trailing ?: return
        val id = line.tags["msg-id"].orEmpty()
        // Connexion refusée (jeton expiré…) : jeton renouvelé, sinon le chat
        // reste lisible en anonyme.
        if (msg.contains("Login authentication failed") || msg.contains("Improperly formatted auth")) {
            Logger.warn("CHAT", "Authentification refusée", msg)
            canSend = false
            authRefused()
            add(system(msg))
            return
        }
        // Compte banni de ce salon : Twitch le refuse au JOIN (et à l'envoi).
        // Pas un message refusé : le chat continue en lecture seule, sans compte.
        if (id == "msg_banned") {
            if (usedToken != null && !readOnly) {
                removeRecentEcho()
                enterReadOnly()
            }
            return
        }
        // Message refusé (mode abonnés, followers, lent…) : la raison dans la
        // langue de l'app, et notre message, affiché d'avance comme envoyé,
        // retiré — sinon on le croyait passé.
        val key = chatRejections[id] ?: if (id.startsWith("msg_")) "chat_rej_generic" else null
        if (key != null) {
            Logger.warn("CHAT", "Message refusé : $id", msg)
            removeRecentEcho()
            val minutes = modes.followersMinutes ?: 0
            add(system(AppState.t(key)
                .replace("{d}", if (minutes > 0) RoomModes.duration(minutes) else "")
                .replace("{t}", msg)))
            return
        }
        add(system(msg))
    }

    private fun clearChat(line: IrcLine) {
        val target = line.trailing?.lowercase()
        if (target == null) {
            // Purge du salon (/clear) : la liste est vidée, même avec « garder
            // les messages supprimés » (comme l'iPhone) ; les messages qui
            // attendaient l'image partent avec.
            pending.clear()
            messages.clear()
            add(system(AppState.t("chat_cleared")))
            return
        }
        markDeleted { it.login == target }
        // Nous visés, avec le compte (sans compte, les exclusions ne nous concernent pas).
        val me = AppState.user?.login?.lowercase()
        if (target != me || usedToken == null || readOnly) return
        val seconds = line.tags["ban-duration"]?.toIntOrNull()
        if (seconds != null) {
            // Exclusion temporaire : on reste dans le salon, Twitch refusera
            // les messages d'ici là (msg_timedout).
            Logger.warn("CHAT", "Exclu de #$channel", "$seconds s")
            add(system(AppState.t("chat_timed_out").replace("{d}", RoomModes.durationSeconds(seconds))))
        } else {
            rejoinWithAccount("CLEARCHAT")
        }
    }

    /** ROOMSTATE : complet à l'arrivée, puis seulement ce qui change. */
    private fun roomState(tags: Map<String, String>) {
        var m = modes
        tags["followers-only"]?.toIntOrNull()?.let { m = m.copy(followersMinutes = if (it < 0) null else it) }
        tags["subs-only"]?.let { m = m.copy(subsOnly = it == "1") }
        tags["emote-only"]?.let { m = m.copy(emoteOnly = it == "1") }
        tags["slow"]?.toIntOrNull()?.let { m = m.copy(slowSeconds = maxOf(0, it)) }
        tags["r9k"]?.let { m = m.copy(uniqueChat = it == "1") }
        modes = m
    }

    /** Règles et modes du chat (GQL public, sans compte) : la vérification du
     *  compte n'est dite que là. */
    private suspend fun loadChatSettings() {
        val q = "query { user(login: \"${Gql.str(channel)}\") { chatSettings { rules followersOnlyDurationMinutes " +
            "isSubscribersOnlyModeEnabled isEmoteOnlyModeEnabled slowModeDurationSeconds requireVerifiedAccount " +
            "isUniqueChatModeEnabled } } }"
        val cs = Gql.raw(q)?.obj("data")?.obj("user")?.obj("chatSettings") ?: return
        if (stopped) return
        rules = cs.arr("rules")?.strings().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        modes = modes.copy(
            followersMinutes = cs.int("followersOnlyDurationMinutes")?.takeIf { it >= 0 },
            subsOnly = cs.bool("isSubscribersOnlyModeEnabled") == true,
            emoteOnly = cs.bool("isEmoteOnlyModeEnabled") == true,
            slowSeconds = maxOf(0, cs.int("slowModeDurationSeconds") ?: 0),
            uniqueChat = cs.bool("isUniqueChatModeEnabled") == true,
            verifiedOnly = cs.bool("requireVerifiedAccount") == true,
        )
    }

    /** Retire notre dernier message, affiché d'avance comme envoyé, quand
     *  Twitch le refuse dans la foulée. */
    private fun removeRecentEcho() {
        val i = messages.indexOfLast { it.id.startsWith("local-") }
        if (i >= 0 && System.currentTimeMillis() - messages[i].time < 20_000) messages.removeAt(i)
    }

    // ── Synchro avec la vidéo (ChatSync) ──

    /** Message reçu : affiché tout de suite, ou une fois rejoint par l'image.
     *  Sans retard et rien en attente : tout de suite ; sinon derrière ceux
     *  qui attendent déjà. */
    private fun publish(m: ChatMessage) {
        val now = System.currentTimeMillis()
        if (currentDelay(now) < 0.5 && pending.isEmpty()) {
            add(m)
            return
        }
        pending.addLast(Pending(m, now))
        startRelease()
    }

    /** Retard voulu : celui de l'image, moins la marge de réaction, plus la
     *  retouche ; 0 si la synchro est coupée ou le retard inconnu. */
    private fun syncTarget(now: Long): Double {
        if (!syncsWithPlayer || !ChatPrefs.sync.value) return 0.0
        val player = ChatSync.playerDelay(now) ?: return 0.0
        return ChatSync.chatDelay(player, ChatPrefs.syncNudge.value.toDouble())
    }

    /** Retard appliqué à la file. Une hausse (calage) est suivie tout de
     *  suite ; une baisse (retour au direct, synchro coupée) en quelques
     *  secondes : les messages en attente sortent dans l'ordre, en accéléré,
     *  sans tout lâcher d'un coup. */
    private fun currentDelay(now: Long): Double {
        val target = syncTarget(now)
        val elapsed = ((now - appliedAt) / 1000.0).coerceIn(0.0, 1.0)
        appliedAt = now
        if (pending.isEmpty() || target >= appliedDelay) {
            appliedDelay = target
            catchUpRate = 0.0
        } else {
            // Vitesse fixée au début du rattrapage ; une nouvelle baisse l'accélère.
            catchUpRate = maxOf(catchUpRate, MIN_CATCH_UP_RATE, (appliedDelay - target) / CATCH_UP_TIME)
            appliedDelay = maxOf(target, appliedDelay - catchUpRate * elapsed)
        }
        return appliedDelay
    }

    private fun startRelease() {
        if (releaseJob?.isActive == true || pending.isEmpty()) return
        releaseJob = scope.launch {
            while (pending.isNotEmpty() && !stopped) {
                delay(150)
                releaseDue()
            }
        }
    }

    /** Rend, dans l'ordre d'arrivée, les messages dont l'heure est venue ;
     *  file trop longue : les plus anciens aussi, en avance. */
    private fun releaseDue() {
        val now = System.currentTimeMillis()
        val horizon = now - (currentDelay(now) * 1000).toLong()
        val ripe = pending.indexOfFirst { it.arrivedAt > horizon }.let { if (it < 0) pending.size else it }
        val count = maxOf(ripe, pending.size - MAX_PENDING)
        repeat(count) { add(pending.removeFirst().message) }
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
        // Messages qui attendaient l'image (synchro) : la modération s'y applique
        // aussi, sinon un spam supprimé s'affichait intact quelques secondes après.
        for (p in pending) if (match(p.message) && !p.message.deleted) p.message = p.message.copy(deleted = true)
    }

    private fun system(text: String) = ChatMessage(
        id = "sys-${System.nanoTime()}", login = "", name = "", color = 0, badges = emptyList(),
        tokens = emptyList(), notice = text,
    )

    private fun notice(line: IrcLine) {
        noticeMessage(line)?.let { publish(it) }
    }

    /** Abonnement, raid, cadeau, annonce… : le texte du système, et le message
     *  de la personne s'il y en a un. Annonce (/announce) : system-msg vide, le
     *  message mis en avant dans sa couleur, comme buildNotice (iOS). Partagé
     *  par le direct et l'historique. */
    private fun noticeMessage(line: IrcLine, historical: Boolean = false): ChatMessage? {
        val kind = line.tags["msg-id"]
        val announce = kind == "announcement"
        var systemText = if (announce) null else line.tags["system-msg"]?.takeIf { it.isNotBlank() }
        var raider: String? = null
        if (kind == "raid") {
            // Raid entrant : la chaîne qui arrive (lien vers elle) et le nombre
            // de spectateurs mis en forme, dans la langue de l'app.
            raider = line.tags["msg-param-login"]?.lowercase()?.takeIf { Regex("^[a-z0-9_]{1,25}$").matches(it) }
            val who = line.tags["msg-param-displayName"]?.takeIf { it.isNotBlank() } ?: raider
            val count = line.tags["msg-param-viewerCount"]?.toIntOrNull()
            if (who != null && count != null) {
                systemText = AppState.tn("c6_raid_incoming", count).replace("{u}", who).replace("{n}", Format.viewers(count))
            }
        }
        val built = build(line, historical)
        val base = if (raider != null) built?.copy(raider = raider) else built
        if (base == null || base.tokens.isEmpty()) {
            // Sans texte de la personne (abonnement offert, raid) : la notice
            // seule ; chatLine n'écrit pas de « Pseudo : » vide dessous.
            val notice = systemText ?: return null
            return (base ?: system(notice)).copy(notice = notice, historical = historical, raider = raider)
        }
        if (announce) return base.copy(accent = announceColor(line.tags["msg-param-color"]))
        return base.copy(notice = systemText)
    }

    /** Couleur d'une annonce (msg-param-color), comme l'iPhone et le site. */
    private fun announceColor(name: String?): Int = when (name?.uppercase()) {
        "BLUE" -> 0xFF387AFF
        "GREEN" -> 0xFF00C853
        "ORANGE" -> 0xFFFF9A00
        "PURPLE" -> 0xFFBF94FF
        else -> 0xFF9146FF
    }.toInt()

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

    private fun build(line: IrcLine, historical: Boolean = false): ChatMessage? {
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
        val userId = line.tags["user-id"]?.takeIf { it.isNotEmpty() }
        val tokens = if (text.isEmpty()) emptyList()
                     else ChatText.tokens(text, line.tags["emotes"], channelId, userId, line.tags["gifs"])
        val highlight = me != null && login != me && mention(me).containsMatchIn(text.lowercase())
        val badgeIds = (line.tags["badges"] ?: "").split(',').filter { it.contains('/') }
        return ChatMessage(
            id = line.tags["id"] ?: "m-${System.nanoTime()}",
            login = login,
            name = line.tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login,
            color = ChatColors.parse(line.tags["color"], login),
            badges = badgeIds.mapNotNull { Badges.url(it, channelId) },
            tokens = tokens,
            action = action,
            highlight = highlight,
            time = line.tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis(),
            badgeIds = badgeIds,
            userId = userId,
            replyTo = line.tags["reply-parent-display-name"]?.takeIf { it.isNotEmpty() }
                ?: line.tags["reply-parent-user-login"]?.takeIf { it.isNotEmpty() },
            replyBody = line.tags["reply-parent-msg-body"]?.takeIf { it.isNotEmpty() },
            parentId = line.tags["reply-parent-msg-id"]?.takeIf { it.isNotEmpty() },
            threadId = (line.tags["reply-thread-parent-msg-id"] ?: line.tags["reply-parent-msg-id"])?.takeIf { it.isNotEmpty() },
            firstMessage = line.tags["first-msg"] == "1",
            historical = historical,
        )
    }

    /** Envoie un message (compte connecté). true s'il est parti. `replyTo` :
     *  le message auquel il répond (fil de réponse, comme sur Twitch). */
    fun send(input: String, replyTo: ChatMessage? = null): Boolean {
        val ws = socket ?: return false
        val text = input.replace('\n', ' ').trim()
        if (text.isEmpty() || text.length > 500 || !canSend) return false
        // Identifiant venu des tags IRC : vérifié, un retour à la ligne y
        // ajouterait une commande IRC.
        val parent = replyTo?.id?.takeIf { id -> id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' } }
        val line = if (parent != null) "@reply-parent-msg-id=$parent PRIVMSG #$channel :$text" else "PRIVMSG #$channel :$text"
        if (!ws.send(line)) return false
        val me = AppState.user ?: return true
        // Twitch ne renvoie pas nos messages : écho local (les commandes
        // « / » autres que /me n'en ont pas).
        if (text.startsWith("/") && !text.startsWith("/me ")) return true
        val action = text.startsWith("/me ")
        val body = if (action) text.removePrefix("/me ") else text
        val room = channelId
        val parentMsg = if (parent != null) replyTo else null
        add(ChatMessage(
            id = "local-${System.nanoTime()}",
            login = me.login,
            name = myName ?: me.displayName,
            color = myColor ?: ChatColors.parse(null, me.login),
            badges = myBadges.mapNotNull { Badges.url(it, channelId) },
            // Sans tag emotes : les emotes Twitch retrouvées par leur nom
            // (vues dans ce chat, ou celles du compte).
            tokens = ChatText.tokens(body, null, room, twitchLookup = { w -> TwitchEmotes.byName(w, room) }),
            action = action,
            badgeIds = myBadges,
            replyTo = parentMsg?.name,
            replyBody = parentMsg?.tokens?.joinToString("") { textOf(it) }?.take(200),
            parentId = parent,
            threadId = parentMsg?.let { it.threadId ?: it.id },
        ))
        return true
    }

    private fun textOf(t: ChatToken): String = when (t) {
        is ChatToken.Text -> t.text
        is ChatToken.Emote -> t.name
        is ChatToken.Mention -> t.name
        is ChatToken.Link -> t.url
    }

    /** 50 derniers messages (recent-messages.robotty.de), placés avant ceux reçus
     *  depuis. La modération rejouée s'y applique dans l'ordre, comme
     *  loadRecentMessages (iOS) : messages supprimés et ceux des personnes
     *  bannies retirés, ou barrés si on garde les messages supprimés. */
    private suspend fun loadHistory() {
        val r = Http.get("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=50", timeout = 10) ?: return
        val lines = parseJson(r.body)?.arr("messages")?.strings() ?: return
        val keep = Features.chatKeepDeleted.value
        val old = ArrayList<ChatMessage>()
        fun moderate(match: (ChatMessage) -> Boolean) {
            if (keep) {
                for (i in old.indices) if (match(old[i])) old[i] = old[i].copy(deleted = true)
            } else {
                old.removeAll { match(it) }
            }
        }
        for (raw in lines) {
            val line = Irc.parse(raw) ?: continue
            when (line.command) {
                "PRIVMSG" -> {
                    val m = build(line, historical = true) ?: continue
                    // Supprimé depuis par la modération (marqué par le service).
                    if (line.tags["rm-deleted"] == "1") {
                        if (keep) old += m.copy(deleted = true)
                    } else {
                        old += m
                    }
                }
                "USERNOTICE" -> noticeMessage(line, historical = true)?.let { old += it }
                "CLEARMSG" -> line.tags["target-msg-id"]?.let { id -> moderate { it.id == id } }
                "CLEARCHAT" -> {
                    val target = line.trailing?.lowercase()
                    if (target.isNullOrEmpty()) {
                        // Chat vidé : rien de ce qui précède.
                        old.clear()
                        old += system(AppState.t("chat_cleared")).copy(
                            time = line.tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis())
                    } else {
                        moderate { it.login == target }
                    }
                }
            }
        }
        if (old.isEmpty()) return
        val known = messages.map { it.id }.toHashSet()
        messages.addAll(0, old.filter { it.id !in known }.distinctBy { it.id })
        while (messages.size > 300) messages.removeAt(0)
    }

    companion object {
        /** Couples « compte#chaîne » refusés pendant cette séance : rouvrir le
         *  chat (multi-live, retour au direct) repart directement sans compte. */
        private val readOnlyKeys = HashSet<String>()
        /** Au-delà (chat très rapide retardé d'une minute), les plus anciens
         *  sortent en avance plutôt que de grossir sans fin. */
        private const val MAX_PENDING = 1500
        /** Une baisse du retard se rattrape en 4 s environ, jamais à moins de
         *  2 s regagnées par seconde. */
        private const val CATCH_UP_TIME = 4.0
        private const val MIN_CATCH_UP_RATE = 2.0
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
    /** `userId` : l'auteur (ses emotes BTTV Pro) ; `gifsTag` : GIF du clavier
     *  GIF de Twitch ; `twitchLookup` : emote Twitch d'un mot sans tag emotes
     *  (écho de nos messages). */
    fun tokens(text: String, emotesTag: String?, channelId: String?, userId: String? = null, gifsTag: String? = null,
               twitchLookup: ((String) -> ChatToken.Emote?)? = null): List<ChatToken> {
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
        // GIF : « début-fin|id|adresse », positions en points de code comme
        // les emotes ; GIPHY seulement (règle de Twitch).
        val gifs = HashMap<Int, Pair<Int, String>>()
        if (!gifsTag.isNullOrEmpty()) {
            for (m in gifRe.findAll(gifsTag)) {
                val a = m.groupValues[1].toIntOrNull() ?: continue
                val b = m.groupValues[2].toIntOrNull() ?: continue
                val url = m.groupValues[3]
                val host = try { java.net.URI(url).host?.lowercase() } catch (e: Exception) { null } ?: continue
                if (host != "giphy.com" && !host.endsWith(".giphy.com")) continue
                if (a in 0..b && b < cps.size) gifs[a] = Pair(b, url)
            }
        }
        val out = mutableListOf<ChatToken>()
        val segment = StringBuilder()
        fun flushSegment() {
            if (segment.isEmpty()) return
            words(segment.toString(), channelId, out, userId, twitchLookup)
            segment.setLength(0)
        }
        var i = 0
        while (i < cps.size) {
            val r = ranges[i]
            val g = gifs[i]
            if (g != null) {
                flushSegment()
                out += ChatToken.Emote(String(cps, i, g.first - i + 1).trim(), g.second, ratio = 1.5f, source = "gif")
                i = g.first + 1
            } else if (r != null) {
                flushSegment()
                val name = String(cps, i, r.first - i + 1)
                TwitchEmotes.register(r.second, name)
                out += ChatToken.Emote(name, "https://static-cdn.jtvnw.net/emoticons/v2/${r.second}/default/dark/2.0",
                                       twitchId = r.second)
                i = r.first + 1
            } else {
                segment.appendCodePoint(cps[i])
                i++
            }
        }
        flushSegment()
        return merge(out)
    }

    /** Message déjà découpé, refait avec les emotes 7TV/BTTV/FFZ connues
     *  maintenant (chargées après lui, ou d'une autre chaîne) ; celles de
     *  Twitch (tag « emotes ») restent. Les morceaux recollés redonnent le texte. */
    fun retokenize(tokens: List<ChatToken>, channelId: String?, userId: String? = null): List<ChatToken> {
        val out = mutableListOf<ChatToken>()
        val segment = StringBuilder()
        fun flushSegment() {
            if (segment.isEmpty()) return
            words(segment.toString(), channelId, out, userId, null)
            segment.setLength(0)
        }
        for (t in tokens) {
            when (t) {
                is ChatToken.Emote -> if (t.source == "twitch" || t.source == "gif") {
                    flushSegment()
                    out += t
                } else {
                    segment.append(t.name)
                }
                is ChatToken.Text -> segment.append(t.text)
                is ChatToken.Mention -> segment.append(t.name)
                is ChatToken.Link -> segment.append(t.url)
            }
        }
        flushSegment()
        return merge(out)
    }

    private val gifRe = Regex("""(\d+)-(\d+)\|[^|]*\|(https://[^,]+?)(?=,\d+-\d+\||$)""")

    private fun words(s: String, channelId: String?, out: MutableList<ChatToken>, userId: String?,
                      twitchLookup: ((String) -> ChatToken.Emote?)?) {
        val parts = s.split(' ')
        for ((index, w) in parts.withIndex()) {
            if (index > 0) out += ChatToken.Text(" ")
            if (w.isEmpty()) continue
            val emote = Emotes.find(w, channelId, userId)
            val twitch = if (emote == null && twitchLookup != null) twitchLookup(w) else null
            when {
                emote != null -> out += ChatToken.Emote(emote.name, emote.url, emote.ratio, emote.zeroWidth, emote.source,
                                                        author = emote.author, channel = emote.channel, sourceId = emote.id,
                                                        original = emote.original, personal = emote.personal)
                twitch != null -> out += twitch
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
