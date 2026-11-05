package com.mxfia19.twitchunblock.core

import com.mxfia19.twitchunblock.notify.NotifChannels

/**
 * Réglages de la navigation (série 5) : chaînes épinglées, lives en liste,
 * onglets (ordre, masqués, onglet au lancement), parties de l'accueil,
 * catégories suivies (taille, ordre). Comme UICustomization (iOS).
 */
object BrowsePrefs {
    /** Onglets du téléphone, dans l'ordre par défaut : Découvrir après
     *  l'accueil, comme sur l'iPhone. */
    val defaultTabs = listOf("home", "discover", "search", "following", "library")
    /** Parties de l'accueil qu'on peut masquer, dans leur ordre. */
    val homeParts = listOf("announcement", "continue", "followed", "categories", "top")

    /** Épinglées, dans l'ordre d'épinglage (300 au plus, comme l'iPhone). */
    val pinned = wordsPref("pinned_streamers", 300)
    /** Directs épinglés triés entre eux par spectateurs ; sinon dans l'ordre d'épinglage. */
    val pinnedByViewers = boolPref("pinned_by_viewers", true)
    /** Lives en liste (miniature à gauche) au lieu de la grille. */
    val homeList = boolPref("home_list", false)
    val tabOrder = wordsPref("tab_order", 10)
    val hiddenTabs = wordsPref("tabs_hidden", 10)
    val launchTab = stringPref("launch_tab", "home")
    val hiddenHome = wordsPref("home_hidden", 10)
    /** Catégories suivies : jaquettes plus petites. */
    val compactCategories = boolPref("followed_cats_compact", false)
    /** Leur ordre : « viewers » (les plus regardées d'abord) ou « name » (A → Z). */
    val categorySort = stringPref("followed_cats_sort", "viewers")

    /** L'ordre choisi (éléments connus, sans doublon), puis les nouveaux :
     *  un onglet ajouté par une version suivante prend place à la fin. */
    fun merged(order: List<String>, all: List<String>): List<String> {
        val out = order.filter { it in all }.distinct().toMutableList()
        for (x in all) if (x !in out) out += x
        return out
    }

    val tabs: List<String> get() = merged(tabOrder.value, defaultTabs)

    /** Au moins un onglet : l'accueil si tout était masqué. */
    val visibleTabs: List<String> get() = tabs.filter { it !in hiddenTabs.value }.ifEmpty { listOf("home") }

    val startTab: String get() = launchTab.value.takeIf { it in visibleTabs } ?: visibleTabs.first()

    fun showsHome(part: String) = part !in hiddenHome.value

    fun setHomeShown(part: String, on: Boolean) {
        hiddenHome.set(if (on) hiddenHome.value - part else hiddenHome.value + part)
    }

    fun setTabShown(key: String, on: Boolean) {
        val hidden = if (on) hiddenTabs.value - key else hiddenTabs.value + key
        // Il reste toujours un onglet.
        if (tabs.all { it in hidden }) return
        hiddenTabs.set(hidden)
    }

    /** Onglet déplacé d'un cran (`delta` : -1 vers le haut, 1 vers le bas). */
    fun moveTab(key: String, delta: Int) {
        val list = tabs.toMutableList()
        val i = list.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]
        list[j] = key
        tabOrder.set(list)
    }

    /** « Rétablir la disposition d'origine » : onglets et accueil. */
    fun reset() {
        tabOrder.set(emptyList())
        hiddenTabs.set(emptyList())
        launchTab.set("home")
        hiddenHome.set(emptyList())
        pinnedByViewers.set(true)
        compactCategories.set(false)
        categorySort.set("viewers")
    }
}

/** Chaînes épinglées en tête des suivies ; rang : ordre d'épinglage. Comme
 *  pinnedStreamers (iOS). */
object Pins {
    fun isPinned(login: String) = login.lowercase() in BrowsePrefs.pinned.value

    fun rank(login: String): Int? = BrowsePrefs.pinned.value.indexOf(login.lowercase()).takeIf { it >= 0 }

    fun toggle(login: String) {
        val l = login.lowercase()
        if (!Links.isValidLogin(l)) return
        val list = BrowsePrefs.pinned.value
        BrowsePrefs.pinned.set(if (l in list) list - l else list + l)
        Logger.info("PINS", if (l in list) "Chaîne désépinglée" else "Chaîne épinglée", l)
    }

    /** Deux directs : épinglés d'abord, entre eux par spectateurs (réglage)
     *  ou dans l'ordre d'épinglage — à égalité de spectateurs aussi, pour
     *  qu'ils ne s'échangent pas d'une lecture à l'autre. */
    private fun compareLive(loginA: String, viewersA: Int, loginB: String, viewersB: Int): Int {
        val ra = rank(loginA)
        val rb = rank(loginB)
        return when {
            ra != null && rb != null ->
                if (BrowsePrefs.pinnedByViewers.value && viewersA != viewersB) viewersB.compareTo(viewersA) else ra.compareTo(rb)
            ra != null -> -1
            rb != null -> 1
            else -> viewersB.compareTo(viewersA)
        }
    }

    fun sortStreams(list: List<TwitchStream>): List<TwitchStream> =
        list.sortedWith { a, b -> compareLive(a.login, a.viewers, b.login, b.viewers) }

    /** Chaînes suivies : en direct d'abord (épinglées en tête), puis hors
     *  ligne (épinglées en tête, puis la plus récemment vue en direct). */
    fun sortBriefs(list: List<ChannelBrief>): List<ChannelBrief> = list.sortedWith { a, b ->
        val sa = a.stream
        val sb = b.stream
        when {
            sa != null && sb != null -> compareLive(a.login, sa.viewers, b.login, sb.viewers)
            sa != null -> -1
            sb != null -> 1
            else -> {
                val ra = rank(a.login)
                val rb = rank(b.login)
                when {
                    ra != null && rb != null -> ra.compareTo(rb)
                    ra != null -> -1
                    rb != null -> 1
                    else -> (b.lastEnd ?: 0L).compareTo(a.lastEnd ?: 0L)
                }
            }
        }
    }
}

/** Ce qu'on suit ou épingle n'est jamais masqué (comme l'iPhone) : ni la
 *  chaîne, ni les directs d'une catégorie suivie. */
object BrowseRules {
    fun follows(login: String) = NotifChannels.followed(login) || Pins.isPinned(login)

    fun shows(s: TwitchStream, followed: Set<String> = emptySet()): Boolean {
        if (s.login.lowercase() in followed || follows(s.login)) return true
        if (Hidden.hidesChannel(s.login)) return false
        return !Hidden.hidesCategory(s.gameId) || FollowedCategories.isFollowed(s.gameId)
    }
}
