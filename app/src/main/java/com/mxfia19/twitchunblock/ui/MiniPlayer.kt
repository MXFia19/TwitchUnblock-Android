package com.mxfia19.twitchunblock.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.PlayerExtraPrefs
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.player.LiveLatency
import com.mxfia19.twitchunblock.player.NetworkReturn
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── Mini-lecteur (série 6) ──
// Le lecteur en grand est en haut de la pile (Screen.Player). Réduit, il en
// sort pour Navigator.mini : sa lecture (même PlaybackSession, PlayerHolder,
// notification) continue sous les pages et les onglets, avec une barre
// au-dessus des onglets. Touchée, elle le remet en haut de la pile, en grand,
// sans rien recharger. ✕ arrête la lecture (PhoneApp libère la session).

/** Lecteur en grand → mini-lecteur (Retour, glissé vers le bas, Réduire). */
fun Navigator.minimize() {
    val top = stack.lastOrNull() as? Screen.Player ?: return
    // Jamais de pile vide : sous le lecteur, au moins l'onglet de départ.
    if (stack.size == 1) stack.add(0, BrowseTabs.startScreen())
    stack.removeAt(stack.lastIndex)
    mini = top
    reopenAt = null
    Logger.info("PLAYER", "Lecteur réduit")
}

/** Mini-lecteur touché (ou image dans l'image agrandie) : le lecteur revient
 *  en grand, sur la même lecture. */
fun Navigator.expand() {
    val p = mini ?: return
    mini = null
    reopenAt = null
    stack.removeAll { it is Screen.Player }
    stack.add(p)
    Logger.info("PLAYER", "Lecteur rouvert en grand")
}

/** ✕ du mini-lecteur : la lecture s'arrête. */
fun Navigator.closeMini() {
    mini = null
    reopenAt = null
}

/** Page ouverte depuis le lecteur en grand (fiche chaîne, connexion…) : il se
 *  réduit, la vidéo reste visible en bas, et il revient en grand quand cette
 *  page se ferme (comme l'iPhone). Il jouait le son sans image ni commande. */
internal fun Navigator.minimizeUnder(page: Screen) {
    // Lecteur poussé directement : il remplace le mini-lecteur (un seul à la fois).
    if (page is Screen.Player) {
        dropMini()
        return
    }
    if (stack.lastOrNull() !is Screen.Player) return
    minimize()
    reopenAt = stack.size
}

/** Après un retour : la page ouverte depuis le lecteur fermée, il revient en grand. */
internal fun Navigator.reopenAfterPop() {
    val at = reopenAt ?: return
    if (mini == null) reopenAt = null
    else if (stack.size <= at) expand()
}

/** Changement d'onglet : un lecteur en grand se réduit au lieu de s'arrêter. */
internal fun Navigator.keepPlaying() {
    (stack.lastOrNull { it is Screen.Player } as? Screen.Player)?.let { mini = it }
    reopenAt = null
}

/** Autre lecture ou multi-live : le mini-lecteur laisse la place. */
internal fun Navigator.dropMini() {
    mini = null
    reopenAt = null
}

/** Quitter le lecteur en grand, depuis le geste (GesturePlayerView), le
 *  bouton Réduire ou Retour. */
object PlayerLeave {
    /** Posé tant que le lecteur est en grand ; null sinon. */
    var action by mutableStateOf<(() -> Unit)?>(null)
    /** Image dans l'image demandée en quittant le lecteur : il se réduit dessous. */
    var minimizeOnPip = false
}

/** « PiP en quittant le lecteur » (réglage) si la vidéo joue, sinon mini-lecteur. */
private fun leavePlayer(activity: MainActivity?, nav: Navigator, session: PlaybackSession) {
    if (PlayerExtraPrefs.pipOnLeave.value && activity != null && pipAllowed(activity, session)) {
        PlayerLeave.minimizeOnPip = true
        if (activity.enterPipNow()) {
            Logger.info("PLAYER", "Lecteur quitté : image dans l'image")
            return
        }
        PlayerLeave.minimizeOnPip = false
    }
    nav.minimize()
}

