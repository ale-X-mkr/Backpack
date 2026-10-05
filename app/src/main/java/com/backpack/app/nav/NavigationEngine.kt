package com.backpack.app.nav

import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.backpack.app.Prefs
import com.backpack.app.core.Fix
import com.backpack.app.core.LatLon
import com.backpack.app.core.NavigationTracker
import com.backpack.app.core.Progress
import com.backpack.app.core.RoutePlan
import com.backpack.app.core.RouteRequest
import com.backpack.app.core.RouteResult
import com.backpack.app.location.LocationHub
import com.backpack.app.route.RoutePlanner
import java.util.Locale

/**
 * Turn-by-turn navigation that lives outside the screen: it keeps going
 * with the screen off or the app in the background (NavigationService
 * keeps the process alive and shows the notification). The map screen
 * only observes it.
 */
object NavigationEngine {

    interface Listener {
        fun onProgress(progress: Progress, route: RouteResult) {}
        fun onRerouting() {}
        fun onRerouted(plan: RoutePlan) {}
        fun onRerouteFailed(error: String) {}
        fun onArrived() {}
        fun onStopped() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var app: Context? = null
    private var planner: RoutePlanner? = null
    private var tracker: NavigationTracker? = null
    private var request: RouteRequest? = null
    private var lastRerouteAtMs = 0L
    private var rerouting = false
    private var arrived = false

    var isActive = false
        private set
    var route: RouteResult? = null
        private set
    var lastProgress: Progress? = null
        private set
    /** Latest plan (after reroutes), for redrawing the map after the screen
     *  was recreated. */
    var plan: RoutePlan? = null
        private set
    var planSelected = 0
        private set

    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val fixListener = LocationHub.Listener { location, plausible ->
        if (plausible) onFix(location)
    }

    fun start(context: Context, plan: RoutePlan, selectedIndex: Int, req: RouteRequest) {
        val c = context.applicationContext
        app = c
        if (planner == null) planner = RoutePlanner(c)
        initTts(c)
        request = req
        this.plan = plan
        planSelected = selectedIndex.coerceIn(0, plan.routes.size - 1)
        setRoute(plan.routes[planSelected])
        isActive = true
        lastRerouteAtMs = System.currentTimeMillis()
        rerouting = false
        LocationHub.addListener(fixListener)
        LocationHub.acquire(c, LocationHub.LEASE_NAV)
        try {
            ContextCompat.startForegroundService(c, Intent(c, NavigationService::class.java))
        } catch (e: Exception) {
            // Android refused the service (rare, e.g. started from background):
            // navigation still works while the app is open.
        }
        tracker?.departureSpeech()?.let { speak(it) }
        LocationHub.lastGoodFix?.let { onFix(it) }
    }

    /** User picked another route chip mid-trip. */
    fun switchRoute(plan: RoutePlan, selectedIndex: Int, newRequest: RouteRequest? = null) {
        if (!isActive) return
        // A changed preference / toll / urgency mid-trip also applies to later reroutes.
        if (newRequest != null) request = newRequest
        this.plan = plan
        planSelected = selectedIndex.coerceIn(0, plan.routes.size - 1)
        setRoute(plan.routes[planSelected])
        LocationHub.lastGoodFix?.let { onFix(it) }
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        planner?.cancel()
        LocationHub.removeListener(fixListener)
        app?.let { c ->
            LocationHub.release(c, LocationHub.LEASE_NAV)
            c.stopService(Intent(c, NavigationService::class.java))
        }
        tracker = null
        route = null
        lastProgress = null
        plan = null
        pendingSpeech = null
        tts?.stop()
        for (l in listeners.toList()) l.onStopped()
    }

    /** Stops not yet visited, from the engine's own (possibly rerouted)
     *  request; null when not navigating. */
    fun remainingStops(): List<LatLon>? {
        if (!isActive) return null
        val req = request ?: return null
        val passed = (tracker?.passedStops() ?: 0).coerceAtMost(req.waypoints.size)
        return req.waypoints.drop(passed)
    }

    /** Start the voice engine early (it initialises asynchronously), so the
     *  first instruction of the first trip is not lost. */
    fun warmUp(context: Context) {
        if (app == null) app = context.applicationContext
        initTts(context.applicationContext)
    }

