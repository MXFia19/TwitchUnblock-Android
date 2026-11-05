package com.mxfia19.twitchunblock.core

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Réglages synchronisés avec le compte Twitch, entre le site, l'app iPhone
 * et les appareils Android (Réglages → Général → « Synchroniser mes
 * réglages », allumé d'office). Même document que les autres (Worker,
 * GET/POST /api/sync/prefs, jeton du compte), à part de l'historique, en
 * sections datées (`at`, ms) ; la plus récente gagne, section par section :
 *   common : réglages communs avec le site, noms de la sauvegarde ;
 *   hidden : chaînes et catégories masquées (format de la sauvegarde) ;
 *   ios    : réglages de l'app iPhone ; Android n'y touche qu'à ceux qu'il
 *            a aussi (ios_autoClaimChest…), le reste est gardé tel quel ;
 *   pinned : chaînes épinglées, seulement si l'app en a (pinnedList).
 * Un envoi part toujours après une relecture : les clés que l'app ne
 * connaît pas (vitesse des VODs, personnalisation de l'iPhone…) repartent
 * comme le serveur les avait. Mêmes règles que SettingsSync (iOS) et le
 * site : empreinte de chaque section, date du moment quand elle change ici,
 * première rencontre qui adopte le compte (en y ajoutant les listes d'ici),
 * fusion des masquées avec la liste du dernier échange (un retrait fait
 * d'un côté tient), rien envoyé aux valeurs par défaut.
 * Pas synchronisés (propres à l'appareil) : langue, qualités, source du
 * direct, faible latence, lecture en arrière-plan, PiP, chat affiché,
 * taille du chat, téléchargements, notifications, thème et icône.
 * Fil principal.
 */
object SettingsSync {
    /** Un réglage synchronisé : son nom chez l'iPhone et le site, sa section,
     *  sa valeur au format de la sauvegarde, et comment appliquer celle reçue
     *  (validée comme à l'import ; une valeur invalide est ignorée). */
    class Entry(
        val section: String,
        val key: String,
        val read: () -> Any,
        val write: (Any?) -> Unit,
        val isDefault: () -> Boolean,
        /** Ajouté dans une version suivante : hors de l'empreinte tant qu'il
         *  reste à sa valeur par défaut (sinon la mise à jour datait la
         *  section et écrasait celle du compte). */
        val late: Boolean = false,
        /** Liste réunie à celle du compte à la première rencontre (mots
         *  masqués…) : son plafond, 0 si ce n'en est pas une. */
        val unionMax: Int = 0,
        /** Liste dont les plus récents sont à la fin (emotes) : la réunion
         *  garde la fin. */
        val unionKeepsEnd: Boolean = false,
    )

    /** Chaînes épinglées de l'app, branchées ici quand elle les a. */
    interface PinnedList {
        val logins: List<String>
        fun replace(list: List<String>)
    }

    var pinnedList: PinnedList? = null

    private const val STATE_KEY = "settings_sync_state"
    private const val DELAY_MS = 4_000L
    private const val PULL_EVERY_MS = 10 * 60_000L
    /** Le Worker garde 64 Ko par envoi : au-delà, les masqués les plus anciens restent ici. */
    private const val MAX_BODY = 60_000

    val enabled = boolPref("sync_settings", true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Mutex()
    private var pullQueued = false
    private var pushQueued = false
    private var lastPullAt = 0L
    private var started = false
    private var noteJob: Job? = null

    // ── Réglages ──

    private val entries = mutableListOf<Entry>()

    /** Ajoute un réglage synchronisé (réglages d'autres parties de l'app). */
    fun register(entry: Entry) {
        entries.removeAll { it.key == entry.key }
        entries += entry
    }

    private fun bool(section: String, key: String, pref: BoolPref, default: Boolean) = register(Entry(
        section, key, read = { pref.value }, write = { v -> (v as? Boolean)?.let { pref.set(it) } },
        isDefault = { pref.value == default }))

    private fun words(key: String, pref: WordsPref, max: Int) = register(Entry(
        "common", key, read = { JSONArray(pref.value) },
        write = { v -> (v as? JSONArray)?.let { a -> pref.set(a.strings()) } },
        isDefault = { pref.value.isEmpty() }, unionMax = max))

    init {
        // Communs avec le site et l'iPhone (noms de la sauvegarde).
        register(Entry("common", "topLang",
            // « zh_hk » chez le site et l'iPhone, « zh-hk » ici.
            read = { Features.topLang?.replace("-", "_") ?: JSONObject.NULL },
            write = { v ->
                if (v == null || v == JSONObject.NULL) Features.updateTopLang(null)
                else (v as? String)?.lowercase()?.replace("_", "-")?.takeIf { it in TopLanguage.codes }?.let { Features.updateTopLang(it) }
            },
            isDefault = { Features.topLang == null }))
        bool("common", "showRecent", Features.showRecent, true)
        bool("common", "timestamps", Features.chatTimestamps, true)
        bool("common", "keepDeleted", Features.chatKeepDeleted, false)
        bool("common", "loadHistory", Features.chatLoadHistory, true)
        bool("common", "hideBots", Features.chatHideBots, false)
        bool("common", "hideCommands", Features.chatHideCommands, false)
        words("mutedWords", Features.mutedWords, 50)
        words("blockedUsers", Features.blockedUsers, 500)
        words("highlightWords", Features.highlightWords, 30)
        // Ceux de l'app iPhone qu'Android a aussi, sous leur nom de l'iPhone.
        bool("ios", "ios_autoClaimChest", Features.autoClaimChest, true)
        bool("ios", "ios_showPinned", Features.showPinned, true)
        bool("ios", "ios_showLiveEvents", Features.showLiveEvents, true)
        bool("ios", "ios_enableRaids", Features.enableRaids, true)
        bool("ios", "ios_showWatchStreak", Features.showWatchStreak, true)
        bool("ios", "ios_subAdFree", Features.subAdFree, true)
    }

    private fun sections(): List<String> =
        if (pinnedList != null) listOf("common", "pinned", "hidden", "ios") else listOf("common", "hidden", "ios")

    // ── Moments ──

    /** Au lancement, au retour dans l'app et à la connexion (CloudSync.syncNow) :
     *  relecture au plus toutes les 10 minutes, tout de suite pour un autre compte. */
    fun refreshIfStale() {
        start()
        loadState()
        val t = target() ?: return
        if (owner != t.userId || System.currentTimeMillis() - lastPullAt > PULL_EVERY_MS) request(pull = true)
    }

    /** Réglage rallumé : relu tout de suite. */
    fun toggled(on: Boolean) {
        enabled.set(on)
        if (!on) return
        lastPullAt = 0L
        start()
        request(pull = true)
    }

    /** Surveille les réglages : quelques secondes après le dernier
     *  changement, les sections changées prennent une date et partent. */
    private fun start() {
        if (started) return
        started = true
        scope.launch {
            snapshotFlow { fingerprint() }.collect {
                noteJob?.cancel()
                noteJob = scope.launch {
                    delay(DELAY_MS)
                    if (noteChanges()) request(pull = false)
                }
            }
        }
    }

    /** Ce que la surveillance lit : toutes les sections (états Compose). */
    private fun fingerprint(): String = sections().joinToString("|") { canonical(digestData(it)) }

    // ── Échanges ──

    private class Target(val userId: String, val token: String)

    private fun target(): Target? {
        if (!enabled.value) return null
        val id = AppState.user?.id?.takeIf { it.isNotEmpty() } ?: return null
        val token = AppState.token?.takeIf { it.isNotEmpty() } ?: return null
        return Target(id, token)
    }

    /** Un échange à la fois, en file ; une demande pareille (ou une relecture)
     *  qui attend encore suffit. */
    private fun request(pull: Boolean, retry: Boolean = false) {
        if (pull) {
            if (pullQueued) return
            pullQueued = true
        } else {
            if (pullQueued || pushQueued) return
            pushQueued = true
        }
        scope.launch {
            lock.withLock {
                if (pull) pullQueued = false else pushQueued = false
                val refused = try {
                    exchange(pull)
                } catch (e: Exception) {
                    Logger.warn("SYNC", "Synchronisation des réglages interrompue", e.javaClass.simpleName)
                    false
                }
                // Section refusée par le Worker (plus récente chez lui) : relue une fois.
                if (refused && !retry) request(pull = true, retry = true)
            }
        }
    }

    /** Le compte va changer (Accounts) : ce qui attendait part avec son
     *  jeton, sans relecture ; le Worker ne garde une section que si elle
     *  est plus récente que la sienne. Pas les masquées ni les épinglées :
     *  elles se fusionnent, elles partiront au prochain échange du compte. */
    fun finalFlush() {
        noteChanges()
        val t = target() ?: return
        if (owner != t.userId) return
        val send = JSONObject()
        val sentFrom = HashMap<String, Long>()
        for (name in listOf("common", "ios")) {
            val s = sec(name)
            if (s.at <= s.srv || s.serverData == null) continue
            send.put(name, JSONObject().put("at", s.at).put("data", outgoing(name, s.serverData)))
            sentFrom[name] = s.at
        }
        if (send.length() == 0) return
        scope.launch {
            val res = push(t, send) ?: return@launch
            accepted(t.userId, sentFrom, res)
        }
    }

    /** Relecture, puis chaque section appliquée ici ou envoyée. Rien ne part
     *  sans une relecture réussie juste avant. true si le Worker a refusé une
     *  section (plus récente chez lui entre-temps). */
    private suspend fun exchange(pull: Boolean): Boolean {
        noteChanges()
        val t = target() ?: return false
        adoptOwner(t.userId)
        if (!pull && !dirty()) return false
        val remote = fetch(t) ?: return false
        // Autre compte, déconnexion ou réglage coupé pendant la relecture.
        if (target()?.userId != t.userId) return false
        lastPullAt = System.currentTimeMillis()
        noteChanges()
        adoptOwner(t.userId)

        val now = System.currentTimeMillis()
        val send = JSONObject()
        val sentFrom = HashMap<String, Long>()
        val applied = mutableListOf<String>()
        for (name in sections()) {
            val s = sec(name)
            val r = remote[name]
            if (r == null) {
                // Rien au serveur : ce qui a une date part ; une section jamais
                // changée aussi, sauf aux valeurs par défaut, datée de 1 : la
                // moindre modification faite ailleurs l'emportera.
                if (s.at > 0 || !isDefault(name)) {
                    send.put(name, JSONObject().put("at", maxOf(s.at, 1L)).put("data", outgoing(name, null)))
                    sentFrom[name] = s.at
                }
                continue
            }
            val here = s.at > s.srv
            val there = r.at != s.srv
            s.serverData = r.data
            val next: Any
            if (s.at == 0L) {
                // Jamais changée ici pour ce compte : ce qui existe d'abord.
                next = merged(name, r.data, s, first = true)
            } else if (!here && there) {
                next = r.data
            } else if (here && !there) {
                send.put(name, JSONObject().put("at", s.at).put("data", outgoing(name, r.data)))
                sentFrom[name] = s.at
                continue
            } else if (!here) {
                continue
            } else if (r.at == s.at) {
                // La même version (envoi dont la réponse s'est perdue).
                s.srv = r.at
                setBase(s, name, r.data)
                continue
            } else if (name == "common" || name == "ios") {
                // Les deux ont changé : la plus récente.
                if (r.at < s.at) {
                    send.put(name, JSONObject().put("at", s.at).put("data", outgoing(name, r.data)))
                    sentFrom[name] = s.at
                    continue
                }
                next = r.data
            } else {
                next = merged(name, r.data, s, first = false)
            }
            applySection(name, next)
            applied += name
            s.sum = digest(digestData(name))
            s.srv = r.at
            setBase(s, name, r.data)
            if (canonical(next) == canonical(r.data)) {
                s.at = r.at
            } else {
                // Ce qui vient d'ici s'y est ajouté (première rencontre,
                // fusion) : la section repart, plus récente que celle du serveur.
                s.at = maxOf(now, r.at + 1)
                send.put(name, JSONObject().put("at", s.at).put("data", outgoing(name, r.data)))
                sentFrom[name] = s.at
            }
        }
        saveState()
        if (applied.isNotEmpty()) Logger.info("SYNC", "Réglages repris du compte", applied.joinToString())
        if (send.length() == 0) return false
        val res = push(t, fit(send, t.userId)) ?: return false
        return accepted(t.userId, sentFrom, res)
    }

    /** Réponse d'un envoi : les sections prises deviennent la version du
     *  serveur. true si l'une a été refusée. */
    private fun accepted(userId: String, sentFrom: Map<String, Long>, res: JSONObject): Boolean {
        if (owner != userId) return false
        val accepted = res.arr("accepted")?.strings()?.toSet().orEmpty()
        val sections = remoteSections(res)
        var refused = false
        for (name in sentFrom.keys) {
            val r = sections[name]
            if (name !in accepted || r == null) {
                refused = true
                continue
            }
            val s = sec(name)
            // Rechangée ici pendant l'envoi : elle repartira.
            if (s.at == sentFrom[name]) s.at = r.at
            s.srv = r.at
            s.serverData = r.data
            setBase(s, name, r.data)
        }
        saveState()
        if (accepted.isNotEmpty()) Logger.debug("SYNC", "Réglages envoyés", accepted.sorted().joinToString())
        return refused
    }

    private class Remote(val at: Long, val data: Any)

    private fun remoteSections(json: JSONObject): Map<String, Remote> {
        val out = HashMap<String, Remote>()
        val secs = json.obj("sections") ?: return out
        val keys = secs.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val s = secs.optJSONObject(name) ?: continue
            val at = (s.opt("at") as? Number)?.toDouble() ?: continue
            if (!at.isFinite() || at <= 0) continue
            val data = s.opt("data") ?: continue
            out[name] = Remote(at.toLong(), data)
        }
        return out
    }

    private suspend fun fetch(t: Target): Map<String, Remote>? {
        val r = Http.get("${Config.API_URL}/api/sync/prefs?userId=${Http.encode(t.userId)}",
                         mapOf("Authorization" to "Bearer ${t.token}"), timeout = 15)
        val json = if (r != null && r.code == 200) parseJson(r.body) else null
        if (json == null) {
            Logger.warn("SYNC", "Relecture des réglages impossible", (r?.code?.let { "HTTP $it" } ?: "réseau") + " — rien n'est envoyé")
            return null
        }
        return remoteSections(json)
    }

    private suspend fun push(t: Target, sections: JSONObject): JSONObject? {
        val body = JSONObject().put("userId", t.userId).put("sections", sections).toString()
        val r = Http.postJson("${Config.API_URL}/api/sync/prefs", body, mapOf("Authorization" to "Bearer ${t.token}"), timeout = 20)
        val json = if (r != null && r.code in 200..299) parseJson(r.body) else null
        if (json == null) {
            // Toujours « à envoyer » : le prochain échange réessaie.
            Logger.warn("SYNC", "Envoi des réglages refusé", r?.code?.let { "HTTP $it" } ?: "réseau")
        }
        return json
    }

    /** Envoi borné : au-delà, les masqués les plus anciens restent sur cet appareil seulement. */
    private fun fit(send: JSONObject, userId: String): JSONObject {
        val hidden = send.optJSONObject("hidden")?.optJSONObject("data") ?: return send
        fun size() = JSONObject().put("userId", userId).put("sections", send).toString().toByteArray().size
        while (size() > MAX_BODY) {
            val ch = hidden.optJSONArray("channels") ?: JSONArray()
            val cats = hidden.optJSONArray("categories") ?: JSONArray()
            if (ch.length() == 0 && cats.length() == 0) break
            hidden.put("channels", JSONArray((0 until ch.length() * 9 / 10).map { ch.get(it) }))
            hidden.put("categories", JSONArray((0 until cats.length() * 9 / 10).map { cats.get(it) }))
        }
        return send
    }

    // ── Contenu des sections ──

    /** Contenu d'une section ici, au format du document. */
    private fun localData(name: String): Any = when (name) {
        "hidden" -> Hidden.toJson()
        "pinned" -> JSONArray(pinnedList?.logins.orEmpty())
        else -> JSONObject().apply { entries.filter { it.section == name }.forEach { put(it.key, it.read()) } }
    }

    /** Contenu pour l'empreinte : sans les réglages ajoutés après coup restés
     *  à leur valeur par défaut. */
    private fun digestData(name: String): Any = when (name) {
        "common", "ios" -> JSONObject().apply {
            entries.filter { it.section == name && !(it.late && it.isDefault()) }.forEach { put(it.key, it.read()) }
        }
        else -> localData(name)
    }

    private fun isDefault(name: String): Boolean = when (name) {
        "hidden" -> Hidden.isEmpty
        "pinned" -> pinnedList?.logins.isNullOrEmpty()
        else -> entries.filter { it.section == name }.all { it.isDefault() }
    }

    /** Ce qui part : pour les réglages, la section du serveur (clés que l'app
     *  ne connaît pas comprises) avec les valeurs d'ici par-dessus. */
    private fun outgoing(name: String, server: Any?): Any = when (name) {
        "common", "ios" -> {
            val out = (server as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
            entries.filter { it.section == name }.forEach { out.put(it.key, it.read()) }
            out
        }
        else -> localData(name)
    }

    /** Section reçue appliquée ici, chaque valeur validée comme à l'import. */
    private fun applySection(name: String, data: Any) {
        when (name) {
            "hidden" -> applyHidden(data as? JSONObject ?: JSONObject())
            "pinned" -> pinnedList?.let { p ->
                val list = logins(data)
                if (list != p.logins) p.replace(list)
            }
            else -> {
                val o = data as? JSONObject ?: return
                for (e in entries.filter { it.section == name }) {
                    if (o.has(e.key)) e.write(o.opt(e.key))
                }
            }
        }
    }

    /** Ce que devient une section quand les deux côtés ont changé, ou à la
     *  première rencontre : listes fusionnées ; réglages communs avec les
     *  listes du chat d'ici en plus (première rencontre). */
    private fun merged(name: String, theirs: Any, s: Sec, first: Boolean): Any = when (name) {
        "hidden" -> mergeHidden(theirs as? JSONObject, if (first) null else s.base as? JSONObject)
        "pinned" -> JSONArray(mergePins(pinnedList?.logins.orEmpty(), logins(theirs), if (first) null else (s.base as? JSONArray)?.let { logins(it) }))
        "common" -> {
            val out = (theirs as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
            for (e in entries.filter { it.section == "common" && it.unionMax > 0 }) {
                val there = out.optJSONArray(e.key)?.strings().orEmpty()
                val mine = (e.read() as? JSONArray)?.strings().orEmpty()
                val all = (there + mine).distinct()
                out.put(e.key, JSONArray(if (e.unionKeepsEnd) all.takeLast(e.unionMax) else all.take(e.unionMax)))
            }
            out
        }
        else -> theirs
    }

    private fun setBase(s: Sec, name: String, data: Any) {
        s.base = when (name) {
            "hidden" -> data as? JSONObject
            "pinned" -> JSONArray(logins(data))
            else -> null
        }
    }

    // ── Masquées ──

    private data class HChannel(val login: String, val name: String, val at: Long)
    private data class HCat(val id: String, val name: String, val box: String, val at: Long)

    private fun stamp(o: JSONObject): Long =
        (o.opt("at") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it < 1e14 }?.toLong() ?: 0L

    private fun channelsOf(o: JSONObject?): List<HChannel> = o?.arr("channels")?.objects().orEmpty().mapNotNull { e ->
        val l = e.str("login")?.lowercase()?.takeIf { Links.isValidLogin(it) } ?: return@mapNotNull null
        HChannel(l, (e.str("name") ?: "").take(100).ifEmpty { l }, stamp(e))
    }

    private fun categoriesOf(o: JSONObject?): List<HCat> = o?.arr("categories")?.objects().orEmpty().mapNotNull { e ->
        val id = e.str("id")?.takeIf { it.isNotEmpty() && it.length <= 20 && it.all { c -> c.isDigit() } } ?: return@mapNotNull null
        val box = e.str("box") ?: ""
        HCat(id, (e.str("name") ?: "").take(100), if (box.startsWith("https://") && box.length <= 500) box else "", stamp(e))
    }

    /** Masquées d'ici et du serveur : fusion à trois avec la liste du dernier
     *  échange (`base`) ; sans elle, réunion (la plus récente l'emporte). */
    private fun mergeHidden(remote: JSONObject?, base: JSONObject?): JSONObject {
        val local = Hidden.toJson()
        var hereCh = channelsOf(local)
        var hereCat = categoriesOf(local)
        var thereCh = channelsOf(remote)
        var thereCat = categoriesOf(remote)
        if (base != null) {
            val wasCh = channelsOf(base).associate { it.login to it.at }
            val wasCat = categoriesOf(base).associate { it.id to it.at }
            val hereChSet = hereCh.map { it.login }.toSet()
            val thereChSet = thereCh.map { it.login }.toSet()
            val hereCatSet = hereCat.map { it.id }.toSet()
            val thereCatSet = thereCat.map { it.id }.toSet()
            // Réaffichée là-bas depuis le dernier échange : réaffichée ici.
            hereCh = hereCh.filter { c -> c.login in thereChSet || !(wasCh[c.login]?.let { c.at <= it } ?: false) }
            hereCat = hereCat.filter { c -> c.id in thereCatSet || !(wasCat[c.id]?.let { c.at <= it } ?: false) }
            // Réaffichée ici depuis : ne revient pas.
            thereCh = thereCh.filter { c -> c.login in hereChSet || !(wasCh[c.login]?.let { c.at <= it } ?: false) }
            thereCat = thereCat.filter { c -> c.id in hereCatSet || !(wasCat[c.id]?.let { c.at <= it } ?: false) }
        }
        val ch = LinkedHashMap<String, HChannel>()
        (hereCh + thereCh).forEach { c -> if ((ch[c.login]?.at ?: -1L) < c.at) ch[c.login] = c }
        val cats = LinkedHashMap<String, HCat>()
        (hereCat + thereCat).forEach { c -> if ((cats[c.id]?.at ?: -1L) < c.at) cats[c.id] = c }
        val outCh = JSONArray()
        ch.values.sortedByDescending { it.at }.take(500).forEach {
            outCh.put(JSONObject().put("login", it.login).put("name", it.name).put("at", it.at))
        }
        val outCat = JSONArray()
        cats.values.sortedByDescending { it.at }.take(200).forEach {
            outCat.put(JSONObject().put("id", it.id).put("name", it.name).put("box", it.box).put("at", it.at))
        }
        return JSONObject().put("channels", outCh).put("categories", outCat)
    }

    /** Masquées d'ici remplacées par `data` : celles qui n'y sont plus
     *  réaffichées, les autres ajoutées ou mises à jour. */
    private fun applyHidden(data: JSONObject) {
        val logins = channelsOf(data).map { it.login }.toSet()
        val ids = categoriesOf(data).map { it.id }.toSet()
        Hidden.channels.toList().filter { it.login !in logins }.forEach { Hidden.unhideChannel(it.login) }
        Hidden.categories.toList().filter { it.id !in ids }.forEach { Hidden.unhideCategory(it.id) }
        Hidden.merge(data)
    }

    // ── Épinglées ──

    /** Pseudos valides (minuscules), sans doublon, 300 au plus. */
    private fun logins(value: Any?): List<String> =
        (value as? JSONArray)?.strings().orEmpty().map { it.lowercase() }.filter { Links.isValidLogin(it) }.distinct().take(300)

    /** Épinglées des deux côtés : fusion à trois avec la liste du dernier
     *  échange ; sans elle, réunion. L'ordre du serveur, puis celles d'ici. */
    private fun mergePins(here: List<String>, there: List<String>, base: List<String>?): List<String> {
        val was = base.orEmpty().toSet()
        val mine = here.toSet()
        val theirs = there.toSet()
        // Désépinglée ici depuis le dernier échange : pas reprise.
        val out = there.filter { it in mine || it !in was }.toMutableList()
        // Désépinglée là-bas depuis : retirée ici aussi.
        for (login in here) if (login !in theirs && login !in was) out += login
        return out.take(300)
    }

    // ── État gardé (préférences) ──

    /** Par section : `at` (version d'ici ; 0 = jamais changée pour ce
     *  compte), `srv` (date du serveur au dernier échange), `sum`
     *  (empreinte), `base` (liste du serveur au dernier échange, pour la
     *  fusion), et ce que le serveur avait (envoi final sans relecture). */
    private class Sec(var at: Long = 0L, var srv: Long = 0L, var sum: String? = null, var base: Any? = null,
                      var serverData: Any? = null)

    private var owner: String? = null
    private val secs = HashMap<String, Sec>()
    private var stateLoaded = false

    private fun sec(name: String): Sec {
        loadState()
        return secs.getOrPut(name) { Sec() }
    }

    private fun dirty(): Boolean {
        loadState()
        return secs.values.any { it.at > it.srv }
    }

    private fun loadState() {
        if (stateLoaded) return
        stateLoaded = true
        val o = parseJson(AppState.prefs.getString(STATE_KEY, null)) ?: return
        owner = o.str("owner")
        val all = o.obj("sections") ?: return
        val keys = all.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val s = all.optJSONObject(name) ?: continue
            secs[name] = Sec(s.long("at") ?: 0L, s.long("srv") ?: 0L, s.str("sum"), s.opt("base")?.takeIf { it != JSONObject.NULL },
                             s.opt("server")?.takeIf { it != JSONObject.NULL })
        }
    }

    private fun saveState() {
        val all = JSONObject()
        for ((name, s) in secs) {
            all.put(name, JSONObject().put("at", s.at).put("srv", s.srv).apply {
                s.sum?.let { put("sum", it) }
                s.base?.let { put("base", it) }
                // Seulement les réglages (petits) : de quoi garder les clés
                // inconnues d'ici lors d'un envoi sans relecture.
                if (name == "common" || name == "ios") s.serverData?.let { put("server", it) }
            })
        }
        val o = JSONObject().put("sections", all).apply { owner?.let { put("owner", it) } }
        AppState.prefs.edit().putString(STATE_KEY, o.toString()).apply()
    }

    /** Dates gardées pour un autre compte, ou d'avant toute connexion : elles
     *  ne valent rien pour celui-ci, qui repart de ce qu'il a sur le serveur
     *  (première rencontre). */
    private fun adoptOwner(userId: String) {
        loadState()
        if (owner == userId) return
        for (name in sections()) {
            val s = sec(name)
            s.at = 0L
            s.srv = 0L
            s.base = null
            s.serverData = null
        }
        owner = userId
        saveState()
    }

    /** Sections changées ici depuis leur empreinte : datées de maintenant (la
     *  toute première empreinte n'est qu'un point de départ). true si l'une
     *  d'elles vient de prendre une date. */
    private fun noteChanges(): Boolean {
        var touched = false
        var dated = false
        for (name in sections()) {
            val s = sec(name)
            val sum = digest(digestData(name))
            if (s.sum == sum) continue
            if (s.sum != null) {
                s.at = maxOf(System.currentTimeMillis(), s.srv + 1)
                dated = true
            }
            s.sum = sum
            touched = true
        }
        if (touched) saveState()
        return dated
    }

    // ── Empreintes ──

    /** JSON aux clés triées, nombres entiers sans « .0 » : le même texte
     *  pour le même contenu, d'un lancement à l'autre. */
    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Boolean -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        else -> JSONObject.quote(v.toString())
    }

    /** Empreinte (FNV-1a 64 bits) d'un contenu JSON. */
    private fun digest(v: Any): String {
        val bytes = canonical(v).toByteArray(Charsets.UTF_8)
        var h = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
        for (b in bytes) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(36) + "." + bytes.size
    }
}
