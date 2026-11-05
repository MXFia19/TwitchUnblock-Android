package com.mxfia19.twitchunblock.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.App
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.boolPref
import com.mxfia19.twitchunblock.core.intPref
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.stringPref
import com.mxfia19.twitchunblock.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Réglages du chat de la série 5 : synchro avec la vidéo, place du chat en
 * paysage, zone d'envoi, tailles, format de l'heure. Mêmes clés et valeurs
 * par défaut que l'app iPhone, sauf le chat en paysage : replié d'office,
 * comme avant sur Android (le choix est ensuite gardé).
 */
object ChatPrefs {
    // ── Synchro avec la vidéo ──
    val sync = boolPref("cfg_chat_sync", true)
    /** Retouche du retard du chat (s) : + le retarde, − l'avance. */
    val syncNudge = intPref("chat_sync_nudge", 0, -20..20)

    // ── Disposition ──
    /** Zone d'envoi (champ, emotes, envoyer) sous le chat. Coupée, pour qui
     *  ne fait que lire : la place va aux messages (portrait, paysage, multi-live). */
    val showInput = boolPref("cfg_chat_input", true)
    /** Chat en paysage : « column », « overlay » ou « hidden » (LandscapeChat). */
    val landscape = stringPref("cfg_landscape_chat", "hidden")
    /** Chat posé sur l'image : « right-full », « left-bottom »… (forme de l'iPhone et du site). */
    val overlay = stringPref("cfg_chat_overlay_layout", "right-full")
    /** Largeur du chat en paysage, en % de l'écran (colonne ou posé sur l'image). */
    val widthPercent = intPref("cfg_chat_width_pct", 32, 20..60)

    // ── Tailles ──
    /** Espace entre deux messages (dp) : 6, l'écart d'avant la série 5. */
    val spacing = intPref("cfg_chat_spacing", 6, 0..16)
    /** Emotes et badges suivent la taille du texte, puis ces facteurs (%). */
    val emoteScale = intPref("cfg_chat_emote_pct", 100, 50..200)
    val badgeScale = intPref("cfg_chat_badge_pct", 100, 50..200)
    /** Heure des messages : « auto » (celle de l'appareil), « 12 » ou « 24 ». */
    val timeFormat = stringPref("cfg_time_format", "auto")
}

/** Heure devant les messages, en 12 h ou 24 h (ChatPrefs.timeFormat). */
object ChatClock {
    private val h24 = SimpleDateFormat("HH:mm", Locale.US)
    private val h12 = SimpleDateFormat("h:mm a", Locale.US)
    /** Réglage de l'appareil, lu une fois. */
    private val device12h by lazy { !android.text.format.DateFormat.is24HourFormat(App.instance) }

    fun twelve(): Boolean = when (ChatPrefs.timeFormat.value) {
        "12" -> true
        "24" -> false
        else -> device12h
    }

    fun format(time: Long, twelve: Boolean): String = (if (twelve) h12 else h24).format(Date(time))
}

/**
 * Emotes masquées et favorites, par leur code exact (la casse compte :
 * « KEKW » et « kekw » sont deux emotes), la plus récente à la fin. Une
 * emote masquée s'affiche comme le mot tapé, dans tout le chat (direct,
 * VODs, multi-live) ; les favorites passent en tête du sélecteur et de
 * l'autocomplétion. Mêmes clés que l'iPhone ; « hiddenEmotes » et
 * « favoriteEmotes » dans la sauvegarde et la synchro (comme le site).
 */
object EmoteLists {
    const val MAX_HIDDEN = 200
    const val MAX_FAVORITES = 100
    private const val HIDDEN_KEY = "chat_hidden_emotes"
    private const val FAVORITES_KEY = "chat_favorite_emotes"
    /** Image de chaque favorite (code → adresse) : une emote Twitch n'est pas
     *  parmi celles du sélecteur (7TV, BTTV, FFZ), elle y reste visible ainsi. */
    private const val URLS_KEY = "chat_favorite_emote_urls"

    var hidden by mutableStateOf<List<String>>(emptyList())
        private set
    /** Les mêmes, pour la recherche faite à chaque emote affichée. */
    var hiddenSet by mutableStateOf<Set<String>>(emptySet())
        private set
    var favorites by mutableStateOf<List<String>>(emptyList())
        private set
    private var urls: Map<String, String> = emptyMap()

