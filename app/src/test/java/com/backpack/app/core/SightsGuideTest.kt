package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.0: sight kinds, heads-up by time, Wikipedia read-aloud helpers. */
class SightsGuideTest {

    @Test fun sightGroupsSaveAndParse() {
        assertEquals(SightGroup.values().toSet(), SightGroup.parse(null))
        assertEquals(emptySet<SightGroup>(), SightGroup.parse(""))
        val two = setOf(SightGroup.NATURE, SightGroup.PARKS)
        assertEquals(two, SightGroup.parse(SightGroup.save(two)))
        assertEquals(setOf(SightGroup.HISTORIC), SightGroup.parse("HISTORIC,TRANSIT"))
    }

    @Test fun headsUpIsTimeNotDistance() {
        // Route at 72 km/h (20 m/s): 3 min = 3600 m.
        assertEquals(3600.0, Tourist.headsUpLeadM(3, 72_000.0, 3600.0, TravelMode.DRIVING), 1e-6)
        // Walking route 5 km in 1 h: 3 min = 250 m.
        assertEquals(250.0, Tourist.headsUpLeadM(3, 5000.0, 3600.0, TravelMode.WALKING), 1e-6)
        // No usable route time: the mode's own speed; never under 100 m or over 10 km.
        assertEquals(100.0, Tourist.headsUpLeadM(1, 0.0, 0.0, TravelMode.WALKING), 1e-6)
        assertEquals(10_000.0, Tourist.headsUpLeadM(15, 360_000.0, 3600.0, TravelMode.DRIVING), 1e-6)
    }

    @Test fun englishTitle() {
        assertEquals("Eiffel Tower", Tourist.englishTitle("https://en.wikipedia.org/wiki/Eiffel_Tower", null))
        assertEquals("Château de Chillon", Tourist.englishTitle("https://en.wikipedia.org/wiki/Ch%C3%A2teau_de_Chillon", null))
        assertNull(Tourist.englishTitle("https://fr.wikipedia.org/wiki/Tour_Eiffel", "fr:Tour Eiffel"))
        assertEquals("Matterhorn", Tourist.englishTitle(null, "en:Matterhorn"))
    }

    @Test fun summaryUrlAndParse() {
        assertEquals("https://en.wikipedia.org/api/rest_v1/page/summary/Ch%C3%A2teau_de_Chillon", Tourist.summaryUrl("Château de Chillon"))
        assertEquals("A castle.", Tourist.parseSummary("""{"title":"X","extract":" A castle. "}"""))
        assertNull(Tourist.parseSummary("""{"title":"X"}"""))
        val body = """{"entities":{"Q1":{"sitelinks":{"enwiki":{"site":"enwiki","title":"Big Ben"}}}}}"""
        assertEquals("Big Ben", Tourist.parseEnwikiTitle(body, "Q1"))
        assertNull(Tourist.parseEnwikiTitle("""{"entities":{"Q1":{"sitelinks":{}}}}""", "Q1"))
        assertTrue(Tourist.enwikiTitleUrl("Q42").endsWith("ids=Q42"))
    }
}
