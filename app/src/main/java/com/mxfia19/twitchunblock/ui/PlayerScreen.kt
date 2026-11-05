package com.mxfia19.twitchunblock.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.chat.VodChat
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NetworkStatus
import com.mxfia19.twitchunblock.core.PlayerPrefs
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.download.Downloads
import com.mxfia19.twitchunblock.core.Features
import com.mxfia19.twitchunblock.events.LiveEvents
import com.mxfia19.twitchunblock.events.PinnedWatcher
import com.mxfia19.twitchunblock.events.RaidWatcher
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.points.ChannelPoints
import com.mxfia19.twitchunblock.web.ClipCreator
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.delay

/** Image du lecteur (PlayerView de Media3 et ses commandes), avec les gestes
 *  du lecteur : double-tap ±10 s, zoom, remplir l'écran (PlayerGestures.kt). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(session: PlaybackSession, modifier: Modifier = Modifier, onFullscreen: (() -> Unit)? = null,
                 focusOnStart: Boolean = false, controls: Boolean = true) {
    // Chat posé sur l'image (paysage) : les commandes s'en écartent (ChatLayout.kt).
    val chatInset = LocalChatOverlayInset.current
    // Remplir l'écran : en paysage seulement, et pas l'image verticale (9:16
    // étirée à la largeur, il n'en restait qu'un quart). Passages coupés de la VOD sur la barre.
    val fill = PlayerPrefs.fillScreen.value && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        !session.vertical.on
    val muted = PlayerUi.markers?.takeIf { it.id == session.vodId }?.muted.orEmpty()
    AndroidView(
        factory = { ctx ->
            GesturePlayerView(ctx).apply {
                // Sans la vitesse dans la roue dentée : le menu ⋮ seul la règle (PlayerGestures.kt).
                usePlayer(session.player)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
                if (onFullscreen != null) setFullscreenButtonClickListener { onFullscreen() }
                if (focusOnStart) {
                    isFocusable = true
                    post { requestFocus() }
                }
            }
        },
        update = { view ->
            view.usePlayer(session.player)
            // Écran allumé pendant la lecture seulement (pas en pause, à la fin
            // ni sur une erreur) ; relu à chaque changement.
            view.keepScreenOn = session.isPlaying
            if (view.useController != controls) {
                view.useController = controls
                if (!controls) view.hideController()
            }
            view.bind(session, gestures = controls, fill = fill, muted = muted)
            view.insetControls(chatInset)
        },
        // La session survit à l'écran : la vue retirée lâche le lecteur.
        onRelease = { view -> view.usePlayer(null) },
        modifier = modifier.background(Color.Black),
    )
}

/** Chargement (source essayée) ou erreur, par-dessus l'image. */
@Composable
fun PlayerOverlay(session: PlaybackSession, modifier: Modifier = Modifier) {
    when (val s = session.state) {
        is PlaybackSession.State.Loading -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TU.primary)
            val step = s.step
            if (step != null) {
                Text(AppState.t("loading_source").replace("{s}", sourceName(step)), color = TU.muted, fontSize = 13.sp,
                     modifier = Modifier.padding(top = 8.dp))
            }
        }
        is PlaybackSession.State.Error -> Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(AppState.t(s.key), color = TU.text, fontSize = 15.sp)
            Button(onClick = { session.retry() }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                   modifier = Modifier.padding(top = 10.dp)) { Text(AppState.t("retry")) }
        }
        PlaybackSession.State.Ready -> {}
    }
}

/** Nom d'une source dans la langue de l'app (src_as, src_twitch…, comme
 *  l'iPhone) ; l'identifiant faute de texte. */
fun sourceName(id: String): String {
    val key = "src_$id"
    val name = AppState.t(key)
    return if (name == key) id else name
}

/** Minuterie : libellés des durées proposées (minutes). */
val sleepChoices = listOf(15, 30, 45, 60, 120)

fun sleepLabel(minutes: Int): String = when (minutes) {
    60 -> AppState.t("sleep_hour")
    120 -> AppState.t("sleep_2hours")
    else -> "$minutes ${AppState.t("sleep_minutes")}"
}

/** Chat rejoué d'une VOD, ou d'un clip (sa VOD d'origine) : suit la position
 *  de lecture (ChatReplay.kt). */
@Composable
fun rememberVodChat(session: PlaybackSession, show: Boolean): VodChat? = rememberReplayChat(session, show)

