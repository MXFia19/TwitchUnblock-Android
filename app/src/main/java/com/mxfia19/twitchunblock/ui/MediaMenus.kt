package com.mxfia19.twitchunblock.ui

import android.content.Context
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.ClipPlayback
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.LiveSources
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.download.MediaFiles
import com.mxfia19.twitchunblock.player.MutedRange
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.VodMarkers
import com.mxfia19.twitchunblock.player.VodUnmute
import kotlinx.coroutines.launch

/** Fenêtres du lecteur ajoutées en série 6 (partie Média). */
object MediaUi {
    /** Fenêtre de téléchargement de la VOD lue (DownloadDialog). */
    var downloadOpen by mutableStateOf(false)
}

/**
 * Source du direct dans le menu de qualité (série 6), comme le menu ⋯ de
 * l'iPhone : celle qui sert le flux, puis toutes dans l'ordre des réglages
 * (cochée : celle en cours). En choisir une la met en tête et relance le
 * direct par elle ; pendant une lecture par le compte, le compte est laissé
 * de côté pour cette chaîne jusqu'au prochain lancement.
 */
@Composable
fun LiveSourceMenuItems(session: PlaybackSession, onClose: () -> Unit) {
    val used = session.source ?: return
    if (!session.isLive) return
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(AppState.t("live_source") + " : " + sourceName(used) + if (expanded) "  ▴" else "  ▾", color = TU.muted, fontSize = 13.sp) },
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        for (id in LiveSources.order()) {
            DropdownMenuItem(
                text = { Text(sourceName(id) + if (id == used) "  ✓" else "") },
                onClick = {
                    onClose()
                    pickLiveSource(session, id)
                },
            )
        }
    }
}

/** Source choisie dans le lecteur : en tête de l'ordre, et le direct relancé
 *  par elle (sauf si c'est déjà elle qui le sert). */
fun pickLiveSource(session: PlaybackSession, id: String) {
    val login = (session.request as? PlayRequest.Live)?.login ?: return
    val used = session.source
    if (id == used) return
    if (used == "account") LiveSources.skipAccount(login)
    LiveSources.prefer(id)
    Logger.info("PLAYER", "Source choisie dans le lecteur", "$login · $id")
    session.retry()
}

/**
 * Clip (menu ⋮) : « Voir dans la VOD » à l'instant regardé, quand la VOD
 * d'origine existe encore, et « Télécharger le clip » dans la qualité lue
 * (fichier MP4, à l'endroit choisi). Comme l'iPhone.
 */
@Composable
fun ClipMenuItems(session: PlaybackSession, nav: Navigator, onClose: () -> Unit) {
    val clip = session.request as? PlayRequest.Clip ?: return
    if (session.state !is PlaybackSession.State.Ready) return
    val context = LocalContext.current
    val info by produceState(ClipMedia.cached(clip.slug), clip.slug) { value = ClipMedia.info(clip.slug) }
    val vod = info?.vodId
    if (vod != null) {
        val at = ((info?.vodOffset ?: 0.0) + session.player.currentPosition / 1000.0).toLong()
        DropdownMenuItem(text = { Text(AppState.t("clip_full_vod") + "  " + Format.clock(at)) }, onClick = {
            onClose()
            Logger.info("PLAYER", "Clip : VOD d'origine", "$vod · ${at}s")
            nav.play(PlayRequest.Vod(vod, "", session.channelLogin, startMs = at * 1000))
        })
    }
    DropdownMenuItem(text = { Text(AppState.t("clip_download")) }, onClick = {
        onClose()
        ClipMedia.download(context, session)
    })
}

/** VOD d'origine des clips ouverts (BrowseApi.clip), et téléchargement d'un clip. */
object ClipMedia {
    private val infos = HashMap<String, ClipPlayback>()

    fun cached(slug: String): ClipPlayback? = synchronized(infos) { infos[slug] }

