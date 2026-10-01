package com.backpack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.backpack.app.core.Geo
import com.backpack.app.core.LatLon
import com.backpack.app.core.Maneuver
import com.backpack.app.core.NavStep
import com.backpack.app.core.Progress
import com.backpack.app.core.RouteOptions
import com.backpack.app.core.RoutePlan
import com.backpack.app.core.RoutePreference
import com.backpack.app.core.RouteRequest
import com.backpack.app.core.RouteResult
import com.backpack.app.core.Speech
import com.backpack.app.core.TravelMode
import com.backpack.app.location.LocationHub
import com.backpack.app.map.MapLayers
import com.backpack.app.nav.NavigationEngine
import com.backpack.app.route.RoutePlanner
import com.backpack.app.ui.Popup
import com.backpack.app.ui.RouteOptionsDialog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.IOException

/**
 * Backpack 0.4.0 map screen.
 *
 * Kept from Step 38 (behaviour unchanged): travel mode / preference /
 * toll / urgency toggles, the Balanced toll-vs-time choice, speed-limit
 * sign and alarm, screen dimming, POI categories, 2 stops, alternatives.
 *
 * New: MapLibre vector map (OpenFreeMap, real night map), GPS chip only,
 * turn-by-turn that keeps running with the screen off (NavigationEngine +
 * NavigationService), maneuver icons from the route data, route options,
 * trip kept across rotation / day-night switch, OSM credit on screen.
 */
class MainActivity : AppCompatActivity() {

    private enum class ModeUi(val mode: TravelMode, val iconRes: Int, val label: String) {
        WALKING(TravelMode.WALKING, R.drawable.ic_mode_walking, "Walking"),
        CYCLING(TravelMode.CYCLING, R.drawable.ic_mode_cycling, "Cycling"),
        DRIVING(TravelMode.DRIVING, R.drawable.ic_mode_driving, "Driving")
    }

    private enum class PreferenceUi(val pref: RoutePreference, val iconRes: Int, val label: String) {
        FASTEST(RoutePreference.FASTEST, R.drawable.ic_pref_fastest, "Fastest route"),
        SHORTEST(RoutePreference.SHORTEST, R.drawable.ic_pref_shortest, "Shortest distance"),
        RECOMMENDED(RoutePreference.RECOMMENDED, R.drawable.ic_pref_balanced, "Balanced mode")
    }

    /** Icons only -- the EUR/hour values come from Settings. */
    private enum class UrgencyMode(val iconRes: Int) {
        WEEKEND(R.drawable.ic_urgency_weekend),
        WORK(R.drawable.ic_urgency_work),
        EMERGENCY(R.drawable.ic_urgency_emergency)
    }

    private enum class PoiCategory(val overpassFilter: String, val iconRes: Int, val iconName: String, val label: String) {
        NONE("", R.drawable.ic_poi_toggle, "", "Off"),
        FUEL("[\"amenity\"~\"fuel|charging_station\"]", R.drawable.ic_poi_fuel, "poi_fuel", "Fuel/Charging"),
        FOOD("[\"amenity\"~\"restaurant|cafe|fast_food\"]", R.drawable.ic_poi_food, "poi_food", "Food"),
        LODGING("[\"tourism\"~\"hotel|guest_house|motel\"]", R.drawable.ic_poi_lodging, "poi_lodging", "Lodging"),
        PARKING("[\"amenity\"=\"parking\"]", R.drawable.ic_poi_parking, "poi_parking", "Parking"),
        TOILETS("[\"amenity\"=\"toilets\"]", R.drawable.ic_poi_toilets, "poi_toilets", "Toilets"),
        EMERGENCY("[\"amenity\"~\"hospital|police|fire_station\"]", R.drawable.ic_poi_emergency, "poi_emergency", "Emergency")
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var layers: MapLayers
    private lateinit var planner: RoutePlanner
    private var loadedStyleUrl: String? = null

    private lateinit var statusStrip: TextView
    private lateinit var navDistanceText: TextView
    private lateinit var navInstructionIcon: ImageView
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

    private var currentMode = ModeUi.WALKING
    private var currentPreference = PreferenceUi.RECOMMENDED
    private var avoidTolls = false
    private var urgencyMode = UrgencyMode.WEEKEND
    private var currentPoiCategory = PoiCategory.NONE
    private var routeOptions = RouteOptions()

    // From Settings.
    private var valuePerMinuteWeekend = SettingsActivity.DEFAULT_VALUE_WEEKEND / 60.0
    private var valuePerMinuteWork = SettingsActivity.DEFAULT_VALUE_WORK / 60.0
    private var valuePerMinuteEmergency = SettingsActivity.DEFAULT_VALUE_EMERGENCY / 60.0
    private var tollRatePerKm = SettingsActivity.DEFAULT_TOLL_RATE
    private var useMetric = true

    private var waypointModeArmed = false
    private val maxWaypoints = 2

    // Navigation screen state (the navigation itself lives in NavigationEngine).
    private var navUiActive = false
    private var lastRealLocationAtMs = 0L
    private var deadReckoningActive = false
    private var isGpsDegraded = false
    private var isDisplayDimmed = false
    private var lastTrackingZoom = 0.0

    // Speed limit.
    private var lastOverpassQueryAtMs = 0L
    private var lastKnownSpeedLimit: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = true
    private var isBlinking = false
    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmAtMs = 0L

    // POIs.
    private var poiRequestGeneration = 0
    private var lastPoiQueryAtMs = 0L
    private var lastPoiQueryCenter: LatLon? = null

    private val activeColor by lazy { ContextCompat.getColor(this, R.color.rg_active) }
    private val inactiveColor by lazy { ContextCompat.getColor(this, R.color.rg_icon_inactive) }
    private val backdropColor by lazy { ContextCompat.getColor(this, R.color.rg_control_backdrop) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOnly = !fine && result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine) {
            LocationHub.acquire(this, LocationHub.LEASE_UI)
            map?.style?.let { activateLocationPuck(it) }
            if (!LocationHub.isGpsEnabled(this)) showError("GPS is off -- turn on Location")
        } else if (coarseOnly) {
            showError("Precise location needed -- GPS navigation can't work with approximate location")
        } else {
            showError("Location permission denied -- no position, no navigation")
        }
    }

