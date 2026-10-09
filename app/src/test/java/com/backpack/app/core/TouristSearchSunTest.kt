package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TouristSearchSunTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, m - 1, d, h, min)
        }.timeInMillis

    // ---------- Sun ----------

    @Test fun sunElevationKnownValues() {
        // Paris, summer solstice, solar noon (~11:50 UTC): about 64°.
        assertEquals(64.0, SunPosition.elevation(48.85, 2.35, utc(2026, 6, 21, 11, 50)), 1.0)
        // Paris, winter solstice, solar noon: about 17.7°.
        assertEquals(17.7, SunPosition.elevation(48.85, 2.35, utc(2026, 12, 21, 11, 50)), 1.0)
        // Equator, equinox, noon at Greenwich: almost overhead.
        assertTrue(SunPosition.elevation(0.0, 0.0, utc(2026, 3, 20, 12, 7)) > 85.0)
    }

    @Test fun nightAndDay() {
        assertTrue(SunPosition.isNight(48.85, 2.35, utc(2026, 1, 15, 23)))   // Paris, midnight local
        assertFalse(SunPosition.isNight(48.85, 2.35, utc(2026, 1, 15, 12)))  // Paris, noon
        assertTrue(SunPosition.isNight(48.85, 2.35, utc(2026, 1, 15, 17, 30)))  // after a January sunset (~16:25 UTC)
        assertFalse(SunPosition.isNight(48.85, 2.35, utc(2026, 7, 15, 19, 0)))  // July evening, sun still up
        assertTrue(SunPosition.isNight(-33.87, 151.21, utc(2026, 1, 15, 14)))   // Sydney, local midnight
    }

    // ---------- Tourist ----------

    private val overpass = """
    {"elements":[
     {"type":"way","id":11,"center":{"lat":48.8584,"lon":2.2945},
      "tags":{"tourism":"attraction","name":"Tour Eiffel","wikidata":"Q243","wikipedia":"fr:Tour Eiffel"}},
     {"type":"node","id":12,"lat":48.8585,"lon":2.2946,
      "tags":{"tourism":"attraction","name":"Tour Eiffel","wikidata":"Q243"}},
     {"type":"node","id":13,"lat":48.8606,"lon":2.3376,
      "tags":{"tourism":"museum","name":"Louvre","wikidata":"Q19675"}},
     {"type":"node","id":14,"lat":45.8326,"lon":6.8652,
      "tags":{"natural":"peak","name":"Mont Blanc","wikidata":"Q583"}},
     {"type":"node","id":15,"lat":48.0,"lon":2.0,
      "tags":{"historic":"memorial","name":"No id"}},
     {"type":"node","id":16,"lat":48.0,"lon":2.0,
      "tags":{"historic":"castle","wikidata":"Q1"}},
     {"type":"way","id":17,"center":{"lat":48.853,"lon":2.3499},
      "tags":{"amenity":"place_of_worship","building":"cathedral","name":"Notre-Dame","wikidata":"Q2981"}}
    ]}"""

    @Test fun overpassParsingClassifiesAndDedupes() {
        val pois = Tourist.parseOverpass(overpass)
        assertEquals(listOf("Tour Eiffel", "Louvre", "Mont Blanc", "Notre-Dame"), pois.map { it.name })
        assertEquals("w11", pois[0].key)
        assertEquals(TouristCategory.SIGHT, pois[0].category)
        assertEquals(TouristCategory.MUSEUM, pois[1].category)
        assertEquals(TouristCategory.NATURE, pois[2].category)
        assertEquals(TouristCategory.HISTORIC, pois[3].category)
        assertEquals("https://fr.wikipedia.org/wiki/Tour_Eiffel", pois[0].wikipediaUrl)
        assertNull(pois[1].wikipediaUrl)
    }

    @Test fun sitelinksAndRanking() {
        val links = Tourist.parseSitelinks(
            """{"entities":{"Q243":{"sitelinks":{"enwiki":{},"frwiki":{},"dewiki":{}}},"Q19675":{"sitelinks":{"enwiki":{}}},"Q583":{"missing":""}}}"""
        )
        assertEquals(3, links["Q243"])
        assertEquals(1, links["Q19675"])
        assertNull(links["Q583"])
        val pois = Tourist.parseOverpass(overpass).map { it.copy(sitelinks = links[it.wikidata] ?: 0) }
        // 1.4.0: your kind order counts too (culture first here; default puts nature before).
        Tourist.order = listOf(SightGroup.CULTURE, SightGroup.PREHISTORY, SightGroup.NATURE, SightGroup.HISTORIC, SightGroup.PARKS)
        val ranked = Tourist.rank(pois, 2)
        assertEquals(listOf("Tour Eiffel", "Louvre"), ranked.map { it.name })
        Tourist.order = SightGroup.DEFAULT_ORDER
        assertEquals("Mont Blanc", Tourist.rank(pois, 1)[0].name)
        assertEquals(1, ranked[0].stars)
        assertTrue(Tourist.wikidataUrl(listOf("Q1", "Q2")).endsWith("ids=Q1%7CQ2"))
    }

    @Test fun corridorQueryAndSampling() {
        val start = LatLon(45.0, 6.0)
        val pts = (0..1000).map { Geo.project(start, 90.0, it * 100.0) } // 100 km
        val sampled = Tourist.sampleLine(pts, maxPoints = 80)
        assertTrue(sampled.size in 70..81)
        assertEquals(pts.first(), sampled.first())
        assertEquals(pts.last(), sampled.last())
        val q = Tourist.corridorQuery(sampled.take(2), 4000)
        assertTrue(q.contains("nwr(around:4000,45.000,6.000,"))
        assertTrue(q.contains("out center tags;"))
        assertTrue(q.contains("out center tags"))
        assertEquals(9, Regex("nwr\\(around").findAll(q).count()) // 1.1.1: 4 base + 5 extra kinds
        assertEquals(2, Regex(" nw\\(around").findAll(q).count())  // parks: points / ways only
        val trimmed = Tourist.trimEnds(pts)
        assertTrue(Geo.distance(trimmed.first(), pts.first()) >= 500.0)
        assertTrue(Geo.distance(trimmed.last(), pts.last()) >= 500.0)
        val short = pts.take(20) // 1.9 km: kept whole
        assertEquals(short, Tourist.trimEnds(short))
    }

    @Test fun placeOnRouteDetourAndStopOrder() {
        val start = LatLon(45.0, 6.0)
        val line = Polyline((0..20).map { Geo.project(start, 0.0, it * 100.0) }) // 2 km north
        val off = Geo.project(Geo.project(start, 0.0, 1000.0), 90.0, 300.0)
        val p = Tourist.placeOnRoute(listOf(TouristPoi("n1", "X", off, TouristCategory.SIGHT, "Q1", null)), line)[0]
        assertEquals(300.0, p.offsetM, 2.0)
        assertEquals(1000.0, p.alongM, 2.0)
        assertEquals(2 * 300 * 1.3 / 1.3, Tourist.estimateDetourSeconds(300.0, TravelMode.WALKING), 1.0)
        assertEquals("10 min", Tourist.minutesLabel(600.0))
        val s1 = Geo.project(start, 0.0, 1500.0)
        val newStop = Geo.project(start, 0.0, 500.0)
        assertEquals(listOf(newStop, s1), Tourist.insertStop(listOf(s1), newStop, line))
    }

    // ---------- Search ----------

    @Test fun photonParsing() {
        val body = """{"features":[
          {"geometry":{"coordinates":[2.2945,48.8584]},"properties":{"name":"Tour Eiffel","street":"Avenue Gustave Eiffel","housenumber":"5","postcode":"75007","city":"Paris","country":"France"}},
          {"geometry":{"coordinates":[5.72,45.19]},"properties":{"street":"Rue Félix Poulat","housenumber":"3","city":"Grenoble","country":"France"}},
          {"geometry":{"coordinates":[1,2]},"properties":{}}
        ]}"""
        val places = Geocode.parsePhoton(body)
        assertEquals(2, places.size)
        assertEquals("Tour Eiffel", places[0].name)
        assertEquals("Avenue Gustave Eiffel 5, 75007, Paris, France", places[0].detail)
        assertEquals(48.8584, places[0].point.lat, 1e-9)
        assertEquals("Rue Félix Poulat 3", places[1].name)
        assertEquals("Grenoble, France", places[1].detail)
        assertTrue(Geocode.photonUrl("a b", LatLon(45.123456, 5.7)).contains("q=a+b&limit=6&lang=en&lat=45.12&lon=5.70"))
    }

    @Test fun nominatimAndCoordinates() {
        val n = Geocode.parseNominatim("""[{"lat":"45.19","lon":"5.72","name":"Grenoble","display_name":"Grenoble, Isère, France"}]""")
        assertEquals("Grenoble", n[0].name)
        assertEquals("Isère, France", n[0].detail)
        assertEquals(LatLon(45.19, 5.72), Geocode.parseCoordinates("45.19, 5.72"))
        assertEquals(LatLon(-33.8, 151.2), Geocode.parseCoordinates("-33.8 151.2"))
        assertNull(Geocode.parseCoordinates("95, 5"))
        assertNull(Geocode.parseCoordinates("rue de la paix"))
        assertNotNull(Geocode.parseCoordinates(" 1;2 "))
    }
}

