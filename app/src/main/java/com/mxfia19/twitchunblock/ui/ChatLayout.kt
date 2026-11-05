package com.mxfia19.twitchunblock.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mxfia19.twitchunblock.chat.ChatPrefs
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import kotlinx.coroutines.delay
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
 * en glissant la poignée, de 20 à 60 % de l'écran et 180 dp au moins.
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
            val totalPx = with(LocalDensity.current) { total.toPx() }
            val shown = chatWidth / total
            val save: () -> Unit = {
                dragRatio?.let {
                    ChatPrefs.widthPercent.set((it * 100).roundToInt())
                    Logger.info("CHAT", "Largeur du chat en paysage", "${ChatPrefs.widthPercent.value} %")
                }
                dragRatio = null
            }
            // Le chat garde sa place dans l'arbre d'une disposition à l'autre
            // (saisie et défilement conservés) ; la poignée côté image.
            Row(Modifier.align(align).width(chatWidth + handle).fillMaxHeight(if (half == "full") 1f else 0.5f)) {
                if (!onLeft) ResizeHandle(totalPx, chatOnLeft = false, current = { dragRatio ?: shown }, onDrag = { dragRatio = it }, onDone = save)
                Box(Modifier.weight(1f).fillMaxHeight().imePadding()) { chat(Modifier.fillMaxSize(), overlay) }
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
