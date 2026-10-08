package com.backpack.app.speed

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import com.backpack.app.Prefs
import com.backpack.app.TripState
import com.backpack.app.core.LatLon
import com.backpack.app.core.SpeedAlarm
import com.backpack.app.location.LocationHub
import com.backpack.app.nav.NavigationEngine

/**
 * 1.3.0: the speed-limit alarm, outside the screen, so it keeps watching
 * with the screen off or another app in front (until Backpack is exited).
 * Car mode only, rules in [SpeedAlarm] (unchanged). The map screen only
 * draws what it reports.
 */
object SpeedGuard {

    fun interface Listener {
        fun onSpeed(location: Location, limit: Int?, state: SpeedAlarm.State, beeped: Boolean)
    }

    private val listeners = mutableListOf<Listener>()
    private var app: Context? = null
    private var started = false

    /** Road limit (km/h) known for where you are, kept 2 min when nothing answers. */
    var limit: Int? = null
        private set
    private var limitOkAtMs = 0L
    private var lastBeepAtMs = 0L
    private var tone: ToneGenerator? = null

    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val fixListener = LocationHub.Listener { location, plausible -> if (plausible) onFix(location) }

    /** Idempotent: from the screen and from the background service. */
    fun start(c: Context) {
        app = c.applicationContext
        if (started) return
        started = true
        LocationHub.addListener(fixListener)
    }

    fun stop() {
        if (!started) return
        started = false
        LocationHub.removeListener(fixListener)
        try { tone?.release() } catch (e: Exception) { }
        tone = null
    }

    /** Car mode (the screen saves it on every change). */
    fun driving(c: Context): Boolean =
        c.getSharedPreferences("backpack_toggle_state", Context.MODE_PRIVATE).getString("mode", null) == "DRIVING"

    private fun onFix(location: Location) {
        val c = app ?: return
        if (!driving(c)) return
        updateLimit(c, location)
        report(c, location)
    }

    private fun report(c: Context, location: Location) {
        val kmh = (location.speed * 3.6).toInt()
        val moving = location.hasSpeed() && kmh > 3
        val st = SpeedAlarm.state(kmh, moving, limit)
        val beeped = st.beep != SpeedAlarm.Beep.NONE && beep(c, st.beep == SpeedAlarm.Beep.HIGH)
        for (l in listeners.toList()) l.onSpeed(location, limit, st, beeped)
    }

    /** The route being followed (or chosen): its roads ahead are loaded in one go. */
    private fun updateLimit(c: Context, location: Location) {
        val now = System.currentTimeMillis()
        val route = (if (NavigationEngine.isActive) NavigationEngine.route else null)
            ?: TripState.plan?.routes?.getOrNull(TripState.selectedIndex)
        if (route != null && route.points.size >= 2) {
            val along = (if (NavigationEngine.isActive) NavigationEngine.lastProgress?.alongM else null)
                ?: route.line.snap(LatLon(location.latitude, location.longitude))?.alongM ?: 0.0
            SpeedLimitLookup.prepare(c, route, along)
        }
        if (SpeedLimitLookup.covers(location)) {
            limit = SpeedLimitLookup.limitAt(location)
            limitOkAtMs = now
            return
        }
        if (now - limitOkAtMs > 120_000L) limit = null
        SpeedLimitLookup.lookupHere(c, location) { ok ->
            if (!ok) return@lookupHere
            val fix = LocationHub.lastFix ?: return@lookupHere
            if (SpeedLimitLookup.covers(fix)) {
                limit = SpeedLimitLookup.limitAt(fix)
                limitOkAtMs = System.currentTimeMillis()
            }
            if (driving(c)) report(c, fix)
        }
    }

    /** Settings > Speed > Alarm sound; at most every 3 s. True when it sounded. */
    private fun beep(c: Context, high: Boolean): Boolean {
        if (!Prefs.alarmSound(c)) return false
        val now = System.currentTimeMillis()
        if (now - lastBeepAtMs < 3000L) return false
        lastBeepAtMs = now
        return try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone?.startTone(if (high) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_LOW_L, 200)
            true
        } catch (e: Exception) { false }
    }
}
