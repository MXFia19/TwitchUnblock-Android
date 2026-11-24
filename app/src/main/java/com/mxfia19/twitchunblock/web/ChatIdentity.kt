package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject

/** Badge au choix (« setID/version »). */
data class ChatBadgeChoice(val setId: String, val version: String, val title: String, val imageUrl: String) {
    val id: String get() = "$setId/$version"
}

/** Badges que le compte peut montrer sur une chaîne, celui qui y est choisi,
 *  et l'id de la chaîne (pour le choix). */
data class ChannelBadges(val channelId: String, val available: List<ChatBadgeChoice>, val selected: String?)

/**
 * Identité dans le chat : le badge montré à côté de son nom (fb_mwjodpzf),
 * comme « Identité dans le chat » de twitch.tv, l'iPhone et le site — un
 * badge global (Prime Gaming, Turbo, un événement…), et pour la chaîne
 * regardée son badge à elle (abonnement, bits…), qui y remplace le global.
 * Demande la session web ; la lecture passe sans jeton d'intégrité, le choix
 * par WebGql.mutation (jeton d'intégrité, nouvel essai s'il est refusé).
 * Champs et mutations vérifiés le 24/11 par une sonde publique sans session
 * (validation du schéma ; voir ChatIdentity.swift de l'app iPhone).
 */
object ChatIdentity {
    private const val BADGE_FIELDS = "availableBadges { setID version title imageURL } selectedBadge { setID version }"

    /** Badges globaux du compte et celui choisi ; null : session refusée ou réseau. */
    suspend fun globalBadges(): Pair<List<ChatBadgeChoice>, String?>? {
        val token = WebSession.token ?: return null
        val r = WebGql.raw("query { currentUser { id $BADGE_FIELDS } }", token = token)
        val user = r.data?.obj("currentUser")
        if (user == null) {
            Logger.warn("CHAT", "Badges du compte indisponibles", if (r.code == 0) "réseau" else "HTTP ${r.code}")
            return null
        }
        return badges(user) to selected(user)
    }

    /** Badges de la chaîne `login` ; null : chaîne inconnue, session refusée ou réseau. */
    suspend fun channelBadges(login: String): ChannelBadges? {
        val token = WebSession.token ?: return null
        val r = WebGql.raw("query(\$l: String!) { user(login: \$l) { id self { $BADGE_FIELDS } } }",
                           JSONObject().put("l", login), token = token)
        val user = r.data?.obj("user")
        val id = user?.str("id")
        if (user == null || id.isNullOrEmpty()) {
            Logger.warn("CHAT", "Badges de la chaîne indisponibles",
                        "$login · " + if (r.code == 0) "réseau" else "HTTP ${r.code}")
            return null
        }
        val me = user.obj("self")
        return ChannelBadges(id, me?.let { badges(it) }.orEmpty(), me?.let { selected(it) })
    }

    /** Choisit le badge global du compte. Vrai si Twitch l'a fait. */
    suspend fun selectGlobal(badge: ChatBadgeChoice): Boolean =
        select("ChatIdentity_SelectGlobalBadge", "selectGlobalBadge",
               "badgeSetID: ${JSONObject.quote(badge.setId)}, badgeSetVersion: ${JSONObject.quote(badge.version)}",
               badge.id)

    /** Choisit le badge montré sur une chaîne. Vrai si Twitch l'a fait. */
    suspend fun selectChannel(badge: ChatBadgeChoice, channelId: String, login: String): Boolean =
        select("ChatIdentity_SelectChannelBadge", "selectChannelBadge",
               "channelID: ${JSONObject.quote(channelId)}, badgeSetID: ${JSONObject.quote(badge.setId)}, " +
                   "badgeSetVersion: ${JSONObject.quote(badge.version)}",
               "$login · ${badge.id}")

    /** Mutation de l'app, valeurs écrites dans la requête ; réussie si son
     *  champ rend un `user`, sans erreur. */
    private suspend fun select(tag: String, field: String, input: String, what: String): Boolean {
        val token = WebSession.token ?: return false
        val res = WebGql.mutation(WebGql.written("mutation { $field(input: { $input }) { user { id } } }"), token, tag)
        val errors = WebGql.errors(res)
        val ok = res?.obj("data")?.obj(field)?.obj("user") != null && errors.isEmpty()
        if (ok) Logger.success("CHAT", "Badge du chat choisi", what)
        else Logger.warn("CHAT", "Badge du chat refusé", if (errors.isEmpty()) what else "$what · $errors")
        return ok
    }

    /** Liste de Twitch → choix, sans doublon (clés uniques de la liste). */
    private fun badges(o: JSONObject): List<ChatBadgeChoice> =
        o.arr("availableBadges")?.objects().orEmpty().mapNotNull { b ->
            val set = b.str("setID") ?: return@mapNotNull null
            val version = b.str("version") ?: return@mapNotNull null
            ChatBadgeChoice(set, version, b.str("title")?.ifEmpty { null } ?: set, b.str("imageURL") ?: "")
        }.distinctBy { it.id }

    private fun selected(o: JSONObject): String? = o.obj("selectedBadge")?.let { s ->
        val set = s.str("setID")
        val version = s.str("version")
        if (set != null && version != null) "$set/$version" else null
    }
}
