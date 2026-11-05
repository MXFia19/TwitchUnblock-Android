package com.mxfia19.twitchunblock.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.player.MutedRange
import com.mxfia19.twitchunblock.player.PlaybackSession
import com.mxfia19.twitchunblock.player.PlayerSpeed
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Image du lecteur principal (téléphone) avec les gestes de l'app iPhone :
 * double-tap à gauche / à droite pour −10 s / +10 s (les touchers suivants
 * enchaînent : +20 s, +30 s…), zoom à deux doigts (jusqu'à 4×, depuis le
 * point pincé ; agrandie, un doigt la déplace), remplir l'écran en paysage,
 * passages coupés d'une VOD en orange sur la barre. Les touchers sur les
 * boutons et la barre restent aux commandes de Media3.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
class GesturePlayerView(context: Context) : PlayerView(context) {
    private var session: PlaybackSession? = null
    private var gestures = false

    // ── Zoom à deux doigts ──
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var pinching = false

    // ── Geste en cours ──
    /** Geste retiré aux commandes (saut, zoom, glissé de l'image agrandie). */
    private var swallow = false
    private var seekGesture = false
    private var panning = false
    private var downOnControl = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L

    // ── Double-tap ──
    /** Fin du dernier toucher simple (0 : aucun), et son côté (−1 gauche, 1 droite). */
    private var tapAt = 0L
    private var tapSide = 0
    /** Série de sauts en cours : chaque toucher du même côté ajoute 10 s. */
    private var streak = false
    /** Commandes affichées avant le premier toucher d'une série : le double-tap
     *  les remet comme avant (le premier toucher les avait basculées). */
    private var controlsBefore = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    /** Lecteur de la session, donné à la vue sans la vitesse (NoSpeedPlayer). */
    private var source: Player? = null

    /** Série 6 : glissé vers le bas en portrait, le lecteur se réduit
     *  (MiniPlayer.kt) ; null : pas de geste. */
    var onPullDown: (() -> Unit)? = null
    private var pulling = false

    fun usePlayer(p: Player?) {
        if (p === source) return
        source = p
        player = p?.let { NoSpeedPlayer(it) }
    }

