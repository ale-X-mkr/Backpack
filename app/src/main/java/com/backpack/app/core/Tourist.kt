package com.backpack.app.core

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class TouristCategory(private val key: String, private val en: String) {
    /** 1.4.0: decorated caves, rock shelters, dolmens, archaeological sites. */
    PREHISTORY("core_cat_prehistory", "Prehistory / cave"),
    SIGHT("core_cat_sight", "Sight"),
    MUSEUM("core_cat_museum", "Museum"),
    HISTORIC("core_cat_historic", "Historic"),
    VIEWPOINT("core_cat_viewpoint", "Viewpoint"),
    NATURE("core_cat_nature", "Nature"),
    /** 1.1.0: waterfalls, springs, hot springs, geysers. */
    WATER("core_cat_water", "Waterfall / spring"),
    /** 1.1.0: national parks, nature reserves, protected areas. */
    PARK("core_cat_park", "Nature park"),
    /** 1.1.0: UNESCO World Heritage. */
    HERITAGE("core_cat_heritage", "World Heritage");

    /** 1.6.0: in the screen language. */
    val label: String get() = Txt.s(key, en)
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

/** 1.2.0: kinds of sights (Settings > Sights), each a set of categories. */
enum class SightGroup(private val key: String, private val en: String, val kinds: Set<TouristCategory>) {
    PREHISTORY("core_group_prehistory", "Prehistory & caves (decorated caves, rock shelters, dolmens, archaeology)", setOf(TouristCategory.PREHISTORY)),
    HISTORIC("core_group_historic", "Historic (castles, ruins, monuments, UNESCO)", setOf(TouristCategory.HISTORIC, TouristCategory.HERITAGE)),
    NATURE("core_group_nature", "Nature (waterfalls, springs, caves, peaks, viewpoints)", setOf(TouristCategory.NATURE, TouristCategory.WATER, TouristCategory.VIEWPOINT)),
    PARKS("core_group_parks", "Parks (national parks, reserves)", setOf(TouristCategory.PARK)),
    CULTURE("core_group_culture", "Culture (museums, galleries, zoos, attractions)", setOf(TouristCategory.MUSEUM, TouristCategory.SIGHT));

    /** 1.6.0: in the screen language. */
    val label: String get() = Txt.s(key, en)

    companion object {
        /** Saved "HISTORIC,NATURE" -> groups; nothing saved = all. */
        fun parse(saved: String?): Set<SightGroup> =
            saved?.split(',')?.mapNotNull { n -> values().firstOrNull { it.name == n } }?.toSet() ?: values().toSet()
        fun save(groups: Set<SightGroup>): String = groups.joinToString(",") { it.name }

        /** 1.4.0 default priority: prehistory first (Alex). */
        val DEFAULT_ORDER = listOf(PREHISTORY, NATURE, HISTORIC, CULTURE, PARKS)

        /** Saved order "NATURE,PREHISTORY,..." (missing groups appended in default order). */
        fun parseOrder(saved: String?): List<SightGroup> {
            val got = saved?.split(',')?.mapNotNull { n -> values().firstOrNull { it.name == n } }?.distinct().orEmpty()
            return got + DEFAULT_ORDER.filter { it !in got }
        }
        fun saveOrder(order: List<SightGroup>): String = order.joinToString(",") { it.name }
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

    /** 1.4.0: your order of kinds (Settings > Sights), first = most wanted. */
    @Volatile var order: List<SightGroup> = SightGroup.DEFAULT_ORDER

    /** Bonus of a kind by its place in [order]: 8, 6, 4, 2, 0 (one step = about 7x more articles). */
    private fun bonus(c: TouristCategory): Double {
        val i = order.indexOfFirst { c in it.kinds }
        if (i < 0) return 0.0
        return 2.0 * (order.size - 1 - i)
    }

    /**
     * 1.4.0 ranking: how widely a place is written about (log scale) plus
     * your kind order -- with prehistory first and castles third, a cave
     * written about in 3 Wikipedias comes before a castle in 200.
     */
    fun score(p: TouristPoi): Double = Math.log(1.0 + p.sitelinks) + bonus(p.category)

    /**
     * 1.2.0 voice heads-up: how far ahead (m) a sight is announced, from the
     * minutes chosen in Settings and the route's own average speed (so the
     * same minutes on foot, by bike or by car). Kept within 100 m .. 10 km.
     */
    fun headsUpLeadM(minutes: Int, routeLengthM: Double, routeSeconds: Double, mode: TravelMode): Double {
        val fallback = when (mode) { TravelMode.WALKING -> 1.3; TravelMode.CYCLING -> 4.2; TravelMode.DRIVING -> 15.0 }
        val speed = if (routeSeconds > 60.0 && routeLengthM > 0.0) routeLengthM / routeSeconds else fallback
        return (minutes * 60.0 * speed).coerceIn(100.0, 10_000.0)
    }

    /** 1.2.0 read aloud (1.3.0 any language): Wikipedia title of a Wikidata item in [lang]. */
    fun wikiTitleUrl(qid: String, lang: String = "en"): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=sitelinks&sitefilter=${lang}wiki&ids=" + qid

    fun parseWikiTitle(body: String, qid: String, lang: String = "en"): String? =
        org.json.JSONObject(body).optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("sitelinks")
            ?.optJSONObject("${lang}wiki")?.optString("title", "")?.takeIf { it.isNotBlank() }

    fun enwikiTitleUrl(qid: String): String = wikiTitleUrl(qid, "en")
    fun parseEnwikiTitle(body: String, qid: String): String? = parseWikiTitle(body, qid, "en")

    /** Title in [lang] from a "https://xx.wikipedia.org/wiki/Title" link or an "xx:Title" tag. */
    fun titleIn(lang: String, articleUrl: String?, wikipediaTag: String?): String? {
        articleUrl?.let { u ->
            val m = Regex("^https?://" + Regex.escape(lang) + "\\.(?:m\\.)?wikipedia\\.org/wiki/(.+)$").find(u)
            if (m != null) return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").replace('_', ' ')
        }
        wikipediaTag?.let { t -> if (t.startsWith("$lang:")) return t.substring(lang.length + 1).trim().ifBlank { null } }
        return null
    }

    fun englishTitle(articleUrl: String?, wikipediaTag: String?): String? = titleIn("en", articleUrl, wikipediaTag)

    /** Wikipedia REST summary (first paragraph, plain text) of an article in [lang]. */
    fun summaryUrl(title: String, lang: String = "en"): String =
        "https://$lang.wikipedia.org/api/rest_v1/page/summary/" +
            java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")

    fun parseSummary(body: String): String? =
        org.json.JSONObject(body).optString("extract", "").trim().ifBlank { null }

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
        "[\"historic\"~\"^(citywalls|aqueduct|megalith|rock_shelter)$\"][\"wikidata\"]",
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
            // 1.4.0: caves and archaeology first (also when UNESCO, like the Vézère valley).
            natural == "cave_entrance" || historic in setOf("archaeological_site", "megalith", "rock_shelter") ||
                tags.optString("historic:civilization", "").contains("prehistor") -> TouristCategory.PREHISTORY
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

    /** [description]: 1.4.0 the short description ("Paleolithic decorated cave in Dordogne"). */
    data class WikiHit(val wikidata: String, val title: String, val point: LatLon, val description: String? = null)

    fun parseWikiNearby(body: String): List<WikiHit> {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = mutableListOf<WikiHit>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val p = pages.optJSONObject(keys.next()) ?: continue
            val q = p.optJSONObject("pageprops")?.optString("wikibase_item", "").orEmpty()
            val c = p.optJSONArray("coordinates")?.optJSONObject(0) ?: continue
            if (!q.matches(Regex("Q[0-9]+"))) continue
            out.add(WikiHit(q, p.optString("title", ""), LatLon(c.optDouble("lat"), c.optDouble("lon")),
                p.optString("description", "").ifBlank { null }))
        }
        return out
    }

    /** Kinds of Wikidata items (P31, direct) that count as places to visit. */
    val WIKIDATA_KINDS: Map<String, TouristCategory> = mapOf(
        "Q570116" to TouristCategory.SIGHT, "Q4989906" to TouristCategory.HISTORIC, "Q23413" to TouristCategory.HISTORIC,
        "Q16560" to TouristCategory.HISTORIC, "Q57821" to TouristCategory.HISTORIC, "Q839954" to TouristCategory.PREHISTORY,
        "Q109607" to TouristCategory.HISTORIC, "Q44613" to TouristCategory.HISTORIC, "Q160742" to TouristCategory.HISTORIC,
        "Q2977" to TouristCategory.HISTORIC, "Q16970" to TouristCategory.HISTORIC, "Q39715" to TouristCategory.HISTORIC,
        "Q12518" to TouristCategory.HISTORIC, "Q33506" to TouristCategory.MUSEUM, "Q207694" to TouristCategory.MUSEUM,
        "Q34038" to TouristCategory.WATER, "Q124714" to TouristCategory.WATER, "Q177380" to TouristCategory.WATER,
        "Q8502" to TouristCategory.NATURE, "Q35509" to TouristCategory.PREHISTORY, "Q40080" to TouristCategory.NATURE,
        "Q8072" to TouristCategory.NATURE, "Q46169" to TouristCategory.PARK, "Q179049" to TouristCategory.PARK,
        "Q6017969" to TouristCategory.VIEWPOINT, "Q9259" to TouristCategory.HERITAGE,
        // 1.2.1 (Wikipedia as main source): a few more common kinds.
        "Q43501" to TouristCategory.SIGHT, "Q194195" to TouristCategory.SIGHT, "Q2281788" to TouristCategory.SIGHT,
        "Q167346" to TouristCategory.PARK, "Q1007870" to TouristCategory.MUSEUM, "Q676050" to TouristCategory.HISTORIC,
        "Q16748868" to TouristCategory.HISTORIC, "Q32815" to TouristCategory.HISTORIC, "Q44539" to TouristCategory.HISTORIC,
        "Q83471" to TouristCategory.WATER, "Q35666" to TouristCategory.NATURE, "Q150784" to TouristCategory.NATURE
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

    // ---------- 1.2.1: Wikipedia as the main source of sights ----------

    /** Wikipedia cells: 0.1° squares (~11 x 8 km), one nearby-articles request each. */
    const val WIKI_CELL = 0.1
    /** Search radius around a cell's centre: covers the whole cell. */
    const val WIKI_RADIUS_M = 8000

    fun wikiCellOf(p: LatLon): Tile = Tile(kotlin.math.floor(p.lon / WIKI_CELL).toInt(), kotlin.math.floor(p.lat / WIKI_CELL).toInt())
    fun wikiCellCenter(t: Tile): LatLon = LatLon((t.y + 0.5) * WIKI_CELL, (t.x + 0.5) * WIKI_CELL)

    /** Cells within [radiusM] of the lines, nearest along first. */
    fun wikiCellsAlong(lines: List<List<LatLon>>, radiusM: Double): List<Pair<Tile, Double>> =
        Tiles.alongLinesOrdered(lines, radiusM, WIKI_CELL)

    /** Cells covering a box, nearest to its centre first, at most [max]. */
    fun wikiCellsInBox(south: Double, west: Double, north: Double, east: Double, max: Int): List<Tile> {
        val a = wikiCellOf(LatLon(south, west))
        val b = wikiCellOf(LatLon(north, east))
        val cy = (south + north) / 2
        val cx = (west + east) / 2
        val out = ArrayList<Tile>()
        for (x in a.x..b.x) for (y in a.y..b.y) out.add(Tile(x, y))
        return out.sortedBy { val c = wikiCellCenter(it); (c.lat - cy) * (c.lat - cy) + (c.lon - cx) * (c.lon - cx) }.take(max)
    }

    /** English Wikipedia: up to 500 articles with coordinates around the cell's centre, with their Wikidata ids. */
    fun wikiCellUrl(t: Tile): String {
        val c = wikiCellCenter(t)
        return "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=geosearch" +
            "&ggscoord=%.5f%%7C%.5f".format(java.util.Locale.ROOT, c.lat, c.lon) +
            "&ggsradius=$WIKI_RADIUS_M&ggslimit=500&prop=coordinates%7Cpageprops%7Cdescription&colimit=max&ppprop=wikibase_item"
    }

    /** SPARQL: direct kinds (P31) and number of Wikimedia articles of [ids] (sent by POST). */
    fun kindsLinksQuery(ids: List<String>): String =
        "SELECT ?item ?kind ?links WHERE { VALUES ?item { " + ids.joinToString(" ") { "wd:$it" } +
            " } ?item wikibase:sitelinks ?links . OPTIONAL { ?item wdt:P31 ?kind } }"

    /** 1.4.0: what Wikidata says of an item: its known kinds (direct P31) and articles count. */
    data class KindInfo(val kinds: Set<TouristCategory>, val links: Int)

    /** id -> kinds found among [WIKIDATA_KINDS] (maybe none) and sitelinks, for every item answered. */
    fun parseKindsLinks(body: String): Map<String, KindInfo> {
        val rows = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyMap()
        val kinds = HashMap<String, MutableSet<TouristCategory>>()
        val links = HashMap<String, Int>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val item = r.optJSONObject("item")?.optString("value", "").orEmpty().substringAfterLast('/')
            if (item.isEmpty()) continue
            links[item] = r.optJSONObject("links")?.optString("value", "0")?.toIntOrNull() ?: 0
            val kind = r.optJSONObject("kind")?.optString("value", "").orEmpty().substringAfterLast('/')
            val set = kinds.getOrPut(item) { HashSet() }
            WIKIDATA_KINDS[kind]?.let { set.add(it) }
        }
        return links.mapValues { (id, n) -> KindInfo(kinds[id].orEmpty(), n) }
    }

    private val PREHISTORY_WORDS = Regex("\\b(cave|caves|grotto|grotte|cavern|rock shelter|abri|prehistoric|pre-historic|paleolithic|palaeolithic|neolithic|mesolithic|upper paleolithic|dolmen|menhir|megalith\\w*|cromlech|stone circle|tumulus|cairn|petroglyph\\w*|rock art|cave painting\\w*|archaeological|archeological|excavation|oppidum|hillfort)\\b")
    private val KEYWORD_KINDS = listOf(
        Regex("\\b(museum|gallery|ecomuseum)\\b") to TouristCategory.MUSEUM,
        Regex("\\b(castle|château|chateau|fortress|citadel|fortified|abbey|monastery|priory|cathedral|basilica|palace|ruins|ruined|aqueduct|city walls|bastide|manor|keep|lighthouse|roman (theatre|amphitheatre|bridge|villa))\\b") to TouristCategory.HISTORIC,
        Regex("\\b(waterfall|cascade|hot spring|geyser)\\b") to TouristCategory.WATER,
        Regex("\\b(gorge|canyon|glacier|volcano|natural arch|cliffs?|peak|summit|viewpoint|belvedere)\\b") to TouristCategory.NATURE,
        Regex("\\b(national park|nature reserve|regional (natural )?park|botanical garden|arboretum)\\b") to TouristCategory.PARK,
        Regex("\\b(zoo|aquarium|amusement park|theme park|wildlife park)\\b") to TouristCategory.SIGHT
    )

    /**
     * 1.4.0: one kind from what Wikidata says (direct types) and the short
     * description (catches sub-types: "decorated cave", "rock shelter",
     * "megalithic site"...). Prehistory words win; null = not a place to visit.
     */
    fun categorize(kinds: Set<TouristCategory>, description: String?): TouristCategory? {
        val d = description?.lowercase().orEmpty()
        if (PREHISTORY_WORDS.containsMatchIn(d) || TouristCategory.PREHISTORY in kinds) return TouristCategory.PREHISTORY
        if (TouristCategory.HERITAGE in kinds) return TouristCategory.HERITAGE
        val order = listOf(TouristCategory.MUSEUM, TouristCategory.HISTORIC, TouristCategory.WATER, TouristCategory.NATURE,
            TouristCategory.PARK, TouristCategory.VIEWPOINT, TouristCategory.SIGHT)
        order.firstOrNull { it in kinds }?.let { return it }
        return KEYWORD_KINDS.firstOrNull { it.first.containsMatchIn(d) }?.second
    }

    /** Wikipedia hits of one cell (only those inside it) + Wikidata -> places. */
    fun wikiPlaces(cell: Tile, hits: List<WikiHit>, kinds: Map<String, KindInfo>): List<TouristPoi> =
        hits.filter { wikiCellOf(it.point) == cell }.distinctBy { it.wikidata }.mapNotNull { h ->
            val k = kinds[h.wikidata] ?: return@mapNotNull null
            val cat = categorize(k.kinds, h.description) ?: return@mapNotNull null
            TouristPoi("q" + h.wikidata, h.title, h.point, cat, h.wikidata, "en:${h.title}", sitelinks = k.links)
        }

    /**
     * 1.2.1: the best places spread along the whole route: every stretch of
     * [segmentM] gives its best first, then its second best... (so a long
     * route is not all one famous city); [limit] grows with the length.
     */
    fun rankAlong(pois: List<TouristPoi>, routeLengthM: Double, segmentM: Double = 25_000.0, base: Int = 25, max: Int = 100): List<TouristPoi> {
        val limit = (base + 3 * (routeLengthM / segmentM).toInt()).coerceAtMost(max)
        if (pois.size <= limit) return rank(pois, limit)
        val groups = pois.groupBy { (it.alongM / segmentM).toInt() }.toSortedMap()
            .values.map { g -> g.sortedByDescending { score(it) } }
        val out = ArrayList<TouristPoi>()
        var round = 0
        while (out.size < limit) {
            var any = false
            for (g in groups) {
                if (round < g.size && out.size < limit) { out.add(g[round]); any = true }
            }
            if (!any) break
            round++
        }
        return out.sortedWith(compareByDescending<TouristPoi> { score(it) }.thenBy { it.alongM })
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
        pois.sortedWith(compareByDescending<TouristPoi> { score(it) }.thenBy { it.alongM }).take(limit)

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
