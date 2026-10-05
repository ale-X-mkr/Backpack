package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.0: more sights kinds, named natural spots, Wikivoyage, Wikipedia nearby. */
class PlacesSourcesTest {

    private fun el(id: Int, tags: JSONObject) = JSONObject().put("type", "node").put("id", id)
        .put("lat", 45.0).put("lon", 6.0).put("tags", tags)

    @Test fun newKindsNeedWikidata() {
        val body = JSONObject().put("elements", JSONArray()
            .put(el(1, JSONObject().put("waterway", "waterfall").put("name", "Cascade A").put("wikidata", "Q1")))
            .put(el(6, JSONObject().put("waterway", "waterfall").put("name", "No wikidata")))
            .put(el(2, JSONObject().put("boundary", "national_park").put("name", "Parc B").put("wikidata", "Q2")))
            .put(el(3, JSONObject().put("heritage:operator", "whc").put("historic", "castle").put("name", "C").put("wikidata", "Q3")))
            .put(el(4, JSONObject().put("natural", "hot_spring").put("name", "Spring D").put("wikidata", "Q4")))
            .put(el(5, JSONObject().put("natural", "peak")))).toString()
        val list = Tourist.parseOverpass(body)
        assertEquals(4, list.size)
        assertEquals(TouristCategory.WATER, list[0].category)
        assertEquals("Q1", list[0].identity)
        assertEquals(TouristCategory.PARK, list[1].category)
        assertEquals(TouristCategory.HERITAGE, list[2].category)
        assertEquals(TouristCategory.WATER, list[3].category)
        assertEquals(list, Tourist.fromJson(Tourist.toJson(list)))
        val extras = Tourist.tileQuery(listOf(Tile(120, 900)), extrasOnly = true)
        assertTrue(extras.contains("nwr(45.0000,6.0000,45.0500,6.0500)[\"waterway\"=\"waterfall\"][\"wikidata\"];"))
        assertTrue(extras.contains(" nw(45.0000,6.0000,45.0500,6.0500)[\"boundary\"=\"national_park\"]"))
        assertTrue(!extras.contains("castle"))
    }

    @Test fun wikivoyageFromSitelinks() {
        val links = JSONObject().put("enwikivoyage", JSONObject().put("title", "Grenoble"))
            .put("frwiki", JSONObject().put("title", "Grenoble"))
        assertEquals("https://en.wikivoyage.org/wiki/Grenoble", Tourist.voyageUrl(links, listOf("fr", "en")))
        assertNull(Tourist.voyageUrl(JSONObject(), listOf("en")))
        val body = JSONObject().put("entities", JSONObject().put("Q1", JSONObject().put("sitelinks", links))).toString()
        assertEquals("https://en.wikivoyage.org/wiki/Grenoble", Tourist.parseSiteInfo(body, listOf("en")).getValue("Q1").voyageUrl)
    }

    @Test fun wikipediaNearbyAndKinds() {
        val pages = JSONObject().put("11", JSONObject().put("title", "Château X")
            .put("coordinates", JSONArray().put(JSONObject().put("lat", 45.1).put("lon", 6.1)))
            .put("pageprops", JSONObject().put("wikibase_item", "Q10")))
            .put("12", JSONObject().put("title", "No coords").put("pageprops", JSONObject().put("wikibase_item", "Q11")))
        val hits = Tourist.parseWikiNearby(JSONObject().put("query", JSONObject().put("pages", pages)).toString())
        assertEquals(1, hits.size)
        assertEquals("Q10", hits[0].wikidata)
        assertTrue(Tourist.wikiNearbyUrl("fr", LatLon(45.0, 6.0), 50_000).contains("ggsradius=10000"))
        assertTrue(Tourist.kindsQuery(listOf("Q10", "Q11")).contains("VALUES ?item { wd:Q10 wd:Q11 }"))
        fun row(item: String, kind: String) = JSONObject()
            .put("item", JSONObject().put("value", "http://www.wikidata.org/entity/$item"))
            .put("kind", JSONObject().put("value", "http://www.wikidata.org/entity/$kind"))
        val kinds = Tourist.parseKinds(JSONObject().put("results", JSONObject().put("bindings", JSONArray()
            .put(row("Q10", "Q23413")).put(row("Q10", "Q9259")).put(row("Q11", "Q515")))).toString())
        assertEquals(mapOf("Q10" to TouristCategory.HERITAGE), kinds)
    }

    @Test fun reverseTown() {
        val body = JSONObject().put("name", "Grenoble").put("extratags", JSONObject().put("wikidata", "Q1289")).toString()
        assertEquals("Grenoble" to "Q1289", Tourist.parseReverseTown(body))
        val noTag = JSONObject().put("name", "").put("address", JSONObject().put("village", "Vif")).toString()
        assertEquals("Vif" to null, Tourist.parseReverseTown(noTag))
    }

    @Test fun servicesOneQueryAndTags() {
        val q = Places.servicesQuery(listOf(Tile(120, 900)))
        assertEquals(1, Regex("out center tags 400;").findAll(q).count())
        assertEquals(Places.SERVICES.size, Regex("nwr\\(45.0000").findAll(q).count())
        val cafeWifi = JSONObject().put("amenity", "cafe").put("internet_access", "wlan")
        assertEquals(setOf("FOOD", "WIFI"), Places.servicesOf(cafeWifi))
        assertEquals(emptySet<String>(), Places.servicesOf(JSONObject().put("internet_access", "wlan").put("internet_access:fee", "yes")))
        assertEquals(setOf("FUEL"), Places.serviceOfKind("charging_station"))
        val body = JSONObject().put("elements", JSONArray().put(JSONObject().put("type", "node").put("id", 9)
            .put("lat", 45.01).put("lon", 6.01).put("tags", cafeWifi.put("name", "Bar")))).toString()
        val list = Places.parseOverpass(body)
        assertEquals(setOf("FOOD", "WIFI"), list[0].services)
        assertEquals(list, Places.fromJson(Places.toJson(list)))
    }
}
