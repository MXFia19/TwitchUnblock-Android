package com.mxfia19.twitchunblock.chat

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.HttpResult
import com.mxfia19.twitchunblock.core.Logger
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * Ce qui limite qui peut écrire dans un chat (ROOMSTATE de l'IRC, réglages
 * du chat par GQL), dit au-dessus du champ avant d'écrire. Comme
 * ChatRoomModes (iOS).
 */
data class RoomModes(
    /** Minutes de suivi exigées (0 : suivre suffit) ; null : pas de mode followers. */
    val followersMinutes: Int? = null,
    val subsOnly: Boolean = false,
    val emoteOnly: Boolean = false,
    val slowSeconds: Int = 0,
    val uniqueChat: Boolean = false,
    val verifiedOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = followersMinutes == null && !subsOnly && !emoteOnly && slowSeconds == 0 && !uniqueChat && !verifiedOnly

    companion object {
        /** « 10 min », « 1 h 30 min », « 2 j 3 h » dans la langue de l'app. */
        fun duration(minutes: Int): String = when {
            minutes >= 1440 -> measures(minutes / 1440 to MeasureUnit.DAY, (minutes % 1440) / 60 to MeasureUnit.HOUR)
            minutes >= 60 -> measures(minutes / 60 to MeasureUnit.HOUR, minutes % 60 to MeasureUnit.MINUTE)
            else -> measures(minutes to MeasureUnit.MINUTE)
        }

        /** Durée d'une exclusion (`ban-duration`, en secondes, jusqu'à deux semaines). */
        fun durationSeconds(seconds: Int): String = when {
            seconds >= 86_400 -> measures(seconds / 86_400 to MeasureUnit.DAY, (seconds % 86_400) / 3600 to MeasureUnit.HOUR)
            seconds >= 3600 -> measures(seconds / 3600 to MeasureUnit.HOUR, (seconds % 3600) / 60 to MeasureUnit.MINUTE)
            seconds >= 60 -> measures(seconds / 60 to MeasureUnit.MINUTE, seconds % 60 to MeasureUnit.SECOND)
            else -> measures(seconds to MeasureUnit.SECOND)
        }

        /** Deux unités au plus, les nulles omises (« 2 h » plutôt que « 2 h 0 min »). */
        private fun measures(vararg parts: Pair<Int, MeasureUnit>): String {
            val shown = parts.filter { it.first > 0 }.ifEmpty { listOf(parts.first()) }
            return try {
                MeasureFormat.getInstance(Locale.forLanguageTag(AppState.lang.code), MeasureFormat.FormatWidth.SHORT)
                    .formatMeasures(*shown.map { Measure(it.first, it.second) }.toTypedArray())
            } catch (e: Exception) {
                shown.joinToString(" ") { "${it.first}" }
            }
        }
    }
}

/** Message refusé par Twitch (msg-id du NOTICE) → clé du texte traduit. */
internal val chatRejections = mapOf(
    "msg_followersonly" to "chat_rej_followers", "msg_followersonly_zero" to "chat_rej_followers",
    "msg_followersonly_followed" to "chat_rej_followers_time",
    "msg_subsonly" to "chat_rej_subs", "msg_emoteonly" to "chat_rej_emotes", "msg_emotesonly" to "chat_rej_emotes",
    "msg_slowmode" to "chat_rej_slow", "msg_r9k" to "chat_rej_unique", "msg_duplicate" to "chat_rej_duplicate",
    "msg_verified_email" to "chat_rej_verified", "msg_requires_verified_phone_number" to "chat_rej_verified",
    "msg_timedout" to "chat_rej_timeout",
    "msg_rejected" to "chat_rej_automod", "msg_rejected_mandatory" to "chat_rej_automod",
    "msg_ratelimit" to "chat_rej_ratelimit", "msg_channel_suspended" to "chat_rej_suspended",
    "msg_suspended" to "chat_rej_suspended",
)

/**
 * Couleur du pseudo dans le chat, par Helix (PUT /helix/chat/color, scope
 * user:manage:chat_color) : la commande IRC « /color » renvoie « Unrecognized
 * command » depuis 2023. Les couleurs nommées sont acceptées pour tous les
 * comptes (les teintes libres restent réservées à Turbo et Prime). Comme
 * setChatColor (iOS).
 */
object ChatColor {
    /** Nom chez Twitch (et clé « color_<nom> » du texte) → couleur affichée. */
    val choices: List<Pair<String, Long>> = listOf(
        "blue" to 0xFF0000FF, "blue_violet" to 0xFF8A2BE2, "cadet_blue" to 0xFF5F9EA0, "chocolate" to 0xFFD2691E,
        "coral" to 0xFFFF7F50, "dodger_blue" to 0xFF1E90FF, "firebrick" to 0xFFB22222, "golden_rod" to 0xFFDAA520,
        "green" to 0xFF008000, "hot_pink" to 0xFFFF69B4, "orange_red" to 0xFFFF4500, "red" to 0xFFFF0000,
        "sea_green" to 0xFF2E8B57, "spring_green" to 0xFF00FF7F, "yellow_green" to 0xFF9ACD32,
    )

    /** null : couleur changée ; sinon la clé du texte de l'erreur. Jeton
     *  refusé : renouvelé une fois, comme Auth.helix. */
    suspend fun set(name: String): String? {
        val uid = AppState.user?.id?.takeIf { it.isNotEmpty() } ?: return "color_needs_relogin"
        val token = AppState.token ?: return "color_needs_relogin"
        var r = put(token, uid, name) ?: return "color_network"
        if (r.code == 401) {
            r = when (Auth.renew(token)) {
                Auth.Renewal.RENEWED -> AppState.token?.let { put(it, uid, name) } ?: return "color_network"
                Auth.Renewal.UNREACHABLE -> return "color_network"
                Auth.Renewal.REFUSED -> return "color_needs_relogin"
            }
        }
        return when (r.code) {
            200, 204 -> {
                Logger.success("CHAT", "Couleur du pseudo changée", name)
                null
            }
            401 -> {
                // Jeton sans le scope de la couleur : reconnexion nécessaire.
                Logger.warn("CHAT", "Couleur refusée : scope manquant", "reconnexion nécessaire")
                "color_needs_relogin"
            }
            400 -> {
                Logger.warn("CHAT", "Couleur refusée", "HTTP 400")
                "color_bad_request"
            }
            else -> {
                Logger.warn("CHAT", "Couleur refusée", "HTTP ${r.code}")
                "color_failed"
            }
        }
    }

    private suspend fun put(token: String, uid: String, color: String): HttpResult? {
        val request = Request.Builder()
            .url("https://api.twitch.tv/helix/chat/color?user_id=${Http.encode(uid)}&color=${Http.encode(color)}")
            .put(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $token")
            .header("Client-Id", Config.HELIX_CLIENT_ID)
            .build()
        return Http.send(request)
    }
}
