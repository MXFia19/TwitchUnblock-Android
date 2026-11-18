package com.mxfia19.twitchunblock.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Place du chat en paysage : trois états, parcourus par le même bouton du
 *  lecteur. Comme LandscapeChat (iOS). */
enum class LandscapeChat(val key: String, val labelKey: String) {
    /** Colonne à droite : l'image rétrécit d'autant. */
    COLUMN("column", "chat_mode_column"),
    /** Posé sur l'image, fond transparent : l'image garde toute la largeur. */
    OVERLAY("overlay", "chat_mode_overlay"),
    /** Replié : l'image seule. */
    HIDDEN("hidden", "chat_mode_hidden");

    val next: LandscapeChat
        get() = when (this) {
            COLUMN -> OVERLAY
            OVERLAY -> HIDDEN
            HIDDEN -> COLUMN
        }

    companion object {
        fun of(key: String): LandscapeChat = entries.firstOrNull { it.key == key } ?: HIDDEN
    }
}

/** Largeur prise par le chat posé sur l'image, de chaque côté : les commandes
 *  du lecteur s'en écartent, rien d'elles n'est couvert. */
data class ChatOverlayInset(val start: Dp = 0.dp, val end: Dp = 0.dp)

/** Lu par VideoSurface (PlayerScreen.kt), fourni par LandscapePlayer. */
val LocalChatOverlayInset = compositionLocalOf { ChatOverlayInset() }

/** Commandes de la PlayerView écartées du chat posé sur l'image. */
@androidx.annotation.OptIn(UnstableApi::class)
fun PlayerView.insetControls(inset: ChatOverlayInset) {
    val controls = findViewById<View>(androidx.media3.ui.R.id.exo_controller) ?: return
    val density = resources.displayMetrics.density
    val left = (inset.start.value * density).roundToInt()
    val right = (inset.end.value * density).roundToInt()
    if (controls.paddingLeft != left || controls.paddingRight != right) {
        controls.setPadding(left, controls.paddingTop, right, controls.paddingBottom)
    }
}

/**
 * Lecteur en paysage et son chat : colonne à droite, posé sur l'image (à
 * gauche ou à droite, sur toute la hauteur ou dans un coin), ou replié. Le
 * bouton du chat passe de l'un à l'autre (choix gardé) ; la largeur se règle
 * en glissant la poignée, de 20 à 60 % de l'écran et 180 dp au moins. Posé
 * sur l'image, le chat se déplace par la poignée de son bord haut (série 7) :
 * lâché, il s'aimante à la plus proche de 6 places — les 4 coins et le milieu
 * des bords gauche et droit (toute la hauteur) —, gardée dans les réglages.
 * `video` : l'image et ce qui se pose dessus ; `chat` : le chat, avec sa
 * taille et s'il est posé sur l'image (fond transparent).
 */
