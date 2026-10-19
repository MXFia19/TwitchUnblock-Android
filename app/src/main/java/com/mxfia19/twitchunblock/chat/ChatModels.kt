package com.mxfia19.twitchunblock.chat

/** Morceau d'un message : texte, emote (image), mention, lien. */
sealed class ChatToken {
    data class Text(val text: String) : ChatToken()
    /** Pour sa fiche : `source` (« twitch », « 7tv », « bttv », « ffz »), son
     *  id chez Twitch, son auteur s'il est connu, emote de la chaîne ou globale. */
    data class Emote(val name: String, val url: String, val ratio: Float = 1f, val zeroWidth: Boolean = false,
                     val source: String = "twitch", val twitchId: String? = null, val author: String? = null,
                     val channel: Boolean = false) : ChatToken()
    data class Mention(val name: String) : ChatToken()
    data class Link(val url: String) : ChatToken()
}

data class ChatMessage(
    val id: String,
    val login: String,
    val name: String,
    /** Couleur du pseudo (ARGB), déjà rendue lisible sur fond sombre. */
    val color: Int,
    /** Adresses des badges devant le pseudo. */
    val badges: List<String>,
    val tokens: List<ChatToken>,
    /** « /me » : tout le message dans la couleur du pseudo. */
    val action: Boolean = false,
    /** Message du système (abonnement, raid, modération…), seul ou au-dessus
     *  du message de la personne. */
    val notice: String? = null,
    /** On y est mentionné. */
    val highlight: Boolean = false,
    val deleted: Boolean = false,
    val time: Long = System.currentTimeMillis(),
)

/** Ligne IRC lue : tags, préfixe, commande, paramètres, texte final. */
class IrcLine(
    val tags: Map<String, String>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
    val trailing: String?,
) {
    /** Pseudo de l'expéditeur (« nick!user@host »). */
    val nick: String? get() = prefix?.substringBefore('!')?.takeIf { it.isNotEmpty() && !it.contains('.') }
}

object Irc {
    fun parse(line: String): IrcLine? {
        var rest = line
        var tags: Map<String, String> = emptyMap()
        if (rest.startsWith("@")) {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            tags = parseTags(rest.substring(1, sp))
            rest = rest.substring(sp + 1)
        }
        var prefix: String? = null
        if (rest.startsWith(":")) {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            prefix = rest.substring(1, sp)
            rest = rest.substring(sp + 1)
        }
        var trailing: String? = null
        val ti = rest.indexOf(" :")
        if (ti >= 0) {
            trailing = rest.substring(ti + 2)
            rest = rest.substring(0, ti)
        } else if (rest.startsWith(":")) {
            trailing = rest.substring(1)
            rest = ""
        }
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return IrcLine(tags, prefix, parts[0], parts.drop(1), trailing)
    }

    private fun parseTags(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in s.split(';')) {
            val i = pair.indexOf('=')
            if (i < 0) out[pair] = "" else out[pair.substring(0, i)] = unescape(pair.substring(i + 1))
        }
        return out
    }

    /** Valeurs des tags : \s espace, \: point-virgule, \\ barre, \r \n. */
    private fun unescape(v: String): String {
        if (!v.contains('\\')) return v
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    's' -> sb.append(' ')
                    ':' -> sb.append(';')
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(v[i + 1])
                }
                i += 2
            } else {
                if (c != '\\') sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
