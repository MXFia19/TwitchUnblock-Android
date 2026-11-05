package com.mxfia19.twitchunblock.core

import android.media.MediaCodecList
import android.os.Build

/**
 * Réglages des sources et des flux du direct (série 5) : Ma source, image
 * verticale retenue, qualités supérieures. Mêmes clés de sauvegarde que
 * l'app iPhone (customSource, cfg_prefer_vertical, cfg_higher_qualities).
 */
object StreamPrefs {
    /** Ma source : « https://hôte/chemin » d'un serveur luminous-ttv, vide sans. */
    val customSource = stringPref("custom_source", "")

    /** Image verticale choisie : les directs suivants qui en ont une partent dessus. */
    val preferVertical = boolPref("prefer_vertical", false)

    /** Qualités supérieures (1440p…) par le compte : coupé par défaut, comme sur iPhone. */
    val higherQualities = boolPref("higher_qualities", false)

    /** Ma source saisie : enregistrée si l'adresse est valable (sinon faux,
     *  l'ancienne reste) ; vide = retirée. */
    fun setCustomSource(raw: String): Boolean {
        val trimmed = raw.trim()
        val was = customSourceHost()
        if (trimmed.isEmpty()) {
            customSource.set("")
        } else {
            val base = customSourceBase(trimmed) ?: return false
            customSource.set(base)
        }
        // Ordre des sources rangé : Ma source y entre en tête, ou en sort.
        if (customSourceHost() != was) LiveSources.onCustomChanged()
        return true
    }

    /** Hôte et chemin de Ma source, sans « https:// » (comme les autres proxys), null sans. */
    fun customSourceHost(): String? =
        customSourceBase(customSource.value)?.removePrefix("https://")

    /**
     * Adresse d'un serveur luminous-ttv à soi : https://, un nom de domaine,
     * sans port ni paramètres (ni adresse IP ni réseau local). Rend
     * « https://hôte/chemin » sans « / » final, ou null. Mêmes règles que
     * customSourceBase (iOS et site).
     */
    fun customSourceBase(value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.length > 200) return null
        val u = try { java.net.URI(v) } catch (e: Exception) { return null }
        if (u.scheme?.lowercase() != "https" || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
        if (u.port != -1 && u.port != 443) return null
        val host = u.host?.lowercase() ?: return null
        if (!host.contains('.') || host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val local = listOf("localhost", "local", "internal", "lan", "home", "arpa")
        if (local.any { host == it || host.endsWith(".$it") }) return null
        val path = (u.rawPath ?: "").trimEnd('/')
        return "https://$host$path"
    }
}

/**
 * Qualités supérieures (Enhanced Broadcasting) : la demande de playlist du
 * direct par le compte annonce à Twitch les codecs que l'appareil décode
 * (`supported_codecs`), et les chaînes concernées y ajoutent leurs 1440p et
 * plus. Jamais pour un jeton anonyme (proxys, Worker, Twitch sans compte) :
 * Twitch le plafonne à 1080p. Comme HigherQualities (iOS), mais seulement
 * pour un compte sans pub sur la chaîne (abonné, Turbo) : la bascule pendant
 * les pauses pub d'un compte qui en a n'est pas portée.
 */
object HigherQualities {
    /** H.264 toujours ; HEVC et AV1 seulement décodés en matériel. Le plus efficace d'abord. */
    val codecs: List<String> by lazy {
        val list = mutableListOf<String>()
        if (hardwareDecoder("video/av01")) list += "av1"
        if (hardwareDecoder("video/hevc")) list += "h265"
        list += "h264"
        list
    }

    /** Valeur de `supported_codecs` si le réglage est actif, sinon null. */
    fun requested(): String? = if (StreamPrefs.higherQualities.value) codecs.joinToString(",") else null

    /** « H.264 · HEVC · AV1 » (Réglages). */
    val label: String
        get() = codecs.reversed().joinToString(" · ") {
            when (it) { "h264" -> "H.264"; "h265" -> "HEVC"; "av1" -> "AV1"; else -> it }
        }

    private fun hardwareDecoder(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else {
                // Avant Android 10 : les décodeurs logiciels se reconnaissent à leur nom.
                val n = info.name.lowercase()
                !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains(".sw.")
            }
        }
    } catch (e: Exception) {
        false
    }
}
