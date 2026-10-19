package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.mutableStateMapOf
import com.mxfia19.twitchunblock.download.Downloads

/**
 * « Continuer à regarder » (accueil du téléphone, « Reprendre » sur la TV) :
 * VODs de l'historique vues plus de 30 s et pas finies, les 10 plus récentes.
 * Leur durée vient du lecteur, d'un téléchargement, ou de Twitch (une requête
 * par VOD et par lancement de l'app, comme VodMetaStore sur iPhone). Fil
 * principal.
 */
object ContinueWatching {
    /** VOD à reprendre : position (ms) et durée (s) connues. */
    data class Entry(val id: String, val title: String, val channel: String?, val thumb: String?,
                     val positionMs: Long, val lengthSeconds: Int) {
        /** Part déjà vue, de 0 à 1. */
        val progress: Float get() = (positionMs / 1000f / lengthSeconds).coerceIn(0f, 1f)
    }

    /** Ce qu'on sait des VODs (durée, titre, miniature), par identifiant. */
    private val metas = mutableStateMapOf<String, Vod>()
    /** VODs déjà demandées à Twitch pendant ce lancement (trouvées ou non ;
     *  une demande annulée ne compte pas). */
    private val asked = HashSet<String>()

    /** Infos d'une VOD lue (lecteur) : pas de requête de plus pour elle. */
    fun keep(meta: Vod) {
        if (meta.id.isNotEmpty() && meta.lengthSeconds > 0) metas[meta.id] = meta
    }

    private fun length(id: String): Int =
        metas[id]?.lengthSeconds?.takeIf { it > 0 } ?: Downloads.find(id)?.lengthSeconds ?: 0

    /** Il reste plus de 30 s (sinon la reprise repartirait du début), et plus
     *  de 2 min d'une longue VOD (5 %) : la fin d'un live (écran de fin,
     *  raid) ne compte pas. Durée inconnue : pas montrée (VOD peut-être
     *  finie, ou supprimée). */
    private fun unfinished(positionMs: Long, lengthSeconds: Int): Boolean {
        if (lengthSeconds <= 0) return false
        val margin = maxOf(30.0, minOf(120.0, lengthSeconds * 0.05))
        return lengthSeconds - positionMs / 1000.0 > margin
    }

    /** VODs de l'historique (les plus récentes d'abord) vues plus de 30 s. */
    private fun candidates(positions: Map<String, Long>): List<HistoryItem> =
        History.vods.filter { (positions[it.term] ?: 0L) > 30_000 }

    /** Les 10 plus récentes qui ne sont pas finies. */
    fun items(): List<Entry> {
        val positions = AppState.vodPositions()
        val out = ArrayList<Entry>()
        for (h in candidates(positions)) {
            val position = positions[h.term] ?: continue
            val total = length(h.term)
            if (!unfinished(position, total)) continue
            // Ce que l'historique n'a pas (VOD ouverte sans ses infos) : Twitch.
            val meta = metas[h.term]
            val title = if (h.display == h.term) meta?.title?.takeIf { it.isNotEmpty() } ?: h.display else h.display
            val thumb = h.thumb ?: meta?.thumbnail?.takeIf { it.startsWith("https://") }
            out += Entry(h.term, title, h.streamer, thumb, position, total)
            if (out.size >= 10) break
        }
        return out
    }

    /** Durées demandées dans l'ordre de l'historique, jusqu'à en avoir 10 à
     *  montrer. */
    suspend fun load() {
        val positions = AppState.vodPositions()
        var shown = 0
        for (h in candidates(positions)) {
            val id = h.term
            if (length(id) <= 0 && id !in asked) {
                // Notée après la réponse : une demande annulée (accueil quitté,
                // historique changé) est refaite au prochain passage, au lieu
                // de laisser la VOD sans durée jusqu'au prochain lancement.
                val meta = VodApi.meta(id)
                asked.add(id)
                meta?.let { keep(it) }
            }
            if (unfinished(positions[id] ?: 0L, length(id))) shown++
            if (shown >= 10) return
        }
    }
}
