package com.mxfia19.twitchunblock.web

import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GQL de twitch.tv avec la session web : lectures (requêtes enregistrées ou
 * écrites), et mutations signées du jeton d'intégrité, renvoyées une fois
 * s'il est refusé. Comme TwitchGQL (iOS).
 */
object WebGql {
    private const val URL = "https://gql.twitch.tv/gql"
    private val TEXT = "text/plain;charset=UTF-8".toMediaType()

    /** Réponse : code HTTP (0 sans réponse) et premier objet du corps. */
    class Result(val code: Int, val json: JSONObject?) {
        val data: JSONObject? get() = json?.optJSONObject("data")
    }

    fun persisted(op: String, variables: JSONObject, hash: String): String =
        JSONArray().put(JSONObject()
            .put("operationName", op)
            .put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))))
            .toString()

    fun written(query: String, variables: JSONObject? = null): String =
        JSONObject().put("query", query).apply { if (variables != null) put("variables", variables) }.toString()

    suspend fun post(body: String, token: String?, integrity: Integrity.Token? = null, timeout: Long = 15): Result {
        val b = Request.Builder().url(URL)
            .header("Client-ID", WebSession.CLIENT_ID)
            .header("User-Agent", WebSession.UA)
            .header("X-Device-Id", integrity?.deviceId ?: WebSession.deviceId)
        if (!token.isNullOrEmpty()) b.header("Authorization", "OAuth $token")
        if (integrity != null) b.header("Client-Integrity", integrity.token)
        val r = Http.send(b.post(body.toRequestBody(TEXT)).build(), timeout) ?: return Result(0, null)
        if (r.code == 401 && token != null) WebSession.markExpired(token)
        if (r.code != 200) return Result(r.code, null)
        val json = try {
            val t = r.body.trim()
            if (t.startsWith("[")) JSONArray(t).optJSONObject(0) else JSONObject(t)
        } catch (e: Exception) {
            null
        }
        return Result(r.code, json)
    }

    /** Lecture enregistrée ; `token` pour les champs « self ». */
    suspend fun query(op: String, variables: JSONObject, hash: String, token: String?): Result =
        post(persisted(op, variables, hash), token)

    /** Lecture écrite à la main. */
    suspend fun raw(query: String, variables: JSONObject? = null, token: String? = null, timeout: Long = 15): Result =
        post(written(query, variables), token, timeout = timeout)

    /** Mutation (corps complet) avec le jeton d'intégrité. null : pas de
     *  jeton ou pas de réponse. */
    suspend fun mutation(body: String, token: String, tag: String, retried: Boolean = false): JSONObject? {
        val signed = Integrity.get(token) ?: return null
        val r = post(body, token, signed)
        val json = r.json
        if (json == null) {
            Logger.warn("GQL", "$tag : requête échouée", if (r.code == 0) "réseau" else "HTTP ${r.code}")
            return null
        }
        val msgs = errors(json)
        if (msgs.isNotEmpty()) {
            Logger.warn("GQL", "Erreurs dans $tag", msgs)
            if (msgs.lowercase().contains("integrity")) {
                Integrity.invalidate()
                if (!retried) return mutation(body, token, tag, retried = true)
            }
        }
        return json
    }

    /** Messages d'erreur GQL, en une ligne (vide sans erreur). */
    fun errors(json: JSONObject?): String =
        json?.arr("errors")?.objects().orEmpty().mapNotNull { it.str("message") }.joinToString(" · ")
}
