package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1: only the ticked services asked, stored per service; keyed servers. */
class ServicesFastTest {

    @Test fun onlyTickedServicesAsked() {
        val t = Tile(120, 920)
        val q = Places.servicesQuery(listOf(t), names = listOf("FUEL", "TOILETS"))
        assertTrue("fuel|charging_station" in q && "\"toilets\"" in q)
        assertFalse("restaurant" in q || "pharmacy" in q)
        assertEquals(2, Regex("out center tags").findAll(q).count())
        assertEquals(Places.SERVICES.size, Regex("out center tags").findAll(Places.servicesQuery(listOf(t))).count())
    }

    @Test fun storedPerServiceAndSquare() {
        val t = Tiles.of(LatLon(46.01, 6.01))
        val cafeWifi = PoiPlace("n1", "Cafe", LatLon(46.01, 6.01), "cafe", null, null, null, null, services = setOf("FOOD", "WIFI"))
        val fuel = PoiPlace("n2", "Fuel", LatLon(46.01, 6.02), "fuel", null, null, null, null, services = setOf("FUEL"))
        val far = PoiPlace("n3", "Far", LatLon(47.0, 6.0), "cafe", null, null, null, null, services = setOf("FOOD"))
        val all = listOf(cafeWifi, fuel, far)
        assertEquals(listOf("n1"), Places.ofService(all, "WIFI", t).map { it.key })
        assertEquals(listOf("n1"), Places.ofService(all, "FOOD", t).map { it.key })
        assertEquals(emptyList<PoiPlace>(), Places.ofService(all, "TOILETS", t))
    }

    @Test fun keyedServers() {
        val fm = OverpassProviders.byId("fairwaymapper")!!
        assertEquals("https://api.fairwaymapper.com/k/abc123/api/interpreter", fm.url(" abc123 "))
        assertNull(fm.url(""))
        assertNull(fm.url("bad/key"))
        assertEquals("https://overpass.geofabrik.de/K-1/api/interpreter", OverpassProviders.byId("geofabrik")!!.url("K-1"))
        assertNull(OverpassProviders.byId("none"))
        assertTrue(OverpassProviders.ALL.none { "nextgis" in it.id || "mail.ru" in it.url("k").orEmpty() })
    }
}
