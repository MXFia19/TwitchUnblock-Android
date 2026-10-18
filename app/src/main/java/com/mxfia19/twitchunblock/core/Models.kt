package com.mxfia19.twitchunblock.core

/** Un direct dans une liste (accueil, catégorie, suivis). */
data class TwitchStream(
    val id: String,
    val login: String,
    val name: String,
    val title: String,
    val game: String,
    val viewers: Int,
    val thumbnail: String,
    val tags: List<String> = emptyList(),
    val gameId: String = "",
    val startedAt: String? = null,
) {
    /** Miniature à la taille voulue (Helix renvoie {width}x{height}). */
    fun thumb(width: Int = 440, height: Int = 248): String =
        thumbnail.replace("{width}", "$width").replace("{height}", "$height")
}

data class Category(val id: String, val name: String, val boxArt: String, val viewers: Int?) {
    fun art(width: Int = 285, height: Int = 380): String =
        boxArt.replace("{width}", "$width").replace("{height}", "$height")
}

data class Vod(
    val id: String,
    val title: String,
    val thumbnail: String,
    val publishedAt: String,
    val lengthSeconds: Int,
    val viewCount: Int = 0,
)

data class Clip(
    val slug: String,
    val title: String,
    val thumbnail: String,
    val viewCount: Int,
    val durationSeconds: Int,
    val createdAt: String,
    val curator: String?,
)

data class TwitchUser(val id: String, val login: String, val displayName: String, val avatar: String)

/** Chaîne suivie : son direct s'il y en a un. `lastEnd` : fin du dernier live (ms). */
data class ChannelBrief(val login: String, val name: String, val avatar: String, val stream: TwitchStream?,
                        val lastEnd: Long? = null)

/** Proposition de la recherche (chaîne), `viewers` non nul si en direct. */
data class Suggestion(val login: String, val name: String, val avatar: String?, val viewers: Int?, val game: String?) {
    val isLive: Boolean get() = viewers != null
}

/** Ce que dit une liste maîtresse d'une qualité. */
data class Variant(val height: Int, val fps: Double, val bandwidth: Int)

/** Qualités d'une lecture : liens par nom, ce qu'on sait de chacune, et le
 *  texte d'une liste maîtresse aux adresses absolues (`text`, pour la
 *  qualité Auto : ExoPlayer y suit le débit). */
data class Master(
    val links: Map<String, String> = emptyMap(),
    val variants: Map<String, Variant> = emptyMap(),
    val text: String? = null,
) {
    val isEmpty: Boolean get() = links.isEmpty()
}

/** Page d'une chaîne, ou réponse d'un lancement de direct. `error` :
 *  « offline », « not_found », « network », « no_source ». */
data class LiveInfo(
    val login: String,
    val title: String = "",
    val game: String = "",
    val thumbnail: String = "",
    val avatar: String? = null,
    val userId: String? = null,
    val viewers: Int = 0,
    val startedAt: String? = null,
    val lastStart: String? = null,
    val error: String? = null,
)

data class LiveResult(
    val info: LiveInfo,
    val master: Master = Master(),
    val source: String? = null,
    val dvrVideoId: String? = null,
    /** Abonné à la chaîne (jeton du compte), null : inconnu. */
    val subscriber: Boolean? = null,
)

data class ClipPlayback(
    val links: Map<String, String>,
    val title: String,
    val broadcasterLogin: String?,
    val broadcasterName: String?,
    val vodId: String?,
    val vodOffset: Double?,
)

/** Page d'une liste : éléments, curseur de la suite, et `refused` : Twitch
 *  refuse la suite (intégrité). null côté appelant = pas de réponse. */
data class Page<T>(val items: List<T>, val next: String? = null, val refused: Boolean = false)

/** Jeton refusé par Twitch (401) : à renouveler ou se reconnecter. */
class AuthExpiredException : Exception("auth expired")

/** Chapitre d'une VOD (secondes). */
data class VodChapter(val start: Double, val duration: Double, val title: String)

/** Diffusion passée retrouvable (VOD retirée ou jamais listée). `epoch` : début (s). */
data class RecoverableStream(
    val streamId: String,
    val login: String,
    val epoch: Long,
    val title: String,
    val game: String,
    val maxViews: Int,
    val duration: Int,
)
