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
import com.backpack.app.core.NavLink
import com.backpack.app.core.PoiPlace
import com.backpack.app.core.RouteLabels
import com.backpack.app.core.Stops
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
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.search.SearchClient
import com.backpack.app.tourist.TouristFinder
import com.backpack.app.location.LocationHub
import com.backpack.app.map.MapLayers
import com.backpack.app.nav.NavigationEngine
import com.backpack.app.route.RoutePlanner
import com.backpack.app.places.PlacesFinder
import com.backpack.app.speed.SpeedLimitLookup
import com.backpack.app.ui.LetterBadge
import com.backpack.app.ui.Popup
import com.backpack.app.ui.SettingsPopup
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

/**
 * Backpack map screen (1.0.0: one button style, POI dropdown, fixed message
 * band, lettered routes, settings popup by category, place popups with
 * "Go here" / "Add stop" / read more, temporary detours with "resume",
 * Drive / Overview views, speed limits loaded per route).
 *
 * History (0.4.0):
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

    /** [orsIds]: openrouteservice place categories (second source when Overpass is busy). */
    private enum class PoiCategory(
        val overpassFilter: String, val iconRes: Int, val iconName: String, val label: String,
        val orsIds: Map<Int, String> = emptyMap()
    ) {
        NONE("", R.drawable.ic_poi_toggle, "", "Off"),
        /** Well-known places (same as Weekend mode), from Wikidata-ranked OSM data. */
        SIGHTS("", R.drawable.ic_tourist, "", "Sights"),
        FOOD("[\"amenity\"~\"^(restaurant|cafe|fast_food)$\"]", R.drawable.ic_poi_food, "poi_food", "Food",
            mapOf(570 to "restaurant", 564 to "cafe", 566 to "fast_food")),
        LODGING("[\"tourism\"~\"^(hotel|guest_house|motel)$\"]", R.drawable.ic_poi_lodging, "poi_lodging", "Lodging",
            mapOf(108 to "hotel", 106 to "guest_house", 109 to "motel")),
        PARKING("[\"amenity\"=\"parking\"]", R.drawable.ic_poi_parking, "poi_parking", "Parking", mapOf(601 to "parking")),
        FUEL("[\"amenity\"~\"^(fuel|charging_station)$\"]", R.drawable.ic_poi_fuel, "poi_fuel", "Fuel/Charging",
            mapOf(596 to "fuel", 593 to "charging_station")),
        TOILETS("[\"amenity\"=\"toilets\"]", R.drawable.ic_poi_toilets, "poi_toilets", "Toilets", mapOf(179 to "toilets")),
        EMERGENCY("[\"amenity\"~\"^(hospital|police|fire_station)$\"]", R.drawable.ic_poi_emergency, "poi_emergency", "Emergency",
            mapOf(206 to "hospital", 369 to "police", 367 to "fire_station"));

        fun finderCategory() = PlacesFinder.Category(name, overpassFilter, orsIds)
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
    /** 1.0.4: instruction box = turn row + distance/arrival/cost line. */
    private lateinit var navBox: View
    private lateinit var routeInfo: View
    private lateinit var routeInfoDist: TextView
    private lateinit var routeInfoEta: TextView
    private lateinit var routeInfoCost: TextView
    private lateinit var tollToggle: ImageButton
    private lateinit var urgencyButton: ImageButton
    private lateinit var speedWidget: FrameLayout
    private lateinit var speedSign: View
    private lateinit var speedSignText: TextView
    private lateinit var speedUnitText: TextView
    private lateinit var speedLimitSign: TextView
    private lateinit var messageBubble: TextView
    private lateinit var errorBubble: TextView

    private var currentMode = ModeUi.WALKING
    private var currentPreference = PreferenceUi.RECOMMENDED
    private var avoidTolls = false
    private var urgencyMode = UrgencyMode.WEEKEND
    private var currentPoiCategory = PoiCategory.NONE
    private var routeOptions = RouteOptions()

    // From Settings.
    private var valuePerMinuteWeekend = Settings.DEFAULT_VALUE_WEEKEND / 60.0
    private var valuePerMinuteWork = Settings.DEFAULT_VALUE_WORK / 60.0
    private var valuePerMinuteEmergency = Settings.DEFAULT_VALUE_EMERGENCY / 60.0
    private var tollRatePerKm = Settings.DEFAULT_TOLL_RATE
    private var useMetric = true

    private var waypointModeArmed = false
    private val maxWaypoints = Stops.MAX

    // Navigation screen state (the navigation itself lives in NavigationEngine).
    private var navUiActive = false
    private var lastRealLocationAtMs = 0L
    private var deadReckoningActive = false
    private var isGpsDegraded = false
    private var isDisplayDimmed = false
    private var lastTrackingZoom = 0.0

    // Speed limit (loaded per route by SpeedLimitLookup).
    private var lastKnownSpeedLimit: Int? = null
    private var lastSpeedLimitOkAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = true
    private var isBlinking = false
    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmAtMs = 0L

    // POIs.
    private var poiRequestGeneration = 0
    private var lastPoiQueryAtMs = 0L
    private var lastPoiQueryCenter: LatLon? = null
    private val placesFinder by lazy { PlacesFinder(this) }
    private var poiFailures = 0
    private var poiRetryAtMs = 0L
    /** Everyday places on the map (tap one for its popup). */
    private var poiResults: List<PoiPlace> = emptyList()

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
        navBox = findViewById(R.id.nav_box)
        routeInfo = findViewById(R.id.route_info)
        routeInfoDist = findViewById(R.id.route_info_dist)
        routeInfoEta = findViewById(R.id.route_info_eta)
        routeInfoCost = findViewById(R.id.route_info_cost)
        tollToggle = findViewById(R.id.toll_toggle)
        urgencyButton = findViewById(R.id.urgency_button)
        speedWidget = findViewById(R.id.speed_widget)
        speedSign = findViewById(R.id.speed_sign)
        speedSignText = findViewById(R.id.speed_sign_text)
        speedUnitText = findViewById(R.id.speed_unit_text)
        speedLimitSign = findViewById(R.id.speed_limit_sign)
        messageBubble = findViewById(R.id.message_bubble)
        errorBubble = findViewById(R.id.error_bubble)
        applyBubbleColors()
        var lastBarHeight = 0
        navBox.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height != lastBarHeight) { lastBarHeight = v.height; v.post { placeLayout() } }
        }
        // Speed number centred on its digits, not on the font's line box.
        speedSignText.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> centerDigits(v as TextView) }

        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { showError("Map could not load -- check the connection") }
        mapView.getMapAsync { m -> onMapReady(m) }

        findViewById<ImageButton>(R.id.locate_button).apply {
            setIconActive(this, false)
            setOnClickListener { centerOnMyLocation() }
        }
        findViewById<ImageButton>(R.id.overview_button).apply {
            setIconActive(this, false)
            setOnClickListener { if (overviewOn) leaveOverview() else enterOverview() }
        }
        findViewById<ImageButton>(R.id.resume_button).apply {
            setIconActive(this, false)
            setOnClickListener { resumePreviousRoute() }
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
        setupAltRouteButton()
        styleBottomButtons()
        refreshResumeButton()
        watchKeyboard()
        placeLayout()
        touristWasOn = touristOn
        refreshModeButtonHighlight()
        refreshPreferenceHighlight()
        syncDrivingModeUI()

        if (!LocationHub.hasPermission(this)) {
            requestLocationPermission()
        }
        // The screen was rebuilt while a trip change waited for its route
        // (that request was cancelled): back to the trip being followed.
        if (TripState.changePending) {
            TripState.committed?.let { TripState.restore(it) }
            TripState.changePending = false
            refreshResumeButton()
        }
        // Opened by another app with a place ("navigate here"): only on a real
        // start (not a rebuild, not a relaunch from Recents), handled once the
        // map is ready so the camera can show the place and the route.
        if (savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) startLink = intent
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (map != null) handleNavLink(intent) else startLink = intent
    }

    /** Link that opened the app, handled when the map is ready. */
    private var startLink: Intent? = null

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

    /** Settings popup open: the automatic day/night switch waits (it would rebuild the screen under it). */
    private var settingsOpen = false

    /** Auto night mode: re-checked every minute (sunset / sunrise). */
    private val nightCheckRunnable = object : Runnable {
        override fun run() {
            if (!settingsOpen) Prefs.applyNightMode(this@MainActivity)
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(nightCheckRunnable)
        handler.post(nightCheckRunnable)
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
        handler.removeCallbacks(nightCheckRunnable)
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
        // Leaving the app (not a rebuild): a place still waiting for GPS is dropped,
        // it must not come back unasked at the next start.
        if (isFinishing) {
            TripState.pendingLink = null
            TripState.pendingLinkMode = null
        }
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
        // No compass: during navigation the locate button switches
        // "your direction up" / "north up"; outside navigation north is up.
        m.uiSettings.isCompassEnabled = false
        // Left side, below the mode row: the right column is full of buttons.

        val saved = Prefs.camera(this)
        val start = when {
            saved != null -> LatLng(saved.first, saved.second)
            else -> LocationHub.lastKnownGps(this)?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(60.3913, 5.3221)
        }
        m.cameraPosition = CameraPosition.Builder().target(start).zoom(saved?.third ?: 12.0).build()

        m.addOnMapClickListener { p ->
            hideSuggestions()
            // A tap on the map closes the search box, nothing else.
            if (searchOpen) { closeSearch(); return@addOnMapClickListener true }
            val screen = m.projection.toScreenLocation(p)
            val touristKey = layers.touristAt(m, screen)
            val tourist = touristKey?.let { k -> touristResults.firstOrNull { it.key == k } }
            if (tourist != null) {
                showTouristPlace(tourist)
                return@addOnMapClickListener true
            }
            val poiKey = layers.poiAt(m, screen)
            val poi = poiKey?.let { k -> poiResults.firstOrNull { it.key == k } }
            if (poi != null) {
                showPoiPlace(poi)
                return@addOnMapClickListener true
            }
            // While navigating, grey lines are only offered from "find alternative route".
            val choosing = !NavigationEngine.isActive || altChoiceOpen
            val idx = if (choosing) layers.letterAt(m, screen) ?: layers.alternativeAt(m, screen) else null
            if (idx != null) {
                if (idx != TripState.selectedIndex) promoteRouteToPrimary(idx)
            } else if (!overviewOn) {
                handleDestinationTap(LatLon(p.latitude, p.longitude))
            }
            true
        }
        // Overview: any touch of the map restarts its 20 s.
        m.addOnCameraMoveStartedListener { reason ->
            if (overviewOn && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) scheduleOverviewReturn()
        }
        m.addOnMapLongClickListener { p ->
            handleStartOverride(LatLon(p.latitude, p.longitude))
            true
        }
        m.addOnCameraIdleListener {
            queryPois()
            if (TripState.plan == null) refreshTourist()
        }
        loadStyle(m)
        // A place from another app still waiting for GPS (screen rebuilt
        // meanwhile) -- unless a newer link just arrived.
        if (startLink == null) TripState.pendingLink?.let { (point, _) ->
            m.cameraPosition = CameraPosition.Builder().target(LatLng(point.lat, point.lon)).zoom(14.0).build()
        }
        startLink?.let { startLink = null; handleNavLink(it) }
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
            // Position arrow 170 % (Alex, 0.7.0 test), same size at every zoom.
            .minZoomIconScale(1.7f)
            .maxZoomIconScale(1.7f)
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
        if (touristOn) layers.setTourist(touristResults)
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
            // A place from another app was waiting for the first position.
            TripState.pendingLink?.let { (point, label) ->
                val mode = TripState.pendingLinkMode
                TripState.pendingLink = null
                TripState.pendingLinkMode = null
                placeLinkDestination(point, label, mode)
            }
            if (puckActive()) map?.locationComponent?.forceLocationUpdate(withHeading(location))
            if (currentMode == ModeUi.DRIVING) {
                updateSpeedLimit(location)
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
        if (navUiActive && overviewOn) {
            leaveOverview()
            return
        }
        if (navUiActive && puckActive()) {
            // Following: switch "your direction up" <-> "north up".
            // Map moved by hand: follow again, same orientation as before.
            val following = m.locationComponent.cameraMode != CameraMode.NONE
            if (following) northUp = !northUp
            followPosition(null)
            showMessage(if (northUp) "North up" else "Your direction up")
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
        val defaultPoi = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
            .getString(Settings.KEY_DEFAULT_POI, "NONE")
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
        val prefs = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
        useMetric = prefs.getBoolean(Settings.KEY_UNIT_METRIC, true)
        valuePerMinuteWeekend = prefs.getFloat(Settings.KEY_VALUE_WEEKEND, Settings.DEFAULT_VALUE_WEEKEND.toFloat()).toDouble() / 60.0
        valuePerMinuteWork = prefs.getFloat(Settings.KEY_VALUE_WORK, Settings.DEFAULT_VALUE_WORK.toFloat()).toDouble() / 60.0
        valuePerMinuteEmergency = prefs.getFloat(Settings.KEY_VALUE_EMERGENCY, Settings.DEFAULT_VALUE_EMERGENCY.toFloat()).toDouble() / 60.0
        tollRatePerKm = prefs.getFloat(Settings.KEY_TOLL_RATE, Settings.DEFAULT_TOLL_RATE.toFloat()).toDouble()
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

    /** Routine messages: always in the message band (between the arrow and
     *  the bottom buttons), under any error. */
    private fun showMessage(text: String) {
        bubbleFadeOutRunnable?.let { handler.removeCallbacks(it) }
        messageBubble.text = text
        messageBubble.visibility = View.VISIBLE
        messageBubble.animate().cancel()
        messageBubble.alpha = 0f
        messageBubble.animate().alpha(1f).setDuration(200).start()
        val fadeOut = Runnable {
            messageBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                messageBubble.visibility = View.GONE
            }.start()
        }
        bubbleFadeOutRunnable = fadeOut
        handler.postDelayed(fadeOut, 3000L)
    }

    private var errorFadeOutRunnable: Runnable? = null

    /** Genuine failures, in the same band, just above the routine message.
     *  With [retry], the bubble says "tap to retry" and stays a little longer. */
    private fun showError(text: String, retry: (() -> Unit)? = null) {
        errorFadeOutRunnable?.let { handler.removeCallbacks(it) }
        errorBubble.text = if (retry != null) "$text \u00b7 tap to retry" else text
        if (retry != null) {
            errorBubble.isClickable = true
            errorBubble.setOnClickListener {
                errorBubble.visibility = View.GONE
                errorBubble.setOnClickListener(null)
                errorBubble.isClickable = false
                retry()
            }
        } else {
            errorBubble.setOnClickListener(null)
            errorBubble.isClickable = false
        }
        errorBubble.visibility = View.VISIBLE
        errorBubble.animate().cancel()
        errorBubble.alpha = 0f
        errorBubble.animate().alpha(1f).setDuration(200).start()
        val fadeOut = Runnable {
            errorBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                errorBubble.visibility = View.GONE
                errorBubble.setOnClickListener(null)
                errorBubble.isClickable = false
            }.start()
        }
        errorFadeOutRunnable = fadeOut
        handler.postDelayed(fadeOut, if (retry != null) 7000L else 4000L)
    }

    private fun setIconActive(button: ImageButton, active: Boolean) {
        button.imageTintList = ColorStateList.valueOf(if (active) activeColor else inactiveColor)
    }

    /**
     * Status ring on the standard grey backdrop (RedGRPS map-control look):
     * red when [on], the faint dark edge otherwise. The backdrop itself stays
     * the same light grey on every button, so no button turns dark.
     */
    private fun setStatusRing(button: ImageButton, on: Boolean) {
        (button.background?.mutate() as? GradientDrawable)?.setStroke(
            if (on) dp(2) else Math.max(1, dp(1) * 3 / 4),
            if (on) activeColor else ContextCompat.getColor(this, R.color.rg_control_edge)
        )
    }

    /** Go / Stop: the main action, RedGRPS "Start sharing" look (dark red,
     *  white icon). Clear: standard grey button. */
    private fun styleBottomButtons() {
        findViewById<ImageButton>(R.id.nav_go_button).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.go_backdrop)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        setIconActive(findViewById(R.id.clear_route_button), false)
    }

    /** Puts the digits of [tv] exactly in the middle of its view (and so of the circle). */
    private fun centerDigits(tv: TextView) {
        if (tv.height == 0 || tv.layout == null) return
        val b = android.graphics.Rect()
        tv.paint.getTextBounds("0123456789", 0, 10, b)
        val glyphCenter = tv.baseline + b.exactCenterY()
        val shift = tv.height / 2f - glyphCenter
        if (Math.abs(tv.translationY - shift) > 0.5f) tv.translationY = shift
    }

    private fun showCredits() {
        val p = Popup(this, "Map and data credits")
        p.text("Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).")
        p.text("Map tiles: OpenFreeMap © OpenMapTiles.")
        p.text("Routing and address search: openrouteservice.org by HeiGIT.")
        p.text("Places and speed limits: Overpass API (OpenStreetMap data); openrouteservice places as a second source.")
        p.text("Well-known places: Wikidata (CC0) and Wikipedia.")
        p.text("Search: Photon (komoot) and Nominatim (OpenStreetMap Foundation).")
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

    private val searchClient = SearchClient()
    private var suggestRunnable: Runnable? = null

    private fun setupAddressSearch() {
        val searchButton = findViewById<ImageButton>(R.id.search_button)
        val searchInput = findViewById<EditText>(R.id.search_input)
        setIconActive(searchButton, false)
        // 1.0.4: the magnifier opens / closes the floating search box.
        searchButton.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSuggestions()
        }
        // Suggestions while typing (Photon), 350 ms after the last key.
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                suggestRunnable?.let { handler.removeCallbacks(it) }
                val text = e?.toString()?.trim().orEmpty()
                if (text.length < 3 || !searchInput.hasFocus()) {
                    searchClient.cancel()
                    hideSuggestions()
                    return
                }
                val r = Runnable {
                    searchClient.suggest(text, currentPosition() ?: cameraCenter()) { places, _ ->
                        if (searchInput.hasFocus() && searchInput.text.toString().trim() == text) showSuggestions(places)
                    }
                }
                suggestRunnable = r
                handler.postDelayed(r, 350L)
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val text = searchInput.text.toString().trim()
                if (text.isNotEmpty()) submitSearch(text)
                true
            } else {
                false
            }
        }
    }

    private var searchOpen = false

    /** Search box shown just over the keyboard, keyboard opened. */
    private fun openSearch() {
        searchOpen = true
        val searchInput = findViewById<EditText>(R.id.search_input)
        searchInput.visibility = View.VISIBLE
        setIconActive(findViewById(R.id.search_button), true)
        placeLayout()
        searchInput.requestFocus()
        searchInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Result picked, map tapped or magnifier again: box and keyboard away. */
    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        suggestRunnable?.let { handler.removeCallbacks(it) }
        hideSuggestions()
        closeSearchKeyboard()
        findViewById<View>(R.id.search_input).visibility = View.GONE
        setIconActive(findViewById(R.id.search_button), false)
        placeLayout()
    }

    /** Enter: first result straight away (as before). */
    private fun submitSearch(text: String) {
        suggestRunnable?.let { handler.removeCallbacks(it) }
        showMessage("Searching...")
        closeSearch()
        searchClient.search(text, currentPosition() ?: cameraCenter()) { places, error ->
            val first = places.firstOrNull()
            if (first == null) showError(error ?: "Address not found") else goToPlace(first)
        }
    }

    // ---------- Places handed over by other apps ----------

    /**
     * geo: / google.navigation: / Google Maps links: the place becomes the
     * destination and the route is shown -- you check it (vehicle, options...)
     * and press Go. While navigating, you are asked first.
     */
    private fun handleNavLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || intent.data == null) return
        val link = NavLink.parse(intent.dataString)
        // Handled once: a later rebuild or resume does not repeat it.
        intent.action = null
        if (link == null) {
            showError("This place link can't be read by Backpack")
            return
        }
        if (NavigationEngine.isActive) {
            val p = Popup(this, "New destination")
            p.text("${link.label ?: link.query ?: "A place"} was sent by another app. Navigate there instead?")
            p.button("Go here", primary = true) { p.dismiss(); resolveLink(link) }
            p.button("Cancel") { p.dismiss() }
            p.show()
            return
        }
        resolveLink(link)
    }

    private fun resolveLink(link: NavLink) {
        val point = link.point
        if (point != null) {
            placeLinkDestination(point, link.label ?: "%.5f, %.5f".format(java.util.Locale.ROOT, point.lat, point.lon), link.mode)
            return
        }
        val query = link.query ?: return
        showMessage("Looking for $query...")
        searchClient.search(query, currentPosition() ?: cameraCenter()) { places, error ->
            val first = places.firstOrNull()
            if (first == null) showError(error ?: "Place not found: $query")
            else placeLinkDestination(first.point, link.label ?: first.name, link.mode)
        }
    }

    private fun placeLinkDestination(point: LatLon, label: String, mode: com.backpack.app.core.TravelMode?) {
        // The sender's travel mode (walk / bike / car), set just before routing.
        fun applyMode() = mode?.let { m -> ModeUi.values().firstOrNull { it.mode == m }?.let { setModeQuietly(it) } }
        if (NavigationEngine.isActive) {
            applyMode()
            goHere(point, label)
            return
        }
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 14.0))
        if (resolveStartPoint() == null) {
            TripState.pendingLink = point to label
            TripState.pendingLinkMode = mode
            showMessage("$label \u00b7 waiting for GPS to plan the route...")
            return
        }
        applyMode()
        // A destination, never a stop, even if stop mode was armed.
        if (waypointModeArmed) {
            waypointModeArmed = false
            setIconActive(findViewById(R.id.waypoint_button), false)
        }
        handleDestinationTap(point)
        showMessage("$label \u00b7 check the route, then Go")
    }

    /** Travel mode set without rerouting (a new destination follows). */
    private fun setModeQuietly(mode: ModeUi) {
        if (currentMode == mode) return
        currentMode = mode
        refreshModeButtonHighlight()
        syncDrivingModeUI()
        saveToggleState()
    }

    /** Planning: the place becomes the destination. Navigating: its popup
     *  (Go here / Add stop), the trip is not replaced by itself. */
    private fun goToPlace(place: com.backpack.app.core.Place) {
        closeSearch()
        val point = place.point
        if (NavigationEngine.isActive) {
            if (overviewOn) scheduleOverviewReturn()
            showPlacePopup(place.name, place.detail.ifBlank { place.name }, point, null, "")
            return
        }
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 14.0))
        handleDestinationTap(point)
    }

    private fun closeSearchKeyboard() {
        val searchInput = findViewById<EditText>(R.id.search_input)
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showSuggestions(places: List<com.backpack.app.core.Place>) {
        val scroll = findViewById<View>(R.id.search_results_scroll)
        val list = findViewById<LinearLayout>(R.id.search_results)
        list.removeAllViews()
        if (places.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        val textColor = ContextCompat.getColor(this, R.color.rg_text)
        val detailColor = ContextCompat.getColor(this, R.color.rg_text_secondary)
        for ((i, place) in places.withIndex()) {
            if (i > 0) list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.rg_divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            val row = TextView(this)
            val title = android.text.SpannableString(place.name + if (place.detail.isNotBlank()) "\n" + place.detail else "")
            title.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, place.name.length, 0)
            title.setSpan(android.text.style.ForegroundColorSpan(detailColor), place.name.length, title.length, 0)
            title.setSpan(android.text.style.RelativeSizeSpan(0.85f), place.name.length, title.length, 0)
            row.text = title
            row.setTextColor(textColor)
            row.textSize = 15f
            row.maxLines = 3
            row.setPadding(dp(12), dp(7), dp(12), dp(7))
            row.setOnClickListener { goToPlace(place) }
            list.addView(row)
        }
        scroll.visibility = View.VISIBLE
        // Never taller than about 5 rows.
        scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (places.size > 5) dp(5 * 52) else FrameLayout.LayoutParams.WRAP_CONTENT
        }
    }

    private fun hideSuggestions() {
        findViewById<View>(R.id.search_results_scroll).visibility = View.GONE
    }

    private fun cameraCenter(): LatLon? = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }

    // ---------- Tourist mode ----------

    private val touristFinder by lazy { TouristFinder(this) }
    private val detourPlanner by lazy { RoutePlanner(this) }
    /** Well-known places are shown in Weekend mode or with the Sights POI category. */
    private val touristOn: Boolean
        get() = urgencyMode == UrgencyMode.WEEKEND || currentPoiCategory == PoiCategory.SIGHTS
    // Results live in TripState: a rotation or day/night switch keeps them.
    private var touristResults: List<TouristPoi>
        get() = TripState.touristResults
        set(v) { TripState.touristResults = v }
    private var touristRouteKey: Any?
        get() = TripState.touristRouteKey
        set(v) { TripState.touristRouteKey = v }
    private var touristAreaCenter: LatLon?
        get() = TripState.touristAreaCenter
        set(v) { TripState.touristAreaCenter = v }

    // Search in flight (not stored in TripState: a rebuilt screen searches again).
    private var touristPendingRoute: Any? = null
    private var touristPendingCenter: LatLon? = null

    /** Places-on state the screen last acted on (Weekend or Sights). */
    private var touristWasOn = false

    /** Called after Weekend or the POI category changed: acts only when
     *  "well-known places on" actually switched (no new search when going
     *  from Fuel to Food in Weekend, for example). */
    private fun touristModeChanged() {
        val now = touristOn
        if (now == touristWasOn) {
            if (now && currentPoiCategory == PoiCategory.SIGHTS) {
                setPoiButtonStatus(findViewById(R.id.poi_toggle_button), touristResults.isNotEmpty())
            }
            return
        }
        touristWasOn = now
        if (now) {
            refreshTourist(force = true)
        } else {
            touristFinder.cancel()
            touristPendingRoute = null
            touristPendingCenter = null
            touristResults = emptyList()
            TripState.touristCandidates = emptyList()
            touristRouteKey = null
            touristAreaCenter = null
            layers.setTourist(emptyList())
        }
    }

    private fun sightsMaxFraction(): Double =
        getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
            .getInt(Settings.KEY_SIGHTS_MAX_PCT, Settings.DEFAULT_SIGHTS_MAX_PCT) / 100.0

    private fun onPlacesServerSwitch() = showMessage("Places server busy, trying another...")

    /** Along the routes of the current plan when there is one (all
     *  alternatives in Weekend, to pick the sights route), else in the
     *  visible area (re-searched when the map moved more than ~1 km). */
    private fun refreshTourist(force: Boolean = false, extend: Boolean = false) {
        if (!touristOn) return
        val m = map ?: return
        val plan = TripState.plan
        val route = selectedRoute()
        if (plan != null && route != null && route.points.size >= 2) {
            if (!force && !extend && touristRouteKey === plan) {
                // Same plan, maybe another route chosen: re-rank locally, no network.
                showTouristFor(route)
                return
            }
            if (!force && touristPendingRoute === plan) return
            touristPendingRoute = plan
            touristPendingCenter = null
            val scenic = urgencyMode == UrgencyMode.WEEKEND && !NavigationEngine.isActive && !plan.tollDecision &&
                plan.routes.size > 1 && sightsMaxFraction() > 0.0
            // All routes of the plan before Go (picking another chip then
            // needs no new search); only the followed one while navigating.
            val lines = if (navUiActive) listOf(route.points) else plan.routes.map { it.points }
            val startIndex = TripState.selectedIndex
            // While navigating: from where you are (the squares behind you are not needed).
            val fromM = if (navUiActive) (NavigationEngine.lastProgress?.alongM ?: 0.0) else 0.0
            touristFinder.alongRoutes(lines, currentMode.mode, fromM, { onPlacesServerSwitch() }) { all, error, coveredM ->
                // Only a search that answered counts as covering the stretch;
                // a failed one is tried again 2 min later, not on every fix.
                if (all != null && error == null) TripState.touristCoveredM = coveredM
                else touristRetryAfterMs = System.currentTimeMillis() + 120_000L
                onTouristCandidates(all, error, plan, null, announce = !extend, scenic = scenic && !extend,
                    startIndex = startIndex, keepOnFailure = extend)
            }
            return
        }
        val target = m.cameraPosition.target ?: return
        val center = LatLon(target.latitude, target.longitude)
        val near = { c: LatLon? -> c != null && Geo.distance(c, center) < 1000.0 }
        if (!force && touristRouteKey == null && (near(touristAreaCenter) || near(touristPendingCenter))) return
        touristPendingRoute = null
        touristPendingCenter = center
        val box = m.projection.visibleRegion.latLngBounds
        val maxSpan = 25000.0 / 111000.0
        val latSpan = (box.latitudeNorth - box.latitudeSouth).coerceIn(0.0, maxSpan)
        val lonSpan = (box.longitudeEast - box.longitudeWest).coerceIn(0.0, maxSpan)
        touristFinder.inBox(center.lat - latSpan / 2, center.lon - lonSpan / 2, center.lat + latSpan / 2, center.lon + lonSpan / 2,
            { if (force) onPlacesServerSwitch() }) { all, error, _ ->
            onTouristCandidates(all, error, null, center, announce = force, scenic = false)
        }
    }

    private fun onTouristCandidates(
        all: List<TouristPoi>?, error: String?, plan: RoutePlan?, center: LatLon?,
        announce: Boolean, scenic: Boolean, startIndex: Int = -1, keepOnFailure: Boolean = false
    ) {
        if (!touristOn || isDestroyed) return
        touistClearPending()
        if (all == null && keepOnFailure) return // the places already shown stay; retried later
        if (all == null) {
            // Remember the failed search too: no retry on every map move.
            touristResults = emptyList()
            TripState.touristCandidates = emptyList()
            touristRouteKey = plan
            touristAreaCenter = center
            layers.setTourist(emptyList())
            if (currentPoiCategory == PoiCategory.SIGHTS) setPoiButtonStatus(findViewById(R.id.poi_toggle_button), false)
            showError(error ?: "Places search failed", retry = { refreshTourist(force = true) })
            return
        }
        TripState.touristCandidates = all
        touristRouteKey = plan
        touristAreaCenter = center
        var note: String? = null
        // The sights route is chosen once per plan, only before Go, and never
        // over a route the user picked while the search was running.
        if (scenic && plan != null && plan === TripState.plan && !NavigationEngine.isActive &&
            TripState.selectedIndex == startIndex && TripState.scenicCheckedPlan !== plan
        ) {
            TripState.scenicCheckedPlan = plan
            val choice = Tourist.scenicChoice(plan.routes, TripState.selectedIndex, all,
                Tourist.corridorRadius(currentMode.mode), sightsMaxFraction())
            if (choice != null) {
                drawPlan(plan, choice.index, fit = false, note = null, tourist = false)
                note = "Weekend sights route: ${choice.places} well-known places instead of ${choice.basePlaces}, +" +
                    Tourist.minutesLabel(choice.extraSeconds)
            }
        }
        val shown = showTouristFor(if (plan != null) selectedRoute() else null)
        // Part of the squares could not be fetched: say so, keep what was found.
        if (error != null) showError("Some places may be missing ($error)", retry = { refreshTourist(force = true) })
        if (!announce) return
        showMessage(note ?: when {
            shown.isEmpty() -> if (plan != null) "No well-known places along this route" else "No well-known places here"
            plan != null -> "${shown.size} well-known places along your route (hold the button for the list)"
            else -> "${shown.size} well-known places here"
        })
    }

    /** Well-known places are searched up to a distance ahead; while driving
     *  on, the next stretch is searched before you reach the end of it. */
    private var touristRetryAfterMs = 0L

    private fun extendTouristIfNeeded(p: Progress, route: RouteResult) {
        if (!touristOn || touristPendingRoute != null || TripState.plan == null) return
        if (System.currentTimeMillis() < touristRetryAfterMs) return
        val covered = TripState.touristCoveredM
        if (covered >= route.line.length - 1.0) return
        if (p.alongM < covered - Tourist.searchAheadM(currentMode.mode) * 0.3) return
        refreshTourist(extend = true)
    }

    private fun touistClearPending() {
        touristPendingRoute = null
        touristPendingCenter = null
    }

    /** Ranks the stored candidates for [route] (or the area) and shows them. */
    private fun showTouristFor(route: RouteResult?): List<TouristPoi> {
        val all = TripState.touristCandidates
        val radius = Tourist.corridorRadius(currentMode.mode)
        val shown = if (route != null) {
            Tourist.rank(Tourist.placeOnRoute(all, route.line).filter { it.offsetM <= radius * 1.05 }, TouristFinder.ROUTE_LIMIT)
        } else {
            Tourist.rank(all, TouristFinder.AREA_LIMIT)
        }
        touristResults = shown
        layers.setTourist(shown)
        if (currentPoiCategory == PoiCategory.SIGHTS) setPoiButtonStatus(findViewById(R.id.poi_toggle_button), shown.isNotEmpty())
        return shown
    }

    private fun starsText(n: Int) = if (n == 0) "" else "\u2605".repeat(n) + " "

    private fun selectedRoute(): RouteResult? = TripState.plan?.routes?.getOrNull(TripState.selectedIndex)

    /** The route places are measured on: the one being navigated, else the selected one. */
    private fun touristRoute(): RouteResult? = (if (NavigationEngine.isActive) NavigationEngine.route else null) ?: selectedRoute()

    private fun aheadOnRoute(poi: TouristPoi): Pair<Double, Double>? = aheadOn(poi.point)

    private fun showTouristList() {
        val onRoute = touristRoute() != null && TripState.destination != null
        val p = Popup(this, if (onRoute) "Along your route" else "Well-known places here")
        // While navigating, places already behind you are left out.
        val rows = touristResults.map { it to aheadOnRoute(it) }
            .filter { (_, a) -> !onRoute || a == null || a.first > -50.0 }
            .sortedBy { (_, a) -> if (onRoute) a?.first ?: Double.MAX_VALUE else 0.0 }
        if (rows.isEmpty()) p.text("No places ahead on your route.")
        for ((poi, a) in rows) {
            val where = if (onRoute && a != null) {
                " \u00b7 in ${Speech.shortDistance(a.first.coerceAtLeast(0.0), useMetric)} \u00b7 \u2248 +" +
                    Tourist.minutesLabel(Tourist.estimateDetourSeconds(a.second, currentMode.mode))
            } else ""
            p.text("${starsText(poi.stars)}${poi.name}\n${poi.category.label}$where", 14f).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { p.dismiss(); showTouristPlace(poi) }
            }
        }
        p.button("Close", primary = true) { p.dismiss() }
        p.show()
    }

    private fun showTouristPlace(poi: TouristPoi) {
        val known = if (poi.sitelinks > 0) " \u00b7 written about on ${poi.sitelinks} Wikipedia/Wikimedia sites" else ""
        showPlacePopup(poi.name, "${starsText(poi.stars)}${poi.category.label}$known", poi.point,
            poi.readMoreUrl, if (poi.wikipediaUrl != null || poi.articleUrl != null) "Read about it on Wikipedia" else "Read about it on Wikidata")
    }

    private fun showPoiPlace(place: PoiPlace) {
        val kind = com.backpack.app.core.Places.kindLabel(place.kind)
        val detail = if (place.name.isBlank()) kind else kind + (place.openingHours?.let { " \u00b7 $it" } ?: "")
        val link = when {
            place.wikipedia != null -> "Read about it on Wikipedia"
            place.wikidata != null -> "Read about it on Wikidata"
            else -> "Website"
        }
        showPlacePopup(place.label, detail, place.point, place.readMoreUrl, link)
    }

    /**
     * One popup for every place (well-known places and POI categories):
     * what it is, where it is on your route with the extra time (estimate,
     * then the router's exact value), a link to read about it, and
     * Go here / Add stop.
     */
    private fun showPlacePopup(title: String, detail: String, point: LatLon, readMoreUrl: String?, linkLabel: String) {
        val p = Popup(this, title)
        p.text(detail)
        val route = touristRoute()
        val end = TripState.destination
        val ahead = aheadOn(point)
        val passed = NavigationEngine.isActive && ahead != null && ahead.first < -50.0
        if (route != null && end != null && ahead != null && !passed) {
            val estimate = Tourist.estimateDetourSeconds(ahead.second, currentMode.mode)
            val where = "In ${Speech.shortDistance(ahead.first.coerceAtLeast(0.0), useMetric)} on your route \u00b7 " +
                "${Speech.shortDistance(ahead.second, useMetric)} off it"
            val detourText = p.text("$where \u00b7 \u2248 +${Tourist.minutesLabel(estimate)}")
            // Exact extra time from the router, same rules as the trip.
            val start = (if (NavigationEngine.isActive) currentPosition() else null) ?: resolveStartPoint()
            val stops = Tourist.insertStop(NavigationEngine.remainingStops() ?: TripState.waypoints, point, route.line)
            val current = NavigationEngine.lastProgress?.remainingS ?: route.durationSeconds
            if (start != null) {
                detourPlanner.plan(buildRequest(start, end, stops)) { plan, _ ->
                    val r = plan?.routes?.getOrNull(plan.primaryIndex) ?: return@plan
                    val extra = (r.durationSeconds - current).coerceAtLeast(0.0)
                    detourText.text = "$where \u00b7 +${Tourist.minutesLabel(extra)} as a stop"
                }
            }
        } else if (passed) {
            p.text("Already passed on your route.")
        }
        // Tap to read about it in the browser.
        readMoreUrl?.let { p.link(linkLabel, it) }
        if (end != null && !passed) p.button("Add stop", primary = true) { p.dismiss(); addStopFlow(point, title) }
        p.button("Go here", primary = end == null) { p.dismiss(); goHere(point, title) }
        p.button("Close") { p.dismiss() }
        p.dialog.setOnDismissListener { detourPlanner.cancel() }
        p.show()
    }

    /** (metres ahead -- of you while navigating, else from the start --, metres off the route). */
    private fun aheadOn(point: LatLon): Pair<Double, Double>? {
        val r = touristRoute() ?: return null
        val snap = r.line.snap(point) ?: return null
        val progress = if (NavigationEngine.isActive) NavigationEngine.lastProgress?.alongM ?: 0.0 else 0.0
        return Pair(snap.alongM - progress, snap.offsetM)
    }

    /**
     * Adds [point] as a stop in route order. With 2 stops of your own already:
     * "Visit on the way" (a temporary detour that does not count, then the
     * original plan goes on) or "Replace last stop". Either way the trip
     * before is kept for "Resume previous route".
     */
    private fun addStopFlow(point: LatLon, name: String) {
        val end = TripState.destination ?: return
        val ahead = aheadOn(point)
        if (NavigationEngine.isActive && ahead != null && ahead.first < -50.0) {
            showMessage("$name is already behind you")
            return
        }
        val existing = NavigationEngine.remainingStops() ?: TripState.waypoints.toList()
        val line = touristRoute()?.line
        if (Stops.ownCount(existing, TripState.detour) < Stops.MAX) {
            changeTrip(end, Tourist.insertStop(existing, point, line), TripState.detour, remember = null,
                message = "$name added as a stop \u00b7 finding route...")
            return
        }
        val p = Popup(this, "Already ${Stops.MAX} stops")
        p.text("Visit $name on the way (a short detour, then your route goes on), or replace your last stop by it?")
        p.bodyButton("Visit on the way") {
            p.dismiss()
            changeTrip(end, Stops.withDetour(existing, TripState.detour, point, line), point, remember = existing,
                message = "Detour to $name \u00b7 finding route...")
        }
        p.bodyButton("Replace last stop") {
            p.dismiss()
            changeTrip(end, Stops.replaceLast(existing, TripState.detour, point, line), TripState.detour, remember = existing,
                message = "$name replaces your last stop \u00b7 finding route...")
        }
        p.button("Cancel") { p.dismiss() }
        p.show()
    }

    /**
     * Changes destination / stops / detour and asks for the new route. If the
     * route request fails, the trip goes back to what it was (navigation goes
     * on with the old route, so the screen must not show the new plan).
     * [remember]: current stops to keep for "Resume previous route".
     */
    private fun changeTrip(end: LatLon, stops: List<LatLon>, detour: LatLon?, remember: List<LatLon>?, message: String) {
        val before = TripState.copy()
        if (remember != null) TripState.rememberForResume(remember)
        TripState.destination = end
        TripState.waypoints.clear()
        TripState.waypoints.addAll(stops)
        TripState.detour = detour?.takeIf { it in stops }
        TripState.detourApplied = false
        refreshPins()
        refreshResumeButton()
        val start = resolveStartPoint()
        if (start == null) {
            TripState.restore(before)
            refreshPins()
            refreshResumeButton()
            showError("No GPS fix yet -- try again in a moment")
            return
        }
        showMessage(message)
        TripState.changePending = true
        requestBestRoute(start, end, stops, onFailure = { restoreCommitted(before) })
    }

    /** A trip change failed: back to the trip whose route is on screen (and
     *  being navigated), not to a half-applied earlier change. */
    private fun restoreCommitted(fallback: TripState.Copy) {
        TripState.restore(TripState.committed ?: fallback)
        TripState.changePending = false
        refreshPins()
        refreshResumeButton()
    }

    /** New destination from a place popup; also while navigating (the trip
     *  before is kept for "Resume previous route"). */
    private fun goHere(point: LatLon, name: String) {
        // A destination, not a stop, even if stop mode was armed.
        if (waypointModeArmed) {
            waypointModeArmed = false
            setIconActive(findViewById(R.id.waypoint_button), false)
        }
        if (NavigationEngine.isActive) {
            val existing = NavigationEngine.remainingStops() ?: TripState.waypoints.toList()
            // Straight there: the detour (if any) is dropped, your own stops stay.
            changeTrip(point, existing.filter { it != TripState.detour }, null, remember = existing,
                message = "Going to $name \u00b7 finding route...")
            return
        }
        if (TripState.destination != null) TripState.rememberForResume(TripState.waypoints.toList())
        TripState.dropDetour()
        setDestination(point)
        refreshResumeButton()
    }

    /** Back to the trip before the last detour / replaced stop / "Go here". */
    private fun resumePreviousRoute() {
        val prev = TripState.previous ?: return
        // Stops you have been at since then stay visited.
        val stops = prev.waypoints.filter { it !in TripState.visitedSinceSnapshot }
        val before = TripState.copy()
        TripState.previous = null
        changeTripResume(before, prev.destination, stops)
    }

    private fun changeTripResume(before: TripState.Copy, end: LatLon, stops: List<LatLon>) {
        TripState.destination = end
        TripState.waypoints.clear()
        TripState.waypoints.addAll(stops)
        TripState.detour = null
        TripState.detourApplied = false
        refreshPins()
        refreshResumeButton()
        val start = resolveStartPoint() ?: run { TripState.restore(before); refreshPins(); refreshResumeButton(); return }
        showMessage("Previous route resumed \u00b7 finding route...")
        TripState.changePending = true
        requestBestRoute(start, end, stops, onFailure = { restoreCommitted(before) })
    }

    private fun refreshResumeButton() {
        findViewById<View>(R.id.resume_button).visibility = if (TripState.previous != null) View.VISIBLE else View.GONE
        placeLayout()
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
        if (Stops.ownCount(TripState.waypoints, TripState.detour) >= maxWaypoints) {
            // With a destination: visit on the way or replace the last stop.
            if (TripState.destination != null) addStopFlow(point, "This place")
            else showMessage("Maximum $maxWaypoints stops reached")
            return
        }
        TripState.waypoints.add(point)
        refreshPins()
        if (Stops.ownCount(TripState.waypoints, TripState.detour) >= maxWaypoints) {
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
        findViewById<View>(R.id.overview_button).visibility = View.VISIBLE
        navInstructionBar.visibility = View.VISIBLE
        refreshNavBox()
        lastRealLocationAtMs = System.currentTimeMillis()
        handler.removeCallbacks(deadReckoningRunnable)
        handler.postDelayed(deadReckoningRunnable, 1000L)

        val m = map ?: return
        if (puckActive() && !overviewOn) followPosition(if (first) 18.0 + Prefs.navZoomOffset(this) else null)
        NavigationEngine.lastProgress?.let { p -> NavigationEngine.route?.let { onNavProgress(p, it) } }
    }

    /** Camera follows the arrow, heading-up. Zoom and tilt are part of the
     *  transition itself (MapLibre ignores them while a transition runs);
     *  the arrow is moved to the lower part of the screen once it ends. */
    private var northUp = false

    private fun followPosition(zoom: Double?) {
        val lc = map?.locationComponent ?: return
        if (zoom != null) lastTrackingZoom = zoom
        // Settings > Navigation: 3D tilt (Auto = 45° driving, 25° otherwise).
        val tilt = if (northUp) 0.0 else Prefs.navTilt(this, currentMode == ModeUi.DRIVING)
        val mode = if (northUp) CameraMode.TRACKING_GPS_NORTH else CameraMode.TRACKING_GPS
        lc.setCameraMode(mode, 750L, zoom, if (northUp) 0.0 else null, tilt, object : OnLocationCameraTransitionListener {
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
        if (overviewOn) {
            overviewOn = false
            handler.removeCallbacks(overviewReturnRunnable)
            setOverviewButton(false)
        }
        findViewById<View>(R.id.overview_button).visibility = View.GONE
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
        updateNavBarProximityColor(Double.MAX_VALUE) // planning: plain blue frame
        refreshNavBox()
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
            if (altChoiceOpen) closeAltChoice(restore = false)
            TripState.plan = plan
            TripState.selectedIndex = plan.primaryIndex
            // No full places search on a reroute: places are measured on the
            // current route anyway (see aheadOnRoute); the stretch ahead of
            // the new route is searched by extendTouristIfNeeded (mostly from
            // the squares already on the phone).
            TripState.touristCoveredM = 0.0
            SpeedLimitLookup.routeChanged()
            drawPlan(plan, plan.primaryIndex, fit = false, note = plan.note, tourist = false)
        }
        override fun onRerouteFailed(error: String) = showError(error)
        override fun onArrived() {
            if (altChoiceOpen) closeAltChoice(restore = true)
            // Arrived: nothing left to resume.
            TripState.previous = null
            TripState.dropDetour()
            refreshResumeButton()
            navDistanceText.text = ""
            statusStrip.text = "Arrived"
            navInstructionIcon.setImageResource(R.drawable.ic_nav_arrive)
            navInstructionIcon.visibility = View.VISIBLE
            wakeDisplay()
        }
        override fun onStopped() {
            if (altChoiceOpen) closeAltChoice(restore = true)
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
        // Remember the stops of the remembered trip you pass, for "resume".
        TripState.previous?.let { prev ->
            LocationHub.lastGoodFix?.let { f ->
                val here = LatLon(f.latitude, f.longitude)
                prev.waypoints.forEach { w -> if (Geo.distance(here, w) < 80.0) TripState.visitedSinceSnapshot.add(w) }
            }
        }
        // A temporary detour already visited is no longer "on the way".
        TripState.detour?.let { d ->
            if (TripState.detourApplied && NavigationEngine.remainingStops()?.contains(d) == false) {
                TripState.dropDetour()
                TripState.committed?.let { TripState.committed = TripState.copy() }
            }
        }
        if (overviewOn && p.anticipation) leaveOverview() // back to the drive view before a turn
        extendTouristIfNeeded(p, route)
        // Live remaining distance and arrival time.
        routeInfoDist.text = distanceLabel(p.remainingM)
        routeInfoEta.text = etaLabel(p.remainingS)
        LocationHub.lastGoodFix?.let { dimDisplayIfIdle(p.distanceToNextM, it) }
    }

    private fun updateNavBar(step: NavStep?, distance: Double?) {
        if (navInstructionBar.visibility != View.VISIBLE) { navInstructionBar.visibility = View.VISIBLE; refreshNavBox() }
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
     *  down to 14 at ~120 km/h, plus the Settings offset. Only re-zooms on
     *  a real change; not in the overview. */
    private fun adaptTrackingZoom(location: Location) {
        if (currentMode != ModeUi.DRIVING || !location.hasSpeed() || !puckActive() || overviewOn) return
        val lc = map?.locationComponent ?: return
        if (lc.cameraMode != CameraMode.TRACKING_GPS && lc.cameraMode != CameraMode.TRACKING_GPS_NORTH) return
        val zoom = (18.0 - location.speed * 3.6 / 30.0).coerceIn(14.0, 18.0) + Prefs.navZoomOffset(this)
        if (Math.abs(zoom - lastTrackingZoom) >= 0.3) {
            lastTrackingZoom = zoom
            lc.zoomWhileTracking(zoom, 1200L)
        }
    }

    // ---------- Drive view / Overview (while navigating) ----------

    private var overviewOn = false

    private val overviewReturnRunnable = Runnable { if (overviewOn) leaveOverview() }

    /** 20 s without touching the map: back to the drive view. */
    private fun scheduleOverviewReturn() {
        handler.removeCallbacks(overviewReturnRunnable)
        handler.postDelayed(overviewReturnRunnable, 20_000L)
    }

    private fun setOverviewButton(on: Boolean) {
        findViewById<ImageButton>(R.id.overview_button).apply {
            setImageResource(if (on) R.drawable.ic_drive_view else R.drawable.ic_overview)
            setIconActive(this, on)
            contentDescription = if (on) "Back to the drive view" else "Route overview"
        }
    }

    /**
     * Flat, north up, the rest of the route framed, with the places along it
     * (tap one: Add stop). Navigation and voice go on underneath. Back to the
     * drive view by the button, after 20 s untouched, or before a turn.
     */
    private fun enterOverview() {
        val m = map ?: return
        val route = NavigationEngine.route ?: return
        overviewOn = true
        setOverviewButton(true)
        if (puckActive()) m.locationComponent.cameraMode = CameraMode.NONE
        val from = NavigationEngine.lastProgress?.snap?.segmentIndex ?: 0
        val rest = (listOfNotNull(currentPosition()) + route.points.drop(from + 1)).ifEmpty { route.points }
        try {
            if (rest.size >= 2) {
                val bounds = LatLngBounds.Builder().includes(rest.map { LatLng(it.lat, it.lon) }).build()
                // Reset tilt, bearing and the navigation padding first, then frame.
                m.cameraPosition = CameraPosition.Builder().target(m.cameraPosition.target).zoom(m.cameraPosition.zoom)
                    .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(56), dp(130), dp(56), dp(170)), 700)
            }
        } catch (e: Exception) { }
        showMessage("Overview \u00b7 tap a place to add it \u00b7 back to driving in 20 s")
        scheduleOverviewReturn()
    }

    private fun leaveOverview() {
        if (!overviewOn) return
        overviewOn = false
        handler.removeCallbacks(overviewReturnRunnable)
        setOverviewButton(false)
        if (navUiActive && puckActive()) followPosition(lastTrackingZoom.takeIf { it > 0.0 } ?: (18.0 + Prefs.navZoomOffset(this)))
    }

    private fun setNavigationDeclutter(navigating: Boolean) {
        val visibility = if (navigating) View.GONE else View.VISIBLE
        // After Go: route chips go away, "find alternative route" appears.
        altChoiceOpen = false
        findViewById<View>(R.id.alt_route_button).visibility = if (navigating) View.VISIBLE else View.GONE
        if (navigating) findViewById<View>(R.id.route_options_row).visibility = View.GONE
        else TripState.plan?.let { populateRouteOptionsRow(it.routes, TripState.selectedIndex) }
        redrawRouteLines()
        // 1.0.4: navigating = left column hidden; right column = locate,
        // overview, alternative route, magnifier (+ resume). All back on Stop / Clear.
        findViewById<View>(R.id.mode_row).visibility = visibility
        findViewById<View>(R.id.route_options_button).visibility = visibility
        findViewById<View>(R.id.waypoint_button).visibility = visibility
        findViewById<View>(R.id.preference_toggle_button).visibility = visibility
        tollToggle.visibility = if (navigating || currentMode != ModeUi.DRIVING) View.GONE else View.VISIBLE
        placeLayout()
    }

    private fun setMargins(id: Int, top: Int? = null, bottom: Int? = null, start: Int? = null, end: Int? = null) {
        val v = findViewById<View>(id)
        // Urgency and POI sit inside the left column (LinearLayout params):
        // the margin type common to all parents.
        val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
        var changed = false
        top?.let { if (lp.topMargin != dp(it)) { lp.topMargin = dp(it); changed = true } }
        bottom?.let { if (lp.bottomMargin != dp(it)) { lp.bottomMargin = dp(it); changed = true } }
        start?.let { if (lp.marginStart != dp(it)) { lp.marginStart = dp(it); changed = true } }
        end?.let { if (lp.marginEnd != dp(it)) { lp.marginEnd = dp(it); changed = true } }
        if (changed) v.layoutParams = lp
    }

    private fun setSize(id: Int, height: Int? = null, width: Int? = null) {
        val v = findViewById<View>(id)
        val lp = v.layoutParams
        var changed = false
        height?.let { if (lp.height != dp(it)) { lp.height = dp(it); changed = true } }
        width?.let { if (lp.width != dp(it)) { lp.width = dp(it); changed = true } }
        if (changed) v.layoutParams = lp
    }

    // ---------- Communication area (bottom): search bar + messages ----------

    private var keyboardOpen = false

    /** Instruction box shown while navigating, or while planning when a
     *  route is up (then only its distance / arrival / cost line). */
    private fun refreshNavBox() {
        val show = navUiActive || routeInfo.visibility == View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        if (navBox.visibility != v) navBox.visibility = v
    }

    /**
     * Places every control (1.0.4, portrait and landscape):
     *
     * Top centre: the instruction box (80 % wide while navigating, just the
     * distance / arrival / cost line while planning); the speed circle at
     * its right end (top right when not navigating); route chips under it,
     * stacked in portrait, side by side in landscape.
     *
     * Two columns starting under the top band:
     *  left  (planning only) = travel mode (+ toll), urgency, POI, settings, resume;
     *  right planning   = locate, add stop, preference, magnifier;
     *  right navigating = locate, overview, alternative route, magnifier, resume.
     *
     * Bottom: Go / Clear just over the map credit, messages just over them
     * (bottom-left in landscape, clear of the position arrow). The search
     * box appears only from the magnifier, floating over the keyboard.
     *
     * Transparent guards under the columns and the bottom buttons keep near
     * misses from tapping the map.
     */
    private fun placeLayout() {
        refreshNavBox()
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val driving = currentMode == ModeUi.DRIVING
        val density = resources.displayMetrics.density
        // The layout's own width (side navigation bar, cutout, split screen).
        val screenW = findViewById<View>(android.R.id.content).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

        // Instruction box: 80 % of the old full width while navigating;
        // planning: as wide as its one line.
        val boxLp = navBox.layoutParams
        val boxW = if (navUiActive) ((screenW - dp(16)) * 0.8f).toInt() else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (boxLp.width != boxW) { boxLp.width = boxW; navBox.layoutParams = boxLp }
        // Room for the speed circle at the box's right end (driving, navigating).
        val circleInBox = navUiActive && driving
        navBox.setPadding(dp(14), dp(5), if (circleInBox) dp(80) else dp(14), dp(5))
        // Planning while driving: box and chips centred left of the speed
        // circle (top right), never under it.
        val circleTopRight = driving && !circleInBox
        setMargins(R.id.nav_box, start = if (circleTopRight) 8 else 0, end = if (circleTopRight) 96 else 0)
        val boxShown = navBox.visibility == View.VISIBLE && navBox.height > 0
        val boxBottom = if (boxShown) (navBox.bottom / density).toInt() else 0

        // Speed circle: inside the box at its right end, centred on its
        // height; otherwise top right.
        if (circleInBox && boxShown) {
            val endInset = ((screenW - boxW) / 2 / density).toInt()
            val boxTop = (navBox.top / density).toInt()
            val boxH = (navBox.height / density).toInt()
            setMargins(R.id.speed_widget, top = maxOf(boxTop, boxTop + (boxH - 64) / 2), end = endInset + 6)
        } else {
            setMargins(R.id.speed_widget, top = 12, end = 14)
        }

        // Columns start under the top band (speed circle when driving).
        val top0 = maxOf(if (driving) 100 else 60, if (boxShown) boxBottom + 10 else 0)
        val step = if (land) 46 else 53
        val gap = step - 38
        fun shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
        val resumeShown = shown(R.id.resume_button)

        // Left column (planning): mode, urgency, POI, settings, resume.
        setMargins(R.id.mode_row, top = top0)
        setMargins(R.id.urgency_button, top = gap)
        setMargins(R.id.poi_toggle_button, top = gap)
        setMargins(R.id.route_options_button, top = top0 + 3 * step)
        var leftRows = if (navUiActive) 0 else 4

        // Right column.
        setMargins(R.id.locate_button, top = top0)
        var rightRows: Int
        if (navUiActive) {
            setMargins(R.id.overview_button, top = top0 + step)
            val altShown = shown(R.id.alt_route_button)
            setMargins(R.id.alt_route_button, top = top0 + 2 * step)
            rightRows = if (altShown) 3 else 2
            setMargins(R.id.search_button, top = top0 + rightRows * step)
            rightRows++
            if (resumeShown) {
                moveTo(R.id.resume_button, android.view.Gravity.TOP or android.view.Gravity.END)
                setMargins(R.id.resume_button, top = top0 + rightRows * step, end = 8)
                rightRows++
            }
        } else {
            setMargins(R.id.waypoint_button, top = top0 + step)
            setMargins(R.id.preference_toggle_button, top = top0 + 2 * step)
            setMargins(R.id.search_button, top = top0 + 3 * step)
            rightRows = 4
            moveTo(R.id.resume_button, android.view.Gravity.TOP or android.view.Gravity.START)
            setMargins(R.id.resume_button, top = top0 + 4 * step, start = 8)
            if (resumeShown) leftRows = 5
        }

        // Route chips: centred under the box, stacked in portrait, side by side in landscape.
        val row = findViewById<LinearLayout>(R.id.route_options_row)
        val orient = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (row.orientation != orient) {
            row.orientation = orient
            row.gravity = android.view.Gravity.CENTER_HORIZONTAL
            for (i in 0 until row.childCount) chipSpacing(row.getChildAt(i), land)
        }
        setMargins(R.id.route_options_scroll, top = if (boxShown) boxBottom + 6 else 12, start = 56, end = if (circleTopRight) 96 else 56)

        // While typing, the bottom buttons and the credit step aside; only
        // what was shown is hidden, and only that comes back.
        val under = listOf<View>(findViewById(R.id.bottom_bar), findViewById(R.id.credit_text))
        if (keyboardOpen) {
            for (v in under) if (v.visibility == View.VISIBLE) { v.visibility = View.INVISIBLE; hiddenForKeyboard.add(v) }
        } else {
            for (v in hiddenForKeyboard) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
            hiddenForKeyboard.clear()
        }

        // Bottom: Go / Clear (20..64) -> messages (68). The floating search
        // box sits in the messages' place (just over the keyboard while
        // typing), the messages and suggestions over it.
        val searchBottom = if (keyboardOpen) 8 else 68
        setMargins(R.id.search_input, bottom = searchBottom, start = 8, end = 8)
        setMargins(R.id.search_results_scroll, bottom = searchBottom + 33, start = 8, end = 8)
        val bandBottom = if (searchOpen) searchBottom + 33 else 68
        // Landscape: bottom-left, never over the position arrow (screen centre).
        moveTo(R.id.message_band, android.view.Gravity.BOTTOM or if (land) android.view.Gravity.START else android.view.Gravity.CENTER_HORIZONTAL)
        setMargins(R.id.message_band, bottom = bandBottom, start = if (land) 56 else 0)

        // Guards: columns (with a little margin around) and the bottom buttons.
        setMargins(R.id.guard_left, top = top0 - 6)
        setSize(R.id.guard_left, height = if (leftRows == 0) 0 else leftRows * step + 4, width = 88)
        setMargins(R.id.guard_right, top = top0 - 6)
        setSize(R.id.guard_right, height = rightRows * step + 4, width = 56)
        setSize(R.id.guard_bottom, height = if (keyboardOpen) 0 else 66)
    }

    /** Changes a view's gravity inside the main frame (no-op if unchanged). */
    private fun moveTo(id: Int, gravity: Int) {
        val v = findViewById<View>(id)
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.gravity != gravity) { lp.gravity = gravity; v.layoutParams = lp }
    }

    /** Space between chips: under each other in portrait, side by side in landscape. */
    private fun chipSpacing(chip: View, land: Boolean) {
        val lp = chip.layoutParams as LinearLayout.LayoutParams
        lp.marginEnd = if (land) dp(6) else 0
        lp.bottomMargin = if (land) 0 else dp(4)
        chip.layoutParams = lp
    }

    private val hiddenForKeyboard = mutableSetOf<View>()

    /** The screen shrinks when the keyboard opens (adjustResize). After each
     *  layout, the window's own insets say whether the keyboard is shown;
     *  the communication area follows. (No height guessing: split screen or
     *  a resized window is not mistaken for the keyboard.) Below Android 11
     *  the keyboard inset size counts too (the visibility flag is a guess there). */
    private fun watchKeyboard() {
        val root = findViewById<View>(android.R.id.content)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root) ?: return@addOnGlobalLayoutListener
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            val open = insets.isVisible(ime) || insets.getInsets(ime).bottom > dp(80)
            if (open != keyboardOpen) {
                keyboardOpen = open
                root.post { placeLayout() }
            }
        }
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
        val background = navBox.background?.mutate() as? GradientDrawable ?: return
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

    /** Category icons are multi-colour (never tinted): status is a red ring
     *  on the usual grey backdrop (found), no ring otherwise. Off = grey pin. */
    private fun setPoiButtonStatus(button: ImageButton, found: Boolean?) {
        setStatusRing(button, found == true)
        if (found == null) setIconActive(button, false) else button.imageTintList = null
    }

    private fun setupPoiToggle() {
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        button.setImageResource(currentPoiCategory.iconRes)
        setPoiButtonStatus(button, if (currentPoiCategory == PoiCategory.NONE) null else false)
        button.setOnClickListener { showPoiDropdown(button) }
        button.setOnLongClickListener {
            if (currentPoiCategory == PoiCategory.SIGHTS && touristResults.isNotEmpty()) showTouristList()
            true
        }
    }

    /** Dropdown under the POI button: icon + name, the current one in red. One tap chooses. */
    private fun showPoiDropdown(anchor: View) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.popup_background)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val window = android.widget.PopupWindow(list, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        for (cat in PoiCategory.values()) {
            val selected = cat == currentPoiCategory
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(14), dp(5))
                setOnClickListener {
                    window.dismiss()
                    selectPoiCategory(cat)
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(cat.iconRes)
                if (cat == PoiCategory.NONE || cat == PoiCategory.SIGHTS) imageTintList = ColorStateList.valueOf(if (selected) activeColor else inactiveColor)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            row.addView(TextView(this).apply {
                text = cat.label
                textSize = 15f
                setTextColor(if (selected) activeColor else ContextCompat.getColor(this@MainActivity, R.color.rg_text))
                setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(dp(10), 0, 0, 0)
            })
            list.addView(row)
        }
        window.elevation = dp(6).toFloat()
        window.isOutsideTouchable = true
        window.showAsDropDown(anchor, 0, dp(4))
    }

    private fun selectPoiCategory(cat: PoiCategory) {
        if (cat == currentPoiCategory && cat != PoiCategory.NONE) {
            // Same category again: search again (e.g. after a busy server).
            if (cat == PoiCategory.SIGHTS) refreshTourist(force = true) else queryPois(force = true)
            return
        }
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        currentPoiCategory = cat
        button.setImageResource(cat.iconRes)
        saveToggleState()
        poiRequestGeneration++
        placesFinder.cancel()
        poiResults = emptyList()
        layers.setPois(emptyList())
        when (cat) {
            PoiCategory.NONE -> {
                setPoiButtonStatus(button, null)
                touristModeChanged()
                showMessage("POI layer off")
            }
            PoiCategory.SIGHTS -> {
                setPoiButtonStatus(button, false)
                showMessage("Looking for well-known places...")
                touristModeChanged()
            }
            else -> {
                touristModeChanged() // leaving Sights clears them unless Weekend
                setPoiButtonStatus(button, false)
                showMessage("Checking for ${cat.label}...")
                queryPois(force = true)
            }
        }
    }

    /** Places of the chosen category in the visible area (capped at ~25 km),
     *  per map square from the phone's cache or one Overpass request; again
     *  when the map moved more than ~1 km. Only the newest request renders. */
    private fun queryPois(force: Boolean = false) {
        if (currentPoiCategory == PoiCategory.NONE || currentPoiCategory == PoiCategory.SIGHTS) return
        val m = map ?: return
        val target = m.cameraPosition.target ?: return
        val center = LatLon(target.latitude, target.longitude)
        val now = System.currentTimeMillis()
        val movedFar = lastPoiQueryCenter?.let { Geo.distance(it, center) > 1000.0 } ?: true
        if (!force && (now - lastPoiQueryAtMs < 20000L || !movedFar)) return
        // After failures, automatic retries wait 1, 2, then 5 min (a tap always retries).
        if (!force && now < poiRetryAtMs) return
        lastPoiQueryAtMs = now
        lastPoiQueryCenter = center

        val category = currentPoiCategory
        val thisGeneration = ++poiRequestGeneration
        val box = m.projection.visibleRegion.latLngBounds
        val maxSpanDegrees = 25000.0 / 111000.0
        val latSpan = (box.latitudeNorth - box.latitudeSouth).coerceIn(0.0, maxSpanDegrees)
        val lonSpan = (box.longitudeEast - box.longitudeWest).coerceIn(0.0, maxSpanDegrees)
        placesFinder.inBox(category.finderCategory(), center.lat - latSpan / 2, center.lon - lonSpan / 2,
            center.lat + latSpan / 2, center.lon + lonSpan / 2,
            onSwitch = { if (thisGeneration == poiRequestGeneration && force) onPlacesServerSwitch() }
        ) { results, error, note, serverFailed ->
            if (thisGeneration != poiRequestGeneration || isDestroyed) return@inBox
            val button = findViewById<ImageButton>(R.id.poi_toggle_button)
            if (results == null) {
                // Allow a new try as soon as asked (or after a map move, after a pause).
                lastPoiQueryCenter = null
                poiFailures++
                poiRetryAtMs = System.currentTimeMillis() + when (poiFailures) { 1 -> 60_000L; 2 -> 120_000L; else -> 300_000L }
                setPoiButtonStatus(button, false)
                showError(error ?: "Places search failed", retry = { queryPois(force = true) })
                return@inBox
            }
            if (serverFailed) {
                // Shown from the phone / openrouteservice, but Overpass failed:
                // automatic retries for the missing squares wait too.
                poiFailures++
                poiRetryAtMs = System.currentTimeMillis() + when (poiFailures) { 1 -> 60_000L; 2 -> 120_000L; else -> 300_000L }
            } else {
                poiFailures = 0
                poiRetryAtMs = 0L
            }
            poiResults = results
            layers.setPois(results.map { MapLayers.Pin(it.key, it.point, category.iconName) })
            setPoiButtonStatus(button, results.isNotEmpty())
            when {
                note != null -> showMessage(note)
                !force -> {}
                results.isEmpty() -> showMessage("No ${category.label} found here")
                else -> showMessage("${results.size} ${category.label} \u00b7 tap one for details")
            }
        }
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
            speedWidget.visibility = View.GONE
            stopBlinking()
        }
        refreshRouteOptionsButton()
        placeLayout()
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

    /** Same grey backdrop as every button; the icon is red when tolls are
     *  allowed, grey (crossed-out icon) when avoided. */
    private fun setTollButtonStatus(allowed: Boolean) = setIconActive(tollToggle, allowed)

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
            showMessage(
                if (urgencyMode == UrgencyMode.WEEKEND) "Weekend mode selected \u00b7 showing well-known places"
                else "${urgencyMode.name.lowercase().replaceFirstChar { it.uppercase() }} mode selected"
            )
            saveToggleState()
            touristModeChanged()

            if (urgencyMode == UrgencyMode.EMERGENCY && currentPoiCategory != PoiCategory.EMERGENCY) {
                // Sights category (if any) replaced by Emergency.
                selectPoiCategory(PoiCategory.EMERGENCY)
            }

            val start = resolveStartPoint()
            val end = TripState.destination
            if (start != null && end != null && currentMode == ModeUi.DRIVING && currentPreference == PreferenceUi.RECOMMENDED) {
                showMessage("Finding route...")
                requestBestRoute(start, end)
            }
        }
        // Hold: list of well-known places (Weekend).
        urgencyButton.setOnLongClickListener {
            when {
                touristOn && touristResults.isNotEmpty() -> showTouristList()
                touristOn -> showMessage("No well-known places found yet")
                else -> showMessage("Weekend mode shows well-known places")
            }
            true
        }
    }

    /** Quick access to the Route page of Settings. */
    private fun setupRouteOptionsButton() {
        findViewById<ImageButton>(R.id.route_options_button).setOnClickListener {
            SettingsPopup.show(this, settingsHost, SettingsPopup.Page.ROUTE)
        }
        refreshRouteOptionsButton()
    }

    private val settingsHost = object : SettingsPopup.Host {
        override val travelMode get() = currentMode.mode
        override val poiChoices get() = PoiCategory.values().map { it.name to it.label }
        override fun onSettingsOpened() { settingsOpen = true }
        override fun onRouteOptionsChanged(options: RouteOptions) {
            if (isDestroyed) return
            routeOptions = options
            refreshRouteOptionsButton()
            rerouteIfPossible(options.summary(currentMode.mode))
        }
        override fun onSettingsClosed() {
            settingsOpen = false
            if (isDestroyed) return
            loadSettings()
            // Map style changed (day/night itself rebuilds the screen).
            val m = map
            if (m != null && loadedStyleUrl != null && loadedStyleUrl != Prefs.mapStyleUrl(this@MainActivity)) loadStyle(m)
            Prefs.applyNightMode(this@MainActivity)
            // New tilt / zoom apply at once while navigating.
            if (navUiActive && !overviewOn && puckActive()) {
                if (currentMode == ModeUi.DRIVING) {
                    lastTrackingZoom = -100.0 // the next fix sets the speed-adapted zoom again
                    followPosition(null)
                } else {
                    followPosition(18.0 + Prefs.navZoomOffset(this@MainActivity))
                }
            }
        }
        override fun onPlacesCleared() {
            TripState.touristCoveredM = 0.0
        }
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

    // ---------- Speed limit (alarm rules unchanged) ----------

    /**
     * Road limit for the sign. Along a route (planned or navigated) the
     * roads ahead are loaded in one go and looked up on the phone; off a
     * route, one quick lookup around you at most every 15 s. The last known
     * limit is kept for 2 min when nothing answers.
     */
    private fun updateSpeedLimit(location: Location) {
        val now = System.currentTimeMillis()
        val route = (if (NavigationEngine.isActive) NavigationEngine.route else null) ?: selectedRoute()
        if (route != null && route.points.size >= 2) {
            val along = (if (NavigationEngine.isActive) NavigationEngine.lastProgress?.alongM else null)
                ?: route.line.snap(LatLon(location.latitude, location.longitude))?.alongM ?: 0.0
            SpeedLimitLookup.prepare(this, route, along)
        }
        if (SpeedLimitLookup.covers(location)) {
            lastKnownSpeedLimit = SpeedLimitLookup.limitAt(location)
            lastSpeedLimitOkAtMs = now
            return
        }
        if (now - lastSpeedLimitOkAtMs > 120_000L) lastKnownSpeedLimit = null
        SpeedLimitLookup.lookupHere(this, location) { ok ->
            if (!ok || isDestroyed) return@lookupHere
            val fix = LocationHub.lastFix ?: return@lookupHere
            if (SpeedLimitLookup.covers(fix)) {
                lastKnownSpeedLimit = SpeedLimitLookup.limitAt(fix)
                lastSpeedLimitOkAtMs = System.currentTimeMillis()
            }
            if (currentMode == ModeUi.DRIVING) updateSpeedSignDisplay(fix)
        }
    }

    /** Green 5+ under, yellow within 5 under, red up to 5 over, blinking
     *  red + high alarm beyond; blue + low alarm when 15+ under on a 100+
     *  road; red + low alarm when stopped on a 100+ road. */
    private fun updateSpeedSignDisplay(location: Location) {
        val limit = lastKnownSpeedLimit
        val speedKmh = (location.speed * 3.6).toInt()
        val hasRealMovement = location.hasSpeed() && speedKmh > 3
        // Hidden only while the keyboard is open (search bar in its place).
        speedWidget.visibility = View.VISIBLE
        val background = speedSign.background?.mutate() as? GradientDrawable

        // Circle = your speed; sign on top = the road's limit (OSM, km/h).
        val shown = if (useMetric) speedKmh else (location.speed * 2.23694).toInt()
        speedSignText.text = if (location.hasSpeed()) shown.toString() else "--"
        speedUnitText.text = if (useMetric) "km/h" else "mph"
        if (limit == null) {
            speedLimitSign.visibility = View.GONE
            background?.setStroke(dp(4), Color.parseColor("#9E9E9E"))
            stopBlinking()
            return
        }
        speedLimitSign.visibility = View.VISIBLE
        speedLimitSign.text = (if (useMetric) limit else Math.round(limit * 0.621371).toInt()).toString()
        val diff = speedKmh - limit
        val stoppedOnHighway = !hasRealMovement && limit >= 100
        val tooSlowForHighway = hasRealMovement && limit >= 100 && diff <= -15
        when {
            stoppedOnHighway -> {
                background?.setStroke(dp(4), Color.parseColor("#C0392B"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            !hasRealMovement -> {
                background?.setStroke(dp(4), Color.parseColor("#9E9E9E"))
                stopBlinking()
            }
            tooSlowForHighway -> {
                background?.setStroke(dp(4), Color.parseColor("#1565C0"))
                stopBlinking()
                maybeSoundAlarm(highPitch = false)
            }
            diff > 5 -> {
                startBlinking()
                maybeSoundAlarm(highPitch = true)
            }
            diff > 0 -> {
                background?.setStroke(dp(4), Color.parseColor("#C0392B"))
                stopBlinking()
            }
            diff > -5 -> {
                background?.setStroke(dp(4), Color.parseColor("#F1C40F"))
                stopBlinking()
            }
            else -> {
                background?.setStroke(dp(4), Color.parseColor("#2E7D32"))
                stopBlinking()
            }
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            val background = speedSign.background?.mutate() as? GradientDrawable
            background?.setStroke(dp(4), if (blinkOn) Color.parseColor("#C0392B") else Color.WHITE)
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
        // Settings > Speed > Alarm sound (the sign itself always shows).
        if (!Prefs.alarmSound(this)) return
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
            showMessage("Stop navigation before changing your route (or open a place: Go here)")
            return
        }
        if (waypointModeArmed) {
            addWaypoint(point)
            return
        }
        // Your own choice wins over a place still waiting for GPS.
        TripState.pendingLink = null
        TripState.pendingLinkMode = null
        // A new destination tapped on the map is a new trip: nothing to resume,
        // and a temporary detour does not become a stop of its own.
        TripState.previous = null
        TripState.dropDetour()
        refreshResumeButton()
        setDestination(point)
    }

    private fun setDestination(point: LatLon) {
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
        TripState.pendingLink = null
        TripState.pendingLinkMode = null
        TripState.clear()
        layers.clearRoutes()
        refreshPins()
        refreshResumeButton()
        routeInfoDist.text = ""
        routeInfoEta.text = ""
        routeInfoCost.text = ""
        routeInfo.visibility = View.GONE
        placeLayout()
        if (navUiActive) stopNavigationUi()
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.GONE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = View.GONE
        findViewById<LinearLayout>(R.id.route_options_row).apply {
            removeAllViews()
            visibility = View.GONE
        }
        // Tourist mode falls back to the visible area.
        refreshTourist(force = true)
    }

    // ---------- Routing ----------

    private fun buildRequest(from: LatLon, to: LatLon, stops: List<LatLon>? = null): RouteRequest = RouteRequest(
        from = from,
        to = to,
        // Mid-trip: only the stops not visited yet.
        waypoints = stops ?: NavigationEngine.remainingStops() ?: TripState.waypoints.toList(),
        mode = currentMode.mode,
        preference = currentPreference.pref,
        avoidTolls = avoidTolls,
        options = routeOptions,
        valuePerMinute = currentValuePerMinute(),
        tollRatePerKm = tollRatePerKm
    )

    private fun requestBestRoute(from: LatLon, to: LatLon, stops: List<LatLon>? = null, onFailure: (() -> Unit)? = null) {
        val navigating = NavigationEngine.isActive
        // Mid-trip changes (preference, urgency) start from where you are now.
        val request = buildRequest(if (navigating) currentPosition() ?: from else from, to, stops)
        planner.plan(request) { plan, error ->
            if (plan == null) {
                onFailure?.invoke()
                showError(error ?: "Route request failed")
                return@plan
            }
            // From now on, a detour missing from the remaining stops means "visited".
            TripState.detour?.let { if (it in request.waypoints) TripState.detourApplied = true }
            // This trip now matches the route on screen.
            TripState.changePending = false
            TripState.committed = TripState.copy()
            if (navigating && altChoiceOpen) closeAltChoice(restore = false)
            TripState.plan = plan
            TripState.selectedIndex = plan.primaryIndex
            drawPlan(plan, plan.primaryIndex, fit = !navigating, note = plan.note)
            if (navigating && NavigationEngine.isActive) NavigationEngine.switchRoute(plan, plan.primaryIndex, request)
        }
    }

    private var speedLimitPlan: RoutePlan? = null

    private fun drawPlan(plan: RoutePlan, selectedIndex: Int, fit: Boolean, note: String?, tourist: Boolean = true) {
        val results = plan.routes
        if (results.isEmpty() || results[0].points.isEmpty()) {
            showError("Route returned no geometry")
            return
        }
        val sel = selectedIndex.coerceIn(0, results.size - 1)
        // Destination where you already are: nothing to navigate.
        if (!NavigationEngine.isActive && results[sel].distanceMeters < 30.0) {
            // Only the destination goes: your start point and stops stay.
            clearRouteVisuals()
            TripState.committed = TripState.copy()
            routeInfo.visibility = View.GONE
            findViewById<View>(R.id.nav_go_button).visibility = View.GONE
            findViewById<View>(R.id.clear_route_button).visibility = View.GONE
            findViewById<LinearLayout>(R.id.route_options_row).apply { removeAllViews(); visibility = View.GONE }
            placeLayout()
            refreshTourist(force = true) // sights of the visible area again
            showMessage("You are already there")
            return
        }
        TripState.selectedIndex = sel
        drawRouteLines(results, sel)
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
        placeLayout()
        populateRouteOptionsRow(results, sel)
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.clear_route_button).visibility = View.VISIBLE

        if (fit) fitCamera(results)
        if (tourist) refreshTourist()
        // Driving: the speed limits of the first stretch load before Go.
        if (currentMode == ModeUi.DRIVING) {
            // A new plan (not a redraw of the same one) may retry at once.
            if (plan !== speedLimitPlan) { speedLimitPlan = plan; SpeedLimitLookup.routeChanged() }
            val along = currentPosition()?.let { primary.line.snap(it)?.alongM } ?: 0.0
            SpeedLimitLookup.prepare(this, primary, along)
        }
    }

    /** After Go only the followed route is drawn (grey alternatives come back
     *  with "find alternative route"). */
    private fun drawRouteLines(results: List<RouteResult>, sel: Int) {
        if (navUiActive && !altChoiceOpen) layers.setRoutes(listOf(results[sel]), 0)
        else layers.setRoutes(results, sel)
    }

    private fun redrawRouteLines() {
        val plan = TripState.plan ?: return
        if (plan.routes.isEmpty()) return
        drawRouteLines(plan.routes, TripState.selectedIndex.coerceIn(0, plan.routes.size - 1))
    }

    private fun distanceLabel(m: Double): String {
        val km = m / 1000.0
        return if (useMetric) "Dist: %.2f km".format(km) else "Dist: %.2f mi".format(km * 0.621371)
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
        // While navigating, chips only after "find alternative route".
        if (results.size < 2 || (navUiActive && !altChoiceOpen)) {
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
            // Letter badge (same as on the map line) + facts; only the selected
            // chip gets the red frame, no chip turns red.
            val sel = index == selected
            val chip = TextView(this)
            chip.text = "${km}km · $eta$costPart$unpavedPart"
            chip.textSize = 12f
            chip.setPadding(dp(6), dp(3), dp(10), dp(3))
            chip.gravity = android.view.Gravity.CENTER_VERTICAL
            chip.compoundDrawablePadding = dp(6)
            chip.setTextColor(ContextCompat.getColor(this, R.color.route_chip_text))
            chip.setTypeface(chip.typeface, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (index < 4) {
                val badge = android.graphics.drawable.BitmapDrawable(resources, LetterBadge.bitmap(this, RouteLabels.letter(index), sel, 17f))
                chip.setCompoundDrawablesWithIntrinsicBounds(badge, null, null, null)
            }
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.route_chip_bg))
                if (sel) setStroke(Math.max(1, dp(3) / 2), activeColor) else setStroke(Math.max(1, dp(1) / 2), ContextCompat.getColor(this@MainActivity, R.color.rg_control_edge))
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            chipSpacing(chip, resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            chip.setOnClickListener { if (index != TripState.selectedIndex) promoteRouteToPrimary(index) }
            row.addView(chip)
        }
        row.visibility = View.VISIBLE
    }

    private fun promoteRouteToPrimary(index: Int) {
        val plan = TripState.plan ?: return
        if (index !in plan.routes.indices) return
        TripState.selectedIndex = index
        if (NavigationEngine.isActive) {
            // Only from "find alternative route" (taps are ignored otherwise).
            val req = altRequests[index]
            if (!altChoiceOpen || req == null) return
            val single = RoutePlan(listOf(plan.routes[index]), 0, null)
            NavigationEngine.switchRoute(single, 0, req)
            closeAltChoice(restore = false)
            TripState.plan = single
            TripState.selectedIndex = 0
            drawPlan(single, 0, fit = false, note = "Route switched", tourist = false)
            return
        }
        // A manual pick is final for this plan: the Weekend sights choice won't override it.
        TripState.scenicCheckedPlan = plan
        drawPlan(plan, index, fit = false, note = "Manually selected route")
    }

    // ---------- Find alternative route (during navigation) ----------

    private var altChoiceOpen = false
    /** Route being followed when the choice opened (to restore it even if
     *  navigation stopped meanwhile). */
    private var altOriginalRoute: RouteResult? = null
    private var altRequests: Map<Int, RouteRequest> = emptyMap()
    private val altPlannerA by lazy { RoutePlanner(this) }
    private val altPlannerB by lazy { RoutePlanner(this) }

    private fun setupAltRouteButton() {
        findViewById<ImageButton>(R.id.alt_route_button).apply {
            setIconActive(this, false)
            setOnClickListener { if (altChoiceOpen) closeAltChoice(restore = true) else findAlternatives() }
        }
    }

    /** Closes the choice. [restore]: back to the route being followed
     *  (when the user closes it without picking). */
    private fun closeAltChoice(restore: Boolean) {
        altChoiceOpen = false
        altRequests = emptyMap()
        setIconActive(findViewById(R.id.alt_route_button), false)
        findViewById<View>(R.id.route_options_row).visibility = View.GONE
        val original = altOriginalRoute
        altOriginalRoute = null
        if (restore) {
            val r = NavigationEngine.route ?: original ?: return
            val single = RoutePlan(listOf(r), 0, null)
            TripState.plan = single
            TripState.selectedIndex = 0
            drawPlan(single, 0, fit = false, note = null, tourist = false)
        }
    }

    /**
     * Other routes from where you are now: the router's alternatives (only
     * possible without remaining stops) plus the route with another
     * preference (Shortest vs Fastest...). Shown as chips with the current
     * route first; tap one to switch.
     */
    private fun findAlternatives() {
        val current = NavigationEngine.route ?: return
        val end = TripState.destination ?: return
        val from = currentPosition() ?: run { showError("No GPS position yet"); return }
        val stops = NavigationEngine.remainingStops() ?: TripState.waypoints.toList()
        val reqA = buildRequest(from, end, stops)
        val otherPref = if (currentPreference == PreferenceUi.FASTEST) RoutePreference.SHORTEST else RoutePreference.FASTEST
        val reqB = reqA.copy(preference = otherPref)
        setIconActive(findViewById(R.id.alt_route_button), true)
        showMessage("Looking for other routes...")
        var resA: RoutePlan? = null
        var resB: RoutePlan? = null
        var answers = 0
        // What is left of the followed route, from here: chip 0 and the
        // "same route" check use this, not the whole trip.
        val progress = NavigationEngine.lastProgress
        val left = if (progress != null && progress.snap != null && current.distanceMeters > 0) {
            val f = (progress.remainingM / current.distanceMeters).coerceIn(0.0, 1.0)
            RouteResult(
                listOf(from) + current.points.drop(progress.snap.segmentIndex + 1),
                progress.remainingM, progress.remainingS,
                current.tollDistanceMeters * f, current.unpavedDistanceMeters * f
            )
        } else current
        fun finish() {
            if (++answers < 2 || !NavigationEngine.isActive) return
            if (NavigationEngine.route !== current) {
                // Rerouted meanwhile: these answers are about an old situation.
                setIconActive(findViewById(R.id.alt_route_button), false)
                showMessage("Route changed meanwhile -- tap again for alternatives")
                return
            }
            val routes = mutableListOf(left)
            val reqs = mutableMapOf<Int, RouteRequest>()
            fun addDistinct(r: RouteResult, req: RouteRequest) {
                val same = routes.any {
                    Math.abs(it.distanceMeters - r.distanceMeters) < it.distanceMeters * 0.02 &&
                        Math.abs(it.durationSeconds - r.durationSeconds) < it.durationSeconds * 0.02
                }
                if (!same) { reqs[routes.size] = req; routes.add(r) }
            }
            resA?.routes?.forEach { addDistinct(it, reqA) }
            resB?.routes?.forEach { addDistinct(it, reqB) }
            if (routes.size < 2) {
                setIconActive(findViewById(R.id.alt_route_button), false)
                showMessage("No other route found")
                return
            }
            val plan = RoutePlan(routes.take(4), 0, null)
            altRequests = reqs
            altChoiceOpen = true
            altOriginalRoute = current
            TripState.plan = plan
            TripState.selectedIndex = 0
            drawPlan(plan, 0, fit = false, note = "Tap a route to switch (yours is highlighted)", tourist = false)
        }
        altPlannerA.plan(reqA) { plan, _ -> resA = plan; finish() }
        altPlannerB.plan(reqB) { plan, _ -> resB = plan; finish() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
