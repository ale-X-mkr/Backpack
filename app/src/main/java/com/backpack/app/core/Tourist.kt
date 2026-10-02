package com.backpack.app.core

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class TouristCategory(val label: String) {
    SIGHT("Sight"),
    MUSEUM("Museum"),
    HISTORIC("Historic"),
    VIEWPOINT("Viewpoint"),
    NATURE("Nature")
}

data class TouristPoi(
    /** "n123" / "w456" / "r789": OSM element. */
    val key: String,
    val name: String,
    val point: LatLon,
    val category: TouristCategory,
    val wikidata: String,
    /** "fr:Tour Eiffel" style tag, if any. */
    val wikipedia: String?,
    /** Wikipedia/Wikimedia articles about it, in all languages: the
     *  "well known" measure. 0 when unknown. */
    val sitelinks: Int = 0,
    /** Distance from the route line (0 without a route). */
    val offsetM: Double = 0.0,
    /** Position along the route (0 without a route). */
    val alongM: Double = 0.0
) {
    /** 0..3 stars from how widely it is written about. */
    val stars: Int get() = when {
        sitelinks >= 50 -> 3
        sitelinks >= 15 -> 2
        sitelinks >= 3 -> 1
        else -> 0
    }

    /** Wikipedia article URL from the wikipedia tag, if present. */
    val wikipediaUrl: String? get() {
        val w = wikipedia ?: return null
        val i = w.indexOf(':')
        if (i <= 0 || i >= w.length - 1) return null
        val lang = w.substring(0, i)
        if (!lang.all { it.isLetter() || it == '-' }) return null
        val title = w.substring(i + 1).replace(' ', '_')
        return "https://$lang.wikipedia.org/wiki/" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "_")
    }
}

/**
 * Tourist mode, data side: which OSM objects count, the Overpass query
 * along a route corridor or in a box, parsing, Wikidata popularity, ranking
 * and a quick detour estimate. Only objects that have a Wikidata entry are
 * considered ("well noted"), ranked by how many Wikipedia/Wikimedia sites
 * have an article about them.
 */
object Tourist {

    private const val FILTERS = """
 ["tourism"~"^(attraction|museum|gallery|viewpoint|theme_park|zoo|aquarium)$"]["wikidata"];
 ["historic"~"^(castle|monument|ruins|archaeological_site|fort|manor|city_gate|palace|tower|monastery)$"]["wikidata"];
 ["natural"~"^(peak|waterfall|cave_entrance|volcano|glacier|arch|beach|gorge)$"]["wikidata"];
 ["amenity"="place_of_worship"]["building"~"^(cathedral|basilica)$"]["wikidata"];"""

    /** Corridor half-width around the route, by travel mode. */
    fun corridorRadius(mode: TravelMode): Int = when (mode) {
        TravelMode.WALKING -> 400
        TravelMode.CYCLING -> 1500
        TravelMode.DRIVING -> 4000
    }

    /** Overpass query for places within [radius] m of the route line.
     *  Coordinates rounded to 3 decimals (~100 m): the corridor is wider
     *  than that, and the exact start / end of the trip is not sent. */
    fun corridorQuery(line: List<LatLon>, radius: Int): String {
        val coords = line.joinToString(",") { "%.3f,%.3f".format(java.util.Locale.ROOT, it.lat, it.lon) }
        return query("nwr(around:$radius,$coords)")
    }

    /** Same, around several route lines at once (one request for all
     *  alternatives, used by the Weekend sights preference). */
    fun corridorQueryMulti(lines: List<List<LatLon>>, radius: Int): String {
        val selectors = lines.filter { it.isNotEmpty() }.map { line ->
            "nwr(around:$radius," + line.joinToString(",") { "%.3f,%.3f".format(java.util.Locale.ROOT, it.lat, it.lon) } + ")"
        }
        val parts = selectors.flatMap { sel -> FILTERS.trim().lines().map { " $sel${it.trim()}" } }.joinToString("\n")
        return "[out:json][timeout:25];\n(\n$parts\n);\nout center tags;"
    }

