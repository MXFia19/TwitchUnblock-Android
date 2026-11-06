package com.mxfia19.twitchunblock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.mxfia19.twitchunblock.chat.ChatClient
import com.mxfia19.twitchunblock.core.AppState

/** Chat d'une chaîne hors ligne (to-do du 05/11, comme l'iPhone et le site) :
 *  Twitch le garde ouvert entre deux streams, on y lit et on y écrit comme
 *  pendant un live. Sans direct, ni points ni événements : seul le chat est
 *  lancé. Identifiant inconnu : le ROOMSTATE de Twitch le donne. */
@Composable
fun OfflineChatWindow(login: String, channelId: String?, nav: Navigator, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chat = remember(login) { ChatClient(login.lowercase(), scope) }
    DisposableEffect(chat) {
        chat.start(channelId)
        onDispose { chat.stop() }
    }
    ChatOnlyWindow(chat, onOpenChannel = { nav.push(Screen.Channel(it)) }, onLogin = { nav.push(Screen.Login) },
                   onClose = onClose, notice = AppState.t("chat_offline_open"))
}
