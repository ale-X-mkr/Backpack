package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

/** An everyday place from OpenStreetMap (fuel, food, toilets...). */
data class PoiPlace(
    /** "n123" / "w456" from Overpass, "o123" from openrouteservice. */
    val key: String,
    val name: String,
    val point: LatLon,
    /** OSM kind, e.g. "fuel", "restaurant". */
    val kind: String,
    val website: String? = null,
    val wikipedia: String? = null,
    val wikidata: String? = null,
    val openingHours: String? = null,
    /** 1.1.1: which services it is (names of [Places.SERVICES]), e.g. "FOOD", "WIFI". */
    val services: Set<String> = emptySet(),
    /** Distance off the route line when searched along a route (else 0). */
    val offsetM: Double = 0.0
) {
    /** Link to read about the place: Wikipedia first, then Wikidata, then its website. */
    val readMoreUrl: String? get() =
        Places.wikipediaUrl(wikipedia) ?: wikidata?.let { "https://www.wikidata.org/wiki/$it" } ?: website?.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }

    val label: String get() = name.ifBlank { Places.kindLabel(kind) }
}

/**
 * Everyday places (POI button categories): Overpass queries per map square,
 * openrouteservice places as a second source, parsing and the small JSON
 * format used for the cache on the phone. No network code here.
 */
object Places {

    /** Places per square at most (busy city centres have hundreds). */
    const val PER_TILE = 60


    /** One service (Services panel): its Overpass filter, how to recognise it
     *  in the tags, and openrouteservice ids for the second source. */
    class ServiceDef(val name: String, val filter: String, val orsIds: Map<Int, String>, val matches: (JSONObject) -> Boolean)

    private fun JSONObject.v(k: String) = optString(k, "")

    val SERVICES: List<ServiceDef> = listOf(
        ServiceDef("FOOD", "[\"amenity\"~\"^(restaurant|cafe|fast_food)$\"]", mapOf(570 to "restaurant", 564 to "cafe", 566 to "fast_food")) {
            it.v("amenity") in setOf("restaurant", "cafe", "fast_food") },
        ServiceDef("LODGING", "[\"tourism\"~\"^(hotel|guest_house|motel)$\"]", mapOf(108 to "hotel", 106 to "guest_house", 109 to "motel")) {
            it.v("tourism") in setOf("hotel", "guest_house", "motel") },
        ServiceDef("PARKING", "[\"amenity\"=\"parking\"]", mapOf(601 to "parking")) { it.v("amenity") == "parking" },
        ServiceDef("FUEL", "[\"amenity\"~\"^(fuel|charging_station)$\"]", mapOf(596 to "fuel", 593 to "charging_station")) {
            it.v("amenity") in setOf("fuel", "charging_station") },
        ServiceDef("TOILETS", "[\"amenity\"=\"toilets\"]", mapOf(179 to "toilets")) { it.v("amenity") == "toilets" },
        ServiceDef("EMERGENCY", "[\"amenity\"~\"^(hospital|police|fire_station)$\"]", mapOf(206 to "hospital", 369 to "police", 367 to "fire_station")) {
            it.v("amenity") in setOf("hospital", "police", "fire_station") },
        ServiceDef("WATER", "[\"amenity\"=\"drinking_water\"]", emptyMap()) { it.v("amenity") == "drinking_water" },
        ServiceDef("SLEEP", "[\"tourism\"~\"^(hostel|camp_site|alpine_hut|wilderness_hut|caravan_site)$\"]", emptyMap()) {
            it.v("tourism") in setOf("hostel", "camp_site", "alpine_hut", "wilderness_hut", "caravan_site") },
        ServiceDef("LAUNDRY", "[\"shop\"~\"^(laundry|dry_cleaning)$\"]", emptyMap()) { it.v("shop") in setOf("laundry", "dry_cleaning") },
        ServiceDef("PHARMACY", "[\"amenity\"=\"pharmacy\"]", emptyMap()) { it.v("amenity") == "pharmacy" },
        ServiceDef("MONEY", "[\"amenity\"~\"^(atm|bureau_de_change|bank)$\"]", emptyMap()) {
            it.v("amenity") in setOf("atm", "bureau_de_change", "bank") },
        ServiceDef("WIFI", "[\"internet_access\"~\"^(wlan|yes|wifi)$\"][\"internet_access:fee\"!=\"yes\"]", emptyMap()) {
            it.v("internet_access") in setOf("wlan", "yes", "wifi") && it.v("internet_access:fee") != "yes" },
        ServiceDef("BIKE", "[\"shop\"=\"bicycle\"]", emptyMap()) { it.v("shop") == "bicycle" }
    )