    /** Overpass query for places inside a box. */
    fun boxQuery(south: Double, west: Double, north: Double, east: Double): String =
        query("nwr(%.5f,%.5f,%.5f,%.5f)".format(java.util.Locale.ROOT, south, west, north, east))

    private fun query(selector: String): String {
        val parts = FILTERS.trim().lines().joinToString("\n") { " $selector${it.trim()}" }
        // No result cap: Overpass returns in id order (points before areas), so a
        // cap would cut castles and cathedrals mapped as areas before ranking.
        return "[out:json][timeout:25];\n(\n$parts\n);\nout center tags;"
    }

    /**
     * Privacy: leaves out the first and last [trimM] metres of a route
     * (your home or hotel is usually one end) when the route is long
     * enough to still have a corridor afterwards.
     */
    fun trimEnds(points: List<LatLon>, trimM: Double = 500.0): List<LatLon> {
        if (points.size < 2) return points
        val line = Polyline(points)
        if (line.length < trimM * 6) return points
        val kept = points.indices.filter { line.cumulative[it] >= trimM && line.cumulative[it] <= line.length - trimM }
        return if (kept.size >= 2) kept.map { points[it] } else points
    }

    /**
     * Route points reduced for the query: one point every [stepM] metres
     * (at most [maxPoints]), always keeping start and end.
     */
    fun sampleLine(points: List<LatLon>, maxPoints: Int = 80, minStepM: Double = 200.0): List<LatLon> {
        if (points.size <= 2) return points
        val line = Polyline(points)
        val step = max(minStepM, line.length / (maxPoints - 1))
        val out = mutableListOf(points.first())
        var nextAt = step
        for (i in 1 until points.size - 1) {
            if (line.cumulative[i] >= nextAt) {
                out.add(points[i])
                nextAt = line.cumulative[i] + step
            }
        }
        out.add(points.last())
        return out
    }

    fun classify(tags: JSONObject): TouristCategory? {
        val tourism = tags.optString("tourism", "")
        val historic = tags.optString("historic", "")
        val natural = tags.optString("natural", "")
        return when {
            tourism == "museum" || tourism == "gallery" -> TouristCategory.MUSEUM
            tourism == "viewpoint" -> TouristCategory.VIEWPOINT
            natural.isNotEmpty() && tourism.isEmpty() && historic.isEmpty() -> TouristCategory.NATURE
            historic.isNotEmpty() -> TouristCategory.HISTORIC
            tourism.isNotEmpty() -> TouristCategory.SIGHT
            tags.optString("amenity", "") == "place_of_worship" -> TouristCategory.HISTORIC
            natural.isNotEmpty() -> TouristCategory.NATURE
            else -> null
        }
    }

