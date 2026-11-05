package com.mxfia19.twitchunblock.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowseApi
import com.mxfia19.twitchunblock.core.BrowseRules
import com.mxfia19.twitchunblock.core.FollowedCategories
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Hidden
import com.mxfia19.twitchunblock.core.LiveApi
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NetworkStatus
import com.mxfia19.twitchunblock.core.Quality
import com.mxfia19.twitchunblock.core.TopLanguage
import com.mxfia19.twitchunblock.core.TwitchStream
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.player.PlayRequest
import com.mxfia19.twitchunblock.player.PlayerFactory
import com.mxfia19.twitchunblock.web.TwitchFollow
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Clip du fil de Découvrir (clips des dernières 24 h des chaînes du top). */
class FeedClip(val slug: String, val title: String, val thumbnail: String, val viewCount: Int, val durationSeconds: Int,
               val game: String, val login: String, val channelId: String, val channelName: String, val avatar: String?)

/**
 * Fil de Découvrir, un seul pour l'app : l'onglet est recréé à chaque
 * passage, le fil reprend où on l'a laissé (mode, page). Le top est relu
 * après 10 min ou quand sa langue change. Fil principal.
 */
object DiscoverFeed {
    /** « live » ou « clips ». */
    var mode by mutableStateOf("live")
    val lives = mutableStateListOf<TwitchStream>()
    var livesLoading by mutableStateOf(false)
        private set
    var livesFailed by mutableStateOf(false)
        private set
    private var next: String? = null
    private var loadingMore = false
    private var loadedLang: String? = null
    private var loadedAt = 0L
    val clips = mutableStateListOf<FeedClip>()
    var clipsLoading by mutableStateOf(false)
        private set
    var clipsFailed by mutableStateOf(false)
        private set
    private var clipsFor: String? = null
    /** Page affichée dans chaque mode. */
    var livePage = 0
    var clipPage = 0
    /** Son des aperçus : coupé au départ ; rallumé, il le reste d'une page à
     *  l'autre jusqu'à la sortie de l'onglet. */
    var sound by mutableStateOf(false)
    /** Photo des chaînes, connue une fois leur direct résolu. */
    val avatars = mutableStateMapOf<String, String>()
    /** Adresses déjà trouvées (5 min) : revenir sur une page ne relance pas
     *  toutes les sources du direct. */
    private val urls = HashMap<String, Pair<String, Long>>()

    suspend fun loadLives(force: Boolean = false) {
        val lang = TopLanguage.resolved()
        val now = System.currentTimeMillis()
        if (!force && loadedLang == lang && lives.isNotEmpty() && now - loadedAt < 600_000) return
        if (livesLoading) return
        livesLoading = true
        livesFailed = false
        val page = BrowseApi.topStreams(lang, null)
        if (page != null && !page.refused) {
            lives.clear()
            lives.addAll(page.items.distinctBy { it.login })
            next = page.next
            loadedLang = lang
            loadedAt = now
            livePage = 0
        } else if (lives.isEmpty()) {
            livesFailed = true
        }
        livesLoading = false
    }

    /** Près de la fin : la page suivante du top. */
    suspend fun loadMoreLives() {
        val cursor = next ?: return
        if (loadingMore) return
        loadingMore = true
        val page = BrowseApi.topStreams(TopLanguage.resolved(), cursor)
        if (page != null) {
            val seen = lives.map { it.login }.toHashSet()
            lives.addAll(page.items.filter { it.login !in seen }.distinctBy { it.login })
            next = if (page.refused) null else page.next
        }
        loadingMore = false
    }

    /** Clips les plus vus des dernières 24 h des 20 premières chaînes du top. */
    suspend fun loadClips(force: Boolean = false) {
        val logins = lives.filter { BrowseRules.shows(it) }.take(20).map { it.login.lowercase() }
        val key = logins.joinToString(",")
        if (!force && clipsFor == key && clips.isNotEmpty()) return
        if (clipsLoading) return
        if (logins.isEmpty()) {
            clipsFailed = livesFailed
            return
        }
        clipsLoading = true
        clipsFailed = false
        val list = topClips(logins)
        if (list != null) {
            clips.clear()
            clips.addAll(list.distinctBy { it.slug })
            clipsFor = key
            clipPage = 0
        } else {
            clipsFailed = clips.isEmpty()
        }
        clipsLoading = false
    }

