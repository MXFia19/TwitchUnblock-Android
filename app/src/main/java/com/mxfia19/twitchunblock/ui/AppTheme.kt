package com.mxfia19.twitchunblock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger

/** Couleur d'accent : boutons, onglet actif, liens. Mêmes couleurs que
 *  AccentTheme (iOS) ; `id` : nom de l'iPhone et du site (sauvegarde). */
enum class AccentTheme(val id: String, val primary: Color, val light: Color) {
    TWITCH("twitch", Color(0xFF9147FF), Color(0xFFBF94FF)),
    BLUE("blue", Color(0xFF2F7CF6), Color(0xFF8AB8FF)),
    GREEN("green", Color(0xFF10B981), Color(0xFF6EE7B7)),
    PINK("pink", Color(0xFFEC4899), Color(0xFFF9A8D4)),
    ORANGE("orange", Color(0xFFF97316), Color(0xFFFDBA74)),
    RED("red", Color(0xFFE5484D), Color(0xFFFF9C9F)),
    TEAL("teal", Color(0xFF06B6D4), Color(0xFF67E8F9));

    val labelKey: String get() = "accent_$id"
}

/** Fond de l'app : sombre façon Twitch, noir complet (écrans OLED : les
 *  pixels éteints ne consomment rien), ou ardoise. Le sombre garde les
 *  couleurs d'avant de l'app Android ; les deux autres, celles de l'iPhone. */
enum class BackgroundTheme(val id: String, val bg: Color, val surface: Color, val surfaceHi: Color, val line: Color) {
    DARK("dark", Color(0xFF0E0E10), Color(0xFF18181B), Color(0xFF1F1F23), Color(0xFF2F2F35)),
    OLED("oled", Color(0xFF000000), Color(0xFF0E0E10), Color(0xFF1C1C1F), Color(0xFF2E2E33)),
    SLATE("slate", Color(0xFF0F141C), Color(0xFF171E29), Color(0xFF232C3A), Color(0xFF35404F));

    val labelKey: String get() = "bg_$id"
}

/**
 * Thème en cours (Réglages → Général → Apparence), lu par les couleurs TU :
 * un changement redessine l'app aussitôt (état Compose). Propre à chaque
 * appareil, comme sur l'iPhone : jamais synchronisé. La TV ne le propose
 * pas et garde le thème par défaut.
 */
object AppTheme {
    private const val ACCENT_KEY = "theme_accent"
    private const val BACKGROUND_KEY = "theme_bg"

    // Lus à la création de l'objet (premier accès, après AppState.init) :
    // les couleurs TU ne sont demandées qu'une fois l'interface lancée.
    var accent by mutableStateOf(
        AccentTheme.entries.firstOrNull { it.id == AppState.prefs.getString(ACCENT_KEY, null) } ?: AccentTheme.TWITCH)
        private set
    var background by mutableStateOf(
        BackgroundTheme.entries.firstOrNull { it.id == AppState.prefs.getString(BACKGROUND_KEY, null) } ?: BackgroundTheme.DARK)
        private set

    fun updateAccent(a: AccentTheme) {
        accent = a
        AppState.prefs.edit().putString(ACCENT_KEY, a.id).apply()
        Logger.info("SETTINGS", "Couleur d'accent", a.id)
    }

    fun updateBackground(b: BackgroundTheme) {
        background = b
        AppState.prefs.edit().putString(BACKGROUND_KEY, b.id).apply()
        Logger.info("SETTINGS", "Fond", b.id)
    }

    /** Valeurs d'une sauvegarde (noms du site et de l'iPhone : « themeAccent »,
     *  « themeBg ») ; une valeur inconnue (fond « light » du site) est ignorée. */
    fun applyBackup(accentId: String?, backgroundId: String?) {
        AccentTheme.entries.firstOrNull { it.id == accentId }?.let { updateAccent(it) }
        BackgroundTheme.entries.firstOrNull { it.id == backgroundId }?.let { updateBackground(it) }
    }
}
