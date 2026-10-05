package com.backpack.app.core

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class TouristCategory(val label: String) {
    SIGHT("Sight"),
    MUSEUM("Museum"),
    HISTORIC("Historic"),
    VIEWPOINT("Viewpoint"),
    NATURE("Nature"),
    /** 1.1.0: waterfalls, springs, hot springs, geysers. */
    WATER("Waterfall / spring"),
    /** 1.1.0: national parks, nature reserves, protected areas. */
    PARK("Nature park"),
    /** 1.1.0: UNESCO World Heritage. */
    HERITAGE("World Heritage")
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
    val alongM: Double = 0.0,
    /** Article found through Wikidata when the OSM data has no wikipedia tag. */
    val articleUrl: String? = null,
    /** 1.1.0: Wikivoyage travel guide page, when there is one. */
    val voyageUrl: String? = null
) {
    /** Same place twice (area and point, or two sources): one key. Named
     *  natural spots without Wikidata (1.1.0) use their OSM key. */
    val identity: String get() = wikidata.ifEmpty { key }

    /** 0..3 stars from how widely it is written about. */
    val stars: Int get() = when {
        sitelinks >= 50 -> 3
        sitelinks >= 15 -> 2
        sitelinks >= 3 -> 1
        else -> 0
    }

    /** Wikipedia article URL from the wikipedia tag, if present. */
    val wikipediaUrl: String? get() = Places.wikipediaUrl(wikipedia)

    /** Wikipedia article when tagged, else the Wikidata page (every place here has one). */
    val readMoreUrl: String? get() = wikipediaUrl ?: articleUrl ?: wikidata.takeIf { it.isNotBlank() }?.let { "https://www.wikidata.org/wiki/$it" }
}

/**
 * Tourist mode, data side: which OSM objects count, the Overpass query
 * along a route corridor or in a box, parsing, Wikidata popularity, ranking
 * and a quick detour estimate. Only objects that have a Wikidata entry are
 * considered ("well noted"), ranked by how many Wikipedia/Wikimedia sites
 * have an article about them.
 */
object Tourist {

    // 1.0.x kinds (kept light: only places with a Wikidata entry).
    private val BASE = listOf(
        "[\"tourism\"~\"^(attraction|museum|gallery|viewpoint|theme_park|zoo|aquarium)$\"][\"wikidata\"]",
        "[\"historic\"~\"^(castle|monument|ruins|archaeological_site|fort|manor|city_gate|palace|tower|monastery)$\"][\"wikidata\"]",
        "[\"natural\"~\"^(peak|waterfall|cave_entrance|volcano|glacier|arch|beach|gorge)$\"][\"wikidata\"]",
        "[\"amenity\"=\"place_of_worship\"][\"building\"~\"^(cathedral|basilica)$\"][\"wikidata\"]"
    )

    /** 1.1.1: the new kinds, fetched "on top" of squares already on the phone.
     *  Parks only as points / ways ("nw:"): their big boundary relations are
     *  what made the servers slow. */
    private val EXTRA = listOf(
        "[\"historic\"~\"^(citywalls|aqueduct)$\"][\"wikidata\"]",
        "[\"natural\"~\"^(spring|hot_spring|geyser)$\"][\"wikidata\"]",
        "[\"waterway\"=\"waterfall\"][\"wikidata\"]",
        "[\"man_made\"=\"lighthouse\"][\"wikidata\"]",
        "[\"heritage:operator\"=\"whc\"][\"wikidata\"]",
        "nw:[\"boundary\"=\"national_park\"][\"wikidata\"]",
        "nw:[\"leisure\"=\"nature_reserve\"][\"wikidata\"]"
    )

