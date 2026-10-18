package com.mxfia19.twitchunblock.core

import org.json.JSONArray
import org.json.JSONObject

// org.json d'Android rend « null » (en texte) pour une valeur JSON nulle avec
// optString : ces aides rendent un vrai null.

fun JSONObject.str(name: String): String? {
    val v = opt(name)
    return if (v == null || v == JSONObject.NULL) null else v.toString()
}

fun JSONObject.obj(name: String): JSONObject? = optJSONObject(name)

fun JSONObject.arr(name: String): JSONArray? = optJSONArray(name)

fun JSONObject.int(name: String): Int? = (opt(name) as? Number)?.toInt()

fun JSONObject.long(name: String): Long? = (opt(name) as? Number)?.toLong()

fun JSONObject.dbl(name: String): Double? = (opt(name) as? Number)?.toDouble()

fun JSONObject.bool(name: String): Boolean? = opt(name) as? Boolean

fun JSONObject.isJsonNull(name: String): Boolean = has(name) && opt(name) == JSONObject.NULL

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

fun parseJson(text: String?): JSONObject? = try {
    if (text.isNullOrBlank()) null else JSONObject(text)
} catch (e: Exception) {
    null
}

fun parseJsonArray(text: String?): JSONArray? = try {
    if (text.isNullOrBlank()) null else JSONArray(text)
} catch (e: Exception) {
    null
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> o.put(k, JSONObject.NULL)
            is List<*> -> o.put(k, JSONArray(v))
            is Map<*, *> -> o.put(k, JSONObject(v))
            else -> o.put(k, v)
        }
    }
    return o
}
