package com.mxfia19.twitchunblock.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import com.mxfia19.twitchunblock.BuildConfig
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Config
import com.mxfia19.twitchunblock.core.CustomWorker
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Lang
import com.mxfia19.twitchunblock.core.LogText
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
import com.mxfia19.twitchunblock.core.boolPref
import com.mxfia19.twitchunblock.core.stringPref
import com.mxfia19.twitchunblock.notify.InAppNotice
import com.mxfia19.twitchunblock.notify.InAppNotices
import com.mxfia19.twitchunblock.web.WebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Signalements (bug, idée) envoyés au développeur, et leur discussion : ses
 * réponses, l'état (vu, en cours, fait…), répondre à son tour avec des
 * photos. Gardés sur l'appareil (identifiant et jeton). Comme FeedbackStore (iOS).
 */
object Feedback {
    data class Message(val fromTeam: Boolean, val text: String, val status: String?, val at: Long, val photos: List<Int>)

    data class Report(
        val id: String,
        val token: String,
        val at: Long,
        val kind: String,
        val text: String,
        val status: String = "new",
        val updatedAt: Long = 0L,
        val lastFrom: String = "u",
        val seen: Long = 0L,
        val closed: Boolean = false,
        val full: Boolean = false,
        val thread: List<Message> = emptyList(),
        /** Worker qui a reçu le signalement (absent : le principal, seul
         *  utilisé avant) : la discussion s'y relit, même après avoir changé
         *  « Tout passer par ce Worker ». */
        val base: String? = null,
    ) {
        val unread: Boolean get() = lastFrom == "a" && updatedAt > seen
        val messages: List<Message> get() = thread.ifEmpty { listOf(Message(false, text, null, at, emptyList())) }
        val origin: String get() = base ?: Config.API_URL
        /** Terminé (fait ou refusé), dernier mot au développeur : plus relu
         *  de lui-même ; une réponse d'ici le remet au suivi. */
        val finished: Boolean get() = (status == "done" || status == "refused") && lastFrom == "a"
        /** Terminé depuis moins de 30 jours : relu une fois par jour (le
         *  Worker y ajoute « le correctif est en ligne » à sa sortie). */
        val recentlyFinished: Boolean get() = finished && updatedAt > System.currentTimeMillis() - 30 * 86_400_000L
    }

    private const val KEY = "feedback_reports"
    /** Dernière relecture des signalements terminés (une par jour au plus). */
    private const val CLOSED_CHECK = "fb_closed_checked"
    val reports = mutableStateListOf<Report>()
    private var checkedAt = 0L
    /** Dernière réponse envoyée d'ici, par signalement : une relecture partie
     *  avant ne l'écrase pas. */
    private val localEdits = HashMap<String, Long>()
    /** « Mes signalements » à l'écran (liste ou discussion) : la réponse s'y
     *  voit déjà, pas de bandeau par-dessus. */
    var inboxViewers = 0

    /** Nom joint, pour que le développeur reconnaisse qui revient d'un
     *  signalement à l'autre : le compte Twitch (interrupteur allumé
     *  d'office), sinon un pseudo écrit à la main ; retenus sur l'appareil.
     *  Mêmes noms que l'app iPhone. */
    val signWithTwitch = boolPref("fb_sign_twitch", true)
    val pseudo = stringPref("fb_pseudo", "")

    /** Compte Twitch connecté (compte de l'app, sinon session web), proposé pour signer. */
    val signLogin: String?
        get() = AppState.user?.login?.takeIf { AppState.token != null && it.isNotEmpty() }
            ?: WebSession.login?.takeIf { WebSession.token != null && it.isNotEmpty() }

    /** Nom joint et sa source (« twitch » ou « pseudo ») ; null : pas signé. */
    fun signedName(): Pair<String, String>? {
        signLogin?.let { return if (signWithTwitch.value) it to "twitch" else null }
        // Espaces et retours à la ligne ramenés à un espace (une ligne, comme le Worker).
        val p = pseudo.value.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ").take(30)
        return if (p.isEmpty()) null else p to "pseudo"
    }

