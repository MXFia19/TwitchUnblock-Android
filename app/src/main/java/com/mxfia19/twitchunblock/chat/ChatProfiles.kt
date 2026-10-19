package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.str
import java.util.concurrent.ConcurrentHashMap

/**
 * Fiches du chat, par l'API publique (GQL, sans compte) : une personne
 * (avatar, nom, création du compte), depuis quand elle suit la chaîne
 * (requête à part : si Twitch refuse ce champ, le reste s'affiche), et le
 * type et la chaîne d'une emote Twitch (le tag IRC ne donne que son id).
 * Réponses gardées pour la séance. Comme getUserCreatedAt, getFollowedAt et
 * EmoteInfoService (iOS).
 */
object ChatProfiles {
    class Profile(val login: String, val name: String, val avatar: String?, val createdAt: Long?)

    /** `type` : GLOBALS, SMILIES, SUBSCRIPTIONS, CHANNEL_POINTS… (tel que
     *  Twitch le donne) ; `tier` : niveau d'une emote d'abonné ; `owner` : sa chaîne. */
    class TwitchEmote(val type: String?, val tier: Int?, val owner: String?)

    private val profiles = ConcurrentHashMap<String, Profile>()
    private val emotes = ConcurrentHashMap<String, TwitchEmote>()
    /** Dates de suivi, par « personne chaîne » (0 : ne suit pas). */
    private val follows = ConcurrentHashMap<String, Long>()
    /** Champ `follow` refusé par Twitch : plus demandé de la séance. */
    @Volatile
    private var followRefused = false

    /** null : compte introuvable, ou Twitch n'a pas répondu. */
    suspend fun profile(login: String): Profile? {
        val l = login.lowercase()
        profiles[l]?.let { return it }
        val q = "query(\$l: String!) { user(login: \$l) { login displayName profileImageURL(width: 150) createdAt } }"
        val u = Gql.request(q, Gql.vars("l" to l))?.obj("user") ?: return null
        return Profile(l, u.str("displayName") ?: l, u.str("profileImageURL"), Format.parseDate(u.str("createdAt")))
            .also { profiles[l] = it }
    }

    /** Depuis quand `login` suit la chaîne `channel` (ms). null : ne la suit
     *  pas, ou Twitch ne le dit pas. */
    suspend fun followedAt(login: String, channel: String): Long? {
        if (channel.isEmpty() || followRefused) return null
        val key = login.lowercase() + " " + channel.lowercase()
        follows[key]?.let { return it.takeIf { at -> at > 0 } }
        val q = "query(\$l: String!, \$c: String!) { user(login: \$l) { follow(targetLogin: \$c) { followedAt } } }"
        // Sans réponse (réseau) : redemandé à la prochaine fiche.
        val json = Gql.raw(q, Gql.vars("l" to login.lowercase(), "c" to channel.lowercase()), timeout = 15) ?: return null
        val at = Format.parseDate(json.obj("data")?.obj("user")?.obj("follow")?.str("followedAt"))
        if (at == null && json.has("errors")) {
            // Refusé : une ligne au journal pour la séance, pas une par fiche ouverte.
            followRefused = true
            Logger.warn("GQL", "Date de suivi refusée", Gql.errorSummary(json))
            return null
        }
        follows[key] = at ?: 0L
        return at
    }

    /** null : emote inconnue, ou Twitch n'a pas répondu. */
    suspend fun twitchEmote(id: String): TwitchEmote? {
        emotes[id]?.let { return it }
        val q = "query(\$id: ID!) { emote(id: \$id) { type subscriptionTier owner { displayName } } }"
        val e = Gql.request(q, Gql.vars("id" to id))?.obj("emote") ?: return null
        // « TIER_1 » → 1.
        val tier = e.str("subscriptionTier")?.removePrefix("TIER_")?.toIntOrNull()
        return TwitchEmote(e.str("type"), tier, e.obj("owner")?.str("displayName")).also { emotes[id] = it }
    }
}
