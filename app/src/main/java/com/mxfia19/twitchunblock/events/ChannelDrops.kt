package com.mxfia19.twitchunblock.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.chat.ChatOptions
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Campagne de drops en cours sur la chaîne : première récompense et ce
 *  qu'il faut regarder pour l'avoir, échéance, lien vers les détails.
 *  `specialEvent` : campagne de Twitch rangée dans « Special Events »
 *  (« 1 Month of Apple TV »…), masquable à part. */
data class DropsNotice(
    val id: String,
    val name: String,
    val game: String,
    val endsAt: Long?,
    val detailsUrl: String?,
    val rewardName: String,
    val rewardImage: String?,
    val minutes: Int,
    val more: Int,
    val specialEvent: Boolean,
)

/**
 * Carte « Drops » en haut du chat, comme dans l'app Twitch : campagnes de la
 * chaîne (lecture publique, sans compte), lues à l'arrivée puis toutes les
 * 10 minutes. Une carte fermée ne revient pas pour cette campagne. Comme
 * ChannelDropsNotice (iOS). Fil principal.
 */
class ChannelDrops(private val scope: CoroutineScope) {
    /** Campagnes en cours, dans l'ordre de Twitch, sans celles fermées. */
    var campaigns by mutableStateOf<List<DropsNotice>>(emptyList())
        private set

    private var channelId = ""
    private var job: Job? = null

    /** Carte à montrer selon les réglages : aucune sans « Drops de la chaîne » ;
     *  sans « Special Events », la campagne suivante passe. */
    val shown: DropsNotice?
        get() = if (!ChatOptions.dropsCard.value) null
                else campaigns.firstOrNull { ChatOptions.specialEvents.value || !it.specialEvent }

    fun start(channelId: String) {
        stop()
        if (channelId.isEmpty()) return
        this.channelId = channelId
        job = scope.launch {
            while (isActive) {
                fetch()
                delay(600_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        campaigns = emptyList()
        channelId = ""
    }

    fun dismiss(c: DropsNotice) {
        val ids = dismissed().toMutableList()
        if (c.id !in ids) ids += c.id
        AppState.prefs.edit().putString(KEY, JSONArray(ids.takeLast(100)).toString()).apply()
        campaigns = campaigns.filter { it.id != c.id }
        Logger.info("DROPS", "Carte des drops fermée", c.name)
    }

    private fun dismissed(): List<String> = parseJsonArray(AppState.prefs.getString(KEY, null))?.strings().orEmpty()

    private suspend fun fetch() {
        val cid = channelId
        val q = "query(\$id: ID!) { user(id: \$id) { channel { viewerDropCampaigns { id name endAt detailsURL " +
            "game { id name displayName } timeBasedDrops { name requiredMinutesWatched " +
            "benefitEdges { benefit { name imageAssetURL } } } } } } }"
        val channel = Gql.raw(q, Gql.vars("id" to cid))?.obj("data")?.obj("user")?.obj("channel") ?: return
        if (cid != channelId) return
        val skip = dismissed().toSet()
        val now = System.currentTimeMillis()
        val found = channel.arr("viewerDropCampaigns")?.objects().orEmpty().mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            if (id in skip) return@mapNotNull null
            val end = Format.parseDate(c.str("endAt"))
            if (end != null && end < now) return@mapNotNull null
            val drops = c.arr("timeBasedDrops")?.objects().orEmpty().sortedBy { it.int("requiredMinutesWatched") ?: 0 }
            val first = drops.firstOrNull()
            val benefit = first?.arr("benefitEdges")?.objects()?.firstOrNull()?.obj("benefit")
            val game = c.obj("game")
            DropsNotice(
                id = id,
                name = c.str("name") ?: "",
                game = game?.str("displayName") ?: "",
                endsAt = end,
                detailsUrl = c.str("detailsURL")?.takeIf { it.startsWith("https://") },
                rewardName = benefit?.str("name") ?: first?.str("name") ?: "",
                rewardImage = benefit?.str("imageAssetURL")?.takeIf { it.startsWith("https://") },
                minutes = first?.int("requiredMinutesWatched") ?: 0,
                more = maxOf(0, drops.size - 1),
                specialEvent = isSpecialEvents(game),
            )
        }
        if (found != campaigns) campaigns = found
    }

    companion object {
        private const val KEY = "drops_notice_dismissed"

        /** Catégorie « Special Events » de Twitch : son id, ou son nom. */
        fun isSpecialEvents(game: JSONObject?): Boolean {
            if (game == null) return false
            if (game.str("id") == "509663") return true
            return listOf(game.str("name"), game.str("displayName")).any { it?.trim().equals("Special Events", ignoreCase = true) }
        }
    }
}
