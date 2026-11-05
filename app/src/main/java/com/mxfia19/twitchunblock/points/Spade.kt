package com.mxfia19.twitchunblock.points

import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.int
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import org.json.JSONObject

/**
 * Envoi de la « minute regardée » à l'adresse de statistiques de Twitch
 * (spade), en direct ou par le Worker (POST /api/spade). Un DNS filtrant
 * (AdGuard, NextDNS) ou un bloqueur coupe souvent cette adresse : aucune
 * minute n'était comptée, ni points passifs ni coffres. Après deux échecs
 * directs d'affilée, le Worker passe d'abord (le personnel en premier) et
 * l'envoi direct est retenté toutes les 15 min. Une ligne de journal par
 * erreur et par séance, pas une par minute. Commun à toutes les chaînes.
 * Comme sendSpade (iOS).
 */
object Spade {
    private var directFailures = 0
    /** Minutes envoyées d'abord par le Worker (direct coupé). */
    private var viaWorker = false
    private var nextDirectTry = 0L
    private const val DIRECT_RETRY = 15 * 60_000L
    private var workerNoted = false
    private val logged = HashSet<String>()
    /** Workers mis de côté : route absente (Worker d'avant), trop d'envois. */
    private val workerSkip = HashMap<String, Long>()

    @Synchronized
    private fun directFirst(): Boolean = !viaWorker || System.currentTimeMillis() >= nextDirectTry

    /** Code HTTP de Twitch, et si la minute est passée par un Worker ; null
     *  si elle n'a pu partir ni en direct, ni par un Worker. */
    suspend fun send(url: String, b64: String): Pair<Int, Boolean>? {
        val directFirst = directFirst()
        if (!directFirst) relay(url, b64)?.let { return Pair(it, true) }
        val r = Http.postForm(url, mapOf("data" to b64), timeout = 15)
        if (r != null) {
            synchronized(this) {
                directFailures = 0
                if (viaWorker) {
                    viaWorker = false
                    Logger.info("POINTS", "Envoi direct de la minute regardée rétabli")
                }
            }
            return Pair(r.code, false)
        }
        synchronized(this) {
            directFailures++
            if (viaWorker) nextDirectTry = System.currentTimeMillis() + DIRECT_RETRY
        }
        val host = try { java.net.URI(url).host } catch (e: Exception) { null }
        noteOnce("direct", "minute-watched échoué", "réseau · hôte : ${host ?: "?"}")
        // Le Worker vient déjà d'échouer pour cette minute.
        if (!directFirst) return null
        val code = relay(url, b64) ?: return null
        synchronized(this) {
            if (directFailures >= 2 && !viaWorker) {
                viaWorker = true
                nextDirectTry = System.currentTimeMillis() + DIRECT_RETRY
                if (!workerNoted) {
                    workerNoted = true
                    Logger.info("POINTS", "Minutes regardées envoyées par le Worker",
                                "envoi direct impossible · nouvel essai direct toutes les 15 min")
                }
            }
        }
        return Pair(code, true)
    }

    /** Par les Workers, dans l'ordre de la lecture (personnel, principal,
     *  secours) : code HTTP de Twitch, null si aucun ne l'a transmise. */
    private suspend fun relay(url: String, b64: String): Int? {
        val body = JSONObject().put("url", url).put("data", b64).toString()
        for (base in AppState.lookupBases) {
            if ((synchronized(this) { workerSkip[base] } ?: 0L) > System.currentTimeMillis()) continue
            val r = Http.postJson("$base/api/spade", body, timeout = 15)
            if (r == null) {
                noteOnce("worker $base", "Relais du Worker injoignable", base)
                continue
            }
            val json = parseJson(r.body)
            if (r.code == 200 && json != null && json.bool("ok") == true) json.int("status")?.let { return it }
            noteOnce("worker $base ${r.code}", "Relais du Worker refusé",
                     "HTTP ${r.code} · $base" + (json?.str("error")?.let { " · $it" } ?: ""))
            when (r.code) {
                // Adresse ou données refusées : un autre Worker dirait pareil.
                400 -> return null
                // Route absente : Worker d'avant (personnel pas à jour).
                404 -> synchronized(this) { workerSkip[base] = Long.MAX_VALUE }
                // Trop d'envois, ou quota du jour : le suivant, celui-ci plus tard.
                429 -> synchronized(this) { workerSkip[base] = System.currentTimeMillis() + 10 * 60_000L }
            }
        }
        return null
    }

    private fun noteOnce(key: String, message: String, detail: String) {
        val first = synchronized(this) { logged.add(key) }
        if (first) Logger.warn("POINTS", message, detail)
    }
}
