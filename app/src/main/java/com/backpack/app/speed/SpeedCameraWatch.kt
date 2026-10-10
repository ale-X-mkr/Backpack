package com.backpack.app.speed

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.backpack.app.Prefs
import com.backpack.app.core.Geo
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteResult
import com.backpack.app.core.SpeedCameras
import com.backpack.app.core.SpeedLimits
import com.backpack.app.core.VoiceText
import com.backpack.app.nav.NavigationEngine
import com.backpack.app.net.OverpassClient

/**
 * 1.4.0: fixed speed cameras, sound only (Settings > Speed, off by default).
 * Cameras are loaded with the road ahead of the route (or around you
 * without one); the country's rule decides what is heard: a sonar blip
 * that speeds up as you approach, one "danger zone" warning (France), or
 * nothing (see [SpeedCameras.rule]). Driven by [SpeedGuard] fixes, main thread.
 */
object SpeedCameraWatch {

    private const val SECTION_M = 20_000.0
    private const val REFILL_M = 8_000.0
    private const val POINT_RADIUS_M = 10_000
    private const val ON_ROUTE_M = 40.0

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cameras = LinkedHashMap<Long, SpeedCameras.Camera>()
    /** Cameras passed / zones announced: never again this session. */
    private val done = HashSet<Long>()

    // Route loading.
    private var sectionRoute: RouteResult? = null
    private var loadedUntilM = 0.0
    private var inFlight = false
    private var failedAtMs = 0L
    /** Where each camera lies along the current route (null = not on it). */
    private var alongRoute: RouteResult? = null
    private val alongOf = HashMap<Long, Double?>()

    // Loading without a route.
    private var pointCenter: LatLon? = null
    private var pointAtMs = 0L
    private var pointInFlight = false

    // The blips.
    private var app: Context? = null
    private var targetM = 0.0
    private var alertM = 1000.0
    private var speedMs = 0.0
    private var fixAtMs = 0L
    private var blipping = false
    private var blipTrack: android.media.AudioTrack? = null
    private var zoneTrack: android.media.AudioTrack? = null

    /** A fix in car mode (from [SpeedGuard]). */
    fun onFix(c: Context, location: Location, limit: Int?, route: RouteResult?, alongM: Double?) {
        app = c.applicationContext
        if (!Prefs.cameras(c)) { reset(); return }
        val me = LatLon(location.latitude, location.longitude)
        val onRoute = route != null && alongM != null && route.points.size >= 2 &&
            (route.line.snap(me)?.offsetM ?: Double.MAX_VALUE) < 60.0
        if (onRoute) loadAlong(c, route!!, alongM!!) else loadAround(c, me)

        speedMs = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        alertM = SpeedCameras.alertDistance(speedMs)
        var nearest: Double? = null
        for (cam in cameras.values) {
            if (cam.id in done) continue
            val rule = SpeedCameras.rule(cam.country)
            if (rule == SpeedCameras.Rule.NONE) continue
            val ahead = (if (onRoute) aheadOnRoute(cam, route!!, alongM!!) else aheadFree(cam, me, location)) ?: continue
            if (ahead < -15.0) { done.add(cam.id); continue }
            when (rule) {
                SpeedCameras.Rule.EXACT ->
                    if (ahead <= alertM && (nearest == null || ahead < nearest)) nearest = ahead.coerceAtLeast(0.0)
                SpeedCameras.Rule.ZONE -> {
                    val len = SpeedCameras.zoneLength(limit)
                    if (ahead <= SpeedCameras.zoneLead(cam, len)) { done.add(cam.id); warnZone(c) }
                }
                SpeedCameras.Rule.NONE -> Unit
            }
        }
        fixAtMs = System.currentTimeMillis()
        if (nearest == null) { stopBlips(); return }
        targetM = nearest
        if (!blipping) { blipping = true; main.post(blip) }
    }

    /** Stopped driving / switched off. */
    fun reset() {
        stopBlips()
    }

    fun release() {
        stopBlips()
        try { blipTrack?.release() } catch (e: Exception) { }
        try { zoneTrack?.release() } catch (e: Exception) { }
        blipTrack = null
        zoneTrack = null
    }

    // ---------- where the cameras are, relative to you ----------

    private fun aheadOnRoute(cam: SpeedCameras.Camera, route: RouteResult, alongM: Double): Double? {
        if (alongRoute !== route) { alongRoute = route; alongOf.clear() }
        val at = alongOf.getOrPut(cam.id) {
            route.line.snap(cam.p)?.takeIf { it.offsetM <= ON_ROUTE_M }?.alongM
        } ?: return null
        return at - alongM
    }

