package com.mxfia19.twitchunblock.core

/** Ce que désigne une saisie (recherche) : VOD, clip ou chaîne. Mêmes
 *  règles que l'app iPhone. */
object Links {
    private val sitePaths = setOf(
        "videos", "directory", "settings", "subscriptions", "inventory", "wallet", "drops", "search",
        "downloads", "jobs", "p", "collections", "moderator", "popout", "embed", "login", "signup", "logout", "payments",
    )
    private val clipPattern = Regex(
        "^(?:https?://)?(?:www\\.|m\\.)?(?:clips\\.twitch\\.tv/|twitch\\.tv/[a-z0-9_]+/clip/)([A-Za-z0-9_-]{3,100})",
        RegexOption.IGNORE_CASE,
    )

    /** Lien …/videos/… ou identifiant (8 chiffres ou plus). */
    fun isVod(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        if (t.lowercase().contains("/videos/")) return true
        return t.length >= 8 && t.all { it.isDigit() }
    }

    fun vodId(input: String): String? = Regex("\\d{8,}").find(input)?.value

    /** Départ d'un lien de VOD (« ?t=1h2m3s »), en secondes. */
    fun vodStart(input: String): Int? {
        val raw = Regex("[?&]t=([^&#\\s]+)").find(input)?.groupValues?.get(1) ?: return null
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$", RegexOption.IGNORE_CASE).find(raw) ?: return null
        fun part(i: Int) = m.groupValues.getOrNull(i)?.toIntOrNull() ?: 0
        val s = part(1) * 3600 + part(2) * 60 + part(3)
        return if (s > 0) s else null
    }

    fun clipSlug(input: String): String? = clipPattern.find(input.trim())?.groupValues?.get(1)

    /** Chaîne désignée : premier mot sans « @ », ou chaîne d'un lien Twitch. */
    fun login(input: String): String {
        var login = input.trim().split(" ").firstOrNull()?.lowercase() ?: ""
        if (!Regex("(^|[/.])clips\\.twitch\\.tv/").containsMatchIn(login)) {
            Regex("twitch\\.tv/([a-z0-9_]{1,25})").find(login)?.groupValues?.get(1)?.let {
                if (it !in sitePaths) login = it
            }
        }
        return login.removePrefix("@")
    }

    fun isValidLogin(login: String) = Regex("^[a-z0-9_]{1,25}$").matches(login)

    /** Chaîne d'un lien twitchunblock://live/<chaîne> (notification, ntfy) ;
     *  null sinon. Lien ouvrable par n'importe quelle page web : pseudo
     *  validé, il finit dans les adresses des sources. */
    fun liveLink(uri: android.net.Uri?): String? {
        if (uri == null || uri.host != "live") return null
        val login = uri.pathSegments.firstOrNull()?.lowercase() ?: return null
        return login.takeIf { isValidLogin(it) }
    }
}
