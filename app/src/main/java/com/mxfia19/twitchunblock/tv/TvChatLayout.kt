package com.mxfia19.twitchunblock.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.stringPref
import com.mxfia19.twitchunblock.ui.TU
import org.json.JSONObject

/** Place du chat sur l'image : côté (left, right) et hauteur (full, top,
 *  bottom), enregistrée « right-full »… comme l'Apple TV (TVChatLayout). */
data class TvChatPlace(val side: String = "right", val height: String = "full") {
    val raw: String get() = "$side-$height"

    val alignment: Alignment get() = when (height) {
        "top" -> if (side == "left") Alignment.TopStart else Alignment.TopEnd
        "bottom" -> if (side == "left") Alignment.BottomStart else Alignment.BottomEnd
        else -> if (side == "left") Alignment.CenterStart else Alignment.CenterEnd
    }

    companion object {
        fun parse(raw: String?): TvChatPlace? {
            val parts = raw?.split("-") ?: return null
            if (parts.size != 2 || parts[0] !in listOf("left", "right") || parts[1] !in listOf("full", "top", "bottom")) return null
            return TvChatPlace(parts[0], parts[1])
        }
    }
}

/**
 * Chat du lecteur TV, comme l'Apple TV : affiché ou masqué et sa place, par
 * défaut (Compte) et pour une chaîne (menu Chat du lecteur) — un streamer qui
 * incruste déjà son chat n'a pas besoin du nôtre. Le choix d'une chaîne
 * disparaît quand il redevient égal au défaut. Affiché par défaut :
 * AppState.showChat. Mêmes clés que l'Apple TV.
 */
object TvChatPrefs {
    private val defaultPlace = stringPref("tv_chat_layout", "right-full")
    /** login → place (« left-top »…). */
    private val places = mutableStateMapOf<String, String>().also { m ->
        val o = read("tv_chat_layouts")
        o.keys().forEach { k -> o.str(k)?.takeIf { TvChatPlace.parse(it) != null }?.let { m[k] = it } }
    }
    /** login → affiché. */
    private val shownBy = mutableStateMapOf<String, Boolean>().also { m ->
        val o = read("tv_chat_shown")
        o.keys().forEach { k -> o.bool(k)?.let { m[k] = it } }
    }

    private fun read(key: String): JSONObject = parseJson(AppState.prefs.getString(key, null)) ?: JSONObject()

    private fun key(login: String?) = login?.lowercase()?.takeIf { it.isNotEmpty() }

    val place: TvChatPlace get() = TvChatPlace.parse(defaultPlace.value) ?: TvChatPlace()

    fun setDefaultPlace(p: TvChatPlace) = defaultPlace.set(p.raw)

    fun place(login: String?): TvChatPlace = key(login)?.let { places[it] }?.let { TvChatPlace.parse(it) } ?: place

    fun shown(login: String?): Boolean = key(login)?.let { shownBy[it] } ?: AppState.showChat

    /** Sans chaîne connue (VOD pas encore lue) : le réglage par défaut. */
    fun setPlace(login: String?, p: TvChatPlace) {
        val k = key(login) ?: return setDefaultPlace(p)
        if (p == place) places.remove(k) else {
            places[k] = p.raw
            trim(places, k)
        }
        save()
    }

    fun setShown(login: String?, on: Boolean) {
        val k = key(login) ?: return AppState.updateShowChat(on)
        if (on == AppState.showChat) shownBy.remove(k) else {
            shownBy[k] = on
            trim(shownBy, k)
        }
        save()
    }

    fun hasOwn(login: String?): Boolean = key(login)?.let { it in places || it in shownBy } ?: false

    /** Chaînes avec leurs propres réglages du chat (Compte). */
    val ownCount: Int get() = (places.keys + shownBy.keys).size

    /** « Garder pour toutes les chaînes » : place et affichage de cette chaîne
     *  deviennent ceux par défaut. */
    fun makeDefault(login: String?) {
        val p = place(login)
        val on = shown(login)
        key(login)?.let {
            places.remove(it)
            shownBy.remove(it)
        }
        setDefaultPlace(p)
        AppState.updateShowChat(on)
        save()
    }

    /** « Revenir au réglage par défaut » pour cette chaîne. */
    fun reset(login: String?) {
        val k = key(login) ?: return
        places.remove(k)
        shownBy.remove(k)
        save()
    }