    /** 10 chaînes par requête (Twitch refuse plus de 15 alias). null : aucune réponse. */
    private suspend fun topClips(logins: List<String>): List<FeedClip>? = coroutineScope {
        val parts = logins.chunked(10).map { chunk -> async { clipsChunk(chunk) } }.awaitAll()
        val answered = parts.filterNotNull()
        if (answered.isEmpty()) null else answered.flatten().sortedByDescending { it.viewCount }
    }

    private suspend fun clipsChunk(list: List<String>): List<FeedClip>? {
        val fields = "id login displayName profileImageURL(width: 70) clips(first: 2, criteria: { period: LAST_DAY, " +
            "sort: VIEWS_DESC }) { edges { node { slug title viewCount durationSeconds thumbnailURL(width: 480, height: 272) " +
            "game { displayName } } } }"
        val q = "query { " + list.mapIndexed { i, l -> "c$i: user(login: \"${Gql.str(l)}\") { $fields }" }.joinToString(" ") + " }"
        val d = Gql.request(q) ?: return null
        val out = mutableListOf<FeedClip>()
        for (i in list.indices) {
            val u = d.obj("c$i") ?: continue
            val login = u.str("login") ?: continue
            for (e in u.obj("clips")?.arr("edges")?.objects().orEmpty()) {
                val n = e.obj("node") ?: continue
                val slug = n.str("slug") ?: continue
                out += FeedClip(slug, n.str("title") ?: "", n.str("thumbnailURL") ?: "", n.int("viewCount") ?: 0,
                                n.int("durationSeconds") ?: 0, n.obj("game")?.str("displayName") ?: "", login.lowercase(),
                                u.str("id") ?: "", u.str("displayName") ?: login, u.str("profileImageURL"))
            }
        }
        return out
    }

    private fun cached(key: String): String? =
        urls[key]?.takeIf { System.currentTimeMillis() - it.second < 300_000 }?.first

    /** Aperçu d'un direct : ses sources habituelles, en 360p (160p en données mobiles). */
    suspend fun liveUrl(login: String): String? {
        val key = "live:$login"
        cached(key)?.let { return it }
        val r = LiveApi.getLive(login)
        r.info.avatar?.let { avatars[login] = it }
        val links = r.master.links
        if (links.isEmpty()) return null
        val q = Quality.preferred(links.keys, if (NetworkStatus.isCellular) "160" else "360", r.master.variants)
        val url = links[q] ?: return null
        urls[key] = Pair(url, System.currentTimeMillis())
        return url
    }

    /** Aperçu d'un clip, en 360p. */
    suspend fun clipUrl(slug: String): String? {
        val key = "clip:$slug"
        cached(key)?.let { return it }
        val links = BrowseApi.clip(slug)?.links ?: return null
        val url = links[Quality.preferred(links.keys, "360")] ?: return null
        urls[key] = Pair(url, System.currentTimeMillis())
        return url
    }
}

