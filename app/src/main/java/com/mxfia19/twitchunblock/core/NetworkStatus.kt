package com.mxfia19.twitchunblock.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Données mobiles ou non : la qualité de lancement en dépend (comme iOS). */
object NetworkStatus {
    @Volatile
    var isCellular: Boolean = false
        private set

    fun start(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    if (cellular != isCellular) {
                        isCellular = cellular
                        Logger.info("NET", "Connexion : " + if (cellular) "données mobiles" else "Wi-Fi ou filaire")
                    }
                }
            })
        } catch (e: Exception) {
            Logger.warn("NET", "Suivi du réseau indisponible", e.message)
        }
    }
}
