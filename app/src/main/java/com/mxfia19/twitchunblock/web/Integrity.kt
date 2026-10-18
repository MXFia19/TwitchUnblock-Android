package com.mxfia19.twitchunblock.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.int
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Jeton « Client-Integrity » de Twitch, exigé par les mutations (coffres,
 * rachats, paris, votes, drops, suivre) : obtenu par twitch.tv lui-même,
 * dans une page cachée (le script anti-robots de Twitch y signe la
 * requête), puis réutilisé en natif jusqu'à son expiration (~1 h). Un seul
 * à la fois. Comme TwitchWebGQL.mintIntegrity (iOS).
 */
object Integrity {
    class Token(val token: String, val deviceId: String, val expiry: Long, val forToken: String)

    private lateinit var app: Context
    private val mutex = Mutex()
    @Volatile
    private var cached: Token? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun invalidate() {
        cached = null
    }

    /** Jeton valide pour cette session web (fabriqué si besoin), ou null. */
    suspend fun get(webToken: String): Token? = mutex.withLock {
        cached?.let { c ->
            if (c.forToken == webToken && c.expiry > System.currentTimeMillis() + 60_000) return@withLock c
        }
        Logger.info("INTEGRITY", "Jeton d'intégrité…", "page twitch.tv cachée, quelques secondes")
        val fresh = withTimeoutOrNull(30_000) { mint(webToken) }
        if (fresh == null) Logger.warn("INTEGRITY", "Jeton d'intégrité indisponible")
        else Logger.success("INTEGRITY", "Jeton d'intégrité obtenu")
        cached = fresh
        fresh
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun mint(webToken: String): Token? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val web = WebView(app)
            var finished = false
            var started = false

            fun finish(result: Token?) {
                if (finished) return
                finished = true
                main.post {
                    try {
                        web.stopLoading()
                        web.destroy()
                    } catch (e: Exception) {
                    }
                }
                if (cont.isActive) cont.resume(result)
            }

            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.userAgentString = WebSession.UA
            web.addJavascriptInterface(IntegrityBridge { text -> main.post { finish(parse(text, webToken)) } }, "TUBridge")

            fun request() {
                if (started || finished) return
                started = true
                // Identifiant d'appareil de twitch.tv (cookie unique_id) : le
                // jeton y est lié, les mutations l'enverront avec lui.
                val device = WebSession.cookies()["unique_id"]?.takeIf { it.isNotEmpty() } ?: WebSession.deviceId
                val js = """
                    (async () => {
                      try {
                        const r = await fetch('https://gql.twitch.tv/integrity', { method: 'POST', headers: {
                          'Client-ID': ${JSONObject.quote(WebSession.CLIENT_ID)},
                          'Authorization': ${JSONObject.quote("OAuth $webToken")},
                          'X-Device-Id': ${JSONObject.quote(device)} } });
                        const body = await r.text();
                        TUBridge.done(JSON.stringify({ status: r.status, body: body, device: ${JSONObject.quote(device)} }));
                      } catch (e) {
                        TUBridge.done(JSON.stringify({ error: String(e) }));
                      }
                    })();
                """.trimIndent()
                web.evaluateJavascript(js, null)
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Le script anti-robots de twitch.tv se met en place après
                    // le chargement : 3 s avant la requête.
                    main.postDelayed({ request() }, 3_000)
                }
            }
            // Page jamais finie (réseau lent) : la requête part quand même.
            main.postDelayed({ request() }, 15_000)
            main.postDelayed({ finish(null) }, 28_000)
            cont.invokeOnCancellation { main.post { finish(null) } }
            web.loadUrl("https://www.twitch.tv/")
        }
    }

    private fun parse(text: String, webToken: String): Token? {
        val outer = parseJson(text) ?: return null
        outer.str("error")?.let {
            Logger.warn("INTEGRITY", "Requête refusée", it)
            return null
        }
        val status = outer.int("status") ?: 0
        val body = parseJson(outer.str("body"))
        val token = body?.str("token")
        if (status != 200 || token.isNullOrEmpty()) {
            Logger.warn("INTEGRITY", "Réponse inattendue", "HTTP $status")
            return null
        }
        val expiry = body.long("expiration")?.takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + 30 * 60_000L)
        return Token(token, outer.str("device") ?: WebSession.deviceId, expiry, webToken)
    }
}

/** Réponse de la page cachée (appelée par son script, hors du fil principal). */
class IntegrityBridge(private val onDone: (String) -> Unit) {
    @JavascriptInterface
    fun done(text: String) {
        onDone(text)
    }
}
