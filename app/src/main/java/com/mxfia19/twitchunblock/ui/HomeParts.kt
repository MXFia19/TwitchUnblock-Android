package com.mxfia19.twitchunblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.NoAccount
import com.mxfia19.twitchunblock.core.TopLanguage
import com.mxfia19.twitchunblock.web.WebSession

/** Cartes du haut de l'accueil (série 6) : invitation à se connecter, rappel
 *  de la session web, et puces du Top. Comme HomeView.swift (loginCard,
 *  webSessionBanner, topTab). */
object HomeCards {
    /** Pas de compte de l'app : l'invitation (masquable ; jamais avec « Sans compte Twitch »). */
    val showsLogin: Boolean
        get() = BrowsePrefs.showsHome("login") && !NoAccount.active && AppState.token == null

    /** Compte de l'app sans session web (ou session expirée) : le rappel, sauf
     *  pendant la semaine qui suit son ✕. */
    val showsWeb: Boolean
        get() = BrowsePrefs.showsHome("web") && !NoAccount.active && AppState.token != null && !WebSession.active &&
            System.currentTimeMillis() > (BrowsePrefs.webCardHiddenUntil.value.toLongOrNull() ?: 0L)
}

@Composable
fun LoginCard(nav: Navigator) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(TU.surface).padding(14.dp),
        verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = TU.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppState.t("login_prompt"), color = TU.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(AppState.t("login_optional_msg"), color = TU.muted, fontSize = 13.sp)
            Button(onClick = { nav.push(Screen.Login) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                Text(AppState.t("btn_login_twitch"), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WebSessionCard(nav: Navigator) {
    val expired = WebSession.expired
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(TU.surface)
            .border(1.dp, TU.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(TU.primary), contentAlignment = Alignment.Center) {
            Icon(if (expired) Icons.Filled.Warning else Icons.Filled.Star, contentDescription = null, tint = Color.White,
                 modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppState.t(if (expired) "web_expired" else "web_banner_title"), color = TU.text, fontSize = 15.sp,
                 fontWeight = FontWeight.SemiBold)
            Text(AppState.t("web_banner_msg"), color = TU.muted, fontSize = 13.sp)
            Button(onClick = { nav.push(Screen.WebLogin) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary)) {
                Text(AppState.t(if (expired) "web_reconnect" else "web_banner_btn"), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        // ✕ : masquée une semaine, puis elle revient (la session reste utile).
        IconButton(onClick = {
            BrowsePrefs.webCardHiddenUntil.set((System.currentTimeMillis() + 7 * 86_400_000L).toString())
        }) {
            Icon(Icons.Filled.Clear, contentDescription = AppState.t("close"), tint = TU.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** Top de ma langue ou du monde entier, d'un toucher. */
@Composable
fun TopLangChips(model: HomeModel) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallChip(TopLanguage.name(TopLanguage.resolved()), !model.world) { model.chooseWorld(false) }
        SmallChip(AppState.t("top_world"), model.world) { model.chooseWorld(true) }
    }
}
