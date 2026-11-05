package com.mxfia19.twitchunblock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Couleurs de l'app iPhone : fond et accent selon le thème choisi
 *  (AppTheme ; sombre et violet de Twitch par défaut), lus à chaque
 *  affichage pour qu'un changement de thème redessine tout. */
object TU {
    val bg: Color get() = AppTheme.background.bg
    val surface: Color get() = AppTheme.background.surface
    val surfaceHi: Color get() = AppTheme.background.surfaceHi
    val line: Color get() = AppTheme.background.line
    val primary: Color get() = AppTheme.accent.primary
    val primaryDim: Color get() = AppTheme.accent.primary.copy(alpha = 0.2f)
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
