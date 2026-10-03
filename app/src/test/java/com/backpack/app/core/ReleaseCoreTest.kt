package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.0: map squares, place cache, speed limits per route, server status, stops. */
class ReleaseCoreTest {

    private val origin = LatLon(45.0, 6.0)

    @Test fun tilesCoverBoxAndLine() {
        val t = Tiles.of(LatLon(45.012, 6.031))
        assertEquals(Tile(120, 900), t)
        assertTrue(t.contains(LatLon(45.012, 6.031)))
        assertEquals("(45.0000,6.0000,45.0500,6.0500)", t.bbox)
        val box = Tiles.inBox(45.01, 6.01, 45.09, 6.09)
        assertEquals(4, box.size)
        assertEquals(Tile(120, 900), box.first())
        // A 20 km line going north with a 1 km radius: a strip of squares, in order.
        val line = (0..20).map { Geo.project(origin, 0.0, it * 1000.0) }
        val along = Tiles.alongLines(listOf(line), 1000.0)
        assertTrue(along.size in 5..12)
        assertEquals(899, along.first().y) // the 1 km radius reaches the square south of the start
        assertTrue(along.first().y <= along.last().y)
    }

    @Test fun placesQueryParseAndCache() {
        val q = Places.tileQuery("[\"amenity\"=\"fuel\"]", listOf(Tile(120, 900), Tile(121, 900)))
        assertTrue(q.contains("nwr(45.0000,6.0000,45.0500,6.0500)[\"amenity\"=\"fuel\"];out center tags 60;"))
        assertEquals(2, Regex("out center tags").findAll(q).count())
        val body = JSONObject().put("elements", JSONArray()
            .put(JSONObject().put("type", "node").put("id", 1).put("lat", 45.01).put("lon", 6.01)
                .put("tags", JSONObject().put("amenity", "fuel").put("name", "Station A").put("website", "https://a.example")))
            .put(JSONObject().put("type", "way").put("id", 2).put("center", JSONObject().put("lat", 45.02).put("lon", 6.02))
                .put("tags", JSONObject().put("amenity", "fuel").put("brand", "Brand B").put("wikipedia", "fr:Station B")))
            .put(JSONObject().put("type", "node").put("id", 3).put("tags", JSONObject().put("amenity", "fuel"))))
            .toString()
        val list = Places.parseOverpass(body)
        assertEquals(2, list.size)
        assertEquals("n1", list[0].key)
        assertEquals("https://a.example", list[0].readMoreUrl)
        assertEquals("Brand B", list[1].name)
        assertEquals("https://fr.wikipedia.org/wiki/Station_B", list[1].readMoreUrl)
        assertEquals(list, Places.fromJson(Places.toJson(list)))
        val grouped = Places.byTile(list, listOf(Tile(120, 900), Tile(125, 905)))
        assertEquals(2, grouped.getValue(Tile(120, 900)).size)
        assertTrue(grouped.getValue(Tile(125, 905)).isEmpty())
        assertEquals("Fuel station", PoiPlace("x", "", origin, "fuel").label)
    }

