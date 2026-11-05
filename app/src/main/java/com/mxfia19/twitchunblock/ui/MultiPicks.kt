package com.mxfia19.twitchunblock.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.boolPref
import com.mxfia19.twitchunblock.core.parseJsonArray
import org.json.JSONArray
import org.json.JSONObject

/** Réglages du multi-live (série 6). */
object MultiPrefs {
    /** Chat de la vidéo qui a le son à côté de la mosaïque, en paysage
     *  (comme l'iPhone : « cfg_multi_chat », activé par défaut). */
    val chat = boolPref("multi_chat", true)
}

/** Une vidéo du multi-live : un direct (sa chaîne) ou une VOD (sa chaîne si
 *  on la connaît). Comme MultiLiveEntry (iOS) ; `startMs` : instant d'un
 *  lien collé, jamais gardé. */
data class MultiEntry(
    val login: String,
    val name: String,
    val vodId: String? = null,
    val title: String? = null,
    val thumb: String? = null,
    val startMs: Long = -1,
) {
    val isVod: Boolean get() = vodId != null
    /** Une VOD n'est jamais confondue avec le direct de sa chaîne. */
    val key: String get() = vodId?.let { "vod:$it" } ?: login
    val displayName: String get() = if (vodId != null && name.isEmpty()) vodId else name
    /** « VOD · nom » pour une VOD, le nom pour un direct. */
    val label: String get() = if (isVod) "VOD · $displayName" else displayName
}

/**
 * Multi-live en préparation, gardé sur l'appareil (comme l'iPhone et le
 * site) : appui long sur une carte de live ou de VOD pour en ajouter, un
 * « + » apparaît alors sur les cartes des lives, et la barre du bas lance la
 * mosaïque (« Regarder »). La mosaïque ouverte tient la liste à jour.
 */
object MultiPicks {
    const val MAX = 4
    private const val KEY = "multi_live"
    /** Site : lien d'une mosaïque (« ?multi=chaîne,v:<id>~<s> »). */
    private const val SITE = "https://twitchunblock.vercel.app"

    val entries = mutableStateListOf<MultiEntry>()

    init {
        parseJsonArray(AppState.prefs.getString(KEY, null))?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vod = o.optString("vodId").takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
                val login = o.optString("login").lowercase()
                if (vod == null && login.isEmpty()) continue
                entries += MultiEntry(login, o.optString("name").ifEmpty { if (vod == null) login else "" }, vod,
                                      o.optString("title").takeIf { it.isNotEmpty() }, o.optString("thumb").takeIf { it.isNotEmpty() })
                if (entries.size >= MAX) break
            }
        }
    }

    val isFull: Boolean get() = entries.size >= MAX
    fun contains(login: String): Boolean = entries.any { !it.isVod && it.login == login.lowercase() }
    fun containsVod(id: String): Boolean = entries.any { it.vodId == id }

    /** Ajoute (faux si la liste est pleine) ; déjà là, rien ne change. */
    fun add(entry: MultiEntry): Boolean {
        if (entries.any { it.key == entry.key }) return true
        if (isFull) return false
        entries += entry.copy(startMs = -1)
        save()
        return true
    }

    fun remove(key: String) {
        if (entries.removeAll { it.key == key }) save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    /** Liste de la mosaïque ouverte (ajouts, retraits, places échangées). */
    fun replace(list: List<MultiEntry>) {
        val next = list.take(MAX).map { it.copy(startMs = -1) }
        if (next == entries.toList()) return
        entries.clear()
        entries.addAll(next)
        save()
    }

    /** Direct ajouté ou retiré ; faux si la liste est pleine. Avec le message. */
    fun toggleLive(context: Context, login: String, name: String): Boolean {
        val l = login.lowercase()
        val ok = if (contains(l)) { remove(l); true } else add(MultiEntry(l, name.ifEmpty { l }))
        say(context, if (!ok) "multi_full" else if (contains(l)) "multi_added" else "multi_removed")
        return ok
    }

    fun toggleVod(context: Context, id: String, title: String?, thumb: String?, name: String, login: String): Boolean {
        val ok = if (containsVod(id)) { remove("vod:$id"); true }
                 else add(MultiEntry(login.lowercase(), name, id, title, thumb))
        say(context, if (!ok) "multi_full" else if (containsVod(id)) "multi_added" else "multi_removed")
        return ok
    }

    /** Lien du site vers la mosaïque, avec l'instant de chaque VOD (`positions` : clé → s). */
    fun shareUrl(list: List<MultiEntry>, positions: Map<String, Long>): String =
        "$SITE/?multi=" + list.joinToString(",") { e ->
            val id = e.vodId ?: return@joinToString e.login
            val s = positions[e.key] ?: 0L
            if (s > 0) "v:$id~$s" else "v:$id"
        }

    private fun say(context: Context, key: String) {
        Toast.makeText(context.applicationContext, AppState.t(key), Toast.LENGTH_SHORT).show()
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("login", e.login).put("name", e.name)
            e.vodId?.let { o.put("vodId", it) }
            e.title?.let { o.put("title", it) }
            e.thumb?.let { o.put("thumb", it) }
            arr.put(o)
        }
        AppState.prefs.edit().putString(KEY, arr.toString()).apply()
        Logger.info("MULTI", "Sélection du multi-live", entries.joinToString(", ") { it.label }.ifEmpty { "vide" })
    }
}