    /** No route: straight distance, only for a camera in front of you (within 25°). */
    private fun aheadFree(cam: SpeedCameras.Camera, me: LatLon, location: Location): Double? {
        if (!location.hasBearing() || !location.hasSpeed() || location.speed < 3f) return null
        val d = Geo.distance(me, cam.p)
        val off = Geo.angleDiff(Geo.bearing(me, cam.p), location.bearing.toDouble())
        if (d < 40.0 && off > 90.0) return -20.0 // just went past it
        return if (off <= 25.0) d else null
    }

    // ---------- loading ----------

    private fun loadAlong(c: Context, route: RouteResult, alongM: Double) {
        if (inFlight) return
        val now = System.currentTimeMillis()
        if (route !== sectionRoute) { sectionRoute = route; loadedUntilM = alongM; failedAtMs = 0L }
        if (now - failedAtMs < 60_000L) return
        if (loadedUntilM >= minOf(route.line.length, alongM + REFILL_M)) return
        val from = maxOf(loadedUntilM, alongM)
        inFlight = true
        val app = c.applicationContext
        worker.execute {
            val part = SpeedLimits.simplify(SpeedLimits.section(route.line, from, SECTION_M), 5.0)
            val query = SpeedCameras.corridorQuery(part)
            main.post {
                OverpassClient.query(app, query) { body, _ ->
                    worker.execute {
                        val found = body?.let { try { SpeedCameras.parse(it) } catch (e: Exception) { null } }
                        main.post {
                            inFlight = false
                            if (found == null) { failedAtMs = System.currentTimeMillis(); return@post }
                            if (route === sectionRoute) loadedUntilM = from + SECTION_M
                            keep(found)
                        }
                    }
                }
            }
        }
    }

    /** No route: 10 km around you, again only near the edge (6 km away from the last centre). */
    private fun loadAround(c: Context, me: LatLon) {
        if (pointInFlight) return
        val now = System.currentTimeMillis()
        val center = pointCenter
        if (center != null && Geo.distance(center, me) < POINT_RADIUS_M * 0.6) return
        if (now - pointAtMs < 60_000L) return
        pointAtMs = now
        pointInFlight = true
        OverpassClient.query(c.applicationContext, SpeedCameras.pointQuery(me, POINT_RADIUS_M), background = true) { body, _ ->
            worker.execute {
                val found = body?.let { try { SpeedCameras.parse(it) } catch (e: Exception) { null } }
                main.post {
                    pointInFlight = false
                    if (found != null) { pointCenter = me; keep(found) }
                }
            }
        }
    }

    private fun keep(found: List<SpeedCameras.Camera>) {
        for (cam in found) cameras[cam.id] = cam
        // A long day of driving: keep the newest few thousand.
        while (cameras.size > 3000) cameras.remove(cameras.keys.first())
        if (done.size > 5000) done.clear()
    }

    // ---------- sounds ----------

    private val blip = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!blipping || now - fixAtMs > 3000L) { blipping = false; return }
            // Between fixes, the distance shrinks with your speed.
            val left = (targetM - speedMs * (now - fixAtMs) / 1000.0).coerceAtLeast(0.0)
            play(true)
            main.postDelayed(this, SpeedCameras.blipIntervalMs(left, alertM))
        }
    }

    private fun stopBlips() {
        if (blipping) { blipping = false; main.removeCallbacks(blip) }
    }

    private fun warnZone(c: Context) {
        play(false)
        // 1.4.1: said in every sound mode, with or without navigation (queued, never cuts an instruction).
        NavigationEngine.speakDanger(c, VoiceText.forLanguage(Prefs.voiceLanguage(c)).dangerZone())
    }

    private fun play(single: Boolean) {
        try {
            val t = if (single) blipTrack ?: makeTrack(listOf(1400.0)).also { blipTrack = it }
            else zoneTrack ?: makeTrack(listOf(900.0, 1200.0)).also { zoneTrack = it }
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) { }
    }

    /** Sonar "pings": 70 ms each with a fast decay, 120 ms apart. */
    private fun makeTrack(freqs: List<Double>): android.media.AudioTrack {
        val rate = 22050
        val ping = rate * 7 / 100
        val gap = rate * 12 / 100
        val n = gap * (freqs.size - 1) + ping
        val pcm = ShortArray(n)
        freqs.forEachIndexed { k, f ->
            val start = k * gap
            for (i in 0 until ping) {
                val t = i.toDouble() / rate
                val attack = minOf(1.0, i / (rate * 0.004))
                val v = Math.sin(2 * Math.PI * f * t) * attack * Math.exp(-t * 45.0)
                pcm[start + i] = (v * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        track.write(pcm, 0, n)
        return track
    }
}