/** Messages du chat rejoué (ChatReplay.kt) : la TV passe ses tailles. */
@Composable
fun VodChatList(chat: VodChat, modifier: Modifier = Modifier, emoteSize: Int? = null, fontSize: Int? = null,
                background: Color = TU.bg) = ReplayChatList(chat, modifier, emoteSize, fontSize, background)

/** Écran du lecteur ; la session est portée par PhoneApp (elle survit à un
 *  écran ouvert par-dessus). */
@Composable
fun PlayerScreen(session: PlaybackSession, nav: Navigator) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            (activity as? MainActivity)?.updatePip()
        }
    }
    // Android 12 et plus : passage automatique en image dans l'image tant que ça joue.
    LaunchedEffect(session.isPlaying) { (activity as? MainActivity)?.updatePip() }
    // Format de l'image changé (image verticale, autre flux) : la fenêtre le suit.
    DisposableEffect(session.player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                (activity as? MainActivity)?.updatePip()
            }
        }
        session.player.addListener(listener)
        onDispose { session.player.removeListener(listener) }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pip = PipState.active
    // Plein écran en paysage : barres du système masquées (un glissement les rappelle).
    DisposableEffect(landscape, pip) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape || pip) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Chat de la chaîne demandée dès l'ouverture : pas celui de la chaîne
    // d'avant le temps de la résolution (VOD de X puis direct de Y).
    val chatLogin = (session.request as? PlayRequest.Live)?.login?.lowercase()?.takeIf { it.isNotBlank() }
    val chat = remember(chatLogin) { chatLogin?.let { ChatClient(it, scope) } }
    DisposableEffect(chat) {
        // Identifiant sûr seulement une fois ce direct résolu (une VOD ou un
        // clip ne le change pas) ; sinon le ROOMSTATE de Twitch le donne.
        val resolved = session.state is PlaybackSession.State.Ready && session.channelLogin.equals(chatLogin, ignoreCase = true)
        chat?.start(if (resolved) session.channelId else null)
        onDispose { chat?.stop() }
    }
    // ChatBars.kt : le chat du direct attend l'image (lecteur principal seulement).
    ChatSyncFeeder(session, chat)
    val vodChat = rememberVodChat(session, AppState.showChat)

    // Direct : points de chaîne, événements, message épinglé, raids.
    val points = remember { ChannelPoints(scope) }
    val events = remember { LiveEvents(scope) }
    val pinned = remember { PinnedWatcher(scope) }
    val raids = remember { RaidWatcher(scope) }
    var rewardsOpen by remember { mutableStateOf(false) }
    val liveLogin = if (session.isLive && session.state is PlaybackSession.State.Ready) session.channelLogin else null
    val liveId = if (liveLogin != null) session.channelId else null
    DisposableEffect(liveLogin, liveId, WebSession.generation) {
        if (liveLogin != null && liveId != null) {
            points.load(liveLogin, liveId)
            events.start(liveLogin, liveId)
            pinned.start(liveId)
            if (Features.enableRaids.value) raids.start(liveId)
        }
        onDispose {
            points.stop()
            events.stop()
            pinned.stop()
            raids.stop()
        }
    }
    // Raid parti : on suit la chaîne raidée.
    LaunchedEffect(raids.joinTarget) {
        val target = raids.joinTarget ?: return@LaunchedEffect
        raids.joinTarget = null
        if (Features.enableRaids.value) nav.play(PlayRequest.Live(target))
    }
    val extras: @Composable () -> Unit = {
        if (liveLogin != null) {
            LiveExtras(points, events, pinned, raids,
                       onJoinRaid = { nav.play(PlayRequest.Live(it)) },
                       onOpenRewards = { rewardsOpen = true },
                       onWebLogin = { nav.push(Screen.WebLogin) })
        }
    }
    // Paysage demandé par le bouton plein écran : Retour revient d'abord en
    // portrait, au lieu de fermer le lecteur.
    var forcedLandscape by remember { mutableStateOf(false) }
    val toggleFullscreen = {
        forcedLandscape = !landscape
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                         else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    BackHandler(enabled = forcedLandscape && landscape) {
        forcedLandscape = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Vitesse, repères de la VOD, fiches du titre et de l'heure (PlayerAddons.kt).
    PlayerAddons(session, nav)

    // Image dans l'image : rien que la vidéo.
    if (pip) {
        VideoSurface(session, Modifier.fillMaxSize(), controls = false)
        return
    }

    // Fenêtre du clip : ici et pas dans le menu ⋮ (portrait seulement), pour
    // rester affichée en paysage au lieu de revenir plus tard sur un autre direct.
    ClipDialog(nav)

    if (landscape) {
        // ChatLayout.kt : chat en colonne, posé sur l'image ou replié (bouton
        // du chat), largeur réglable à la poignée.
        LandscapePlayer(
            hasChat = (chat != null || vodChat != null) && AppState.showChat,
            video = {
                VideoSurface(session, Modifier.fillMaxSize(), onFullscreen = toggleFullscreen)
                PlayerOverlay(session, Modifier.align(Alignment.Center))
                StreamVideoOverlay(session)
                PlayerVideoOverlay(session)
            },
        ) { chatModifier, overlay ->
            if (chat != null) {
                Column(chatModifier) {
                    if (rewardsOpen) {
                        RewardsPanel(points, Modifier.weight(1f), onClose = { rewardsOpen = false }) { nav.push(Screen.WebLogin) }
                    } else {
                        extras()
                        ChatPanel(chat, Modifier.weight(1f), onOpenChannel = { nav.push(Screen.Channel(it)) }, translucent = overlay) {
                            nav.push(Screen.Login)
                        }
                    }
                }
            } else if (vodChat != null) {
                VodChatList(vodChat, chatModifier, background = if (overlay) Color.Black.copy(alpha = 0.45f) else TU.bg)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(TU.bg)) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding().portraitVideoSize(session)) {
            VideoSurface(session, Modifier.fillMaxSize(), onFullscreen = toggleFullscreen)
            PlayerOverlay(session, Modifier.align(Alignment.Center))
            StreamVideoOverlay(session)
            PlayerVideoOverlay(session)
        }
        BroadcastBar(session)
        InfoBar(session, nav)
        when {
            rewardsOpen && liveLogin != null ->
                RewardsPanel(points, Modifier.weight(1f).navigationBarsPadding(), onClose = { rewardsOpen = false }) {
                    nav.push(Screen.WebLogin)
                }
            chat != null && AppState.showChat -> {
                extras()
                ChatPanel(chat, Modifier.weight(1f).navigationBarsPadding().imePadding(),
                          onOpenChannel = { nav.push(Screen.Channel(it)) }) { nav.push(Screen.Login) }
            }
            vodChat != null -> VodChatList(vodChat, Modifier.weight(1f).navigationBarsPadding())
            else -> {
                extras()
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoBar(session: PlaybackSession, nav: Navigator) {
    var quality by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(TU.surface).padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(session.avatar, 36.dp, Modifier.focusCard({ session.channelLogin?.let { nav.push(Screen.Channel(it)) } }))
        Spacer(Modifier.width(10.dp))
        // Titre souvent coupé : le toucher ouvre sa fiche (titre en entier…).
        Column(Modifier.weight(1f).clickable { PlayerUi.infoOpen = true }) {
            Text(session.title.ifEmpty { session.channelName ?: "" }, color = TU.text, fontSize = 14.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = mutableListOf<String>()
            session.channelName?.let { parts += it }
            if (session.isLive) {
                if (session.game.isNotEmpty()) parts += session.game
                parts += "● " + Format.viewers(session.viewers)
                Format.uptime(session.startedAt)?.let { parts += it }
            }
            // Relu chaque seconde : le compte à rebours avance (PlayerAddons.kt).
            sleepLeft(session)?.let { parts += "⏾ " + Format.clock(it) }
            Text(parts.joinToString(" · "), color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { quality = true }, enabled = session.qualities.isNotEmpty()) {
                Text(session.quality?.let { Quality.label(it, AppState.lang) } ?: "—", color = TU.text, fontSize = 13.sp)
            }
            DropdownMenu(expanded = quality, onDismissRequest = { quality = false }) {
                for (q in session.qualities) {
                    DropdownMenuItem(
                        text = { Text(Quality.label(q, AppState.lang) + if (q == session.quality) "  ✓" else "") },
                        onClick = {
                            quality = false
                            session.selectQuality(q)
                        },
                    )
                }
                session.source?.let { src ->
                    DropdownMenuItem(text = { Text(AppState.t("live_source") + " : " + sourceName(src), color = TU.muted) },
                                     onClick = { quality = false }, enabled = false)
                }
            }
        }
        MoreMenu(session, nav)
        IconButton(onClick = { nav.pop() }) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text)
        }
    }
}

/** Menu ⋮ : partager, créer un clip (direct), minuterie, téléchargement
 *  (VOD), multi-live (direct). */
@Composable
private fun MoreMenu(session: PlaybackSession, nav: Navigator) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }   // 0 : menu, 1 : minuterie, 2 : qualité du téléchargement, 3 : chapitres, 4 : vitesse
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    // Téléchargement refusé faute de place : taille estimée et espace libre.
    var noSpace by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let { startDownload(context, session, it) }
        pendingDownload = null
    }
    Box {
        IconButton(onClick = { page = 0; open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = TU.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when (page) {
                1 -> {
                    for (m in sleepChoices) {
                        DropdownMenuItem(text = { Text(sleepLabel(m)) }, onClick = { open = false; session.setSleepTimer(m) })
                    }
                    if (session.sleepAt != null) {
                        DropdownMenuItem(text = { Text(AppState.t("sleep_cancel"), color = TU.danger) },
                                         onClick = { open = false; session.setSleepTimer(null) })
                    }
                }
                4 -> SpeedMenuPage(session) { open = false }
                3 -> {
                    for (c in session.chapters) {
                        DropdownMenuItem(
                            text = { Text(Format.clock(c.start.toLong()) + "  " + c.title.ifEmpty { "—" }) },
                            onClick = { open = false; session.seekTo((c.start * 1000).toLong()) },
                        )
                    }
                }
                2 -> {
                    // Durée et espace libre, puis chaque qualité et sa taille
                    // estimée (débit × durée) ; refusée s'il manque de la place.
                    val seconds = downloadSeconds(session)
                    val free = Downloads.freeBytes()
                    val info = listOfNotNull(
                        if (seconds > 0) Format.duration(seconds) else null,
                        if (free >= 0) AppState.t("dl_free_space").replace("{f}", sizeText(free)) else null,
                    ).joinToString(" · ")
                    if (info.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(info, color = TU.muted, fontSize = 13.sp) }, onClick = {}, enabled = false)
                    }
                    if (Features.dlWifiOnly.value && NetworkStatus.isCellular) {
                        DropdownMenuItem(text = { Text(AppState.t("dl_wifi_needed"), color = TU.muted, fontSize = 13.sp) },
                                         onClick = {}, enabled = false)
                    }
                    for (q in session.qualities.filter { !Quality.isAuto(it) }) {
                        val size = Downloads.estimate(session.bandwidth(q), seconds)
                        DropdownMenuItem(text = { Text(Quality.label(q, AppState.lang) + if (size > 0) "  ≈ " + sizeText(size) else "") }, onClick = {
                            open = false
                            if (size > 0 && free >= 0 && size > free) {
                                Logger.warn("DOWNLOAD", "Pas assez d'espace", "${sizeText(size)} > ${sizeText(free)}")
                                noSpace = Pair(size, free)
                            } else if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                pendingDownload = q
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startDownload(context, session, q)
                            }
                        })
                    }
                }
                else -> {
                    // Partager : la VOD à la position en cours, la chaîne, le clip.
                    if (shareUrl(session) != null) {
                        val at = if (session.request is PlayRequest.Vod) "  " + Format.clock(shareSeconds(session)) else ""
                        DropdownMenuItem(text = { Text(AppState.t("share") + at) }, onClick = {
                            open = false
                            shareUrl(session)?.let { shareViaSheet(context, it) }
                        })
                    }
                    // Direct (pas son rembobinage) : un clip de l'image affichée.
                    val live = session.request as? PlayRequest.Live
                    if (live != null && session.state is PlaybackSession.State.Ready &&
                        !com.mxfia19.twitchunblock.core.NoAccount.active) {
                        DropdownMenuItem(text = { Text(AppState.t("clip_create")) }, onClick = {
                            open = false
                            ClipCreator.start(live.login, System.currentTimeMillis() - liveLatency(session))
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(AppState.t("sleep_timer") + (session.sleepAt?.let { "  ✓" } ?: "")) },
                        onClick = { page = 1 },
                    )
                    // Vitesse, aller à une heure, compresseur audio (PlayerAddons.kt).
                    PlayerMenuExtras(session, onSpeed = { page = 4 }) { open = false }
                    val vod = session.vodId
                    if (vod != null && !session.isOffline && session.state is PlaybackSession.State.Ready) {
                        val saved = Downloads.find(vod)
                        // Enregistrement d'un direct encore en cours (playlist sans
                        // fin) : la copie serait partielle, lue hors ligne comme un direct figé.
                        val p = session.player
                        if (saved == null && p.isCurrentMediaItemDynamic && p.duration != C.TIME_UNSET) {
                            DropdownMenuItem(text = { Text(AppState.t("fl_dl_after_live"), color = TU.muted, fontSize = 13.sp) },
                                             onClick = {}, enabled = false)
                        } else DropdownMenuItem(
                            text = { Text(if (saved != null) AppState.t("dl_section") + "  ✓" else AppState.t("dl_download")) },
                            onClick = { if (saved != null) { open = false; nav.tab(Screen.Library) } else page = 2 },
                        )
                    }
                    if (session.chapters.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(AppState.t("chapters") + " (${session.chapters.size})") }, onClick = { page = 3 })
                    }
                    val dvr = session.dvrVideoId
                    if (session.isLive && dvr != null) {
                        DropdownMenuItem(text = { Text(AppState.t("rewind")) }, onClick = {
                            open = false
                            session.open(PlayRequest.Vod(dvr, session.title, session.channelLogin, rewind = true))
                        })
                    }
                    val rewound = session.request as? PlayRequest.Vod
                    val back = rewound?.channel
                    if (rewound != null && rewound.rewind && back != null) {
                        DropdownMenuItem(text = { Text(AppState.t("rewind_back")) }, onClick = {
                            open = false
                            session.open(PlayRequest.Live(back))
                        })
                    }
                    if (session.isLive) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(AppState.t("multi_add")) },
                                         onClick = { open = false; nav.multi(session.channelLogin) })
                    }
                    // Chromecast (CastUi.kt) : choisir un appareil, ou arrêter la diffusion.
                    if (session.isLive && session.state is PlaybackSession.State.Ready) {
                        val casting = com.mxfia19.twitchunblock.cast.CastManager.device != null
                        DropdownMenuItem(text = { Text(AppState.t(if (casting) "st_cast_stop" else "st_cast")) }, onClick = {
                            open = false
                            if (casting) com.mxfia19.twitchunblock.cast.CastManager.stop()
                            else com.mxfia19.twitchunblock.cast.CastManager.openPicker(context)
                        })
                    }
                    // Signaler un bug ou une idée depuis le lecteur, comme l'iPhone ; il reste dessous.
                    DropdownMenuItem(text = { Text(AppState.t("feedback")) }, onClick = { open = false; nav.push(Screen.Feedback) })
                }
            }
        }
    }
    noSpace?.let { (need, free) ->
        AlertDialog(
            onDismissRequest = { noSpace = null },
            title = { Text(AppState.t("dl_download")) },
            text = { Text(AppState.t("dl_no_space_vod").replace("{s}", sizeText(need)).replace("{f}", sizeText(free))) },
            confirmButton = { TextButton(onClick = { noSpace = null }) { Text(AppState.t("close"), color = TU.primary) } },
            containerColor = TU.surface,
            titleContentColor = TU.text,
            textContentColor = TU.muted,
        )
    }
}

/** Durée de la VOD lue (s) : celle de Twitch, sinon celle du lecteur ; 0 : inconnue. */
private fun downloadSeconds(session: PlaybackSession): Int {
    if (session.lengthSeconds > 0) return session.lengthSeconds
    val d = session.player.duration
    return if (d > 0) (d / 1000).toInt() else 0
}

/** Retard de l'image affichée sur le direct (ms) : mesuré par le lecteur,
 *  sinon 5 s, celui d'un direct ordinaire. */
private fun liveLatency(session: PlaybackSession): Long {
    val offset = session.player.currentLiveOffset
    return if (offset != C.TIME_UNSET && offset > 0) offset else 5_000L
}

private fun startDownload(context: android.content.Context, session: PlaybackSession, quality: String) {
    val id = session.vodId ?: return
    val url = session.links[quality] ?: return
    // Le login (relu hors ligne comme celui de la chaîne), pas le nom affiché.
    Downloads.start(context, id, session.title, session.channelLogin ?: "", session.thumbnail,
                    downloadSeconds(session), quality, url)
}
