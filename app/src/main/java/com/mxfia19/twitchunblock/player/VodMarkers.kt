package com.mxfia19.twitchunblock.player

import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.dbl
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str

/** Passage d'une VOD dont Twitch a coupé le son (musique protégée), en
 *  secondes depuis le début. */
data class MutedRange(val start: Double, val end: Double) {
    fun contains(t: Double) = t >= start && t < end
}

/** Repères d'une VOD : passages coupés, début de la diffusion (archive d'un
 *  direct seulement : heure réelle, « Aller à une heure »), durée (s). */
data class VodMarkers(val id: String, val muted: List<MutedRange>, val broadcastStart: Long?, val length: Int)

/** Comme getVodMarkers (iOS), sans les chapitres (VodApi.chapters). */
object VodMarkersApi {
    suspend fun load(id: String): VodMarkers? {
        val q = "query(\$id: ID!) { video(id: \$id) { createdAt broadcastType lengthSeconds " +
            "muteInfo { mutedSegmentConnection { nodes { offset duration } } } } }"
        val v = Gql.request(q, Gql.vars("id" to id))?.obj("video") ?: return null
        val raw = v.obj("muteInfo")?.obj("mutedSegmentConnection")?.arr("nodes")?.objects().orEmpty().mapNotNull { n ->
            val o = n.dbl("offset") ?: return@mapNotNull null
            val d = n.dbl("duration") ?: return@mapNotNull null
            if (d > 0) MutedRange(o, o + d) else null
        }.sortedBy { it.start }
        // Passages qui se touchent (à 1 s près) : un seul.
        val merged = mutableListOf<MutedRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end + 1) merged[merged.lastIndex] = MutedRange(last.start, maxOf(last.end, r.end))
            else merged += r
        }
        // Heure réelle : seulement pour l'archive d'un direct (son début est
        // celui de la diffusion), pas pour un temps fort ou une vidéo importée.
        val start = if (v.str("broadcastType")?.uppercase() == "ARCHIVE") Format.parseDate(v.str("createdAt")) else null
        return VodMarkers(id, merged, start, v.int("lengthSeconds") ?: 0)
    }
}
