package com.backpack.app.speed

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteResult
import com.backpack.app.core.SpeedLimitIndex
import com.backpack.app.core.SpeedLimits
import com.backpack.app.net.OverpassClient

/**
 * Road speed limits for the speed sign (Driving).
 *
 * With a route: the roads along the next [SECTION_M] of it are loaded in
 * one request (and the next stretch when less than [REFILL_M] is left),
 * then each GPS fix is looked up on the phone. Without a route, or off it:
 * one quick lookup around the position at most every 15 s, as before.
 * Memory only. Main thread.
 */
object SpeedLimitLookup {

    private const val SECTION_M = 20_000.0
    private const val REFILL_M = 8_000.0
    private const val CORRIDOR_M = 20

    private val index = SpeedLimitIndex()
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var sectionRoute: RouteResult? = null
    private var sectionInFlight = false
    private var sectionFailedAtMs = 0L
    private var checkedRoute: RouteResult? = null
    private var checkedAlongM = 0.0
    private var checkedAtMs = 0L
    private var pointInFlight = false
    private var lastPointAtMs = 0L

    /**
     * Makes sure the roads ahead of [alongM] on [route] are loaded (or being
     * loaded). Cheap to call on every fix.
     */
    fun prepare(c: Context, route: RouteResult, alongM: Double) {
        if (sectionInFlight || route.points.size < 2) return
        // After a failure, wait a minute before asking for the same stretch again.
        val now = System.currentTimeMillis()
        if (route === sectionRoute && now - sectionFailedAtMs < 60_000L) return
        // The coverage check walks the route: not more than every 500 m / 30 s.
        if (route === checkedRoute && Math.abs(alongM - checkedAlongM) < 500.0 && now - checkedAtMs < 30_000L) return
        checkedRoute = route
        checkedAlongM = alongM
        checkedAtMs = now
        val line = route.line
        val until = index.coveredUntil(line, alongM, REFILL_M + 250.0)
        if (until >= minOf(line.length, alongM + REFILL_M)) return
        sectionInFlight = true
        sectionRoute = route
        val app = c.applicationContext
        worker.execute {
            val part = SpeedLimits.simplify(SpeedLimits.section(line, until, SECTION_M), 5.0)
            val query = SpeedLimits.corridorQuery(part, CORRIDOR_M)
            main.post {
                OverpassClient.query(app, query) { body, _ ->
                    if (body == null) {
                        sectionInFlight = false
                        sectionFailedAtMs = System.currentTimeMillis()
                        checkedRoute = null
                        return@query
                    }
                    worker.execute {
                        val ways = try { SpeedLimits.parseWays(body) } catch (e: Exception) { null }
                        main.post {
                            sectionInFlight = false
                            checkedRoute = null // check again on the next fix
                            if (ways == null) sectionFailedAtMs = System.currentTimeMillis()
                            else index.add(ways, part, CORRIDOR_M.toDouble())
                        }
                    }
                }
            }
        }
    }

    /** True when [location] lies on a stretch already loaded. */
    fun covers(location: Location): Boolean = index.isCovered(LatLon(location.latitude, location.longitude))

    /** Limit (km/h) of the road you are on, from loaded data; null = unknown / none. */
    fun limitAt(location: Location): Int? {
        val bearing = if (location.hasBearing() && location.hasSpeed() && location.speed > 3f) location.bearing.toDouble() else null
        return index.limitAt(LatLon(location.latitude, location.longitude), bearing)
    }

    /**
     * Free driving (no route, or off it): one quick lookup around the
     * position, at most every 15 s. [done] gets true when an answer arrived.
     */
    fun lookupHere(c: Context, location: Location, done: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        if (pointInFlight || now - lastPointAtMs < 15_000L) return
        lastPointAtMs = now
        pointInFlight = true
        val p = LatLon(location.latitude, location.longitude)
        // Quick (15 s, one round) and silent: a busy server must not cover the
        // screen with errors every 15 s.
        OverpassClient.query(c.applicationContext, SpeedLimits.pointQuery(p), quick = true) { body, _ ->
            pointInFlight = false
            // An answer older than 20 s is about a road already behind you.
            if (body == null || System.currentTimeMillis() - now > 20_000L) { done(false); return@query }
            val ways = try { SpeedLimits.parseWays(body) } catch (e: Exception) { null }
            if (ways == null) { done(false); return@query }
            index.add(ways, listOf(p), 25.0)
            done(true)
        }
    }

    /** New trip in another area: forget nothing (roads stay valid), just the failure pause. */
    fun routeChanged() {
        sectionFailedAtMs = 0L
        checkedRoute = null
    }
}