    /** Android 12+ only shows the dialog when FINE and COARSE are asked together. */
    private fun requestLocationPermission() = locationPermissionLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Navigation works either way; the notification just stays hidden. */ }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyNightMode(this)
        loadToggleState()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        loadSettings()
        planner = RoutePlanner(this)
        layers = MapLayers(this)
        NavigationEngine.warmUp(this)

        mapView = findViewById(R.id.map)
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
        applyBubbleColors()

        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { showError("Map could not load -- check the connection") }
        mapView.getMapAsync { m -> onMapReady(m) }

        findViewById<ImageButton>(R.id.locate_button).apply {
            setIconActive(this, false)
            setOnClickListener { centerOnMyLocation() }
        }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.credit_text).setOnClickListener { showCredits() }

        setupAddressSearch()
        setupWaypointToggle()
        setupNavigationButton()
        setupPoiToggle()
        setupModeButtons()
        setupPreferenceRow()
        setupTollToggle()
        setupUrgencyButton()
        setupRouteOptionsButton()
        refreshModeButtonHighlight()
        refreshPreferenceHighlight()
        syncDrivingModeUI()

        if (!LocationHub.hasPermission(this)) {
            requestLocationPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        LocationHub.addListener(uiFixListener)
        NavigationEngine.addListener(navListener)
        if (LocationHub.hasPermission(this)) LocationHub.acquire(this, LocationHub.LEASE_UI)
        if (navUiActive) {
            handler.removeCallbacks(deadReckoningRunnable)
            handler.postDelayed(deadReckoningRunnable, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        loadSettings()
        // Style setting changed in Settings (day/night handled by recreation).
        val m = map
        if (m != null && loadedStyleUrl != null && loadedStyleUrl != Prefs.mapStyleUrl(this)) loadStyle(m)
        // Navigation may have ended while the screen was away.
        if (navUiActive && !NavigationEngine.isActive) stopNavigationUi()
        if (LocationHub.hasPermission(this) && !LocationHub.isGpsEnabled(this)) {
            showError("GPS is off -- turn on Location")
        }
    }

    override fun onPause() {
        super.onPause()
        map?.cameraPosition?.let { cp ->
            cp.target?.let { Prefs.saveCamera(this, it.latitude, it.longitude, cp.zoom) }
        }
        mapView.onPause()
        stopBlinking()
    }

    override fun onStop() {
        super.onStop()
        LocationHub.removeListener(uiFixListener)
        NavigationEngine.removeListener(navListener)
        // GPS keeps running only if navigation holds its own lease.
        LocationHub.release(this, LocationHub.LEASE_UI)
        handler.removeCallbacks(deadReckoningRunnable)
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        planner.cancel()
        handler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
        toneGenerator = null
        mapView.onDestroy()
        super.onDestroy()
    }

    // ---------- Map ----------

    private fun onMapReady(m: MapLibreMap) {
        map = m
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false // own always-visible credit line instead
        m.uiSettings.isCompassEnabled = true
        // Left side, below the mode row: the right column is full of buttons.
        m.uiSettings.setCompassGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        m.uiSettings.setCompassMargins(dp(12), dp(170), dp(12), dp(12))

        val saved = Prefs.camera(this)
        val start = when {
            saved != null -> LatLng(saved.first, saved.second)
            else -> LocationHub.lastKnownGps(this)?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(60.3913, 5.3221)
        }
        m.cameraPosition = CameraPosition.Builder().target(start).zoom(saved?.third ?: 12.0).build()

        m.addOnMapClickListener { p ->
            val idx = layers.alternativeAt(m, m.projection.toScreenLocation(p))
            if (idx != null) {
                promoteRouteToPrimary(idx)
            } else {
                handleDestinationTap(LatLon(p.latitude, p.longitude))
            }
            true
        }
        m.addOnMapLongClickListener { p ->
            handleStartOverride(LatLon(p.latitude, p.longitude))
            true
        }
        m.addOnCameraIdleListener { queryPois() }
        loadStyle(m)
    }

    private fun loadStyle(m: MapLibreMap) {
        val url = Prefs.mapStyleUrl(this)
        loadedStyleUrl = url
        m.setStyle(Style.Builder().fromUri(url)) { style ->
            layers.install(style)
            if (LocationHub.hasPermission(this)) activateLocationPuck(style)
            restoreTripOnMap()
            if (currentPoiCategory != PoiCategory.NONE) queryPois(force = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocationPuck(style: Style) {
        val m = map ?: return
        if (!LocationHub.hasPermission(this)) return
        val lc = m.locationComponent
        // Own arrow icon (unchanged), no default blue dot, no tinting.
        val options = LocationComponentOptions.builder(this)
            .foregroundDrawable(R.drawable.ic_position_arrow)
            .gpsDrawable(R.drawable.ic_position_arrow)
            .foregroundDrawableStale(R.drawable.ic_position_arrow)
            .backgroundDrawable(R.drawable.puck_none)
            .backgroundDrawableStale(R.drawable.puck_none)
            .bearingDrawable(R.drawable.puck_none)
            .foregroundTintColor(null)
            .foregroundStaleTintColor(null)
            .backgroundTintColor(null)
            .backgroundStaleTintColor(null)
            .bearingTintColor(null)
            .accuracyColor(activeColor)
            .accuracyAlpha(0.12f)
            .enableStaleState(false)
            .build()
        // useDefaultLocationEngine(false): MapLibre gets positions only from
        // LocationHub (GPS chip), never from its own location engine.
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        lc.cameraMode = CameraMode.NONE
        LocationHub.lastGoodFix?.let { lc.forceLocationUpdate(withHeading(it)) }
        if (NavigationEngine.isActive) startNavigationUi()
    }

    private fun puckActive(): Boolean = map?.locationComponent?.isLocationComponentActivated == true

    /** After the screen was rebuilt (rotation, day/night, style change). */
    private fun restoreTripOnMap() {
        refreshPins()
        val plan = NavigationEngine.plan ?: TripState.plan
        if (plan != null) {
            val sel = if (NavigationEngine.isActive) NavigationEngine.planSelected else TripState.selectedIndex
            TripState.plan = plan
            TripState.selectedIndex = sel
            drawPlan(plan, sel, fit = false, note = null)
        }
    }

    // ---------- Location ----------

    private val uiFixListener = LocationHub.Listener { location, plausible ->
        lastRealLocationAtMs = System.currentTimeMillis()
        if (plausible) {
            if (isGpsDegraded) clearGpsWarning()
            if (puckActive()) map?.locationComponent?.forceLocationUpdate(withHeading(location))
            if (currentMode == ModeUi.DRIVING) {
                maybeQuerySpeedLimit(location)
                updateSpeedSignDisplay(location)
            }
            if (navUiActive) adaptTrackingZoom(location)
            queryPois()
        } else {
            // Likely signal loss / reacquisition noise: position, progress
            // and off-route detection keep using the last good fix.
            showGpsWarning()
        }
    }

    /** Near standstill GPS drops the bearing (reported as 0 = north), which
     *  would spin the arrow and the map at every red light: keep the last one. */
    private fun withHeading(location: Location): Location {
        if (location.hasBearing() && location.hasSpeed() && location.speed > 0.7f) return location
        val b = LocationHub.lastMovingBearing ?: return location
        return Location(location).apply { bearing = b }
    }

    private fun currentPosition(): LatLon? = LocationHub.lastGoodFix?.let { LatLon(it.latitude, it.longitude) }

    private fun resolveStartPoint(): LatLon? = TripState.customStart ?: currentPosition()

    private fun showGpsWarning() {
        isGpsDegraded = true
        wakeDisplay()
        val fix = LocationHub.lastGoodFix ?: return
        layers.setWarning(LatLon(fix.latitude, fix.longitude))
        showMessage("GPS signal weak -- holding last known position")
    }

    private fun clearGpsWarning() {
        isGpsDegraded = false
        layers.setWarning(null)
    }

    private fun centerOnMyLocation() {
        if (!LocationHub.hasPermission(this)) {
            requestLocationPermission()
            return
        }
        val m = map ?: return
        if (navUiActive && puckActive()) {
            // Back to following after the map was moved by hand.
            followPosition(null)
            return
        }
        val current = LocationHub.lastGoodFix
        if (current == null) {
            showError(if (LocationHub.isGpsEnabled(this)) "Still waiting for a GPS fix -- try again in a moment" else "GPS is off -- turn on Location")
            return
        }
        val locateButton = findViewById<ImageButton>(R.id.locate_button)
        setIconActive(locateButton, true)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(current.latitude, current.longitude), 15.0))
        handler.postDelayed({ setIconActive(locateButton, false) }, 900L)
    }

    // ---------- Toggle state (same keys as Step 38) ----------

    private fun loadToggleState() {
        val prefs = getSharedPreferences("backpack_toggle_state", MODE_PRIVATE)
        currentMode = enumOr(prefs.getString("mode", null), currentMode)
        currentPreference = enumOr(prefs.getString("preference", null), currentPreference)
        urgencyMode = enumOr(prefs.getString("urgency", null), urgencyMode)
        avoidTolls = prefs.getBoolean("avoid_tolls", avoidTolls)
        currentPoiCategory = enumOr(prefs.getString("poi_category", null), currentPoiCategory)
        // A configured "default on launch" POI in Settings wins over session memory.
        val defaultPoi = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            .getString(SettingsActivity.KEY_DEFAULT_POI, "NONE")
        if (defaultPoi != "NONE") currentPoiCategory = enumOr(defaultPoi, currentPoiCategory)
        routeOptions = Prefs.routeOptions(this)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun saveToggleState() {
        getSharedPreferences("backpack_toggle_state", MODE_PRIVATE).edit()
            .putString("mode", currentMode.name)
            .putString("preference", currentPreference.name)
            .putString("urgency", urgencyMode.name)
            .putBoolean("avoid_tolls", avoidTolls)
            .putString("poi_category", currentPoiCategory.name)
            .apply()
    }

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

    // ---------- Messages ----------

    /** Bubbles: fixed dark, high-contrast scheme in day and night. */
    private fun applyBubbleColors() {
        for (b in listOf(messageBubble, errorBubble)) {
            (b.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#B3333333"))
            b.setTextColor(Color.WHITE)
        }
        (errorBubble.background as? GradientDrawable)?.setStroke(dp(2), activeColor)
    }

    private var bubbleFadeOutRunnable: Runnable? = null

    private fun showMessage(text: String) {
        bubbleFadeOutRunnable?.let { handler.removeCallbacks(it) }
        messageBubble.text = text
        messageBubble.visibility = View.VISIBLE
        messageBubble.animate().cancel()
        messageBubble.alpha = 0f
        messageBubble.animate().alpha(1f).setDuration(200).start()
        val fadeOut = Runnable {
            messageBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                messageBubble.visibility = View.INVISIBLE
            }.start()
        }
        bubbleFadeOutRunnable = fadeOut
        handler.postDelayed(fadeOut, 3000L)
    }

    private var errorFadeOutRunnable: Runnable? = null

    private fun showError(text: String) {
        errorFadeOutRunnable?.let { handler.removeCallbacks(it) }
        errorBubble.text = text
        errorBubble.visibility = View.VISIBLE
        errorBubble.animate().cancel()
        errorBubble.alpha = 0f
        errorBubble.animate().alpha(1f).setDuration(200).start()
        val fadeOut = Runnable {
            errorBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                errorBubble.visibility = View.INVISIBLE
            }.start()
        }
        errorFadeOutRunnable = fadeOut
        handler.postDelayed(fadeOut, 4000L)
    }

    private fun setIconActive(button: ImageButton, active: Boolean) {
        button.imageTintList = ColorStateList.valueOf(if (active) activeColor else inactiveColor)
    }

    private fun setBackdrop(button: ImageButton, color: Int) {
        (button.background?.mutate() as? GradientDrawable)?.setColor(color)
    }

    private fun showCredits() {
        val p = Popup(this, "Map and data credits")
        p.text("Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).")
        p.text("Map tiles: OpenFreeMap © OpenMapTiles.")
        p.text("Routing and address search: openrouteservice.org by HeiGIT.")
        p.text("Places and speed limits: Overpass API (OpenStreetMap data).")
        p.text("Map engine: MapLibre Native (BSD licence).")
        p.button("OSM copyright") {
            p.dismiss()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
            } catch (e: Exception) { }
        }
        p.button("Close", primary = true) { p.dismiss() }
        p.show()
    }

    // ---------- Address search ----------

    private fun setupAddressSearch() {
        val searchButton = findViewById<ImageButton>(R.id.search_button)
        val searchInput = findViewById<EditText>(R.id.search_input)
        setIconActive(searchButton, false)
        searchButton.setOnClickListener {
            searchInput.requestFocus()
            searchInput.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        searchInput.setOnFocusChangeListener { _, hasFocus -> setIconActive(searchButton, hasFocus) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val text = searchInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    showMessage("Searching...")
                    planner.geocode(text, currentPosition()) { point, _, error ->
                        if (point == null) {
                            showError(error ?: "Address not found")
                        } else {
                            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 14.0))
                            handleDestinationTap(point)
                        }
                    }
                }
                searchInput.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    // ---------- Stops (waypoints) ----------

    private fun setupWaypointToggle() {
        val button = findViewById<ImageButton>(R.id.waypoint_button)
        button.setOnClickListener {
            waypointModeArmed = !waypointModeArmed
            setIconActive(button, waypointModeArmed)
            showMessage(if (waypointModeArmed) "Tap the map to add a stop (max $maxWaypoints)" else "Stop mode off")
        }
        setIconActive(button, false)
    }

    private fun addWaypoint(point: LatLon) {
        if (TripState.waypoints.size >= maxWaypoints) {
            showMessage("Maximum $maxWaypoints stops reached")
            return
        }
        TripState.waypoints.add(point)
        refreshPins()
        if (TripState.waypoints.size >= maxWaypoints) {
            // The natural next tap sets the destination, not a rejected 3rd stop.
            waypointModeArmed = false
            setIconActive(findViewById(R.id.waypoint_button), false)
            showMessage("Stop added. Tap the map to set your destination.")
        } else {
            showMessage("Stop added. Tap for one more, or set your destination.")
        }
        val start = resolveStartPoint()
        val end = TripState.destination
        if (start != null && end != null) {
            showMessage("Finding route...")
            requestBestRoute(start, end)
        }
    }

    private fun refreshPins() {
        val pins = mutableListOf<MapLayers.Pin>()
        TripState.customStart?.let { pins.add(MapLayers.Pin("start", it, MapLayers.ICON_START)) }
        TripState.waypoints.forEachIndexed { i, w -> pins.add(MapLayers.Pin("stop$i", w, MapLayers.ICON_STOP)) }
        TripState.destination?.let { pins.add(MapLayers.Pin("dest", it, MapLayers.ICON_DEST)) }
        layers.setPins(pins)
    }

    // ---------- Navigation ----------

    private fun setupNavigationButton() {
        findViewById<ImageButton>(R.id.nav_go_button).setOnClickListener {
            if (NavigationEngine.isActive) {
                NavigationEngine.stop() // stop + clear together, not two separate steps
                clearRoute()
                showMessage("Navigation stopped, route cleared")
            } else {
                startNavigation()
            }
        }
        findViewById<ImageButton>(R.id.clear_route_button).setOnClickListener {
            if (NavigationEngine.isActive) NavigationEngine.stop()
            clearRoute()
            showMessage("Route cleared")
        }
    }

    private fun startNavigation() {
        val plan = TripState.plan
        val end = TripState.destination
        // Fix: navigate the route the user SELECTED (Step 38 always took the first).
        val route = plan?.routes?.getOrNull(TripState.selectedIndex)
        if (plan == null || route == null || route.points.isEmpty() || end == null) {
            showError("No route to navigate yet")
            return
        }
        if (!LocationHub.hasPermission(this)) {
            requestLocationPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val start = resolveStartPoint() ?: route.points.first()
        NavigationEngine.start(this, plan, TripState.selectedIndex, buildRequest(start, end))
        startNavigationUi()
        // Before the first GPS fix: show the departure step.
        if (NavigationEngine.lastProgress == null) updateNavBar(route.steps.firstOrNull(), null)
    }

    /** Switches the screen into navigation look; safe to call again. */
    private fun startNavigationUi() {
        val first = !navUiActive
        navUiActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeDisplay()
        findViewById<ImageButton>(R.id.nav_go_button).setImageResource(R.drawable.ic_navigation_stop)
        setNavigationDeclutter(true)
        navInstructionBar.visibility = View.VISIBLE
        lastRealLocationAtMs = System.currentTimeMillis()
        handler.removeCallbacks(deadReckoningRunnable)
        handler.postDelayed(deadReckoningRunnable, 1000L)

        val m = map ?: return
        if (puckActive()) followPosition(if (first) 18.0 else null)
        NavigationEngine.lastProgress?.let { p -> NavigationEngine.route?.let { onNavProgress(p, it) } }
    }

    /** Camera follows the arrow, heading-up. Zoom and tilt are part of the
     *  transition itself (MapLibre ignores them while a transition runs);
     *  the arrow is moved to the lower part of the screen once it ends. */
    private fun followPosition(zoom: Double?) {
        val lc = map?.locationComponent ?: return
        if (zoom != null) lastTrackingZoom = zoom
        val tilt = if (currentMode == ModeUi.DRIVING) 45.0 else 25.0
        lc.setCameraMode(CameraMode.TRACKING_GPS, 750L, zoom, null, tilt, object : OnLocationCameraTransitionListener {
            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                if (navUiActive) {
                    // No position yet = no transition = zoom/tilt not applied.
                    val cp = map?.cameraPosition
                    if (zoom != null && cp != null && Math.abs(cp.zoom - zoom) > 0.1) lc.zoomWhileTracking(zoom)
                    if (cp != null && Math.abs(cp.tilt - tilt) > 1.0) lc.tiltWhileTracking(tilt)
                    lc.paddingWhileTracking(doubleArrayOf(0.0, resources.displayMetrics.heightPixels * 0.35, 0.0, 0.0))
                }
            }
            override fun onLocationCameraTransitionCanceled(cameraMode: Int) {}
        })
    }

    private fun stopNavigationUi() {
        navUiActive = false
        deadReckoningActive = false
        handler.removeCallbacks(deadReckoningRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        isDisplayDimmed = false
        findViewById<ImageButton>(R.id.nav_go_button).setImageResource(R.drawable.ic_navigation_go)
        setNavigationDeclutter(false)
        statusStrip.text = ""
        navDistanceText.text = ""
        navInstructionIcon.visibility = View.GONE
        navInstructionBar.visibility = View.GONE
        val m = map ?: return
        if (puckActive()) m.locationComponent.cameraMode = CameraMode.NONE
        // The camera reset below also clears the navigation padding.
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(m.cameraPosition.target).zoom(m.cameraPosition.zoom)
                .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
        ))
    }

    private val navListener = object : NavigationEngine.Listener {
        override fun onProgress(progress: Progress, route: RouteResult) = onNavProgress(progress, route)
        override fun onRerouting() = showMessage("Off route, recalculating...")
        override fun onRerouted(plan: RoutePlan) {
            TripState.plan = plan
            TripState.selectedIndex = plan.primaryIndex
            drawPlan(plan, plan.primaryIndex, fit = false, note = plan.note)
        }
        override fun onRerouteFailed(error: String) = showError(error)
        override fun onArrived() {
            navDistanceText.text = ""
            statusStrip.text = "Arrived"
            navInstructionIcon.setImageResource(R.drawable.ic_nav_arrive)
            navInstructionIcon.visibility = View.VISIBLE
            wakeDisplay()
        }
        override fun onStopped() {
            if (navUiActive) stopNavigationUi()
        }
    }

    private fun onNavProgress(p: Progress, route: RouteResult) {
        if (!navUiActive) startNavigationUi()
        if (p.arrived) return
        val step = if (p.nextStepIndex >= 0) route.steps.getOrNull(p.nextStepIndex) else null
        updateNavBar(step, p.distanceToNextM)
        updateNavBarProximityColor(p.distanceToNextM)
        if (p.anticipation) {
            showMessage("${Speech.shortDistance(p.distanceToNextM, useMetric)}: ${step?.instruction ?: "next change"}")
            wakeDisplay()
        }
        if (p.stepAdvanced) wakeDisplay()
        // Live remaining distance and arrival time.
        routeInfoDist.text = distanceLabel(p.remainingM)
        routeInfoEta.text = etaLabel(p.remainingS)
        LocationHub.lastGoodFix?.let { dimDisplayIfIdle(p.distanceToNextM, it) }
    }

    private fun updateNavBar(step: NavStep?, distance: Double?) {
        navInstructionBar.visibility = View.VISIBLE
        if (step == null) {
            navDistanceText.text = ""
            statusStrip.text = "Head to your destination"
            return
        }
        navDistanceText.text = Speech.shortDistance(distance ?: step.distanceMeters, useMetric).replace(" ", "")
        // Street name when known; the turn itself is shown by the icon.
        statusStrip.text = step.streetName.ifBlank { step.instruction }
        navInstructionIcon.setImageResource(navIconFor(step.maneuver))
        navInstructionIcon.visibility = View.VISIBLE
    }

    /** Maneuver type from the route data (not guessed from English text).
     *  Uses the existing icon set; slight and keep turns share the turn icon. */
    private fun navIconFor(m: Maneuver): Int = when (m) {
        Maneuver.LEFT, Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> R.drawable.ic_nav_left
        Maneuver.RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> R.drawable.ic_nav_right
        Maneuver.SHARP_LEFT -> R.drawable.ic_nav_sharp_left
        Maneuver.SHARP_RIGHT -> R.drawable.ic_nav_sharp_right
        Maneuver.ROUNDABOUT_ENTER, Maneuver.ROUNDABOUT_EXIT -> R.drawable.ic_nav_roundabout
        Maneuver.UTURN -> R.drawable.ic_nav_uturn
        Maneuver.GOAL -> R.drawable.ic_nav_arrive
        Maneuver.STRAIGHT, Maneuver.DEPART, Maneuver.UNKNOWN -> R.drawable.ic_nav_straight
    }

    /** Speed-adaptive zoom (Driving only, as before): 18 at a standstill
     *  down to 14 at ~120 km/h. Only re-zooms on a real change. */
    private fun adaptTrackingZoom(location: Location) {
        if (currentMode != ModeUi.DRIVING || !location.hasSpeed() || !puckActive()) return
        val lc = map?.locationComponent ?: return
        if (lc.cameraMode != CameraMode.TRACKING_GPS) return
        val zoom = (18.0 - location.speed * 3.6 / 30.0).coerceIn(14.0, 18.0)
        if (Math.abs(zoom - lastTrackingZoom) >= 0.3) {
            lastTrackingZoom = zoom
            lc.zoomWhileTracking(zoom, 1200L)
        }
    }

    private fun setNavigationDeclutter(navigating: Boolean) {
        val visibility = if (navigating) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mode_row).visibility = visibility
        findViewById<View>(R.id.waypoint_button).visibility = visibility
        findViewById<View>(R.id.search_button).visibility = visibility
        findViewById<View>(R.id.search_input).visibility = visibility
        findViewById<View>(R.id.route_options_button).visibility = visibility
        // preference_toggle stays visible on purpose: switching routes mid-trip.
        tollToggle.visibility = if (navigating || currentMode != ModeUi.DRIVING) View.GONE else View.VISIBLE
    }

    /** Full brightness on any warning, alert, or right after each turn. */
    private fun wakeDisplay() {
        if (!navUiActive) return
        isDisplayDimmed = false
        val params = window.attributes
        params.screenBrightness = 1.0f
        window.attributes = params
    }

    /** 50 % brightness on long boring stretches (60+ s to the next turn),
     *  never while GPS is degraded or the speed alarm is active. */
    private fun dimDisplayIfIdle(distanceToNextTurn: Double, location: Location) {
        if (!navUiActive || isDisplayDimmed) return
        if (isGpsDegraded) return
        if (isBlinking) return
        val hasReliableSpeed = location.hasSpeed() && location.speed > 1.0
        val etaSeconds = if (hasReliableSpeed) distanceToNextTurn / location.speed else Double.MAX_VALUE
        if (etaSeconds > 60.0) {
            isDisplayDimmed = true
            val params = window.attributes
            params.screenBrightness = 0.5f
            window.attributes = params
        }
    }

    /** Border blue (far) -> green -> yellow -> red (close to the turn). */
    private fun updateNavBarProximityColor(distanceToTurn: Double) {
        val background = navInstructionBar.background?.mutate() as? GradientDrawable ?: return
        val farDistance = 250.0
        val nearDistance = 20.0
        val fraction = ((farDistance - distanceToTurn) / (farDistance - nearDistance)).coerceIn(0.0, 1.0)
        val stops = arrayOf(
            intArrayOf(0x2B, 0x6C, 0xB0),
            intArrayOf(0x2E, 0x7D, 0x32),
            intArrayOf(0xF1, 0xC4, 0x0F),
            intArrayOf(0xC0, 0x39, 0x2B)
        )
        val segment = (fraction * (stops.size - 1)).coerceIn(0.0, (stops.size - 1).toDouble())
        val lowerIndex = segment.toInt().coerceIn(0, stops.size - 2)
        val localFraction = segment - lowerIndex
        val from = stops[lowerIndex]
        val to = stops[lowerIndex + 1]
        val r = (from[0] + (to[0] - from[0]) * localFraction).toInt()
        val g = (from[1] + (to[1] - from[1]) * localFraction).toInt()
        val b = (from[2] + (to[2] - from[2]) * localFraction).toInt()
        background.setStroke(dp(5), Color.rgb(r, g, b))
    }

    /** During a GPS gap (3-15 s), keeps the arrow and camera moving with the
     *  last speed and bearing -- visual only; navigation decisions stay on
     *  real fixes. */
    private val deadReckoningRunnable = object : Runnable {
        override fun run() {
            if (!navUiActive) return
            val gapMs = System.currentTimeMillis() - lastRealLocationAtMs
            val fix = LocationHub.lastGoodFix
            if (gapMs in 3000..15000 && fix != null && fix.hasSpeed() && fix.hasBearing() && fix.speed > 0.5f) {
                deadReckoningActive = true
                val projected = Geo.project(LatLon(fix.latitude, fix.longitude), fix.bearing.toDouble(), fix.speed * gapMs / 1000.0)
                val loc = Location(fix).apply {
                    latitude = projected.lat
                    longitude = projected.lon
                }
                if (puckActive()) map?.locationComponent?.forceLocationUpdate(loc)
            } else if (gapMs > 15000 && deadReckoningActive) {
                deadReckoningActive = false
                showMessage("GPS signal lost")
            }
            handler.postDelayed(this, 1000L)
        }
    }

    // ---------- POI layer ----------

    /** Category icons are multi-colour: status colours the backdrop, never
     *  the icon (tinting flattens the symbol). */
    private fun setPoiButtonStatus(button: ImageButton, found: Boolean?) {
        when (found) {
            true -> setBackdrop(button, activeColor)
            false -> setBackdrop(button, inactiveColor)
            null -> setBackdrop(button, backdropColor)
        }
        if (found == null) setIconActive(button, false) else button.imageTintList = null
    }

    private fun setupPoiToggle() {
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        button.setImageResource(currentPoiCategory.iconRes)
        setPoiButtonStatus(button, if (currentPoiCategory == PoiCategory.NONE) null else false)
        button.setOnClickListener {
            val categories = PoiCategory.values()
            currentPoiCategory = categories[(currentPoiCategory.ordinal + 1) % categories.size]
            button.setImageResource(currentPoiCategory.iconRes)
            saveToggleState()
            if (currentPoiCategory == PoiCategory.NONE) {
                poiRequestGeneration++
                setPoiButtonStatus(button, null)
                layers.setPois(emptyList())
                showMessage("POI layer off")
            } else {
                setPoiButtonStatus(button, false)
                showMessage("Checking for ${currentPoiCategory.label}...")
                queryPois(force = true)
            }
        }
    }

    /** Overpass query for the visible area (capped at ~25 km), repeated
     *  when the map moved more than ~1 km. Only the newest request may
     *  render, so categories never mix. */
    private fun queryPois(force: Boolean = false) {
        if (currentPoiCategory == PoiCategory.NONE) return
        val m = map ?: return
        val target = m.cameraPosition.target ?: return
        val center = LatLon(target.latitude, target.longitude)
        val now = System.currentTimeMillis()
        val movedFar = lastPoiQueryCenter?.let { Geo.distance(it, center) > 1000.0 } ?: true
        if (!force && (now - lastPoiQueryAtMs < 20000L || !movedFar)) return
        lastPoiQueryAtMs = now
        lastPoiQueryCenter = center

        val category = currentPoiCategory
        val thisGeneration = ++poiRequestGeneration
        val box = m.projection.visibleRegion.latLngBounds
        val maxSpanDegrees = 25000.0 / 111000.0
        val latSpan = (box.latitudeNorth - box.latitudeSouth).coerceAtMost(maxSpanDegrees)
        val lonSpan = (box.longitudeEast - box.longitudeWest).coerceIn(0.0, maxSpanDegrees)
        val south = center.lat - latSpan / 2
        val north = center.lat + latSpan / 2
        val west = center.lon - lonSpan / 2
        val east = center.lon + lonSpan / 2

        val query = "[out:json][timeout:12];node($south,$west,$north,$east)${category.overpassFilter};out body 30;"
        fetchFromOverpass(query, useMirror = false) { body, errorText ->
            if (thisGeneration != poiRequestGeneration) return@fetchFromOverpass
            val results = body?.let { try { parsePoiResults(it) } catch (e: Exception) { null } }
            runOnUiThread {
                if (thisGeneration != poiRequestGeneration || isDestroyed) return@runOnUiThread
                val button = findViewById<ImageButton>(R.id.poi_toggle_button)
                when {
                    body == null -> {
                        showError(errorText ?: "POI search failed")
                        setPoiButtonStatus(button, false)
                    }
                    results == null -> {
                        showError("Couldn't read POI response")
                        setPoiButtonStatus(button, false)
                    }
                    else -> {
                        layers.setPois(results.mapIndexed { i, p -> MapLayers.Pin("poi$i", p, category.iconName) })
                        if (results.isEmpty()) {
                            setPoiButtonStatus(button, false)
                            showMessage("No ${category.label} found nearby")
                        } else {
                            setPoiButtonStatus(button, true)
                            showMessage("${results.size} ${category.label} found")
                        }
                    }
                }
            }
        }
    }

    /** Overpass with one retry on a second mirror for transient failures. */
    private fun fetchFromOverpass(query: String, useMirror: Boolean, callback: (body: String?, errorText: String?) -> Unit) {
        val host = if (useMirror) "https://overpass.kumi.systems/api/interpreter?data=" else "https://overpass-api.de/api/interpreter?data="
        val request = Request.Builder()
            .url(host + java.net.URLEncoder.encode(query, "UTF-8"))
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Backpack-Android-App")
            .build()
        RoutePlanner.http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!useMirror) fetchFromOverpass(query, true, callback)
                else callback(null, "POI search failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val transient = response.code in setOf(429, 500, 502, 503, 504, 506)
                if (!response.isSuccessful && transient && !useMirror) {
                    response.close()
                    fetchFromOverpass(query, true, callback)
                    return
                }
                val body = response.use { it.body?.string() }
                if (!response.isSuccessful || body == null) {
                    callback(null, "POI search error (HTTP ${response.code})")
                    return
                }
                callback(body, null)
            }
        })
    }

    private fun parsePoiResults(responseBody: String): List<LatLon> {
        val elements = JSONObject(responseBody).getJSONArray("elements")
        val results = mutableListOf<LatLon>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val lat = e.optDouble("lat", Double.NaN)
            val lon = e.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) results.add(LatLon(lat, lon))
        }
        return results
    }

    // ---------- Mode / preference / toll / urgency ----------

    private fun setupModeButtons() {
        findViewById<ImageButton>(R.id.mode_toggle_button).setOnClickListener {
            val modes = ModeUi.values()
            selectMode(modes[(currentMode.ordinal + 1) % modes.size])
        }
    }

    private fun syncDrivingModeUI() {
        val driving = currentMode == ModeUi.DRIVING
        tollToggle.visibility = if (driving && !navUiActive) View.VISIBLE else View.GONE
        if (!driving) {
            speedSign.visibility = View.GONE
            stopBlinking()
        }
        refreshRouteOptionsButton()
    }

    private fun selectMode(mode: ModeUi) {
        if (currentMode == mode) return
        currentMode = mode
        refreshModeButtonHighlight()
        syncDrivingModeUI()
        saveToggleState()
        rerouteIfPossible("${mode.label} mode selected")
    }

    private fun refreshModeButtonHighlight() {
        val button = findViewById<ImageButton>(R.id.mode_toggle_button)
        button.setImageResource(currentMode.iconRes)
        setIconActive(button, true)
    }

    private fun setupPreferenceRow() {
        findViewById<ImageButton>(R.id.preference_toggle_button).setOnClickListener {
            val prefs = PreferenceUi.values()
            selectPreference(prefs[(currentPreference.ordinal + 1) % prefs.size])
        }
    }

    private fun selectPreference(preference: PreferenceUi) {
        if (currentPreference == preference) return
        currentPreference = preference
        refreshPreferenceHighlight()
        saveToggleState()
        rerouteIfPossible("${preference.label} selected")
    }

    private fun refreshPreferenceHighlight() {
        val button = findViewById<ImageButton>(R.id.preference_toggle_button)
        button.setImageResource(currentPreference.iconRes)
        setIconActive(button, true)
    }

    /** Backdrop colour, not icon tint: active red when tolls are allowed,
     *  grey when avoided. */
    private fun setTollButtonStatus(allowed: Boolean) = setBackdrop(tollToggle, if (allowed) activeColor else inactiveColor)

    private fun setupTollToggle() {
        tollToggle.setImageResource(if (avoidTolls) R.drawable.ic_toll_off else R.drawable.ic_toll_on)
        setTollButtonStatus(!avoidTolls)
        tollToggle.setOnClickListener {
            avoidTolls = !avoidTolls
            tollToggle.setImageResource(if (avoidTolls) R.drawable.ic_toll_off else R.drawable.ic_toll_on)
            setTollButtonStatus(!avoidTolls)
            saveToggleState()
            rerouteIfPossible(if (avoidTolls) "No toll selected" else "Toll roads allowed")
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
            val end = TripState.destination
            if (start != null && end != null && currentMode == ModeUi.DRIVING && currentPreference == PreferenceUi.RECOMMENDED) {
                showMessage("Finding route...")
                requestBestRoute(start, end)
            }
        }
    }

    private fun setupRouteOptionsButton() {
        findViewById<ImageButton>(R.id.route_options_button).setOnClickListener {
            RouteOptionsDialog.show(this, currentMode.mode) { opts ->
                routeOptions = opts
                refreshRouteOptionsButton()
                rerouteIfPossible(opts.summary(currentMode.mode))
            }
        }
        refreshRouteOptionsButton()
    }

    /** Red when this mode's options differ from the defaults. */
    private fun refreshRouteOptionsButton() {
        val button = findViewById<ImageButton>(R.id.route_options_button)
        val custom = routeOptions.summary(currentMode.mode) != RouteOptions().summary(currentMode.mode)
        setIconActive(button, custom)
    }

    private fun rerouteIfPossible(label: String) {
        val start = resolveStartPoint()
        val end = TripState.destination
        if (start != null && end != null) {
            showMessage("$label · finding route...")
            requestBestRoute(start, end)
        } else {
            showMessage(label)
        }
    }

    // ---------- Speed limit (unchanged rules) ----------

    /** Overpass maxspeed of the nearest road, at most every 15 s, Driving only. */
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
            val limit = try { parseMaxSpeed(body) } catch (e: Exception) { null }
            runOnUiThread {
                lastKnownSpeedLimit = limit
                LocationHub.lastFix?.let { if (currentMode == ModeUi.DRIVING) updateSpeedSignDisplay(it) }
            }
        }
    }

    private fun parseMaxSpeed(responseBody: String): Int? {
        val elements = JSONObject(responseBody).getJSONArray("elements")
        if (elements.length() == 0) return null
        val tags = elements.getJSONObject(0).optJSONObject("tags") ?: return null
        val raw = tags.optString("maxspeed", "").ifEmpty { return null }
        raw.toIntOrNull()?.let { return it }
        return when (raw) {
            "FR:motorway" -> 130
            "FR:rural" -> 80
            "FR:urban" -> 50
            "FR:zone30" -> 30
            else -> null
        }
    }

    /** Green 5+ under, yellow within 5 under, red up to 5 over, blinking
     *  red + high alarm beyond; blue + low alarm when 15+ under on a 100+
     *  road; red + low alarm when stopped on a 100+ road. */
    private fun updateSpeedSignDisplay(location: Location) {
        val limit = lastKnownSpeedLimit
        val speedKmh = (location.speed * 3.6).toInt()
        val hasRealMovement = location.hasSpeed() && speedKmh > 3
        speedSign.visibility = View.VISIBLE
        val background = speedSign.background?.mutate() as? GradientDrawable

        if (limit == null) {
            speedSignText.text = "--"
            background?.setStroke(dp(5), Color.parseColor("#9E9E9E"))
            stopBlinking()
            return
        }
        speedSignText.text = limit.toString()
        val diff = speedKmh - limit
        val stoppedOnHighway = !hasRealMovement && limit >= 100
        val tooSlowForHighway = hasRealMovement && limit >= 100 && diff <= -15
        when {
            stoppedOnHighway -> {
                background?.setStroke(dp(5), Color.parseColor("#C0392B"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            !hasRealMovement -> {
                background?.setStroke(dp(5), Color.parseColor("#9E9E9E"))
                stopBlinking()
            }
            tooSlowForHighway -> {
                background?.setStroke(dp(5), Color.parseColor("#1565C0"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            diff > 5 -> {
                startBlinking()
                maybeSoundAlarm(highPitch = true)
            }
            diff > 0 -> {
                background?.setStroke(dp(5), Color.parseColor("#C0392B"))
                stopBlinking()
            }
            diff > -5 -> {
                background?.setStroke(dp(5), Color.parseColor("#F1C40F"))
                stopBlinking()
            }
            else -> {
                background?.setStroke(dp(5), Color.parseColor("#2E7D32"))
                stopBlinking()
            }
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            val background = speedSign.background?.mutate() as? GradientDrawable
            background?.setStroke(dp(5), if (blinkOn) Color.parseColor("#C0392B") else Color.WHITE)
            blinkOn = !blinkOn
            handler.postDelayed(this, 400L)
        }
    }

    private fun startBlinking() {
        if (isBlinking) return
        isBlinking = true
        handler.post(blinkRunnable)
    }

    private fun stopBlinking() {
        if (!isBlinking) return
        isBlinking = false
        handler.removeCallbacks(blinkRunnable)
    }

    private fun maybeSoundAlarm(highPitch: Boolean) {
        wakeDisplay()
        val now = System.currentTimeMillis()
        if (now - lastAlarmAtMs < 3000L) return
        lastAlarmAtMs = now
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            toneGenerator?.startTone(if (highPitch) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_LOW_L, 200)
        } catch (e: Exception) {
            // The visual sign is still there.
        }
    }

    // ---------- Map taps ----------

    private fun handleDestinationTap(point: LatLon) {
        if (NavigationEngine.isActive) {
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
        TripState.destination = point
        refreshPins()
        showMessage("Finding route...")
        requestBestRoute(start, point)
    }

    private fun handleStartOverride(point: LatLon) {
        if (NavigationEngine.isActive) {
            showMessage("Stop navigation before changing your route")
            return
        }
        clearRoute()
        TripState.customStart = point
        refreshPins()
        showMessage("Custom start point set -- tap to choose a destination")
    }

    private fun clearRouteVisuals() {
        planner.cancel()
        TripState.plan = null
        TripState.selectedIndex = 0
        TripState.destination = null
        layers.clearRoutes()
        refreshPins()
    }

    private fun clearRoute() {
        planner.cancel()
        TripState.clear()
        layers.clearRoutes()
        refreshPins()
        routeInfoDist.text = ""
        routeInfoEta.text = ""
        routeInfoCost.text = ""
        routeInfo.visibility = View.GONE
        if (navUiActive) stopNavigationUi()
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.GONE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = View.GONE
        findViewById<LinearLayout>(R.id.route_options_row).apply {
            removeAllViews()
            visibility = View.GONE
        }
    }

    // ---------- Routing ----------

    private fun buildRequest(from: LatLon, to: LatLon): RouteRequest = RouteRequest(
        from = from,
        to = to,
        // Mid-trip: only the stops not visited yet.
        waypoints = NavigationEngine.remainingStops() ?: TripState.waypoints.toList(),
        mode = currentMode.mode,
        preference = currentPreference.pref,
        avoidTolls = avoidTolls,
        options = routeOptions,
        valuePerMinute = currentValuePerMinute(),
        tollRatePerKm = tollRatePerKm
    )

    private fun requestBestRoute(from: LatLon, to: LatLon) {
        val navigating = NavigationEngine.isActive
        // Mid-trip changes (preference, urgency) start from where you are now.
        val request = buildRequest(if (navigating) currentPosition() ?: from else from, to)
        planner.plan(request) { plan, error ->
            if (plan == null) {
                showError(error ?: "Route request failed")
                return@plan
            }
            TripState.plan = plan
            TripState.selectedIndex = plan.primaryIndex
            drawPlan(plan, plan.primaryIndex, fit = !navigating, note = plan.note)
            if (navigating && NavigationEngine.isActive) NavigationEngine.switchRoute(plan, plan.primaryIndex, request)
        }
    }

    private fun drawPlan(plan: RoutePlan, selectedIndex: Int, fit: Boolean, note: String?) {
        val results = plan.routes
        if (results.isEmpty() || results[0].points.isEmpty()) {
            showError("Route returned no geometry")
            return
        }
        val sel = selectedIndex.coerceIn(0, results.size - 1)
        TripState.selectedIndex = sel
        layers.setRoutes(results, sel)
        val primary = results[sel]

        routeInfoDist.text = distanceLabel(primary.distanceMeters)
        routeInfoEta.text = etaLabel(primary.durationSeconds)
        routeInfoCost.text = if (primary.tollDistanceMeters > 0) {
            "Cost: ~€%.0f".format(primary.tollDistanceMeters / 1000.0 * tollRatePerKm)
        } else {
            "Cost: --"
        }
        note?.let { showMessage(it) }
        routeInfo.visibility = View.VISIBLE
        populateRouteOptionsRow(results, sel)
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = View.VISIBLE

        if (fit) fitCamera(results)
    }

    private fun distanceLabel(m: Double): String {
        val km = m / 1000.0
        return if (useMetric) "Dist: %.2f Km".format(km) else "Dist: %.2f mi".format(km * 0.621371)
    }

    private fun etaLabel(seconds: Double): String {
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.SECOND, seconds.toInt())
        return "ETA: %02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** Shows the whole route (and alternatives) above the bottom controls. */
    private fun fitCamera(results: List<RouteResult>) {
        val m = map ?: return
        val all = results.flatMap { it.points }
        if (all.size < 2) return
        val bounds = LatLngBounds.Builder().includes(all.map { LatLng(it.lat, it.lon) }).build()
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(60), dp(170), dp(60), dp(170)), 800)
        } catch (e: Exception) { }
    }

    /** One chip per route: distance, duration, estimated toll, unpaved share. */
    private fun populateRouteOptionsRow(results: List<RouteResult>, selected: Int) {
        val row = findViewById<LinearLayout>(R.id.route_options_row)
        row.removeAllViews()
        if (results.size < 2) {
            row.visibility = View.GONE
            return
        }
        for ((index, result) in results.withIndex()) {
            val km = (result.distanceMeters / 1000.0).toInt()
            val totalMinutes = (result.durationSeconds / 60.0).toInt()
            val eta = "%d:%02d".format(totalMinutes / 60, totalMinutes % 60)
            val costPart = if (result.tollDistanceMeters > 0) {
                " · ~€%.0f".format(result.tollDistanceMeters / 1000.0 * tollRatePerKm)
            } else ""
            val unpavedPart = if (result.unpavedFraction >= 0.01) {
                " · %.0f%% unpaved".format(result.unpavedFraction * 100)
            } else ""
            val chip = TextView(this)
            chip.text = "${km}km · $eta$costPart$unpavedPart"
            chip.textSize = 13f
            chip.setPadding(dp(10), dp(6), dp(10), dp(6))
            chip.setTextColor(Color.WHITE)
            val pill = ContextCompat.getDrawable(this, R.drawable.route_pill_background)?.mutate() as? GradientDrawable
            pill?.setColor(ContextCompat.getColor(this, if (index == selected) R.color.rg_active_soft else R.color.rg_inactive_soft))
            chip.background = pill
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6); gravity = android.view.Gravity.END }
            chip.setOnClickListener { if (index != TripState.selectedIndex) promoteRouteToPrimary(index) }
            row.addView(chip)
        }
        row.visibility = View.VISIBLE
    }

    private fun promoteRouteToPrimary(index: Int) {
        val plan = TripState.plan ?: return
        if (index !in plan.routes.indices) return
        TripState.selectedIndex = index
        drawPlan(plan, index, fit = false, note = "Manually selected route")
        if (NavigationEngine.isActive) NavigationEngine.switchRoute(plan, index)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
