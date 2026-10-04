package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.7: area reachable in a given time (Sights). */
class ReachTest {

    private val c = LatLon(45.0, 6.0)

    @Test fun radiusAndLimits() {
        assertEquals(4500.0, Reach.radiusM(TravelMode.WALKING, 60), 1e-6)
        assertEquals(60000.0, Reach.radiusM(TravelMode.DRIVING, 60), 1e-6)
        assertEquals(7500.0, Reach.radiusM(TravelMode.CYCLING, 30), 1e-6)
        assertTrue(Reach.orsCanAnswer(TravelMode.DRIVING, 60))
        assertFalse(Reach.orsCanAnswer(TravelMode.DRIVING, 120))
        assertTrue(Reach.orsCanAnswer(TravelMode.WALKING, 120))
        assertEquals("foot-walking", Reach.orsProfile(TravelMode.WALKING))
        val body = Reach.orsBody(c, 60)
        assertEquals(3600, body.getJSONArray("range").getInt(0))
        assertEquals(6.0, body.getJSONArray("locations").getJSONArray(0).getDouble(0), 0.0)
        assertEquals("time", body.getString("range_type"))
    }

    @Test fun detourOffset() {
        // 5 min by car there and back: ~1.27 km off the route; round trip check.
        val off = Tourist.offsetForDetour(300.0, TravelMode.DRIVING)
        assertEquals(1269.0, off, 2.0)
        assertEquals(300.0, Tourist.estimateDetourSeconds(off, TravelMode.DRIVING), 0.5)
        assertEquals(10000.0, Tourist.offsetForDetour(3600.0, TravelMode.DRIVING), 0.0)
        assertEquals(100.0, Tourist.offsetForDetour(10.0, TravelMode.WALKING), 0.0)
    }

    @Test fun circleContainsAndBounds() {
        val ring = Reach.circle(c, 10_000.0)
        assertEquals(48, ring.size)
        assertTrue(Reach.contains(ring, c))
        assertTrue(Reach.contains(ring, Geo.project(c, 30.0, 9_000.0)))
        assertFalse(Reach.contains(ring, Geo.project(c, 30.0, 11_000.0)))
        val b = Reach.bounds(ring)
        assertTrue(b[0] < 45.0 && b[2] > 45.0 && b[1] < 6.0 && b[3] > 6.0)
        assertEquals(120, Reach.thin(Reach.circle(c, 1000.0, 480)).size)
        assertEquals(48, Reach.thin(ring).size)
    }

    @Test fun parseIsochroneAndQuery() {
        val ring = JSONArray()
        for (p in listOf(6.0 to 45.0, 6.1 to 45.0, 6.1 to 45.1, 6.0 to 45.1, 6.0 to 45.0)) ring.put(JSONArray().put(p.first).put(p.second))
        val body = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(JSONObject()
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))))).toString()
        val parsed = Reach.parseIsochrone(body)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.size)
        assertEquals(45.1, parsed[2].lat, 1e-9)
        assertTrue(Reach.contains(parsed, LatLon(45.05, 6.05)))
        assertNull(Reach.parseIsochrone("{\"features\":[]}"))
        val q = Tourist.reachQuery(parsed)
        assertTrue(q.contains("nwr(poly:\"45.0000 6.0000 45.0000 6.1000"))
        assertTrue(q.startsWith("[out:json][timeout:25]"))
        val far = Reach.circle(c, 100_000.0)
        val (clipped, cut) = Reach.clip(far, c)
        assertTrue(cut)
        assertEquals(70_000.0, Geo.distance(c, clipped[5]), 50.0)
        assertFalse(Reach.clip(Reach.circle(c, 10_000.0), c).second)
    }
}