/** « + » / « ✓ » sur une carte de live, dès qu'une première vidéo est choisie. */
@Composable
fun MultiCardToggle(login: String, name: String, modifier: Modifier = Modifier) {
    if (MultiPicks.entries.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = MultiPicks.contains(login)
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .background(if (on) TU.primary else Color(0xA6000000))
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable { MultiPicks.toggleLive(context, login, name) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (on) Icons.Filled.Check else Icons.Filled.Add,
             contentDescription = AppState.t(if (on) "multi_remove" else "multi_add"),
             tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Entrée « Ajouter au multi-live » / « Retirer du multi-live » d'un menu d'appui long. */
@Composable
fun MultiLiveMenuItem(login: String, name: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = MultiPicks.contains(login)
    DropdownMenuItem(
        text = { Text(AppState.t(if (on) "multi_remove" else if (MultiPicks.isFull) "multi_full" else "multi_add")) },
        enabled = on || !MultiPicks.isFull,
        onClick = {
            onDone()
            MultiPicks.toggleLive(context, login, name)
        },
    )
}

/** Barre du bas : les vidéos choisies, « Regarder » et ✕ pour tout retirer. */
@Composable
fun MultiPickBar(nav: Navigator, modifier: Modifier = Modifier) {
    val list = MultiPicks.entries
    if (list.isEmpty()) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(TU.surfaceHi)
            .border(1.dp, TU.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { nav.multi(null) }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BrowseIcons.grid, contentDescription = null, tint = TU.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${AppState.t("multi_title")} · ${list.size}/${MultiPicks.MAX}", color = TU.text, fontSize = 14.sp,
                 fontWeight = FontWeight.SemiBold)
            Text(list.joinToString(", ") { it.label }, color = TU.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = { nav.multi(null) }, colors = ButtonDefaults.buttonColors(containerColor = TU.primary),
               contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(AppState.t("multi_watch"), fontSize = 13.sp)
        }
        IconButton(onClick = { MultiPicks.clear() }) {
            Icon(Icons.Filled.Close, contentDescription = AppState.t("clear"), tint = TU.muted)
        }
    }
}

/** Appui long sur une carte de VOD (page d'une chaîne, Mes VODs) : ajoutée
 *  au multi-live, ou retirée (le message le dit). */
@Composable
fun multiVodToggle(id: String, title: String?, thumb: String?, login: String, name: String = login): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    return { MultiPicks.toggleVod(context, id, title, thumb, name, login) }
}
