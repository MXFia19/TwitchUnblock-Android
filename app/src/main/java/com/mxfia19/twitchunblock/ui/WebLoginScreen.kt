package com.mxfia19.twitchunblock.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.delay

/**
 * Connexion à twitch.tv dans l'app : la session web (cookie « auth-token »)
 * est prise dès qu'elle apparaît. Un autre compte que celui de l'app est
 * refusé (cookies effacés, page rechargée). Comme TwitchWebAuthManager (iOS).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(nav: Navigator) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun check() {
        if (done) return
        val cookies = WebSession.cookies()
        val token = cookies["auth-token"]?.takeIf { it.length >= 20 } ?: return
        val login = cookies["login"]?.lowercase()
        val app = AppState.user?.login?.lowercase()
        if (app != null && login != null && login != app) {
            // Autre compte : refusé, on repart d'une page de connexion vide.
            Logger.warn("WEB", "Connexion refusée : autre compte", "@$login ≠ @$app")
            wrong = login
            WebSession.clearCookies()
            web?.loadUrl("https://www.twitch.tv/login")
            return
        }
        done = true
        WebSession.save(token, login)
        nav.pop()
    }

    // Cookie posé sans changement de page (connexion en un écran) : relu
    // régulièrement.
    LaunchedEffect(Unit) {
        while (!done) {
            delay(1_200)
            check()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.stopLoading()
            web?.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(TU.bg).imePadding()) {
        PageHeader(AppState.t("account_web"), nav)
        Text(AppState.t("web_login_sub"), color = TU.muted, fontSize = 13.sp,
             modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        wrong?.let { w ->
            val a = AppState.user?.login ?: ""
            Text(AppState.t("web_wrong_account").replace("{w}", w).replace("{a}", a), color = TU.danger, fontSize = 13.sp,
                 modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = WebSession.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // Hors de twitch.tv (liens de la page, pubs) : ouvert à
                            // l'extérieur, pas dans la page qui porte la session.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame || WebSession.isTwitchHost(request.url.host)) return false
                                try {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                } catch (e: Exception) {
                                    // Aucune app pour ce lien : il n'est pas ouvert.
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                check()
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    failed = true
                                    loading = false
                                }
                            }
                        }
                        loadUrl("https://www.twitch.tv/login")
                        web = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) {
                Column(Modifier.fillMaxSize().background(TU.bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppState.t("web_load_failed"), color = TU.text, fontSize = 15.sp)
                    Button(onClick = { failed = false; web?.loadUrl("https://www.twitch.tv/login") },
                           colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                           modifier = Modifier.padding(top = 12.dp)) { Text(AppState.t("retry")) }
                }
            } else if (loading) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TU.primary)
                    Text(AppState.t("web_loading"), color = TU.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
