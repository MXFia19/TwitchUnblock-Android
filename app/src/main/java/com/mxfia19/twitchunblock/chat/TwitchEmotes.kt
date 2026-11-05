package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Emotes Twitch hors des messages reçus : celles de la chaîne (followers,
 * puis abonnés par niveau) pour son onglet du sélecteur, et celles du compte
 * (tag IRC `emote-sets`) pour « Mes emotes » et l'écho de nos messages, qui
 * n'a pas de tag emotes. Requêtes GQL publiques, deux essais au plus par
 * chaîne et par jeu dans la séance. Comme EmoteService (iOS).
 */
object TwitchEmotes {
    /** Chaîne d'un jeu d'emotes du compte (« Mes emotes »). */
    class Owner(val id: String, val login: String, val name: String, val avatar: String?)
    /** Section de « Mes emotes » : une chaîne (avatar, nom), ou « Twitch ». */
    class Section(val id: String, val title: String, val avatar: String?, val emotes: List<Emotes.Emote>)

    private class ChannelEmote(val emote: Emotes.Emote, val setId: String?)
    private class UserSet(val owner: Owner?, val emotes: List<Pair<Emotes.Emote, String>>)

    /** Emotes vues dans les messages (tag emotes), par nom. */
    private val seen = ConcurrentHashMap<String, String>()
    private val channel = ConcurrentHashMap<String, List<ChannelEmote>>()
    private val channelAttempts = ConcurrentHashMap<String, Int>()
    private val channelLoading = ConcurrentHashMap.newKeySet<String>()
    /** Jeux du compte connecté au chat : ceux d'un autre compte ne comptent pas. */
    @Volatile
    private var userSets: Pair<String, Set<String>>? = null
    private val setContents = ConcurrentHashMap<String, UserSet>()
    private val setAttempts = ConcurrentHashMap<String, Int>()
    private val setLoading = ConcurrentHashMap.newKeySet<String>()

    /** Augmenté à chaque chargement : le sélecteur se refait. */
    var version by mutableStateOf(0)
        private set

