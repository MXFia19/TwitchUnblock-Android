package com.mxfia19.twitchunblock.chat

import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Commandes des bots du chat (Nightbot, StreamElements, Fossabot, Moobot) :
 * les lire sans taper « !commands ». Mêmes API publiques que l'extension
 * « View Twitch Commands In Chat ». Comme BotCommandsAPI (iOS).
 */
object BotCommands {
    /** `name` avec son préfixe : « !discord ». */
    class Command(val name: String, val response: String)
    class BotSet(val bot: String, val icon: String, val commands: List<Command>)

    private suspend fun json(url: String, headers: Map<String, String> = emptyMap()): Any? {
        val r = Http.get(url, headers, timeout = 10) ?: return null
        if (r.code != 200) return null
        val t = r.body.trim()
        return if (t.startsWith("[")) parseJsonArray(t) else parseJson(t)
    }

    private fun prefixed(name: String): String {
        val n = name.trim()
        return if (n.startsWith("!")) n else "!$n"
    }

    private fun list(a: JSONArray?): List<JSONObject> = a?.objects().orEmpty()

    private suspend fun nightbot(ch: String): BotSet? {
        val id = (json("https://api.nightbot.tv/1/channels/t/$ch") as? JSONObject)?.obj("channel")?.str("_id") ?: return null
        val r = json("https://api.nightbot.tv/1/commands", mapOf("Nightbot-Channel" to id)) as? JSONObject ?: return null
        val cmds = list(r.arr("commands")).mapNotNull { d ->
            val n = d.str("name") ?: return@mapNotNull null
            Command(n, d.str("message") ?: "")
        }
        return if (cmds.isEmpty()) null else BotSet("Nightbot",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/nightbot-profile_image-2345338c09b4d468-50x50.png", cmds)
    }

    private suspend fun streamElements(ch: String): BotSet? {
        val id = (json("https://api.streamelements.com/kappa/v2/channels/$ch") as? JSONObject)?.str("_id") ?: return null
        val arr = json("https://api.streamelements.com/kappa/v2/bot/commands/$id/public") as? JSONArray ?: return null
        val cmds = list(arr).mapNotNull { d ->
            val n = d.str("command") ?: return@mapNotNull null
            if (d.bool("enabled") == false || d.bool("hidden") == true) return@mapNotNull null
            Command(prefixed(n), d.str("reply") ?: "")
        }
        return if (cmds.isEmpty()) null else BotSet("StreamElements",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/streamelements-profile_image-a89b9d61499d365f-50x50.png", cmds)
    }

    private suspend fun fossabot(ch: String): BotSet? {
        val id = (json("https://api.fossabot.com/v2/cached/channels/by-slug/$ch") as? JSONObject)?.obj("channel")?.str("id")
            ?: return null
        val r = json("https://api.fossabot.com/v2/cached/channels/$id/commands") as? JSONObject ?: return null
        val cmds = list(r.arr("commands")).mapNotNull { d ->
            val n = d.str("name") ?: return@mapNotNull null
            if (d.bool("enabled_online") == false) return@mapNotNull null
            Command(prefixed(n), d.str("response") ?: "")
        }
        return if (cmds.isEmpty()) null else BotSet("Fossabot",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/719a0ffa-6c86-4321-83f1-44990fd644bc-profile_image-50x50.png", cmds)
    }

    private suspend fun moobot(ch: String): BotSet? {
        val id = (json("https://api.moo.bot/1/channel/meta?name=$ch") as? JSONObject)?.obj("channel")?.let {
            it.str("userid") ?: it.opt("userid")?.toString()
        } ?: return null
        val r = json("https://api.moo.bot/1/channel/public/commands/list?channel=${Http.encode(id)}") as? JSONObject ?: return null
        val cmds = list(r.arr("list")).mapNotNull { d ->
            val n = d.str("identifier") ?: return@mapNotNull null
            Command(prefixed(n), d.str("response") ?: "")
        }
        return if (cmds.isEmpty()) null else BotSet("Moobot",
            "https://static-cdn.jtvnw.net/jtv_user_pictures/663db70b-80e7-424b-a54f-ed88f7ac9355-profile_image-50x50.png", cmds)
    }

    /** Les quatre en parallèle ; seuls les bots qui ont des commandes restent,
     *  leurs commandes par ordre alphabétique. */
    suspend fun all(channel: String): List<BotSet> = coroutineScope {
        val ch = Http.encode(channel.lowercase())
        val sets = listOf(async { nightbot(ch) }, async { streamElements(ch) }, async { fossabot(ch) }, async { moobot(ch) })
            .mapNotNull { it.await() }
        // Un nom en double ferait planter la liste (clés) : le premier reste.
        sets.map { s -> BotSet(s.bot, s.icon, s.commands.distinctBy { it.name }.sortedBy { it.name.lowercase() }) }
    }
}
