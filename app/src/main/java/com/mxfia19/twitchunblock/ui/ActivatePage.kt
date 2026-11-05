package com.mxfia19.twitchunblock.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Auth
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Boutons de la connexion par code (téléphone) : twitch.tv/activate dans le
 * navigateur, ou dans une page de l'app aux cookies de twitch.tv vidés, où
 * Twitch demande le compte au lieu de reprendre celui du navigateur. Après
 * un changement de compte ou une déconnexion, la page de l'app passe
 * d'abord (Auth.chooseAccount), comme la session privée de l'iPhone.
 */
@Composable
fun ActivateButtons(url: String) {
    val uri = LocalUriHandler.current
    var inApp by remember { mutableStateOf(false) }
    val colors = ButtonDefaults.buttonColors(containerColor = TU.primary)
    if (Auth.chooseAccount.value || AppState.user != null) {
        Button(onClick = { inApp = true }, colors = colors) { Text(AppState.t("open_activate")) }
        TextButton(onClick = { uri.openUri(url) }) { Text(AppState.t("a6_activate_browser"), color = TU.primary) }
    } else {
        Button(onClick = { uri.openUri(url) }, colors = colors) { Text(AppState.t("open_activate")) }
        TextButton(onClick = { inApp = true }) { Text(AppState.t("a6_activate_in_app"), color = TU.primary) }
    }
    if (inApp) ActivatePage(url) { inApp = false }
}

/** Cookies et stockage des pages web de l'app vidés, attendus avant de
 *  charger la page (sinon l'ancien compte pouvait encore partir avec). */
private suspend fun clearWebData(): Unit = suspendCancellableCoroutine<Unit> { cont ->
    try {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) }
    } catch (e: Exception) {
        // WebView absente ou en cours de mise à jour : rien à vider.
        if (cont.isActive) cont.resume(Unit)
    }
}

/** twitch.tv/activate (code prérempli) dans l'app, sans aucun compte de
 *  twitch.tv : on y saisit soi-même identifiants et code, l'app ne remplit
 *  rien. Fermée, les cookies sont vidés à nouveau : le compte saisi n'y
 *  reste pas (la session web des points est gardée à part, WebSession). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ActivatePage(url: String, onClose: () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(Unit) {
        clearWebData()
        Auth.chooseAccount.set(true)
        Logger.info("AUTH", "Page de connexion de l'app", "cookies de twitch.tv vidés")
        ready = true
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.stopLoading()
            web?.destroy()
            WebSession.clearCookies()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(TU.bg).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = AppState.t("close"), tint = TU.text) }
                Text(AppState.t("tv_login"), color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                     modifier = Modifier.weight(1f))
            }
            Text(AppState.t("a6_activate_page_sub"), color = TU.muted, fontSize = 12.sp,
                 modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (ready) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = WebSession.UA
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    // Hors de twitch.tv (liens de la page) : ouvert à l'extérieur.
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (!request.isForMainFrame || WebSession.isTwitchHost(request.url.host)) return false
                                        try {
                                            view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                        } catch (e: Exception) {
                                            // Aucune app pour ce lien : il n'est pas ouvert.
                                        }
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        loading = false
                                    }
                                }
                                loadUrl(url)
                                web = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!ready || loading) CircularProgressIndicator(color = TU.primary, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
