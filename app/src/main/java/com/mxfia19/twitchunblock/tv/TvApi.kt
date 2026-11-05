package com.mxfia19.twitchunblock.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.Clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