    private val timeBar: View? = findViewById(androidx.media3.ui.R.id.exo_progress)
    private val mutedMarks: MutedMarks? = (timeBar as? DefaultTimeBar)?.let { MutedMarks(it) { player } }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!gestures) return false
            pinching = true
            focusX = detector.focusX
            focusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val s = videoSurfaceView ?: return true
            val z0 = zoom
            val z1 = (z0 * detector.scaleFactor).coerceIn(1f, 4f)
            // Milieu de l'image (sans zoom) dans ce cadre : le point de l'image
            // pris sous les doigts y reste, et les doigts qui bougent la déplacent.
            val frame = s.parent as? View
            val cx = (frame?.left ?: 0) + s.left + s.width / 2f
            val cy = (frame?.top ?: 0) + s.top + s.height / 2f
            panX = detector.focusX - cx - (focusX - cx - panX) * (z1 / z0)
            panY = detector.focusY - cy - (focusY - cy - panY) * (z1 / z0)
            zoom = z1
            focusX = detector.focusX
            focusY = detector.focusY
            applyZoom()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinching = false
            if (zoom < 1.05f) resetZoom(animate = true)
            else PlayerUi.flash(String.format(Locale.US, "%.1f×", zoom))
        }
    })

    init {
        // Pas de « double-tap puis glisser » pour zoomer : le double-tap saute de 10 s.
        scaleDetector.isQuickScaleEnabled = false
        PlayerUi.controlsVisible = false
        PlayerUi.scrubMs = null
        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            PlayerUi.controlsVisible = v == View.VISIBLE
        })
        (timeBar as? TimeBar)?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { PlayerUi.scrubMs = position }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { PlayerUi.scrubMs = position }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { PlayerUi.scrubMs = null }
        })
        val bar = timeBar
        val marks = mutedMarks
        if (bar != null && marks != null) {
            bar.overlay.add(marks)
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> marks.setBounds(0, 0, v.width, v.height) }
        }
    }

    /** Relu à chaque recomposition (VideoSurface) : gestes (pas en image dans
     *  l'image), remplir l'écran, passages coupés de la VOD. */
    fun bind(session: PlaybackSession, gestures: Boolean, fill: Boolean, muted: List<MutedRange>) {
        this.session = session
        if (this.gestures != gestures) {
            this.gestures = gestures
            if (!gestures) resetZoom(animate = false)
        }
        val mode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        if (resizeMode != mode) resizeMode = mode
        val marks = mutedMarks
        if (marks != null && marks.ranges != muted) {
            marks.ranges = muted
            timeBar?.invalidate()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gestures) return super.dispatchTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallow = false
                seekGesture = false
                panning = false
                pulling = false
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                downTime = ev.eventTime
                downOnControl = onControl(ev.x, ev.y)
                val side = if (ev.x < width / 2f) -1 else 1
                // Les touchers d'une série s'enchaînent un peu plus lentement
                // qu'un double-tap.
                val window = if (streak) 700L else doubleTapMs
                val quick = !downOnControl && tapAt > 0 && side == tapSide && ev.eventTime - tapAt < window
                if (quick && seekTap(side)) {
                    if (!streak) {
                        streak = true
                        if (controlsBefore) showController() else hideController()
                    }
                    seekGesture = true
                    swallow = true
                    return true
                }
                if (!quick) {
                    streak = false
                    controlsBefore = isControllerFullyVisible
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Deux doigts : un zoom ; les commandes ne voient plus ce geste.
                if (!swallow) cancelChildren(ev)
                swallow = true
                tapAt = 0L
                streak = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Série 6 : glissé franc vers le bas (portrait, image entière) : réduire.
                val dy = ev.y - downY
                if (!swallow && onPullDown != null && zoom <= 1f && !downOnControl && ev.pointerCount == 1 &&
                    resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                    dy > touchSlop * 2 && dy > abs(ev.x - downX) * 1.5f) {
                    cancelChildren(ev)
                    swallow = true
                    pulling = true
                }
                if (!swallow && zoom > 1f && !downOnControl && ev.pointerCount == 1 &&
                    (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                    // Image agrandie : un doigt la déplace.
                    cancelChildren(ev)
                    swallow = true
                    panning = true
                }
                if (panning && !pinching && ev.pointerCount == 1) {
                    panX += ev.x - lastX
                    panY += ev.y - lastY
                    applyZoom()
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) {
                    pulling = false
                    swallow = false
                    tapAt = 0L
                    if (ev.y - downY > maxOf(height * 0.22f, 56f * resources.displayMetrics.density)) onPullDown?.invoke()
                    return true
                }
                if (seekGesture) {
                    tapAt = ev.eventTime
                    tapSide = if (downX < width / 2f) -1 else 1
                    swallow = false
                    seekGesture = false
                    return true
                }
                if (swallow) {
                    swallow = false
                    panning = false
                    tapAt = 0L
                    return true
                }
                val moved = abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop
                if (!downOnControl && !moved && ev.eventTime - downTime < 400) {
                    tapAt = ev.eventTime
                    tapSide = if (ev.x < width / 2f) -1 else 1
                } else {
                    tapAt = 0L
                    streak = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pulling = false
                val was = swallow
                swallow = false
                seekGesture = false
                panning = false
                tapAt = 0L
                if (was) return true
            }
        }
        if (swallow) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Geste déjà commencé chez les commandes : annulé pour elles. */
    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** Toucher sur un bouton ou la barre des commandes affichées. */
    private fun onControl(x: Float, y: Float): Boolean = isControllerFullyVisible && hitsControl(this, x, y)

    private fun hitsControl(group: ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val v = group.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            if (x < v.left || x >= v.right || y < v.top || y >= v.bottom) continue
            // Boutons et barre seulement : un conteneur, même cliquable, n'en est pas un.
            if (v is TimeBar || (v.isClickable && v !is ViewGroup)) return true
            if (v is ViewGroup && hitsControl(v, x - v.left, y - v.top)) return true
        }
        return false
    }

    /** Saut de 10 s ; faux s'il n'y a nulle part où aller (début, bord du
     *  direct). Direct : en arrière dans la fenêtre, en avant jusqu'au bord. */
    private fun seekTap(side: Int): Boolean {
        val p = player ?: return false
        if (session?.state !is PlaybackSession.State.Ready) return false
        val pos = p.currentPosition
        if (p.isCurrentMediaItemLive) {
            val edge = PlayerSpeed.liveEdgeMs(p) ?: return false
            if (side < 0) {
                if (!p.isCurrentMediaItemSeekable) return false
                val target = max(0L, pos - 10_000)
                if (pos - target < 1_000) return false
                p.seekTo(target)
            } else {
                if (pos >= edge - 1_000) return false
                val target = pos + 10_000
                // Au bord : le retard normal du direct, plutôt qu'un recul tenu.
                if (target >= edge - 1_500) p.seekToDefaultPosition() else p.seekTo(target)
            }
        } else {
            val d = p.duration
            if (d == C.TIME_UNSET || d <= 0) return false
            val target = (pos + side * 10_000L).coerceIn(0L, max(0L, d - 500))
            if (abs(target - pos) < 500) return false
            p.seekTo(target)
        }
        PlayerUi.seekFlash(side * 10)
        return true
    }

    private fun applyZoom() {
        val s = videoSurfaceView ?: return
        // L'image agrandie couvre toujours son cadre : pas de vide sur les bords.
        val maxX = max(0f, s.width * (zoom - 1f) / 2f)
        val maxY = max(0f, s.height * (zoom - 1f) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        s.animate().cancel()
        s.scaleX = zoom
        s.scaleY = zoom
        s.translationX = panX
        s.translationY = panY
    }

    private fun resetZoom(animate: Boolean) {
        zoom = 1f
        panX = 0f
        panY = 0f
        val s = videoSurfaceView ?: return
        if (animate) {
            s.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(200).start()
        } else {
            s.animate().cancel()
            s.scaleX = 1f
            s.scaleY = 1f
            s.translationX = 0f
            s.translationY = 0f
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Autre format d'image (cadre changé) : décalage borné de nouveau.
        if (zoom > 1f) applyZoom()
    }
}

/**
 * Le lecteur sans la commande de vitesse : la roue dentée de Media3 ne la
 * propose plus. Elle la changeait sans PlayerSpeed : direct en 2× qui butait
 * sur le bord, vitesse des VODs non retenue, ✓ du menu ⋮ faux. Le menu ⋮
 * reste seul à la régler.
 */
@OptIn(UnstableApi::class)
private class NoSpeedPlayer(player: Player) : ForwardingPlayer(player) {
    override fun isCommandAvailable(command: Int): Boolean =
        command != Player.COMMAND_SET_SPEED_AND_PITCH && super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().remove(Player.COMMAND_SET_SPEED_AND_PITCH).build()
}

/**
 * Passages coupés d'une VOD, en orange sur la barre de Media3 (DefaultTimeBar).
 * Dessinés par-dessus, dans le rectangle de la piste lu dans la barre ; le
 * curseur reste visible. Sans ce rectangle (autre version de Media3), rien.
 */
@OptIn(UnstableApi::class)
private class MutedMarks(private val bar: DefaultTimeBar, private val player: () -> Player?) : Drawable() {
    var ranges: List<MutedRange> = emptyList()

    private val track: Rect? = try {
        DefaultTimeBar::class.java.getDeclaredField("progressBar").apply { isAccessible = true }.get(bar) as? Rect
    } catch (e: Exception) {
        Logger.warn("PLAYER", "Passages coupés : piste de la barre introuvable", e.javaClass.simpleName)
        null
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFA000.toInt() }
    private val density = bar.resources.displayMetrics.density

    override fun draw(canvas: Canvas) {
        val r = track ?: return
        if (ranges.isEmpty() || r.width() <= 0) return
        val p = player() ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d <= 0) return
        val w = r.width().toFloat()
        fun x(ms: Double) = r.left + w * (ms / d).toFloat().coerceIn(0f, 1f)
        // Le curseur (instant visé pendant un glissé) reste par-dessus.
        val at = x((PlayerUi.scrubMs ?: p.currentPosition).toDouble())
        val hole = 7f * density
        val minWidth = 2f * density
        val top = r.top.toFloat()
        val bottom = r.bottom.toFloat()
        for (m in ranges) {
            val a = x(m.start * 1000)
            val b = max(x(m.end * 1000), a + minWidth)
            if (b <= at - hole || a >= at + hole) {
                canvas.drawRect(a, top, b, bottom, paint)
            } else {
                if (a < at - hole) canvas.drawRect(a, top, at - hole, bottom, paint)
                if (b > at + hole) canvas.drawRect(max(a, at + hole), top, b, bottom, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
