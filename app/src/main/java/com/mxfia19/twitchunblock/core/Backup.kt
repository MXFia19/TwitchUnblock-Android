package com.mxfia19.twitchunblock.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sauvegarde dans un fichier : chaînes suivies, masquées, réglages et
 * « Continuer à regarder », au format du site et de l'app iPhone
 * (« twitchunblock-backup ») : un fichier de l'un s'importe dans l'autre.
 * Réglages communs sous les mêmes noms, ceux de l'app sous « android_… »
 * (ceux de l'iPhone qui existent ici sont repris aussi). L'import demande
 * « Tout » ou « Chaînes seulement » et dit ce qu'il a repris (Summary),
 * comme l'iPhone.
 */
object Backup {
    private const val FORMAT = "twitchunblock-backup"
    /** Au-delà, ce n'est pas une sauvegarde (même borne qu'iOS). */
    private const val MAX_BYTES = 2_000_000

    fun fileName(): String = "twitchunblock-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    /** Écrit l'export dans le fichier choisi, hors du fil principal : un
     *  document Drive peut faire attendre. false : écriture impossible. */
    suspend fun writeTo(context: Context, target: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(target)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            Logger.warn("BACKUP", "Export impossible", e.message)
            false
        }
    }

    /** Lit le fichier choisi hors du fil principal (Drive pas encore en
     *  cache), MAX_BYTES au plus : un gros fichier pris par erreur est
     *  refusé sans tout charger en mémoire. null : illisible ou trop gros. */
    suspend fun readFrom(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@use null
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Logger.warn("BACKUP", "Import illisible", e.message)
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Ce que reprend un import : tout, ou les chaînes seulement (suivies,
     *  épinglées, masquées, et les catégories suivies). */
    enum class Scope { ALL, CHANNELS_ONLY }

    /** Ce qu'un import a repris, pour son résumé. */
    class Summary {
        var follows = 0
        /** Laissées de côté : 300 chaînes suivies au plus sur l'appareil. */
        var followsSkipped = 0
        var categories = 0
        var pinned = 0
        /** Chaînes et catégories masquées ajoutées. */
        var hidden = 0
        var settings = 0
        /** Réglages inconnus de cette version (ou propres au site), ou invalides. */
        var settingsIgnored = 0
        var continueWatching = 0
    }

    /** Sauvegarde lue et reconnue, en attente du choix « Tout » ou « Chaînes seulement ». */
    class Pending internal constructor(internal val root: JSONObject)

    /** Chaînes suivies par le compte, pour l'export (« accountFollows »,
     *  comme l'iPhone) : relues sur Twitch, 10 s au plus ; vide sans compte
     *  ou sans réponse. */
    suspend fun accountFollows(): List<String> {
        if (AppState.user == null || AppState.token == null) return emptyList()
        val list = withTimeoutOrNull(10_000) { BrowseApi.followedLogins() } ?: return emptyList()
        return list.map { it.lowercase() }.filter { Links.isValidLogin(it) }.distinct()
    }

    fun export(accountFollows: List<String> = emptyList()): String {
        val settings = JSONObject()
            .put("lang", AppState.lang.code)
            .put("topLang", Features.topLang?.replace("-", "_") ?: JSONObject.NULL)
            .put("timestamps", Features.chatTimestamps.value)
            .put("keepDeleted", Features.chatKeepDeleted.value)
            .put("loadHistory", Features.chatLoadHistory.value)
            .put("hideBots", Features.chatHideBots.value)
            .put("hideCommands", Features.chatHideCommands.value)
            .put("mutedWords", JSONArray(Features.mutedWords.value))
            .put("blockedUsers", JSONArray(Features.blockedUsers.value))
            .put("highlightWords", JSONArray(Features.highlightWords.value))
            .put("shareUsage", Usage.enabled)
            .put("qualityPref", AppState.qualityPref)
            .put("showRecent", Features.showRecent.value)
            .put("android_autoClaimChest", Features.autoClaimChest.value)
            .put("android_showPinned", Features.showPinned.value)
            .put("android_showLiveEvents", Features.showLiveEvents.value)
            .put("android_enableRaids", Features.enableRaids.value)
            .put("android_showWatchStreak", Features.showWatchStreak.value)
            .put("android_subAdFree", Features.subAdFree.value)
            .put("android_lowLatency", AppState.lowLatency)
            .put("android_cellularQuality", AppState.cellularQuality)
            .put("android_chatFontSize", Features.chatFontSize.value)
            .put("android_backgroundPlay", AppState.backgroundPlay)
            .put("android_autoPip", AppState.autoPip)
            .put("android_showChat", AppState.showChat)
            .put("android_downloadWifiOnly", Features.dlWifiOnly.value)
            // « Reprendre les VODs », sous le nom du site.
            .put("vodProgress", VodResume.enabled)
        // Séries 5 à 7 : sous les noms du site et de l'iPhone quand ils les ont ;
        // largeur et échelles du chat en facteur (0,32 ; 1,25), comme l'iPhone.
        val theme = com.mxfia19.twitchunblock.ui.AppTheme
        val chat = com.mxfia19.twitchunblock.chat.ChatPrefs
        val emotes = com.mxfia19.twitchunblock.chat.EmoteLists
        settings
            .put("themeAccent", theme.accent.id)
            .put("themeBg", theme.background.id)
            .put("customSource", StreamPrefs.customSource.value.ifEmpty { null } ?: JSONObject.NULL)
            .put("hiddenEmotes", JSONArray(emotes.hidden))
            .put("favoriteEmotes", JSONArray(emotes.favorites))
            .put("timeFormat", chat.timeFormat.value)
            .put("pinned", JSONArray(BrowsePrefs.pinned.value))
            .put("pinnedByViewers", BrowsePrefs.pinnedByViewers.value)
            .put("homeList", BrowsePrefs.homeList.value)
            .put("android_higherQualities", StreamPrefs.higherQualities.value)
            .put("android_preferVertical", StreamPrefs.preferVertical.value)
            .put("android_syncSettings", SettingsSync.enabled.value)
            .put("android_hideAccount", NoAccount.pref.value)
            .put("android_chatSync", chat.sync.value)
            .put("android_chatSyncNudge", chat.syncNudge.value)
            .put("android_chatShowInput", chat.showInput.value)
            .put("android_landscapeChat", chat.landscape.value)
            .put("android_chatOverlayLayout", chat.overlay.value)
            .put("android_chatWidthRatio", chat.widthPercent.value / 100.0)
            .put("android_chatSpacing", chat.spacing.value)
            .put("android_chatBadgeScale", chat.badgeScale.value / 100.0)
            .put("android_chatEmoteScale", chat.emoteScale.value / 100.0)
            .put("android_customization", BrowsePrefs.customizationJson())
            .put("vodSpeed", PlayerPrefs.vodSpeed.value / 100.0)
            .put("audioCompressor", PlayerPrefs.audioCompressor.value)
            .put("android_fillScreen", PlayerPrefs.fillScreen.value)
            // Série 6 : réglages du chat (noms de l'iPhone en repli à l'import)
            // et langue des lives des catégories (nom et format du site, null = toutes).
            .put("android_chatKeyboardSuggestions", com.mxfia19.twitchunblock.chat.ChatOptions.keyboardSuggestions.value)
            .put("android_showDropsNotice", com.mxfia19.twitchunblock.chat.ChatOptions.dropsCard.value)
            .put("android_showSpecialEvents", com.mxfia19.twitchunblock.chat.ChatOptions.specialEvents.value)
            .put("android_showFollowButton", com.mxfia19.twitchunblock.chat.ChatOptions.followButton.value)
            .put("android_landNoticesHidden", com.mxfia19.twitchunblock.chat.ChatOptions.landscapeNoticesHidden.value)
            .put("catLang", BrowsePrefs.categoryLang.value.ifEmpty { null }?.replace("-", "_") ?: JSONObject.NULL)
            // Drops, synchro de l'historique et source du direct : perdus avant.
            .put("android_dropsProgress", Features.dropsProgress.value)
            .put("android_autoClaimDrops", Features.dropsAutoClaim.value)
            .put("android_dropsNotify", Features.dropsNotify.value)
            .put("android_cloudSync", Features.cloudSync.value)
            .put("android_liveSource", AppState.liveSource)
            // Ordre des sources, sous le nom de l'iPhone et du site (Ma source en tête, comme ici).
            .put("liveSourceOrder", JSONArray(LiveSources.order()))
            // Série 6 : son d'origine des passages coupés, chat du multi-live.
            .put("android_restoreMuted", com.mxfia19.twitchunblock.player.VodUnmute.enabled.value)
            .put("multiChat", com.mxfia19.twitchunblock.ui.MultiPicks.chat.value)
            // Android TV (série 6) : place du chat par défaut et rangée « Reprendre »
            // (mêmes valeurs que l'Apple TV ; les choix par chaîne restent sur l'appareil).
            .put("android_tvChatLayout", com.mxfia19.twitchunblock.tv.TvChatPrefs.place.raw)
            .put("android_tvResumePlace", com.mxfia19.twitchunblock.tv.TvHomePrefs.resumePlace)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("platform", "android")
            .put("exportedAt", iso.format(Date()))
            .put("follows", JSONArray(AppState.localFollows.toList()))
            .put("accountFollows", JSONArray(accountFollows))
            .put("followedCategories", FollowedCategories.toJson())
            .put("hidden", Hidden.toJson())
            .put("settings", settings)
            .put("continueWatching", continueJson())
        return root.toString(2)
    }

    /** « Continuer à regarder », comme l'iPhone : VODs de l'historique vues
     *  plus de 30 s, avec leur position (s). Sans compte, c'est le seul moyen
     *  de garder les reprises (TU Test, réinstallation). */
    private fun continueJson(): JSONArray {
        val positions = AppState.vodPositions()
        val arr = JSONArray()
        History.vods.filter { (positions[it.term] ?: 0L) > 30_000 }.take(50).forEach { h ->
            arr.put(JSONObject().put("id", h.term).put("title", h.display)
                        .put("position", Math.round((positions[h.term] ?: 0L) / 1000.0))
                        .put("addedAt", h.addedAt).apply {
                            h.thumb?.takeIf { it.isNotEmpty() }?.let { put("thumb", it) }
                            h.streamer?.takeIf { it.isNotEmpty() }?.let { put("streamer", it) }
                        })
        }
        return arr
    }

    /** VODs entamées d'une sauvegarde (continueWatching, l'iPhone compris) :
     *  ajoutées à l'historique si elles n'y sont pas, position reprise si
     *  elle est plus avancée que celle d'ici. Valeurs bornées comme iOS. */
    private fun importContinue(raw: JSONArray?): Int {
        if (raw == null) return 0
        val items = ArrayList<HistoryItem>()
        val incoming = HashMap<String, Long>()
        for (o in raw.objects().take(50)) {
            val id = o.str("id")?.takeIf { it.isNotEmpty() && it.length <= 20 && it.all { c -> c in '0'..'9' } } ?: continue
            val position = o.dbl("position")?.takeIf { it.isFinite() && it > 30 && it < 7 * 24 * 3600 } ?: continue
            if (id in incoming) continue
            val title = (o.str("title") ?: "").take(300)
            val thumb = o.str("thumb")?.takeIf { it.startsWith("https://") && it.length <= 500 }
            val streamer = o.str("streamer")?.take(60)?.takeIf { it.isNotEmpty() }
            val added = o.dbl("addedAt")?.takeIf { it.isFinite() && it > 0 && it < 1e14 } ?: 0.0
            items += HistoryItem(id, "vod", title.ifEmpty { "VOD $id" }, thumb, streamer, added)
            incoming[id] = Math.round(position * 1000)
        }
        if (items.isEmpty()) return 0
        val known = History.items.map { it.key }.toHashSet()
        val list = History.items.toList() + items.filter { it.key !in known }
        // Tri stable : à date égale, l'ordre d'ici d'abord.
        History.replace(list.sortedByDescending { it.addedAt }.take(50))
        val positions = HashMap(AppState.vodPositions())
        var advanced = 0
        for ((id, ms) in incoming) if (ms > (positions[id] ?: 0L) + 1000) {
            positions[id] = ms
            advanced++
        }
        AppState.replaceVodPositions(positions)
        // Ajouts d'ici : partent sur le compte (synchro active).
        CloudSync.scheduleSoon()
        // « Reprendre les VODs » coupé : aucune position reprise.
        return if (VodResume.enabled) advanced else 0
    }

    /** Lit une sauvegarde sans rien appliquer ; null si le fichier n'en est
     *  pas une (app, iPhone ou site, toute version). */
    fun read(text: String): Pending? {
        if (text.length > MAX_BYTES) return null
        val root = parseJson(text) ?: return null
        if (root.str("format") != FORMAT) return null
        return Pending(root)
    }

    /** Applique une sauvegarde lue : chaînes suivies, épinglées et masquées,
     *  catégories suivies, ajoutées aux nôtres (rien n'est retiré) ; pour
     *  « Tout », en plus les réglages connus et valides (les autres sont
     *  ignorés et comptés) et « Continuer à regarder ». */
    fun apply(pending: Pending, scope: Scope): Summary {
        val root = pending.root
        val summary = Summary()
        val incoming = (root.arr("follows")?.strings().orEmpty() + root.arr("accountFollows")?.strings().orEmpty())
            .map { it.lowercase() }.filter { Links.isValidLogin(it) }
        val before = AppState.localFollows.size
        val fresh = incoming.filter { !AppState.isFollowedLocally(it) }.distinct()
        val room = maxOf(0, maxOf(300, before) - before)
        val added = fresh.take(room)
        added.reversed().forEach { AppState.toggleLocalFollow(it) }
        summary.follows = added.size
        summary.followsSkipped = fresh.size - added.size

        val hiddenChannels = Hidden.channels.map { it.login }.toSet()
        val hiddenCategories = Hidden.categories.map { it.id }.toSet()
        Hidden.merge(root.obj("hidden"))
        summary.hidden = Hidden.channels.count { it.login !in hiddenChannels } +
            Hidden.categories.count { it.id !in hiddenCategories }
        summary.categories = FollowedCategories.merge(root.arr("followedCategories"))
        val s = root.obj("settings")
        // Épinglées : dans les réglages sous le nom du site, mais ce sont des chaînes.
        s?.arr("pinned")?.strings()?.let { summary.pinned = Pins.merge(it) }
        if (scope == Scope.CHANNELS_ONLY) return summary

        summary.continueWatching = importContinue(root.arr("continueWatching"))
        if (s != null) {
            val (applied, ignored) = applySettings(s)
            summary.settings = applied
            summary.settingsIgnored = ignored
        }
        return summary
    }

    /** Réglages repris, chaque valeur validée ; ceux de l'iPhone (ios_…)
     *  valent aussi quand Android n'a pas les siens. Rend le nombre de
     *  réglages repris, et celui des clés ignorées (inconnues, propres au
     *  site, invalides). */
    private fun applySettings(s: JSONObject): Pair<Int, Int> {
        val applied = HashSet<String>()
        fun ok(k: String) { applied += k }
        fun bool(vararg keys: String, apply: (Boolean) -> Unit) {
            for (k in keys) s.bool(k)?.let { apply(it); ok(k); return }
        }
        fun words(key: String, pref: WordsPref) {
            s.arr(key)?.strings()?.let { pref.set(it); ok(key) }
        }
        /** Premier texte valable parmi `keys`. */
        fun str(vararg keys: String, valid: (String) -> Boolean): String? {
            for (k in keys) s.str(k)?.takeIf(valid)?.let { ok(k); return it }
            return null
        }
        fun int(vararg keys: String): Int? {
            for (k in keys) s.int(k)?.let { ok(k); return it }
            return null
        }
        /** Facteur (0,32 ; 1,25) → pour cent, comme les réglages d'Android. */
        fun percent(vararg keys: String): Int? {
            for (k in keys) s.dbl(k)?.takeIf { it.isFinite() }?.let { ok(k); return Math.round(it * 100).toInt() }
            return null
        }
        val chat = com.mxfia19.twitchunblock.chat.ChatPrefs
        val emotes = com.mxfia19.twitchunblock.chat.EmoteLists

        str("lang") { Lang.of(it) != null }?.let { code -> Lang.of(code)?.let { AppState.updateLang(it) } }
        if (s.isJsonNull("topLang")) {
            Features.updateTopLang(null)
            ok("topLang")
        } else {
            str("topLang") { it.lowercase().replace("_", "-") in TopLanguage.codes }
                ?.let { Features.updateTopLang(it.lowercase().replace("_", "-")) }
        }
        bool("timestamps") { Features.chatTimestamps.set(it) }
        bool("keepDeleted") { Features.chatKeepDeleted.set(it) }
        bool("loadHistory") { Features.chatLoadHistory.set(it) }
        bool("hideBots") { Features.chatHideBots.set(it) }
        bool("hideCommands") { Features.chatHideCommands.set(it) }
        words("mutedWords", Features.mutedWords)
        words("blockedUsers", Features.blockedUsers)
        words("highlightWords", Features.highlightWords)
        bool("shareUsage") { Usage.updateEnabled(it) }
        bool("showRecent") { Features.showRecent.set(it) }
        bool("android_autoClaimChest", "ios_autoClaimChest") { Features.autoClaimChest.set(it) }
        bool("android_showPinned", "ios_showPinned") { Features.showPinned.set(it) }
        bool("android_showLiveEvents", "ios_showLiveEvents") { Features.showLiveEvents.set(it) }
        bool("android_enableRaids", "ios_enableRaids") { Features.enableRaids.set(it) }
        bool("android_showWatchStreak", "ios_showWatchStreak") { Features.showWatchStreak.set(it) }
        bool("android_subAdFree", "ios_subAdFree") { Features.subAdFree.set(it) }
        bool("android_lowLatency", "ios_lowLatency") { AppState.updateLowLatency(it) }
        bool("android_backgroundPlay") { AppState.updateBackgroundPlay(it) }
        bool("android_autoPip") { AppState.updateAutoPip(it) }
        bool("android_showChat") { AppState.updateShowChat(it) }
        bool("android_downloadWifiOnly", "ios_downloadWifiOnly") {
            com.mxfia19.twitchunblock.download.Downloads.updateWifiOnly(it)
        }
        str("android_cellularQuality", "ios_cellularQuality") { it in Quality.CELLULAR_PREFS }?.let { AppState.updateCellularQuality(it) }
        // Taille du chat de l'iPhone : en points aussi (10 à 20), bornée par IntPref.
        (int("android_chatFontSize") ?: s.dbl("ios_chatFontSize")?.takeIf { it.isFinite() }?.let { ok("ios_chatFontSize"); Math.round(it).toInt() })
            ?.let { Features.chatFontSize.set(it) }
        s.str("qualityPref")?.let { q ->
            val pref = when {
                q in Quality.PREFS -> q
                q.toIntOrNull() != null -> {
                    val h = q.toInt()
                    if (h > 1080) "source" else listOf(1080, 720, 480, 360, 160).minByOrNull { kotlin.math.abs(it - h) }.toString()
                }
                else -> null
            }
            if (pref != null && pref in Quality.PREFS) {
                AppState.updateQualityPref(pref)
                ok("qualityPref")
            }
        }

        // Séries 5 à 7.
        val accent = s.str("themeAccent")?.takeIf { id -> com.mxfia19.twitchunblock.ui.AccentTheme.entries.any { it.id == id } }
        val background = s.str("themeBg")?.takeIf { id -> com.mxfia19.twitchunblock.ui.BackgroundTheme.entries.any { it.id == id } }
        if (accent != null || background != null) {
            com.mxfia19.twitchunblock.ui.AppTheme.applyBackup(accent, background)
            accent?.let { ok("themeAccent") }
            background?.let { ok("themeBg") }
        }
        if (s.isJsonNull("customSource")) {
            StreamPrefs.setCustomSource("")
            ok("customSource")
        } else {
            s.str("customSource")?.let { if (StreamPrefs.setCustomSource(it)) ok("customSource") }
        }
        s.arr("hiddenEmotes")?.strings()?.let { emotes.replaceHidden(it); ok("hiddenEmotes") }
        s.arr("favoriteEmotes")?.strings()?.let { emotes.replaceFavorites(it); ok("favoriteEmotes") }
        str("timeFormat") { it in listOf("auto", "12", "24") }?.let { chat.timeFormat.set(it) }
        bool("pinnedByViewers") { BrowsePrefs.pinnedByViewers.set(it) }
        bool("homeList") { BrowsePrefs.homeList.set(it) }
        bool("android_higherQualities") { StreamPrefs.higherQualities.set(it) }
        bool("android_preferVertical") { StreamPrefs.preferVertical.set(it) }
        bool("android_syncSettings", "ios_syncSettings") { SettingsSync.enabled.set(it) }
        (s.bool("android_hideAccount")?.also { ok("android_hideAccount") } ?: s.obj("ios_customization")?.bool("hideAccount"))
            ?.let { NoAccount.pref.set(it) }
        bool("android_chatSync", "ios_chatSync") { chat.sync.set(it) }
        int("android_chatSyncNudge", "ios_chatSyncNudge")?.let { chat.syncNudge.set(it) }
        bool("android_chatShowInput", "ios_chatShowInput") { chat.showInput.set(it) }
        str("android_landscapeChat", "ios_landscapeChat") { it in listOf("column", "overlay", "hidden") }?.let { chat.landscape.set(it) }
        str("android_chatOverlayLayout", "ios_chatOverlayLayout") { Regex("(left|right)-(full|top|bottom)").matches(it) }
            ?.let { chat.overlay.set(it) }
        percent("android_chatWidthRatio", "ios_chatWidthRatio")?.let { chat.widthPercent.set(it) }
        int("android_chatSpacing", "ios_chatSpacing")?.let { chat.spacing.set(it) }
        percent("android_chatBadgeScale", "ios_chatBadgeScale")?.let { chat.badgeScale.set(it) }
        percent("android_chatEmoteScale", "ios_chatEmoteScale")?.let { chat.emoteScale.set(it) }
        for (k in listOf("android_customization", "ios_customization")) {
            val c = s.obj(k) ?: continue
            BrowsePrefs.applyCustomization(c)
            ok(k)
            break
        }
        // Vitesse des VODs : un facteur de 0,25 à 2 chez le site et l'iPhone.
        s.dbl("vodSpeed")?.takeIf { it in 0.25..2.0 }?.let { PlayerPrefs.vodSpeed.set(Math.round(it * 100).toInt()); ok("vodSpeed") }
        bool("audioCompressor") { PlayerPrefs.setCompressor(it) }
        bool("android_fillScreen", "ios_fillScreen") { PlayerPrefs.fillScreen.set(it) }
        val opts = com.mxfia19.twitchunblock.chat.ChatOptions
        bool("android_chatKeyboardSuggestions", "ios_chatKeyboardSuggestions") { opts.keyboardSuggestions.set(it) }
        bool("android_showDropsNotice", "ios_showDropsNotice") { opts.dropsCard.set(it) }
        bool("android_showSpecialEvents", "ios_showSpecialEvents") { opts.specialEvents.set(it) }
        bool("android_showFollowButton", "ios_showFollowButton") { opts.followButton.set(it) }
        bool("android_landNoticesHidden", "ios_landNoticesHidden") { opts.landscapeNoticesHidden.set(it) }
        // Langue des lives des catégories : null = toutes (format du site, « zh_hk »).
        if (s.isJsonNull("catLang")) {
            BrowsePrefs.categoryLang.set("")
            ok("catLang")
        } else {
            str("catLang") { it.lowercase().replace("_", "-") in TopLanguage.codes }
                ?.let { BrowsePrefs.categoryLang.set(it.lowercase().replace("_", "-")) }
        }
        bool("android_dropsProgress", "ios_dropsProgress") { Features.dropsProgress.set(it) }
        bool("android_autoClaimDrops", "ios_autoClaimDrops") { Features.dropsAutoClaim.set(it) }
        bool("android_dropsNotify", "ios_dropsNotify") { Features.dropsNotify.set(it) }
        bool("android_cloudSync") { Features.cloudSync.set(it) }
        bool("vodProgress") { VodResume.set(it) }
        // Sources du direct : l'ordre complet (iPhone, site, Android depuis la
        // série 6) ; sinon la première source d'une ancienne sauvegarde Android.
        val order = s.arr("liveSourceOrder")?.strings()?.takeIf { l -> l.any { it in Config.LIVE_SOURCES || it == "custom" } }
        if (order != null) {
            LiveSources.setOrder(order)
            ok("liveSourceOrder")
            if (s.has("android_liveSource")) ok("android_liveSource")
        } else {
            str("android_liveSource") { it in Config.LIVE_SOURCE_CHOICES }?.let { AppState.updateLiveSource(it) }
        }
        bool("android_restoreMuted", "ios_restoreMuted") { com.mxfia19.twitchunblock.player.VodUnmute.enabled.set(it) }
        bool("multiChat") { com.mxfia19.twitchunblock.ui.MultiPicks.chat.set(it) }
        str("android_tvChatLayout") { com.mxfia19.twitchunblock.tv.TvChatPlace.parse(it) != null }
            ?.let { com.mxfia19.twitchunblock.tv.TvChatPlace.parse(it)?.let { p -> com.mxfia19.twitchunblock.tv.TvChatPrefs.setDefaultPlace(p) } }
        str("android_tvResumePlace") { it in listOf("top", "bottom", "hidden") }
            ?.let { com.mxfia19.twitchunblock.tv.TvHomePrefs.setResumePlace(it) }

        // Ignorées : ni reprises, ni lues ailleurs (épinglées), ni doublon
        // d'un réglage repris sous l'autre nom (android_… / ios_…).
        fun twin(k: String): String? = when {
            k.startsWith("android_") -> "ios_" + k.removePrefix("android_")
            k.startsWith("ios_") -> "android_" + k.removePrefix("ios_")
            else -> null
        }
        val keys = s.keys().asSequence().toList()
        val ignored = keys.count { k -> k !in applied && k != "pinned" && twin(k)?.let { it in applied } != true }
        return Pair(applied.size, ignored)
    }
}
