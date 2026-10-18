package com.mxfia19.twitchunblock.core

/**
 * Qualités : ordre des menus, préférence, libellés — mêmes règles que l'app
 * iPhone (TwitchAPI.swift). « Auto » est la liste maîtresse (ExoPlayer suit
 * le débit) quand on l'a.
 */
object Quality {
    const val AUTO = "Auto"
    val PREFS = listOf("auto", "source", "1080", "720", "480", "360", "160", "audio")
    val CELLULAR_PREFS = listOf("auto", "same", "720", "480", "360", "audio")

    fun isSource(key: String) = key.lowercase() == "source" || key.lowercase() == "chunked"
    fun isAudio(key: String) = key.lowercase().contains("audio")
    fun isAuto(key: String) = key.lowercase() == "auto"

    /** Hauteur écrite dans le nom : « 720p60 » → 720, « 1920x1080 » → 1080. */
    private fun nameHeight(key: String): Int? {
        Regex("\\d+x(\\d+)").find(key)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("(\\d+)p").find(key)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** Hauteur d'une qualité : celle de la liste maîtresse, sinon du nom ;
     *  « Source » sans information : le palier au-dessus de la meilleure
     *  qualité transcodée. Audio seul : 0. */
    fun height(key: String, keys: Collection<String>, variants: Map<String, Variant> = emptyMap()): Int {
        if (isAudio(key)) return 0
        variants[key]?.height?.takeIf { it > 0 }?.let { return it }
        nameHeight(key)?.let { return it }
        if (!isSource(key)) return 0
        val best = keys.filter { !isSource(it) }.mapNotNull { nameHeight(it) }.maxOrNull() ?: 0
        if (best <= 0) return 1080
        return listOf(360, 480, 720, 1080, 1440, 2160).firstOrNull { it > best } ?: best
    }

    private fun fps(key: String, variants: Map<String, Variant>): Double {
        variants[key]?.fps?.takeIf { it > 0 }?.let { return it }
        return Regex("p(\\d+)").find(key)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    /** Source en tête, puis Auto, puis de la plus haute à la plus basse,
     *  l'audio seul en dernier. */
    fun sort(keys: Collection<String>, variants: Map<String, Variant> = emptyMap()): List<String> {
        fun group(k: String) = if (isSource(k)) 0 else if (isAuto(k)) 1 else if (isAudio(k)) 3 else 2
        return keys.sortedWith(Comparator { a, b ->
            val ga = group(a)
            val gb = group(b)
            if (ga != gb) return@Comparator ga - gb
            val ha = height(a, keys, variants)
            val hb = height(b, keys, variants)
            if (ha != hb) return@Comparator hb - ha
            val fa = fps(a, variants)
            val fb = fps(b, variants)
            if (fa != fb) return@Comparator fb.compareTo(fa)
            a.compareTo(b)
        })
    }

    /** Qualité d'après la préférence (`PREFS`) : une hauteur prend la même
     *  hauteur la plus fluide, sinon la plus haute en dessous, sinon la plus
     *  basse ; « source » Source (sinon la plus haute) ; « audio » l'audio
     *  seul ; « auto » la liste maîtresse si on l'a, sinon comme « source ». */
    fun preferred(keys: Collection<String>, pref: String, variants: Map<String, Variant> = emptyMap()): String {
        val video = keys.filter { !isAudio(it) && !isAuto(it) }.sortedWith(Comparator { a, b ->
            val ha = height(a, keys, variants)
            val hb = height(b, keys, variants)
            if (ha != hb) return@Comparator hb - ha
            fps(b, variants).compareTo(fps(a, variants))
        })
        val audio = keys.firstOrNull { isAudio(it) }
        val fallback = video.firstOrNull() ?: audio ?: keys.firstOrNull() ?: ""
        if (pref == "auto") return keys.firstOrNull { isAuto(it) } ?: (video.firstOrNull { isSource(it) } ?: fallback)
        if (pref == "audio") return audio ?: video.lastOrNull() ?: fallback
        val want = pref.toIntOrNull()
        if (want != null) {
            return video.firstOrNull { height(it, keys, variants) == want }
                ?: video.firstOrNull { height(it, keys, variants) < want }
                ?: video.lastOrNull() ?: fallback
        }
        return video.firstOrNull { isSource(it) } ?: fallback
    }

    /** Qualité au lancement selon la connexion. En données mobiles, « auto »
     *  prend la liste maîtresse si on l'a, sinon 720p au plus : une qualité
     *  fixe trop haute gelait l'image pendant que le chat continuait. */
    fun launchKey(keys: Collection<String>, pref: String, cellularPref: String, cellular: Boolean,
                  variants: Map<String, Variant> = emptyMap()): String {
        if (!cellular || cellularPref == "same") return preferred(keys, pref, variants)
        if (cellularPref == "auto") {
            if (pref == "audio") return preferred(keys, pref, variants)
            keys.firstOrNull { isAuto(it) }?.let { return it }
            return preferred(keys, minOf(pref.toIntOrNull() ?: 720, 720).toString(), variants)
        }
        return preferred(keys, cellularPref, variants)
    }

    /** Préférence retenue quand on choisit une qualité dans le lecteur. */
    fun preferenceFor(key: String, keys: Collection<String>, variants: Map<String, Variant> = emptyMap()): String? {
        if (isAudio(key)) return "audio"
        if (isAuto(key)) return "auto"
        if (isSource(key)) return "source"
        val h = height(key, keys, variants)
        if (h <= 0) return null
        if (h > 1080) return "source"
        return listOf(1080, 720, 480, 360, 160).minByOrNull { kotlin.math.abs(it - h) }?.toString()
    }

    /** Nom dans les menus : « Source », « Audio seul », « 720p » pour
     *  « 720p30 », « 1080p » pour « 1920x1080 ». */
    fun label(key: String, lang: Lang): String {
        if (isAuto(key)) return I18n.t("q_auto_short", lang)
        if (isSource(key)) return "Source"
        if (isAudio(key)) return I18n.t("q_audio", lang)
        if (key.endsWith("p30")) return key.dropLast(2)
        if (Regex("^\\d+x\\d+$").matches(key)) nameHeight(key)?.let { return "${it}p" }
        return key
    }

    /** Libellé d'une préférence (réglages). */
    fun prefLabel(pref: String, lang: Lang): String = when (pref) {
        "auto" -> I18n.t("q_auto", lang)
        "source" -> "Source"
        "audio" -> I18n.t("q_audio", lang)
        "same" -> I18n.t("q_same_wifi", lang)
        else -> "${pref}p"
    }
}
