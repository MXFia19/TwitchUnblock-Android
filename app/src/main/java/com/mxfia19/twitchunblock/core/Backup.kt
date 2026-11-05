package com.mxfia19.twitchunblock.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * (ceux de l'iPhone qui existent ici sont repris aussi).
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

    fun export(): String {
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
            // Drops, synchro de l'historique et source du direct : perdus avant.
            .put("android_dropsProgress", Features.dropsProgress.value)
            .put("android_autoClaimDrops", Features.dropsAutoClaim.value)
            .put("android_dropsNotify", Features.dropsNotify.value)
            .put("android_cloudSync", Features.cloudSync.value)
            .put("android_liveSource", AppState.liveSource)
            // Ordre des sources, sous le nom de l'iPhone et du site (Ma source en tête, comme ici).
            .put("liveSourceOrder", JSONArray(listOfNotNull("custom".takeIf { StreamPrefs.customSourceHost() != null }) +
                                              AppState.liveSourceSteps().filter { it != "worker" }))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("platform", "android")
            .put("exportedAt", iso.format(Date()))
            .put("follows", JSONArray(AppState.localFollows.toList()))
            .put("accountFollows", JSONArray())
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
    private fun importContinue(raw: JSONArray?) {
        if (raw == null) return
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
        if (items.isEmpty()) return
        val known = History.items.map { it.key }.toHashSet()
        val list = History.items.toList() + items.filter { it.key !in known }
        // Tri stable : à date égale, l'ordre d'ici d'abord.
        History.replace(list.sortedByDescending { it.addedAt }.take(50))
        val positions = HashMap(AppState.vodPositions())
        for ((id, ms) in incoming) if (ms > (positions[id] ?: 0L) + 1000) positions[id] = ms
        AppState.replaceVodPositions(positions)
        // Ajouts d'ici : partent sur le compte (synchro active).
        CloudSync.scheduleSoon()
    }

    /** Fusionne une sauvegarde : (chaînes ajoutées, laissées de côté) ; null si le fichier n'en est pas une. */
    fun import(text: String): Pair<Int, Int>? {
        if (text.length > MAX_BYTES) return null
        val root = parseJson(text) ?: return null
        if (root.str("format") != FORMAT) return null

        val incoming = (root.arr("follows")?.strings().orEmpty() + root.arr("accountFollows")?.strings().orEmpty())
            .map { it.lowercase() }.filter { Links.isValidLogin(it) }
        val before = AppState.localFollows.size
        val fresh = incoming.filter { !AppState.isFollowedLocally(it) }.distinct()
        val room = maxOf(0, maxOf(300, before) - before)
        val added = fresh.take(room)
        added.reversed().forEach { AppState.toggleLocalFollow(it) }
        Hidden.merge(root.obj("hidden"))
        FollowedCategories.merge(root.arr("followedCategories"))
        importContinue(root.arr("continueWatching"))

        val s = root.obj("settings") ?: return Pair(added.size, fresh.size - added.size)
        fun bool(vararg keys: String, apply: (Boolean) -> Unit) {
            for (k in keys) s.bool(k)?.let { apply(it); return }
        }
        fun words(key: String, pref: WordsPref) {
            s.arr(key)?.strings()?.let { pref.set(it) }
        }
        s.str("lang")?.let { code -> Lang.of(code)?.let { AppState.updateLang(it) } }
        if (s.isJsonNull("topLang")) Features.updateTopLang(null)
        else s.str("topLang")?.let { t ->
            val code = t.lowercase().replace("_", "-")
            if (code in TopLanguage.codes) Features.updateTopLang(code)
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
        (s.str("android_cellularQuality") ?: s.str("ios_cellularQuality"))?.let {
            if (it in Quality.CELLULAR_PREFS) AppState.updateCellularQuality(it)
        }
        // Taille du chat de l'iPhone : en points aussi (10 à 20), bornée par IntPref.
        (s.int("android_chatFontSize") ?: s.dbl("ios_chatFontSize")?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() })
            ?.let { Features.chatFontSize.set(it) }
        s.str("qualityPref")?.let { q ->
            when {
                q in Quality.PREFS -> AppState.updateQualityPref(q)
                q.toIntOrNull() != null -> {
                    val h = q.toInt()
                    val near = if (h > 1080) "source" else listOf(1080, 720, 480, 360, 160).minByOrNull { kotlin.math.abs(it - h) }.toString()
                    if (near in Quality.PREFS) AppState.updateQualityPref(near)
                }
            }
        }
        importLater(s)
        return Pair(added.size, fresh.size - added.size)
    }

    /** Réglages des séries 5 à 7 : ceux de l'iPhone (ios_…) valent aussi
     *  quand Android n'a pas les siens. Une valeur invalide est ignorée. */
    private fun importLater(s: JSONObject) {
        val chat = com.mxfia19.twitchunblock.chat.ChatPrefs
        val emotes = com.mxfia19.twitchunblock.chat.EmoteLists
        fun bool(vararg keys: String, apply: (Boolean) -> Unit) {
            for (k in keys) s.bool(k)?.let { apply(it); return }
        }
        fun int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { s.int(it) }
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { s.str(it) }
        /** Facteur (0,32 ; 1,25) → pour cent, comme les réglages d'Android. */
        fun percent(vararg keys: String): Int? = keys.firstNotNullOfOrNull { s.dbl(it) }?.let { Math.round(it * 100).toInt() }

        if (s.has("themeAccent") || s.has("themeBg")) {
            com.mxfia19.twitchunblock.ui.AppTheme.applyBackup(s.str("themeAccent"), s.str("themeBg"))
        }
        if (s.isJsonNull("customSource")) StreamPrefs.setCustomSource("")
        else s.str("customSource")?.let { StreamPrefs.setCustomSource(it) }
        s.arr("hiddenEmotes")?.strings()?.let { emotes.replaceHidden(it) }
        s.arr("favoriteEmotes")?.strings()?.let { emotes.replaceFavorites(it) }
        s.str("timeFormat")?.takeIf { it in listOf("auto", "12", "24") }?.let { chat.timeFormat.set(it) }
        s.arr("pinned")?.strings()?.let { Pins.merge(it) }
        bool("pinnedByViewers") { BrowsePrefs.pinnedByViewers.set(it) }
        bool("homeList") { BrowsePrefs.homeList.set(it) }
        bool("android_higherQualities") { StreamPrefs.higherQualities.set(it) }
        bool("android_preferVertical") { StreamPrefs.preferVertical.set(it) }
        bool("android_syncSettings", "ios_syncSettings") { SettingsSync.enabled.set(it) }
        (s.bool("android_hideAccount") ?: s.obj("ios_customization")?.bool("hideAccount"))?.let { NoAccount.pref.set(it) }
        bool("android_chatSync", "ios_chatSync") { chat.sync.set(it) }
        int("android_chatSyncNudge", "ios_chatSyncNudge")?.let { chat.syncNudge.set(it) }
        bool("android_chatShowInput", "ios_chatShowInput") { chat.showInput.set(it) }
        str("android_landscapeChat", "ios_landscapeChat")?.takeIf { it in listOf("column", "overlay", "hidden") }?.let { chat.landscape.set(it) }
        str("android_chatOverlayLayout", "ios_chatOverlayLayout")
            ?.takeIf { Regex("(left|right)-(full|top|bottom)").matches(it) }?.let { chat.overlay.set(it) }
        percent("android_chatWidthRatio", "ios_chatWidthRatio")?.let { chat.widthPercent.set(it) }
        int("android_chatSpacing", "ios_chatSpacing")?.let { chat.spacing.set(it) }
        percent("android_chatBadgeScale", "ios_chatBadgeScale")?.let { chat.badgeScale.set(it) }
        percent("android_chatEmoteScale", "ios_chatEmoteScale")?.let { chat.emoteScale.set(it) }
        (s.obj("android_customization") ?: s.obj("ios_customization"))?.let { BrowsePrefs.applyCustomization(it) }
        // Vitesse des VODs : un facteur de 0,25 à 2 chez le site et l'iPhone.
        s.dbl("vodSpeed")?.takeIf { it in 0.25..2.0 }?.let { PlayerPrefs.vodSpeed.set(Math.round(it * 100).toInt()) }
        bool("audioCompressor") { PlayerPrefs.setCompressor(it) }
        bool("android_fillScreen", "ios_fillScreen") { PlayerPrefs.fillScreen.set(it) }
        bool("android_dropsProgress", "ios_dropsProgress") { Features.dropsProgress.set(it) }
        bool("android_autoClaimDrops", "ios_autoClaimDrops") { Features.dropsAutoClaim.set(it) }
        bool("android_dropsNotify", "ios_dropsNotify") { Features.dropsNotify.set(it) }
        bool("android_cloudSync") { Features.cloudSync.set(it) }
        // Source du direct : celle d'Android, sinon la première connue de
        // l'ordre de l'iPhone et du site (« as » en tête : l'ordre automatique).
        val source = s.str("android_liveSource")?.takeIf { it in Config.LIVE_SOURCE_CHOICES }
            ?: s.arr("liveSourceOrder")?.strings()?.firstOrNull { it in Config.LIVE_SOURCES }
                ?.let { if (it == Config.LIVE_SOURCES.first()) "auto" else it }
        source?.let { AppState.updateLiveSource(it) }
    }
}