    /** Services a place with these tags belongs to (a café with Wi-Fi: both). */
    fun servicesOf(tags: JSONObject): Set<String> = SERVICES.filter { it.matches(tags) }.mapTo(LinkedHashSet()) { it.name }

    /** Service of an openrouteservice kind (second source). */
    fun serviceOfKind(kind: String): Set<String> = SERVICES.filter { kind in it.orsIds.values }.mapTo(LinkedHashSet()) { it.name }

    /** 1.1.1: every service of each square in one request, each with its own
     *  cap (so restaurants can't crowd out toilets or pharmacies). */
    fun servicesQuery(tiles: List<Tile>, perService: Int = PER_SERVICE, names: Collection<String>? = null): String {
        // 1.2.1: only the services asked for ([names]; null = all).
        val defs = if (names == null) SERVICES else SERVICES.filter { it.name in names }
        val parts = tiles.joinToString("\n") { t ->
            defs.joinToString("\n") { "nwr${t.bbox}${it.filter};out center tags $perService;" }
        }
        return "[out:json][timeout:25];\n$parts"
    }

    /** 1.2.1: per service and square, the places of [service] among [places] (stored per service). */
    fun ofService(places: List<PoiPlace>, service: String, tile: Tile): List<PoiPlace> =
        places.filter { service in it.services && Tiles.of(it.point) == tile }

    /** Places per service and square at most. */
    const val PER_SERVICE = 40

    /** One request for several squares; each square gets its own cap. */
    fun tileQuery(filter: String, tiles: List<Tile>, perTile: Int = PER_TILE): String {
        val parts = tiles.joinToString("\n") { "nwr${it.bbox}$filter;out center tags $perTile;" }
        return "[out:json][timeout:25];\n$parts"
    }

