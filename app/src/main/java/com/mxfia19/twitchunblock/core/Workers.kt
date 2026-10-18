package com.mxfia19.twitchunblock.core

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes des Workers qui ne touchent pas à la base D1 (lives, VODs) : le
 * personnel d'abord, puis le principal et ses secours. Un Worker en panne ou
 * au quota du jour (429 « error code: 1027 ») est mis de côté (jusqu'à minuit
 * UTC pour le quota, 30 min sinon). Comme workerJSON (iOS).
 */
object Workers {
    private val downUntil = ConcurrentHashMap<String, Long>()

    private fun isDown(base: String) = (downUntil[base] ?: 0L) > System.currentTimeMillis()

    private fun nextUtcMidnight(): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun ordered(): List<String> {
        val bases = AppState.lookupBases
        return bases.filter { !isDown(it) } + bases.filter { isDown(it) }
    }

    private fun markFailure(base: String, body: String, count: Int) {
        if (count <= 1) return
        val quota = body.contains("1027")
        downUntil[base] = if (quota) nextUtcMidnight() else System.currentTimeMillis() + 30 * 60_000L
    }

    /** JSON d'une route ; une erreur du Worker lui-même (404…) n'est pas une
     *  panne : un autre répondrait pareil, on s'arrête là. */
    suspend fun json(path: String): JSONObject? {
        val bases = ordered()
        val unreachable = mutableListOf<String>()
        for (base in bases) {
            val r = Http.get(base + path, timeout = 20)
            if (r == null) {
                Logger.warn("WORKER", "Worker injoignable", base)
                unreachable += base
                continue
            }
            if (r.code == 429 || r.code in 502..504) {
                markFailure(base, r.body, bases.size)
                Logger.warn("WORKER", if (r.body.contains("1027")) "Quota du jour atteint (1027)" else "Worker indisponible (${r.code})", base)
                continue
            }
            for (b in unreachable) downUntil[b] = System.currentTimeMillis() + 30 * 60_000L
            if (base != bases.first()) Logger.info("WORKER", "Réponse d'un autre Worker", base)
            if (r.code != 200) return null
            return parseJson(r.body)
        }
        return null
    }

    fun proxiedPlaylist(url: String, base: String) = "$base/api/proxy?url=${Http.encode(url)}&isVod=false"

    /** Worker qui sert vraiment cette playlist par son proxy ; null si aucun. */
    suspend fun workingProxyBase(url: String): String? {
        val bases = ordered()
        for (base in bases) {
            val r = Http.get(proxiedPlaylist(url, base), timeout = 15) ?: continue
            if (r.code == 429 || r.code in 502..504) {
                markFailure(base, r.body, bases.size)
                continue
            }
            return if (r.code == 200 && r.body.startsWith("#EXTM3U")) base else null
        }
        return null
    }
}
