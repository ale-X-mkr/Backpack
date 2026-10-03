package com.backpack.app.core

import java.net.URLDecoder

/**
 * A destination handed over by another app: "navigate here" / "show this
 * place" links, so Backpack can be chosen as the navigation app.
 *
 * Understood:
 * - geo:lat,lon  ·  geo:lat,lon?q=lat,lon(Label)  ·  geo:0,0?q=address
 * - google.navigation:q=lat,lon  ·  google.navigation:q=address&mode=d|w|b|l
 * - Google Maps web links with the place in them: ?q= / ?ll= / ?daddr=,
 *   /maps/dir/?api=1&destination=..., /maps/search/?api=1&query=...,
 *   /maps/dir/.../<destination>, /maps/place/.../@lat,lon,z, /maps/@lat,lon,z.
 * Short links (maps.app.goo.gl) are not: reading them means asking Google.
 */
data class NavLink(
    /** Coordinates when the link has them. */
    val point: LatLon?,
    /** Address / place text to search when there are no coordinates. */
    val query: String?,
    /** Label shown to you ("Rue X", "Hotel Y"...). */
    val label: String?,
    /** Travel mode asked by the sender (google.navigation mode=, travelmode=). */
    val mode: TravelMode?
) {
    companion object {

        fun parse(link: String?): NavLink? {
            val raw = link?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val lower = raw.lowercase()
            return try {
                when {
                    lower.startsWith("geo:") -> geo(raw.substring(4))
                    lower.startsWith("google.navigation:") -> navigation(raw.substring("google.navigation:".length))
                    lower.startsWith("http://") || lower.startsWith("https://") -> web(raw)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace('+', ' ').trim()

        /** Query parameters (first value wins), decoded. */
        private fun params(query: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val i = part.indexOf('=')
                val k = (if (i < 0) part else part.substring(0, i)).lowercase()
                val v = if (i < 0) "" else URLDecoder.decode(part.substring(i + 1), "UTF-8").trim()
                if (k !in out) out[k] = v
            }
            return out
        }

        /** "45.19,5.72" (maybe with spaces / a "(label)") -> point, else null. */
        fun coordinates(text: String?): LatLon? {
            val m = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)").find(text ?: return null) ?: return null
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return LatLon(lat, lon)
        }

        private fun labelOf(text: String): String? =
            Regex("\\(([^)]+)\\)\\s*$").find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        private fun fromText(text: String?, mode: TravelMode? = null): NavLink? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val p = coordinates(t)
            return if (p != null) NavLink(p, null, labelOf(t), mode)
            else NavLink(null, t, t, mode)
        }

        private fun geo(rest: String): NavLink? {
            val q = rest.indexOf('?')
            val path = if (q < 0) rest else rest.substring(0, q)
            val ps = if (q < 0) emptyMap() else params(rest.substring(q + 1))
            // The q= place wins over the map centre in the path.
            fromText(ps["q"])?.let { return it }
            val p = coordinates(decode(path))
            if (p != null && !(p.lat == 0.0 && p.lon == 0.0)) return NavLink(p, null, null, null)
            return null
        }

        private fun navigation(rest: String): NavLink? {
            val ps = params(rest.removePrefix("//"))
            val mode = when (ps["mode"]?.lowercase()) {
                "d" -> TravelMode.DRIVING
                "w" -> TravelMode.WALKING
                "b" -> TravelMode.CYCLING
                else -> null
            }
            return fromText(ps["q"] ?: ps["ll"], mode)
        }

        private fun web(url: String): NavLink? {
            val noScheme = url.substringAfter("://")
            val host = noScheme.substringBefore('/').substringBefore('?').lowercase()
            if (host != "maps.google.com" && host != "google.com" && host != "www.google.com" &&
                !host.matches(Regex("(www\\.)?google\\.[a-z.]{2,6}")) && !host.matches(Regex("maps\\.google\\.[a-z.]{2,6}"))
            ) return null
            val pathAndQuery = noScheme.removePrefix(noScheme.substringBefore('/'))
            val path = pathAndQuery.substringBefore('?').substringBefore('#')
            val ps = if ('?' in pathAndQuery) params(pathAndQuery.substringAfter('?').substringBefore('#')) else emptyMap()
            val mode = when (ps["travelmode"]?.lowercase() ?: ps["dirflg"]?.lowercase()) {
                "driving", "d" -> TravelMode.DRIVING
                "walking", "w" -> TravelMode.WALKING
                "bicycling", "b" -> TravelMode.CYCLING
                else -> null
            }
            if (!host.startsWith("maps.") && !path.startsWith("/maps")) return null
            for (key in listOf("destination", "daddr", "q", "query", "ll")) {
                fromText(ps[key], mode)?.let { return it }
            }
            // /maps/dir/<from>/<to>/... : the last stop is the destination.
            if (path.startsWith("/maps/dir/")) {
                val parts = path.removePrefix("/maps/dir/").split('/').map { decode(it) }
                    .filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
                fromText(parts.lastOrNull(), mode)?.let { return it }
            }
            // /maps/place/<name>/@lat,lon,17z  or  /maps/@lat,lon,15z
            val at = Regex("/@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)").find(path)
            val atPoint = at?.let { coordinates("${it.groupValues[1]},${it.groupValues[2]}") }
            val name = if (path.startsWith("/maps/place/")) decode(path.removePrefix("/maps/place/").substringBefore('/')).ifBlank { null } else null
            if (atPoint != null) return NavLink(atPoint, null, name, mode)
            if (name != null) return fromText(name, mode)
            return null
        }
    }
}