    /** Types rangés sous la chaîne du jeu ; les autres (globales, Prime,
     *  Turbo…) vont dans « Twitch ». */
    private val channelKinds = setOf("SUBSCRIPTIONS", "FOLLOWER", "BITS_BADGE_TIERS", "CHANNEL_POINTS")
    private val wordRe = Regex("^[A-Za-z0-9_]+$")
    private val setIdRe = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun url(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"

    /** Emote vue dans un message : l'écho de nos messages la retrouvera. */
    fun register(id: String, name: String) {
        if (seen.size < 5000) seen.putIfAbsent(name, id)
    }

    /** Emote Twitch par son nom, pour l'écho de nos propres messages : parmi
     *  celles déjà vues dans le chat, puis celles des jeux du compte (une
     *  emote follower d'une autre chaîne ne vaut que chez elle). */
    fun byName(name: String, channelId: String?): ChatToken.Emote? {
        seen[name]?.let { return ChatToken.Emote(name, url(it), twitchId = it) }
        val sets = userSets?.second ?: return null
        for (id in sets.sortedWith(SET_ORDER)) {
            val set = setContents[id] ?: continue
            for ((e, kind) in set.emotes) {
                if (e.name != name) continue
                if (kind == "FOLLOWER" && set.owner?.id != channelId) continue
                return ChatToken.Emote(e.name, e.url, twitchId = e.id)
            }
        }
        return null
    }

    // ── Onglet de la chaîne ──

    /** Emotes Twitch de la chaîne : utilisables d'abord, puis celles d'abonné
     *  hors des jeux du compte, verrouillées (inconnus sans compte : aucune). */
    fun forChannel(channelId: String?, login: String?): Pair<List<Emotes.Emote>, List<Emotes.Emote>> {
        val list = channelId?.let { channel[it] } ?: return Pair(emptyList(), emptyList())
        val sets = userSets?.takeIf { it.first == login?.lowercase() }?.second
        val usable = ArrayList<Emotes.Emote>()
        val locked = ArrayList<Emotes.Emote>()
        for (item in list) {
            val setId = item.setId
            if (sets != null && setId != null && setId !in sets) locked += item.emote.copy(locked = true) else usable += item.emote
        }
        return Pair(usable, locked)
    }

    /** Une requête publique par chaîne et par séance, gardée même vide. */
    suspend fun loadChannel(channelId: String) {
        if (channelId.isEmpty() || channel.containsKey(channelId) || !channelLoading.add(channelId)) return
        if ((channelAttempts[channelId] ?: 0) >= 2) {
            channelLoading.remove(channelId)
            return
        }
        channelAttempts[channelId] = (channelAttempts[channelId] ?: 0) + 1
        try {
            val q = "query(\$id: ID!) { user(id: \$id) { subscriptionProducts { tier emotes { id token setID } } } " +
                "channel(id: \$id) { localEmoteSets { emotes { id token setID } } } }"
            val json = Gql.raw(q, Gql.vars("id" to channelId), timeout = 15) ?: return
            val d = json.obj("data") ?: return
            val out = ArrayList<ChannelEmote>()
            val ids = HashSet<String>()
            fun add(list: List<JSONObject>, follower: Boolean) {
                for (e in list) {
                    val id = e.str("id")?.takeIf { it.isNotEmpty() } ?: continue
                    val token = e.str("token")?.takeIf { it.isNotEmpty() } ?: continue
                    if (!ids.add(id)) continue
                    val emote = Emotes.Emote(token, url(id), source = "twitch", channel = true, id = id)
                    out += ChannelEmote(emote, if (follower) null else e.str("setID"))
                }
            }
            // Followers d'abord, puis les niveaux d'abonnement dans l'ordre.
            for (g in d.obj("channel")?.arr("localEmoteSets")?.objects().orEmpty()) add(g.arr("emotes")?.objects().orEmpty(), true)
            val products = d.obj("user")?.arr("subscriptionProducts")?.objects().orEmpty()
                .sortedBy { it.str("tier")?.toIntOrNull() ?: Int.MAX_VALUE }
            for (p in products) add(p.arr("emotes")?.objects().orEmpty(), false)
            // Erreur partielle sans rien d'utilisable : l'essai suivant redemandera.
            if (out.isEmpty() && json.has("errors")) return
            channel[channelId] = out
            version++
            Logger.success("EMOTES", "Emotes Twitch de la chaîne chargées", "${out.size}")
        } finally {
            channelLoading.remove(channelId)
        }
    }

    // ── Mes emotes ──

    /** Jeux du compte, lus dans GLOBALUSERSTATE et USERSTATE ; ceux pas
     *  encore lus sont demandés aussitôt. */
    suspend fun setUserSets(sets: Set<String>, login: String) {
        val l = login.lowercase()
        val current = userSets
        if (current == null || current.first != l || current.second != sets) userSets = Pair(l, sets)
        loadUserSets()
    }

    /** Contenu des jeux pas encore lus, 15 par requête (au-delà, Twitch
     *  refuse les alias), 300 au plus. */
    suspend fun loadUserSets() {
        val known = userSets ?: return
        val todo = known.second.filter { id ->
            setIdRe.matches(id) && !setContents.containsKey(id) && id !in setLoading && (setAttempts[id] ?: 0) < 2
        }.sortedWith(SET_ORDER).take(300)
        if (todo.isEmpty()) return
        setLoading.addAll(todo)
        for (id in todo) setAttempts[id] = (setAttempts[id] ?: 0) + 1
        var loaded = 0
        try {
            for (batch in todo.chunked(15)) {
                val result = fetchSets(batch) ?: continue
                setContents.putAll(result)
                loaded += result.size
            }
        } finally {
            setLoading.removeAll(todo.toSet())
        }
        if (loaded > 0) {
            version++
            Logger.success("EMOTES", "Jeux d'emotes du compte chargés", "$loaded")
        }
    }

    private suspend fun fetchSets(ids: List<String>): Map<String, UserSet>? {
        val params = ids.indices.joinToString(", ") { "\$s$it: ID!" }
        val fields = ids.indices.joinToString(" ") {
            "s$it: emoteSet(id: \$s$it) { id owner { id login displayName profileImageURL(width: 50) } emotes { id token type } }"
        }
        val vars = JSONObject()
        ids.forEachIndexed { i, id -> vars.put("s$i", id) }
        val json = Gql.raw("query($params) { $fields }", vars, timeout = 15) ?: return null
        val d = json.obj("data") ?: return null
        val partial = json.has("errors")
        val out = HashMap<String, UserSet>()
        ids.forEachIndexed { i, id ->
            val set = d.obj("s$i")
            if (set == null) {
                // `null` : jeu inconnu de Twitch (gardé vide) ou erreur partielle.
                if (!partial) out[id] = UserSet(null, emptyList())
                return@forEachIndexed
            }
            val o = set.obj("owner")
            val ownerId = o?.str("id")?.takeIf { it.isNotEmpty() }
            val ownerLogin = o?.str("login")?.takeIf { it.isNotEmpty() }
            val owner = if (ownerId != null && ownerLogin != null) {
                Owner(ownerId, ownerLogin, o?.str("displayName")?.takeIf { it.isNotEmpty() } ?: ownerLogin,
                      o?.str("profileImageURL")?.takeIf { it.startsWith("https://") })
            } else null
            val emotes = set.arr("emotes")?.objects().orEmpty().mapNotNull { e ->
                val eid = e.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val token = e.str("token")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Pair(Emotes.Emote(token, url(eid), source = "twitch", id = eid), e.str("type") ?: "")
            }
            out[id] = UserSet(owner, emotes)
        }
        return out
    }

    /** Sections de « Mes emotes » : la chaîne regardée d'abord si le compte a
     *  ses emotes, puis les autres par nom, puis « Twitch ». Ni smileys (ils
     *  se tapent tels quels), ni emotes follower d'une autre chaîne. Vide sans
     *  compte connecté au chat. */
    fun mine(channelId: String?, login: String?): List<Section> {
        val known = userSets?.takeIf { it.first == login?.lowercase() } ?: return emptyList()
        val byChannel = LinkedHashMap<String, Pair<Owner, ArrayList<Emotes.Emote>>>()
        val twitch = ArrayList<Emotes.Emote>()
        val ids = HashSet<String>()
        for (setId in known.second.sortedWith(SET_ORDER)) {
            val set = setContents[setId] ?: continue
            for ((e, kind) in set.emotes) {
                if (kind == "SMILIES" || !wordRe.matches(e.name)) continue
                val owner = if (kind in channelKinds) set.owner else null
                if (kind == "FOLLOWER" && (owner == null || owner.id != channelId)) continue
                if (!ids.add(e.id ?: e.name)) continue
                if (owner != null) byChannel.getOrPut(owner.id) { Pair(owner, ArrayList()) }.second += e
                else twitch += e
            }
        }
        val ordered = byChannel.values.sortedWith(compareBy<Pair<Owner, ArrayList<Emotes.Emote>>> { it.first.id != channelId }
            .thenBy { it.first.name.lowercase() })
        val out = ordered.map { Section(it.first.id, it.first.name, it.first.avatar, it.second) }.toMutableList()
        if (twitch.isNotEmpty()) out += Section("twitch", "Twitch", null, twitch)
        return out
    }

    /** Menu ⋯ du chat : relu à la prochaine ouverture du sélecteur. Les
     *  emotes vues dans les messages restent (celles déjà affichées aussi). */
    fun reset() {
        channel.clear()
        channelAttempts.clear()
        setContents.clear()
        setAttempts.clear()
        version++
    }

    /** Jeux par id croissant (des nombres) : les globales (jeu 0) en tête. */
    private val SET_ORDER = Comparator<String> { a, b -> setOrder(a, b) }

    private fun setOrder(a: String, b: String): Int {
        val x = a.toLongOrNull()
        val y = b.toLongOrNull()
        return when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b)
        }
    }
}
