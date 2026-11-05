package com.mxfia19.twitchunblock.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.BrowsePrefs
import com.mxfia19.twitchunblock.core.Lang
import com.mxfia19.twitchunblock.core.boolPref

/**
 * Visite guidée du premier lancement (téléphone seulement), revue depuis
 * Réglages → À propos (« Revoir le tutoriel »). Plus légère que celle de
 * l'iPhone : un voile et une bulle par étape, avec les vrais onglets
 * affichés dessous (l'app change d'onglet d'elle-même). Elle se passe à
 * tout moment ; vue ou passée une fois, elle ne revient plus d'elle-même.
 */
object Onboarding {
    private val done = boolPref("ob_done", false)
    var open by mutableStateOf(false)
        private set
    private var checked = false

    /** Installation neuve, jamais mise à jour : la visite au premier lancement.
     *  Une mise à jour ne la montre pas (l'app est déjà connue). */
    fun checkFirstLaunch(context: Context) {
        if (checked) return
        checked = true
        if (done.value) return
        val fresh = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        } catch (e: Exception) {
            false
        }
        if (fresh) open = true else done.set(true)
    }

    /** « Revoir le tutoriel ». */
    fun replay() {
        open = true
    }

    fun finish() {
        open = false
        done.set(true)
    }
}

private class TourStep(
    /** Onglet affiché sous le voile (null : celui où l'on est). */
    val tab: String?,
    val icon: ImageVector,
    val title: String,
    val text: String,
    val tips: List<String> = emptyList(),
    /** Bulle en haut (réglages : l'icône est en haut à droite). */
    val top: Boolean = false,
)

@Composable
fun OnboardingOverlay(nav: Navigator) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Onboarding.checkFirstLaunch(context) }
    if (!Onboarding.open) return
    var step by remember { mutableIntStateOf(0) }
    val visible = BrowsePrefs.visibleTabs
    val steps = buildList {
        add(TourStep("home", Icons.Filled.PlayArrow, AppState.t("ob_welcome_title"), AppState.t("ob_welcome_text")))
        add(TourStep("home", Icons.Filled.Home, AppState.t("ob_home_title"), AppState.t("ob_home_text")))
        if ("search" in visible) add(TourStep("search", Icons.Filled.Search, AppState.t("ob_searchtab_title"), AppState.t("ob_searchtab_text")))
        if ("library" in visible) add(TourStep("library", Icons.AutoMirrored.Filled.List, AppState.t("dl_section"), AppState.t("a6_ob_library_text")))
        add(TourStep(null, Icons.Filled.Settings, AppState.t("ob_settings_title"), AppState.t("ob_settings_text"), top = true))
        add(TourStep(null, Icons.Filled.PlayArrow, AppState.t("ob_player_title"), "",
                     tips = listOf(AppState.t("ob_tip_zoom"), AppState.t("ob_tip_more"))))
    }
    val index = step.coerceIn(0, steps.lastIndex)
    val current = steps[index]

    fun close() {
        Onboarding.finish()
        nav.tab(BrowseTabs.startScreen())
    }

    // Les vrais onglets sous le voile, étape par étape.
    LaunchedEffect(index) {
        current.tab?.let { key -> if (nav.current != BrowseTabs.screen(key) || nav.stack.size > 1) nav.tab(BrowseTabs.screen(key)) }
    }
    BackHandler { if (index > 0) step = index - 1 else close() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            // Le voile garde les touchers : seule la bulle répond.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = if (current.top) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .then(if (current.top) Modifier.statusBarsPadding().padding(top = 56.dp) else Modifier.navigationBarsPadding())
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(TU.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(TU.primaryDim), contentAlignment = Alignment.Center) {
                    Icon(current.icon, contentDescription = null, tint = TU.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(10.dp))
                Text(current.title, color = TU.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (index > 0) TextButton(onClick = { close() }) { Text(AppState.t("ob_skip"), color = TU.muted) }
            }
            if (current.text.isNotEmpty()) Text(current.text, color = TU.text, fontSize = 14.sp)
            for (tip in current.tips) {
                Row {
                    Text("•  ", color = TU.primary, fontSize = 14.sp)
                    Text(tip, color = TU.text, fontSize = 14.sp)
                }
            }
            if (index == 0) {
                // Bienvenue : la langue de l'app d'abord.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (l in Lang.entries) TabChip(l.label, AppState.lang == l) { AppState.updateLang(l) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Étapes : un point chacune.
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    for (i in steps.indices) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == index) TU.primary else TU.line))
                    }
                }
                if (index == 0) {
                    TextButton(onClick = { close() }) { Text(AppState.t("ob_skip"), color = TU.muted) }
                } else {
                    TextButton(onClick = { step = index - 1 }) { Text(AppState.t("ob_back"), color = TU.text) }
                }
                val last = index == steps.lastIndex
                Button(
                    onClick = { if (last) close() else step = index + 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
                ) {
                    Text(AppState.t(when {
                        index == 0 -> "ob_tour"
                        last -> "ob_start"
                        else -> "ob_next"
                    }))
                }
            }
        }
    }
}
