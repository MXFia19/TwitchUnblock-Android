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
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.arr
import com.mxfia19.twitchunblock.core.bool
import com.mxfia19.twitchunblock.core.long
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.objects
import com.mxfia19.twitchunblock.core.parseJson
import com.mxfia19.twitchunblock.core.parseJsonArray
import com.mxfia19.twitchunblock.core.str
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
    ) {
        val unread: Boolean get() = lastFrom == "a" && updatedAt > seen
        val messages: List<Message> get() = thread.ifEmpty { listOf(Message(false, text, null, at, emptyList())) }
    }

    private const val KEY = "feedback_reports"
    val reports = mutableStateListOf<Report>()

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
                thread = parseThread(o.optJSONArray("thread")),
            )
        }
    }

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
                .put("closed", r.closed).put("full", r.full).put("thread", t))
        }
        AppState.prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun parseThread(arr: JSONArray?): List<Message> = arr?.objects()?.map { m ->
        Message(m.str("f") == "a", m.str("m") ?: "", m.str("s"), m.long("at") ?: 0L,
                (0 until (m.optJSONArray("ph")?.length() ?: 0)).map { m.optJSONArray("ph")!!.optInt(it) })
    } ?: emptyList()

    /** Photo choisie : JPEG d'au plus 1600 px et ~470 Ko (le Worker refuse au-delà). */
    fun photoBase64(context: Context, uri: Uri): String? = try {
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
        val errors = Logger.snapshot().filter { it.level == Logger.Level.ERROR || it.level == Logger.Level.WARN }
            .takeLast(20).map { "[${it.tag}] ${it.message}" + (it.detail?.let { d -> " — $d" } ?: "") }
        val info = JSONObject().put("app", "Android ${BuildConfig.VERSION_NAME} (${BuildConfig.CHANNEL})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("lang", AppState.lang.code).toString()
        val body = JSONObject().put("kind", kind).put("message", message).put("contact", contact)
            .put("platform", "android").put("version", BuildConfig.VERSION_NAME).put("info", info)
            .put("errors", JSONArray(errors)).put("photos", JSONArray(photos))
        val r = Http.postJson("${Config.API_URL}/api/feedback", body.toString(), timeout = 30) ?: return "fb_failed"
        if (r.code == 429) return "fb_too_many"
        val j = parseJson(r.body)
        val id = j?.str("id")
        val token = j?.str("token")
        if (r.code != 200 || id == null || token == null) return "fb_failed"
        val now = System.currentTimeMillis()
        reports.add(0, Report(id, token, now, kind, message.take(200), updatedAt = now, seen = now))
        save()
        Logger.success("FEEDBACK", "Signalement envoyé")
        return null
    }

    /** Relit l'état et les réponses des signalements suivis. */
    suspend fun refresh() {
        if (reports.isEmpty()) return
        for (chunk in reports.toList().chunked(30)) {
            val items = JSONArray(chunk.map { JSONObject().put("id", it.id).put("token", it.token) })
            val r = Http.postJson("${Config.API_URL}/api/feedback/thread", JSONObject().put("items", items).toString(), timeout = 20) ?: return
            if (r.code != 200) return
            val states = parseJson(r.body)?.arr("items")?.objects() ?: return
            for (st in states) {
                val i = reports.indexOfFirst { it.id == st.str("id") }
                if (i < 0) continue
                if (st.bool("gone") == true) {
                    reports.removeAt(i)
                    continue
                }
                reports[i] = apply(reports[i], st)
            }
        }
        save()
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
        val r = Http.postJson("${Config.API_URL}/api/feedback/reply", body.toString(), timeout = 30) ?: return "fb_reply_failed"
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
        save()
        return null
    }

    /** Adresse d'une photo d'une discussion. */
    fun photoUrl(r: Report, n: Int): String =
        "${Config.API_URL}/api/feedback/photo?id=${Http.encode(r.id)}&n=$n&token=${Http.encode(r.token)}"
}
