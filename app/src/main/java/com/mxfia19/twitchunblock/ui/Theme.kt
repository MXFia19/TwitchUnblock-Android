package com.mxfia19.twitchunblock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Couleurs de l'app iPhone (thème sombre, violet de Twitch). */
object TU {
    val bg = Color(0xFF0E0E10)
    val surface = Color(0xFF18181B)
    val surfaceHi = Color(0xFF1F1F23)
    val line = Color(0xFF2F2F35)
    val primary = Color(0xFF9147FF)
    val primaryDim = Color(0x339147FF)
    val text = Color(0xFFEFEFF1)
    val muted = Color(0xFFADADB8)
    val live = Color(0xFFEB0400)
    val success = Color(0xFF00C853)
    val danger = Color(0xFFFF4F4D)
    val highlight = Color(0x40FF4F4D)
}

@Composable
fun TUTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TU.primary,
            onPrimary = Color.White,
            secondary = TU.primary,
            onSecondary = Color.White,
            background = TU.bg,
            onBackground = TU.text,
            surface = TU.surface,
            onSurface = TU.text,
            surfaceVariant = TU.surfaceHi,
            onSurfaceVariant = TU.muted,
            surfaceContainer = TU.surface,
            surfaceContainerHigh = TU.surfaceHi,
            outline = TU.line,
            error = TU.danger,
        ),
        content = content,
    )
}
