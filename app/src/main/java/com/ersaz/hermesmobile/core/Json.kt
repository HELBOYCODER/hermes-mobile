package com.ersaz.hermesmobile.core

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers over org.json so the rest of the code stays readable. */

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) if (v != null) o.put(k, v.toJsonValue())
    return o
}

fun jsonArrayOf(vararg items: Any?): JSONArray {
    val a = JSONArray()
    items.forEach { a.put(it.toJsonValue()) }
    return a
}

fun Any?.toJsonValue(): Any? = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { o -> forEach { (k, v) -> o.put(k.toString(), v.toJsonValue()) } }
    is Collection<*> -> JSONArray().also { a -> forEach { a.put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().also { a -> forEach { a.put(it.toJsonValue()) } }
    else -> this
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

/** Parse tool-call arguments leniently: models sometimes send "" or trailing junk. */
fun parseArgs(raw: String?): JSONObject {
    if (raw.isNullOrBlank()) return JSONObject()
    return try {
        JSONObject(raw)
    } catch (_: Exception) {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
    }
}

fun truncateMiddle(s: String, max: Int): String {
    if (s.length <= max) return s
    val head = max * 2 / 3
    val tail = max - head
    return s.take(head) + "\n\n… [${s.length - max} chars truncated] …\n\n" + s.takeLast(tail)
}
