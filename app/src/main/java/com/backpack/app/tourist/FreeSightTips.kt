package com.backpack.app.tourist

import android.content.Context
import android.location.Location
import com.backpack.app.Prefs
import com.backpack.app.Settings
import com.backpack.app.TripState
import com.backpack.app.core.Geo
import com.backpack.app.core.LatLon
import com.backpack.app.core.SightGroup
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.core.VoiceQueue
import com.backpack.app.core.VoiceText
import com.backpack.app.nav.NavigationEngine

/**
 * 1.8.1 (Alex): sight tips without a route. Weekend with the sights camera
 * on, moving, not navigating and no route planned: a well-known place
 * ahead of you (within 30 degrees of your direction), within your Weekend
 * detour and your "minutes before" setting, gets the same voice tip and
 * card as during navigation (also with the Alerts-only sound mode: the
 * camera switch is the choice). Once per place. Runs from the map screen's
 * fixes and, by car, from [com.backpack.app.speed.SpeedGuard] (screen off).
 */
object FreeSightTips {

    private const val CONE_DEG = 30.0
    private val said = HashSet<String>()

    fun onFix(c: Context, location: Location) {
        if (NavigationEngine.isActive || TripState.plan != null) return
        if (!location.hasBearing() || !location.hasSpeed() || location.speed < 1.0f) return
        val toggles = c.getSharedPreferences("backpack_toggle_state", Context.MODE_PRIVATE)
        val prefs = Prefs.settings(c)
        if (toggles.getString("urgency", "WEEKEND") != "WEEKEND") return
        if (!prefs.getBoolean(Settings.KEY_SIGHTS_WEEKEND, true)) return
        if (SightGroup.parse(prefs.getString(Settings.KEY_SIGHT_GROUPS, null)).isEmpty()) return
        if (!prefs.getBoolean(Settings.KEY_SIGHT_HEADSUP, true)) return
        if (NavigationEngine.pendingInfo(VoiceQueue.Kind.HEADS_UP) >= 2) return
        val mode = when (toggles.getString("mode", null)) {
            "CYCLING" -> TravelMode.CYCLING
            "DRIVING" -> TravelMode.DRIVING
            else -> TravelMode.WALKING
        }
        val me = LatLon(location.latitude, location.longitude)
        val heading = location.bearing.toDouble()
        val speed = location.speed.toDouble()
        val minutes = prefs.getInt(Settings.KEY_SIGHT_HEADSUP_MIN, Settings.DEFAULT_SIGHT_HEADSUP_MIN)
        val lead = (minutes * 60.0 * speed).coerceIn(150.0, 10_000.0)
        val maxDetour = prefs.getInt(Settings.KEY_DETOUR_WEEKEND_MIN, Settings.DEFAULT_DETOUR_WEEKEND_MIN) * 60.0
        val maxOff = Tourist.offsetForDetour(maxDetour, mode)
        Tourist.order = SightGroup.parseOrder(prefs.getString(Settings.KEY_SIGHT_ORDER, null))

        data class Ahead(val poi: TouristPoi, val dist: Double, val off: Double)
        val best = TripState.touristResults.asSequence()
            .filter { it.key !in said && it.sitelinks >= 1 }
            .mapNotNull { p ->
                val d = Geo.distance(me, p.point)
                if (d > lead || d < 50.0) return@mapNotNull null
                val angle = Geo.angleDiff(Geo.bearing(me, p.point), heading)
                if (angle > CONE_DEG) return@mapNotNull null
                val off = d * Math.sin(Math.toRadians(angle))
                if (off > maxOff) null else Ahead(p, d, off)
            }
            .maxByOrNull { Tourist.score(it.poi) } ?: return
        said.add(best.poi.key)
        if (said.size > 2000) said.clear()
        val poi = best.poi
        val detour = Tourist.estimateDetourSeconds(best.off, mode)
        val words = VoiceText.forLanguage(Prefs.voiceLanguage(c))
        fun stillAhead(): Boolean {
            val f = com.backpack.app.location.LocationHub.lastGoodFix ?: return true
            val here = LatLon(f.latitude, f.longitude)
            return !f.hasBearing() || Geo.angleDiff(Geo.bearing(here, poi.point), f.bearing.toDouble()) <= 90.0
        }
        NavigationEngine.speakInfo(VoiceQueue.Item(
            VoiceQueue.Kind.HEADS_UP,
            text = {
                val inMin = Math.round(best.dist / speed.coerceAtLeast(0.5) / 60.0).toInt().coerceAtLeast(1)
                words.headsUp(inMin, poi.name, poi.category, Math.round(detour / 60.0).toInt().coerceAtLeast(1))
            },
            valid = { !NavigationEngine.isActive && stillAhead() },
            onStart = { SightHeadsUp.ui?.onHeadsUp(poi, Tourist.minutesLabel(detour)) }
        ), c)
    }
}
