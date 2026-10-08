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

/**
 * 1.2.1: faster Overpass servers that need your own key (Settings >
 * Servers), tried before the free ones. The key is part of the address.
 * Russian services (e.g. NextGIS, VK) are left out on purpose.
 */
object OverpassProviders {
    class Provider(val id: String, val label: String, val note: String, private val template: String) {
        fun url(key: String): String? {
            val k = key.trim()
            if (k.isEmpty() || !k.matches(Regex("[A-Za-z0-9._~-]+"))) return null
            return template.replace("{key}", k)
        }
    }

    val ALL = listOf(
        Provider("fairwaymapper", "FairwayMapper", "free for active OpenStreetMap contributors", "https://api.fairwaymapper.com/k/{key}/api/interpreter"),
        Provider("geofabrik", "Geofabrik", "paid", "https://overpass.geofabrik.de/{key}/api/interpreter"),
        Provider("tracestrack", "Tracestrack", "paid", "https://api.tracestrack.com/overpass/{key}/interpreter")
    )

    fun byId(id: String?): Provider? = ALL.firstOrNull { it.id == id }
}

