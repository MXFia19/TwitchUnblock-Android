package com.mxfia19.twitchunblock.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Réponse lue : code HTTP et corps (texte). */
class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** Requête annulable ; null si pas de réponse (réseau, délai dépassé).
     *  `timeout` : secondes pour tout l'échange (0 = délais du client). */
    suspend fun send(request: Request, timeout: Long = 15): HttpResult? {
        val c = if (timeout > 0) client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build() else client
        return suspendCancellableCoroutine { cont ->
            val call = c.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { HttpResult(it.code, it.body?.string() ?: "") }
                    } catch (e: IOException) {
                        null
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    private fun builder(url: String, headers: Map<String, String>): Request.Builder? = try {
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
    } catch (e: IllegalArgumentException) {
        null   // adresse invalide
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Long = 15): HttpResult? {
        val b = builder(url, headers) ?: return null
        return send(b.build(), timeout)
    }

    suspend fun head(url: String, headers: Map<String, String> = emptyMap(), timeout: Long = 8): Int? {
        val b = builder(url, headers) ?: return null
        return send(b.head().build(), timeout)?.code
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(),
                         timeout: Long = 15): HttpResult? {
        val b = builder(url, headers) ?: return null
        return send(b.post(json.toRequestBody(JSON)).build(), timeout)
    }

    suspend fun postForm(url: String, form: Map<String, String>, timeout: Long = 15): HttpResult? {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val b = builder(url, emptyMap()) ?: return null
        return send(b.post(body).build(), timeout)
    }

    /** Valeur encodée pour une adresse (RFC 3986 : seuls A-Z a-z 0-9 - . _ ~ restent). */
    fun encode(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return out.toString()
    }
}
