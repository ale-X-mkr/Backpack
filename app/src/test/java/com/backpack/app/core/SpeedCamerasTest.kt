package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCamerasTest {

    @Test fun parsesCamerasWithTheirCountry() {
        val body = """{"elements":[
            {"type":"node","id":1,"lat":45.1,"lon":1.1,"tags":{"highway":"speed_camera"}},
            {"type":"area","id":3600002202,"tags":{"ISO3166-1":"FR","admin_level":"2"}},
            {"type":"node","id":2,"lat":40.0,"lon":-3.0,"tags":{"highway":"speed_camera"}},
            {"type":"area","id":3600001311,"tags":{"ISO3166-1":"es","admin_level":"2"}},
            {"type":"node","id":3,"lat":50.0,"lon":8.0}
        ]}"""
        val cams = SpeedCameras.parse(body)
        assertEquals(3, cams.size)
        assertEquals("FR", cams[0].country)
        assertEquals("ES", cams[1].country)
        assertNull(cams[2].country)
    }

    @Test fun countryRules() {
        assertEquals(SpeedCameras.Rule.ZONE, SpeedCameras.rule("FR"))
        assertEquals(SpeedCameras.Rule.ZONE, SpeedCameras.rule(null))
        for (c in listOf("DE", "CH", "CY", "MK")) assertEquals(SpeedCameras.Rule.NONE, SpeedCameras.rule(c))
        assertEquals(SpeedCameras.Rule.EXACT, SpeedCameras.rule("ES"))
    }

    @Test fun blipsSpeedUpAndZoneHidesTheSpot() {
        assertTrue(SpeedCameras.blipIntervalMs(100.0, 800.0) < SpeedCameras.blipIntervalMs(700.0, 800.0))
        assertEquals(200L, SpeedCameras.blipIntervalMs(0.0, 800.0))
        assertEquals(300.0, SpeedCameras.alertDistance(5.0), 0.0)
        assertEquals(1000.0, SpeedCameras.alertDistance(40.0), 0.0)
        val len = SpeedCameras.zoneLength(130)
        assertEquals(4000.0, len, 0.0)
        for (id in 1L..200L) {
            val lead = SpeedCameras.zoneLead(SpeedCameras.Camera(id, LatLon(0.0, 0.0), "FR"), len)
            assertTrue(lead in 0.2 * len..0.8 * len)
        }
    }

    @Test fun queryAsksForTheCountry() {
        val q = SpeedCameras.pointQuery(LatLon(45.0, 1.0))
        assertTrue(q.contains("speed_camera") && q.contains("is_in") && q.contains("ISO3166-1"))
    }
}