    val unreadCount: Int get() = reports.count { it.unread }

    fun init() {
        reports.clear()
        parseJsonArray(AppState.prefs.getString(KEY, null))?.objects()?.forEach { o ->
            reports += Report(
                id = o.str("id") ?: return@forEach, token = o.str("token") ?: return@forEach,
                at = o.long("at") ?: 0L, kind = o.str("kind") ?: "other", text = o.str("text") ?: "",
                status = o.str("status") ?: "new", updatedAt = o.long("updatedAt") ?: 0L,
                lastFrom = o.str("lastFrom") ?: "u", seen = o.long("seen") ?: 0L,
                closed = o.bool("closed") == true, full = o.bool("full") == true,
                thread = parseThread(o.optJSONArray("thread")), base = o.str("base"),
            )
        }
        FeedbackWatch.update(com.mxfia19.twitchunblock.App.instance, followsSome())
    }

    /** Il reste un signalement dont on attend quelque chose (relecture en arrière-plan). */
    private fun followsSome(): Boolean = reports.any { !it.finished || it.recentlyFinished }

    private fun save() {
        val arr = JSONArray()
        for (r in reports) {
            val t = JSONArray()
            for (m in r.thread) {
                t.put(JSONObject().put("f", if (m.fromTeam) "a" else "u").put("m", m.text).put("at", m.at)
                    .apply { m.status?.let { put("s", it) }; if (m.photos.isNotEmpty()) put("ph", JSONArray(m.photos)) })
            }
            arr.put(JSONObject().put("id", r.id).put("token", r.token).put("at", r.at).put("kind", r.kind).put("text", r.text)
                .put("status", r.status).put("updatedAt", r.updatedAt).put("lastFrom", r.lastFrom).put("seen", r.seen)
                .put("closed", r.closed).put("full", r.full).put("thread", t).apply { r.base?.let { put("base", it) } })
        }
        AppState.prefs.edit().putString(KEY, arr.toString()).apply()
        FeedbackWatch.update(com.mxfia19.twitchunblock.App.instance, followsSome())
    }

    private fun parseThread(arr: JSONArray?): List<Message> = arr?.objects()?.map { m ->
        Message(m.str("f") == "a", m.str("m") ?: "", m.str("s"), m.long("at") ?: 0L,
                (0 until (m.optJSONArray("ph")?.length() ?: 0)).map { m.optJSONArray("ph")!!.optInt(it) })
    } ?: emptyList()

    /** Photo choisie : JPEG d'au plus 1600 px et ~470 Ko (le Worker refuse
     *  au-delà). Hors du fil principal : lecture (Drive), décodage et
     *  compressions figeaient l'écran. */
    suspend fun photoBase64(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) { encodePhoto(context, uri) }

