package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Réglage oui/non gardé dans les préférences, lu par l'interface. */
class BoolPref(private val key: String, private val default: Boolean) {
    var value by mutableStateOf(default)
        private set

    fun load() { value = AppState.prefs.getBoolean(key, default) }

    fun set(on: Boolean) {
        value = on
        AppState.prefs.edit().putBoolean(key, on).apply()
    }
}

/** Nombre borné gardé dans les préférences. */
class IntPref(private val key: String, private val default: Int, val range: IntRange) {
    var value by mutableStateOf(default)
        private set

    fun load() { value = AppState.prefs.getInt(key, default).coerceIn(range) }

    fun set(v: Int) {
        value = v.coerceIn(range)
        AppState.prefs.edit().putInt(key, value).apply()
    }
}

/** Liste de mots (en minuscules), saisie séparée par des virgules. */
class WordsPref(private val key: String, private val max: Int) {
    var value by mutableStateOf<List<String>>(emptyList())
        private set

    fun load() { value = parseJsonArray(AppState.prefs.getString(key, null))?.strings().orEmpty() }

    fun set(list: List<String>) {
        value = list.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(max)
        AppState.prefs.edit().putString(key, org.json.JSONArray(value).toString()).apply()
    }

    fun setText(text: String) = set(text.split(','))

    val text: String get() = value.joinToString(", ")
}

/**
 * Réglages des séries 3 : chat (filtres, taille), événements du direct
 * (points, sondages, raids…), session web (sans pub, drops), langue du top,
 * synchronisation. Mêmes valeurs par défaut que l'app iPhone.
 */
object Features {
    // ── Chat ──
    val chatTimestamps = BoolPref("chat_timestamps", true)
    val chatKeepDeleted = BoolPref("chat_keep_deleted", false)
    val chatLoadHistory = BoolPref("chat_load_history", true)
    val chatHideBots = BoolPref("chat_hide_bots", false)
    val chatHideCommands = BoolPref("chat_hide_commands", false)
    val mutedWords = WordsPref("chat_muted_words", 50)
    val blockedUsers = WordsPref("chat_blocked_users", 500)
    val highlightWords = WordsPref("chat_highlight_words", 30)
    val chatFontSize = IntPref("chat_font_size", 14, 10..22)

    // ── Événements du direct ──
    val autoClaimChest = BoolPref("auto_claim_chest", true)
    val showPinned = BoolPref("show_pinned", true)
    val showLiveEvents = BoolPref("show_live_events", true)
    val enableRaids = BoolPref("enable_raids", true)
    val showWatchStreak = BoolPref("show_watch_streak", true)

    // ── Session web ──
    val subAdFree = BoolPref("sub_ad_free", true)
    val dropsProgress = BoolPref("drops_progress", true)
    val dropsAutoClaim = BoolPref("drops_autoclaim", true)
    val dropsNotify = BoolPref("drops_notify", true)

    // ── Divers ──
    val cloudSync = BoolPref("cloud_sync", true)
    val showRecent = BoolPref("show_recent", true)

    // ── Téléchargements ──
    /** Wi-Fi seulement : en données mobiles, un téléchargement attend le
     *  Wi-Fi (Downloads.applyWifiOnly). */
    val dlWifiOnly = BoolPref("dl_wifi_only", false)

    /** Langue du top des lives (TopLanguage.codes), null : celle de l'appareil. */
    var topLang by mutableStateOf<String?>(null)
        private set

    private val all get() = listOf(chatTimestamps, chatKeepDeleted, chatLoadHistory, chatHideBots, chatHideCommands,
                                   autoClaimChest, showPinned, showLiveEvents, enableRaids, showWatchStreak,
                                   subAdFree, dropsProgress, dropsAutoClaim, dropsNotify, cloudSync, showRecent,
                                   dlWifiOnly)

    fun init() {
        all.forEach { it.load() }
        mutedWords.load()
        blockedUsers.load()
        highlightWords.load()
        chatFontSize.load()
        topLang = AppState.prefs.getString("top_lang", null)?.takeIf { it in TopLanguage.codes }
    }

    fun updateTopLang(code: String?) {
        topLang = code?.takeIf { it in TopLanguage.codes }
        AppState.prefs.edit().putString("top_lang", topLang).apply()
    }

    val knownBots = setOf("nightbot", "streamelements", "moobot", "fossabot", "streamlabs", "wizebot", "soundalerts",
                          "sery_bot", "botisimo", "own3d", "kofistreambot", "pokemoncommunitygame", "deepbot",
                          "coebot", "phantombot", "creatisbot", "blerp")
}

/** Langue du top des lives : celle choisie, sinon celle de l'appareil. Comme TopLanguage (iOS). */
object TopLanguage {
    val codes = listOf("ar", "bg", "ca", "cs", "da", "de", "el", "en", "es", "fi", "fr", "hi", "hu", "id", "it",
                       "ja", "ko", "ms", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sv", "th", "tl", "tr", "uk",
                       "vi", "zh", "zh-hk")
    private val aliases = mapOf("nb" to "no", "nn" to "no", "fil" to "tl", "in" to "id")

    val device: String
        get() {
            val l = java.util.Locale.getDefault()
            val base = l.language.lowercase()
            if (base == "yue" || (base == "zh" && (l.country == "HK" || l.country == "MO"))) return "zh-hk"
            val code = aliases[base] ?: base
            return if (code in codes) code else "en"
        }

    fun resolved(): String = Features.topLang ?: device

    fun name(code: String): String {
        val tag = if (code == "zh-hk") "zh-HK" else code
        val n = java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.forLanguageTag(AppState.lang.code))
        return if (n.isEmpty()) code.uppercase() else n.substring(0, 1).uppercase() + n.substring(1)
    }
}