/**
 * Onglet Découvrir : un direct (ou un clip) par écran, à faire défiler vers
 * le haut, comme le fil de l'app Twitch. La page affichée joue un aperçu
 * muet en petite qualité ; un toucher ouvre le lecteur complet (son, chat).
 * Un seul lecteur pour tout le fil, rendu dès que l'onglet n'est plus
 * affiché (lecteur ouvert, autre onglet) : l'aperçu s'arrête, et reprend au
 * retour.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DiscoverScreen(nav: Navigator) {
    val context = LocalContext.current
    val feed = DiscoverFeed
    val player = remember { PlayerFactory.build(context, audioFocus = false) }
    var firstFrame by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.warn("DISCOVER", "Aperçu interrompu", error.errorCodeName)
                previewFailed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            // Son recoupé en quittant l'onglet.
            feed.sound = false
        }
    }
    // App en arrière-plan : l'aperçu s'arrête.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { resumed = false; player.pause() }
                Lifecycle.Event.ON_START -> { resumed = true; if (player.mediaItemCount > 0) player.play() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Son rallumé : le focus audio passe aux aperçus ; coupé, la musique d'une autre app continue.
    LaunchedEffect(feed.sound) {
        player.volume = if (feed.sound) 1f else 0f
        PlayerFactory.setAudioFocus(player, feed.sound)
    }
    val lang = TopLanguage.resolved()
    LaunchedEffect(lang) {
        feed.loadLives()
        if (feed.mode == "clips") feed.loadClips()
    }
    LaunchedEffect(feed.mode, feed.lives.size) { if (feed.mode == "clips") feed.loadClips() }
    val live = feed.mode == "live"
    // Masquées écartées dès qu'elles le sont (menu ⋯ d'une page, accueil…).
    val lives = feed.lives.filter { BrowseRules.shows(it) }
    val clips = feed.clips.filter { BrowseRules.follows(it.login) || !Hidden.hidesChannel(it.login) }
    val count = if (live) lives.size else clips.size
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(AppState.t("discover_live"), live) { feed.mode = "live" }
            TabChip(AppState.t("discover_clips"), !live) { feed.mode = "clips" }
        }
        val loading = if (live) feed.livesLoading else feed.clipsLoading || feed.livesLoading
        val failed = if (live) feed.livesFailed else feed.clipsFailed
        val retry: () -> Unit = {
            scope.launch {
                feed.loadLives(force = true)
                if (!live) feed.loadClips(force = true)
            }
        }
        when {
            count == 0 && loading -> LoadingBox()
            count == 0 && failed -> MessageBox(AppState.t("err_network"), action = AppState.t("retry"), onAction = retry)
            count == 0 -> MessageBox(AppState.t("discover_empty"), action = AppState.t("retry"), onAction = retry)
            else -> key(feed.mode) {
                FeedPager(count, live, lives, clips, player, firstFrame, previewFailed, resumed, nav,
                          onStart = { firstFrame = false; previewFailed = false }, onFail = { previewFailed = true })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FeedPager(count: Int, live: Boolean, lives: List<TwitchStream>, clips: List<FeedClip>, player: ExoPlayer,
                      firstFrame: Boolean, previewFailed: Boolean, resumed: Boolean, nav: Navigator,
                      onStart: () -> Unit, onFail: () -> Unit) {
    val feed = DiscoverFeed
    val pager = rememberPagerState(initialPage = (if (live) feed.livePage else feed.clipPage).coerceIn(0, count - 1)) { count }
    // Onglet touché à nouveau : retour à la première page.
    TabTop.Listen(Screen.Discover) { pager.animateScrollToPage(0) }
    val settled = pager.settledPage.coerceIn(0, count - 1)
    LaunchedEffect(settled, count) {
        if (live) {
            feed.livePage = settled
            if (settled >= count - 4) feed.loadMoreLives()
        } else {
            feed.clipPage = settled
        }
    }
    val currentKey = if (live) "live:" + lives[settled].login.lowercase() else "clip:" + clips[settled].slug
    // Aperçu de la page affichée seulement.
    LaunchedEffect(currentKey) {
        onStart()
        player.stop()
        player.clearMediaItems()
        val url = if (live) feed.liveUrl(lives[settled].login.lowercase()) else feed.clipUrl(clips[settled].slug)
        if (url == null) {
            onFail()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.Builder().setUri(url)
                                .setMimeType(if (live) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4).build())
        // Un clip tourne en boucle.
        player.repeatMode = if (live) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        player.prepare()
        player.playWhenReady = resumed
    }
    VerticalPager(
        state = pager,
        key = { i -> if (live) "live:" + lives.getOrNull(i)?.login else "clip:" + clips.getOrNull(i)?.slug },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val current = page == settled
        if (live) {
            val s = lives.getOrNull(page) ?: return@VerticalPager
            val login = s.login.lowercase()
            FeedPage(s.thumb(640, 360), current, player, firstFrame, previewFailed,
                     onOpen = { nav.play(PlayRequest.Live(login)) },
                     info = {
                         Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                             Row(verticalAlignment = Alignment.CenterVertically) {
                                 LiveBadge()
                                 Spacer(Modifier.width(8.dp))
                                 Text("● " + Format.viewers(s.viewers), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                                      fontWeight = FontWeight.SemiBold)
                             }
                             ChannelLine(s.name, login, s.id, nav)
                             if (s.title.isNotEmpty()) {
                                 Text(s.title, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 2,
                                      overflow = TextOverflow.Ellipsis)
                             }
                             if (s.tags.isNotEmpty()) {
                                 Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                     for (t in s.tags.take(3)) {
                                         Text(t, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                              modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f))
                                                  .padding(horizontal = 8.dp, vertical = 3.dp))
                                     }
                                 }
                             }
                             if (s.game.isNotEmpty()) {
                                 Text("🎮 " + s.game, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
                             }
                         }
                     },
                     side = {
                         SideBar(login, s.name, feed.avatars[login], s.viewers, s.gameId, s.game, "https://www.twitch.tv/$login", nav)
                     })
        } else {
            val c = clips.getOrNull(page) ?: return@VerticalPager
            FeedPage(c.thumbnail, current, player, firstFrame, previewFailed,
                     onOpen = { nav.play(PlayRequest.Clip(c.slug)) },
                     info = {
                         Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                             // « 0:27 » : un clip dure moins d'une minute.
                             val d = maxOf(0, c.durationSeconds)
                             Text("▶ " + Format.viewers(c.viewCount) + " · " + (d / 60) + ":" + "%02d".format(d % 60),
                                  color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                             ChannelLine(c.channelName, c.login, c.channelId, nav)
                             if (c.title.isNotEmpty()) {
                                 Text(c.title, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 2,
                                      overflow = TextOverflow.Ellipsis)
                             }
                             if (c.game.isNotEmpty()) {
                                 Text("🎮 " + c.game, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
                             }
                         }
                     },
                     side = { SideBar(c.login, c.channelName, c.avatar, null, "", "", "https://clips.twitch.tv/${c.slug}", nav) })
        }
    }
}

/** Une page : fond flouté, image 16:9 (miniature, l'aperçu par-dessus sur la
 *  page affichée), puis la fiche et la colonne de droite. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FeedPage(thumb: String, current: Boolean, player: ExoPlayer, firstFrame: Boolean, failed: Boolean,
                     onOpen: () -> Unit, info: @Composable () -> Unit, side: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                   modifier = Modifier.fillMaxSize().blur(24.dp).alpha(0.35f))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(onClick = onOpen)) {
                if (current) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                isFocusable = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                keepScreenOn = true
                                this.player = player
                            }
                        },
                        update = { v -> if (v.player !== player) v.player = player },
                        // Page quittée : la vue lâche le lecteur (repris par la suivante).
                        onRelease = { v -> v.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Miniature tant que l'aperçu n'a pas d'image.
                if (!current || !firstFrame) {
                    AsyncImage(model = thumb, contentDescription = AppState.t("discover_tap"), contentScale = ContentScale.Crop,
                               modifier = Modifier.fillMaxSize())
                }
                if (current && failed) {
                    Text(AppState.t("discover_no_preview"), color = Color.White, fontSize = 13.sp,
                         modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp))
                             .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                if (current) {
                    // Son de l'aperçu (coupé au départ), d'un toucher.
                    val on = DiscoverFeed.sound
                    IconButton(onClick = { DiscoverFeed.sound = !on },
                               modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp)
                                   .clip(CircleShape).background(Color(0x80000000))) {
                        Icon(if (on) BrowseIcons.volumeUp else BrowseIcons.volumeOff,
                             contentDescription = AppState.t(if (on) "discover_mute" else "discover_unmute"), tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { info() }
                Spacer(Modifier.width(12.dp))
                side()
            }
        }
    }
}

/** Nom de la chaîne (vers sa page) et bouton Suivre. */
@Composable
private fun ChannelLine(name: String, login: String, channelId: String, nav: Navigator) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1,
             overflow = TextOverflow.Ellipsis,
             modifier = Modifier.weight(1f, fill = false).clickable { nav.push(Screen.Channel(login)) })
        Spacer(Modifier.width(10.dp))
        FollowButton(login, channelId, name)
    }
}

