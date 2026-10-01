package com.backpack.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.View
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.IOException

/**
 * Step 9 checkpoint -- built in one overnight pass, NOT tested live:
 *
 * 1. UI redesign: no button boxes anywhere. Every control is a borderless
 *    icon floating over the map; ETA/distance is shadowed text with no
 *    background, in the position real nav apps put it.
 * 2. Speed limit indicator (driving mode only): polls OpenStreetMap's
 *    Overpass API for the maxspeed tag on the nearest road to your live
 *    GPS position, compares to your actual speed, shows a road-sign-style
 *    circle whose border color/blink/alarm follow the rules discussed.
 *    Coverage depends entirely on how well-tagged the local roads are in
 *    OSM -- expect gaps on smaller village roads.
 * 3. Balanced driving logic: when mode=Driving and preference=Balanced,
 *    requests both a tolls-allowed and a tolls-avoided route, estimates
 *    the toll cost (~EUR0.12/km on the toll-road portion -- a labeled
 *    estimate, not a real price feed, since no free French toll-price API
 *    exists), and picks whichever route is worth more given your current
 *    "value of time" mode (Weekend/Work/Emergency, cycled via one icon).
 *
 * Every piece of this is a first pass. Thresholds, the EUR/km toll
 * estimate, and the exact speed-limit color bands are reasonable
 * interpretations of the brief, not verified against real-world driving
 * yet -- expect to tune all of these once actually tested.
 */
class MainActivity : AppCompatActivity() {

    private enum class TravelMode(val profile: String, val iconRes: Int) {
        WALKING("foot-walking", R.drawable.ic_mode_walking),
        CYCLING("cycling-regular", R.drawable.ic_mode_cycling),
        DRIVING("driving-car", R.drawable.ic_mode_driving)
    }

    private enum class RoutePreference(val param: String, val iconRes: Int) {
        FASTEST("fastest", R.drawable.ic_pref_fastest),
        SHORTEST("shortest", R.drawable.ic_pref_shortest),
        RECOMMENDED("recommended", R.drawable.ic_pref_balanced)
    }

    /** Icons only now -- actual EUR/minute values come from settings,
     *  loaded via loadSettings() and currentValuePerMinute(). */
    private enum class UrgencyMode(val iconRes: Int) {
        WEEKEND(R.drawable.ic_urgency_weekend),
        WORK(R.drawable.ic_urgency_work),
        EMERGENCY(R.drawable.ic_urgency_emergency)
    }

    private lateinit var map: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var statusStrip: TextView
    private lateinit var navDistanceText: TextView
    private lateinit var navInstructionIcon: android.widget.ImageView
    private lateinit var navInstructionBar: View
    private lateinit var routeInfo: View
    private lateinit var routeInfoDist: TextView
    private lateinit var routeInfoEta: TextView
    private lateinit var routeInfoCost: TextView
    private lateinit var tollToggle: ImageButton
    private lateinit var urgencyButton: ImageButton
    private lateinit var speedSign: FrameLayout
    private lateinit var speedSignText: TextView
    private lateinit var messageBubble: TextView
    private lateinit var errorBubble: TextView
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val orsApiKey = "REMOVED"

    // TODO: paste your second account's key here once created. Automatic
    // failover only kicks in once this is a real key, not this placeholder.
    private val orsApiKeySecondary = "REMOVED"

    private fun hasSecondaryKey(): Boolean = orsApiKeySecondary != "PASTE_SECOND_ORS_API_KEY_HERE"

    private fun activeApiKey(useSecondary: Boolean): String =
        if (useSecondary) orsApiKeySecondary else orsApiKey

    private val fallbackLocation = GeoPoint(60.3913, 5.3221)

    private var currentMode = TravelMode.WALKING
    private var currentPreference = RoutePreference.RECOMMENDED
    private var avoidTolls = false
    private var urgencyMode = UrgencyMode.WEEKEND

    /** All toggle selections persist across app restarts now -- no more
     *  defaulting back to Walking/Balanced/Weekend/tolls-on every time
     *  the app launches. Stored by enum name (not ordinal), so this
     *  stays correct even if enum order ever changes later. */
    private fun loadToggleState() {
        val prefs = getSharedPreferences("backpack_toggle_state", MODE_PRIVATE)
        try {
            currentMode = TravelMode.valueOf(prefs.getString("mode", currentMode.name)!!)
        } catch (e: Exception) { }
        try {
            currentPreference = RoutePreference.valueOf(prefs.getString("preference", currentPreference.name)!!)
        } catch (e: Exception) { }
        try {
            urgencyMode = UrgencyMode.valueOf(prefs.getString("urgency", urgencyMode.name)!!)
        } catch (e: Exception) { }
        avoidTolls = prefs.getBoolean("avoid_tolls", avoidTolls)
        try {
            currentPoiCategory = PoiCategory.valueOf(prefs.getString("poi_category", currentPoiCategory.name)!!)
        } catch (e: Exception) { }

        // A configured "default on launch" in Settings takes priority
        // over session memory for POI specifically -- if you've set one,
        // you want the app to start there every time, not wherever you
        // last left it.
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val defaultPoi = settingsPrefs.getString(SettingsActivity.KEY_DEFAULT_POI, "NONE")
        if (defaultPoi != "NONE") {
            try {
                currentPoiCategory = PoiCategory.valueOf(defaultPoi!!)
            } catch (e: Exception) { }
        }
    }

    private fun saveToggleState() {
        getSharedPreferences("backpack_toggle_state", MODE_PRIVATE).edit()
            .putString("mode", currentMode.name)
            .putString("preference", currentPreference.name)
            .putString("urgency", urgencyMode.name)
            .putBoolean("avoid_tolls", avoidTolls)
            .putString("poi_category", currentPoiCategory.name)
            .apply()
    }

    // Loaded from SettingsActivity's SharedPreferences; replace the
    // hardcoded values the enum used to carry directly.
    private var valuePerMinuteWeekend = SettingsActivity.DEFAULT_VALUE_WEEKEND / 60.0
    private var valuePerMinuteWork = SettingsActivity.DEFAULT_VALUE_WORK / 60.0
    private var valuePerMinuteEmergency = SettingsActivity.DEFAULT_VALUE_EMERGENCY / 60.0
    private var tollRatePerKm = SettingsActivity.DEFAULT_TOLL_RATE
    private var useMetric = true

    private fun currentValuePerMinute(): Double = when (urgencyMode) {
        UrgencyMode.WEEKEND -> valuePerMinuteWeekend
        UrgencyMode.WORK -> valuePerMinuteWork
        UrgencyMode.EMERGENCY -> valuePerMinuteEmergency
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        useMetric = prefs.getBoolean(SettingsActivity.KEY_UNIT_METRIC, true)
        valuePerMinuteWeekend = prefs.getFloat(SettingsActivity.KEY_VALUE_WEEKEND, SettingsActivity.DEFAULT_VALUE_WEEKEND.toFloat()).toDouble() / 60.0
        valuePerMinuteWork = prefs.getFloat(SettingsActivity.KEY_VALUE_WORK, SettingsActivity.DEFAULT_VALUE_WORK.toFloat()).toDouble() / 60.0
        valuePerMinuteEmergency = prefs.getFloat(SettingsActivity.KEY_VALUE_EMERGENCY, SettingsActivity.DEFAULT_VALUE_EMERGENCY.toFloat()).toDouble() / 60.0
        tollRatePerKm = prefs.getFloat(SettingsActivity.KEY_TOLL_RATE, SettingsActivity.DEFAULT_TOLL_RATE.toFloat()).toDouble()
    }

    private var customStartPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var alternativeLines: MutableList<Polyline> = mutableListOf()
    private var lastComputedRoutes: List<RouteResult> = emptyList()
    private var selectedRouteIndex = 0

    // Pass-by stops: up to 2, added while waypoint mode is armed.
    private var waypoints: MutableList<GeoPoint> = mutableListOf()
    private var waypointMarkers: MutableList<Marker> = mutableListOf()
    private var waypointModeArmed = false
    private val maxWaypoints = 2

    // ---------- Navigation state ----------
    private var isNavigating = false
    private var activeRoute: RouteResult? = null
    private var currentStepIndex = 0
    private var lastRerouteAtMs = 0L
    private var lastRealLocationAtMs = 0L
    private var deadReckoningActive = false
    private var lastGoodFix: Location? = null
    private var isGpsDegraded = false
    private var gpsWarningMarker: Marker? = null
    private var anticipationWarnedForStep = -1
    private var isDisplayDimmed = false

    // ---------- Speed limit tracking ----------
    private var rawLocationManager: LocationManager? = null
    private var lastFix: Location? = null
    private var lastOverpassQueryAtMs = 0L
    private var lastKnownSpeedLimit: Int? = null
    private val speedHandler = Handler(Looper.getMainLooper())
    private var blinkOn = true
    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmAtMs = 0L

    private val rawLocationListener = LocationListener { location ->
        lastFix = location
        lastRealLocationAtMs = System.currentTimeMillis()

        if (isPlausibleFix(location)) {
            if (isGpsDegraded) clearGpsWarning()
            lastGoodFix = location

            if (currentMode == TravelMode.DRIVING) {
                maybeQuerySpeedLimit(location)
                updateSpeedSignDisplay(location)
            }
            if (isNavigating) {
                updateNavigationProgress(location)
            }
            queryPois()
        } else {
            // Bad fix -- likely signal loss/reacquisition noise (urban
            // canyon, multipath, brief GPS dropout). Deliberately does
            // NOT touch position, navigation progress, or off-route
            // detection -- all of those keep using lastGoodFix until a
            // plausible reading comes back, instead of jumping to noise
            // and triggering a reroute against a wrong position.
            showGpsWarning()
        }
    }

