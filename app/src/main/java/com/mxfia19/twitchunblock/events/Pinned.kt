package com.mxfia19.twitchunblock.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.chat.ChatColors
import com.mxfia19.twitchunblock.chat.ChatMessage
import com.mxfia19.twitchunblock.chat.ChatText
import com.mxfia19.twitchunblock.chat.ChatToken
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Message épinglé d'un chat. */
data class PinnedChat(
    val id: String,
    val message: ChatMessage,
    val text: String,
    val pinnedBy: String?,
    val startsAt: Long?,
    val endsAt: Long?,
)

/**
 * Message épinglé du direct, relu toutes les 20 s (requête publique) et
 * retiré à sa fin. Comme ChatPubSub (iOS). Fil principal.
 */
class PinnedWatcher(private val scope: CoroutineScope) {
    var pinned by mutableStateOf<PinnedChat?>(null)
        private set

    private var channelId = ""
    private var job: Job? = null
    private var expiry: Job? = null

    fun start(channelId: String) {
        stop()
        if (channelId.isEmpty()) return
        this.channelId = channelId
        job = scope.launch {
            while (isActive) {
                fetch()
                delay(20_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        expiry?.cancel()
        job = null
        pinned = null
    }

    private suspend fun fetch() {
        val cid = channelId
        val q = "query(\$id: ID!) { channel(id: \$id) { pinnedChatMessages(first: 1) { edges { node { " +
            "id startsAt endsAt pinnedBy { login displayName } pinnedMessage { " +
            "content { text fragments { text content { __typename ... on Emote { id } } } } " +
            "sender { login displayName chatColor displayBadges(channelID: \$id) { setID version imageURL(size: DOUBLE) } } } } } } } }"
        val edges = Gql.raw(q, Gql.vars("id" to cid))?.obj("data")?.obj("channel")?.obj("pinnedChatMessages")?.arr("edges") ?: return
        if (cid != channelId) return
        val node = edges.objects().firstOrNull()?.obj("node")
        val pm = node?.obj("pinnedMessage")
        val content = pm?.obj("content")
        val text = content?.str("text")
        val id = node?.str("id")
        if (node == null || id == null || text == null || text.isEmpty()) {
            setPinned(null)
            return
        }
        val endsAt = Format.parseDate(node.str("endsAt"))
        if (endsAt != null && endsAt < System.currentTimeMillis()) {
            setPinned(null)
            return
        }
        if (pinned?.id == id && pinned?.endsAt == endsAt) return
        val sender = pm?.obj("sender")
        val senderLogin = sender?.str("login") ?: ""
        val name = sender?.str("displayName")?.takeIf { it.isNotEmpty() } ?: senderLogin
        val badges = sender?.arr("displayBadges")?.objects().orEmpty().mapNotNull { b ->
            b.str("imageURL")?.takeIf { it.startsWith("https://") }
        }
        val tokens = ArrayList<ChatToken>()
        for (f in content?.arr("fragments")?.objects().orEmpty()) {
            val ftext = f.str("text") ?: ""
            val c = f.obj("content")
            val emoteId = if (c != null && c.str("__typename") == "Emote") c.str("id") else null
            if (!emoteId.isNullOrEmpty()) {
                tokens += ChatToken.Emote(ftext.trim(), "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/2.0")
            } else if (ftext.isNotEmpty()) {
                tokens += ChatText.tokens(ftext, null, cid)
            }
        }
        if (tokens.isEmpty()) tokens += ChatText.tokens(text, null, cid)
        val by = node.obj("pinnedBy")
        val byLogin = by?.str("login")
        val pinnedBy = if (byLogin != null && byLogin != senderLogin) by?.str("displayName") else null
        val startsAt = Format.parseDate(node.str("startsAt"))
        val message = ChatMessage(
            id = "pinned-$id", login = senderLogin.lowercase().ifEmpty { "pinned" }, name = name,
            color = ChatColors.parse(sender?.str("chatColor"), senderLogin), badges = badges, tokens = tokens,
            time = startsAt ?: System.currentTimeMillis(),
        )
        setPinned(PinnedChat(id, message, text, pinnedBy, startsAt, endsAt))
        Logger.success("PINNED", "Message épinglé", text)
    }

    private fun setPinned(p: PinnedChat?) {
        pinned = p
        expiry?.cancel()
        val ends = p?.endsAt ?: return
        expiry = scope.launch {
            delay(maxOf(0L, ends - System.currentTimeMillis()))
            if (pinned?.id == p.id) pinned = null
        }
    }
}
