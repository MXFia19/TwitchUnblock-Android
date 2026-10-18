package com.mxfia19.twitchunblock.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Requêtes GQL publiques de Twitch (sans compte), comme TwitchAPI.swift. */
object Gql {
    private const val URL = "https://gql.twitch.tv/gql"

    /** Texte sûr dans une requête GQL écrite à la main. */
    fun str(s: String): String {
        val out = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n', '\r', '\t' -> out.append(' ')
                else -> if (ch.code >= 0x20) out.append(ch)
            }
        }
        return out.toString()
    }

    private fun headers(clientId: String) = mapOf(
        "Client-ID" to clientId,
        "User-Agent" to Config.USER_AGENT,
        "Device-ID" to "MkMq8a9${Random.nextInt(100000, 999999)}",
    )

    /** Réponse entière (data et errors) ; null sans réponse lisible. */
    suspend fun raw(query: String, variables: JSONObject? = null, timeout: Long = 10): JSONObject? {
        val body = JSONObject().put("query", query)
        if (variables != null) body.put("variables", variables)
        val r = Http.postJson(URL, body.toString(), headers(Config.GQL_CLIENT_ID), timeout) ?: return null
        if (r.code != 200) return null
        return parseJson(r.body)
    }

    /** `data` d'une requête avec variables ; null sans réponse (réseau). */
    suspend fun request(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        val json = raw(query, variables, timeout = 15)
        if (json == null) {
            Logger.warn("GQL", "Requête sans réponse (réseau)", query.take(60))
            return null
        }
        if (json.has("errors")) Logger.warn("GQL", "Requête refusée", errorSummary(json))
        return json.obj("data")
    }

    /** Messages d'erreur d'une réponse (codes compris), pour le journal. */
    fun errorSummary(json: JSONObject?): String {
        val errors = json?.arr("errors")?.objects().orEmpty()
        if (errors.isEmpty()) return "réponse inattendue"
        return errors.take(3).joinToString(" | ") { e ->
            listOfNotNull(e.str("message"), e.obj("extensions")?.str("code")).joinToString(" · ")
        }
    }

    private fun integrityRefused(json: JSONObject): Boolean =
        json.arr("errors")?.objects().orEmpty().any { e ->
            e.obj("extensions")?.str("code") == "IntegrityCheckFailed" ||
                (e.str("message")?.lowercase()?.contains("integrity check") ?: false)
        }

    /** Page suivante d'une liste, avec le Client-ID d'Android. null : panne ;
     *  `second` vrai : Twitch refuse (intégrité, 400/401/403). */
    suspend fun next(query: String, variables: JSONObject): Pair<JSONObject?, Boolean>? {
        val body = JSONObject().put("query", query).put("variables", variables)
        val r = Http.postJson(URL, body.toString(), mapOf("Client-ID" to Config.GQL_NEXT_CLIENT_ID), 15) ?: return null
        if (r.code == 400 || r.code == 401 || r.code == 403) {
            Logger.warn("GQL", "Page suivante refusée (HTTP ${r.code})")
            return Pair(null, true)
        }
        if (r.code != 200) return null
        val json = parseJson(r.body) ?: return null
        if (integrityRefused(json)) {
            Logger.warn("GQL", "Page suivante refusée (intégrité)")
            return Pair(null, true)
        }
        return Pair(json.obj("data"), false)
    }

    /** Une page de liste : la 1ʳᵉ (`after` nul) par le Client-ID web, la
     *  suite par celui d'Android, `after` passé en variable `$after`. */
    suspend fun listPage(query: String, variables: JSONObject, after: String?): Pair<JSONObject?, Boolean>? {
        if (after == null) {
            val d = request(query, variables) ?: return null
            return Pair(d, false)
        }
        val vars = JSONObject(variables.toString()).put("after", after)
        return next(query, vars)
    }

    /** Curseur de la page suivante d'une connexion (seulement s'il y a une suite). */
    fun nextCursor(connection: JSONObject?): String? {
        if (connection?.obj("pageInfo")?.bool("hasNextPage") != true) return null
        val edges = connection?.arr("edges") ?: return null
        if (edges.length() == 0) return null
        return edges.optJSONObject(edges.length() - 1)?.str("cursor")?.takeIf { it.isNotEmpty() }
    }

    fun vars(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> {}
                is List<*> -> o.put(k, JSONArray(v))
                else -> o.put(k, v)
            }
        }
        return o
    }
}
