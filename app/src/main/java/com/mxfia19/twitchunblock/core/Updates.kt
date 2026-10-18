package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.BuildConfig

/**
 * Nouvelle version sur GitHub Releases : la dernière du même canal (« TU
 * Test » : pré-versions test-N ; sinon vX.Y.N), comparée au numéro de build
 * (N, le même que versionCode). Vérifiée au lancement, au plus toutes les 6 h.
 */
object Updates {
    data class Update(val name: String, val code: Int, val notes: String, val apkUrl: String, val page: String)

    var available by mutableStateOf<Update?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    private const val LAST = "update_last_check"
    private const val SKIPPED = "update_skipped"

    suspend fun check(force: Boolean = false): Update? {
        val last = AppState.prefs.getLong(LAST, 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 3_600_000L) return available
        checking = true
        val r = Http.get("https://api.github.com/repos/${Config.REPO}/releases?per_page=20",
                         mapOf("Accept" to "application/vnd.github+json"), timeout = 15)
        checking = false
        val list = parseJsonArray(r?.body)?.objects()
        if (list == null) {
            Logger.warn("UPDATE", "Versions illisibles", "HTTP ${r?.code ?: "réseau"}")
            return null
        }
        AppState.prefs.edit().putLong(LAST, System.currentTimeMillis()).apply()
        val test = BuildConfig.CHANNEL == "test"
        val best = list.asSequence()
            .filter { it.bool("draft") != true && (it.bool("prerelease") == true) == test }
            .mapNotNull { rel ->
                val tag = rel.str("tag_name") ?: return@mapNotNull null
                val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return@mapNotNull null
                val apk = rel.arr("assets")?.objects()?.firstOrNull { it.str("name")?.endsWith(".apk") == true }
                    ?.str("browser_download_url") ?: return@mapNotNull null
                Update(rel.str("name") ?: tag, code, rel.str("body") ?: "", apk, rel.str("html_url") ?: apk)
            }
            .maxByOrNull { it.code }
        val newer = best?.takeIf { it.code > BuildConfig.VERSION_CODE }
        available = newer
        if (newer != null) Logger.info("UPDATE", "Nouvelle version", newer.name)
        return newer
    }

    /** « Plus tard » : cette version n'est plus proposée d'elle-même. */
    fun skip(update: Update) {
        AppState.prefs.edit().putInt(SKIPPED, update.code).apply()
    }

    fun skipped(update: Update): Boolean = AppState.prefs.getInt(SKIPPED, 0) >= update.code
}