    @Test fun orsPlacesBodyAndParse() {
        val body = Places.orsBody(origin, 2000, listOf(596, 593), 50)
        assertEquals("pois", body.getString("request"))
        assertEquals(2000, body.getJSONObject("geometry").getInt("buffer"))
        assertEquals(596, body.getJSONObject("filters").getJSONArray("category_ids").getInt(0))
        val answer = JSONObject().put("type", "FeatureCollection").put("features", JSONArray()
            .put(JSONObject().put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(6.01).put(45.01)))
                .put("properties", JSONObject().put("osm_id", 77).put("category_ids", JSONObject().put("596", JSONObject()))
                    .put("osm_tags", JSONObject().put("name", "Total")))))
            .toString()
        val list = Places.parseOrs(answer, mapOf(596 to "fuel"))
        assertEquals(1, list.size)
        assertEquals("o77", list[0].key)
        assertEquals("fuel", list[0].kind)
        assertEquals(45.01, list[0].point.lat, 1e-9)
    }

    @Test fun maxspeedValues() {
        assertEquals(50, SpeedLimits.parse("50"))
        assertEquals(48, SpeedLimits.parse("30 mph"))
        assertEquals(80, SpeedLimits.parse("FR:rural"))
        assertEquals(30, SpeedLimits.parse("DE:zone30"))
        assertEquals(20, SpeedLimits.parse("CH:zone:20"))
        assertEquals(50, SpeedLimits.parse("50;70"))
        assertNull(SpeedLimits.parse("none"))
        assertNull(SpeedLimits.parse("walk"))
        assertNull(SpeedLimits.parse(""))
    }

    private fun wayJson(id: Int, maxspeed: String, pts: List<LatLon>): JSONObject {
        val g = JSONArray()
        for (p in pts) g.put(JSONObject().put("lat", p.lat).put("lon", p.lon))
        return JSONObject().put("type", "way").put("id", id)
            .put("tags", JSONObject().put("highway", "primary").put("maxspeed", maxspeed)).put("geometry", g)
    }

    @Test fun speedLimitIndexUsesNearestRoadInYourDirection() {
        // A road going north (80) crossed by a bridge going east (130) at 500 m.
        val north = (0..10).map { Geo.project(origin, 0.0, it * 100.0) }
        val mid = north[5]
        val east = listOf(Geo.project(mid, 270.0, 300.0), mid, Geo.project(mid, 90.0, 300.0))
        val body = JSONObject().put("elements", JSONArray().put(wayJson(1, "80", north)).put(wayJson(2, "130", east))).toString()
        val ways = SpeedLimits.parseWays(body)
        assertEquals(2, ways.size)
        val idx = SpeedLimitIndex()
        idx.add(ways, north, 20.0)
        assertEquals(80, idx.limitAt(mid, 0.0))
        assertEquals(80, idx.limitAt(mid, 180.0)) // same road, other direction
        assertEquals(130, idx.limitAt(mid, 90.0))
        assertNull(idx.limitAt(Geo.project(mid, 90.0, 200.0), 0.0)) // no road going north there
        assertTrue(idx.isCovered(north[3]))
        assertFalse(idx.isCovered(Geo.project(origin, 90.0, 5000.0)))
        val route = Polyline((0..30).map { Geo.project(origin, 0.0, it * 100.0) })
        assertEquals(1000.0, idx.coveredUntil(route, 0.0, 5000.0), 260.0)
    }

    @Test fun simplifyAndSection() {
        val straight = (0..50).map { Geo.project(origin, 0.0, it * 20.0) }
        assertEquals(2, SpeedLimits.simplify(straight, 5.0).size)
        val corner = (0..10).map { Geo.project(origin, 0.0, it * 100.0) } +
            (1..10).map { Geo.project(Geo.project(origin, 0.0, 1000.0), 90.0, it * 100.0) }
        assertEquals(3, SpeedLimits.simplify(corner, 5.0).size)
        val line = Polyline(corner)
        val sec = SpeedLimits.section(line, 500.0, 1000.0)
        assertEquals(500.0, Geo.distance(origin, sec.first()), 1.0)
        assertEquals(1000.0, Polyline(sec).length, 2.0)
        assertTrue(SpeedLimits.corridorQuery(sec).startsWith("[out:json][timeout:25];way(around:20,45.0"))
    }

    @Test fun overpassStatusPage() {
        assertEquals(0, OverpassCheck.statusWait("Connected as: 1\nRate limit: 2\n2 slots available now.\nCurrently running queries"))
        assertEquals(12, OverpassCheck.statusWait("Rate limit: 2\nSlot available after: 2026-10-03T10:00:12Z, in 12 seconds.\nSlot available after: 2026-10-03T10:00:30Z, in 30 seconds."))
        assertEquals(0, OverpassCheck.statusWait("Connected as: 1\nRate limit: 0\nCurrently running queries"))
        assertNull(OverpassCheck.statusWait("<html>nope</html>"))
        assertEquals("https://x.org/api/status", OverpassCheck.statusUrl("https://x.org/api/interpreter"))
        assertNull(OverpassCheck.statusUrl("https://x.org/other"))
    }

    @Test fun stopsDetourAndReplaceLast() {
        val line = Polyline((0..20).map { Geo.project(origin, 0.0, it * 500.0) })
        val s1 = Geo.project(origin, 0.0, 2000.0)
        val s2 = Geo.project(origin, 0.0, 6000.0)
        val d = Geo.project(origin, 0.0, 4000.0)
        val withD = Stops.withDetour(listOf(s1, s2), null, d, line)
        assertEquals(listOf(s1, d, s2), withD)
        assertEquals(2, Stops.ownCount(withD, d))
        val d2 = Geo.project(origin, 0.0, 8000.0)
        assertEquals(listOf(s1, s2, d2), Stops.withDetour(withD, d, d2, line))
        val n = Geo.project(origin, 0.0, 1000.0)
        assertEquals(listOf(n, s1, d), Stops.replaceLast(withD, d, n, line))
    }

    @Test fun sightsTileQueryCacheAndArticle() {
        val q = Tourist.tileQuery(listOf(Tile(120, 900)))
        assertTrue(q.contains("nwr(45.0000,6.0000,45.0500,6.0500)[\"tourism\""))
        val p = TouristPoi("w1", "Château", LatLon(45.01, 6.01), TouristCategory.HISTORIC, "Q42", null)
        val back = Tourist.fromJson(Tourist.toJson(listOf(p)))
        assertEquals(p, back[0])
        assertEquals("https://www.wikidata.org/wiki/Q42", p.readMoreUrl)
        val links = JSONObject().put("entities", JSONObject().put("Q42", JSONObject().put("sitelinks", JSONObject()
            .put("enwiki", JSONObject().put("title", "Some Castle"))
            .put("frwiki", JSONObject().put("title", "Un Château")))))
        val info = Tourist.parseSiteInfo(links.toString(), listOf("fr", "en"))
        assertEquals(2, info.getValue("Q42").sitelinks)
        assertEquals("https://fr.wikipedia.org/wiki/Un_Ch%C3%A2teau", info.getValue("Q42").articleUrl)
        assertNotNull(p.copy(articleUrl = info.getValue("Q42").articleUrl).readMoreUrl)
        assertEquals(120_000.0, Tourist.searchAheadM(TravelMode.DRIVING), 0.0)
    }

    @Test fun routeLettersSitWhereRoutesPart() {
        val a = Polyline((0..20).map { Geo.project(origin, 0.0, it * 500.0) })
        // B leaves A eastwards in the middle part, then comes back.
        val b = Polyline((0..20).map { i ->
            val p = Geo.project(origin, 0.0, i * 500.0)
            if (i in 6..14) Geo.project(p, 90.0, 2000.0) else p
        })
        val pts = RouteLabels.points(listOf(a, b))
        assertEquals(2, pts.size)
        assertTrue((b.snap(pts[1]!!)?.offsetM ?: 99.0) < 1.0)
        assertTrue((a.snap(pts[1]!!)?.offsetM ?: 0.0) > 1500.0)
        assertEquals('A', RouteLabels.letter(0))
        assertEquals('C', RouteLabels.letter(2))
    }
}
