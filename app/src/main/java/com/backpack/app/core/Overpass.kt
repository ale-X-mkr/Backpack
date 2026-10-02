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
}