@Composable
fun LandscapePlayer(hasChat: Boolean, video: @Composable BoxScope.() -> Unit,
                    chat: @Composable (modifier: Modifier, overlay: Boolean) -> Unit) {
    val mode = if (hasChat) LandscapeChat.of(ChatPrefs.landscape.value) else LandscapeChat.HIDDEN
    val column = mode == LandscapeChat.COLUMN
    val overlay = mode == LandscapeChat.OVERLAY
    // Posé sur l'image : côté et hauteur des réglages. La colonne reste à
    // droite, sur toute la hauteur.
    val place = ChatPrefs.overlay.value
    val onLeft = overlay && place.startsWith("left")
    val half = if (overlay) place.substringAfter('-', "full") else "full"
    // Pendant un glissement, la largeur vient d'ici : écrite dans les
    // préférences seulement à la fin.
    var dragRatio by remember { mutableStateOf<Float?>(null) }
    // Chat posé sur l'image déplacé à la poignée : décalage pendant le geste,
    // puis ramené en douceur à sa nouvelle place.
    val scope = rememberCoroutineScope()
    val moved = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // Libellé bref de la disposition choisie (« Chat : superposé »).
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(1500)
            flash = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val total = maxWidth
        val handle = 14.dp
        val ratio = dragRatio ?: (ChatPrefs.widthPercent.value / 100f)
        // Jamais sous 180 dp : les messages se hachaient en mots isolés.
        val chatWidth = maxOf(total * ratio, 180.dp).coerceAtMost(total * 0.75f)
        val inset = when {
            !overlay -> ChatOverlayInset()
            onLeft -> ChatOverlayInset(start = chatWidth + handle)
            else -> ChatOverlayInset(end = chatWidth + handle)
        }
        // Image : toujours le premier enfant, au même endroit de l'arbre — la
        // PlayerView n'est pas recréée en changeant de disposition.
        Box(Modifier.align(Alignment.CenterStart).width(if (column) total - chatWidth - handle else total).fillMaxHeight()) {
            CompositionLocalProvider(LocalChatOverlayInset provides inset) { video() }
        }
        if (mode != LandscapeChat.HIDDEN) {
            val align = when {
                !overlay -> Alignment.CenterEnd
                half == "top" -> if (onLeft) Alignment.TopStart else Alignment.TopEnd
                half == "bottom" -> if (onLeft) Alignment.BottomStart else Alignment.BottomEnd
                else -> if (onLeft) Alignment.CenterStart else Alignment.CenterEnd
            }
            val density = LocalDensity.current
            val totalPx = with(density) { total.toPx() }
            val shown = chatWidth / total
            val save: () -> Unit = {
                dragRatio?.let {
                    ChatPrefs.widthPercent.set((it * 100).roundToInt())
                    Logger.info("CHAT", "Largeur du chat en paysage", "${ChatPrefs.widthPercent.value} %")
                }
                dragRatio = null
            }
            // Poignée du haut lâchée : la place la plus proche du milieu du chat
            // (côté gauche ou droit ; tiers haut, milieu ou bas de l'écran).
            val boxW = with(density) { (chatWidth + handle).toPx() }
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val snap: () -> Unit = {
                // Après les derniers déplacements encore en file (même fil).
                scope.launch {
                    val d = moved.value
                    val boxH = if (half == "full") screenH else screenH / 2f
                    val x0 = if (onLeft) 0f else screenW - boxW
                    val y0 = if (half == "bottom") screenH - boxH else 0f
                    val cx = x0 + boxW / 2f + d.x
                    val cy = y0 + boxH / 2f + d.y
                    val left = cx < screenW / 2f
                    val spot = when {
                        cy < screenH / 3f -> "top"
                        cy > screenH * 2f / 3f -> "bottom"
                        else -> "full"
                    }
                    val raw = (if (left) "left-" else "right-") + spot
                    val newH = if (spot == "full") screenH else screenH / 2f
                    val x1 = if (left) 0f else screenW - boxW
                    val y1 = if (spot == "bottom") screenH - newH else 0f
                    // Repart d'où le doigt l'a laissé, la place changée dans la même image.
                    moved.snapTo(Offset(x0 + d.x - x1, y0 + d.y - y1))
                    if (raw != place) {
                        ChatPrefs.overlay.set(raw)
                        Logger.info("CHAT", "Chat posé sur l'image déplacé", raw)
                    }
                    val sideLabel = if (left) AppState.t("chat_side_left") else AppState.t("chat_side_right")
                    val spotLabel = when (spot) {
                        "top" -> AppState.t("chat_height_top")
                        "bottom" -> AppState.t("chat_height_bottom")
                        else -> AppState.t("chat_height_full")
                    }
                    flash = "$sideLabel · $spotLabel"
                    moved.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            // Le chat garde sa place dans l'arbre d'une disposition à l'autre
            // (saisie et défilement conservés) ; la poignée côté image.
            Row(Modifier.align(align)
                    .offset { if (overlay) IntOffset(moved.value.x.roundToInt(), moved.value.y.roundToInt()) else IntOffset.Zero }
                    .width(chatWidth + handle).fillMaxHeight(if (half == "full") 1f else 0.5f)) {
                if (!onLeft) ResizeHandle(totalPx, chatOnLeft = false, current = { dragRatio ?: shown }, onDrag = { dragRatio = it }, onDone = save)
                // Écarté de l'encoche ou de la caméra de son côté (contenu sous
                // l'encoche en paysage) ; l'image peut rester dessous.
                val cutout = WindowInsets.displayCutout.only(if (onLeft) WindowInsetsSides.Start else WindowInsetsSides.End)
                Column(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(cutout).imePadding()) {
                    if (overlay) ChatMoveGrip(onMove = { delta -> scope.launch { moved.snapTo(moved.value + delta) } }, onDone = snap)
                    Box(Modifier.weight(1f).fillMaxWidth()) { chat(Modifier.fillMaxSize(), overlay) }
                }
                if (onLeft) ResizeHandle(totalPx, chatOnLeft = true, current = { dragRatio ?: shown }, onDrag = { dragRatio = it }, onDone = save)
            }
        }
        if (hasChat) {
            // Bouton du chat : coin haut de l'image, à côté du chat, jamais dessous.
            val endPad = when {
                column -> chatWidth + handle
                overlay && !onLeft && half != "bottom" -> chatWidth + handle
                else -> 0.dp
            }
            Column(Modifier.align(Alignment.TopEnd).padding(end = endPad), horizontalAlignment = Alignment.End) {
                IconButton(onClick = {
                    val next = mode.next
                    ChatPrefs.landscape.set(next.key)
                    Logger.info("CHAT", "Chat en paysage", next.key)
                    flash = AppState.t(next.labelKey)
                }) {
                    Icon(Icons.Filled.Email, contentDescription = AppState.t("tv_chat"), tint = Color.White)
                }
                flash?.let {
                    Text(it, color = Color.White, fontSize = 12.sp,
                         modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(6.dp))
                             .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** Poignée du bord haut du chat posé sur l'image (série 7) : la glisser
 *  déplace le chat ; lâchée, il s'aimante à sa place la plus proche. */
@Composable
private fun ChatMoveGrip(onMove: (Offset) -> Unit, onDone: () -> Unit) {
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    val label = AppState.t("s7_chat_move")
    Box(
        Modifier.fillMaxWidth().height(20.dp).background(Color.Black.copy(alpha = 0.35f))
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { change, amount ->
                    change.consume()
                    move(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.6f)))
    }
}

/** Poignée entre l'image et le chat (pas de trait sur l'image) : la glisser
 *  vers l'image élargit le chat. */
@Composable
private fun ResizeHandle(totalPx: Float, chatOnLeft: Boolean, current: () -> Float, onDrag: (Float) -> Unit, onDone: () -> Unit) {
    val state = rememberDraggableState { delta ->
        if (totalPx > 0f) onDrag((current() + (if (chatOnLeft) delta else -delta) / totalPx).coerceIn(0.20f, 0.60f))
    }
    Box(Modifier.width(14.dp).fillMaxHeight().draggable(state, Orientation.Horizontal, onDragStopped = { onDone() }),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.55f)))
    }
}
