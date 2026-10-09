package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/** 1.4.0: caves and prehistory found and ranked by your order. */
class PrehistoryTest {
    @Test fun categorizeByDescription() {
        assertEquals(TouristCategory.PREHISTORY, Tourist.categorize(emptySet(), "Paleolithic decorated cave in Dordogne, France"))
        assertEquals(TouristCategory.PREHISTORY, Tourist.categorize(setOf(TouristCategory.HERITAGE), "rock shelter in Dordogne"))
        assertEquals(TouristCategory.PREHISTORY, Tourist.categorize(setOf(TouristCategory.HISTORIC), "Neolithic dolmen"))
        assertEquals(TouristCategory.HISTORIC, Tourist.categorize(emptySet(), "castle in Dordogne, France"))
        assertEquals(TouristCategory.MUSEUM, Tourist.categorize(emptySet(), "museum of prehistory"))  // hmm: prehistory word?
        assertNull(Tourist.categorize(emptySet(), "commune in Dordogne, France"))
        assertNull(Tourist.categorize(emptySet(), "river in France"))
        assertEquals(TouristCategory.WATER, Tourist.categorize(setOf(TouristCategory.WATER), null))
    }

    @Test fun osmCavesAndArchaeology() {
        val cave = JSONObject().put("natural", "cave_entrance").put("heritage:operator", "whc")
        assertEquals(TouristCategory.PREHISTORY, Tourist.classify(cave))
        assertEquals(TouristCategory.PREHISTORY, Tourist.classify(JSONObject().put("historic", "archaeological_site")))
        assertEquals(TouristCategory.HISTORIC, Tourist.classify(JSONObject().put("historic", "castle")))
    }

    @Test fun orderBeatsFame() {
        fun p(n: String, c: TouristCategory, links: Int) = TouristPoi(n, n, LatLon(0.0, 0.0), c, "Q$links", null, links)
        val castle = p("Castle", TouristCategory.HISTORIC, 200)
        val cave = p("Cave", TouristCategory.PREHISTORY, 10)
        Tourist.order = SightGroup.DEFAULT_ORDER
        assertEquals("Cave", Tourist.rank(listOf(castle, cave), 1)[0].name)
        Tourist.order = listOf(SightGroup.HISTORIC, SightGroup.PREHISTORY, SightGroup.NATURE, SightGroup.CULTURE, SightGroup.PARKS)
        assertEquals("Castle", Tourist.rank(listOf(castle, cave), 1)[0].name)
        Tourist.order = SightGroup.DEFAULT_ORDER
        assertEquals(SightGroup.DEFAULT_ORDER, SightGroup.parseOrder(null))
        assertEquals(SightGroup.NATURE, SightGroup.parseOrder("NATURE,BOGUS").first())
        assertTrue(SightGroup.parseOrder("NATURE").size == 5)
    }
}
