package com.mxfia19.twitchunblock.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Clip
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Vod
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Requêtes propres à la page d'une chaîne sur la TV. */
object TvApi {
    /** Collection (playlist) d'une chaîne et ses vidéos. `total` : vidéos en tout. */
    data class Playlist(val id: String, val title: String, val total: Int, val videos: List<Vod>)

    /** Collections d'une chaîne, avec leurs vidéos (20 au plus, 50 vidéos
     *  chacune, vides écartées), comme l'Apple TV (getCollections). null :
     *  pas de réponse. */
    suspend fun collections(login: String): List<Playlist>? {
        val q = "query(\$l: String!) { user(login: \$l) { collections(first: 20) { edges { node { id title " +
            "items(first: 50) { totalCount edges { node { ... on Video { id title lengthSeconds createdAt " +
            "previewThumbnailURL(width: 320, height: 180) } } } } } } } } }"
        val d = Gql.request(q, Gql.vars("l" to login.lowercase())) ?: return null
        val edges = d.obj("user")?.obj("collections")?.arr("edges")?.objects() ?: return emptyList()
        return edges.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            val id = n.str("id") ?: return@mapNotNull null
            val items = n.obj("items") ?: return@mapNotNull null
            val videos = items.arr("edges")?.objects().orEmpty().mapNotNull { ie ->
                val v = ie.obj("node") ?: return@mapNotNull null
                val vid = v.str("id") ?: return@mapNotNull null
                Vod(vid, v.str("title") ?: "", v.str("previewThumbnailURL") ?: "", v.str("createdAt") ?: "",
                    v.int("lengthSeconds") ?: 0)
            }
            if (videos.isEmpty()) null
            else Playlist(id, n.str("title") ?: "", items.int("totalCount") ?: videos.size, videos)
        }
    }
}

/** Clips les plus vus de la période choisie (LAST_DAY, LAST_WEEK, LAST_MONTH,
 *  ALL_TIME ; 100 au plus, comme l'Apple TV). Une réponse arrivée après un
 *  changement de période est écartée. Fil principal. */
class TvClips(private val login: String, private val scope: CoroutineScope) {
    var period by mutableStateOf("LAST_WEEK")
        private set
    /** null : en cours. */
    var clips by mutableStateOf<List<Clip>?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    fun load(p: String = period) {
        period = p
        clips = null
        failed = false
        scope.launch {
            val page = BrowseApi.clips(login, p, null)
            if (p != period) return@launch
            if (page == null) failed = true else clips = page.items
        }
    }
}