/** Une image à montrer qui joue : ni chargement, ni erreur, ni son seul, ni direct fini. */
private fun pipAllowed(activity: Activity, session: PlaybackSession): Boolean {
    if (Build.VERSION.SDK_INT < 26 || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    val q = session.quality ?: return false
    return session.state is PlaybackSession.State.Ready && session.player.playWhenReady && !Quality.isAudio(q) &&
        !LiveEnd.isEnded(session)
}

/**
 * Appelé une fois par PhoneApp, avant le lecteur et les pages : fenêtre de la
 * minuterie, puis pour la lecture principale (en grand ou réduite) Retour qui
 * réduit, son seul en arrière-plan, reprise au retour du réseau, fin du
 * direct, faible latence. Vrai : image dans l'image du mini-lecteur affichée,
 * PhoneApp n'a rien d'autre à montrer.
 */
@Composable
fun MiniPlayerHost(session: PlaybackSession?, nav: Navigator): Boolean {
    val context = LocalContext.current
    LaunchedEffect(Unit) { NetworkReturn.start(context.applicationContext) }
    SleepDialogHost()
    if (session == null) return false
    val activity = context as? MainActivity
    val full = nav.current is Screen.Player
    // Lecteur en grand : Retour le réduit (ou l'ouvre en image dans
    // l'image), au lieu de le fermer. Le paysage forcé (PlayerScreen) passe avant.
    BackHandler(enabled = full) { leavePlayer(activity, nav, session) }
    DisposableEffect(full, session) {
        PlayerLeave.action = if (full) ({ leavePlayer(activity, nav, session) }) else null
        onDispose { PlayerLeave.action = null }
    }
    LaunchedEffect(full) { if (!full) ChapterUi.open = false }
    // Image dans l'image ouverte en quittant le lecteur : il se réduit dessous.
    LaunchedEffect(PipState.active) {
        if (PipState.active && PlayerLeave.minimizeOnPip) {
            PlayerLeave.minimizeOnPip = false
            nav.minimize()
        }
    }
    NetworkResume(session)
    LiveEndWatcher(session)
    BackgroundAudio(session)
    LowLatencyKeeper(session)
    if (full) ChapterDialogHost(session)
    // Image dans l'image depuis le mini-lecteur : rien que la vidéo.
    if (PipState.active && nav.mini != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(session, Modifier.fillMaxSize(), controls = false)
        }
        return true
    }
    return false
}

/** Erreurs qu'un retour du réseau ne change pas. */
private val PERMANENT_ERRORS = setOf("offline_msg", "not_found", "err_geo_blocked")

/** Réseau revenu après une coupure : un lecteur arrêté sur « Réessayer » (ou
 *  une erreur de chargement) repart seul, une fois, deux secondes après. */
@Composable
private fun NetworkResume(session: PlaybackSession) {
    LaunchedEffect(session) {
        var seen = NetworkReturn.comebacks
        snapshotFlow { NetworkReturn.comebacks }.collectLatest { n ->
            if (n == seen) return@collectLatest
            seen = n
            delay(2_000)
            val s = session.state
            if (s is PlaybackSession.State.Error && s.key !in PERMANENT_ERRORS && !LiveEnd.isEnded(session)) {
                Logger.info("PLAYER", "Réseau revenu : lecture relancée", s.key)
                session.retry()
            }
        }
    }
}

/** Mode faible latence (LiveLatency.kt) : direct de la lecture principale. */
@Composable
private fun LowLatencyKeeper(session: PlaybackSession) {
    val on = AppState.lowLatency && session.isLive && session.state is PlaybackSession.State.Ready &&
        !LiveEnd.isEnded(session)
    LaunchedEffect(session, session.openCount, on) {
        if (on) LiveLatency.run(session.player)
    }
}

/** Son seul en arrière-plan (réglage) : écran verrouillé ou autre app, sans
 *  image dans l'image, la qualité audio (beaucoup moins de données) ; au
 *  retour, la qualité d'avant. La pause du réglage « Lecture en
 *  arrière-plan » coupé reste à MainActivity.onStop. */