    /** Rejects a location reading as noise if it's low-accuracy, or if
     *  it implies an impossible speed from the last known good fix
     *  (a classic GPS artifact right after signal loss/reacquisition --
     *  position "teleports" briefly before settling back down). 55 m/s
     *  (~200 km/h) is generous enough for genuine fast driving while
     *  still catching real teleport-style jumps. */
    private fun isPlausibleFix(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > 50f) return false

        val previous = lastGoodFix ?: return true
        val elapsedSeconds = (location.time - previous.time) / 1000.0
        if (elapsedSeconds <= 0) return true

        val distanceMeters = previous.distanceTo(location)
        val impliedSpeed = distanceMeters / elapsedSeconds
        return impliedSpeed <= 55.0
    }

    private fun showGpsWarning() {
        isGpsDegraded = true
        wakeDisplay()
        // Arrow stays visible now (not fully hidden) -- the warning icon
        // superimposes over it instead, so you still see your last
        // known heading underneath.
        val fix = lastGoodFix ?: return
        val point = GeoPoint(fix.latitude, fix.longitude)

        if (gpsWarningMarker == null) {
            val marker = Marker(map)
            marker.setInfoWindow(null)
            ContextCompat.getDrawable(this, R.drawable.ic_gps_warning)?.let { marker.icon = it }
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.alpha = 0.75f
            map.overlays.add(marker)
            gpsWarningMarker = marker
        }
        gpsWarningMarker?.position = point
        map.invalidate()
        showMessage("GPS signal weak -- holding last known position")
    }

    private fun clearGpsWarning() {
        isGpsDegraded = false
        // Arrow was never hidden, nothing to restore here.
        gpsWarningMarker?.let { map.overlays.remove(it) }
        gpsWarningMarker = null
        map.invalidate()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            myLocationOverlay.enableMyLocation()
            startRawLocationUpdates()
        } else {
            showError("Location permission denied -- Center button won't work")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        loadToggleState()
        Configuration.getInstance().load(
            applicationContext,
            applicationContext.getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        // Tiles were cached indefinitely with no expiration before this --
        // once downloaded, a tile would never be re-fetched even if OSM's
        // own data changed, which explains outdated map details. Forces a
        // refresh check after 7 days instead.
        Configuration.getInstance().expirationOverrideDuration = 7L * 24 * 60 * 60 * 1000

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadSettings()

        map = findViewById(R.id.map)
        statusStrip = findViewById(R.id.status_strip)
        navDistanceText = findViewById(R.id.nav_distance_text)
        navInstructionIcon = findViewById(R.id.nav_instruction_icon)
        navInstructionBar = findViewById(R.id.nav_instruction_bar)
        routeInfo = findViewById(R.id.route_info)
        routeInfoDist = findViewById(R.id.route_info_dist)
        routeInfoEta = findViewById(R.id.route_info_eta)
        routeInfoCost = findViewById(R.id.route_info_cost)
        tollToggle = findViewById(R.id.toll_toggle)
        urgencyButton = findViewById(R.id.urgency_button)
        speedSign = findViewById(R.id.speed_sign)
        speedSignText = findViewById(R.id.speed_sign_text)
        messageBubble = findViewById(R.id.message_bubble)
        errorBubble = findViewById(R.id.error_bubble)
        messageBubble.post {
            val params = messageBubble.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = (resources.displayMetrics.heightPixels * 0.25).toInt()
            messageBubble.layoutParams = params
        }
        errorBubble.post {
            val params = errorBubble.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = (resources.displayMetrics.heightPixels * 0.40).toInt()
            errorBubble.layoutParams = params
        }

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.setTilesScaledToDpi(true)
        applyDisplayMode()
        map.controller.setZoom(10.0)
        map.controller.setCenter(resolveInitialCenter())

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        updateLocationMarkerIcon()
        map.overlays.add(myLocationOverlay)

        if (hasLocationPermission()) {
            myLocationOverlay.enableMyLocation()
            startRawLocationUpdates()
        }

        findViewById<ImageButton>(R.id.locate_button).apply {
            setIconActive(this, false)
            setOnClickListener {
                centerOnMyLocation()
            }
        }

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        setupAddressSearch()
        setupWaypointToggle()
        setupNavigationButton()
        setupPoiToggle()
        initTextToSpeech()

        setupModeButtons()
        setupPreferenceRow()
        setupTollToggle()
        setupUrgencyButton()
        refreshModeButtonHighlight()
        refreshPreferenceHighlight()
        syncDrivingModeUI()

        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                handleDestinationTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                handleStartOverride(p)
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(tapReceiver))
    }

    // ---------- Address search ----------

    private fun setupAddressSearch() {
        val searchButton = findViewById<ImageButton>(R.id.search_button)
        val searchInput = findViewById<android.widget.EditText>(R.id.search_input)

        // Both icons start inactive-tinted; they only flash active
        // while actually being used (typing, centering).
        setIconActive(searchButton, false)

        searchButton.setOnClickListener {
            searchInput.requestFocus()
            searchInput.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        searchInput.setOnFocusChangeListener { _, hasFocus ->
            setIconActive(searchButton, hasFocus)
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val text = searchInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    showMessage("Searching...")
                    geocodeAddress(text)
                }
                searchInput.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    /** Reuses the same ORS API key for their free geocoding endpoint --
     *  one less service/key to manage. Takes the top result as-is; no
     *  disambiguation UI yet if the address is ambiguous. */
    private fun geocodeAddress(text: String, useSecondary: Boolean = false) {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://api.openrouteservice.org/geocode/search?api_key=${activeApiKey(useSecondary)}&text=$encoded&size=1"
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError("Search failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 403 && !useSecondary && hasSecondaryKey()) {
                    geocodeAddress(text, useSecondary = true)
                    return
                }
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    runOnUiThread { showError("Search failed") }
                    return
                }
                val point = try {
                    val json = JSONObject(body)
                    val features = json.getJSONArray("features")
                    if (features.length() == 0) null else {
                        val coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                        GeoPoint(coords.getDouble(1), coords.getDouble(0))
                    }
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (point == null) {
                        showError("Address not found")
                    } else {
                        map.controller.animateTo(point)
                        map.controller.setZoom(14.0)
                        handleDestinationTap(point)
                    }
                }
            }
        })
    }

    // ---------- Pass-by stops (waypoints) ----------

    private fun setupWaypointToggle() {
        val button = findViewById<ImageButton>(R.id.waypoint_button)
        button.setOnClickListener {
            waypointModeArmed = !waypointModeArmed
            setIconActive(button, waypointModeArmed)
            showMessage(if (waypointModeArmed) "Tap the map to add a stop (max $maxWaypoints)" else "Stop mode off")
        }
        setIconActive(button, false)
    }

    private fun addWaypoint(point: GeoPoint) {
        if (waypoints.size >= maxWaypoints) {
            showMessage("Maximum $maxWaypoints stops reached")
            return
        }
        waypoints.add(point)
        waypointMarkers.add(addMarker(point, "Stop ${waypoints.size}", R.drawable.ic_marker_waypoint))

        if (waypoints.size >= maxWaypoints) {
            // Auto-exit stop mode: the natural next tap should set the
            // destination, not attempt a rejected 3rd stop.
            waypointModeArmed = false
            findViewById<ImageButton>(R.id.waypoint_button).let { setIconActive(it, false) }
            showMessage("Stop added. Tap the map to set your destination.")
        } else {
            showMessage("Stop added. Tap for one more, or set your destination.")
        }

        val start = resolveStartPoint()
        val end = endPoint
        if (start != null && end != null) {
            showMessage("Finding route...")
            requestBestRoute(start, end)
        }
    }

    // ---------- Mode selection ----------

    /** Real dark map tiles need a proper dark basemap provider, which
     *  isn't wired in. Both filter attempts (desaturate, invert) looked
     *  bad, so tile color filtering is dropped entirely -- the map stays
     *  in its normal daylight style always. Day/Night/Auto in Settings
     *  now only controls OUR OWN UI colors (icon backdrops, text), not
     *  the map itself, until a real dark tile source is integrated. */
    private fun applyDisplayMode() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getString(SettingsActivity.KEY_DISPLAY_MODE, SettingsActivity.DISPLAY_MODE_AUTO)
        val systemIsNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val useNightUi = when (mode) {
            SettingsActivity.DISPLAY_MODE_NIGHT -> true
            SettingsActivity.DISPLAY_MODE_DAY -> false
            else -> systemIsNight
        }
        // useNightUi is kept for other display-mode-sensitive elements
        // added later; the bubbles themselves are now a fixed dark,
        // high-contrast scheme regardless of day/night, since faint
        // versions in both modes were hard to read.
        val bubbleBg = "#99555555"
        val bubbleText = "#FFFFFF"
        (messageBubble.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(android.graphics.Color.parseColor(bubbleBg))
        messageBubble.setTextColor(android.graphics.Color.parseColor(bubbleText))
        (errorBubble.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(android.graphics.Color.parseColor(bubbleBg))
        errorBubble.setTextColor(android.graphics.Color.parseColor(bubbleText))
    }

    private var bubbleFadeOutRunnable: Runnable? = null

    /** The single place any status, confirmation, or error message shows.
     *  Fades in quickly, holds for ~5 seconds, fades out over ~2 --
     *  roughly 7 seconds on screen, within the 5-10s asked for. */
    private fun showMessage(text: String) {
        bubbleFadeOutRunnable?.let { speedHandler.removeCallbacks(it) }
        messageBubble.text = text
        messageBubble.visibility = android.view.View.VISIBLE
        messageBubble.animate().cancel()
        messageBubble.alpha = 0f
        messageBubble.animate().alpha(1f).setDuration(200).start()

        val fadeOut = Runnable {
            messageBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                messageBubble.visibility = android.view.View.INVISIBLE
            }.start()
        }
        bubbleFadeOutRunnable = fadeOut
        speedHandler.postDelayed(fadeOut, 3000L)
    }

    private var errorFadeOutRunnable: Runnable? = null

    /** Genuine failures (route errors, search failures, permission
     *  problems) -- kept visually separate from routine confirmation
     *  messages, positioned higher up the screen. */
    private fun showError(text: String) {
        errorFadeOutRunnable?.let { speedHandler.removeCallbacks(it) }
        errorBubble.text = text
        errorBubble.visibility = android.view.View.VISIBLE
        errorBubble.animate().cancel()
        errorBubble.alpha = 0f
        errorBubble.animate().alpha(1f).setDuration(200).start()

        val fadeOut = Runnable {
            errorBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                errorBubble.visibility = android.view.View.INVISIBLE
            }.start()
        }
        errorFadeOutRunnable = fadeOut
        speedHandler.postDelayed(fadeOut, 4000L)
    }

    private val activeIconTint = android.graphics.Color.parseColor("#B15C52")
    private val inactiveIconTint = android.graphics.Color.parseColor("#7288A0")

    private fun setIconActive(button: ImageButton, active: Boolean) {
        val color = if (active) activeIconTint else inactiveIconTint
        button.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------- Navigation ----------

    private fun setupNavigationButton() {
        findViewById<ImageButton>(R.id.nav_go_button).setOnClickListener {
            if (isNavigating) {
                clearRoute() // stop + clear together, not two separate steps
                showMessage("Navigation stopped, route cleared")
            } else {
                startNavigation()
            }
        }
        findViewById<ImageButton>(R.id.clear_route_button).setOnClickListener {
            clearRoute()
            showMessage("Route cleared")
        }
    }

    private fun startNavigation() {
        val route = lastComputedRoutes.firstOrNull()
        if (route == null || route.points.isEmpty()) {
            showError("No route to navigate yet")
            return
        }
        isNavigating = true
        activeRoute = route
        currentStepIndex = 0
        anticipationWarnedForStep = -1
        lastRerouteAtMs = System.currentTimeMillis()
        lastRealLocationAtMs = System.currentTimeMillis()
        deadReckoningActive = false
        speedHandler.postDelayed(deadReckoningRunnable, 1000L)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeDisplay()

        val goButton = findViewById<ImageButton>(R.id.nav_go_button)
        goButton.setImageResource(R.drawable.ic_navigation_stop)

        // Declutter during navigation -- selection controls aren't useful
        // mid-trip, and this is closer to how a real nav app looks once
        // you're actually moving, not still planning.
        setNavigationDeclutter(true)

        findViewById<View>(R.id.nav_instruction_bar).visibility = android.view.View.VISIBLE
        val firstStep = route.steps.firstOrNull()
        if (firstStep != null) {
            val display = compactInstructionText(firstStep, null)
            navDistanceText.text = display.distance
            statusStrip.text = display.instruction
            updateNavIcon(firstStep)
            speakInstruction(firstStep)
        } else {
            navDistanceText.text = ""
            statusStrip.text = "Head to your destination"
        }

        map.controller.setZoom(18.0)
        myLocationOverlay.myLocation?.let { map.controller.animateTo(it) }
    }

    private fun stopNavigation() {
        isNavigating = false
        activeRoute = null
        currentStepIndex = 0
        deadReckoningActive = false
        speedHandler.removeCallbacks(deadReckoningRunnable)

        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        isDisplayDimmed = false

        val goButton = findViewById<ImageButton>(R.id.nav_go_button)
        goButton.setImageResource(R.drawable.ic_navigation_go)

        setNavigationDeclutter(false)
        statusStrip.text = ""
        navDistanceText.text = ""
        navInstructionIcon.visibility = android.view.View.GONE
        findViewById<View>(R.id.nav_instruction_bar).visibility = android.view.View.GONE
        map.mapOrientation = 0f
    }

    /** Restores full screen brightness immediately -- called on any
     *  anticipatory warning, an alert (speeding, GPS loss), or right
     *  after each turn, so you're never looking at a dimmed screen when
     *  something actually needs attention. */
    private fun wakeDisplay() {
        if (!isNavigating) return
        isDisplayDimmed = false
        val params = window.attributes
        params.screenBrightness = 1.0f
        window.attributes = params
    }

    /** Dims to 50% brightness during long boring stretches (60+ seconds
     *  to the next turn at current speed) to ease glare/save battery,
     *  and only while nothing alert-worthy is happening. Never dims
     *  while GPS is degraded or the speed alarm is active -- those need
     *  full visibility, not a faded screen. */
    private fun dimDisplayIfIdle(distanceToNextTurn: Double, location: Location) {
        if (!isNavigating || isDisplayDimmed) return
        if (isGpsDegraded) return
        if (isBlinking) return // speed-limit alarm currently active

        val hasReliableSpeed = location.hasSpeed() && location.speed > 1.0
        val etaSeconds = if (hasReliableSpeed) distanceToNextTurn / location.speed else Double.MAX_VALUE
        if (etaSeconds > 60.0) {
            isDisplayDimmed = true
            val params = window.attributes
            params.screenBrightness = 0.5f
            window.attributes = params
        }
    }

    private fun setNavigationDeclutter(navigating: Boolean) {
        val visibility = if (navigating) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<View>(R.id.mode_row).visibility = visibility
        findViewById<View>(R.id.waypoint_button).visibility = visibility
        findViewById<View>(R.id.search_button).visibility = visibility
        findViewById<View>(R.id.search_input).visibility = visibility
        // preference_row stays visible during navigation on purpose --
        // it's the mechanism for switching routes mid-trip, per explicit
        // request. Everything else above is pure planning UI that's
        // genuinely not useful once you're moving.

        // Toll is a separate overlay badge now (moved out of mode_row a
        // couple rounds back), so it needs its own explicit hide here --
        // hiding mode_row no longer covers it. Restored correctly on
        // stop based on current mode, same as before.
        findViewById<View>(R.id.toll_toggle).visibility =
            if (navigating || currentMode != TravelMode.DRIVING) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Called on every location update while navigating: advances to the
     *  next instruction as you pass each maneuver point, keeps the map
     *  centered and heading-up on your position, and triggers a basic
     *  reroute if you've drifted far enough from the route. */
    private fun updateNavigationProgress(location: Location) {
        val route = activeRoute ?: return
        val current = GeoPoint(location.latitude, location.longitude)

        // Follow position, heading-up orientation.
        map.controller.animateTo(current)
        if (location.hasBearing()) {
            map.mapOrientation = -location.bearing
        }

        // Speed-adaptive zoom (Driving only): zoom out as speed
        // increases, so highway navigation doesn't feel like the screen
        // jumps in big steps between GPS updates. 18 at a standstill,
        // down to 14 at ~120 km/h, clamped in between.
        if (currentMode == TravelMode.DRIVING && location.hasSpeed()) {
            val speedKmh = location.speed * 3.6
            val zoom = (18.0 - speedKmh / 30.0).coerceIn(14.0, 18.0)
            map.controller.setZoom(zoom)
        }

        // Advance the current step once we're close to its maneuver point.
        val steps = route.steps
        if (currentStepIndex < steps.size) {
            val step = steps[currentStepIndex]
            val targetIndex = step.pointIndex.coerceIn(0, route.points.size - 1)
            val target = route.points[targetIndex]
            val distanceToStep = current.distanceToAsDouble(target)

            if (distanceToStep < 25.0) {
                currentStepIndex++
                anticipationWarnedForStep = -1
                if (currentStepIndex < steps.size) {
                    val nextStep = steps[currentStepIndex]
                    val display = compactInstructionText(nextStep, null)
                    navDistanceText.text = display.distance
                    statusStrip.text = display.instruction
                    updateNavIcon(nextStep)
                    updateNavBarProximityColor(Double.MAX_VALUE)
                    speakInstruction(nextStep)
                    wakeDisplay()
                } else {
                    navDistanceText.text = ""
                    statusStrip.text = "Arrived"
                    navInstructionIcon.setImageResource(R.drawable.ic_nav_arrive)
                    navInstructionIcon.visibility = android.view.View.VISIBLE
                    speakText("Arrived")
                    wakeDisplay()
                    speedHandler.postDelayed({ if (isNavigating) stopNavigation() }, 4000L)
                }
            } else {
                val display = compactInstructionText(step, distanceToStep)
                navDistanceText.text = display.distance
                statusStrip.text = display.instruction
                updateNavIcon(step)
                updateNavBarProximityColor(distanceToStep)

                // Anticipatory warning: vocal instructions only fire once
                // you reach a maneuver, which can be too late at highway
                // speed. This fires once per step, ~30 seconds before
                // estimated arrival at the turn, using current speed.
                if (anticipationWarnedForStep != currentStepIndex && location.hasSpeed() && location.speed > 1.0) {
                    val etaSeconds = distanceToStep / location.speed
                    if (etaSeconds in 0.0..30.0) {
                        anticipationWarnedForStep = currentStepIndex
                        speakText("Next change in 30 seconds")
                        showMessage("Next change in 30 seconds")
                        wakeDisplay()
                    }
                }

                dimDisplayIfIdle(distanceToStep, location)
            }
        }

        // Basic off-route check: distance from current position to the
        // nearest point on the route. A simple linear scan, fine for
        // routes of reasonable length. Throttled to avoid spamming
        // reroute requests on noisy GPS.
        val nearestDistance = route.points.minOfOrNull { it.distanceToAsDouble(current) } ?: 0.0
        if (nearestDistance > 50.0) {
            val now = System.currentTimeMillis()
            if (now - lastRerouteAtMs > 15000L) {
                lastRerouteAtMs = now
                val destination = endPoint
                if (destination != null) {
                    showMessage("Off route, recalculating...")
                    // Reverted to requestBestRoute -- using the same
                    // method as the original calculation (including
                    // Balanced mode's toll comparison when that's what
                    // you had selected) matters more than the marginal
                    // speed gain from skipping it, especially since that
                    // shortcut didn't meaningfully fix the slowness.
                    requestBestRoute(current, destination)
                }
            }
        }
    }

    /** Maps ORS's instruction text to a turn icon via keyword matching --
     *  ORS doesn't expose a clean maneuver-type enum through this
     *  endpoint, so this is a pragmatic text-based v1, not a perfect
     *  parse of every phrasing ORS might use. */
    private fun navIconFor(instruction: String): Int {
        val text = instruction.lowercase()
        return when {
            "roundabout" in text -> R.drawable.ic_nav_roundabout
            "u-turn" in text -> R.drawable.ic_nav_uturn
            "sharp left" in text -> R.drawable.ic_nav_sharp_left
            "sharp right" in text -> R.drawable.ic_nav_sharp_right
            "left" in text -> R.drawable.ic_nav_left
            "right" in text -> R.drawable.ic_nav_right
            "arrive" in text || "destination" in text -> R.drawable.ic_nav_arrive
            else -> R.drawable.ic_nav_straight
        }
    }

    /** Updates the instruction icon to match the given step. */
    private fun updateNavIcon(step: NavStep) {
        navInstructionIcon.setImageResource(navIconFor(step.instruction))
        navInstructionIcon.visibility = android.view.View.VISIBLE
    }

    /** Short display text: distance and the actual street name when ORS
     *  provided one, instead of the full verbose sentence. The turn
     *  direction itself is shown by the icon now, not embedded in text.
     *  Returns distance and street/instruction separately -- the nav bar
     *  displays them as distinct elements (icon, then distance, then
     *  indication), not one combined line. */
    private data class NavDisplayText(val distance: String, val instruction: String)

    private fun compactInstructionText(step: NavStep, overrideDistance: Double?): NavDisplayText {
        val distanceMeters = overrideDistance ?: step.distanceMeters
        val distanceText = if (distanceMeters >= 1000) {
            "%.1fkm".format(distanceMeters / 1000.0)
        } else {
            "${distanceMeters.toInt()}m"
        }
        val street = step.streetName.ifBlank { null } ?: step.instruction
        return NavDisplayText(distanceText, street)
    }

    // ---------- Voice guidance ----------

    private var textToSpeech: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    private fun initTextToSpeech() {
        textToSpeech = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.language = java.util.Locale.getDefault()
                textToSpeech?.setSpeechRate(0.85f)
            }
        }
    }

    /** Speaks the plain-language instruction (not the compact symbol
     *  text, which wouldn't make sense read aloud), only called once
     *  per step advance, not on every location update. */
    /** ORS's instruction text already naturally includes the street name
     *  (e.g. "Turn right onto Rue de la Paix") -- speak it as-is. */
    private fun speakInstruction(step: NavStep) {
        speakText(step.instruction)
    }

    private fun speakText(text: String) {
        if (!ttsReady) return
        textToSpeech?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /** Border shifts blue (far from the next turn) toward red (close to
     *  it) -- a glance-able urgency cue, same visual language as the
     *  speed sign's color coding. */
    private fun updateNavBarProximityColor(distanceToTurn: Double) {
        val background = navInstructionBar.background?.mutate() as? android.graphics.drawable.GradientDrawable ?: return
        // Smooth 4-stop gradient: blue (far) -> green -> yellow -> red
        // (close), not discrete jumps. Fully blue at 250m+, fully red
        // at 20m or less.
        val farDistance = 250.0
        val nearDistance = 20.0
        val fraction = ((farDistance - distanceToTurn) / (farDistance - nearDistance)).coerceIn(0.0, 1.0)

        val stops = arrayOf(
            intArrayOf(0x2B, 0x6C, 0xB0), // blue
            intArrayOf(0x2E, 0x7D, 0x32), // green
            intArrayOf(0xF1, 0xC4, 0x0F), // yellow
            intArrayOf(0xC0, 0x39, 0x2B)  // red
        )
        val segment = (fraction * (stops.size - 1)).coerceIn(0.0, (stops.size - 1).toDouble())
        val lowerIndex = segment.toInt().coerceIn(0, stops.size - 2)
        val localFraction = segment - lowerIndex
        val from = stops[lowerIndex]
        val to = stops[lowerIndex + 1]

        val r = (from[0] + (to[0] - from[0]) * localFraction).toInt()
        val g = (from[1] + (to[1] - from[1]) * localFraction).toInt()
        val b = (from[2] + (to[2] - from[2]) * localFraction).toInt()

        background.setStroke(dp(5), android.graphics.Color.rgb(r, g, b))
    }

    /** During a GPS gap, keeps the map camera panning forward using the
     *  last known speed and bearing, so the view doesn't just freeze --
     *  purely visual continuity. Deliberately does NOT feed into step
     *  advancement or off-route detection, which stay gated on real GPS
     *  fixes only, since acting on projected/guessed position for those
     *  would risk a wrong navigation decision. Gives up after 15s of no
     *  real fix, since projecting further than that gets unreliable. */
    private val deadReckoningRunnable = object : Runnable {
        override fun run() {
            if (!isNavigating) return
            val gapMs = System.currentTimeMillis() - lastRealLocationAtMs
            val fix = lastGoodFix

            if (gapMs in 3000..15000 && fix != null && fix.hasSpeed() && fix.hasBearing() && fix.speed > 0.5f) {
                deadReckoningActive = true
                val elapsedSeconds = gapMs / 1000.0
                val distanceMeters = fix.speed * elapsedSeconds
                val projected = projectPoint(GeoPoint(fix.latitude, fix.longitude), fix.bearing.toDouble(), distanceMeters)
                map.controller.animateTo(projected)
            } else if (gapMs > 15000 && deadReckoningActive) {
                deadReckoningActive = false
                showMessage("GPS signal lost")
            }

            speedHandler.postDelayed(this, 1000L)
        }
    }

    /** Simple flat-earth projection, fine for the short distances (a few
     *  hundred meters at most) this is ever used for. */
    private fun projectPoint(from: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val earthRadius = 6371000.0
        val bearingRad = Math.toRadians(bearingDegrees)
        val latRad = Math.toRadians(from.latitude)
        val lonRad = Math.toRadians(from.longitude)

        val newLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(distanceMeters / earthRadius) +
                Math.cos(latRad) * Math.sin(distanceMeters / earthRadius) * Math.cos(bearingRad)
        )
        val newLonRad = lonRad + Math.atan2(
            Math.sin(bearingRad) * Math.sin(distanceMeters / earthRadius) * Math.cos(latRad),
            Math.cos(distanceMeters / earthRadius) - Math.sin(latRad) * Math.sin(newLatRad)
        )
        return GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad))
    }

    // ---------- POI layer ----------

    private enum class PoiCategory(val overpassFilter: String, val iconRes: Int, val label: String) {
        NONE("", R.drawable.ic_poi_toggle, "Off"),
        FUEL("[\"amenity\"~\"fuel|charging_station\"]", R.drawable.ic_poi_fuel, "Fuel/Charging"),
        FOOD("[\"amenity\"~\"restaurant|cafe|fast_food\"]", R.drawable.ic_poi_food, "Food"),
        LODGING("[\"tourism\"~\"hotel|guest_house|motel\"]", R.drawable.ic_poi_lodging, "Lodging"),
        PARKING("[\"amenity\"=\"parking\"]", R.drawable.ic_poi_parking, "Parking"),
        TOILETS("[\"amenity\"=\"toilets\"]", R.drawable.ic_poi_toilets, "Toilets"),
        EMERGENCY("[\"amenity\"~\"hospital|police|fire_station\"]", R.drawable.ic_poi_emergency, "Emergency")
    }

    private var currentPoiCategory = PoiCategory.NONE
    private var poiRequestGeneration = 0
    private var poiMarkers: MutableList<Marker> = mutableListOf()
    private var lastPoiQueryAtMs = 0L
    private var lastPoiQueryCenter: GeoPoint? = null

    /** POI icons are multi-color (a colored pin shape with a distinct
     *  white symbol inside) -- unlike the other toggle buttons, tinting
     *  them with setIconActive flattens the whole icon to one solid
     *  color and erases the symbol that actually distinguishes fuel
     *  from food from emergency. So POI status (off/pending/found)
     *  colors the backdrop circle behind it instead, leaving the icon
     *  itself untouched and always readable. */
    private fun setPoiButtonStatus(button: ImageButton, found: Boolean?) {
        val backdrop = button.background?.mutate() as? android.graphics.drawable.GradientDrawable
        val color = when (found) {
            true -> "#B15C52"  // confirmed results nearby
            false -> "#7288A0" // selected but pending, or confirmed empty -- same inactive tone used everywhere else
            null -> "#99FFFFFF" // off -- the actual default backdrop color used everywhere else
        }
        backdrop?.setColor(android.graphics.Color.parseColor(color))

        if (found == null) {
            // Generic "off" icon is single-color, safe to tint like every
            // other toggle so it matches the inactive blueish-gray.
            setIconActive(button, false)
        } else {
            // Category icons are multi-color (pin shape + distinct
            // symbol) -- any tint flattens them. Explicitly clear any
            // leftover tint from a previous "off" state, since
            // ImageButton retains imageTintList across setImageResource
            // calls otherwise, which was the real cause of category
            // icons looking washed out/wrong-colored before.
            button.imageTintList = null
        }
    }

    private fun setupPoiToggle() {
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        button.setImageResource(currentPoiCategory.iconRes)
        setPoiButtonStatus(button, if (currentPoiCategory == PoiCategory.NONE) null else false)
        if (currentPoiCategory != PoiCategory.NONE) {
            queryPois(force = true)
        }
        button.setOnClickListener {
            val categories = PoiCategory.values()
            val nextIndex = (currentPoiCategory.ordinal + 1) % categories.size
            currentPoiCategory = categories[nextIndex]
            button.setImageResource(currentPoiCategory.iconRes)
            saveToggleState()

            if (currentPoiCategory == PoiCategory.NONE) {
                poiRequestGeneration++
                setPoiButtonStatus(button, null)
                clearPoiMarkers()
                showMessage("POI layer off")
            } else {
                setPoiButtonStatus(button, false) // gray while pending -- turns colored only if the query confirms results
                showMessage("Checking for ${currentPoiCategory.label}...")
                queryPois(force = true)
            }
        }
    }

    private fun clearPoiMarkers() {
        poiMarkers.forEach { map.overlays.remove(it) }
        poiMarkers.clear()
        map.invalidate()
    }

    /** Queries Overpass for the current category around the map's
     *  current center -- reuses the exact same API and throttling
     *  pattern already proven for speed limits. Re-queries when the map
     *  has moved more than ~1km from where it was last queried, so
     *  panning around doesn't spam requests for a still-relevant area. */
    private fun queryPois(force: Boolean = false) {
        if (currentPoiCategory == PoiCategory.NONE) return
        val centerRaw = map.mapCenter
        val center = GeoPoint(centerRaw.latitude, centerRaw.longitude)

        val now = System.currentTimeMillis()
        val movedFar = lastPoiQueryCenter?.let { it.distanceToAsDouble(center) > 1000.0 } ?: true
        if (!force && (now - lastPoiQueryAtMs < 20000L || !movedFar)) return
        lastPoiQueryAtMs = now
        lastPoiQueryCenter = center

        val category = currentPoiCategory
        poiRequestGeneration++
        val thisGeneration = poiRequestGeneration

        // Restrict to what's actually visible on screen, capped at a
        // 25km-radius equivalent even when zoomed way out -- smaller
        // search area means a faster Overpass response, and there's no
        // point returning POIs you can't currently see anyway.
        val box = map.boundingBox
        val maxSpanDegrees = 25000.0 / 111000.0 // ~25km in degrees latitude
        val latSpan = (box.latNorth - box.latSouth).coerceAtMost(maxSpanDegrees)
        val lonSpan = (box.lonEast - box.lonWest).coerceAtMost(maxSpanDegrees)
        val south = center.latitude - latSpan / 2
        val north = center.latitude + latSpan / 2
        val west = center.longitude - lonSpan / 2
        val east = center.longitude + lonSpan / 2

        val query = "[out:json][timeout:12];node($south,$west,$north,$east)${category.overpassFilter};out body 30;"
        fetchFromOverpass(query, useMirror = false) { body, errorText ->
            // Only the single most recent request is ever allowed to
            // render, full stop -- this is what actually fixes the
            // "categories mix" bug: a slow response for a category you
            // already switched away from can no longer overwrite
            // whatever the newer selection already showed, regardless
            // of whether that older request happened to succeed.
            if (thisGeneration != poiRequestGeneration) return@fetchFromOverpass

            if (body != null) {
                val results = try {
                    parsePoiResults(body)
                } catch (e: Exception) {
                    runOnUiThread {
                        if (thisGeneration == poiRequestGeneration) {
                            showError("Couldn't read POI response")
                            setPoiButtonStatus(findViewById(R.id.poi_toggle_button), false)
                        }
                    }
                    return@fetchFromOverpass
                }

                runOnUiThread {
                    if (thisGeneration != poiRequestGeneration) return@runOnUiThread
                    clearPoiMarkers()
                    for ((point, name) in results) {
                        val marker = Marker(map)
                        marker.position = point
                        marker.title = name
                        marker.setInfoWindow(null)
                        ContextCompat.getDrawable(this@MainActivity, category.iconRes)?.let { marker.icon = it }
                        map.overlays.add(marker)
                        poiMarkers.add(marker)
                    }
                    map.invalidate()

                    val button = findViewById<ImageButton>(R.id.poi_toggle_button)
                    if (results.isEmpty()) {
                        setPoiButtonStatus(button, false)
                        showMessage("No ${category.label} found nearby")
                    } else {
                        setPoiButtonStatus(button, true)
                        showMessage("${results.size} ${category.label} found")
                    }
                }
            } else {
                runOnUiThread {
                    if (thisGeneration == poiRequestGeneration) {
                        showError(errorText ?: "POI search failed")
                        setPoiButtonStatus(findViewById(R.id.poi_toggle_button), false)
                    }
                }
            }
        }
    }

    /** Shared Overpass fetch with automatic mirror fallback: transient
     *  failures (timeout, connection errors, 429/500/502/503/504/506 --
     *  all signs of the free public instance being overloaded, not a
     *  bug in the query itself) retry once against a second mirror
     *  before actually surfacing an error. */
    private fun fetchFromOverpass(query: String, useMirror: Boolean, callback: (body: String?, errorText: String?) -> Unit) {
        val host = if (useMirror) "https://overpass.kumi.systems/api/interpreter?data=" else "https://overpass-api.de/api/interpreter?data="
        val url = host + java.net.URLEncoder.encode(query, "UTF-8")

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Backpack-Android-App")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!useMirror) {
                    fetchFromOverpass(query, useMirror = true, callback = callback)
                } else {
                    callback(null, "POI search failed: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val transientCode = response.code in setOf(429, 500, 502, 503, 504, 506)
                if (!response.isSuccessful && transientCode && !useMirror) {
                    fetchFromOverpass(query, useMirror = true, callback = callback)
                    return
                }
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    callback(null, "POI search error (HTTP ${response.code})")
                    return
                }
                callback(body, null)
            }
        })
    }

    private fun parsePoiResults(responseBody: String): List<Pair<GeoPoint, String>> {
        val results = mutableListOf<Pair<GeoPoint, String>>()
        val json = JSONObject(responseBody)
        val elements = json.getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val lat = element.optDouble("lat", Double.NaN)
            val lon = element.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val name = element.optJSONObject("tags")?.optString("name", "") ?: ""
            results.add(GeoPoint(lat, lon) to name.ifBlank { currentPoiCategory.label })
        }
        return results
    }

    private fun setupModeButtons() {
        findViewById<ImageButton>(R.id.mode_toggle_button).setOnClickListener {
            val modes = TravelMode.values()
            val nextIndex = (currentMode.ordinal + 1) % modes.size
            selectMode(modes[nextIndex])
        }
    }

    /** Toll visibility and speed-sign state depend on Driving being the
     *  active mode. Used both when the mode actively changes and once
     *  at startup, so a restored "last session was Driving" state
     *  correctly shows the toll icon immediately, not just after the
     *  user taps mode again. */
    private fun syncDrivingModeUI() {
        val showDrivingExtras = currentMode == TravelMode.DRIVING
        tollToggle.visibility = if (showDrivingExtras) android.view.View.VISIBLE else android.view.View.GONE
        if (!showDrivingExtras) {
            speedSign.visibility = android.view.View.GONE
        }
    }

    private fun selectMode(mode: TravelMode) {
        if (currentMode == mode) return
        currentMode = mode
        updateLocationMarkerIcon()
        refreshModeButtonHighlight()
        syncDrivingModeUI()
        saveToggleState()

        val modeLabel = when (mode) {
            TravelMode.WALKING -> "Walking"
            TravelMode.CYCLING -> "Cycling"
            TravelMode.DRIVING -> "Driving"
        }

        val start = resolveStartPoint()
        val end = endPoint
        if (start != null && end != null) {
            showMessage("$modeLabel mode selected \u00b7 finding route...")
            requestBestRoute(start, end)
        } else {
            showMessage("$modeLabel mode selected")
        }
    }

    /** The on-map "you are here" marker. Uses one consistent arrow icon
     *  (ic_position_arrow -- replace this file via Vector Asset Studio
     *  for your own design) instead of switching per travel mode.
     *  osmdroid automatically rotates the direction icon to match GPS
     *  bearing while moving, and falls back to the person icon (same
     *  arrow here) when stationary or no bearing is available yet. */
    private fun updateLocationMarkerIcon() {
        val bitmap = ContextCompat.getDrawable(this, R.drawable.ic_position_arrow)?.toBitmap(width = 96, height = 96)
            ?: return
        myLocationOverlay.setPersonIcon(bitmap)
        myLocationOverlay.setPersonAnchor(0.5f, 0.5f)
        myLocationOverlay.setDirectionIcon(bitmap)
        myLocationOverlay.setDirectionAnchor(0.5f, 0.5f)
        map.invalidate()
    }

    /** Single toggle button now, not a row of three -- its icon shows
     *  the current selection directly, kept at the active/reddish tint
     *  as a standing "this is a live control" indicator. */
    private fun refreshModeButtonHighlight() {
        val button = findViewById<ImageButton>(R.id.mode_toggle_button)
        button.setImageResource(currentMode.iconRes)
        setIconActive(button, true)
    }

    // ---------- Route preference ----------

    private fun setupPreferenceRow() {
        findViewById<ImageButton>(R.id.preference_toggle_button).setOnClickListener {
            val prefs = RoutePreference.values()
            val nextIndex = (currentPreference.ordinal + 1) % prefs.size
            selectPreference(prefs[nextIndex])
        }
    }

    private fun selectPreference(preference: RoutePreference) {
        if (currentPreference == preference) return
        currentPreference = preference
        refreshPreferenceHighlight()
        saveToggleState()

        val prefLabel = when (preference) {
            RoutePreference.FASTEST -> "Fastest route"
            RoutePreference.SHORTEST -> "Shortest distance"
            RoutePreference.RECOMMENDED -> "Balanced mode"
        }

        val start = resolveStartPoint()
        val end = endPoint
        if (start != null && end != null) {
            showMessage("$prefLabel selected \u00b7 finding route...")
            requestBestRoute(start, end)
        } else {
            showMessage("$prefLabel selected")
        }
    }

    private fun refreshPreferenceHighlight() {
        val button = findViewById<ImageButton>(R.id.preference_toggle_button)
        button.setImageResource(currentPreference.iconRes)
        setIconActive(button, true)
    }

    // ---------- Toll avoidance & urgency ----------

    /** Colors the backdrop (not the icon itself, learned from the POI
     *  icon lesson -- tinting would flatten any internal detail) to
     *  match the app's standard active/inactive convention: reddish
     *  when tolls are allowed (the "on" state), blueish-gray when
     *  avoided (a deliberate deselection). */
    private fun setTollButtonStatus(allowed: Boolean) {
        val backdrop = tollToggle.background?.mutate() as? android.graphics.drawable.GradientDrawable
        val color = if (allowed) "#B15C52" else "#7288A0"
        backdrop?.setColor(android.graphics.Color.parseColor(color))
    }

    private fun setupTollToggle() {
        tollToggle.setImageResource(if (avoidTolls) R.drawable.ic_toll_off else R.drawable.ic_toll_on)
        setTollButtonStatus(!avoidTolls)
        tollToggle.setOnClickListener {
            avoidTolls = !avoidTolls
            tollToggle.setImageResource(if (avoidTolls) R.drawable.ic_toll_off else R.drawable.ic_toll_on)
            setTollButtonStatus(!avoidTolls)
            saveToggleState()
            val tollLabel = if (avoidTolls) "No toll selected" else "Toll roads allowed"
            val start = resolveStartPoint()
            val end = endPoint
            if (start != null && end != null) {
                showMessage("$tollLabel \u00b7 finding route...")
                requestBestRoute(start, end)
            } else {
                showMessage(tollLabel)
            }
        }
    }

    private fun setupUrgencyButton() {
        urgencyButton.setImageResource(urgencyMode.iconRes)
        urgencyButton.setOnClickListener {
            urgencyMode = when (urgencyMode) {
                UrgencyMode.WEEKEND -> UrgencyMode.WORK
                UrgencyMode.WORK -> UrgencyMode.EMERGENCY
                UrgencyMode.EMERGENCY -> UrgencyMode.WEEKEND
            }
            urgencyButton.setImageResource(urgencyMode.iconRes)
            showMessage("${urgencyMode.name.lowercase().replaceFirstChar { it.uppercase() }} mode selected")
            saveToggleState()

            if (urgencyMode == UrgencyMode.EMERGENCY && currentPoiCategory != PoiCategory.EMERGENCY) {
                currentPoiCategory = PoiCategory.EMERGENCY
                val poiButton = findViewById<ImageButton>(R.id.poi_toggle_button)
                poiButton.setImageResource(PoiCategory.EMERGENCY.iconRes)
                setPoiButtonStatus(poiButton, false)
                queryPois(force = true)
            }

            val start = resolveStartPoint()
            val end = endPoint
            if (start != null && end != null && currentMode == TravelMode.DRIVING && currentPreference == RoutePreference.RECOMMENDED) {
                showMessage("Finding route...")
                requestBestRoute(start, end)
            }
        }
    }

    // ---------- Location / permissions ----------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRawLocationUpdates() {
        if (!hasLocationPermission()) return
        rawLocationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            rawLocationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000L, 10f, rawLocationListener
            )
        } catch (e: Exception) {
            // Device may not have a GPS provider at all -- not fatal,
            // the network fallback below may still work.
        }
        try {
            rawLocationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000L, 20f, rawLocationListener
            )
        } catch (e: Exception) {
            // No network location provider either -- speed sign simply
            // won't have data, handled gracefully elsewhere.
        }
    }

    private fun stopRawLocationUpdates() {
        try {
            rawLocationManager?.removeUpdates(rawLocationListener)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun resolveInitialCenter(): GeoPoint {
        if (!hasLocationPermission()) return fallbackLocation

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsLast = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) { null }
        val networkLast = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }

        val best = gpsLast ?: networkLast ?: return fallbackLocation
        return GeoPoint(best.latitude, best.longitude)
    }

    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val current = myLocationOverlay.myLocation
        if (current == null) {
            showError("Still waiting for a GPS fix -- try again in a moment")
            return
        }
        val locateButton = findViewById<ImageButton>(R.id.locate_button)
        setIconActive(locateButton, true)
        map.controller.animateTo(current)
        map.controller.setZoom(15.0)
        speedHandler.postDelayed({ setIconActive(locateButton, false) }, 900L)
    }

    private fun resolveStartPoint(): GeoPoint? {
        return customStartPoint ?: myLocationOverlay.myLocation
    }

    // ---------- Speed limit indicator ----------

    /** Throttled Overpass query for the maxspeed tag on the nearest road.
     *  Only fires at most once per 15 seconds to be a reasonable citizen
     *  of a free public API, and only while in Driving mode. */
    private fun maybeQuerySpeedLimit(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastOverpassQueryAtMs < 15000L) return
        lastOverpassQueryAtMs = now

        val query = "[out:json][timeout:10];way(around:25,${location.latitude},${location.longitude})[\"highway\"][\"maxspeed\"];out tags 3;"
        fetchFromOverpass(query, useMirror = false) { body, errorText ->
            if (body == null) {
                runOnUiThread { showError(errorText ?: "Speed limit check failed") }
                return@fetchFromOverpass
            }
            val limit = try {
                parseMaxSpeed(body)
            } catch (e: Exception) {
                null
            }
            lastKnownSpeedLimit = limit
            runOnUiThread {
                lastFix?.let { updateSpeedSignDisplay(it) }
            }
        }
    }

    /** Parses the first way's maxspeed tag. Handles plain numbers and the
     *  common French implicit-limit codes; anything else is treated as
     *  unknown rather than guessed. */
    private fun parseMaxSpeed(responseBody: String): Int? {
        val json = JSONObject(responseBody)
        val elements = json.getJSONArray("elements")
        if (elements.length() == 0) return null

        val tags = elements.getJSONObject(0).optJSONObject("tags") ?: return null
        val raw = tags.optString("maxspeed", "") .ifEmpty { return null }

        raw.toIntOrNull()?.let { return it }

        return when (raw) {
            "FR:motorway" -> 130
            "FR:rural" -> 80
            "FR:urban" -> 50
            "FR:zone30" -> 30
            else -> null
        }
    }

    /** Updates the on-screen sign and triggers color/blink/alarm state
     *  per the thresholds discussed:
     *  - 5+ km/h under the limit: green (comfortably legal)
     *  - within 5 km/h under, or exactly at the limit: yellow
     *  - up to 5 km/h over: red, steady
     *  - more than 5 km/h over: red, blinking, high-pitched repeating alarm
     *  - on a 100+ km/h road, 15+ km/h under the limit: blue, low-pitched
     *    repeating alarm (driving suspiciously slow for a fast road) --
     *    this overrides the green/yellow reading in that specific case. */
    private fun updateSpeedSignDisplay(location: Location) {
        val limit = lastKnownSpeedLimit
        val speedKmhExact = location.speed * 3.6
        val speedKmh = speedKmhExact.toInt()
        val hasRealMovement = location.hasSpeed() && speedKmh > 3

        speedSign.visibility = android.view.View.VISIBLE
        speedSign.visibility = android.view.View.VISIBLE

        val background = speedSign.background?.mutate() as? android.graphics.drawable.GradientDrawable

        if (limit == null) {
            // No limit known for this road yet -- still show the sign
            // (current speed is always shown above it), but with a
            // placeholder in the circle instead of a number that isn't
            // real, and neutral styling since there's nothing to compare.
            speedSignText.text = "--"
            background?.setStroke(dp(5), android.graphics.Color.parseColor("#9E9E9E"))
            stopBlinking()
            return
        }

        speedSignText.text = limit.toString()

        val diff = speedKmh - limit
        val stoppedOnHighway = !hasRealMovement && limit >= 100
        val tooSlowForHighway = hasRealMovement && limit >= 100 && diff <= -15

        when {
            stoppedOnHighway -> {
                // Stopped/crawling on what should be a fast road -- a
                // real hazard worth flagging (breakdown, incident,
                // traffic backup), so this one still alarms even though
                // other stationary cases don't.
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#C0392B"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            !hasRealMovement -> {
                // Stationary on an ordinary road: normal, no alarm --
                // stopping at lights/traffic is expected here.
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#9E9E9E"))
                stopBlinking()
            }
            tooSlowForHighway -> {
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#1565C0"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            diff > 5 -> {
                startBlinking()
                maybeSoundAlarm(highPitch = true)
            }
            diff > 0 -> {
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#C0392B"))
                stopBlinking()
            }
            diff > -5 -> {
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#F1C40F"))
                stopBlinking()
            }
            else -> {
                background?.setStroke(dp(5), android.graphics.Color.parseColor("#2E7D32"))
                stopBlinking()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val blinkRunnable = object : Runnable {
        override fun run() {
            val background = speedSign.background?.mutate() as? android.graphics.drawable.GradientDrawable
            val color = if (blinkOn) android.graphics.Color.parseColor("#C0392B") else android.graphics.Color.parseColor("#FFFFFF")
            background?.setStroke(dp(5), color)
            blinkOn = !blinkOn
            speedHandler.postDelayed(this, 400L)
        }
    }
    private var isBlinking = false

    private fun startBlinking() {
        if (isBlinking) return
        isBlinking = true
        speedHandler.post(blinkRunnable)
    }

    private fun stopBlinking() {
        if (!isBlinking) return
        isBlinking = false
        speedHandler.removeCallbacks(blinkRunnable)
    }

    private fun maybeSoundAlarm(highPitch: Boolean) {
        wakeDisplay()
        val now = System.currentTimeMillis()
        if (now - lastAlarmAtMs < 3000L) return
        lastAlarmAtMs = now
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            }
            val tone = if (highPitch) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_LOW_L
            toneGenerator?.startTone(tone, 200)
        } catch (e: Exception) {
            // Non-critical -- if the tone generator fails, the visual
            // sign is still there.
        }
    }

    // ---------- Map taps ----------

    private fun handleDestinationTap(point: GeoPoint) {
        if (isNavigating) {
            showMessage("Stop navigation before changing your route")
            return
        }
        if (waypointModeArmed) {
            addWaypoint(point)
            return
        }

        val start = resolveStartPoint()
        if (start == null) {
            showError("No GPS fix yet -- long-press to set a start point manually")
            return
        }

        clearRouteVisuals()
        endPoint = point
        endMarker = addMarker(point, "Destination", R.drawable.ic_marker_destination)
        showMessage("Finding route...")
        requestBestRoute(start, point)
        map.invalidate()
    }

    private fun handleStartOverride(point: GeoPoint) {
        if (isNavigating) {
            showMessage("Stop navigation before changing your route")
            return
        }
        clearRoute()
        customStartPoint = point
        startMarker = addMarker(point, "Start", R.drawable.ic_marker_start)
        showMessage("Custom start point set -- tap to choose a destination")
        map.invalidate()
    }

    private fun addMarker(point: GeoPoint, title: String, iconRes: Int): Marker {
        val marker = Marker(map)
        marker.position = point
        marker.title = title
        marker.setInfoWindow(null)
        ContextCompat.getDrawable(this, iconRes)?.let { marker.icon = it }
        map.overlays.add(marker)
        return marker
    }

    private fun clearRouteVisuals() {
        endMarker?.let { map.overlays.remove(it) }
        routeLine?.let { map.overlays.remove(it) }
        alternativeLines.forEach { map.overlays.remove(it) }
        alternativeLines.clear()
        endMarker = null
        routeLine = null
    }

    private fun clearRoute() {
        endPoint = null
        startMarker?.let { map.overlays.remove(it) }
        waypointMarkers.forEach { map.overlays.remove(it) }
        waypointMarkers.clear()
        waypoints.clear()
        clearRouteVisuals()
        startMarker = null
        customStartPoint = null
        // (reset -- no message needed)
        routeInfoDist.text = ""
        routeInfoEta.text = ""
        routeInfoCost.text = ""
        routeInfo.visibility = android.view.View.GONE
        if (isNavigating) stopNavigation()
        findViewById<ImageButton>(R.id.nav_go_button).visibility = android.view.View.GONE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = android.view.View.GONE
        findViewById<LinearLayout>(R.id.route_options_row).apply {
            removeAllViews()
            visibility = android.view.View.GONE
        }
        map.invalidate()
    }

    // ---------- Routing ----------

    private fun buildCoordinatesArray(from: GeoPoint, to: GeoPoint): JSONArray {
        return JSONArray().apply {
            put(JSONArray().apply { put(from.longitude); put(from.latitude) })
            for (wp in waypoints) {
                put(JSONArray().apply { put(wp.longitude); put(wp.latitude) })
            }
            put(JSONArray().apply { put(to.longitude); put(to.latitude) })
        }
    }

    /** One turn-by-turn instruction. pointIndex refers to the index in
     *  RouteResult.points where this maneuver happens -- ORS's own
     *  way_points indices map directly onto our combined geometry. */
    private data class NavStep(
        val instruction: String,
        val distanceMeters: Double,
        val pointIndex: Int,
        val streetName: String = ""
    )

    private data class RouteResult(
        val points: List<GeoPoint>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val tollDistanceMeters: Double = 0.0,
        val steps: List<NavStep> = emptyList()
    )

    /** Entry point for any route request. Driving + Balanced gets the
     *  cost/time trade-off logic; everything else is a single request. */
    private var routeRequestStartedAtMs = 0L

    private fun requestBestRoute(from: GeoPoint, to: GeoPoint) {
        routeRequestStartedAtMs = System.currentTimeMillis()
        if (currentMode == TravelMode.DRIVING && currentPreference == RoutePreference.RECOMMENDED && !avoidTolls) {
            requestBalancedDrivingRoute(from, to)
        } else {
            requestRoute(from, to)
        }
    }

    /** Fetches both a tolls-allowed and a tolls-avoided route, estimates
     *  the toll cost on the tolled one (~EUR0.12/km on the toll-road
     *  portion -- a labeled estimate), and picks whichever is worth more
     *  given the current urgency mode's EUR-per-minute value. Both are
     *  drawn -- the winner as the primary thick line, the other thin. */
    private data class FetchOutcome(val result: RouteResult?, val httpCode: Int?, val body: String?)

    private fun requestBalancedDrivingRoute(from: GeoPoint, to: GeoPoint) {
        var withTolls: FetchOutcome? = null
        var withoutTolls: FetchOutcome? = null
        val lock = Any()

        fun tryFinish() {
            val wt: FetchOutcome?
            val wo: FetchOutcome?
            synchronized(lock) {
                wt = withTolls
                wo = withoutTolls
            }
            if (wt == null || wo == null) return

            runOnUiThread {
                if (wt.result == null && wo.result == null) {
                    val code = wt.httpCode ?: wo.httpCode
                    val body = wt.body ?: wo.body
                    showError(if (code != null) friendlyErrorMessage(code, body) else "Route request failed")
                    return@runOnUiThread
                }
                if (wt.result == null) {
                    drawRoutes(listOf(wo.result!!), noteOverride = null)
                    return@runOnUiThread
                }
                if (wo.result == null || wt.result.tollDistanceMeters <= 0.0) {
                    // No toll road involved at all -- nothing to balance.
                    drawRoutes(listOf(wt.result), noteOverride = null)
                    return@runOnUiThread
                }

                val tollKm = wt.result.tollDistanceMeters / 1000.0
                val estimatedTollCost = tollKm * tollRatePerKm
                val timeSavedMinutes = (wo.result.durationSeconds - wt.result.durationSeconds) / 60.0
                val valueOfTimeSaved = timeSavedMinutes * currentValuePerMinute()

                val takeToll = valueOfTimeSaved >= estimatedTollCost
                val primary = if (takeToll) wt.result else wo.result
                val secondary = if (takeToll) wo.result else wt.result

                val note = if (takeToll) {
                    "Toll worth it: saves ~%.0f min for an est. \u20ac%.0f".format(timeSavedMinutes, estimatedTollCost)
                } else {
                    "Skipping toll: only saves ~%.0f min for est. \u20ac%.0f".format(timeSavedMinutes, estimatedTollCost)
                }

                drawRoutes(listOf(primary, secondary), noteOverride = note)
            }
        }

        // Both fired immediately, not nested -- they run concurrently on
        // OkHttp's dispatcher instead of waiting on each other, roughly
        // halving real-world wait time on a normal connection.
        fetchSingleRoute(from, to, avoidTollsOverride = false, includeTollExtras = true) { outcome ->
            synchronized(lock) { withTolls = outcome }
            tryFinish()
        }
        fetchSingleRoute(from, to, avoidTollsOverride = true, includeTollExtras = false) { outcome ->
            synchronized(lock) { withoutTolls = outcome }
            tryFinish()
        }
    }

    /** Single ORS request. Callback-based (not suspend) to keep this
     *  compatible with the existing OkHttp callback style used elsewhere.
     *  Returns a FetchOutcome (not just a nullable result) so the caller
     *  can show a real error message instead of a generic failure. */
    private fun fetchSingleRoute(
        from: GeoPoint,
        to: GeoPoint,
        avoidTollsOverride: Boolean,
        includeTollExtras: Boolean,
        useSecondary: Boolean = false,
        onResult: (FetchOutcome) -> Unit
    ) {
        val bodyJson = JSONObject().apply {
            put("coordinates", buildCoordinatesArray(from, to))
            put("preference", "recommended")
            if (avoidTollsOverride) {
                put("options", JSONObject().apply {
                    put("avoid_features", JSONArray().apply { put("tollways") })
                })
            }
            if (includeTollExtras) {
                put("extra_info", JSONArray().apply { put("tollways") })
            }
        }

        val url = "https://api.openrouteservice.org/v2/directions/${currentMode.profile}/geojson"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", activeApiKey(useSecondary))
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(FetchOutcome(null, null, null))
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 403 && !useSecondary && hasSecondaryKey()) {
                    fetchSingleRoute(from, to, avoidTollsOverride, includeTollExtras, useSecondary = true, onResult = onResult)
                    return
                }
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onResult(FetchOutcome(null, response.code, body))
                    return
                }
                val result = try {
                    parseSingleRouteResult(body)
                } catch (e: Exception) {
                    null
                }
                onResult(FetchOutcome(result, null, null))
            }
        })
    }

    private fun requestRoute(from: GeoPoint, to: GeoPoint, withAlternatives: Boolean = true, useSecondary: Boolean = false) {
        val bodyJson = JSONObject().apply {
            put("coordinates", buildCoordinatesArray(from, to))
            put("preference", currentPreference.param)
            if (currentMode == TravelMode.DRIVING && avoidTolls) {
                put("options", JSONObject().apply {
                    put("avoid_features", JSONArray().apply { put("tollways") })
                })
            }
            if (withAlternatives) {
                put("alternative_routes", JSONObject().apply {
                    put("target_count", 2)
                    put("weight_factor", 1.6)
                })
            }
        }

        val url = "https://api.openrouteservice.org/v2/directions/${currentMode.profile}/geojson"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", activeApiKey(useSecondary))
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError("Route request failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()

                // Likely daily quota on this key -- silently retry on the
                // backup key if one's configured, rather than surfacing
                // an error the user would otherwise have to work around
                // by hand.
                if (response.code == 403 && !useSecondary && hasSecondaryKey()) {
                    requestRoute(from, to, withAlternatives, useSecondary = true)
                    return
                }

                if (!response.isSuccessful && withAlternatives) {
                    requestRoute(from, to, withAlternatives = false, useSecondary = useSecondary)
                    return
                }
                if (!response.isSuccessful || body == null) {
                    runOnUiThread { showError(friendlyErrorMessage(response.code, body)) }
                    return
                }
                val results = try {
                    parseRouteResults(body)
                } catch (e: Exception) {
                    runOnUiThread { showError("Couldn't read route response") }
                    return
                }
                runOnUiThread { drawRoutes(results, noteOverride = null) }
            }
        })
    }

    private fun friendlyErrorMessage(httpCode: Int, body: String?): String {
        val orsCode = try {
            body?.let { JSONObject(it).getJSONObject("error").getInt("code") }
        } catch (e: Exception) { null }
        return when (orsCode) {
            2010 -> "No road found near that point -- try tapping closer to a street"
            2009 -> "No route possible between these points"
            else -> "Route request failed (error $httpCode)"
        }
    }

    private fun parseSingleRouteResult(responseBody: String): RouteResult {
        val results = parseRouteResults(responseBody, wantTollExtras = true)
        return results[0]
    }

    private fun parseRouteResults(responseBody: String, wantTollExtras: Boolean = false): List<RouteResult> {
        val json = JSONObject(responseBody)
        val features = json.getJSONArray("features")
        val results = mutableListOf<RouteResult>()

        for (f in 0 until features.length()) {
            val feature = features.getJSONObject(f)
            val points = mutableListOf<GeoPoint>()

            val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            for (i in 0 until coordinates.length()) {
                val pair = coordinates.getJSONArray(i)
                points.add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
            }

            val properties = feature.getJSONObject("properties")
            val summary = properties.getJSONObject("summary")
            val distanceMeters = summary.getDouble("distance")
            val durationSeconds = summary.getDouble("duration")

            var tollDistance = 0.0
            if (wantTollExtras) {
                tollDistance = try {
                    val tollwaysSummary = properties.getJSONObject("extras")
                        .getJSONObject("tollways")
                        .getJSONArray("summary")
                    var sum = 0.0
                    for (i in 0 until tollwaysSummary.length()) {
                        val entry = tollwaysSummary.getJSONObject(i)
                        if (entry.getInt("value") == 1) {
                            sum += entry.getDouble("distance")
                        }
                    }
                    sum
                } catch (e: Exception) {
                    0.0
                }
            }

            val steps = mutableListOf<NavStep>()
            try {
                val segments = properties.getJSONArray("segments")
                for (s in 0 until segments.length()) {
                    val stepsArray = segments.getJSONObject(s).getJSONArray("steps")
                    for (st in 0 until stepsArray.length()) {
                        val step = stepsArray.getJSONObject(st)
                        val wayPoints = step.getJSONArray("way_points")
                        steps.add(
                            NavStep(
                                instruction = step.optString("instruction", "Continue"),
                                distanceMeters = step.optDouble("distance", 0.0),
                                pointIndex = wayPoints.getInt(1),
                                streetName = step.optString("name", "")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // No instructions available for this route -- navigation
                // will fall back to distance/bearing only, no step text.
            }

            results.add(RouteResult(points, distanceMeters, durationSeconds, tollDistance, steps))
        }
        return results
    }

    private fun drawRoutes(results: List<RouteResult>, noteOverride: String?, preserveSelection: Boolean = false) {
        if (results.isEmpty() || results[0].points.isEmpty()) {
            showError("Route returned no geometry")
            return
        }

        lastComputedRoutes = results
        if (!preserveSelection || selectedRouteIndex >= results.size) {
            selectedRouteIndex = 0
        }

        routeLine?.let { map.overlays.remove(it) }
        alternativeLines.forEach { map.overlays.remove(it) }
        alternativeLines.clear()

        // Alternatives are tappable -- tapping one promotes it to primary,
        // which is the "override the automatic choice" behavior asked for.
        // The underlying list order never changes -- only which index is
        // "selected" does, so pill text/position stays stable and only
        // color moves.
        for (i in results.indices) {
            if (i == selectedRouteIndex) continue
            val alt = Polyline()
            alt.setPoints(results[i].points)
            alt.outlinePaint.color = android.graphics.Color.parseColor("#B07288A0")
            alt.outlinePaint.strokeWidth = 9f
            val indexForClick = i
            alt.setOnClickListener { _, _, _ ->
                promoteRouteToPrimary(indexForClick)
                true
            }
            map.overlays.add(alt)
            alternativeLines.add(alt)
        }

        val primary = results[selectedRouteIndex]
        val line = Polyline()
        line.setPoints(primary.points)
        line.outlinePaint.color = android.graphics.Color.parseColor("#FFB15C52")
        line.outlinePaint.strokeWidth = 10f
        map.overlays.add(line)
        routeLine = line
        map.invalidate()

        val kmExact = primary.distanceMeters / 1000.0
        val distText = if (useMetric) {
            "Dist: %.2f Km".format(kmExact)
        } else {
            "Dist: %.2f mi".format(kmExact * 0.621371)
        }

        val arrivalCalendar = java.util.Calendar.getInstance()
        arrivalCalendar.add(java.util.Calendar.SECOND, primary.durationSeconds.toInt())
        val etaText = "ETA: %02d:%02d".format(
            arrivalCalendar.get(java.util.Calendar.HOUR_OF_DAY),
            arrivalCalendar.get(java.util.Calendar.MINUTE)
        )

        val costText = if (primary.tollDistanceMeters > 0) {
            val estimatedCost = (primary.tollDistanceMeters / 1000.0) * tollRatePerKm
            "Cost: ~\u20ac%.0f".format(estimatedCost)
        } else {
            "Cost: --"
        }

        noteOverride?.let { showMessage(it) }
        routeInfoDist.text = distText
        routeInfoEta.text = etaText
        routeInfoCost.text = costText
        routeInfo.visibility = android.view.View.VISIBLE

        populateRouteOptionsRow(results)

        findViewById<ImageButton>(R.id.nav_go_button).visibility = android.view.View.VISIBLE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = android.view.View.VISIBLE
        if (isNavigating) {
            // This draw came from an off-route reroute (or a manual
            // route-option tap mid-navigation) -- keep navigating, just
            // against the freshly computed route and reset step index.
            activeRoute = results[selectedRouteIndex]
            currentStepIndex = 0
        }
    }

    /** One tappable chip per computed route -- distance, ETA, and an
     *  estimated toll cost when the route uses toll roads (blank
     *  otherwise, since not every route has that data). Only shown when
     *  there's more than one route to actually choose between. */
    private fun populateRouteOptionsRow(results: List<RouteResult>) {
        val row = findViewById<LinearLayout>(R.id.route_options_row)
        row.removeAllViews()

        if (results.size < 2) {
            row.visibility = android.view.View.GONE
            return
        }

        for ((index, result) in results.withIndex()) {
            val km = (result.distanceMeters / 1000.0).toInt()
            val totalMinutes = (result.durationSeconds / 60.0).toInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            val eta = if (hours > 0) "%d:%02d".format(hours, minutes) else "0:%02d".format(minutes)

            val costPart = if (result.tollDistanceMeters > 0) {
                val est = (result.tollDistanceMeters / 1000.0) * tollRatePerKm
                " \u00b7 ~\u20ac%.0f".format(est)
            } else {
                ""
            }

            val chip = TextView(this)
            chip.text = "${km}km \u00b7 $eta$costPart"
            chip.textSize = 13f
            chip.setPadding(dp(10), dp(6), dp(10), dp(6))
            chip.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            val pillDrawable = ContextCompat.getDrawable(this, R.drawable.route_pill_background)?.mutate() as? android.graphics.drawable.GradientDrawable
            val pillColor = if (index == selectedRouteIndex) "#E0B15C52" else "#E07288A0"
            pillDrawable?.setColor(android.graphics.Color.parseColor(pillColor))
            chip.background = pillDrawable
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(6)
            chip.layoutParams = params

            val indexForClick = index
            chip.setOnClickListener {
                if (indexForClick != selectedRouteIndex) promoteRouteToPrimary(indexForClick)
            }
            row.addView(chip)
        }

        row.visibility = android.view.View.VISIBLE
    }

    private fun promoteRouteToPrimary(index: Int) {
        if (index !in lastComputedRoutes.indices) return
        selectedRouteIndex = index
        drawRoutes(lastComputedRoutes, noteOverride = "Manually selected route", preserveSelection = true)
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        applyDisplayMode()
        map.onResume()
        myLocationOverlay.enableMyLocation()
        if (hasLocationPermission()) startRawLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        myLocationOverlay.disableMyLocation()
        stopRawLocationUpdates()
        stopBlinking()
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
