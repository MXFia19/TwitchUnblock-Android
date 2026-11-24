package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject

/** Badge global au choix (« setID/version »). */
data class ChatBadgeChoice(val setId: String, val version: String, val title: String, val imageUrl: String) {
    val id: String get() = "$setId/$version"
}

/**
 * Identité dans le chat : le badge global montré à côté de son nom
 * (fb_mwjodpzf), comme « Identité dans le chat » de twitch.tv et l'iPhone.
 * Les badges d'une chaîne (abonnement, bits) restent à choisir sur twitch.tv.
 * Demande la session web ; la lecture passe sans jeton d'intégrité, le choix
 * par WebGql.mutation (jeton d'intégrité, nouvel essai s'il est refusé).
 * Champs et mutation vérifiés le 24/11 par une sonde publique sans session
 * (validation du schéma ; voir ChatIdentity.swift de l'app iPhone).
 */
object ChatIdentity {
    /** Badges globaux du compte et celui choisi ; null : session refusée ou réseau. */
    suspend fun globalBadges(): Pair<List<ChatBadgeChoice>, String?>? {
        val token = WebSession.token ?: return null
        val r = WebGql.raw("query { currentUser { id availableBadges { setID version title imageURL } " +
                           "selectedBadge { setID version } } }", token = token)
        val user = r.data?.obj("currentUser")
        if (user == null) {
            Logger.warn("CHAT", "Badges du compte indisponibles", if (r.code == 0) "réseau" else "HTTP ${r.code}")
            return null
        }
        val list = user.arr("availableBadges")?.objects().orEmpty().mapNotNull { b ->
            val set = b.str("setID") ?: return@mapNotNull null
            val version = b.str("version") ?: return@mapNotNull null
            ChatBadgeChoice(set, version, b.str("title")?.ifEmpty { null } ?: set, b.str("imageURL") ?: "")
        }
        val selected = user.obj("selectedBadge")?.let { s ->
            val set = s.str("setID")
            val version = s.str("version")
            if (set != null && version != null) "$set/$version" else null
        }
        return list to selected
    }

    /** Choisit le badge global du compte. Vrai si Twitch l'a fait. */
    suspend fun selectGlobal(badge: ChatBadgeChoice): Boolean {
        val token = WebSession.token ?: return false
        val q = "mutation { selectGlobalBadge(input: { badgeSetID: ${JSONObject.quote(badge.setId)}, " +
            "badgeSetVersion: ${JSONObject.quote(badge.version)} }) { user { id } } }"
        val res = WebGql.mutation(WebGql.written(q), token, "ChatIdentity_SelectGlobalBadge")
        val errors = WebGql.errors(res)
        val ok = res?.obj("data")?.obj("selectGlobalBadge")?.obj("user") != null && errors.isEmpty()
        if (ok) Logger.success("CHAT", "Badge du chat choisi", badge.id)
        else Logger.warn("CHAT", "Badge du chat refusé", errors.ifEmpty { badge.id })
        return ok
    }
}
