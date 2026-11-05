package com.mxfia19.twitchunblock.ui

import androidx.compose.runtime.Composable
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs

/** Réglages de la navigation : accueil, listes, onglets (groupe Général). */
@Composable
fun BrowseSettings(nav: Navigator) {
    Toggle(AppState.t("home_list"), AppState.t("br_home_list_sub"), BrowsePrefs.homeList.value) { BrowsePrefs.homeList.set(it) }
    LinkRow(AppState.t("cust_title") + " · " + AppState.t("br_cust_open")) { nav.push(Screen.Customize) }
}