    /** Overpass statements for [selector] = "(bbox)" / "(around:...)" / "(poly:...)". */
    private fun statements(selector: String, extrasOnly: Boolean = false): List<String> =
        (if (extrasOnly) EXTRA else BASE + EXTRA).map { l ->
            if (l.startsWith("nw:")) " nw$selector${l.removePrefix("nw:")};" else " nwr$selector$l;"
        }

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
        return query("(around:$radius,$coords)")
    }

    /** Same, around several route lines at once (one request for all
     *  alternatives, used by the Weekend sights preference). */
    fun corridorQueryMulti(lines: List<List<LatLon>>, radius: Int): String {
        val selectors = lines.filter { it.isNotEmpty() }.map { line ->
            "(around:$radius," + line.joinToString(",") { "%.3f,%.3f".format(java.util.Locale.ROOT, it.lat, it.lon) } + ")"
        }
        val parts = selectors.flatMap { sel -> statements(sel) }.joinToString("\n")
        return "[out:json][timeout:25];\n(\n$parts\n);\nout center tags;"
    }

    /** One request for several map squares (cheaper for the server than a
     *  query around a route line, and cached per square on the phone). */
    fun tileQuery(tiles: List<Tile>, extrasOnly: Boolean = false): String {
        val parts = tiles.flatMap { t -> statements(t.bbox, extrasOnly) }.joinToString("\n")
        return "[out:json][timeout:25];\n(\n$parts\n);\nout center tags;"
    }

    /** How far ahead along a route places are looked for at once, by mode. */
    fun searchAheadM(mode: TravelMode): Double = when (mode) {
        TravelMode.WALKING -> 15_000.0
        TravelMode.CYCLING -> 60_000.0
        TravelMode.DRIVING -> 120_000.0
    }

    // ---------- Cache format (sitelinks are cached apart) ----------

    fun toJson(list: List<TouristPoi>): String {
        val a = org.json.JSONArray()
        for (p in list) {
            val o = JSONObject().put("k", p.key).put("n", p.name).put("a", p.point.lat).put("o", p.point.lon)
                .put("c", p.category.name).put("d", p.wikidata)
            p.wikipedia?.let { o.put("w", it) }
            a.put(o)
        }
        return a.toString()
    }

    fun fromJson(text: String): List<TouristPoi> {
        val a = org.json.JSONArray(text)
        val out = mutableListOf<TouristPoi>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val cat = TouristCategory.values().firstOrNull { it.name == o.optString("c", "") } ?: continue
            out.add(TouristPoi(o.optString("k", ""), o.optString("n", ""), LatLon(o.optDouble("a"), o.optDouble("o")),
                cat, o.optString("d", ""), o.optString("w", "").ifBlank { null }))
        }
        return out
    }

    /** Groups places by square; every queried square gets an entry (maybe empty). */
    fun byTile(pois: List<TouristPoi>, queried: List<Tile>): Map<Tile, List<TouristPoi>> {
        val wanted = queried.toSet()
        val out = LinkedHashMap<Tile, MutableList<TouristPoi>>()
        for (t in queried) out[t] = mutableListOf()
        for (p in pois) {
            val t = Tiles.of(p.point)
            if (t in wanted) out.getValue(t).add(p)
        }
        return out
    }

    /** Overpass query for places inside a box. */
    fun boxQuery(south: Double, west: Double, north: Double, east: Double): String =
        query("(%.5f,%.5f,%.5f,%.5f)".format(java.util.Locale.ROOT, south, west, north, east))

    /** Overpass query for places inside the reach polygon (one request, for
     *  areas too big for the cached squares, e.g. 1 h by car). */
    fun reachQuery(ring: List<LatLon>): String {
        val parts = statements("(poly:\"${Reach.polyFilter(ring)}\")").joinToString("\n")
        return "[out:json][timeout:25];\n(\n$parts\n);\nout center tags;"
    }

    private fun query(selector: String): String {
        val parts = statements(selector).joinToString("\n")
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
        val boundary = tags.optString("boundary", "")
        return when {
            tags.optString("heritage:operator", "") == "whc" -> TouristCategory.HERITAGE
            boundary == "national_park" || boundary == "protected_area" || tags.optString("leisure", "") == "nature_reserve" -> TouristCategory.PARK
            natural in setOf("waterfall", "spring", "hot_spring", "geyser") || tags.optString("waterway", "") == "waterfall" -> TouristCategory.WATER
            tags.optString("man_made", "") == "lighthouse" -> TouristCategory.HISTORIC
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
            val name = tags.optString("name", "").ifBlank { tags.optString("name:en", "") }
            if (name.isBlank()) continue
            // Without Wikidata only for the named natural spots / viewpoints / UNESCO lines.
            val wikidata = tags.optString("wikidata", "").takeIf { it.matches(Regex("Q[0-9]+")) } ?: ""
            val category = classify(tags) ?: continue
            // 1.1.1: only places with a Wikidata entry (as in 1.0.x).
            if (wikidata.isEmpty()) continue
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
        return out.distinctBy { it.identity }
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

    /** Popularity of a place, the best Wikipedia article to read about it,
     *  and (1.1.0) its Wikivoyage travel guide page. */
    data class SiteInfo(val sitelinks: Int, val articleUrl: String?, val voyageUrl: String? = null)

    /**
     * id -> sitelink count + article in the first of [languages] that has
     * one (e.g. the phone's language, then English).
     */
    fun parseSiteInfo(body: String, languages: List<String>): Map<String, SiteInfo> {
        val entities = JSONObject(body).optJSONObject("entities") ?: return emptyMap()
        val out = HashMap<String, SiteInfo>()
        val keys = entities.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val links = entities.optJSONObject(id)?.optJSONObject("sitelinks") ?: continue
            var url: String? = null
            for (lang in languages) {
                val title = links.optJSONObject(lang.replace('-', '_') + "wiki")?.optString("title", "").orEmpty()
                if (title.isNotBlank()) { url = Places.wikipediaUrl("$lang:$title"); break }
            }
            out[id] = SiteInfo(links.length(), url, voyageUrl(links, languages))
        }
        return out
    }

    /** 1.1.0: Wikivoyage page from a Wikidata sitelinks object, first language that has one. */
    fun voyageUrl(links: JSONObject, languages: List<String>): String? {
        for (lang in languages) {
            val title = links.optJSONObject(lang.replace('-', '_') + "wikivoyage")?.optString("title", "").orEmpty()
            if (title.isNotBlank()) {
                return "https://$lang.wikivoyage.org/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/").replace("%3A", ":")
            }
        }
        return null
    }

    // ---------- 1.1.0: Wikipedia "nearby" as a second source ----------

    /** Wikipedia articles with coordinates around [center] (max 10 km),
     *  with their Wikidata id. */
    fun wikiNearbyUrl(lang: String, center: LatLon, radiusM: Int): String =
        "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=geosearch" +
            "&ggscoord=%.5f%%7C%.5f".format(java.util.Locale.ROOT, center.lat, center.lon) +
            "&ggsradius=${radiusM.coerceIn(10, 10000)}&ggslimit=50&prop=coordinates%7Cpageprops&ppprop=wikibase_item"

    data class WikiHit(val wikidata: String, val title: String, val point: LatLon)

    fun parseWikiNearby(body: String): List<WikiHit> {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = mutableListOf<WikiHit>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val p = pages.optJSONObject(keys.next()) ?: continue
            val q = p.optJSONObject("pageprops")?.optString("wikibase_item", "").orEmpty()
            val c = p.optJSONArray("coordinates")?.optJSONObject(0) ?: continue
            if (!q.matches(Regex("Q[0-9]+"))) continue
            out.add(WikiHit(q, p.optString("title", ""), LatLon(c.optDouble("lat"), c.optDouble("lon"))))
        }
        return out
    }

    /** Kinds of Wikidata items (P31, direct) that count as places to visit. */
    val WIKIDATA_KINDS: Map<String, TouristCategory> = mapOf(
        "Q570116" to TouristCategory.SIGHT, "Q4989906" to TouristCategory.HISTORIC, "Q23413" to TouristCategory.HISTORIC,
        "Q16560" to TouristCategory.HISTORIC, "Q57821" to TouristCategory.HISTORIC, "Q839954" to TouristCategory.HISTORIC,
        "Q109607" to TouristCategory.HISTORIC, "Q44613" to TouristCategory.HISTORIC, "Q160742" to TouristCategory.HISTORIC,
        "Q2977" to TouristCategory.HISTORIC, "Q16970" to TouristCategory.HISTORIC, "Q39715" to TouristCategory.HISTORIC,
        "Q12518" to TouristCategory.HISTORIC, "Q33506" to TouristCategory.MUSEUM, "Q207694" to TouristCategory.MUSEUM,
        "Q34038" to TouristCategory.WATER, "Q124714" to TouristCategory.WATER, "Q177380" to TouristCategory.WATER,
        "Q8502" to TouristCategory.NATURE, "Q35509" to TouristCategory.NATURE, "Q40080" to TouristCategory.NATURE,
        "Q8072" to TouristCategory.NATURE, "Q46169" to TouristCategory.PARK, "Q179049" to TouristCategory.PARK,
        "Q6017969" to TouristCategory.VIEWPOINT, "Q9259" to TouristCategory.HERITAGE
    )

    /** SPARQL for the direct kinds (P31) of [ids] (Wikidata Query Service). */
    fun kindsQuery(ids: List<String>): String =
        "SELECT ?item ?kind WHERE { VALUES ?item { " + ids.joinToString(" ") { "wd:$it" } + " } ?item wdt:P31 ?kind . }"

    /** id -> category, only for the kinds above (heritage wins, then the first found). */
    fun parseKinds(body: String): Map<String, TouristCategory> {
        val rows = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyMap()
        val out = HashMap<String, TouristCategory>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val item = r.optJSONObject("item")?.optString("value", "").orEmpty().substringAfterLast('/')
            val kind = r.optJSONObject("kind")?.optString("value", "").orEmpty().substringAfterLast('/')
            val cat = WIKIDATA_KINDS[kind] ?: continue
            if (out[item] == null || cat == TouristCategory.HERITAGE) out[item] = cat
        }
        return out
    }

    /** 1.1.0: Nominatim reverse (town level) -> (name, Wikidata id) for the destination's travel guide. */
    fun parseReverseTown(body: String): Pair<String, String?>? {
        val o = JSONObject(body)
        val name = o.optString("name", "").ifBlank { o.optJSONObject("address")?.let { a ->
            listOf("city", "town", "village", "municipality").map { a.optString(it, "") }.firstOrNull { it.isNotBlank() }
        }.orEmpty() }
        if (name.isBlank()) return null
        val q = o.optJSONObject("extratags")?.optString("wikidata", "").orEmpty().takeIf { it.matches(Regex("Q[0-9]+")) }
        return name to q
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

    /** 1.0.9: distance off the route a place may be for a there-and-back
     *  detour of at most [seconds] (inverse of [estimateDetourSeconds]),
     *  kept within what one search covers well for the mode. */
    fun offsetForDetour(seconds: Double, mode: TravelMode): Double {
        val speed = when (mode) {
            TravelMode.WALKING -> 1.3
            TravelMode.CYCLING -> 4.5
            TravelMode.DRIVING -> 11.0
        }
        val cap = when (mode) {
            TravelMode.WALKING -> 1500.0
            TravelMode.CYCLING -> 4000.0
            TravelMode.DRIVING -> 10000.0
        }
        return (seconds * speed / 2.6).coerceIn(100.0, cap)
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