    /** Parses an Overpass "out center tags" answer. */
    fun parseOverpass(body: String): List<TouristPoi> {
        val elements = JSONObject(body).getJSONArray("elements")
        val out = mutableListOf<TouristPoi>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val wikidata = tags.optString("wikidata", "")
            if (!wikidata.matches(Regex("Q[0-9]+"))) continue
            val name = tags.optString("name", "").ifBlank { tags.optString("name:en", "") }
            if (name.isBlank()) continue
            val category = classify(tags) ?: continue
            val center = e.optJSONObject("center")
            val lat = center?.optDouble("lat", Double.NaN) ?: e.optDouble("lat", Double.NaN)
            val lon = center?.optDouble("lon", Double.NaN) ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val type = e.optString("type", "node")
            out.add(
                TouristPoi(
                    key = type.take(1) + e.optLong("id", 0L),
                    name = name,
                    point = LatLon(lat, lon),
                    category = category,
                    wikidata = wikidata,
                    wikipedia = tags.optString("wikipedia", "").ifBlank { null }
                )
            )
        }
        // A castle mapped as both an area and a point: keep one.
        return out.distinctBy { it.wikidata }
    }

    /** Wikidata wbgetentities URL for up to 50 ids, sitelinks only. */
    fun wikidataUrl(ids: List<String>): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks&format=json&ids=" +
            ids.take(50).joinToString("%7C")

    /** id -> number of sitelinks. */
    fun parseSitelinks(body: String): Map<String, Int> {
        val entities = JSONObject(body).optJSONObject("entities") ?: return emptyMap()
        val out = HashMap<String, Int>()
        val keys = entities.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val links = entities.optJSONObject(id)?.optJSONObject("sitelinks") ?: continue
            out[id] = links.length()
        }
        return out
    }

    /** Adds distance from / position along the route. */
    fun placeOnRoute(pois: List<TouristPoi>, route: Polyline?): List<TouristPoi> {
        if (route == null || route.points.size < 2) return pois
        return pois.map { p ->
            val s = route.snap(p.point)
            if (s == null) p else p.copy(offsetM = s.offsetM, alongM = s.alongM)
        }
    }

    /** Best known first; ties keep the order along the route. */
    fun rank(pois: List<TouristPoi>, limit: Int): List<TouristPoi> =
        pois.sortedWith(compareByDescending<TouristPoi> { it.sitelinks }.thenBy { it.alongM }).take(limit)

    /** Rough extra time to visit and come back to the route: there and
     *  back, roads ~30 % longer than straight. Exact value comes from the
     *  router when a place is opened. */
    fun estimateDetourSeconds(offsetM: Double, mode: TravelMode): Double {
        val speed = when (mode) {
            TravelMode.WALKING -> 1.3
            TravelMode.CYCLING -> 4.5
            TravelMode.DRIVING -> 11.0
        }
        return 2.0 * offsetM * 1.3 / speed
    }

    fun minutesLabel(seconds: Double): String {
        val m = (seconds / 60.0).roundToInt()
        return if (m < 1) "< 1 min" else if (m < 60) "$m min" else "%d h %02d".format(m / 60, m % 60)
    }

    /** Result of the Weekend sights preference. */
    data class ScenicChoice(val index: Int, val places: Int, val basePlaces: Int, val extraSeconds: Double)

    /** Sights score of one route: well-known places within [radius] of it,
     *  better known places count more (1 + stars each). */
    fun score(route: Polyline, pois: List<TouristPoi>, radius: Int): Pair<Double, Int> {
        var s = 0.0
        var n = 0
        for (p in pois) {
            val snap = route.snap(p.point) ?: continue
            if (snap.offsetM <= radius) { s += 1.0 + p.stars; n++ }
        }
        return Pair(s, n)
    }

    /**
     * Weekend: among the alternatives, the one passing clearly more
     * well-known places (≥ 2 more points and ≥ 25 % more), if it takes at
     * most [maxExtraFraction] more time than the current choice. Null when
     * the current choice stays.
     */
    fun scenicChoice(
        routes: List<RouteResult>,
        current: Int,
        pois: List<TouristPoi>,
        radius: Int,
        maxExtraFraction: Double
    ): ScenicChoice? {
        if (routes.size < 2 || maxExtraFraction <= 0.0 || pois.isEmpty()) return null
        val base = routes.getOrNull(current) ?: return null
        val (baseScore, baseCount) = score(base.line, pois, radius)
        var best: ScenicChoice? = null
        var bestScore = baseScore
        for (i in routes.indices) {
            if (i == current) continue
            val r = routes[i]
            if (r.durationSeconds > base.durationSeconds * (1.0 + maxExtraFraction)) continue
            val (s, n) = score(r.line, pois, radius)
            if (s >= baseScore + 2.0 && s >= baseScore * 1.25 && s > bestScore) {
                bestScore = s
                best = ScenicChoice(i, n, baseCount, (r.durationSeconds - base.durationSeconds).coerceAtLeast(0.0))
            }
        }
        return best
    }

    /** Where a new stop goes among existing ones: ordered along the route. */
    fun insertStop(existing: List<LatLon>, newStop: LatLon, route: Polyline?): List<LatLon> {
        if (route == null || route.points.size < 2) return existing + newStop
        val all = existing + newStop
        return all.sortedBy { route.snap(it)?.alongM ?: Double.MAX_VALUE }
    }
}
