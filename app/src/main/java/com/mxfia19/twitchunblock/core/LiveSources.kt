package com.mxfia19.twitchunblock.core

import java.util.Collections

/**
 * Ordre des sources du direct (série 6), comme liveSourceOrder (iOS) et le
 * site : chaque source à la place choisie, Ma source et Mon proxy (série 7)
 * compris quand ils sont réglés, le Worker toujours en dernier. Avant lui, seule la première source
 * se choisissait (AppState.liveSource, gardée pour la TV et la sauvegarde) :
 * un ordre jamais rangé part d'elle.
 */
object LiveSources {
    /** Ordre rangé (identifiants séparés par des virgules) ; vide : jamais rangé. */
    val saved = stringPref("live_source_order", "")

    /** Chaînes lues sans le compte jusqu'au prochain lancement : une source
     *  choisie dans le lecteur pendant une lecture par le compte (comme l'iPhone). */
    private val accountSkipped: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun skipsAccount(login: String): Boolean = login.lowercase() in accountSkipped

    fun skipAccount(login: String) {
        if (accountSkipped.add(login.lowercase())) Logger.info("LIVE", "Compte mis de côté pour $login", "jusqu'au prochain lancement")
    }

    /** Ordre essayé par getLive : celui des réglages, puis le Worker. */
    fun steps(): List<String> = order() + "worker"

    /** Ordre des réglages. Une première source choisie ailleurs depuis le
     *  dernier rangement (TV, sauvegarde importée) passe en tête ;
     *  « Automatique » remet l'ordre par défaut. */
    fun order(): List<String> {
        val chosen = AppState.liveSource
        var list = parse(saved.value)
        if (list.isNotEmpty()) {
            val first = list.firstOrNull { it in Config.LIVE_SOURCES }
            if (chosen == "auto") {
                list = emptyList()
            } else if (chosen in Config.LIVE_SOURCES && chosen != first) {
                // Sources à soi (Ma source, Mon proxy) gardées devant.
                val head = list.takeWhile { it == "custom" || it == Config.MY_PROXY_SOURCE }
                list = head + chosen + list.filter { it != chosen && it !in head }
            }
        } else if (chosen in Config.LIVE_SOURCES) {
            list = listOf(chosen)
        }
        return complete(list)
    }

    /** Ordre par défaut : l'automatique, Ma source puis Mon proxy en tête quand ils sont réglés. */
    fun defaultOrder(): List<String> = complete(emptyList())

    /** Monte (−1) ou descend (+1) une source d'un cran. */
    fun move(id: String, delta: Int) {
        val list = order().toMutableList()
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]
        list[j] = id
        save(list)
    }

    /** En tête de l'ordre (choisie dans le menu du lecteur). */
    fun prefer(id: String) {
        val list = order()
        if (list.firstOrNull() == id || id !in list) return
        save(listOf(id) + list.filter { it != id })
        Logger.info("SETTINGS", "Source du direct en tête", id)
    }

    fun reset() {
        saved.set("")
        if (AppState.liveSource != "auto") AppState.updateLiveSource("auto")
        Logger.info("SETTINGS", "Ordre des sources", "par défaut")
    }

    /** Ordre reçu (sauvegarde, synchro) : complété, comme le reste. */
    fun setOrder(list: List<String>) {
        if (list.none { it in Config.LIVE_SOURCES || it == "custom" || it == Config.MY_PROXY_SOURCE }) return
        save(complete(list))
    }

    /** Ma source ajoutée ou changée : en tête (comme l'iPhone et le site) ;
     *  retirée : enlevée de l'ordre rangé. */
    fun onCustomChanged() {
        val list = parse(saved.value)
        if (list.isEmpty()) return
        if (StreamPrefs.customSourceHost() != null) prefer("custom")
        else saved.set(list.filter { it != "custom" }.joinToString(","))
    }

    /** Mon proxy réglé ou changé : en tête de l'ordre rangé ; retiré : enlevé. */
    fun onMyProxyChanged() {
        val list = parse(saved.value)
        if (list.isEmpty()) return
        if (MyProxy.configured) prefer(Config.MY_PROXY_SOURCE)
        else saved.set(list.filter { it != Config.MY_PROXY_SOURCE }.joinToString(","))
    }

    private fun save(list: List<String>) {
        saved.set(list.joinToString(","))
        // Première vraie source : celle que lisent la TV et la sauvegarde.
        val first = list.firstOrNull { it in Config.LIVE_SOURCES } ?: "auto"
        if (AppState.liveSource != first) AppState.updateLiveSource(first)
    }

    private fun parse(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Sources inconnues retirées, doublons aussi ; Ma source réglée mais
     *  jamais rangée : en tête, Mon proxy réglé mais jamais rangé : juste
     *  après elle (sinon en tête) ; chaque source oubliée juste après celle
     *  qu'elle secourt, sinon à la fin. Comme completeLiveSourceOrder (iOS).
     *  Un ordre qui contient un proxy retiré reprend l'ordre automatique (Ma
     *  source et Mon proxy gardés). */
    private fun complete(saved: List<String>): List<String> {
        val custom = StreamPrefs.customSourceHost() != null
        val proxy = MyProxy.configured
        val mine = setOf("custom", Config.MY_PROXY_SOURCE)
        val kept = if (saved.any { it in Config.RETIRED_LIVE_SOURCES }) saved.filter { it in mine } else saved
        val order = mutableListOf<String>()
        for (id in kept) {
            val known = id in Config.LIVE_SOURCES || (custom && id == "custom") || (proxy && id == Config.MY_PROXY_SOURCE)
            if (known && id !in order) order += id
        }
        if (custom && "custom" !in order) order.add(0, "custom")
        if (proxy && Config.MY_PROXY_SOURCE !in order) order.add(if (order.firstOrNull() == "custom") 1 else 0, Config.MY_PROXY_SOURCE)
        for (id in Config.LIVE_SOURCES) {
            if (id in order) continue
            val parent = Config.LIVE_SOURCE_BACKUP_OF[id]
            val i = if (parent != null) order.indexOf(parent) else -1
            if (i >= 0) order.add(i + 1, id) else order.add(id)
        }
        return order
    }
}
