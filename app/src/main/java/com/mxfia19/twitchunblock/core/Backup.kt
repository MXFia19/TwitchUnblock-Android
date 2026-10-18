package com.mxfia19.twitchunblock.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sauvegarde dans un fichier : chaînes suivies, masquées et réglages, au
 * format du site et de l'app iPhone (« twitchunblock-backup ») : un fichier
 * de l'un s'importe dans l'autre. Réglages communs sous les mêmes noms,
 * ceux de l'app sous « android_… » (ceux de l'iPhone qui existent ici sont
 * repris aussi).
 */
object Backup {
    private const val FORMAT = "twitchunblock-backup"

    fun fileName(): String = "twitchunblock-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

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
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("platform", "android")
            .put("exportedAt", iso.format(Date()))
            .put("follows", JSONArray(AppState.localFollows.toList()))
            .put("accountFollows", JSONArray())
            .put("followedCategories", JSONArray())
            .put("hidden", Hidden.toJson())
            .put("settings", settings)
        return root.toString(2)
    }

    /** Fusionne une sauvegarde : (chaînes ajoutées, laissées de côté) ; null si le fichier n'en est pas une. */
    fun import(text: String): Pair<Int, Int>? {
        if (text.length > 2_000_000) return null
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
        (s.str("android_cellularQuality") ?: s.str("ios_cellularQuality"))?.let {
            if (it in Quality.CELLULAR_PREFS) AppState.updateCellularQuality(it)
        }
        s.int("android_chatFontSize")?.let { Features.chatFontSize.set(it) }
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
        return Pair(added.size, fresh.size - added.size)
    }
}
