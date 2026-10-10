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
    private const val REROUTE_GAP_MS = 8000L
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
        // New trip: the GPS pace starts from the travel mode, not a past stop.
        stillSinceMs = 0L
        LocationHub.setNavPace(c, when (req.mode) {
            com.backpack.app.core.TravelMode.WALKING -> 3000L
            com.backpack.app.core.TravelMode.CYCLING -> 2000L
            com.backpack.app.core.TravelMode.DRIVING -> 1000L
        })
        planSelected = selectedIndex.coerceIn(0, plan.routes.size - 1)
        // A new trip only (not an alternative started mid-trip): heads-ups said
        // stay said, and the track goes on in the same recording.
        if (!isActive) {
            LastTrip.clear(c)
            com.backpack.app.tourist.SightHeadsUp.reset()
            // 1.3.0: your track, on this phone only (Settings > Navigation).
            com.backpack.app.track.TripRecorder.start(c, null)
        }
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
        // 1.4.0: stopped before arriving -> "Resume navigation" can pick it up again.
        val c0 = app
        val req0 = request
        if (c0 != null && req0 != null) {
            if (arrived) LastTrip.clear(c0)
            else LastTrip.save(c0, LastTrip.Trip(req0.to, remainingStops() ?: req0.waypoints, req0.mode.name))
        }
        isActive = false
        planner?.cancel()
        com.backpack.app.track.TripRecorder.stop()
        LocationHub.removeListener(fixListener)
        app?.let { c ->
            LocationHub.release(c, LocationHub.LEASE_NAV)
            // 1.3.0: while Backpack is open (not exited) the service stays for
            // the speed alarm; it switches its notification itself (onStopped).
            if (!AppRunning.open) c.stopService(Intent(c, NavigationService::class.java))
        }
        tracker = null
        route = null
        lastProgress = null
        plan = null
        pendingSpeech = null
        voice.clear()
        main.removeCallbacks(silentDone)
        main.removeCallbacks(watchdog)
        watchdogId = null
        tts?.stop()
        abandonFocus()
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
        tracker = NavigationTracker(r, request?.mode ?: com.backpack.app.core.TravelMode.DRIVING, Prefs.useMetric(c), Prefs.voiceShort(c),
            com.backpack.app.core.VoiceText.forLanguage(Prefs.voiceLanguage(c)))
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
        t.callShort = inCall()
        val p = t.update(fix)
        lastProgress = p
        lastSpeedMps = fix.speedMps?.toDouble()
        p.speech?.let { speak(it) }
        pump() // a queued heads-up may fit in now
        for (l in listeners.toList()) l.onProgress(p, r)
        // 1.3.0: sight heads-ups with the navigation itself (screen off too).
        app?.let { com.backpack.app.tourist.SightHeadsUp.onProgress(it, p, r) }

        if (p.arrived) {
            // Freeze on "Arrived" for the last 4 s instead of flickering back.
            arrived = true
            speak(words().arrived())
            for (l in listeners.toList()) l.onArrived()
            main.postDelayed({ if (isActive && route === r) stop() }, 4000L)
            return
        }
        if (p.offRoute) maybeReroute(fix.latLon)
    }

    private fun maybeReroute(current: LatLon) {
        val now = System.currentTimeMillis()
        // 1.3.0: 8 s between recalculations (was 15 s).
        if (rerouting || now - lastRerouteAtMs < REROUTE_GAP_MS) return
        val req = request ?: return
        val pl = planner ?: return
        lastRerouteAtMs = now
        rerouting = true
        for (l in listeners.toList()) l.onRerouting()
        // Same request, same rules (incl. Balanced toll choice), new start,
        // minus the stops already visited.
        val passed = (tracker?.passedStops() ?: 0).coerceAtMost(req.waypoints.size)
        val newReq = req.copy(from = current, waypoints = req.waypoints.drop(passed))
        // One quick request (no alternatives), same toll choice as the route followed.
        val hasTolls = (route?.tollDistanceMeters ?: 0.0) > 0.0
        pl.reroute(newReq, hasTolls) plan@{ newPlan, error ->
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
            speak(words().recalculated())
            for (l in listeners.toList()) l.onRerouted(newPlan)
            LocationHub.lastGoodFix?.let { onFix(it) }
        }
    }

    // ---------- Voice ----------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** The voice engine could not start: info shows silently. */
    private var ttsFailed = false
    private var pendingSpeech: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private fun initTts(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsFailed = !ttsReady
            if (ttsFailed) main.post { pump() }
            if (ttsReady) {
                // 1.3.0: the voice language from Settings (Spanish: a Latin American voice first).
                setVoiceLocale(Prefs.voiceLanguage(c))
                tts?.setSpeechRate(0.85f)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { said(utteranceId) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { said(utteranceId) } }
                    // Cut on purpose (an instruction, Stop reading): the queue already knows.
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {}
                })
                // Said while the voice was still starting up.
                main.post {
                    pendingSpeech?.let { pendingSpeech = null; if (isActive) speak(it) }
                    pump()
                }
            }
        }
    }

    private fun words() = com.backpack.app.core.VoiceText.forLanguage(app?.let { Prefs.voiceLanguage(it) })

    /** Language the voice is set to now. */
    private var voiceLang: String? = null

    /** Spanish: a Latin American voice when the phone has one (es-419, Mexico, US...), then any Spanish. */
    private fun setVoiceLocale(lang: String) {
        if (lang == voiceLang) return
        val t = tts ?: return
        val wanted = when (lang) {
            "es" -> listOf(Locale("es", "419"), Locale("es", "MX"), Locale("es", "US"), Locale("es", "CO"), Locale("es", "AR"), Locale("es", "CL"))
            "fr" -> listOf(Locale.FRANCE, Locale("fr", "CA"))
            "it" -> listOf(Locale.ITALY)
            "de" -> listOf(Locale.GERMANY, Locale("de", "AT"))
            else -> listOf(Locale.US, Locale.UK)
        }
        val chosen = try {
            wanted.firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_COUNTRY_AVAILABLE }
                ?: wanted.firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        } catch (e: Exception) { null }
        try { t.language = chosen ?: Locale.ENGLISH } catch (e: Exception) { }
        voiceLang = lang
    }

    /** Settings changed: the next words use the new language (instructions from the next route). */
    fun languageChanged() {
        val c = app ?: return
        if (ttsReady) setVoiceLocale(Prefs.voiceLanguage(c))
    }

    /** A navigation instruction: always first, cuts any info being said
     *  (which resumes afterwards from the start of its sentence). */
    fun speak(text: String) {
        // Settings > Navigation > Spoken instructions; 1.4.1: none in "Alerts only".
        if (app?.let { Prefs.voiceOn(it) } == false) return
        if (app?.let { Prefs.soundMode(it) } == Prefs.SoundMode.ALERTS) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        voice.navStarted()
        requestFocus()
        say(text, UTTERANCE_NAV)
    }

    /**
     * Speaks [text]; never leaves the queue waiting: a refused call counts
     * as said at once, and an utterance that never reports back is taken as
     * said after a while (the voice engine may restart).
     */
    private fun say(text: String, kind: String, lang: String? = null) {
        // A reading in another language (no article in yours): that voice for it only.
        app?.let { c -> setVoiceLocale(lang ?: Prefs.voiceLanguage(c)) }
        main.removeCallbacks(watchdog)
        // Own id per utterance: a late report of a cut one is never taken for this one.
        val id = "$kind#${++utteranceSeq}"
        watchdogId = id
        // 1.4.0: during a phone call, 20% quieter.
        val params = if (inCall()) android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.8f) } else null
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) ?: TextToSpeech.ERROR
        if (r == TextToSpeech.ERROR) { main.post { said(id) }; return }
        main.postDelayed(watchdog, 20_000L + text.length * 120L)
    }

    private var utteranceSeq = 0
    /** The utterance being said (the only one whose report counts). */
    private var watchdogId: String? = null
    private val watchdog = Runnable { watchdogId?.let { said(it) } }

    // ---------- 1.2.1 info voice: sight heads-ups and readings, queued ----------

    private val voice = com.backpack.app.core.VoiceQueue()
    private var lastSpeedMps: Double? = null
    private const val UTTERANCE_NAV = "nav"
    private const val UTTERANCE_INFO = "info"
    /** Voice off: a heads-up still shows its message, for this long. */
    private const val SILENT_HEADS_UP_MS = 8000L

    /**
     * 1.4.1: a danger warning (danger zone...): said in every sound mode,
     * with or without navigation, before any other info, never cutting an
     * instruction. Dropped when it could not be said within [validMs].
     */
    fun speakDanger(c: Context, text: String, validMs: Long = 60_000L) {
        if (app == null) app = c.applicationContext
        initTts(c.applicationContext)
        val until = System.currentTimeMillis() + validMs
        voice.add(com.backpack.app.core.VoiceQueue.Item(com.backpack.app.core.VoiceQueue.Kind.DANGER,
            { text }, { System.currentTimeMillis() < until }))
        pump()
    }

    /** 1.4.1: the sound button changed the mode: below "Full" no readings go on. */
    fun soundModeChanged() {
        val c = app ?: return
        if (Prefs.soundMode(c) == Prefs.SoundMode.FULL) return
        if (voice.dropHeadsUps()) { tts?.stop(); main.removeCallbacks(watchdog); watchdogId = null }
        stopReading()
    }

    /** Queues a heads-up or a reading (said between instructions, one at a time). */
    fun speakInfo(item: com.backpack.app.core.VoiceQueue.Item, c: Context? = null) {
        // 1.4.1: from the map screen also without navigation (the voice starts here then).
        if (c != null) { if (app == null) app = c.applicationContext; initTts(c.applicationContext) }
        voice.add(item)
        pump()
    }

    fun pendingInfo(kind: com.backpack.app.core.VoiceQueue.Kind): Int = voice.pendingCount(kind)

    /** Stop reading: drops the reading (being said and queued); heads-ups stay. */
    fun stopReading() {
        if (voice.dropReadings()) { tts?.stop(); main.removeCallbacks(watchdog); watchdogId = null }
        pump()
        if (!voice.navSpeaking && !voice.infoSpeaking) abandonFocus()
    }

    private val silentDone = Runnable { voice.sentenceDone(); pump() }

    private fun said(utteranceId: String?) {
        // Only the latest utterance counts (a cut one may still report back late).
        if (utteranceId == null || utteranceId != watchdogId) return
        main.removeCallbacks(watchdog)
        watchdogId = null
        when (utteranceId.substringBefore('#')) {
            UTTERANCE_NAV -> voice.navDone(System.currentTimeMillis())
            UTTERANCE_INFO -> voice.sentenceDone()
        }
        pump()
        if (!voice.navSpeaking && !voice.infoSpeaking) abandonFocus()
    }

    /** Seconds to the next maneuver at your speed (route average when slow / unknown). */
    private fun secondsToNext(): Double? {
        if (!isActive) return null
        val p = lastProgress ?: return null
        val r = route
        val avg = if (r != null && r.durationSeconds > 60.0) r.distanceMeters / r.durationSeconds else 10.0
        val v = lastSpeedMps?.takeIf { it > 1.0 } ?: avg
        return p.distanceToNextM / v.coerceAtLeast(0.5)
    }

    private fun pump() {
        val voiceOn = app?.let { Prefs.voiceOn(it) } != false
        if (tts != null && !ttsReady && !ttsFailed) return // waits for the voice to start
        val s = voice.next(System.currentTimeMillis(), secondsToNext())
        if (s !is com.backpack.app.core.VoiceQueue.Say.Sentence) return
        // 1.4.1: while navigating, readings only in the "Full" sound mode (Read aloud outside navigation works).
        if (s.item.kind == com.backpack.app.core.VoiceQueue.Kind.READING && isActive &&
            app?.let { Prefs.soundMode(it) } != Prefs.SoundMode.FULL) { voice.dropReadings(); pump(); return }
        // 1.4.1: dangers are always said (also in a call, also with instructions off).
        val danger = s.item.kind == com.backpack.app.core.VoiceQueue.Kind.DANGER
        // 1.4.0: in a phone call only instructions are said; a sight tip shows its message silently.
        if (!ttsReady || (!danger && (!voiceOn || inCall()))) {
            // Readings need the voice; a heads-up still shows its message for a while.
            if (s.item.kind == com.backpack.app.core.VoiceQueue.Kind.READING) { voice.dropReadings(); pump(); return }
            main.removeCallbacks(silentDone)
            main.postDelayed(silentDone, SILENT_HEADS_UP_MS / s.item.sentences.size.coerceAtLeast(1))
            return
        }
        requestFocus()
        say(s.text, UTTERANCE_INFO, s.item.lang)
    }

    /** 1.4.0: a phone (or VoIP) call is going on. No permission needed. */
    fun inCall(): Boolean {
        val c = app ?: return false
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
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
