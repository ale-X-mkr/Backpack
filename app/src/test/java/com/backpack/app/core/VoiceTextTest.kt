package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3.0: Backpack's own words in five languages. */
class VoiceTextTest {
    private val en = VoiceText.forLanguage("en")
    private val es = VoiceText.forLanguage("es")
    private val fr = VoiceText.forLanguage("fr")
    private val it = VoiceText.forLanguage("it")
    private val de = VoiceText.forLanguage("de")
    private fun step(m: Maneuver, exit: Int = 0) = NavStep("x", m, 0.0, 0.0, 0, 0, exitNumber = exit)

    @Test fun englishUnchanged() {
        assertEquals("In 300 meters, turn right", en.inDistance(312.0, true, "Turn right"))
        assertEquals("Continue for 2 kilometers", en.continueFor(en.distance(2000.0, true)))
        assertEquals("Take the 3rd exit", en.short(step(Maneuver.ROUNDABOUT_ENTER, 3)))
        assertEquals("Turn right, then turn left", en.then("Turn right", "Turn left"))
        assertEquals("en", VoiceText.forLanguage("pt").lang) // not ours: English
    }

    @Test fun otherLanguages() {
        assertEquals("En 300 metros, gire a la derecha", es.inDistance(300.0, true, es.short(step(Maneuver.RIGHT))))
        assertEquals("Tome la tercera salida", es.short(step(Maneuver.ROUNDABOUT_ENTER, 3)))
        assertEquals("1,5 kilómetros", es.distance(1500.0, true))
        assertEquals("Dans 200 mètres, tournez à gauche", fr.inDistance(200.0, true, fr.short(step(Maneuver.LEFT))))
        assertEquals("Tra 100 metri, svolta a destra", it.inDistance(100.0, true, it.short(step(Maneuver.RIGHT))))
        assertEquals("In 300 Metern: Rechts abbiegen", de.inDistance(300.0, true, de.short(step(Maneuver.RIGHT))))
        assertEquals("In einem Kilometer: Links halten", de.inDistance(1000.0, true, de.short(step(Maneuver.KEEP_LEFT))))
        assertEquals("Nehmen Sie die zweite Ausfahrt", de.short(step(Maneuver.ROUNDABOUT_ENTER, 2)))
        assertEquals("Llegó a su destino", es.arrived())
        assertTrue(es.headsUp(3, "Castillo X", TouristCategory.HISTORIC, 6).startsWith("En unos 3 minutos, Castillo X, sitio histórico, a unos 6 minutos"))
        assertEquals("en", VoiceText.defaultFor("pt"))
        assertEquals("es", VoiceText.defaultFor("es"))
    }

    @Test fun trackerSpeaksTheChosenLanguage() {
        val o = LatLon(45.0, 6.0)
        val c = Geo.project(o, 0.0, 1000.0)
        val pts = (0..10).map { Geo.project(o, 0.0, it * 100.0) } + (1..10).map { Geo.project(c, 90.0, it * 100.0) }
        val steps = listOf(NavStep("Diríjase al norte", Maneuver.DEPART, 1000.0, 100.0, 0, 10),
            NavStep("Gire a la derecha en Calle B", Maneuver.RIGHT, 1000.0, 100.0, 10, 20),
            NavStep("Llegada", Maneuver.GOAL, 0.0, 0.0, 20, 20))
        val t = NavigationTracker(RouteResult(pts, 2000.0, 200.0, steps = steps), TravelMode.DRIVING, true, false, es)
        val said = ArrayList<String>()
        var d = 0.0
        while (d < 1000.0) { t.update(Fix(Geo.project(o, 0.0, d).lat, Geo.project(o, 0.0, d).lon, 10f, null, 5f, d.toLong())).speech?.let { said.add(it) }; d += 20.0 }
        assertEquals("En 300 metros, gire a la derecha en Calle B", said[0])
    }
}