    fun forgetAll() {
        places.clear()
        shownBy.clear()
        save()
    }

    /** 200 chaînes suffisent (comme l'Apple TV) : une autre est oubliée. */
    private fun <V> trim(m: MutableMap<String, V>, keep: String) {
        while (m.size > 200) {
            val other = m.keys.firstOrNull { it != keep } ?: break
            m.remove(other)
        }
    }

    private fun save() {
        val layouts = JSONObject()
        places.forEach { (k, v) -> layouts.put(k, v) }
        val shown = JSONObject()
        shownBy.forEach { (k, v) -> shown.put(k, v) }
        AppState.prefs.edit()
            .putString("tv_chat_layouts", layouts.toString())
            .putString("tv_chat_shown", shown.toString())
            .apply()
    }
}

/** Chat posé sur l'image, à sa place : un côté, sur toute la hauteur ou une
 *  moitié ; les messages les plus anciens s'effacent en haut. `bottomInset` :
 *  place laissée aux commandes du lecteur quand elles sont affichées. Ne
 *  prend jamais le focus. */
@Composable
fun BoxScope.TvChatOverlay(place: TvChatPlace, bottomInset: Dp, content: @Composable BoxScope.() -> Unit) {
    val half = place.height != "full"
    Box(Modifier.matchParentSize().padding(bottom = if (place.height == "top") 0.dp else bottomInset)) {
        Box(
            Modifier.align(place.alignment).width(320.dp).fillMaxHeight(if (half) 0.5f else 1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black), blendMode = BlendMode.DstIn)
                }
                .background(Color(0x80000000))
                .padding(vertical = if (half) 12.dp else 20.dp),
            content = content,
        )
    }
}

/** Menu Chat du lecteur : affiché ou masqué, côté et hauteur, retenus pour
 *  la chaîne regardée ; ses propres réglages gardés partout, ou oubliés. Une
 *  place choisie affiche le chat s'il était masqué, pour la voir. */
@Composable
fun TvChatMenu(login: String?, first: FocusRequester, onDone: () -> Unit) {
    val shown = TvChatPrefs.shown(login)
    val place = TvChatPrefs.place(login)
    fun mark(on: Boolean) = if (on) "  ✓" else ""
    fun move(p: TvChatPlace) {
        TvChatPrefs.setPlace(login, p)
        if (!shown) TvChatPrefs.setShown(login, true)
        onDone()
    }
    val wide = Modifier.fillMaxWidth()
    Text(AppState.t("tv_chat"), color = TU.muted, fontSize = 14.sp)
    TvButton(AppState.t("tv_chat_show") + mark(shown), selected = shown, modifier = Modifier.focusRequester(first).fillMaxWidth()) {
        TvChatPrefs.setShown(login, !shown)
        onDone()
    }
    Text(AppState.t("tv_chat_side"), color = TU.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    TvButton(AppState.t("tv_chat_left") + mark(place.side == "left"), selected = place.side == "left", modifier = wide) {
        move(place.copy(side = "left"))
    }
    TvButton(AppState.t("tv_chat_right") + mark(place.side == "right"), selected = place.side == "right", modifier = wide) {
        move(place.copy(side = "right"))
    }
    Text(AppState.t("tv_chat_height"), color = TU.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    TvButton(AppState.t("tv_chat_full") + mark(place.height == "full"), selected = place.height == "full", modifier = wide) {
        move(place.copy(height = "full"))
    }
    TvButton(AppState.t("tv_chat_top") + mark(place.height == "top"), selected = place.height == "top", modifier = wide) {
        move(place.copy(height = "top"))
    }
    TvButton(AppState.t("tv_chat_bottom") + mark(place.height == "bottom"), selected = place.height == "bottom", modifier = wide) {
        move(place.copy(height = "bottom"))
    }
    if (TvChatPrefs.hasOwn(login)) {
        TvButton(AppState.t("tv_chat_all"), modifier = wide.padding(top = 6.dp)) {
            TvChatPrefs.makeDefault(login)
            onDone()
        }
        TvButton(AppState.t("tv_chat_reset"), modifier = wide) {
            TvChatPrefs.reset(login)
            onDone()
        }
    }
}