    init {
        hidden = codes(read(HIDDEN_KEY)).takeLast(MAX_HIDDEN)
        hiddenSet = hidden.toHashSet()
        favorites = codes(read(FAVORITES_KEY)).takeLast(MAX_FAVORITES)
        val saved = parseJson(AppState.prefs.getString(URLS_KEY, null))
        val map = HashMap<String, String>()
        if (saved != null) for (k in saved.keys()) saved.str(k)?.let { map[k] = it }
        urls = map
    }

    private fun read(key: String): List<String> = parseJsonArray(AppState.prefs.getString(key, null))?.strings().orEmpty()

    /** Codes valides (sauvegarde, synchro, site) : sans espace, 100 caractères
     *  au plus, sans doublon, les 200 derniers. Comme AppStore.emoteCodes (iOS). */
    fun codes(list: List<String>): List<String> {
        val seen = HashSet<String>()
        return list.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 100 && it.none { c -> c.isWhitespace() } && seen.add(it) }
            .takeLast(MAX_HIDDEN)
    }

    fun hides(code: String): Boolean = code in hiddenSet

    /** Masque l'emote, ou la réaffiche si elle l'était déjà. Au-delà de 200,
     *  les plus anciennes repartent. */
    fun toggleHidden(code: String) {
        val c = codes(listOf(code)).firstOrNull() ?: return
        if (c in hiddenSet) {
            setHidden(hidden - c)
            Logger.info("CHAT", "Emote réaffichée", c)
        } else {
            setHidden(hidden + c)
            Logger.info("CHAT", "Emote masquée", c)
        }
    }

    /** Liste entière (« Tout réafficher », import d'une sauvegarde). */
    fun setHidden(list: List<String>) {
        hidden = codes(list).takeLast(MAX_HIDDEN)
        hiddenSet = hidden.toHashSet()
        AppState.prefs.edit().putString(HIDDEN_KEY, JSONArray(hidden).toString()).apply()
    }

    fun isFavorite(code: String): Boolean = code in favorites

    /** Ajoute l'emote aux favoris (avec son image), ou l'en retire. Au-delà
     *  de 100, les plus anciennes repartent. */
    fun toggleFavorite(code: String, url: String?) {
        val c = codes(listOf(code)).firstOrNull() ?: return
        if (c in favorites) {
            setFavorites(favorites - c)
            Logger.info("CHAT", "Emote retirée des favoris", c)
        } else {
            if (!url.isNullOrEmpty()) urls = urls + (c to url)
            setFavorites(favorites + c)
            Logger.info("CHAT", "Emote ajoutée aux favoris", c)
        }
    }

    /** Liste entière (import d'une sauvegarde). Les images des favorites
     *  retirées sont oubliées. */
    fun setFavorites(list: List<String>) {
        favorites = codes(list).takeLast(MAX_FAVORITES)
        urls = urls.filterKeys { it in favorites }
        val o = JSONObject()
        for ((k, v) in urls) o.put(k, v)
        AppState.prefs.edit()
            .putString(FAVORITES_KEY, JSONArray(favorites).toString())
            .putString(URLS_KEY, o.toString())
            .apply()
    }

    /** Image gardée avec la favorite (null : seulement son code, venu d'une sauvegarde). */
    fun favoriteUrl(code: String): String? = urls[code]
}

/** Règles du chat acceptées, par chaîne, sur cet appareil ; redemandées si
 *  la chaîne les change. Comme ChatRules (iOS). */
object ChatRules {
    private const val KEY = "chat_rules_ok"

    fun accepted(channel: String, rules: List<String>): Boolean =
        parseJson(AppState.prefs.getString(KEY, null))?.str(channel.lowercase()) == rules.joinToString("\n")

    fun accept(channel: String, rules: List<String>) {
        var saved = parseJson(AppState.prefs.getString(KEY, null)) ?: JSONObject()
        if (saved.length() > 300) saved = JSONObject()
        saved.put(channel.lowercase(), rules.joinToString("\n"))
        AppState.prefs.edit().putString(KEY, saved.toString()).apply()
    }
}
