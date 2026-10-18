package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject

/**
 * Suivre une chaîne sur le compte Twitch (session web), comme le cœur de
 * twitch.tv (mêmes requêtes enregistrées). Comme FollowService (iOS).
 */
object TwitchFollow {
    private const val STATUS = "1131e2a2138f219d96db8deda8eb06b217c0669e4fd33b8bed47623fc9764b33"
    private const val FOLLOW = "800e7346bdf7e5278a3c1d3f21b2b56e2639928f86815677a7126b093b2fdd08"
    private const val UNFOLLOW = "f7dae976ebf41c755ae2d758546bfd176b4eeb856656098bb40e0a672ca0d880"

    /** (suivie ?, identifiant de la chaîne) ; null sans réponse. */
    suspend fun status(login: String): Pair<Boolean, String>? {
        val token = WebSession.token ?: return null
        val user = WebGql.query("FollowButton_User", JSONObject().put("login", login.lowercase()), STATUS, token).data?.obj("user")
            ?: return null
        val id = user.str("id") ?: return null
        val self = user.obj("self")
        val following = self != null && self.has("follower") && !self.isNull("follower")
        return Pair(following, id)
    }

    /** Suit ou ne suit plus ; null si Twitch l'a fait, sinon le code du refus (vide s'il n'en donne pas). */
    suspend fun set(channelId: String, follow: Boolean): String? {
        val token = WebSession.token ?: return ""
        val input = JSONObject().put("targetID", channelId)
        if (follow) input.put("disableNotifications", false)
        val op = if (follow) "FollowButton_FollowUser" else "FollowButton_UnfollowUser"
        val field = if (follow) "followUser" else "unfollowUser"
        val res = WebGql.mutation(WebGql.persisted(op, JSONObject().put("input", input), if (follow) FOLLOW else UNFOLLOW),
                                  token, op)
        val payload = res?.obj("data")?.obj(field)
        val error = payload?.obj("error")
        if (payload != null && error == null && WebGql.errors(res).isEmpty() && !(follow && payload.has("follow") && payload.isNull("follow"))) {
            Logger.success("FOLLOW", if (follow) "Chaîne suivie sur Twitch" else "Chaîne retirée des suivis Twitch", channelId)
            return null
        }
        val code = error?.str("code") ?: ""
        Logger.warn("FOLLOW", "Suivi refusé", code.ifEmpty { null })
        return code
    }
}