    private fun encodePhoto(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        // Dimensions d'abord : une photo de 50 à 200 Mpx décodée en entier
        // épuisait la mémoire. Réduite dès le décodage (par 2, 4, 8…), sans
        // passer sous 1600 px.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var side = 1600
        var quality = 80
        var out: ByteArray
        while (true) {
            val scale = minOf(1f, side.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            out = stream.toByteArray()
            if (out.size < 470_000 || side < 500) break
            side = (side * 0.8).toInt()
            quality = maxOf(50, quality - 10)
        }
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        Logger.warn("FEEDBACK", "Photo illisible", e.message)
        null
    } catch (e: OutOfMemoryError) {
        // Une Error : « Exception » ne l'attrape pas, l'app plantait.
        Logger.warn("FEEDBACK", "Photo trop lourde", e.message)
        null
    }

    /** Envoie un signalement. null si c'est parti, sinon une clé d'erreur. */
    suspend fun send(kind: String, message: String, contact: String, photos: List<String>): String? {
        // Journal en anglais, quelle que soit la langue de l'app (comme l'iPhone).
        val errors = Logger.snapshot().filter { it.level == Logger.Level.ERROR || it.level == Logger.Level.WARN }
            .takeLast(20).map { "[${it.tag}] ${LogText.tr(it.message, Lang.EN)}" + (it.detail?.let { d -> " — ${LogText.tr(d, Lang.EN)}" } ?: "") }
        val info = JSONObject().put("app", "Android ${BuildConfig.VERSION_NAME} (${BuildConfig.CHANNEL})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("lang", AppState.lang.code).toString()
        val body = JSONObject().put("kind", kind).put("message", message).put("contact", contact)
            .put("platform", "android").put("version", BuildConfig.VERSION_NAME).put("info", info)
            .put("errors", JSONArray(errors)).put("photos", JSONArray(photos))
        // Champs « name » et « nameSource » du Worker (gardés en nm/ns), comme l'iPhone.
        signedName()?.let { (name, source) -> body.put("name", name).put("nameSource", source) }
        // Worker des signalements : le personnel avec « Tout passer par ce Worker ».
        val base = CustomWorker.base
        val r = Http.postJson("$base/api/feedback", body.toString(), timeout = 30) ?: return "fb_failed"
        if (r.code == 429) return "fb_too_many"
        val j = parseJson(r.body)
        val id = j?.str("id")
        val token = j?.str("token")
        if (r.code != 200 || id == null || token == null) return "fb_failed"
        val now = System.currentTimeMillis()
        // `seen` à 0 : rien du développeur n'est encore arrivé ; la date du
        // serveur prendra le relais (l'horloge de l'appareil, décalée, faisait
        // manquer une réponse).
        reports.add(0, Report(id, token, now, kind, message.take(200), updatedAt = now, seen = 0L, base = base))
        save()
        Logger.success("FEEDBACK", "Signalement envoyé")
        return null
    }

    /**
     * Relit l'état et les réponses des signalements suivis, en une requête
     * par Worker et par paquet de 30 — au plus une fois par `minInterval`
     * sans `force`. Un signalement terminé n'est plus relu, sauf s'il est
     * dans `including` (discussion qu'on ouvre) ou, une fois par jour, dans
     * les 30 jours qui suivent. Une réponse (ou un nouvel état) du
     * développeur arrivée depuis la dernière lecture : bandeau dans l'app,
     * ou notification (InAppNotices). Comme FeedbackStore.refresh (iOS).
     */
    suspend fun refresh(force: Boolean = false, minInterval: Long = 180_000L, including: Set<String> = emptySet()) {
        val started = System.currentTimeMillis()
        val closedDue = started - AppState.prefs.getLong(CLOSED_CHECK, 0L) > 86_400_000L
        val followed = reports.filter { !it.finished || it.id in including || (closedDue && it.recentlyFinished) }
        if (followed.isEmpty() || (!force && started - checkedAt < minInterval)) return
        checkedAt = started
        val byId = HashMap<String, JSONObject>()
        var reached = false
        // Chacun à son Worker d'origine : « effacé » y fait foi.
        for ((base, list) in followed.groupBy { it.origin }) {
            for (chunk in list.chunked(30)) {
                val items = JSONArray(chunk.map { JSONObject().put("id", it.id).put("token", it.token) })
                val r = Http.postJson("$base/api/feedback/thread", JSONObject().put("items", items).toString(), timeout = 20)
                if (r == null || r.code != 200) {
                    Logger.warn("FEEDBACK", "Requête refusée", "/api/feedback/thread · " + (r?.code?.let { "HTTP $it" } ?: "réseau"))
                    continue
                }
                val states = parseJson(r.body)?.arr("items")?.objects() ?: continue
                reached = true
                for (st in states) st.str("id")?.let { byId[it] = st }
            }
        }
        if (!reached) return
        if (closedDue && followed.any { it.recentlyFinished }) AppState.prefs.edit().putLong(CLOSED_CHECK, started).apply()
        val before = reports.associate { it.id to it.updatedAt }
        val next = reports.mapNotNull { r ->
            val st = byId[r.id] ?: return@mapNotNull r
            if (st.bool("gone") == true) return@mapNotNull null
            // Réponse envoyée pendant la relecture : l'état lu est plus ancien.
            if ((localEdits[r.id] ?: 0L) > started) return@mapNotNull r
            apply(r, st)
        }
        reports.clear()
        reports.addAll(next)
        save()
        val fresh = reports.filter { it.unread && it.updatedAt > (before[it.id] ?: 0L) }
        // « Mes signalements » à l'écran : la réponse s'y voit déjà.
        if (fresh.isNotEmpty() && !(inboxViewers > 0 && InAppNotices.foreground)) notifyTeamUpdates(fresh)
    }

    /** Un avis par signalement mis à jour par le développeur ; le toucher
     *  ouvre « Mes signalements ». Identifiant = signalement + date : la même
     *  mise à jour n'est jamais annoncée deux fois. */
    private fun notifyTeamUpdates(list: List<Report>) {
        for (r in list.take(3)) {
            val last = r.messages.lastOrNull { it.fromTeam }
            val notice = if (last != null && last.text.isNotEmpty()) {
                InAppNotice(InAppNotice.Kind.FEEDBACK, AppState.t("fb_notif_reply"), last.text)
            } else {
                InAppNotice(InAppNotice.Kind.FEEDBACK, AppState.t("fb_notif_status"),
                            AppState.t("fb_status_line").replace("{s}", AppState.t("fb_status_${r.status}")))
            }
            InAppNotices.deliver(notice, "fb-${r.id}-${r.updatedAt}")
        }
    }

    private fun apply(r: Report, st: JSONObject): Report = r.copy(
        status = st.str("status") ?: r.status,
        updatedAt = st.long("updatedAt") ?: r.updatedAt,
        lastFrom = st.str("lastFrom") ?: r.lastFrom,
        thread = st.optJSONArray("thread")?.let { parseThread(it) } ?: r.thread,
        closed = st.bool("closed") == true,
        full = st.bool("full") == true,
    )

    fun markSeen(id: String) {
        val i = reports.indexOfFirst { it.id == id }
        if (i < 0) return
        reports[i] = reports[i].copy(seen = reports[i].updatedAt)
        save()
    }

    /** Répond dans la discussion. null si c'est parti, sinon une clé d'erreur. */
    suspend fun reply(id: String, message: String, photos: List<String>): String? {
        val report = reports.firstOrNull { it.id == id } ?: return "fb_reply_failed"
        val body = JSONObject().put("id", id).put("token", report.token).put("message", message).put("photos", JSONArray(photos))
        val r = Http.postJson("${report.origin}/api/feedback/reply", body.toString(), timeout = 30) ?: return "fb_reply_failed"
        val j = parseJson(r.body)
        val i = reports.indexOfFirst { it.id == id }
        if ((r.code == 403 || r.code == 409) && i >= 0) {
            reports[i] = reports[i].copy(closed = j?.bool("closed") == true, full = j?.bool("full") == true)
            save()
            return if (r.code == 403) "fb_closed_note" else "fb_full_note"
        }
        if (r.code == 429) return "fb_too_many"
        val item = j?.obj("item")
        if (r.code != 200 || item == null || i < 0) return "fb_reply_failed"
        reports[i] = apply(reports[i], item).let { it.copy(seen = it.updatedAt) }
        localEdits[id] = System.currentTimeMillis()
        save()
        return null
    }

    /** Adresse d'une photo d'une discussion. */
    fun photoUrl(r: Report, n: Int): String =
        "${r.origin}/api/feedback/photo?id=${Http.encode(r.id)}&n=$n&token=${Http.encode(r.token)}"
}