    suspend fun info(slug: String): ClipPlayback? {
        cached(slug)?.let { return it }
        val c = BrowseApi.clip(slug) ?: return null
        synchronized(infos) {
            if (infos.size > 50) infos.clear()
            infos[slug] = c
        }
        return c
    }

    /** MP4 de la qualité lue, enregistré à l'endroit choisi. */
    fun download(context: Context, session: PlaybackSession) {
        val url = session.quality?.let { session.links[it] } ?: session.links.values.firstOrNull() ?: return
        val name = MediaFiles.fileName(listOfNotNull(session.channelName, session.title.ifEmpty { null }).joinToString(" - ")) + ".mp4"
        MediaFiles.saveAs(context, name, "video/mp4") { target ->
            MediaFiles.toast(context, AppState.t("clip_dl_started"))
            MediaFiles.scope.launch {
                val ok = MediaFiles.copyUrl(context, url, target)
                if (ok) Logger.success("FILES", "Clip enregistré", name) else Logger.warn("FILES", "Clip non enregistré", name)
                MediaFiles.toast(context, AppState.t(if (ok) "m6_clip_saved" else "clip_dl_failed"))
            }
        }
    }
}

/**
 * Ajouts du lecteur (série 6, partie Média), composés une fois par
 * PlayerScreen : fenêtre de téléchargement, son d'origine des passages
 * coupés (barre et bandeau), repères d'une partie de VOD lue hors ligne.
 */
@Composable
fun MediaPlayerHost(session: PlaybackSession, nav: Navigator) {
    // Autre lecture : la fenêtre de la précédente se ferme.
    LaunchedEffect(session.request) { MediaUi.downloadOpen = false }
    if (MediaUi.downloadOpen && session.vodId != null && !session.isOffline) {
        DownloadDialog(session) { MediaUi.downloadOpen = false }
    }
    UnmuteEffects(session)
    PartMarkers(session)
}

/** Son d'origine rétabli (VodUnmute) : la barre ne garde en orange que les
 *  passages encore muets, et un bandeau le dit à l'ouverture de la VOD. */
@Composable
private fun UnmuteEffects(session: PlaybackSession) {
    val vod = session.vodId ?: return
    if (session.isOffline || session.qualities.isEmpty()) return
    val key = session.links.values.firstOrNull()?.let { VodUnmute.keyOf(it) } ?: return
    val outcome = VodUnmute.outcomes[key] ?: return
    val markers = PlayerUi.markers
    LaunchedEffect(outcome, markers?.id) {
        val m = PlayerUi.markers?.takeIf { it.id == vod } ?: return@LaunchedEffect
        if (m.muted != outcome.remaining) PlayerUi.markers = m.copy(muted = outcome.remaining)
    }
    val restored = outcome.restored > 0
    LaunchedEffect(vod, session.openCount, restored) {
        if (restored) PlayerUi.flash(AppState.t("vod_unmuted"), 2_500)
    }
}

/** Partie d'une VOD lue hors ligne : repères de la VOD (passages coupés,
 *  heure réelle) ramenés au début de la partie. */
@Composable
private fun PartMarkers(session: PlaybackSession) {
    if (!session.isOffline) return
    val vod = session.vodId ?: return
    val item = Downloads.find(vod)?.takeIf { it.isPart } ?: return
    val markers = PlayerUi.markers
    val done = remember(vod) { arrayOfNulls<VodMarkers>(1) }
    LaunchedEffect(markers) {
        val m = markers?.takeIf { it.id == vod } ?: return@LaunchedEffect
        if (m === done[0]) return@LaunchedEffect
        val length = item.lengthSeconds.toDouble()
        val shifted = m.muted.mapNotNull { r ->
            val s = r.start - item.from
            val e = r.end - item.from
            if (e <= 0 || s >= length) null else MutedRange(maxOf(0.0, s), minOf(length, e))
        }
        val fixed = m.copy(muted = shifted, broadcastStart = m.broadcastStart?.plus((item.from * 1000).toLong()),
                           length = item.lengthSeconds)
        done[0] = fixed
        PlayerUi.markers = fixed
    }
}
