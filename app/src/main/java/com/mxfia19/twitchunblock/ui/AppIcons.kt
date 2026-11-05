package com.mxfia19.twitchunblock.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.R
import com.mxfia19.twitchunblock.core.Logger

/** Paquet des classes de l'app (namespace), pas l'identifiant de l'app :
 *  TU Test a le sien, mais ses alias gardent ce nom de classe. */
private val CLASS_PACKAGE: String = AppIcon::class.java.name.substringBeforeLast('.')

/**
 * Icônes au choix (Réglages → Général → Apparence), les mêmes que l'app
 * iPhone : dessins de assets/icons, mipmaps faits par la CI. Le manifeste a
 * un alias de MainActivity par icône (« .ui.Launcher<Nom> ») : seul celui
 * de l'icône choisie reste actif. `id` : nom de l'iPhone (AppIconChoice).
 * `preview` : un PNG (celle par défaut est adaptative à partir d'Android 8,
 * et Compose ne dessine pas une icône adaptative).
 */
enum class AppIcon(val id: String, val preview: Int) {
    DEFAULT("Default", R.mipmap.ic_launcher_legacy),
    PURPLE("Purple", R.mipmap.ic_launcher_purple),
    NIGHT("Night", R.mipmap.ic_launcher_night),
    LIGHT("Light", R.mipmap.ic_launcher_light),
    CONTRAST("Contrast", R.mipmap.ic_launcher_contrast),
    NEON("Neon", R.mipmap.ic_launcher_neon);

    val labelKey: String get() = "icon_${id.lowercase()}"

    fun component(context: Context) = ComponentName(context, "$CLASS_PACKAGE.Launcher$id")
}

object AppIcons {
    /** Change à chaque icône appliquée : les raccourcis de l'icône sont alors
     *  republiés (BrowseEffects), Android retirant ceux de l'alias désactivé. */
    var changes by mutableIntStateOf(0)
        private set

    /** Icône en place : l'alias actif (celle par défaut tant qu'aucun n'a
     *  été changé, l'état du manifeste). */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        for (icon in AppIcon.entries) {
            val state = try {
                pm.getComponentEnabledSetting(icon.component(context))
            } catch (e: Exception) {
                continue
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED && icon != AppIcon.DEFAULT) return icon
        }
        return AppIcon.DEFAULT
    }

    /** Change l'icône du lanceur. null si c'est fait, sinon la raison.
     *  L'alias choisi d'abord, les autres ensuite : jamais un moment sans
     *  entrée dans le lanceur. DONT_KILL_APP : l'app reste ouverte. */
    fun apply(context: Context, icon: AppIcon): String? = try {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(icon.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                      PackageManager.DONT_KILL_APP)
        for (other in AppIcon.entries) {
            if (other == icon) continue
            pm.setComponentEnabledSetting(other.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                          PackageManager.DONT_KILL_APP)
        }
        Logger.success("ICON", "Icône changée", icon.id)
        changes++
        null
    } catch (e: Exception) {
        Logger.warn("ICON", "Icône refusée par Android", "${icon.id} — ${e.javaClass.simpleName}: ${e.message}")
        e.message ?: e.javaClass.simpleName
    }
}
