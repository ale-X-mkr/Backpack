package com.backpack.app.speed

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.backpack.app.Prefs
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteResult
import com.backpack.app.core.SpeedLimits
import com.backpack.app.core.TollExits
import com.backpack.app.core.VoiceText
import com.backpack.app.nav.NavigationEngine
import com.backpack.app.net.OverpassClient

/**
 * 1.8.1 (Alex): "last exit before the toll", by voice (every sound mode,
 * like the other alerts) and as a red alert message on the map screen.
 * With a route (navigating or planned and on it): the last motorway exit
 * before each toll stretch of the route. Without one: the last exit before
 * a toll barrier straight ahead at motorway speed. Car mode, driven by
 * [SpeedGuard] fixes (screen off too). Main thread.
 */
object TollExitWatch {

    /** The map screen's alert line while it is shown. */
    var ui: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    // With a route: toll start (m along) -> its last exit (m along; NaN = none / not known yet).
    private var forRoute: RouteResult? = null
    private val lastExitOf = HashMap<Double, Double>()
    private val loading = HashSet<Double>()
    private var failedAtMs = 0L

    // Without a route: nodes around, reloaded after 6 km or 2 min.
    private var freeNodes: List<TollExits.Node> = emptyList()
    private var freeCenter: LatLon? = null
    private var freeAtMs = 0L
    private var freeInFlight = false

    /** Announcements already made: "<key>:early" / "<key>:now". */
    private val said = HashSet<String>()

    fun onFix(c: Context, location: Location, route: RouteResult?, alongM: Double?) {
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val onRoute = route != null && alongM != null && route.points.size >= 2 &&
            (route.line.snap(LatLon(location.latitude, location.longitude))?.offsetM ?: Double.MAX_VALUE) < 60.0
        if (onRoute) withRoute(c, route!!, alongM!!, speed) else withoutRoute(c, location, speed)
    }

    // ---------- with a route ----------

    private fun withRoute(c: Context, route: RouteResult, alongM: Double, speed: Double) {
        if (route !== forRoute) { forRoute = route; lastExitOf.clear(); loading.clear(); failedAtMs = 0L }
        if (route.tollRanges.isEmpty()) return
        for (start in TollExits.starts(route.tollRanges, route.line)) {
            if (start < alongM) continue
            // Look the exits up once the toll start is within 50 km.
            if (start - alongM > TollExits.LOOK_BACK_M + 20_000.0) continue
            val exit = lastExitOf[start]
            if (exit == null) { load(c, route, start); continue }
            if (exit.isNaN()) continue
            announce(c, "r${start.toLong()}", exit - alongM, speed)
        }
    }

    private fun load(c: Context, route: RouteResult, start: Double) {
        if (start in loading) return
        if (System.currentTimeMillis() - failedAtMs < 60_000L) return
        loading.add(start)
        val app = c.applicationContext
        val from = (start - TollExits.LOOK_BACK_M).coerceAtLeast(0.0)
        worker.execute {
            val part = SpeedLimits.simplify(SpeedLimits.section(route.line, from, start - from), 5.0)
            val query = TollExits.corridorQuery(part)
            main.post {
                OverpassClient.query(app, query, background = true) { body, _ ->
                    worker.execute {
                        val nodes = body?.let { try { TollExits.parse(it) } catch (e: Exception) { null } }
                        // Where each exit lies along the route (on it: within 40 m).
                        val along = nodes?.mapNotNull { n -> route.line.snap(n.p)?.takeIf { it.offsetM <= 40.0 }?.alongM }
                        main.post {
                            loading.remove(start)
                            if (route !== forRoute) return@post
                            if (along == null) { failedAtMs = System.currentTimeMillis(); return@post }
                            lastExitOf[start] = TollExits.lastExitAlong(along, start) ?: Double.NaN
                        }
                    }
                }
            }
        }
    }

    // ---------- without a route ----------

    private fun withoutRoute(c: Context, location: Location, speed: Double) {
        if (!location.hasBearing() || location.speed < TollExits.FREE_MIN_SPEED) return
        val me = LatLon(location.latitude, location.longitude)
        val now = System.currentTimeMillis()
        val center = freeCenter
        if (!freeInFlight && (center == null || com.backpack.app.core.Geo.distance(center, me) > 6000.0 || now - freeAtMs > 120_000L)) {
            freeInFlight = true
            freeAtMs = now
            OverpassClient.query(c.applicationContext, TollExits.pointQuery(me), background = true) { body, _ ->
                worker.execute {
                    val nodes = body?.let { try { TollExits.parse(it) } catch (e: Exception) { null } }
                    main.post {
                        freeInFlight = false
                        if (nodes != null) { freeNodes = nodes; freeCenter = me }
                    }
                }
            }
        }
        val (exit, dist) = TollExits.lastExitAhead(me, location.bearing.toDouble(), freeNodes) ?: return
        announce(c, "n${exit.id}", dist, speed)
    }

    // ---------- saying it ----------

    private fun announce(c: Context, key: String, aheadM: Double, speed: Double) {
        if (aheadM < 0.0) return
        val words = VoiceText.forLanguage(Prefs.voiceLanguage(c))
        val metric = Prefs.useMetric(c)
        val text = when {
            aheadM <= TollExits.nowM(speed) && said.add("$key:now") -> { said.add("$key:early"); words.lastExitBeforeToll() }
            aheadM <= TollExits.earlyM(speed) && aheadM > TollExits.nowM(speed) * 1.5 && said.add("$key:early") ->
                words.inDistance(aheadM, metric, words.lastExitBeforeToll())
            else -> return
        }
        NavigationEngine.speakDanger(c, text, validMs = 20_000L)
        ui?.invoke(text)
        if (said.size > 500) said.clear()
    }
}