class WeekendAndOverpassTest {
    private val start = LatLon(45.0, 6.0)

    private fun route(bearing: Double, km: Int, minutes: Double): RouteResult {
        val pts = (0..km * 10).map { Geo.project(start, bearing, it * 100.0) }
        return RouteResult(pts, km * 1000.0, minutes * 60)
    }

    private fun poi(i: Int, p: LatLon, links: Int) =
        TouristPoi("n$i", "P$i", p, TouristCategory.SIGHT, "Q$i", null, sitelinks = links)

    @Test fun scenicChoicePrefersRouteWithMoreSightsWithinTimeLimit() {
        val fast = route(0.0, 10, 10.0)     // north
        val scenic = route(20.0, 10, 11.5)  // a bit east, +15 %
        val sights = (1..4).map { poi(it, Geo.project(start, 20.0, it * 2000.0), 20) } // ★★ each, on the scenic one
        val c = Tourist.scenicChoice(listOf(fast, scenic), 0, sights, 400, 0.20)
        org.junit.Assert.assertNotNull(c)
        org.junit.Assert.assertEquals(1, c!!.index)
        org.junit.Assert.assertEquals(4, c.places)
        org.junit.Assert.assertEquals(90.0, c.extraSeconds, 0.1)
        // Over the time limit: stays on the fast one.
        org.junit.Assert.assertNull(Tourist.scenicChoice(listOf(fast, scenic), 0, sights, 400, 0.10))
        // Off (0 %): nothing changes.
        org.junit.Assert.assertNull(Tourist.scenicChoice(listOf(fast, scenic), 0, sights, 400, 0.0))
        // A single extra place is not "clearly more".
        org.junit.Assert.assertNull(Tourist.scenicChoice(listOf(fast, scenic), 0, sights.take(1).map { it.copy(sitelinks = 0) }, 400, 0.2))
    }

    @Test fun multiCorridorQueryHasAllLines() {
        val q = Tourist.corridorQueryMulti(listOf(listOf(start, Geo.project(start, 0.0, 500.0)), listOf(start)), 1500)
        org.junit.Assert.assertEquals(18, Regex("nwr\\(around:1500,").findAll(q).count())
    }

    @Test fun overpassRuntimeErrorIsAFailure() {
        org.junit.Assert.assertNotNull(OverpassCheck.runtimeError(
            """{"version":0.6,"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 26 seconds."}"""))
        org.junit.Assert.assertNull(OverpassCheck.runtimeError("""{"elements":[{"type":"node"}]}"""))
        org.junit.Assert.assertNull(OverpassCheck.runtimeError("""{"elements":[],"remark":"something harmless"}"""))
    }
}
