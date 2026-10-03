package com.backpack.app.core

import org.json.JSONObject

/** Overpass answers that look fine but are not. */
object OverpassCheck {
    /**
     * Overpass reports a query that ran out of time or memory as HTTP 200
     * with an empty "elements" list and a "remark" such as
     * "runtime error: Query timed out in \"query\" at line 3 after 26 seconds."
     * Treated as a failure (try another server), never as "nothing found".
     */
    fun runtimeError(body: String): String? {
        if (!body.contains("\"remark\"")) return null
        return try {
            val remark = JSONObject(body).optString("remark", "")
            if (remark.contains("runtime error", ignoreCase = true) || remark.contains("timed out", ignoreCase = true)) remark else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads an Overpass /api/status page. 0 = a query slot is free now,
     * n > 0 = the next slot frees in n seconds, null = not understood.
     * "Rate limit: 0" means the server has no per-user limit.
     */
    fun statusWait(text: String): Int? {
        Regex("(\\d+) slots? available now").find(text)?.let { if (it.groupValues[1].toInt() > 0) return 0 }
        if (Regex("Rate limit:\\s*0\\b").containsMatchIn(text)) return 0
        val waits = Regex("in (-?\\d+) seconds?").findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (waits.isNotEmpty()) return waits.min().coerceAtLeast(0)
        return null
    }

    /** Status page address for an interpreter address (…/api/interpreter -> …/api/status). */
    fun statusUrl(interpreter: String): String? =
        if (interpreter.endsWith("/interpreter")) interpreter.removeSuffix("interpreter") + "status" else null
}