    fun parseOverpass(body: String): List<PoiPlace> {
        val elements = JSONObject(body).getJSONArray("elements")
        val out = mutableListOf<PoiPlace>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val center = e.optJSONObject("center")
            val lat = center?.optDouble("lat", Double.NaN) ?: e.optDouble("lat", Double.NaN)
            val lon = center?.optDouble("lon", Double.NaN) ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            out.add(PoiPlace(
                key = e.optString("type", "node").take(1) + e.optLong("id", 0L),
                name = tags.optString("name", "").ifBlank { tags.optString("brand", "") },
                point = LatLon(lat, lon),
                kind = kindOf(tags),
                website = tags.optString("website", "").ifBlank { tags.optString("contact:website", "") }.ifBlank { null },
                wikipedia = tags.optString("wikipedia", "").ifBlank { null },
                wikidata = tags.optString("wikidata", "").takeIf { it.matches(Regex("Q[0-9]+")) },
                openingHours = tags.optString("opening_hours", "").ifBlank { null },
                services = servicesOf(tags)
            ))
        }
        return out.distinctBy { it.key }
    }

    private fun kindOf(tags: JSONObject): String =
        tags.optString("amenity", "").ifBlank { tags.optString("tourism", "") }.ifBlank { tags.optString("shop", "") }

    fun kindLabel(kind: String): String = when (kind) {
        "fuel" -> Txt.s("core_kind_fuel", "Fuel station")
        "charging_station" -> Txt.s("core_kind_charging_station", "Charging station")
        "restaurant" -> Txt.s("core_kind_restaurant", "Restaurant")
        "cafe" -> Txt.s("core_kind_cafe", "Café")
        "fast_food" -> Txt.s("core_kind_fast_food", "Fast food")
        "hotel" -> Txt.s("core_kind_hotel", "Hotel")
        "guest_house" -> Txt.s("core_kind_guest_house", "Guest house")
        "motel" -> Txt.s("core_kind_motel", "Motel")
        "parking" -> Txt.s("core_kind_parking", "Parking")
        "toilets" -> Txt.s("core_kind_toilets", "Toilets")
        "hospital" -> Txt.s("core_kind_hospital", "Hospital")
        "police" -> Txt.s("core_kind_police", "Police")
        "fire_station" -> Txt.s("core_kind_fire_station", "Fire station")
        "drinking_water" -> Txt.s("core_kind_drinking_water", "Drinking water")
        "hostel" -> Txt.s("core_kind_hostel", "Hostel")
        "camp_site" -> Txt.s("core_kind_camp_site", "Campsite")
        "alpine_hut", "wilderness_hut" -> Txt.s("core_kind_hut", "Mountain hut")
        "caravan_site" -> Txt.s("core_kind_caravan_site", "Caravan site")
        "laundry" -> Txt.s("core_kind_laundry", "Laundry")
        "dry_cleaning" -> Txt.s("core_kind_dry_cleaning", "Dry cleaning")
        "pharmacy" -> Txt.s("core_kind_pharmacy", "Pharmacy")
        "atm" -> Txt.s("core_kind_atm", "ATM")
        "bureau_de_change" -> Txt.s("core_kind_bureau_de_change", "Money exchange")
        "bank" -> Txt.s("core_kind_bank", "Bank")
        "bicycle" -> Txt.s("core_kind_bicycle", "Bike shop / repair")
        "" -> Txt.s("core_kind_place", "Place")
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "fr:Tour Eiffel" -> https://fr.wikipedia.org/wiki/Tour_Eiffel */
    fun wikipediaUrl(tag: String?): String? {
        val w = tag ?: return null
        val i = w.indexOf(':')
        if (i <= 0 || i >= w.length - 1) return null
        val lang = w.substring(0, i)
        if (!lang.all { it.isLetter() || it == '-' }) return null
        val title = w.substring(i + 1).replace(' ', '_')
        return "https://$lang.wikipedia.org/wiki/" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "_")
    }

    // ---------- openrouteservice places (second source) ----------

    /**
     * Body for POST /pois: places of [categoryIds] within [bufferM] metres
     * of [center] (openpoiservice request format).
     */
    fun orsBody(center: LatLon, bufferM: Int, categoryIds: List<Int>, limit: Int = PER_TILE): JSONObject {
        val dLat = bufferM / 111_000.0
        val dLon = bufferM / (111_000.0 * Math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        val bbox = JSONArray()
            .put(JSONArray().put(center.lon - dLon).put(center.lat - dLat))
            .put(JSONArray().put(center.lon + dLon).put(center.lat + dLat))
        val geometry = JSONObject()
            .put("bbox", bbox)
            .put("geojson", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(center.lon).put(center.lat)))
            .put("buffer", bufferM)
        val ids = JSONArray()
        categoryIds.forEach { ids.put(it) }
        return JSONObject()
            .put("request", "pois")
            .put("geometry", geometry)
            .put("filters", JSONObject().put("category_ids", ids))
            .put("limit", limit)
    }

    /** openpoiservice answer: GeoJSON points with "osm_id" and "osm_tags". */
    fun parseOrs(body: String, kindById: Map<Int, String>): List<PoiPlace> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<PoiPlace>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = c.optDouble(0)
            val lat = c.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val tags = p.optJSONObject("osm_tags") ?: JSONObject()
            val cats = p.optJSONObject("category_ids")
            var kind = ""
            if (cats != null) {
                val keys = cats.keys()
                while (keys.hasNext() && kind.isEmpty()) kind = kindById[keys.next().toIntOrNull() ?: -1].orEmpty()
            }
            out.add(PoiPlace(
                key = "o" + p.optLong("osm_id", i.toLong()),
                name = tags.optString("name", ""),
                point = LatLon(lat, lon),
                kind = kind,
                website = tags.optString("website", "").ifBlank { null },
                openingHours = tags.optString("opening_hours", "").ifBlank { null },
                services = serviceOfKind(kind)
            ))
        }
        return out.distinctBy { it.key }
    }

    // ---------- Cache format ----------

    fun toJson(list: List<PoiPlace>): String {
        val a = JSONArray()
        for (p in list) {
            val o = JSONObject().put("k", p.key).put("n", p.name).put("a", p.point.lat).put("o", p.point.lon).put("t", p.kind)
            p.website?.let { o.put("w", it) }
            p.wikipedia?.let { o.put("wp", it) }
            p.wikidata?.let { o.put("wd", it) }
            p.openingHours?.let { o.put("h", it) }
            if (p.services.isNotEmpty()) o.put("s", p.services.joinToString(","))
            a.put(o)
        }
        return a.toString()
    }

    fun fromJson(text: String): List<PoiPlace> {
        val a = JSONArray(text)
        val out = mutableListOf<PoiPlace>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(PoiPlace(
                o.optString("k", ""), o.optString("n", ""), LatLon(o.optDouble("a"), o.optDouble("o")), o.optString("t", ""),
                o.optString("w", "").ifBlank { null }, o.optString("wp", "").ifBlank { null },
                o.optString("wd", "").ifBlank { null }, o.optString("h", "").ifBlank { null },
                o.optString("s", "").split(',').filter { it.isNotBlank() }.toSet()
            ))
        }
        return out
    }

    /** Groups places by square; every queried square gets an entry (maybe empty). */
    fun byTile(places: List<PoiPlace>, queried: List<Tile>): Map<Tile, List<PoiPlace>> {
        val wanted = queried.toSet()
        val out = LinkedHashMap<Tile, MutableList<PoiPlace>>()
        for (t in queried) out[t] = mutableListOf()
        for (p in places) {
            val t = Tiles.of(p.point)
            if (t in wanted) out.getValue(t).add(p)
        }
        return out
    }
}