/** Suivre : sur Twitch avec la session web, sinon sur l'appareil (comme la
 *  page d'une chaîne). Ne plus suivre se confirme. */
@Composable
private fun FollowButton(login: String, channelId: String, name: String) {
    val scope = rememberCoroutineScope()
    var twitch by remember(login) { mutableStateOf<Boolean?>(null) }
    var id by remember(login) { mutableStateOf(channelId) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(login, WebSession.token) {
        twitch = null
        if (WebSession.active) {
            TwitchFollow.status(login)?.let { (following, uid) ->
                twitch = following
                id = uid
            }
        }
    }
    val usesTwitch = twitch != null
    val followed = if (usesTwitch) twitch == true else AppState.isFollowedLocally(login)
    val send: (Boolean) -> Unit = { want ->
        if (!busy && id.isNotEmpty()) {
            busy = true
            scope.launch {
                if (TwitchFollow.set(id, want) == null) twitch = want
                busy = false
            }
        }
    }
    Text(AppState.t(if (followed) "following" else "follow"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
         maxLines = 1,
         modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (followed) Color.White.copy(alpha = 0.16f) else TU.primary)
             .clickable(enabled = !busy) {
                 when {
                     followed -> confirm = true
                     usesTwitch -> send(true)
                     else -> AppState.toggleLocalFollow(login)
                 }
             }
             .padding(horizontal = 12.dp, vertical = 5.dp))
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(AppState.t("unfollow_confirm").replace("{c}", name.ifEmpty { login })) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    if (usesTwitch) send(false) else if (AppState.isFollowedLocally(login)) AppState.toggleLocalFollow(login)
                }) { Text(AppState.t("unfollow"), color = TU.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(AppState.t("cancel"), color = TU.text) } },
            containerColor = TU.surface, titleContentColor = TU.text,
        )
    }
}

