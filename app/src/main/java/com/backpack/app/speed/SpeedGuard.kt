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

    /** 1.7.2: each alarm sound as it plays (the ring flashes [color] for [ms], in step). */
    fun interface SoundListener { fun onSound(color: Int, ms: Long) }
    private val soundListeners = mutableListOf<SoundListener>()
    fun addSoundListener(l: SoundListener) { if (l !in soundListeners) soundListeners.add(l) }
    fun removeSoundListener(l: SoundListener) { soundListeners.remove(l) }
    val FLASH_RED = 0xFFC0392B.toInt()
    val FLASH_BLUE = 0xFF1565C0.toInt()
    private fun flash(color: Int, ms: Long) {
        main.post { for (l in soundListeners.toList()) l.onSound(color, ms) }
    }

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
        stopContinuous()
        try { buzzTrack?.release() } catch (e: Exception) { }
        buzzTrack = null
        SpeedCameraWatch.release()
    }

    /** Car mode (the screen saves it on every change). */
    fun driving(c: Context): Boolean =
        c.getSharedPreferences("backpack_toggle_state", Context.MODE_PRIVATE).getString("mode", null) == "DRIVING"

    private fun onFix(location: Location) {
        val c = app ?: return
        if (!driving(c)) { stopContinuous(); SpeedCameraWatch.reset(); return }
        updateLimit(c, location)
        report(c, location)
        // 1.4.0: fixed speed cameras (sound only, off by default).
        val route = routeNow()
        val along = route?.let { alongOn(it, location) }
        SpeedCameraWatch.onFix(c, location, limit, route, along)
    }

    private fun routeNow() = (if (NavigationEngine.isActive) NavigationEngine.route else null)
        ?: TripState.plan?.routes?.getOrNull(TripState.selectedIndex)

    private fun alongOn(route: com.backpack.app.core.RouteResult, location: Location): Double? =
        (if (NavigationEngine.isActive && route === NavigationEngine.route) NavigationEngine.lastProgress?.alongM else null)
            ?: route.line.snap(LatLon(location.latitude, location.longitude))?.alongM

    private fun report(c: Context, location: Location) {
        val kmh = (location.speed * 3.6).toInt()
        val moving = location.hasSpeed() && kmh > 3
        val st = SpeedAlarm.state(kmh, moving, limit)
        val beeped = when (st.beep) {
            SpeedAlarm.Beep.NONE -> { stopContinuous(); false }
            SpeedAlarm.Beep.HIGH -> { stopContinuous(); beep(c, true) }
            // Stopped on a fast road: the same buzz, the ring flashing red.
            SpeedAlarm.Beep.LOW -> { stopContinuous(); buzz(c, if (st.ring == SpeedAlarm.Ring.STOPPED_RED) FLASH_RED else FLASH_BLUE) }
            SpeedAlarm.Beep.CONTINUOUS -> { startContinuous(c, high = true); true }
            SpeedAlarm.Beep.LOW_CONTINUOUS -> { startContinuous(c, high = false); true }
        }
        for (l in listeners.toList()) l.onSpeed(location, limit, st, beeped)
    }

    // ---------- 1.4.0: the "priiip" repeated without pause (20+ over), the low buzz ----------

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var continuousUntilMs = 0L
    private var continuousOn = false
    /** 1.7.2: the high "priiip" (20+ over) or the low buzz (far too slow) without pause. */
    private var continuousHigh = true
    private val continuous = object : Runnable {
        override fun run() {
            val c = app ?: return
            // A fix every second keeps it going; GPS lost -> it stops by itself.
            if (System.currentTimeMillis() > continuousUntilMs || !Prefs.alarmSound(c)) { continuousOn = false; return }
            if (continuousHigh) { playHigh(); main.postDelayed(this, 450L) }
            else { playBuzz(FLASH_BLUE); main.postDelayed(this, 550L) }
        }
    }

    private fun startContinuous(c: Context, high: Boolean) {
        continuousUntilMs = System.currentTimeMillis() + 2500L
        if (continuousOn && continuousHigh != high) { main.removeCallbacks(continuous); continuousOn = false }
        continuousHigh = high
        if (continuousOn || !Prefs.alarmSound(c)) return
        continuousOn = true
        main.post(continuous)
    }

    private fun stopContinuous() {
        continuousUntilMs = 0L
        if (continuousOn) { continuousOn = false; main.removeCallbacks(continuous) }
    }

    private fun playHigh() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 200)
            flash(FLASH_RED, 300L)
        } catch (e: Exception) { }
    }

    private var lastBuzzAtMs = 0L
    private var buzzTrack: android.media.AudioTrack? = null

    /** Low-speed / stopped warning: a real low buzz (~250 Hz), at most every 3 s. */
    private fun buzz(c: Context, color: Int): Boolean {
        if (!Prefs.alarmSound(c)) return false
        val now = System.currentTimeMillis()
        if (now - lastBuzzAtMs < 3000L) return false
        lastBuzzAtMs = now
        return playBuzz(color)
    }

    /** The low buzz now; the ring flashes [color] while it sounds. */
    private fun playBuzz(color: Int): Boolean = try {
        val t = buzzTrack ?: makeBuzz().also { buzzTrack = it }
        t.stop()
        t.reloadStaticData()
        t.play()
        flash(color, 350L)
        true
    } catch (e: Exception) { false }

    /** 350 ms at 250 Hz with a little 500 Hz (heard on phone speakers too), soft edges. */
    private fun makeBuzz(): android.media.AudioTrack {
        val rate = 22050
        val n = rate * 35 / 100
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val edge = minOf(1.0, minOf(i, n - i) / (rate * 0.02))
            val v = (0.75 * Math.sin(2 * Math.PI * 250 * t) + 0.25 * Math.sin(2 * Math.PI * 500 * t)) * edge
            pcm[i] = (v * 0.8 * Short.MAX_VALUE).toInt().toShort()
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

    /** The route being followed (or chosen): its roads ahead are loaded in one go. */
    private fun updateLimit(c: Context, location: Location) {
        val now = System.currentTimeMillis()
        val route = routeNow()
        if (route != null && route.points.size >= 2) {
            SpeedLimitLookup.prepare(c, route, alongOn(route, location) ?: 0.0)
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

    /** Settings > Speed > Alarm sound; the high "priiip" at most every 3 s. True when it sounded. */
    private fun beep(c: Context, high: Boolean): Boolean {
        if (!Prefs.alarmSound(c)) return false
        val now = System.currentTimeMillis()
        if (now - lastBeepAtMs < 3000L) return false
        lastBeepAtMs = now
        if (high) playHigh()
        return true
    }
}
