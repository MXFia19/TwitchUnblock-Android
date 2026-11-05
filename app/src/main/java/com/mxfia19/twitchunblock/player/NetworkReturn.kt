package com.mxfia19.twitchunblock.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Logger

/**
 * Retour du réseau après une coupure (série 6) : un lecteur arrêté sur
 * « Réessayer » repart seul, une fois, deux secondes après (MiniPlayer.kt),
 * comme l'iPhone. NetworkStatus ne suit que les données mobiles. Les tuiles du
 * multi-live peuvent suivre `comebacks` de la même façon.
 */
object NetworkReturn {
    /** Retours du réseau depuis le lancement (après une perte). */
    var comebacks by mutableStateOf(0)
        private set

    private var online = true
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        online = cm.activeNetwork != null
        // Rappels sur un fil du système : l'état se lit et s'écrit sur le fil principal.
        val main = Handler(Looper.getMainLooper())
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        if (!online) {
                            online = true
                            comebacks++
                            Logger.info("NET", "Réseau revenu")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    main.post {
                        if (online) Logger.info("NET", "Réseau perdu")
                        online = false
                    }
                }
            })
        } catch (e: Exception) {
            Logger.warn("NET", "Retour du réseau non suivi", e.message)
        }
    }
}