/** Colonne de droite : photo de la chaîne (vers sa page), spectateurs,
 *  partager, masquer la chaîne ou sa catégorie (jamais ce qu'on suit). */
@Composable
private fun SideBar(login: String, name: String, avatar: String?, viewers: Int?, gameId: String, game: String, link: String,
                    nav: Navigator) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(avatar, 48.dp, Modifier.clickable { nav.push(Screen.Channel(login)) })
        if (viewers != null) {
            Text("● " + Format.viewers(viewers), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            try {
                context.startActivity(Intent.createChooser(send, AppState.t("share")))
            } catch (e: Exception) {
                Logger.warn("DISCOVER", "Partage impossible", e.message)
            }
        }) {
            Icon(Icons.Filled.Share, contentDescription = AppState.t("share"), tint = Color.White)
        }
        val hideChannel = !BrowseRules.follows(login)
        val hideGame = gameId.isNotEmpty() && game.isNotEmpty() && !FollowedCategories.isFollowed(gameId)
        if (hideChannel || hideGame) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (hideChannel) {
                        DropdownMenuItem(text = { Text(AppState.t("hide_channel")) }, onClick = {
                            menu = false
                            Hidden.hideChannel(login, name)
                        })
                    }
                    if (hideGame) {
                        DropdownMenuItem(text = { Text(AppState.t("hide_category").replace("{c}", game)) }, onClick = {
                            menu = false
                            Hidden.hideCategory(gameId, game, "")
                        })
                    }
                }
            }
        }
    }
}