@Composable
private fun BackgroundAudio(session: PlaybackSession) {
    val activity = LocalContext.current as? Activity
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, session) {
        var restore: String? = null
        var forOpen = -1
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (activity?.isChangingConfigurations == true || activity?.isInPictureInPictureMode == true) return@LifecycleEventObserver
                if (!AppState.backgroundPlay || !PlayerExtraPrefs.bgAudioOnly.value) return@LifecycleEventObserver
                val q = session.quality ?: return@LifecycleEventObserver
                if (Quality.isAudio(q) || session.state !is PlaybackSession.State.Ready || !session.player.playWhenReady ||
                    LiveEnd.isEnded(session)) return@LifecycleEventObserver
                val audio = session.qualities.firstOrNull { Quality.isAudio(it) } ?: return@LifecycleEventObserver
                restore = q
                forOpen = session.openCount
                switchQuality(session, audio)
                Logger.info("PLAYER", "Arrière-plan : son seul", q)
            } else if (event == Lifecycle.Event.ON_START) {
                val q = restore ?: return@LifecycleEventObserver
                restore = null
                val now = session.quality
                // Autre lecture entre-temps (raid suivi…), ou qualité changée : rien à rendre.
                if (session.openCount != forOpen || now == null || !Quality.isAudio(now)) return@LifecycleEventObserver
                switchQuality(session, q)
                Logger.info("PLAYER", "Retour dans l'app : image rendue", q)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Autre qualité sans la retenir comme préférence ; une lecture en pause le reste. */
private fun switchQuality(session: PlaybackSession, key: String) {
    val wanted = session.player.playWhenReady
    session.selectQuality(key, remember = false)
    session.player.playWhenReady = wanted
}

/** Lecture demandée (playWhenReady), suivie : icône lecture/pause. */
@Composable
fun rememberPlayWhenReady(player: Player): Boolean {
    var on by remember(player) { mutableStateOf(player.playWhenReady) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                on = playWhenReady
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return on
}

/** Icônes absentes de material-icons-core (tracés Material, 24 × 24). */
object PlayerIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val pause by lazy { icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val bedtime by lazy {
        icon("bedtime", "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02" +
            "C13.15,16.73 8.57,8.55 12.34,2.02z")
    }
}

/**
 * Barre du mini-lecteur, au-dessus des onglets (ou en bas d'une page) :
 * l'image (ou l'avatar en son seul), le titre et l'état, lecture/pause, ✕.
 * Un toucher remet le lecteur en grand. `tabsBelow` : la barre d'onglets
 * dessous porte déjà la marge de la barre de navigation du système.
 */
@Composable
fun MiniPlayerBar(session: PlaybackSession, nav: Navigator, tabsBelow: Boolean) {
    val activity = LocalContext.current as? MainActivity
    val player = session.player
    val playWhenReady = rememberPlayWhenReady(player)
    // Image dans l'image en quittant l'app : suit la lecture du mini-lecteur aussi.
    LaunchedEffect(playWhenReady) { activity?.updatePip() }
    DisposableEffect(Unit) { onDispose { activity?.updatePip() } }
    val ended = LiveEnd.isEnded(session)
    val state = session.state
    val audioOnly = session.quality?.let { Quality.isAudio(it) } == true
    // Avancée d'une VOD, relue chaque seconde ; null pour un direct.
    val vod = !session.isLive && state is PlaybackSession.State.Ready
    val progress by produceState<Float?>(null, player, vod) {
        if (!vod) {
            value = null
            return@produceState
        }
        while (true) {
            val d = player.duration
            value = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else null
            delay(1_000)
        }
    }
    val title = session.title.ifEmpty { session.channelName ?: (session.request as? PlayRequest.Live)?.login ?: "" }
    val sub = when {
        ended -> AppState.t("p6_live_ended")
        state is PlaybackSession.State.Error -> AppState.t(state.key)
        state is PlaybackSession.State.Loading -> AppState.t("loading")
        session.isLive -> listOfNotNull(session.channelName, if (session.viewers > 0) Format.viewers(session.viewers) else null)
            .joinToString(" · ")
        else -> session.channelName ?: ""
    }
    // Direct qui joue : point rouge devant l'état, comme les cartes.
    val liveDot = session.isLive && !ended && state is PlaybackSession.State.Ready
    Column(Modifier.fillMaxWidth().background(TU.surface).then(if (tabsBelow) Modifier else Modifier.navigationBarsPadding())) {
        val p = progress
        if (p != null) {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = TU.primary, trackColor = TU.line)
        } else {
            HorizontalDivider(color = TU.line)
        }
        Row(Modifier.fillMaxWidth().height(60.dp).clickable { nav.expand() }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                if (audioOnly || ended || state !is PlaybackSession.State.Ready) {
                    Avatar(session.avatar, 34.dp)
                } else {
                    MiniVideo(session)
                }
                // La vue de l'image garde les touchers : un calque les rend à la barre.
                Box(Modifier.fillMaxSize().clickable { nav.expand() })
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, color = TU.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty() || liveDot) {
                    val text = buildAnnotatedString {
                        if (liveDot) withStyle(SpanStyle(color = TU.live)) { append("● ") }
                        append(sub)
                    }
                    Text(text, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                ended -> {}
                state is PlaybackSession.State.Error -> IconButton(onClick = { session.retry() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = AppState.t("retry"), tint = TU.text)
                }
                state is PlaybackSession.State.Loading -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = TU.primary, strokeWidth = 2.dp)
                }
                else -> IconButton(onClick = { if (playWhenReady) player.pause() else player.play() }) {
                    Icon(if (playWhenReady) PlayerIcons.pause else Icons.Filled.PlayArrow,
                         contentDescription = AppState.t(if (playWhenReady) "multi_pause" else "multi_play"), tint = TU.text)
                }
            }
            IconButton(onClick = {
                Logger.info("PLAYER", "Mini-lecteur fermé")
                nav.closeMini()
            }) {
                Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text)
            }
        }
    }
}

/** Image du mini-lecteur : une PlayerView sans commandes, qui prend la sortie
 *  vidéo du lecteur (rendue à l'écran du lecteur quand il revient en grand). */
@OptIn(UnstableApi::class)
@Composable
private fun MiniVideo(session: PlaybackSession) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isClickable = false
                isFocusable = false
                player = session.player
            }
        },
        update = { view -> if (view.player !== session.player) view.player = session.player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