    private fun setRoute(r: RouteResult) {
        route = r
        arrived = false
        val c = app ?: return
        tracker = NavigationTracker(r, request?.mode ?: com.backpack.app.core.TravelMode.DRIVING, Prefs.useMetric(c), Prefs.voiceShort(c))
        lastProgress = null
    }

    /** Since when you stand still (speed < 0.5 m/s), for the GPS pace. */
    private var stillSinceMs = 0L

    /** 1.1.0 battery: GPS pace by travel mode, slower after 1 min standing still. */
    private fun adaptPace(c: Context, location: Location) {
        val now = System.currentTimeMillis()
        val moving = location.hasSpeed() && location.speed >= 0.5f
        if (moving) stillSinceMs = 0L else if (stillSinceMs == 0L) stillSinceMs = now
        val pace = when {
            stillSinceMs != 0L && now - stillSinceMs > 60_000L -> 5000L
            request?.mode == com.backpack.app.core.TravelMode.WALKING -> 3000L
            request?.mode == com.backpack.app.core.TravelMode.CYCLING -> 2000L
            else -> 1000L
        }
        LocationHub.setNavPace(c, pace)
    }

    private fun onFix(location: Location) {
        app?.let { adaptPace(it, location) }
        if (!isActive || arrived) return
        val t = tracker ?: return
        val r = route ?: return
        val fix = Fix(
            location.latitude, location.longitude,
            if (location.hasSpeed()) location.speed else null,
            if (location.hasBearing()) location.bearing else null,
            if (location.hasAccuracy()) location.accuracy else null,
            location.time
        )
        val p = t.update(fix)
        lastProgress = p
        p.speech?.let { speak(it) }
        for (l in listeners.toList()) l.onProgress(p, r)

        if (p.arrived) {
            // Freeze on "Arrived" for the last 4 s instead of flickering back.
            arrived = true
            speak("You have arrived")
            for (l in listeners.toList()) l.onArrived()
            main.postDelayed({ if (isActive && route === r) stop() }, 4000L)
            return
        }
        if (p.offRoute) maybeReroute(fix.latLon)
    }

    private fun maybeReroute(current: LatLon) {
        val now = System.currentTimeMillis()
        if (rerouting || now - lastRerouteAtMs < 15000L) return
        val req = request ?: return
        val pl = planner ?: return
        lastRerouteAtMs = now
        rerouting = true
        for (l in listeners.toList()) l.onRerouting()
        // Same request, same rules (incl. Balanced toll choice), new start,
        // minus the stops already visited.
        val passed = (tracker?.passedStops() ?: 0).coerceAtMost(req.waypoints.size)
        val newReq = req.copy(from = current, waypoints = req.waypoints.drop(passed))
        pl.plan(newReq) { newPlan, error ->
            rerouting = false
            if (!isActive) return@plan
            if (newPlan == null) {
                for (l in listeners.toList()) l.onRerouteFailed(error ?: "Reroute failed")
                return@plan
            }
            request = newReq
            plan = newPlan
            planSelected = newPlan.primaryIndex
            setRoute(newPlan.routes[newPlan.primaryIndex])
            speak("Route recalculated")
            for (l in listeners.toList()) l.onRerouted(newPlan)
            LocationHub.lastGoodFix?.let { onFix(it) }
        }
    }

    // ---------- Voice ----------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private fun initTts(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                // Instructions come from the server in English.
                tts?.language = Locale.ENGLISH
                tts?.setSpeechRate(0.85f)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = abandonFocus()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = abandonFocus()
                })
                // Said while the voice was still starting up.
                main.post { pendingSpeech?.let { pendingSpeech = null; if (isActive) speak(it) } }
            }
        }
    }

    fun speak(text: String) {
        // Settings > Navigation > Spoken instructions.
        if (app?.let { Prefs.voiceOn(it) } == false) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    /** Music and podcasts duck (get quieter) while an instruction plays. */
    private fun requestFocus() {
        val c = app ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build().also { focusRequest = it }
            am.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() {
        val c = app ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
    }
}
