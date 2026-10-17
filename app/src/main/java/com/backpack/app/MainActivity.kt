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
import com.backpack.app.core.Reach
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
import com.backpack.app.tourist.ReachArea
import com.backpack.app.tourist.TouristFinder
import com.backpack.app.location.LocationHub
import com.backpack.app.map.MapLayers
import com.backpack.app.nav.NavigationEngine
import com.backpack.app.route.RoutePlanner
import com.backpack.app.places.PlacesFinder
import com.backpack.app.speed.SpeedLimitLookup
import com.backpack.app.core.SpeedAlarm.Ring as SpeedRing
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

/** 1.4.0: urgency searches still to run (kept over a screen rebuild). */
private var urgencyApplyPending = false

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

    private enum class ModeUi(val mode: TravelMode, val iconRes: Int, val labelRes: Int) {
        WALKING(TravelMode.WALKING, R.drawable.ic_mode_walking, R.string.main_mode_walking),
        CYCLING(TravelMode.CYCLING, R.drawable.ic_mode_cycling, R.string.main_mode_cycling),
        DRIVING(TravelMode.DRIVING, R.drawable.ic_mode_driving, R.string.main_mode_driving)
    }

    private enum class PreferenceUi(val pref: RoutePreference, val iconRes: Int, val labelRes: Int) {
        FASTEST(RoutePreference.FASTEST, R.drawable.ic_pref_fastest, R.string.main_pref_fastest),
        SHORTEST(RoutePreference.SHORTEST, R.drawable.ic_pref_shortest, R.string.main_pref_shortest),
        RECOMMENDED(RoutePreference.RECOMMENDED, R.drawable.ic_pref_balanced, R.string.main_pref_balanced)
    }

    /** Icons only -- the EUR/hour values come from Settings. */
    private enum class UrgencyMode(val iconRes: Int) {
        WEEKEND(R.drawable.ic_urgency_weekend),
        WORK(R.drawable.ic_urgency_work),
        EMERGENCY(R.drawable.ic_urgency_emergency)
    }

    /** 1.1.1 Services panel. Names match [com.backpack.app.core.Places.SERVICES]
     *  (filters and tag rules live there). [essential]: the first group
     *  (the standard set, in its usual order); the others are the extras.
     *  [color]: on the button, in the panel and on the map icons. */
    private enum class PoiCategory(
        val iconRes: Int, val iconName: String, val labelRes: Int, val color: Int? = null, val essential: Boolean = false
    ) {
        NONE(R.drawable.ic_poi_toggle, "", R.string.main_poi_off),
        FOOD(R.drawable.ic_poi_food, "poi_food", R.string.main_poi_food, 0xFF800000.toInt(), true),
        LODGING(R.drawable.ic_poi_lodging, "poi_lodging", R.string.main_poi_lodging, 0xFF3E5468.toInt(), true),
        PARKING(R.drawable.ic_poi_parking, "poi_parking", R.string.main_poi_parking, 0xFF0D3B8C.toInt(), true),
        FUEL(R.drawable.ic_poi_fuel, "poi_fuel", R.string.main_poi_fuel, 0xFF1E6B2E.toInt(), true),
        TOILETS(R.drawable.ic_poi_toilets, "poi_toilets", R.string.main_poi_toilets, 0xFF1F78B4.toInt(), true),
        EMERGENCY(R.drawable.ic_poi_emergency, "poi_emergency", R.string.main_poi_emergency, 0xFFE51401.toInt(), true),
        WATER(R.drawable.ic_poi_water, "poi_water", R.string.main_poi_water, 0xFF0097A7.toInt()),
        SLEEP(R.drawable.ic_poi_sleep, "poi_sleep", R.string.main_poi_sleep, 0xFF6D4C41.toInt()),
        LAUNDRY(R.drawable.ic_poi_laundry, "poi_laundry", R.string.main_poi_laundry, 0xFF5E35B1.toInt()),
        PHARMACY(R.drawable.ic_poi_pharmacy, "poi_pharmacy", R.string.main_poi_pharmacy, 0xFF00897B.toInt()),
        MONEY(R.drawable.ic_poi_money, "poi_money", R.string.main_poi_money, 0xFF827717.toInt()),
        WIFI(R.drawable.ic_poi_wifi, "poi_wifi", R.string.main_poi_wifi, 0xFF3949AB.toInt()),
        BIKE(R.drawable.ic_poi_bike, "poi_bike", R.string.main_poi_bike, 0xFFEF6C00.toInt())
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
    /** 1.2.0: route chips this far (dp) above the message band's base. */
    private val PILLS_ABOVE_BAND_DP = 96
    /** 1.2.1: fixed top (dp) of both button columns. */
    private val COLUMNS_TOP_DP = 100
    /** 1.7.1: portrait columns under the box grown with the 70 dp circle. */
    private val COLUMNS_TOP_PORTRAIT_DP = 116
    /** 1.7.1: landscape on a short screen: never higher than this (speed circle above). */
    private val COLUMNS_MIN_TOP_DP = 86
    /** 1.7.1 (Alex): buttons 15 % bigger. */
    private val BTN_DP = 44
    /** The columns as last fitted without the keyboard (dp), and for which orientation. */
    private var columnsTop = 0
    private var columnsStep = 0
    private var columnsFor: Boolean? = null
    /** 1.8.0 (Alex): bottom row 15 % smaller (49 dp), fainter, clear of the credit line. */
    private val BOTTOM_ROW_DP = 49
    private val BOTTOM_ROW_BOTTOM_DP = 20
    private val MESSAGES_BOTTOM_DP = 70
    /** Edge + column + margin: what a side column takes (landscape messages / chips stay inside).
     *  1.8.0: columns 4 dp from the screen edges. */
    private val COLUMN_ZONE_DP = 56
    private val SPEED_CIRCLE_DP = 70
    private val LIMIT_SIGN_DP = 36
    /** 1.8.0: a spoken instruction stays in the box this long, then the street name. */
    private val INSTRUCTION_SHOWN_MS = 6000L
    /** 1.9.0 (Alex): shorter touches on the empty map do nothing. */
    private val ACCIDENTAL_TOUCH_MS = 150L
    /** 1.8.2: the box's last line (distance / arrival), dp. */
    private val ROUTE_LINE_DP = 15.6f
    /** 1.8.0: full brightness this long after something to see; checked this often. */
    private val WAKE_MS = 10_000L
    private val BRIGHTNESS_TICK_MS = 2000L
    /** The navigation box's top margin (layout). */
    private val NAV_BOX_TOP_DP = 4
    private var currentPreference = PreferenceUi.RECOMMENDED
    private var avoidTolls = false
    private var urgencyMode = UrgencyMode.WEEKEND
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
    private var lastTrackingZoom = 0.0

    // Speed limit (loaded per route by SpeedLimitLookup).
    private val handler = Handler(Looper.getMainLooper())
    /** The speed alarm is sounding (keeps the screen from dimming). */
    private var isBlinking = false

    // POIs.
    /** Everyday places on the map, both layers (tap one for its popup). */
    private var poiResults: List<PoiPlace> = emptyList()

    private val activeColor by lazy { ContextCompat.getColor(this, R.color.rg_active) }
    private val inactiveColor by lazy { ContextCompat.getColor(this, R.color.rg_icon_inactive) }
    /** 1.4.1: map-button icons: day dark red / dark grey, night white / faint white. */
    private val iconOn by lazy { ContextCompat.getColor(this, R.color.rg_icon_on) }
    private val iconOff by lazy { ContextCompat.getColor(this, R.color.rg_icon_off) }
    private val backdropColor by lazy { ContextCompat.getColor(this, R.color.rg_control_backdrop) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOnly = !fine && result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine) {
            LocationHub.acquire(this, LocationHub.LEASE_UI)
            map?.style?.let { activateLocationPuck(it) }
            if (!LocationHub.isGpsEnabled(this)) showError(getString(R.string.main_gps_off))
        } else if (coarseOnly) {
            showError(getString(R.string.main_precise_needed))
        } else {
            showError(getString(R.string.main_permission_denied))
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
        // 1.3.0: walking / cycling: tap the distance line for the height profile.
        routeInfo.setOnClickListener {
            val r = (if (NavigationEngine.isActive) NavigationEngine.route else null) ?: selectedRoute()
            if (r != null && r.elevations.isNotEmpty()) showElevation(r)
        }
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
        var lastBarHeight = 0
        var lastBandHeight = 0
        findViewById<View>(R.id.message_band).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height != lastBandHeight) { lastBandHeight = v.height; v.post { placeLayout() } }
        }
        navBox.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height != lastBarHeight) { lastBarHeight = v.height; v.post { placeLayout() } }
        }
        // 1.8.2: the box's last line refitted when its width changes.
        findViewById<View>(R.id.route_info_group).addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) fitRouteLine() }
        // Speed number centred on its digits, not on the font's line box.
        speedSignText.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> centerDigits(v as TextView) }

        mapView.onCreate(savedInstanceState)
        // 1.3.0: the speed alarm lives outside the screen (also with it off).
        com.backpack.app.speed.SpeedGuard.start(this)
        com.backpack.app.nav.AppRunning.onExit = { finishAndRemoveTask() }
        mapView.addOnDidFailLoadingMapListener { showError(getString(R.string.main_map_load_failed)) }
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
            // Previous route (after a detour / Go here) first; else 1.4.0 the navigation you stopped.
            setOnClickListener { if (TripState.previous != null) resumePreviousRoute() else resumeLastTrip() }
            // 1.4.1: hold = forget it (the Cancel button takes the place again).
            setOnLongClickListener {
                if (TripState.previous != null) {
                    TripState.previous = null
                    showMessage(getString(R.string.main_previous_forgotten))
                } else {
                    com.backpack.app.nav.LastTrip.clear(this@MainActivity)
                    showMessage(getString(R.string.main_last_nav_forgotten))
                }
                refreshResumeButton()
                true
            }
        }
        findViewById<TextView>(R.id.credit_text).setOnClickListener {
            com.backpack.app.security.AppLock.unlock(this) { SettingsPopup.show(this, settingsHost, SettingsPopup.Page.CREDITS) }
        }

        setupAddressSearch()
        // 1.5.0: first start without any routing key: the key guide, once.
        // 1.7.0: not while the shared starter key's trial lasts (the guide comes when it ends).
        if (Prefs.routingKeys(this).isEmpty() && !Prefs.settings(this).getBoolean(KEY_GUIDE_SHOWN, false)) {
            handler.postDelayed({
                if (!isFinishing && Prefs.routingKeys(this).isEmpty()) {
                    Prefs.settings(this).edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
                    showKeyGuide()
                }
            }, 1500L)
        }
        setupMyPlacesButton()
        setupNavigationButton()
        setupPoiToggle()
        setupSightsButton()
        setupModeButtons()
        setupPreferenceRow()
        setupTollToggle()
        setupUrgencyButton()
        // A turn of the phone right after an urgency tap: its searches still run.
        if (urgencyApplyPending) urgencyButton.postDelayed(urgencyApply, 1000L)
        setupRouteOptionsButton()
        setupAltRouteButton()
        styleBottomButtons()
        setupSoundButton()
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
        com.backpack.app.security.AppLock.onForeground()
        mapView.onStart()
        LocationHub.addListener(uiFixListener)
        NavigationEngine.addListener(navListener)
        if (LocationHub.hasPermission(this)) LocationHub.acquire(this, LocationHub.LEASE_UI)
        // 1.4.0: your order of kinds ranks the sights.
        Tourist.order = com.backpack.app.core.SightGroup.parseOrder(sightsPrefs().getString(Settings.KEY_SIGHT_ORDER, null))
        // 1.3.0: open until expressly exited; the service keeps the rest going in the background.
        com.backpack.app.speed.SpeedGuard.start(this)
        com.backpack.app.speed.SpeedGuard.addListener(speedListener)
        com.backpack.app.speed.SpeedGuard.addSoundListener(speedSoundListener)
        // 1.8.1: "last exit before the toll" also as a red alert line.
        com.backpack.app.speed.TollExitWatch.ui = { text -> if (!isDestroyed) showError(text) }
        com.backpack.app.tourist.SightHeadsUp.ui = headsUpUi
        com.backpack.app.nav.AppRunning.onExit = { finishAndRemoveTask() }
        com.backpack.app.nav.AppRunning.markUiVisible(true)
        com.backpack.app.nav.NavigationService.keepRunning(this)
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

    /** 1.5.0: back from the browser with a key copied: the guide picks it up. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.backpack.app.ui.KeyGuide.lookAtClipboard(this)
    }

    private val KEY_GUIDE_SHOWN = "ors_key_guide_shown"

    private fun showKeyGuide() {
        com.backpack.app.ui.KeyGuide.show(this) {
            // A destination was waiting for a route: ask again with the new key.
            val end = TripState.destination
            if (!isFinishing && !isDestroyed && end != null && TripState.plan == null && !NavigationEngine.isActive) {
                resolveStartPoint()?.let { requestBestRoute(it, end) }
            }
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
            showError(getString(R.string.main_gps_off))
        }
        // 1.8.0 (Alex): the map screen never sleeps while it is in front; the
        // brightness follows what is going on (see applyBrightness).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeDisplay()
        handler.removeCallbacks(brightnessTick)
        handler.postDelayed(brightnessTick, BRIGHTNESS_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(nightCheckRunnable)
        handler.removeCallbacks(brightnessTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        map?.cameraPosition?.let { cp ->
            cp.target?.let { Prefs.saveCamera(this, it.latitude, it.longitude, cp.zoom) }
        }
        mapView.onPause()
        stopBlinking()
    }

    override fun onStop() {
        super.onStop()
        com.backpack.app.security.AppLock.onBackground()
        LocationHub.removeListener(uiFixListener)
        NavigationEngine.removeListener(navListener)
        com.backpack.app.speed.SpeedGuard.removeListener(speedListener)
        com.backpack.app.speed.SpeedGuard.removeSoundListener(speedSoundListener)
        com.backpack.app.speed.TollExitWatch.ui = null
        if (com.backpack.app.tourist.SightHeadsUp.ui === headsUpUi) com.backpack.app.tourist.SightHeadsUp.ui = null
        // GPS keeps running only if navigation (or, by car, the background service) holds its own lease.
        LocationHub.release(this, LocationHub.LEASE_UI)
        com.backpack.app.nav.AppRunning.markUiVisible(false)
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
        if (::urgencyButton.isInitialized) urgencyButton.removeCallbacks(urgencyApply)
        com.backpack.app.ui.KeyGuide.close()
        actionFades.values.forEach { it.cancel() }
        actionFades.clear()
        // Leaving the app (not a rebuild): a place still waiting for GPS is dropped,
        // it must not come back unasked at the next start.
        if (isFinishing) {
            TripState.pendingLink = null
            TripState.pendingLinkMode = null
        }
        planner.cancel()
        handler.removeCallbacksAndMessages(null)
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
        m.uiSettings.isTiltGesturesEnabled = navUiActive

        m.addOnMapClickListener { p ->
            hideSuggestions()
            // 1.2.1: choosing a route, a place or a destination: no more following.
            if (!navUiActive) stopPlanFollow()
            // A tap on the map closes the search box, nothing else.
            if (searchOpen) { closeSearch(); return@addOnMapClickListener true }
            val screen = m.projection.toScreenLocation(p)
            // 1.1.0: the destination pin: its town's travel guide.
            if (layers.pinAt(m, screen) == "dest") { showDestinationGuide(); return@addOnMapClickListener true }
            val touristKey = layers.touristAt(m, screen)
            val tourist = touristKey?.let { k -> touristResults.firstOrNull { it.key == k } }
            if (tourist != null) {
                // A place of another proposed route: that route is selected
                // first, so the extra time shown is for its own route.
                touristDimOwner[tourist.key]?.let { if (!NavigationEngine.isActive) promoteRouteToPrimary(it) }
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
                // Touching a route only selects it (its figures in the route
                // line); nothing is recalculated, Go starts it.
                if (idx != TripState.selectedIndex) promoteRouteToPrimary(idx)
            } else if (choosing && !waypointModeArmed && TripState.plan != null && layers.selectedRouteAt(m, screen)) {
                // The selected route itself: never a new destination.
                if (!NavigationEngine.isActive) showMessage(getString(R.string.main_route_selected_go, RouteLabels.letter(TripState.selectedIndex)))
            } else if (!overviewOn && lastTouchMs >= ACCIDENTAL_TOUCH_MS) {
                // 1.9.0 (Alex): a touch shorter than 0.15 s on the empty map is an accident.
                handleDestinationTap(LatLon(p.latitude, p.longitude))
            }
            true
        }
        // Overview: any touch of the map restarts its 20 s.
        m.addOnCameraMoveStartedListener { reason ->
            if (overviewOn && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) scheduleOverviewReturn()
            // 1.2.1: moving the map by hand while planning = looking around: no following.
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) lastMapGestureMs = System.currentTimeMillis()
            if (!navUiActive && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) stopPlanFollow()
        }
        // 1.4.2: the centre button follows the map's orientation (compass).
        m.addOnCameraMoveListener { refreshNorthMark() }
        m.addOnCameraIdleListener { refreshNorthMark() }
        // 1.9.0 (Alex): hold the map: one menu (go, stop, start, save).
        m.addOnMapLongClickListener { p ->
            mapView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showHoldMenu(LatLon(p.latitude, p.longitude))
            true
        }
        m.addOnCameraIdleListener {
            if (flattenOnIdle && !navUiActive) {
                flattenOnIdle = false
                val cp = m.cameraPosition
                if (cp.tilt > 0.5 || Math.abs(cp.bearing) > 0.5) cp.target?.let { flyFlat(it, cp.zoom, keepFollow = true) }
            }
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
            layers.labelFont = if (url.contains("versatiles")) "noto_sans_regular" else "Noto Sans Regular"
            layers.install(style)
            if (LocationHub.hasPermission(this)) activateLocationPuck(style)
            restoreTripOnMap()
            if (activeServices().isNotEmpty()) queryPois(force = true)
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
            .minZoomIconScale(2.0f) // 1.3.0: back to 2.0 (Alex)
            .maxZoomIconScale(2.0f)
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
            keepArrowOnScreen(location)
            maybeAutoFollow(location)
            // 1.8.1: sights ahead without a route (by car SpeedGuard does it, screen off too).
            if (currentMode != ModeUi.DRIVING) com.backpack.app.tourist.FreeSightTips.onFix(this, location)
            // Car: SpeedGuard watches and reports (speedListener); else your speed only.
            if (currentMode != ModeUi.DRIVING && navUiActive) updateSpeedOnly(location)
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
        showMessage(getString(R.string.main_gps_weak))
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
            refreshNorthMark()
            showMessage(getString(if (northUp) R.string.main_north_up else R.string.main_direction_up))
            return
        }
        val current = LocationHub.lastGoodFix
        if (current == null) {
            showError(getString(if (LocationHub.isGpsEnabled(this)) R.string.main_waiting_fix else R.string.main_gps_off))
            return
        }
        // 1.8.0 (Alex): two modes, both centred on your arrow: your direction
        // up in 3D <-> north up flat. Map moved by hand: back to the last one.
        val next = when (planFollow) {
            PlanFollow.OFF -> lastPlanFollow
            PlanFollow.NORTH -> PlanFollow.HEADING
            PlanFollow.HEADING -> PlanFollow.NORTH
        }
        if (!puckActive()) {
            flyFlat(LatLng(current.latitude, current.longitude), maxOf(m.cameraPosition.zoom, 15.0))
            return
        }
        startPlanFollow(next)
        showMessage(getString(if (next == PlanFollow.NORTH) R.string.main_following_north else R.string.main_following_heading))
    }

    /** Planning / no route: follow your arrow, centred, in [f] (NORTH flat, HEADING 3D). */
    private fun startPlanFollow(f: PlanFollow) {
        val m = map ?: return
        if (!puckActive() || f == PlanFollow.OFF) return
        val lc = m.locationComponent
        if (f == PlanFollow.NORTH) lc.setCameraMode(CameraMode.TRACKING_GPS_NORTH, 750L, maxOf(m.cameraPosition.zoom, 15.0), 0.0, 0.0, null)
        else lc.setCameraMode(CameraMode.TRACKING_GPS, 750L, maxOf(m.cameraPosition.zoom, 16.0), null,
            Prefs.navTilt(this, currentMode == ModeUi.DRIVING), null)
        flattenOnIdle = false
        setPlanFollow(f)
    }

    /** 1.8.0: the last follow mode chosen, kept (Waze-like default: your direction up, 3D). */
    private var lastPlanFollow = PlanFollow.HEADING
    /** Last time the map was moved by hand (no automatic following for a while). */
    private var lastMapGestureMs = 0L

    /**
     * 1.8.0 (Alex, as Waze does without a route): moving with no route and no
     * navigation, the map follows your arrow again (centred, your last mode)
     * 20 s after you last moved it by hand.
     */
    private fun maybeAutoFollow(location: Location) {
        if (navUiActive || TripState.plan != null || planFollow != PlanFollow.OFF || searchOpen || altChoiceOpen) return
        if (!location.hasSpeed() || location.speed < 2.5f) return
        if (System.currentTimeMillis() - lastMapGestureMs < 20_000L) return
        startPlanFollow(lastPlanFollow)
    }

    // ---------- 1.2.1 following you while planning (not navigating) ----------

    private enum class PlanFollow { OFF, NORTH, HEADING }
    private var planFollow = PlanFollow.OFF
    /** The map was moved by hand in 3D: made flat again once it stops. */
    private var flattenOnIdle = false

    private fun setPlanFollow(f: PlanFollow) {
        planFollow = f
        if (f != PlanFollow.OFF && f != lastPlanFollow) {
            lastPlanFollow = f
            getSharedPreferences("backpack_toggle_state", MODE_PRIVATE).edit().putString("plan_follow", f.name).apply()
        }
        findViewById<ImageButton>(R.id.locate_button)?.let { setIconActive(it, f != PlanFollow.OFF) }
        refreshNorthMark()
    }

    /**
     * Centre button as a compass (1.4.2; 1.5.1 as agreed with Alex):
     * - map NOT north up (navigating or not, centred or not): the ring and
     *   N/S/E/W turn with the map around a red arrow that stays pointing up;
     * - map north up and centred on you: letters still, the arrow shows your
     *   heading (GPS, while moving);
     * - map north up, not centred: letters still, the dot ("centre on me").
     * Only the ring, letters and arrow turn, never the button itself.
     */
    private fun refreshNorthMark() {
        val b = findViewById<ImageButton>(R.id.locate_button) ?: return
        if (b.rotation != 0f) b.rotation = 0f
        val m = map
        var bearing = m?.cameraPosition?.bearing ?: 0.0
        bearing = ((bearing % 360.0) + 360.0) % 360.0
        val northNow = bearing < 1.5 || bearing > 358.5
        val centred = if (navUiActive) !overviewOn && m != null && puckActive() && m.locationComponent.cameraMode != CameraMode.NONE
            else planFollow != PlanFollow.OFF
        val dot = northNow && !centred
        val image: android.graphics.drawable.Drawable = if (dot) dotIcon else ringRotate
        if (b.drawable !== image) {
            val tint = b.imageTintList
            b.setImageDrawable(image)
            b.imageTintList = tint
        }
        val roseLevel = if (northNow) 0 else levelOf(-bearing.toFloat())
        ringRotate.level = roseLevel
        lettersRotate.level = roseLevel
        val heading = LocationHub.lastGoodFix?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 0.8f }?.bearing ?: 0f
        needleRotate.level = if (northNow && centred) levelOf(heading) else 0
        compassLayer.getDrawable(1).alpha = if (dot) 0 else 255
        if (b.foreground !== compassLayer) b.foreground = compassLayer else b.invalidate()
    }

    private fun levelOf(deg: Float): Int = ((((deg % 360f) + 360f) % 360f) / 360f * 10000f).toInt()

    private fun rotating(res: Int) = android.graphics.drawable.RotateDrawable().apply {
        drawable = ContextCompat.getDrawable(this@MainActivity, res)!!.mutate()
        fromDegrees = 0f
        toDegrees = 360f
    }

    private val dotIcon by lazy { ContextCompat.getDrawable(this, R.drawable.ic_location_marker)!!.mutate() }
    private val ringRotate by lazy { rotating(R.drawable.ic_compass_ring) }
    private val lettersRotate by lazy { rotating(R.drawable.compass_letters) }
    private val needleRotate by lazy { rotating(R.drawable.ic_compass_needle) }

    /** Letters over the whole button; the arrow inside the ring (1.8.0: bigger, 5 dp in). */
    private val compassLayer by lazy {
        android.graphics.drawable.LayerDrawable(arrayOf(
            lettersRotate,
            android.graphics.drawable.InsetDrawable(needleRotate, dp(5))
        ))
    }

    /** Searching the map, choosing routes or places: no more following. */
    private fun stopPlanFollow() {
        if (planFollow == PlanFollow.OFF) return
        if (puckActive() && !navUiActive) {
            map?.locationComponent?.cameraMode = CameraMode.NONE
            if (planFollow == PlanFollow.HEADING) flattenOnIdle = true
        }
        // setPlanFollow(OFF) keeps lastPlanFollow (the mode to come back to);
        // the app moved the map for you: no automatic following for a while.
        lastMapGestureMs = System.currentTimeMillis()
        planFollow = PlanFollow.OFF
        findViewById<ImageButton>(R.id.locate_button)?.let { setIconActive(it, false) }
        refreshNorthMark()
    }

    /** North up: the map moves only when the arrow nears the edge of the screen. */
    private fun keepArrowOnScreen(location: android.location.Location) {
        // 1.8.0: north up is centred tracking now; only without the arrow layer.
        if (planFollow != PlanFollow.NORTH || navUiActive || puckActive()) return
        val m = map ?: return
        val here = LatLng(location.latitude, location.longitude)
        val pt = m.projection.toScreenLocation(here)
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (pt.x < w * 0.15f || pt.x > w * 0.85f || pt.y < h * 0.2f || pt.y > h * 0.8f) {
            flyFlat(here, m.cameraPosition.zoom, keepFollow = true)
        }
    }

    // ---------- Toggle state (same keys as Step 38) ----------

    private fun loadToggleState() {
        val prefs = getSharedPreferences("backpack_toggle_state", MODE_PRIVATE)
        lastPlanFollow = if (prefs.getString("plan_follow", null) == PlanFollow.NORTH.name) PlanFollow.NORTH else PlanFollow.HEADING
        currentMode = enumOr(prefs.getString("mode", null), currentMode)
        currentPreference = enumOr(prefs.getString("preference", null), currentPreference)
        urgencyMode = enumOr(prefs.getString("urgency", null), urgencyMode)
        avoidTolls = prefs.getBoolean("avoid_tolls", avoidTolls)
        loadServices()
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

    private var bubbleFadeOutRunnable: Runnable? = null

    /** Routine messages: always in the message band (between the arrow and
     *  the bottom buttons), under any error. */
    private fun showMessage(text: String, onTap: (() -> Unit)? = null, showMs: Long = 3000L) {
        bubbleFadeOutRunnable?.let { handler.removeCallbacks(it) }
        // 1.1.1: some messages can be tapped (e.g. "add this sight as a stop").
        if (onTap != null) {
            messageBubble.isClickable = true
            messageBubble.setOnClickListener { messageBubble.visibility = View.GONE; onTap() }
        } else {
            messageBubble.setOnClickListener(null)
            messageBubble.isClickable = false
        }
        messageBubble.text = text
        messageBubble.visibility = View.VISIBLE
        wakeDisplay()
        messageBubble.animate().cancel()
        messageBubble.alpha = 0f
        messageBubble.animate().alpha(1f).setDuration(200).start()
        val fadeOut = Runnable {
            messageBubble.animate().alpha(0f).setDuration(1800).withEndAction {
                messageBubble.visibility = View.GONE
            }.start()
        }
        bubbleFadeOutRunnable = fadeOut
        handler.postDelayed(fadeOut, showMs)
    }

    private var errorFadeOutRunnable: Runnable? = null

    /** Genuine failures, in the same band, just above the routine message.
     *  With [retry], the bubble says "tap to retry" and stays a little longer. */
    private fun showError(text: String, retry: (() -> Unit)? = null) {
        errorFadeOutRunnable?.let { handler.removeCallbacks(it) }
        wakeDisplay()
        errorBubble.text = if (retry != null) getString(R.string.main_tap_to_retry, text) else text
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
        button.imageTintList = ColorStateList.valueOf(if (active) iconOn else iconOff)
    }

    /**
     * Status ring on the standard grey backdrop (RedGRPS map-control look):
     * red when [on], the faint dark edge otherwise. The backdrop itself stays
     * the same light grey on every button, so no button turns dark.
     */
    private fun setStatusRing(button: ImageButton, on: Boolean) {
        (button.background?.mutate() as? GradientDrawable)?.setStroke(
            if (on) dp(2) else Math.max(1, dp(1) * 3 / 4),
            if (on) iconOn else ContextCompat.getColor(this, R.color.rg_control_edge)
        )
    }

    /**
     * 1.4.1 right slot of the bottom row: the sound mode. Navigating: tap
     * cycles Full -> Navigation -> Alerts only (kept for the next trips).
     * Not navigating: always Alerts only. Dangers are heard in every mode.
     */
    private fun setupSoundButton() {
        findViewById<ImageButton>(R.id.sound_button).setOnClickListener {
            if (!NavigationEngine.isActive) {
                showMessage(getString(R.string.main_alerts_only_idle))
                return@setOnClickListener
            }
            val next = when (Prefs.navSoundMode(this)) {
                Prefs.SoundMode.FULL -> Prefs.SoundMode.NAV
                Prefs.SoundMode.NAV -> Prefs.SoundMode.ALERTS
                Prefs.SoundMode.ALERTS -> Prefs.SoundMode.FULL
            }
            Prefs.setNavSoundMode(this, next)
            NavigationEngine.soundModeChanged()
            refreshSoundButton()
            showMessage(getString(when (next) {
                Prefs.SoundMode.FULL -> R.string.main_sound_full
                Prefs.SoundMode.NAV -> R.string.main_sound_nav
                Prefs.SoundMode.ALERTS -> R.string.main_sound_alerts
            }))
        }
        refreshSoundButton()
    }

    private fun refreshSoundButton() {
        val b = findViewById<ImageButton>(R.id.sound_button) ?: return
        // Without navigation Alerts only is simply how it is: no button.
        b.visibility = if (NavigationEngine.isActive) View.VISIBLE else View.GONE
        val icon = when (Prefs.soundMode(this)) {
            Prefs.SoundMode.FULL -> R.drawable.ic_sound_full
            Prefs.SoundMode.NAV -> R.drawable.ic_sound_nav
            Prefs.SoundMode.ALERTS -> R.drawable.ic_sound_alerts
        }
        if (b.tag != icon) { b.tag = icon; b.setImageResource(icon) }
    }

    /** 1.4.1: the bottom row (navigation actions): circled two-tone icons
     *  filling the round backdrop, in the "on" colour. */
    private fun styleBottomButtons() {
        for (id in listOf(R.id.nav_go_button, R.id.clear_route_button, R.id.resume_button, R.id.sound_button)) {
            findViewById<ImageButton>(id).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.icon_backdrop_faint)
                // At rest: faint ("standby", waiting for the next action).
                imageTintList = ColorStateList.valueOf(iconOff)
                // Touched: the active colour 1 s, then 1 s back to faint (taps and holds alike).
                setOnTouchListener { v, e ->
                    if (e.action == android.view.MotionEvent.ACTION_DOWN) flashAction(v as ImageButton)
                    false
                }
            }
        }
    }

    private val actionFades = HashMap<Int, android.animation.ValueAnimator>()

    private fun flashAction(b: ImageButton) {
        actionFades.remove(b.id)?.cancel()
        b.imageTintList = ColorStateList.valueOf(iconOn)
        val a = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), iconOn, iconOff).apply {
            startDelay = 1000L
            duration = 1000L
            addUpdateListener { b.imageTintList = ColorStateList.valueOf(it.animatedValue as Int) }
        }
        actionFades[b.id] = a
        a.start()
    }

    /** 1.4.1: Android's status and navigation bars hidden while navigating (a swipe shows them for a moment). */
    private fun setFullScreen(on: Boolean) {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
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

    // ---------- Address search ----------

    private val searchClient = SearchClient()
    private var suggestRunnable: Runnable? = null

    private fun setupAddressSearch() {
        val searchButton = findViewById<ImageButton>(R.id.search_button)
        val searchInput = findViewById<EditText>(R.id.search_input)
        setIconActive(searchButton, false)
        // 1.0.4: the magnifier opens / closes the floating search box.
        searchButton.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        findViewById<ImageButton>(R.id.search_mic).apply {
            setIconActive(this, true)
            setOnClickListener { startVoiceSearch() }
        }
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
                if (text.isEmpty() && searchInput.hasFocus()) { searchClient.cancel(); showRecent(); return }
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
        stopPlanFollow()
        searchOpen = true
        val searchInput = findViewById<EditText>(R.id.search_input)
        searchInput.visibility = View.VISIBLE
        setIconActive(findViewById(R.id.search_button), true)
        findViewById<View>(R.id.search_mic).visibility = if (voiceAvailable()) View.VISIBLE else View.GONE
        placeLayout()
        searchInput.requestFocus()
        if (searchInput.text.isNullOrBlank()) showRecent()
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
        findViewById<View>(R.id.search_mic).visibility = View.GONE
        setIconActive(findViewById(R.id.search_button), false)
        placeLayout()
    }

    /** Enter: first result straight away (as before). */
    private fun submitSearch(text: String) {
        suggestRunnable?.let { handler.removeCallbacks(it) }
        showMessage(getString(R.string.main_searching))
        closeSearch()
        searchClient.search(text, currentPosition() ?: cameraCenter()) { places, error ->
            val first = places.firstOrNull()
            if (first == null) showError(error ?: getString(R.string.main_address_not_found)) else goToPlace(first)
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
            showError(getString(R.string.main_link_unreadable))
            return
        }
        if (NavigationEngine.isActive) {
            val p = Popup(this, getString(R.string.main_new_destination))
            p.text(getString(R.string.main_link_sent, link.label ?: link.query ?: getString(R.string.main_a_place)))
            p.button(getString(R.string.main_go_here), primary = true) { p.dismiss(); resolveLink(link) }
            p.button(getString(R.string.main_cancel)) { p.dismiss() }
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
        showMessage(getString(R.string.main_looking_for, query))
        searchClient.search(query, currentPosition() ?: cameraCenter()) { places, error ->
            val first = places.firstOrNull()
            if (first == null) showError(error ?: getString(R.string.main_place_not_found, query))
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
        flyFlat(LatLng(point.lat, point.lon), 14.0)
        if (resolveStartPoint() == null) {
            TripState.pendingLink = point to label
            TripState.pendingLinkMode = mode
            showMessage(getString(R.string.main_waiting_gps_plan, label))
            return
        }
        applyMode()
        // A destination, never a stop, even if stop mode was armed.
        if (waypointModeArmed) {
            waypointModeArmed = false
            setIconActive(findViewById(R.id.waypoint_button), false)
        }
        handleDestinationTap(point)
        showMessage(getString(R.string.main_check_route_go, label))
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
        addRecent(place)
        val point = place.point
        if (NavigationEngine.isActive) {
            if (overviewOn) scheduleOverviewReturn()
            showPlacePopup(place.name, place.detail.ifBlank { place.name }, point, null, "")
            return
        }
        flyFlat(LatLng(point.lat, point.lon), 14.0)
        handleDestinationTap(point)
    }

    private fun closeSearchKeyboard() {
        val searchInput = findViewById<EditText>(R.id.search_input)
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------- 1.1.1 Recent searches (phone only; 1.9.0 encrypted, behind the lock) ----------

    private fun recentPlaces(forList: Boolean = false): List<com.backpack.app.core.Place> = try {
        // 1.9.0: not listed while the app is locked (nobody else sees where you went);
        // still kept up to date underneath.
        if (forList && !com.backpack.app.security.AppLock.isOpen(this)) throw IllegalStateException()
        com.backpack.app.security.PrivateData.migrate(this)
        val a = org.json.JSONArray(com.backpack.app.security.Vault.getString(this, com.backpack.app.security.PrivateData.RECENT_SEARCHES) ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> com.backpack.app.core.Place(o.optString("n"), o.optString("d"), LatLon(o.optDouble("a"), o.optDouble("o"))) }
        }
    } catch (e: Exception) { emptyList() }

    private fun saveRecent(list: List<com.backpack.app.core.Place>) {
        val a = org.json.JSONArray()
        for (p in list.take(10)) a.put(org.json.JSONObject().put("n", p.name).put("d", p.detail).put("a", p.point.lat).put("o", p.point.lon))
        com.backpack.app.security.Vault.putString(this, com.backpack.app.security.PrivateData.RECENT_SEARCHES, a.toString())
    }

    /** Newest first, the same place once. */
    private fun addRecent(place: com.backpack.app.core.Place) {
        saveRecent(listOf(place) + recentPlaces().filter { Geo.distance(it.point, place.point) > 30.0 })
    }

    /** Empty search box: the last destinations (hold one to forget it). */
    private fun showRecent() {
        if (!com.backpack.app.security.AppLock.isOpen(this)) { com.backpack.app.security.AppLock.unlock(this) { if (searchOpen) showRecent() }; return }
        val recent = recentPlaces(forList = true)
        if (recent.isEmpty()) { hideSuggestions(); return }
        showSuggestions(recent, recent = true)
    }

    // ---------- 1.1.1 Voice search (the phone's own recognizer) ----------

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode != RESULT_OK || text.isNullOrEmpty()) return@registerForActivityResult
        val input = findViewById<EditText>(R.id.search_input)
        input.requestFocus()
        input.setText(text)
        input.setSelection(text.length)
    }

    private fun voiceAvailable(): Boolean = try {
        packageManager.queryIntentActivities(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    } catch (e: Exception) { false }

    private fun voiceIntent() = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))

    /**
     * 1.5.1: the app that really answers Backpack's microphone (the speech
     * request), not Android's "voice input" service setting (not used here).
     * Null = Android will ask (several apps, none chosen as default).
     */
    private fun voiceHandlerName(): String? {
        val ri = try { packageManager.resolveActivity(voiceIntent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) } catch (e: Exception) { null }
        val pkg = ri?.activityInfo?.packageName ?: return getString(R.string.voice_handler_unknown)
        // The "choose an app" screen (android, or com.android.intentresolver on newer phones).
        val all = try { packageManager.queryIntentActivities(voiceIntent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) } catch (e: Exception) { emptyList() }
        if (pkg == "android" || pkg.contains("resolver", ignoreCase = true) ||
            ri.activityInfo.name.contains("Resolver") || (all.size > 1 && all.none { it.activityInfo.packageName == pkg })) return null
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
    }

    private fun startVoiceSearch() {
        val prefs = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
        fun launch() {
            try { voiceLauncher.launch(voiceIntent()) } catch (e: Exception) { showError(getString(R.string.voice_none)) }
        }
        if (prefs.getBoolean(Settings.KEY_VOICE_INFO_SHOWN, false)) { launch(); return }
        val p = Popup(this, getString(R.string.voice_title))
        val name = voiceHandlerName()
        p.text(if (name == null) getString(R.string.voice_handler_choose) else getString(R.string.voice_handler, name))
        p.text(getString(R.string.voice_offline_tip), 12f)
        p.button(getString(R.string.voice_cancel)) { p.dismiss() }
        p.button(getString(R.string.voice_continue), primary = true) {
            p.dismiss()
            prefs.edit().putBoolean(Settings.KEY_VOICE_INFO_SHOWN, true).apply()
            launch()
        }
        p.show()
    }

    private fun showSuggestions(places: List<com.backpack.app.core.Place>, recent: Boolean = false) {
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
            if (recent) row.setOnLongClickListener {
                saveRecent(recentPlaces().filter { Geo.distance(it.point, place.point) > 30.0 })
                showRecent()
                true
            }
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
    private val detourPlanner by lazy { RoutePlanner(this).apply { countsForTrial = false } }
    /** Well-known places are shown in Weekend mode or with the Sights POI category. */
    private val touristOn: Boolean
        // 1.0.9: Weekend's default layer, or the Sights category; never in Emergency (no distractions).
        // 1.2.0: Weekend mode with its small camera on, and at least one kind (Settings > Sights).
        get() = sightsSwitchOn() && sightGroups().isNotEmpty()
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
            refreshSightsButton()
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
        refreshSightsButton()
    }

    private fun sightsMaxFraction(): Double =
        getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
            .getInt(Settings.KEY_SIGHTS_MAX_PCT, Settings.DEFAULT_SIGHTS_MAX_PCT) / 100.0

    /** 1.2.1: no message any more -- the faint "Searching places" line says it. */
    private fun onPlacesServerSwitch() {
        searchingSwitched = true
        pokeSearching()
    }

    // ---------- 1.2.1 faint "Searching places..." line with a spinner ----------

    private var searchingStartedAtMs = 0L
    private var searchingSwitched = false
    private val searchingPoll = object : Runnable {
        override fun run() { if (refreshSearching()) handler.postDelayed(this, 500L) }
    }

    private fun searchingNow(): Boolean =
        (svcSlot.busyGen != 0 && svcSlot.busyGen == svcSlot.gen) || touristPendingRoute != null || touristPendingCenter != null

    /** A places search started (or changed server): show the line, watch until done. */
    private fun pokeSearching() {
        if (!searchingNow()) return
        if (findViewById<View>(R.id.searching_line).visibility != View.VISIBLE) searchingStartedAtMs = System.currentTimeMillis()
        refreshSearching()
        handler.removeCallbacks(searchingPoll)
        handler.postDelayed(searchingPoll, 500L)
    }

    /** Shows / hides the line; true while it is shown. Never longer than 2 min. */
    private fun refreshSearching(): Boolean {
        val line = findViewById<View>(R.id.searching_line) ?: return false
        val on = searchingNow() && System.currentTimeMillis() - searchingStartedAtMs < 120_000L
        if (!on) searchingSwitched = false
        findViewById<TextView>(R.id.searching_text).text =
            getString(if (searchingSwitched) R.string.main_searching_places_switch else R.string.main_searching_places)
        val v = if (on) View.VISIBLE else View.GONE
        if (line.visibility != v) line.visibility = v
        return on
    }

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
            pokeSearching()
            val scenic = urgencyMode == UrgencyMode.WEEKEND && !NavigationEngine.isActive && !plan.tollDecision &&
                plan.routes.size > 1 && sightsMaxFraction() > 0.0
            // All routes of the plan before Go (picking another chip then
            // needs no new search); only the followed one while navigating.
            val lines = if (navUiActive) listOf(route.points) else plan.routes.map { it.points }
            val startIndex = TripState.selectedIndex
            // While navigating: from where you are (the squares behind you are not needed).
            val fromM = if (navUiActive) (NavigationEngine.lastProgress?.alongM ?: 0.0) else 0.0
            touristFinder.alongRoutes(lines, currentMode.mode, fromM, corridorM(), { onPlacesServerSwitch() },
                // Before Go: the first 40 km (faster); while driving the rest follows.
                aheadM = if (navUiActive) null else minOf(Tourist.searchAheadM(currentMode.mode), 40_000.0),
                onPartial = { part ->
                    // The nearest squares answered: show them now (the rest follows).
                    if (TripState.plan === plan && touristOn) {
                        TripState.touristCandidates = part
                        showTouristFor(selectedRoute())
                    }
                }
            ) { all, error, coveredM ->
                // Only a search that answered counts as covering the stretch;
                // a failed one is tried again 2 min later, not on every fix.
                if (all != null && error == null) TripState.touristCoveredM = coveredM
                else touristRetryAfterMs = System.currentTimeMillis() + 120_000L
                onTouristCandidates(all, error, plan, null, announce = !extend, scenic = scenic && !extend,
                    startIndex = startIndex, keepOnFailure = extend)
            }
            return
        }
        // No route: well-known places within reach of you / the map centre
        // (Weekend and the Sights category alike, 1.0.7 / 1.0.9).
        refreshReach(force)
    }

    // ---------- Sights within reach (1.0.7) ----------

    private var reachOrigin: LatLon? = null
    private var reachRetryAfterMs = 0L
    private var reachSettings: String? = null
    /** "within 1 h on foot of you" style text for the result message. */
    private var reachLabel: String? = null

    /** "within [about] <time> <how> of you / the map centre" (1.6.0: from resources). */
    private fun reachText(time: String, how: String, exact: Boolean, aroundMe: Boolean): String = getString(
        if (aroundMe) { if (exact) R.string.main_reach_you else R.string.main_reach_you_about }
        else { if (exact) R.string.main_reach_centre else R.string.main_reach_centre_about },
        time, how)

    private fun sightsReachMinutes() = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
        .getInt(Settings.KEY_SIGHTS_REACH_MIN, Reach.DEFAULT_MINUTES)

    /**
     * Well-known places reachable within the chosen time (Settings > Places)
     * in the current travel mode, around the map centre. Searched
     * again after you moved ~2 km (around you) or the map ~3 km (centre).
     */
    private fun refreshReach(force: Boolean) {
        // 1.2.0: always around the centre of the map (your position while it follows you).
        val aroundMe = false
        val minutes = sightsReachMinutes()
        val origin = (if (aroundMe) currentPosition() else null) ?: cameraCenter() ?: return
        val settings = "$aroundMe/${currentMode.name}/$minutes"
        val prev = reachOrigin
        // Moved by a quarter of the reach (at least 2 km around you, 3 km
        // for the map centre): the area changed enough to search again.
        val step = maxOf(if (aroundMe) 2000.0 else 3000.0, 0.25 * minOf(Reach.radiusM(currentMode.mode, minutes), Reach.MAX_SEARCH_M))
        val moved = prev == null || Geo.distance(prev, origin) >= step
        if (!force && settings == reachSettings && !moved) return
        // After a failed search: again 2 min later at the earliest (not on every fix).
        if (!force && System.currentTimeMillis() < reachRetryAfterMs) return
        val announce = force || settings != reachSettings
        reachOrigin = origin
        reachSettings = settings
        touristPendingRoute = null
        touristPendingCenter = origin
        pokeSearching()
        val mode = currentMode
        val time = if (minutes < 60) "$minutes min" else "${minutes / 60} h"
        val how = when (mode.mode) {
            com.backpack.app.core.TravelMode.WALKING -> getString(R.string.main_on_foot)
            com.backpack.app.core.TravelMode.CYCLING -> getString(R.string.main_by_bike)
            com.backpack.app.core.TravelMode.DRIVING -> getString(R.string.main_by_car)
        }
        ReachArea.get(this, origin, mode.mode, minutes) { ring, exact ->
            // Still wanted (no route meanwhile, same settings and place)?
            if (isDestroyed || !touristOn || TripState.plan != null ||
                reachSettings != settings || reachOrigin !== origin
            ) return@get
            reachLabel = reachText(time, how, exact, aroundMe)
            val key = "%.3f,%.3f,%s,%d,%b".format(java.util.Locale.ROOT, origin.lat, origin.lon, mode.name, minutes, exact)
            touristFinder.inReach(ring, key, { if (announce) onPlacesServerSwitch() },
                onPartial = { part ->
                    // 1.2.1: Wikipedia's nearest cells answered: show them now.
                    if (touristOn && TripState.plan == null && reachSettings == settings && reachOrigin === origin) {
                        TripState.touristCandidates = part
                        showTouristFor(null)
                    }
                }) { all, error, _ ->
                if (all == null) { reachOrigin = null; reachRetryAfterMs = System.currentTimeMillis() + 120_000L }
                onTouristCandidates(all, error, null, origin, announce = announce, scenic = false)
            }
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
            refreshSightsButton()
            showError(error ?: getString(R.string.main_places_search_failed), retry = { refreshTourist(force = true) })
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
            val kinds = sightKinds()
            val choice = Tourist.scenicChoice(plan.routes, TripState.selectedIndex, all.filter { it.category in kinds },
                corridorM().toInt(), sightsMaxFraction())
            if (choice != null) {
                drawPlan(plan, choice.index, fit = false, note = null, tourist = false)
                note = getString(R.string.main_weekend_sights_route, choice.places.toString(), choice.basePlaces.toString(),
                    Tourist.minutesLabel(choice.extraSeconds))
            }
        }
        val shown = showTouristFor(if (plan != null) selectedRoute() else null)
        // Part of the squares could not be fetched: say so, keep what was found.
        if (error != null) showError(getString(R.string.main_places_missing, error), retry = { refreshTourist(force = true) })
        if (!announce) return
        showMessage(note ?: when {
            shown.isEmpty() -> if (plan != null) getString(R.string.main_no_sights_route)
                else if (reachLabel != null) getString(R.string.main_no_sights_reach, reachLabel) else getString(R.string.main_no_sights_here)
            plan != null -> getString(R.string.main_sights_on_route, shown.size)
            reachLabel != null -> getString(R.string.main_sights_reach, shown.size, reachLabel)
            else -> getString(R.string.main_sights_here, shown.size)
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

    // ---------- 1.1.1 Weekend voice heads-up ----------

    /** 1.3.0: sight heads-ups run with the navigation ([com.backpack.app.tourist.SightHeadsUp]);
     *  while the map is shown, 1.4.0: a card + the map showing the detour. */
    private val headsUpUi = object : com.backpack.app.tourist.SightHeadsUp.Ui {
        override fun onHeadsUp(poi: TouristPoi, detourLabel: String) {
            if (isDestroyed) return
            wakeDisplay()
            // 1.7.1: summaries being read keep their card (and its Stop).
            if (readAllActive) showMessage("${poi.name} \u00b7 \u2248 +$detourLabel", showMs = 8000L)
            else showSightCard(poi, detourLabel)
            showSightOnMap(poi)
        }
        override fun onSeveral(pois: List<TouristPoi>) {
            if (isDestroyed) return
            val text = getString(R.string.main_several_sights, pois.size)
            // 1.7.1: a card with Read aloud (Settings > Sights), else the message as before.
            if (!readAllActive && Prefs.settings(this@MainActivity).getBoolean(Settings.KEY_SIGHT_SUMMARY_READ, true)) showSummaryCard(pois, text)
            else showMessage(text, showMs = 8000L)
        }
    }

    private val hideSightCard = Runnable { findViewById<View>(R.id.sight_card)?.visibility = View.GONE }

    /** 1.7.1: how long a sight card stays (Settings > Sights: 10 / 15 / 20 s). */
    private fun sightCardMs(): Long =
        Prefs.settings(this).getInt(Settings.KEY_SIGHT_CARD_S, Settings.DEFAULT_SIGHT_CARD_S).toLong() * 1000L

    /** 1.4.0: name, kind, extra time; Go there / Read aloud. Top in portrait,
     *  left in landscape (the road ahead stays visible); 1.7.1: 10-20 s. */
    private fun showSightCard(poi: TouristPoi, detourLabel: String) {
        val card = findViewById<View>(R.id.sight_card) ?: return
        findViewById<TextView>(R.id.sight_card_title).text = poi.name
        findViewById<TextView>(R.id.sight_card_detail).text = "${poi.category.label} \u00b7 \u2248 +$detourLabel"
        findViewById<View>(R.id.sight_card_go).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                hideSightCard.run(); handler.removeCallbacks(hideSightCard)
                addStopFlow(poi.point, poi.name)
            }
        }
        findViewById<TextView>(R.id.sight_card_read).apply {
            text = getString(R.string.main_card_read)
            setOnClickListener {
                hideSightCard.run(); handler.removeCallbacks(hideSightCard)
                readAloud(poi)
            }
        }
        placeSightCard(card)
    }

    /**
     * 1.7.1: a summary (start, arrival, 3+ sights ahead) as a card: the
     * message, the places' names and Read aloud, which reads them one after
     * another; the button then turns into Stop.
     */
    private fun showSummaryCard(pois: List<TouristPoi>, text: String) {
        val card = findViewById<View>(R.id.sight_card) ?: return
        findViewById<TextView>(R.id.sight_card_title).text = text
        findViewById<TextView>(R.id.sight_card_detail).text = pois.joinToString(" \u00b7 ") { it.name }
        findViewById<View>(R.id.sight_card_go).visibility = View.GONE
        val read = findViewById<TextView>(R.id.sight_card_read)
        read.text = getString(R.string.main_card_read)
        read.setOnClickListener {
            if (readAllActive) {
                readChainId++
                readAllActive = false
                NavigationEngine.stopReading()
                handler.removeCallbacks(hideWhenReadingDone)
                hideSightCard.run(); handler.removeCallbacks(hideSightCard)
            } else if (readAll(pois)) {
                read.text = getString(R.string.main_card_stop)
                // Stays (with Stop) until the last place has been read.
                handler.removeCallbacks(hideSightCard)
                handler.removeCallbacks(hideWhenReadingDone)
                handler.postDelayed(hideWhenReadingDone, 2000L)
            }
        }
        placeSightCard(card)
    }

    /** One "read them all" at a time: a new one or Stop ends the one going on. */
    private var readChainId = 0
    /** The summary card is reading (its button is Stop); new tips are messages meanwhile. */
    private var readAllActive = false
    /** Lookups still coming (the card waits for them, at most this long). */
    private var readAllLookupUntil = 0L

    /** Each place's Wikipedia summary, queued in order (one fetch after another). */
    private fun readAll(pois: List<TouristPoi>): Boolean {
        if (!Prefs.voiceOn(this)) { showMessage(getString(R.string.main_voice_off)); return false }
        if (!readingsAllowed() || pois.isEmpty()) return false
        // Whatever was being read gives way to this list.
        NavigationEngine.stopReading()
        val id = ++readChainId
        readAllActive = true
        val lang = Prefs.voiceLanguage(this)
        fun next(i: Int) {
            if (isDestroyed || id != readChainId) return
            if (i >= pois.size) { readAllLookupUntil = 0L; return }
            val poi = pois[i]
            readAllLookupUntil = System.currentTimeMillis() + 30_000L
            showMessage(getString(R.string.main_looking_up, poi.name))
            com.backpack.app.tourist.WikiSummary.forPoi(poi, lang) { text, textLang ->
                // Ended (Stop, or the card gave up waiting): nothing more is queued without a Stop.
                if (isDestroyed || id != readChainId || !readAllActive) return@forPoi
                if (text != null) NavigationEngine.speakInfo(com.backpack.app.core.VoiceQueue.Item(
                    com.backpack.app.core.VoiceQueue.Kind.READING, "${poi.name}. $text", textLang.takeIf { it != lang }), this)
                next(i + 1)
            }
        }
        next(0)
        return true
    }

    private fun placeSightCard(card: View) {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val screenW = findViewById<View>(android.R.id.content).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        // 1.8.0: the left column (planning: 4 buttons; navigating: travel mode,
        // urgency) always stays: the card beside it (1.8.2: also without a route).
        val startDp = COLUMN_ZONE_DP + 32
        // 1.8.2: never over the box's last line (landscape: under it, one-line texts so
        // the messages below stay clear); the buttons stacked when side by side would wrap.
        val title = findViewById<TextView>(R.id.sight_card_title)
        val detail = findViewById<TextView>(R.id.sight_card_detail)
        title.maxLines = if (land) 1 else 2
        detail.maxLines = if (land) 1 else 2
        val cardW: Int
        if (land) {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            val top = if (navBox.visibility == View.VISIBLE) navBox.bottom + dp(8) else dp(NAV_BOX_TOP_DP + 4 + SPEED_CIRCLE_DP + 8)
            lp.setMargins(dp(startDp), top, 0, 0)
            // Wide enough for both buttons side by side (a short card stays above the messages).
            lp.width = (screenW * 0.45).toInt()
            cardW = lp.width
        } else {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            // 1.8.2: under the box, or under the speed circle (top right, 8..78 dp) without it.
            val boxBottom = if (navBox.visibility == View.VISIBLE) navBox.bottom else dp(NAV_BOX_TOP_DP + 4 + SPEED_CIRCLE_DP)
            // 1.7.1: never under the right column.
            lp.setMargins(dp(startDp), boxBottom + dp(8), dp(COLUMN_ZONE_DP), 0)
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            cardW = screenW - dp(startDp) - dp(COLUMN_ZONE_DP)
        }
        stackCardButtons(cardW - card.paddingLeft - card.paddingRight)
        // 1.8.2: the card ends above the messages: if it would reach them, the detail line goes.
        detail.visibility = View.VISIBLE
        card.post {
            val band = findViewById<View>(R.id.message_band)
            if (card.visibility == View.VISIBLE && band.visibility == View.VISIBLE && band.height > 0 &&
                card.bottom > band.top - dp(6) && card.right > band.left && card.left < band.right) detail.visibility = View.GONE
        }
        card.layoutParams = lp
        card.visibility = View.VISIBLE
        wakeDisplay()
        handler.removeCallbacks(hideSightCard)
        handler.removeCallbacks(hideWhenReadingDone)
        handler.postDelayed(hideSightCard, sightCardMs())
    }

    /** 1.7.1: a summary card being read keeps its Stop until the last reading ends. */
    private val hideWhenReadingDone = object : Runnable {
        override fun run() {
            if (readAllActive && NavigationEngine.pendingInfo(com.backpack.app.core.VoiceQueue.Kind.READING) > 0) {
                handler.postDelayed(this, 2000L); return
            }
            if (readAllActive && System.currentTimeMillis() < readAllLookupUntil) { handler.postDelayed(this, 2000L); return }
            readAllActive = false
            hideSightCard.run()
        }
    }

    /** The two card buttons side by side when they fit at their size, else one under the other. */
    private fun stackCardButtons(innerW: Int) {
        val go = findViewById<TextView>(R.id.sight_card_go)
        val read = findViewById<TextView>(R.id.sight_card_read)
        val row = read.parent as LinearLayout
        fun w(t: TextView) = if (t.visibility == View.VISIBLE) t.paint.measureText(t.text.toString()) + t.paddingLeft + t.paddingRight else 0f
        val side = w(go) + w(read) + dp(10) <= innerW
        val want = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (row.orientation != want) row.orientation = want
        (read.layoutParams as LinearLayout.LayoutParams).let {
            val start = if (side && go.visibility == View.VISIBLE) dp(10) else 0
            val top = if (!side && go.visibility == View.VISIBLE) dp(6) else 0
            if (it.marginStart != start || it.topMargin != top) { it.marginStart = start; it.topMargin = top; read.layoutParams = it }
        }
    }

    /** 1.4.0: zoom out to you + the sight (blinking ring) for 10 s, then back to driving. */
    private var focusBlinks = 0
    private val focusBlink = object : Runnable {
        override fun run() {
            if (focusBlinks <= 0) { layers.setFocus(null); return }
            layers.setFocusVisible(focusBlinks % 2 == 0)
            focusBlinks--
            handler.postDelayed(this, 400L)
        }
    }

    private fun showSightOnMap(poi: TouristPoi) {
        layers.setFocus(poi.point)
        focusBlinks = 25
        handler.removeCallbacks(focusBlink)
        handler.post(focusBlink)
        val m = map ?: return
        if (!navUiActive || overviewOn) return
        val here = currentPosition() ?: return
        overviewOn = true
        setOverviewButton(true)
        if (puckActive()) m.locationComponent.cameraMode = CameraMode.NONE
        try {
            val bounds = LatLngBounds.Builder().include(LatLng(here.lat, here.lon)).include(LatLng(poi.point.lat, poi.point.lon)).build()
            m.cameraPosition = CameraPosition.Builder().target(m.cameraPosition.target).zoom(m.cameraPosition.zoom)
                .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
            val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // Room for the card (top in portrait, left in landscape).
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,
                if (land) dp(260) else dp(60), if (land) dp(80) else dp(230), dp(60), dp(150)), 700)
        } catch (e: Exception) { }
        handler.removeCallbacks(overviewReturnRunnable)
        handler.postDelayed(overviewReturnRunnable, 10_000L)
    }

    /** The place's Wikipedia summary, read aloud (queued like every reading). */
    private fun readAloud(poi: TouristPoi) {
        if (!Prefs.voiceOn(this)) { showMessage(getString(R.string.main_voice_off)); return }
        if (!readingsAllowed()) return
        showMessage(getString(R.string.main_looking_up, poi.name))
        val lang = Prefs.voiceLanguage(this)
        com.backpack.app.tourist.WikiSummary.forPoi(poi, lang) { text, textLang ->
            if (isDestroyed) return@forPoi
            if (text == null) { showMessage(getString(R.string.main_no_article_read, poi.name)); return@forPoi }
            NavigationEngine.speakInfo(com.backpack.app.core.VoiceQueue.Item(
                com.backpack.app.core.VoiceQueue.Kind.READING, "${poi.name}. $text", textLang.takeIf { it != lang }), this)
        }
    }

    /** 1.4.1: while navigating, readings only in the "Full" sound mode. */
    private fun readingsAllowed(): Boolean {
        if (Prefs.soundMode(this) == Prefs.SoundMode.FULL || !NavigationEngine.isActive) return true
        showMessage(getString(R.string.main_readings_need_full))
        return false
    }

    /**
     * 1.2.0 tourist guide: the heads-up message tapped. Reads the place's
     * Wikipedia summary aloud (a new instruction interrupts it: the road
     * first) and shows it, with Add stop. Close leaves the voice reading.
     */
    private fun showSightGuide(poi: TouristPoi) {
        val p = Popup(this, poi.name)
        val body = p.text(getString(R.string.main_looking_wikipedia))
        p.button(getString(R.string.main_stop_reading)) { NavigationEngine.stopReading() }
        p.button(getString(R.string.main_add_stop)) { NavigationEngine.stopReading(); p.dismiss(); addStopFlow(poi.point, poi.name) }
        // Close keeps the voice going (eyes back on the road); Stop reading ends it.
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
        val lang = Prefs.voiceLanguage(this)
        com.backpack.app.tourist.WikiSummary.forPoi(poi, lang) { text, textLang ->
            if (isDestroyed) return@forPoi
            if (text == null) {
                body.text = getString(R.string.main_no_article_place)
                return@forPoi
            }
            body.text = text + "\n\nWikipedia, CC BY-SA"
            p.fit()
            if (!Prefs.voiceOn(this)) showMessage(getString(R.string.main_voice_off))
            // Queued: after the heads-up being said, cut by instructions and resumed.
            else if (readingsAllowed()) NavigationEngine.speakInfo(com.backpack.app.core.VoiceQueue.Item(
                com.backpack.app.core.VoiceQueue.Kind.READING, "${poi.name}. $text", textLang.takeIf { it != lang }), this)
        }
    }

    private fun touistClearPending() {
        touristPendingRoute = null
        touristPendingCenter = null
    }

    /** Places of the routes not selected (dimmed) -> their route index (1.0.7). */
    private var touristDimOwner: Map<String, Int> = emptyMap()

    /**
     * Ranks the stored candidates for [route] (or the area) and shows them.
     * While choosing among several routes, the places of the other routes
     * are shown too, dimmed (1.0.7). Returns the selected route's places.
     */
    private fun showTouristFor(route: RouteResult?): List<TouristPoi> {
        // 1.1.1: only the kinds ticked in the Settings > Sights (no new search).
        val kinds = sightKinds()
        val all = TripState.touristCandidates.filter { it.category in kinds }
        val radius = corridorM()
        // 1.2.1: spread over the whole route (each stretch its best first).
        fun along(r: RouteResult) = Tourist.rankAlong(Tourist.placeOnRoute(all, r.line).filter { it.offsetM <= radius * 1.05 }, r.line.length)
        val owner = HashMap<String, Int>()
        val shown = if (route != null) along(route) else {
            Tourist.rank(all, TouristFinder.REACH_LIMIT)
        }
        val extra = ArrayList<TouristPoi>()
        val plan = TripState.plan
        if (route != null && plan != null && plan.routes.size > 1 && !navUiActive) {
            val keys = shown.map { it.key }.toHashSet()
            for ((i, r) in plan.routes.withIndex()) {
                if (i == TripState.selectedIndex) continue
                for (p in along(r)) if (keys.add(p.key)) { extra.add(p); owner[p.key] = i }
            }
        }
        touristDimOwner = owner
        TripState.touristDimKeys = owner.keys.toSet()
        touristResults = shown + extra
        layers.setTourist(shown + extra, owner.keys)
        refreshSightsButton()
        return shown
    }

    private fun starsText(n: Int) = if (n == 0) "" else "\u2605".repeat(n) + " "

    private fun selectedRoute(): RouteResult? = TripState.plan?.routes?.getOrNull(TripState.selectedIndex)

    /** The route places are measured on: the one being navigated, else the selected one. */
    private fun touristRoute(): RouteResult? = (if (NavigationEngine.isActive) NavigationEngine.route else null) ?: selectedRoute()

    private fun aheadOnRoute(poi: TouristPoi): Pair<Double, Double>? = aheadOn(poi.point)

    private fun showTouristList() {
        val onRoute = touristRoute() != null && TripState.destination != null
        val p = Popup(this, getString(if (onRoute) R.string.main_along_route else R.string.main_sights_here_title))
        // While navigating, places already behind you are left out.
        val rows = touristResults.filter { it.key !in touristDimOwner }.map { it to aheadOnRoute(it) }
            .filter { (_, a) -> !onRoute || a == null || a.first > -50.0 }
            .sortedBy { (_, a) -> if (onRoute) a?.first ?: Double.MAX_VALUE else 0.0 }
        if (rows.isEmpty()) p.text(getString(R.string.main_no_places_ahead))
        for ((poi, a) in rows) {
            val where = if (onRoute && a != null) {
                " · " + getString(R.string.main_list_ahead, Speech.shortDistance(a.first.coerceAtLeast(0.0), useMetric),
                    Tourist.minutesLabel(Tourist.estimateDetourSeconds(a.second, currentMode.mode)))
            } else ""
            p.text("${starsText(poi.stars)}${poi.name}\n${poi.category.label}$where", 14f).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { p.dismiss(); showTouristPlace(poi) }
            }
        }
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
    }

    /** 1.1.0: destination pin tapped: the town's Wikivoyage guide, if any. */
    private fun showDestinationGuide() {
        val dest = TripState.destination ?: return
        showMessage(getString(R.string.main_looking_guide))
        com.backpack.app.tourist.TravelGuide.forPoint(dest) { name, url ->
            if (isDestroyed) return@forPoint
            val p = Popup(this, name ?: getString(R.string.main_destination))
            when {
                url != null -> { p.text(getString(R.string.main_guide_for, name)); p.link(getString(R.string.main_open_guide), url) }
                name != null -> p.text(getString(R.string.main_no_guide_for, name))
                else -> p.text(getString(R.string.main_no_guide))
            }
            p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
            p.show()
        }
    }

    private fun showTouristPlace(poi: TouristPoi) {
        val known = if (poi.sitelinks > 0) " · " + getString(R.string.main_sitelinks, poi.sitelinks) else ""
        showPlacePopup(poi.name, "${starsText(poi.stars)}${poi.category.label}$known", poi.point,
            poi.readMoreUrl, getString(if (poi.wikipediaUrl != null || poi.articleUrl != null) R.string.main_read_wikipedia else R.string.main_read_wikidata),
            poi.voyageUrl)
    }

    private fun showPoiPlace(place: PoiPlace) {
        val kind = com.backpack.app.core.Places.kindLabel(place.kind)
        // 1.3.0: open or closed right now, then the hours as written.
        val now = when (com.backpack.app.core.OpeningHours.isOpenNow(place.openingHours)) {
            true -> " · " + getString(R.string.main_open_now)
            false -> " · " + getString(R.string.main_closed_now)
            null -> ""
        }
        val detail = kind + now + (place.openingHours?.let { "\n$it" } ?: "")
        val link = when {
            place.wikipedia != null -> getString(R.string.main_read_wikipedia)
            place.wikidata != null -> getString(R.string.main_read_wikidata)
            else -> getString(R.string.main_website)
        }
        showPlacePopup(place.label, detail, place.point, place.readMoreUrl, link)
    }

    /**
     * One popup for every place (well-known places and POI categories):
     * what it is, where it is on your route with the extra time (estimate,
     * then the router's exact value), a link to read about it, and
     * Go here / Add stop.
     */
    private fun showPlacePopup(title: String, detail: String, point: LatLon, readMoreUrl: String?, linkLabel: String, voyageUrl: String? = null) {
        stopPlanFollow()
        val p = Popup(this, title)
        p.text(detail)
        val route = touristRoute()
        val end = TripState.destination
        val ahead = aheadOn(point)
        val passed = NavigationEngine.isActive && ahead != null && ahead.first < -50.0
        if (route != null && end != null && ahead != null && !passed) {
            val estimate = Tourist.estimateDetourSeconds(ahead.second, currentMode.mode)
            val where = getString(R.string.main_on_route_where, Speech.shortDistance(ahead.first.coerceAtLeast(0.0), useMetric),
                Speech.shortDistance(ahead.second, useMetric))
            val detourText = p.text("$where \u00b7 \u2248 +${Tourist.minutesLabel(estimate)}")
            // Exact extra time from the router, same rules as the trip.
            val start = (if (NavigationEngine.isActive) currentPosition() else null) ?: resolveStartPoint()
            val stops = Tourist.insertStop(NavigationEngine.remainingStops() ?: TripState.waypoints, point, route.line)
            val current = NavigationEngine.lastProgress?.remainingS ?: route.durationSeconds
            if (start != null) {
                detourPlanner.plan(buildRequest(start, end, stops)) { plan, _ ->
                    val r = plan?.routes?.getOrNull(plan.primaryIndex) ?: return@plan
                    val extra = (r.durationSeconds - current).coerceAtLeast(0.0)
                    detourText.text = getString(R.string.main_as_stop, where, Tourist.minutesLabel(extra))
                }
            }
        } else if (passed) {
            p.text(getString(R.string.main_already_passed))
        }
        // Tap to read about it in the browser.
        readMoreUrl?.let { p.link(linkLabel, it) }
        voyageUrl?.let { p.link(getString(R.string.main_guide_link), it) }
        if (end != null && !passed) p.button(getString(R.string.main_add_stop), primary = true) { p.dismiss(); addStopFlow(point, title) }
        p.button(getString(R.string.main_go_here), primary = end == null) { p.dismiss(); goHere(point, title) }
        p.button(getString(R.string.main_close)) { p.dismiss() }
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
            showMessage(getString(R.string.main_already_behind, name))
            return
        }
        val existing = NavigationEngine.remainingStops() ?: TripState.waypoints.toList()
        val line = touristRoute()?.line
        if (Stops.ownCount(existing, TripState.detour) < Stops.MAX) {
            changeTrip(end, Tourist.insertStop(existing, point, line), TripState.detour, remember = null,
                message = getString(R.string.main_added_stop, name))
            return
        }
        val p = Popup(this, getString(R.string.main_max_stops_title, Stops.MAX))
        p.text(getString(R.string.main_visit_or_replace, name))
        p.bodyButton(getString(R.string.main_visit_on_way)) {
            p.dismiss()
            changeTrip(end, Stops.withDetour(existing, TripState.detour, point, line), point, remember = existing,
                message = getString(R.string.main_detour_to, name))
        }
        p.bodyButton(getString(R.string.main_replace_last)) {
            p.dismiss()
            changeTrip(end, Stops.replaceLast(existing, TripState.detour, point, line), TripState.detour, remember = existing,
                message = getString(R.string.main_replaces_last, name))
        }
        p.button(getString(R.string.main_cancel)) { p.dismiss() }
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
            showError(getString(R.string.main_no_fix_retry))
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
                message = getString(R.string.main_going_to, name))
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
        showMessage(getString(R.string.main_previous_resumed))
        TripState.changePending = true
        requestBestRoute(start, end, stops, onFailure = { restoreCommitted(before) })
    }

    /**
     * 1.4.1 left slot of the bottom row: Cancel and Resume take turns.
     * Resume: the route before a detour / "Go here", or (nothing on screen)
     * the navigation stopped before arriving. Cancel: a route on screen.
     */
    private fun refreshResumeButton() {
        val routeShown = TripState.plan != null || NavigationEngine.isActive
        val last = !routeShown && com.backpack.app.nav.LastTrip.load(this) != null
        val resume = TripState.previous != null || last
        findViewById<View>(R.id.resume_button).visibility = if (resume) View.VISIBLE else View.GONE
        findViewById<View>(R.id.clear_route_button).visibility = if (routeShown && !resume) View.VISIBLE else View.GONE
        placeLayout()
    }

    /** 1.4.0: the navigation stopped before arriving: same destination, the
     *  stops not yet visited, same travel mode; navigation starts by itself. */
    private fun resumeLastTrip() {
        val t = com.backpack.app.nav.LastTrip.load(this) ?: return
        enumValues<ModeUi>().firstOrNull { it.mode.name == t.mode }?.let { setModeQuietly(it) }
        val before = TripState.copy()
        TripState.destination = t.destination
        TripState.waypoints.clear()
        TripState.waypoints.addAll(t.stops)
        refreshPins()
        val start = resolveStartPoint() ?: run { TripState.restore(before); refreshPins(); showError(getString(R.string.main_waiting_gps)); return }
        showMessage(getString(R.string.main_resuming))
        TripState.changePending = true
        requestBestRoute(start, t.destination, t.stops, onFailure = { restoreCommitted(before) }, autoStart = true)
    }

    // ---------- Stops (waypoints) ----------

    // ---------- 1.9.0 My places (Home, Work, favourites) and the hold menu ----------

    /** The old "add a stop" slot: My places. Tap = the list, hold = edit. */
    private fun setupMyPlacesButton() {
        val button = findViewById<ImageButton>(R.id.waypoint_button)
        button.setImageResource(R.drawable.ic_my_places)
        button.contentDescription = getString(R.string.main_my_places)
        button.setOnClickListener { com.backpack.app.security.AppLock.unlock(this) { showMyPlaces() } }
        button.setOnLongClickListener { com.backpack.app.security.AppLock.unlock(this) { editMyPlaces() }; true }
        setIconActive(button, true)
    }

    private fun loadMyPlaces(): com.backpack.app.core.MyPlaces.All {
        com.backpack.app.security.PrivateData.migrate(this)
        return com.backpack.app.core.MyPlaces.decode(
            com.backpack.app.security.Vault.getString(this, com.backpack.app.security.PrivateData.MY_PLACES))
    }

    private fun storeMyPlaces(a: com.backpack.app.core.MyPlaces.All) =
        com.backpack.app.security.Vault.putString(this, com.backpack.app.security.PrivateData.MY_PLACES, com.backpack.app.core.MyPlaces.encode(a))

    private fun spotLabel(s: com.backpack.app.core.MyPlaces.Spot): String {
        val kind = when (s.kind) {
            com.backpack.app.core.MyPlaces.Kind.HOME -> "\uD83C\uDFE0 " + getString(R.string.main_save_home)
            com.backpack.app.core.MyPlaces.Kind.WORK -> "\uD83D\uDCBC " + getString(R.string.main_save_work)
            com.backpack.app.core.MyPlaces.Kind.FAVOURITE -> "\u2605"
        }
        val here = currentPosition()
        val dist = here?.let { " \u00b7 " + distanceLabel(Geo.distance(it, s.point)) }.orEmpty()
        val name = if (s.kind == com.backpack.app.core.MyPlaces.Kind.FAVOURITE || s.name.isNotBlank() && s.name != getString(R.string.main_save_home) && s.name != getString(R.string.main_save_work)) " " + s.name else ""
        return kind + name + dist
    }

    /** Tap: Home, Work, favourites; choosing one = the destination. */
    private fun showMyPlaces() {
        val all = loadMyPlaces()
        val p = Popup(this, getString(R.string.main_my_places))
        if (all.home == null) p.text(getString(R.string.main_home_not_set), 12f)
        if (all.work == null) p.text(getString(R.string.main_work_not_set), 12f)
        for (s in all.list) p.bodyButton(spotLabel(s)) { p.dismiss(); goToSpot(s) }
        if (all.favourites.isEmpty()) p.text(getString(R.string.main_no_favs), 12f)
        TripState.destination?.let { dest ->
            p.bodyButton(getString(R.string.main_save_dest)) { p.dismiss(); askSaveAs(dest) }
        }
        p.button(getString(R.string.main_edit_places)) { p.dismiss(); editMyPlaces() }
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
    }

    private fun goToSpot(s: com.backpack.app.core.MyPlaces.Spot) {
        val name = s.name.ifBlank { getString(R.string.main_this_place) }
        if (NavigationEngine.isActive) goHere(s.point, name)
        else {
            handleDestinationTap(s.point)
            // Show where it goes.
            map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(s.point.lat, s.point.lon)))
        }
    }

    /** Hold the button: rename, delete, move up. */
    private fun editMyPlaces() {
        val all = loadMyPlaces()
        val p = Popup(this, getString(R.string.main_edit_places))
        if (all.list.isEmpty()) p.text(getString(R.string.main_no_favs), 12f)
        for (s in all.list) {
            p.section(spotLabel(s))
            p.bodyButton(getString(R.string.main_rename)) { p.dismiss(); askName(s.name) { n -> storeMyPlaces(com.backpack.app.core.MyPlaces.rename(loadMyPlaces(), s, n)); editMyPlaces() } }
            if (s.kind == com.backpack.app.core.MyPlaces.Kind.FAVOURITE && all.favourites.indexOf(s) > 0)
                p.bodyButton(getString(R.string.main_move_up)) { p.dismiss(); storeMyPlaces(com.backpack.app.core.MyPlaces.moveUp(loadMyPlaces(), s)); editMyPlaces() }
            p.bodyButton(getString(R.string.main_delete)) { p.dismiss(); storeMyPlaces(com.backpack.app.core.MyPlaces.remove(loadMyPlaces(), s)); editMyPlaces() }
        }
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
    }

    private fun askName(current: String, done: (String) -> Unit) {
        val p = Popup(this, getString(R.string.main_place_name))
        var value = current
        p.field(getString(R.string.main_place_name), current) { value = it }
        p.button(getString(R.string.main_cancel)) { p.dismiss() }
        p.button(getString(R.string.main_save), primary = true) { p.dismiss(); done(value.trim().ifEmpty { current }) }
        p.show()
    }

    /** Save [point] as Home, Work or a favourite (named). */
    private fun askSaveAs(point: LatLon) {
        val p = Popup(this, getString(R.string.main_save_as))
        fun save(kind: com.backpack.app.core.MyPlaces.Kind, defaultName: String) {
            p.dismiss()
            askName(defaultName) { n ->
                val next = com.backpack.app.core.MyPlaces.save(loadMyPlaces(), kind, n, point)
                if (next == null) { showError(getString(R.string.main_favs_full, com.backpack.app.core.MyPlaces.MAX_FAVOURITES)); return@askName }
                storeMyPlaces(next)
                showMessage(getString(R.string.main_saved_place, n))
            }
        }
        p.bodyButton("\uD83C\uDFE0 " + getString(R.string.main_save_home)) { save(com.backpack.app.core.MyPlaces.Kind.HOME, getString(R.string.main_save_home)) }
        p.bodyButton("\uD83D\uDCBC " + getString(R.string.main_save_work)) { save(com.backpack.app.core.MyPlaces.Kind.WORK, getString(R.string.main_save_work)) }
        p.bodyButton("\u2605 " + getString(R.string.main_save_fav)) {
            save(com.backpack.app.core.MyPlaces.Kind.FAVOURITE, getString(R.string.main_save_fav) + " " + (loadMyPlaces().favourites.size + 1))
        }
        p.button(getString(R.string.main_cancel), primary = true) { p.dismiss() }
        p.show()
    }

    /** 1.9.0: hold the map: go here, add as stop (with a route, max 2), start from here, save as. */
    private fun showHoldMenu(point: LatLon) {
        stopPlanFollow()
        val p = Popup(this, getString(R.string.main_this_place))
        val navigating = NavigationEngine.isActive
        p.bodyButton(getString(R.string.main_go_here)) {
            p.dismiss()
            if (navigating) goHere(point, getString(R.string.main_this_place)) else handleDestinationTap(point)
        }
        if (TripState.destination != null) p.bodyButton(getString(R.string.main_add_stop)) {
            p.dismiss()
            if (navigating) addStopFlow(point, getString(R.string.main_this_place)) else addWaypoint(point)
        }
        if (!navigating) p.bodyButton(getString(R.string.main_start_here)) { p.dismiss(); handleStartOverride(point) }
        p.bodyButton(getString(R.string.main_save_as)) { p.dismiss(); askSaveAs(point) }
        p.button(getString(R.string.main_cancel), primary = true) { p.dismiss() }
        p.show()
    }

    private fun addWaypoint(point: LatLon) {
        if (Stops.ownCount(TripState.waypoints, TripState.detour) >= maxWaypoints) {
            // With a destination: visit on the way or replace the last stop.
            if (TripState.destination != null) addStopFlow(point, getString(R.string.main_this_place))
            else showMessage(getString(R.string.main_max_stops, maxWaypoints))
            return
        }
        TripState.waypoints.add(point)
        refreshPins()
        if (Stops.ownCount(TripState.waypoints, TripState.detour) >= maxWaypoints) {
            // The natural next tap sets the destination, not a rejected 3rd stop.
            waypointModeArmed = false
            setIconActive(findViewById(R.id.waypoint_button), false)
            showMessage(getString(R.string.main_stop_added_dest))
        } else {
            showMessage(getString(R.string.main_stop_added_more))
        }
        val start = resolveStartPoint()
        val end = TripState.destination
        if (start != null && end != null) {
            showMessage(getString(R.string.main_finding_route))
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
                showMessage(getString(R.string.main_nav_stopped_cleared))
            } else {
                startNavigation()
            }
        }
        findViewById<ImageButton>(R.id.clear_route_button).setOnClickListener {
            if (NavigationEngine.isActive) NavigationEngine.stop()
            clearRoute()
            showMessage(getString(R.string.main_route_cleared))
        }
        // 1.4.1: hold = cancel and also forget the trip (no Resume afterwards).
        findViewById<ImageButton>(R.id.clear_route_button).setOnLongClickListener {
            if (NavigationEngine.isActive) NavigationEngine.stop()
            clearRoute()
            com.backpack.app.nav.LastTrip.clear(this)
            refreshResumeButton()
            showMessage(getString(R.string.main_route_forgotten))
            true
        }
    }

    private fun startNavigation() {
        val plan = TripState.plan
        val end = TripState.destination
        // Fix: navigate the route the user SELECTED (Step 38 always took the first).
        val route = plan?.routes?.getOrNull(TripState.selectedIndex)
        if (plan == null || route == null || route.points.isEmpty() || end == null) {
            showError(getString(R.string.main_no_route_yet))
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
        // A route picked among the alternatives keeps its own request
        // (preference) for later reroutes.
        val req = (if (plan === altReviewPlan) altReviewRequests[TripState.selectedIndex] else null) ?: buildRequest(start, end)
        NavigationEngine.start(this, plan, TripState.selectedIndex, req)
        startNavigationUi()
        // Before the first GPS fix: show the departure step.
        if (NavigationEngine.lastProgress == null) updateNavBar(route.steps.firstOrNull(), null)
    }

    /** Switches the screen into navigation look; safe to call again. */
    private fun startNavigationUi() {
        val first = !navUiActive
        handler.post { refreshSoundButton() }
        // Navigation has its own camera (and its own north-up switch).
        setPlanFollow(PlanFollow.OFF)
        flattenOnIdle = false
        navUiActive = true
        map?.uiSettings?.isTiltGesturesEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeDisplay()
        findViewById<ImageButton>(R.id.nav_go_button).setImageResource(R.drawable.ic_btn_stop)
        setNavigationDeclutter(true)
        // 1.4.0: the sights of the route followed stay on the map while navigating.
        if (touristOn) showTouristFor(NavigationEngine.route ?: selectedRoute())
        findViewById<View>(R.id.overview_button).visibility = View.VISIBLE
        navInstructionBar.visibility = View.VISIBLE
        refreshNavBox()
        // 1.4.1: full screen while navigating (1.8.0: no clock in the box).
        setFullScreen(true)
        navDistanceText.visibility = View.VISIBLE
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
        refreshNorthMark()
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
        handler.post { refreshSoundButton() }
        if (overviewOn) {
            overviewOn = false
            handler.removeCallbacks(overviewReturnRunnable)
            setOverviewButton(false)
        }
        findViewById<View>(R.id.overview_button).visibility = View.GONE
        navUiActive = false
        nextTurnKey = null
        layers.setNextTurn(null)
        statusStrip.maxLines = 2
        setPlanFollow(PlanFollow.OFF)
        // Outside navigation the map stays flat: no tilting by accident.
        map?.uiSettings?.isTiltGesturesEnabled = false
        deadReckoningActive = false
        handler.removeCallbacks(deadReckoningRunnable)
        // 1.8.0: the screen stays on (the map screen's own rule), brightness by applyBrightness.
        wakeDisplay()
        findViewById<ImageButton>(R.id.nav_go_button).setImageResource(R.drawable.ic_btn_go)
        setNavigationDeclutter(false)
        statusStrip.animate().cancel()
        statusStrip.alpha = 1f
        statusStrip.text = ""
        instructionShownUntil = 0L
        navDistanceText.text = ""
        navDistanceText.visibility = View.GONE
        showTurnIcon(null)
        navInstructionBar.visibility = View.GONE
        setFullScreen(false)
        updateNavBarProximityColor(Double.MAX_VALUE) // planning: plain blue frame
        layers.setProgress(null)
        if (currentMode != ModeUi.DRIVING) speedWidget.visibility = View.GONE
        handler.post { queryPois() } // back to the whole planned route / the area
        // Back to the planned route's figures (not the last live ones).
        selectedRoute()?.let { setRouteInfoText(it) }
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
        override fun onRerouting() = showMessage(getString(R.string.main_off_route))
        override fun onRerouted(plan: RoutePlan) {
            if (altChoiceOpen) closeAltChoice(restore = false)
            TripState.plan = plan
            TripState.selectedIndex = plan.primaryIndex
            // Sights measured again on the new route (voice heads-up, list).
            if (touristOn) handler.post { if (TripState.plan === plan) showTouristFor(selectedRoute()) }
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
            instructionShownUntil = 0L
            statusStrip.animate().cancel()
            statusStrip.alpha = 1f
            statusStrip.text = getString(R.string.main_arrived)
            showTurnIcon(R.drawable.ic_nav_arrive)
            wakeDisplay()
        }
        override fun onStopped() {
            if (altChoiceOpen) closeAltChoice(restore = true)
            if (navUiActive) stopNavigationUi()
            handler.post { if (!isDestroyed) refreshResumeButton() }
        }
    }

    /** 1.4.0: the next maneuver drawn brighter (150 m before it to 50 m after), arrowhead at its end. */
    private var nextTurnKey: Any? = null

    private fun showNextTurn(p: Progress, route: RouteResult) {
        val step = route.steps.getOrNull(p.nextStepIndex)
        if (step == null || step.maneuver == com.backpack.app.core.Maneuver.GOAL || p.offRoute) {
            if (nextTurnKey != null) { nextTurnKey = null; layers.setNextTurn(null) }
            return
        }
        val key = System.identityHashCode(route) to p.nextStepIndex
        if (key == nextTurnKey) return
        nextTurnKey = key
        val at = route.line.alongAt(step.wayStart)
        // 1.5.1: short; 1.8.2 (Alex): shorter still, ~20 m before the turn and ~15 m after (arrowhead at the end), wider.
        val from = (at - 20.0).coerceAtLeast(0.0)
        layers.setNextTurn(com.backpack.app.core.SpeedLimits.section(route.line, from, at + 15.0 - from))
    }

    private fun onNavProgress(p: Progress, route: RouteResult) {
        if (!navUiActive) startNavigationUi()
        refreshNorthMark() // the arrow shows your heading when north up
        if (p.arrived) return
        var step = if (p.nextStepIndex >= 0) route.steps.getOrNull(p.nextStepIndex) else null
        // 1.8.0: going round a roundabout, its circle (and exit number) stays
        // until you leave it; the distance counts down to your exit.
        route.steps.getOrNull(p.nextStepIndex - 1)?.takeIf { it.maneuver == Maneuver.ROUNDABOUT_ENTER }?.let { step = it }
        // 1.8.0 (Alex): what is said shows in the box for a few seconds, then
        // fades into the street name; never in the message area.
        p.speech?.let { showInstruction(it) }
        updateNavBar(step, p.distanceToNextM)
        updateNavBarProximityColor(p.distanceToNextM)
        if (p.anticipation || p.speech != null) wakeDisplay()
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
        layers.setProgress(p.alongM, route) // driven part darker
        showNextTurn(p, route)
        queryPois() // the stretch ahead, every 40 % of it
        // Live remaining distance and arrival time.
        routeInfoDist.text = navInfo(getString(R.string.main_dist), navDistance(p.remainingM), " \u00b7")
        routeInfoEta.text = navInfo(getString(R.string.main_eta), etaLabel(p.remainingS).removePrefix(getString(R.string.main_eta) + " "))
        fitRouteLine()
    }

    private fun updateNavBar(step: NavStep?, distance: Double?) {
        if (navInstructionBar.visibility != View.VISIBLE) { navInstructionBar.visibility = View.VISIBLE; refreshNavBox() }
        if (step == null) {
            navDistanceText.text = ""
            statusStrip.text = getString(R.string.main_head_to_destination)
            return
        }
        navDistanceText.text = Speech.shortDistance(distance ?: step.distanceMeters, useMetric).replace(" ", "")
        // Street name when known (the turn itself is shown by the icon); a
        // spoken instruction keeps its place for a few seconds first.
        if (System.currentTimeMillis() >= instructionShownUntil) {
            val street = step.streetName.ifBlank { step.instruction }
            if (statusStrip.text.toString() != street) {
                if (instructionShownUntil > 0L) {
                    // The instruction fades out, the street name fades in.
                    instructionShownUntil = 0L
                    statusStrip.animate().cancel()
                    statusStrip.animate().alpha(0f).setDuration(400).withEndAction {
                        statusStrip.text = street
                        statusStrip.animate().alpha(1f).setDuration(400).start()
                    }.start()
                } else statusStrip.text = street
            }
        }
        showTurnIcon(navIconFor(step.maneuver), if (step.maneuver == Maneuver.ROUNDABOUT_ENTER && step.exitNumber > 0) step.exitNumber else 0)
    }

    /** 1.8.0: the instruction just said, shown in the box this long before the street name. */
    private var instructionShownUntil = 0L

    private fun showInstruction(text: String) {
        statusStrip.animate().cancel()
        statusStrip.alpha = 1f
        statusStrip.text = text
        instructionShownUntil = System.currentTimeMillis() + INSTRUCTION_SHOWN_MS
    }

    /** The next action's circle: icon (null = hidden) and, for a roundabout, its exit number. */
    private fun showTurnIcon(icon: Int?, exitNumber: Int = 0) {
        val circle = findViewById<View>(R.id.nav_turn_circle)
        if (icon == null) { circle.visibility = View.GONE; return }
        navInstructionIcon.setImageResource(icon)
        findViewById<TextView>(R.id.nav_exit_number).apply {
            text = if (exitNumber > 0) exitNumber.toString() else ""
            visibility = if (exitNumber > 0) View.VISIBLE else View.GONE
        }
        circle.visibility = View.VISIBLE
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
        // 1.4.0 adaptive 3D: 30° slow, up to 50° fast (not in north-up, which is flat).
        if (!northUp && lc.cameraMode == CameraMode.TRACKING_GPS) Prefs.navTiltAt(this, location.speed * 3.6)?.let { t ->
            if (Math.abs(t - lastTrackingTilt) >= 2.0) { lastTrackingTilt = t; lc.tiltWhileTracking(t) }
        }
    }

    private var lastTrackingTilt = 30.0

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
            contentDescription = getString(if (on) R.string.main_cd_drive_view else R.string.main_cd_overview)
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
        showMessage(getString(R.string.main_overview_msg))
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
        // 1.0.4: navigating = right column = locate, overview, alternative
        // route, magnifier. 1.8.0 (Alex): the left column keeps travel mode
        // and urgency (changing them recalculates from where you are); the
        // services and settings come back on Stop / Clear.
        findViewById<View>(R.id.mode_row).visibility = View.VISIBLE
        findViewById<View>(R.id.poi_row).visibility = visibility
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

    /** Instruction box: navigating only (1.0.5). */
    private fun refreshNavBox() {
        val v = if (navUiActive) View.VISIBLE else View.GONE
        if (navBox.visibility != v) navBox.visibility = v
        placeRouteInfo()
    }

    /**
     * Distance · arrival · cost: the last line of the instruction box while
     * navigating; while planning a permanent message (message look, thin
     * frame), the lowest one in the message band, just over Go / Clear.
     */
    private fun placeRouteInfo() {
        val box = navBox as android.view.ViewGroup
        val band = findViewById<android.view.ViewGroup>(R.id.message_band)
        val target = if (navUiActive) box else band
        if (routeInfo.parent === target) return
        (routeInfo.parent as? android.view.ViewGroup)?.removeView(routeInfo)
        val lp = LinearLayout.LayoutParams(
            if (navUiActive) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (navUiActive) {
            routeInfo.background = null
            // 1.8.0: the distance to the next action first, under its circle
            // (end padding set in placeLayout, clear of the limit sign).
            routeInfo.setPadding(0, 0, dp(12), 0)
            (routeInfo as LinearLayout).gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        } else {
            lp.topMargin = dp(4)
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            (routeInfo as LinearLayout).gravity = android.view.Gravity.CENTER
            // 1.8.2: an information message like the others (faint grey, blue frame).
            routeInfo.background = ContextCompat.getDrawable(this, R.drawable.msg_info_background)
            routeInfo.setPadding(dp(11), dp(4), dp(11), dp(4))
        }
        // White in the box; the message colour in the band. Cost only before Go.
        val lineColor = if (navUiActive) Color.WHITE else ContextCompat.getColor(this, R.color.msg_text)
        for (t in listOf(routeInfoDist, routeInfoEta, routeInfoCost)) t.setTextColor(lineColor)
        // 1.8.2: no gap after the arrival time when it is the last item (navigating).
        (routeInfoEta.layoutParams as LinearLayout.LayoutParams).let { if (it.marginEnd != (if (navUiActive) 0 else dp(12))) { it.marginEnd = if (navUiActive) 0 else dp(12); routeInfoEta.layoutParams = it } }
        routeInfoCost.visibility = if (navUiActive) View.GONE else View.VISIBLE
        if (selectedRoute() != null || NavigationEngine.lastProgress != null) refreshRouteInfoText()
        routeInfo.isClickable = true // a tap on it never reaches the map
        target.addView(routeInfo, lp) // last child: the bottom line
    }

    /** After moving the line between band and box: the right text style. */
    private fun refreshRouteInfoText() {
        val p = NavigationEngine.lastProgress
        val r = selectedRoute()
        if (navUiActive && (p != null || r != null)) {
            val m = p?.remainingM ?: r!!.distanceMeters
            val sec = p?.remainingS ?: r!!.durationSeconds
            routeInfoDist.text = navInfo(getString(R.string.main_dist), navDistance(m), " \u00b7")
            routeInfoEta.text = navInfo(getString(R.string.main_eta), etaLabel(sec).removePrefix(getString(R.string.main_eta) + " "))
        } else r?.let { setRouteInfoText(it) }
    }

    /**
     * 1.8.2: the box's last line at 15.6 dp, made smaller (down to 11 dp) only
     * when distance + arrival would not fit the space left (narrow phones,
     * long words): never cut.
     */
    private fun fitRouteLine() {
        val group = findViewById<View>(R.id.route_info_group) ?: return
        val avail = group.width - group.paddingLeft - group.paddingRight
        val texts = listOf(routeInfoDist, routeInfoEta)
        val d = resources.displayMetrics.density
        if (!navUiActive) {
            for (t in texts) if (Math.abs(t.textSize - ROUTE_LINE_DP * d) > 0.5f) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, ROUTE_LINE_DP)
            return
        }
        if (avail <= 0) return
        var need = 0f
        for (t in texts) {
            val p = android.text.TextPaint(t.paint).apply { textSize = ROUTE_LINE_DP * d }
            need += android.text.Layout.getDesiredWidth(t.text, p)
            (t.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { need += it.marginStart + it.marginEnd }
        }
        val size = if (need <= avail) ROUTE_LINE_DP else maxOf(11f, ROUTE_LINE_DP * avail / need * 0.98f)
        for (t in texts) if (Math.abs(t.textSize - size * d) > 0.5f) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size)
    }

    /** "96 km" (1 decimal under 10). */
    private fun navDistance(m: Double): String {
        val v = if (useMetric) m / 1000.0 else m / 1609.344
        val unit = if (useMetric) "km" else "mi"
        return if (v < 10) "%.1f %s".format(v, unit) else "%.0f %s".format(v, unit)
    }

    /** 1.0.8 navigation line: small label, bold bigger value with its unit. */
    private fun navInfo(label: String, value: String, after: String = ""): CharSequence {
        val s = android.text.SpannableStringBuilder()
        s.append(label, android.text.style.RelativeSizeSpan(0.85f), 0)
        s.append(" ")
        val from = s.length
        s.append(value)
        s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, s.length, 0)
        s.setSpan(android.text.style.RelativeSizeSpan(1.25f), from, s.length, 0)
        s.append(after)
        return s
    }

    /** Route info for a planned route (distance, arrival, cost). */
    private fun setRouteInfoText(r: RouteResult) {
        routeInfoDist.text = distanceLabel(r.distanceMeters)
        routeInfoEta.text = etaLabel(r.durationSeconds)
        routeInfoCost.text = when {
            // 1.3.0 walking / cycling: climb and descent (tap the line for the profile).
            currentMode != ModeUi.DRIVING && r.elevations.isNotEmpty() -> {
                val (up, down) = com.backpack.app.core.Elevation.gain(r)
                fun h(v: Double) = if (useMetric) "${v.toInt()} m" else "${(v * 3.28084).toInt()} ft"
                "\u2191 ${h(up)}  \u2193 ${h(down)}"
            }
            r.tollDistanceMeters > 0 -> getString(R.string.main_cost, r.tollDistanceMeters / 1000.0 * tollRatePerKm)
            else -> getString(R.string.main_cost_none)
        }
    }

    // ---------- 1.3.0 recorded trips ----------

    private var tripToSave: com.backpack.app.track.TripRecorder.Trip? = null

    /** Android's own "save as" screen: you choose where the GPX goes. */
    private val saveGpxLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val trip = tripToSave ?: return@registerForActivityResult
        tripToSave = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(com.backpack.app.core.Gpx.write(tripTitle(trip), trip.points).toByteArray())
            }
            showMessage(getString(R.string.main_trip_saved))
        } catch (e: Exception) {
            showError(getString(R.string.main_trip_save_failed))
        }
    }

    private fun tripTitle(t: com.backpack.app.track.TripRecorder.Trip): String {
        val date = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.startMs))
        return if (t.name.isBlank()) getString(R.string.main_trip_default_name, date) else "${t.name} ($date)"
    }

    private fun showTrips() {
        val trips = com.backpack.app.track.TripRecorder.trips(this)
        val p = Popup(this, getString(R.string.main_my_trips))
        if (trips.isEmpty()) p.text(getString(R.string.main_no_trips))
        for (t in trips) {
            val pts = t.points
            val km = distanceLabel(com.backpack.app.core.Gpx.length(pts))
            val mins = if (pts.size >= 2) ((pts.last().timeMs - pts.first().timeMs) / 60_000L).toInt() else 0
            p.text("${tripTitle(t)}\n$km \u00b7 ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"}", 14f).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { p.dismiss(); showTrip(t) }
            }
        }
        if (trackShown) p.bodyButton(getString(R.string.main_hide_trip)) {
            layers.setTrack(null); trackShown = false; p.dismiss()
        }
        if (trips.isNotEmpty()) p.bodyButton(getString(R.string.main_delete_all_trips)) {
            com.backpack.app.track.TripRecorder.deleteAll(this)
            p.dismiss()
            showMessage(getString(R.string.main_all_trips_deleted))
        }
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
    }

    private var trackShown = false

    private fun showTrackOnMap(t: com.backpack.app.track.TripRecorder.Trip) {
        val pts = t.points.map { LatLon(it.lat, it.lon) }
        if (pts.size < 2) { showMessage(getString(R.string.main_nothing_recorded)); return }
        layers.setTrack(pts)
        trackShown = true
        stopPlanFollow()
        val m = map ?: return
        try {
            val bounds = LatLngBounds.Builder().includes(pts.map { LatLng(it.lat, it.lon) }).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(48), dp(130), dp(48), dp(150)), 800)
        } catch (e: Exception) { }
        showMessage(getString(R.string.main_trip_shown, tripTitle(t)))
    }

    private fun showTrip(t: com.backpack.app.track.TripRecorder.Trip) {
        val p = Popup(this, tripTitle(t))
        p.text(getString(R.string.main_trip_info, t.points.size, distanceLabel(com.backpack.app.core.Gpx.length(t.points))))
        p.button(getString(R.string.main_delete)) { com.backpack.app.track.TripRecorder.delete(t); p.dismiss(); showMessage(getString(R.string.main_trip_deleted)) }
        // 1.4.0: the track on the map, framed whole.
        p.button(getString(R.string.main_show_on_map)) { p.dismiss(); showTrackOnMap(t) }
        p.button(getString(R.string.main_save_gpx), primary = true) {
            p.dismiss()
            tripToSave = t
            val name = "Backpack_" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(java.util.Date(t.startMs)) + ".gpx"
            try { saveGpxLauncher.launch(name) } catch (e: Exception) { showError(getString(R.string.main_no_save_app)) }
        }
        p.show()
    }

    /** 1.3.0: height profile of the route (walking / cycling). */
    private fun showElevation(r: RouteResult) {
        val pts = com.backpack.app.core.Elevation.profile(r)
        if (pts.isEmpty()) return
        val (up, down) = com.backpack.app.core.Elevation.gain(r)
        fun h(v: Double) = if (useMetric) "${v.toInt()} m" else "${(v * 3.28084).toInt()} ft"
        val p = Popup(this, getString(R.string.main_height_profile))
        p.text(getString(R.string.main_climb_descent, h(up), h(down), distanceLabel(r.distanceMeters)))
        p.view(com.backpack.app.ui.ElevationView(this, pts, useMetric), 140)
        p.button(getString(R.string.main_close), primary = true) { p.dismiss() }
        p.show()
    }

    /** Message band: full width in portrait; landscape compact, centred
     *  between the two button columns (1.7.1). */
    private fun shapeMessageBand(land: Boolean) {
        val band = findViewById<View>(R.id.message_band)
        val lp = band.layoutParams as FrameLayout.LayoutParams
        val w = if (land) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT
        if (lp.width != w) { lp.width = w; band.layoutParams = lp }
        val screenW = findViewById<View>(android.R.id.content).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val maxW = if (land) minOf(dp(340), screenW - 2 * dp(COLUMN_ZONE_DP)) else Int.MAX_VALUE
        for (b in listOf(messageBubble, errorBubble)) {
            val blp = b.layoutParams
            if (blp.width != bw(land)) { blp.width = bw(land); b.layoutParams = blp }
            if (b.maxWidth != maxW) b.maxWidth = maxW
        }
        if (routeInfo.parent === band) {
            val rlp = routeInfo.layoutParams
            if (rlp.width != bw(land)) { rlp.width = bw(land); routeInfo.layoutParams = rlp }
        }
    }

    private fun bw(land: Boolean) = if (land) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT

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
        // Portrait: full width; landscape: 80 %.
        // 1.4.1: 4 dp from the screen edges (half the old 8).
        val boxW = if (navUiActive) ((screenW - dp(8)) * (if (land) 0.8f else 1f)).toInt() else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (boxLp.width != boxW) { boxLp.width = boxW; navBox.layoutParams = boxLp }
        // Room for the speed circle at the box's right end (driving, navigating).
        val circleInBox = navUiActive // 1.1.0: speed shown in every mode while navigating
        // 1.7.2: 16 dp on the right (the speed circle is placed on its own);
        // 1.8.2: 4 dp on the left, so the turn circle sits exactly in the box's
        // left corner, touching the frame like the speed circle (nothing cut).
        // 8 dp at the bottom: the last line clear of the round bottom corners.
        navBox.setPadding(dp(4), dp(4), dp(16), dp(8))
        // 1.8.0: the speed circle (4..74 dp from the box's right edge) sits beside
        // the turn row; the limit sign went down to the box's last line.
        // Driving: also clear of the limit sign below-left of the circle (2-line street names).
        navInstructionBar.setPadding(0, 0, if (!circleInBox) 0 else if (driving) dp(74) else dp(62), 0)
        navInstructionBar.minimumHeight = if (circleInBox) dp(SPEED_CIRCLE_DP) else 0
        // Planning while driving: box and chips centred left of the speed
        // circle (top right), never under it.
        val circleTopRight = driving && !circleInBox
        // 1.7.2: the circle always where it sits in the navigation box (top right,
        // 4 dp inside the box's corner), with or without the box.
        val navBoxW = ((screenW - dp(8)) * (if (land) 0.8f else 1f)).toInt()
        val circleEnd = ((screenW - navBoxW) / 2 / density).toInt() + 4
        // Planning: the limit sign beside the circle's top (~70 dp more to the left).
        setMargins(R.id.nav_box, start = if (circleTopRight) 8 else 0, end = if (circleTopRight) circleEnd + SPEED_CIRCLE_DP + 42 else 0)
        val boxShown = navBox.visibility == View.VISIBLE && navBox.height > 0

        // 1.7.1: the columns' top and spacing (bigger buttons): portrait under
        // the full-width box; landscape (box 80 % wide, columns beside it)
        // fitted to the screen height so the 4th button stays clear of the
        // bottom row -- first the gaps shrink (down to 4 dp), then, only if
        // still needed, the columns start higher (never under the speed circle).
        // The keyboard shrinks the screen: the columns keep where they were.
        var top0 = if (land) COLUMNS_TOP_DP else COLUMNS_TOP_PORTRAIT_DP
        // 1.8.2 (Alex): 8 dp between the buttons, both orientations.
        var step = BTN_DP + 8
        if (keyboardOpen && columnsFor == land) {
            top0 = columnsTop; step = columnsStep
        } else {
            val content = findViewById<View>(android.R.id.content)
            var screenH = (content.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / density
            // Turned with the keyboard open: fit to the height without it.
            if (keyboardOpen) androidx.core.view.ViewCompat.getRootWindowInsets(content)?.let {
                // The keyboard inset includes the navigation bar, already outside the content.
                val ime = it.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val bars = it.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                screenH += maxOf(0, ime - bars) / density
            }
            val bottomLimit = screenH - (BOTTOM_ROW_BOTTOM_DP + BOTTOM_ROW_DP) - 6
            if (top0 + 3 * step + BTN_DP > bottomLimit) {
                step = maxOf(BTN_DP + 4, ((bottomLimit - BTN_DP - top0) / 3).toInt())
                // Higher only in landscape (portrait: the full-width box is above).
                if (land && top0 + 3 * step + BTN_DP > bottomLimit) top0 = maxOf(COLUMNS_MIN_TOP_DP, (bottomLimit - BTN_DP - 3 * step).toInt())
            }
            if (!keyboardOpen) { columnsTop = top0; columnsStep = step; columnsFor = land }
        }

        // 1.2.1: the columns never move, so the box keeps above them: a long
        // street name gets one line instead of two when it would reach them.
        // 1.4.2: the turn row is at least the circle's size: two lines fit in it,
        // so only a row grown past that (large font) is cut to one line.
        if (navUiActive && boxShown && !land && navInstructionBar.height > dp(SPEED_CIRCLE_DP + 2) && navBox.bottom / density > top0 - 4 && statusStrip.maxLines > 1) {
            statusStrip.maxLines = 1
        }

        // Speed circle: fitted in the box's top-right corner (outer curve 39 dp
        // = circle radius 35 + frame 4; the drawable's 37 dp is the stroke's
        // centre line); 1.7.2: the same spot without the box.
        val boxTop = if (circleInBox && boxShown) (navBox.top / density).toInt() else NAV_BOX_TOP_DP
        setMargins(R.id.speed_widget, top = boxTop + 4, end = circleEnd)
        // 1.8.0 (Alex): the limit sign at the bottom of the box, touching the
        // speed circle and the box's frame; planning: beside the circle's top.
        val signReserve = placeLimitSign(screenW, circleEnd, boxTop + 4,
            if (circleInBox && boxShown) navBox.bottom / density - 4f else null)
        // The box's last line keeps clear of the sign (driving: the sign's place
        // is kept even while no limit is known, so the line never jumps).
        if (routeInfo.parent === navBox) {
            val end = if (driving && signReserve != null) dp(signReserve - 16 + 4) else dp(12)
            if (routeInfo.paddingEnd != end || routeInfo.paddingStart != 0) routeInfo.setPadding(0, 0, end, 0)
        }

        // 1.2.1 (Alex): the columns never move with the mode -- the same
        // height in every mode, planning or navigating (top0 / step above).
        val gap = step - BTN_DP
        // A view hidden only for the keyboard still counts (it comes back below).
        fun shown(id: Int) = findViewById<View>(id).let { it.visibility == View.VISIBLE || it in hiddenForKeyboard }

        // Left column (planning), 1.2.0 four buttons: mode (+ toll), urgency
        // (+ sights camera in Weekend), backpack services, settings; resume.
        setMargins(R.id.mode_row, top = top0)
        setMargins(R.id.urgency_row, top = gap)
        setMargins(R.id.poi_row, top = gap)
        setMargins(R.id.route_options_button, top = top0 + 3 * step)
        val leftRows = if (navUiActive) 2 else 4

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
        } else {
            setMargins(R.id.waypoint_button, top = top0 + step)
            setMargins(R.id.preference_toggle_button, top = top0 + 2 * step)
            setMargins(R.id.search_button, top = top0 + 3 * step)
            rightRows = 4
        }

        // Route chips (1.7.1, Alex): a route-choosing action, so on the right
        // side, right-aligned, just over the messages and growing upward
        // (placed below, once the message band is placed); hidden while the
        // search box is open (kept).
        moveTo(R.id.route_options_scroll, android.view.Gravity.BOTTOM or android.view.Gravity.END)
        findViewById<View>(R.id.route_options_scroll).visibility = if (searchOpen) View.GONE else View.VISIBLE

        // While typing, the bottom buttons and the credit step aside; only
        // what was shown is hidden, and only that comes back.
        // 1.8.2: the side columns too (the search box and its suggestions take their place).
        val under = listOf<View>(findViewById(R.id.bottom_bar), findViewById(R.id.credit_text), findViewById(R.id.mode_row),
            findViewById(R.id.route_options_button), findViewById(R.id.locate_button), findViewById(R.id.waypoint_button),
            findViewById(R.id.preference_toggle_button), findViewById(R.id.alt_route_button), findViewById(R.id.overview_button),
            findViewById(R.id.search_button))
        if (keyboardOpen) {
            for (v in under) if (v.visibility == View.VISIBLE) { v.visibility = View.INVISIBLE; hiddenForKeyboard.add(v) }
        } else {
            for (v in hiddenForKeyboard) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
            hiddenForKeyboard.clear()
        }

        // Bottom: the action row (20..69, 1.8.0) -> messages (70). The floating
        // search box sits in the messages' place (just over the keyboard while
        // typing), the messages and suggestions over it.
        val searchBottom = if (keyboardOpen) 8 else MESSAGES_BOTTOM_DP
        setMargins(R.id.search_input, bottom = searchBottom, start = 8, end = 8)
        setMargins(R.id.search_mic, bottom = searchBottom, end = 10)
        setMargins(R.id.search_results_scroll, bottom = searchBottom + 33, start = 8, end = 8)
        val bandBottom = if (searchOpen) searchBottom + 33 else MESSAGES_BOTTOM_DP
        // 1.7.1 (Alex): centred in portrait and landscape; in landscape kept
        // between the two button columns.
        moveTo(R.id.message_band, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        shapeMessageBand(land)
        // Search open: the route line steps aside (it would cover the lowest suggestion).
        val hideInfo = searchOpen && routeInfo.parent === findViewById<View>(R.id.message_band)
        if (hideInfo && routeInfo.visibility == View.VISIBLE) routeInfo.visibility = View.INVISIBLE
        else if (!hideInfo && routeInfo.visibility == View.INVISIBLE) routeInfo.visibility = View.VISIBLE
        val sideDp = if (land) COLUMN_ZONE_DP else 8
        setMargins(R.id.message_band, bottom = bandBottom, start = sideDp, end = sideDp)
        // 1.2.0: route chips at a fixed height (room for the route line and a
        // two-line message under them): a message appearing no longer moves
        // them while you are choosing.
        // Only an unusually tall band (two wrapped bubbles) lifts them, so it never covers a chip.
        val band = findViewById<View>(R.id.message_band)
        val bandH = if (band.visibility == View.VISIBLE) (band.height / resources.displayMetrics.density).toInt() else 0
        // Portrait: against the right edge, up to just under the right column;
        // landscape (no room under it): just left of the column, up to its top.
        // More chips than fit: the stack scrolls.
        val columnBottom = top0 + (rightRows - 1) * step + BTN_DP
        setMargins(R.id.route_options_scroll, bottom = bandBottom + maxOf(PILLS_ABOVE_BAND_DP, bandH + 6),
            start = if (land) COLUMN_ZONE_DP else 8, end = if (land) COLUMN_ZONE_DP else 8,
            top = if (!land) columnBottom + 8 else if (boxShown) maxOf(top0, (navBox.bottom / density).toInt() + 4) else top0)

        // Guards: columns (with a little margin around) and the bottom buttons.
        setMargins(R.id.guard_left, top = top0 - 6)
        setSize(R.id.guard_left, height = if (leftRows == 0 || keyboardOpen) 0 else leftRows * step + 4, width = 90)
        setMargins(R.id.guard_right, top = top0 - 6)
        setSize(R.id.guard_right, height = if (keyboardOpen) 0 else rightRows * step + 4, width = 58)
        setSize(R.id.guard_bottom, height = if (keyboardOpen) 0 else BOTTOM_ROW_BOTTOM_DP + BOTTOM_ROW_DP + 2)
    }

    /**
     * 1.8.0: the limit sign touches the speed circle (centres exactly one radius
     * sum apart), never overlapping. With the box ([innerBottomDp] = the frame's
     * inner bottom edge) it sits on that edge, under-left of the circle;
     * without it, level with the circle's top, to its left. Returns how far its
     * left edge is from the circle's right margin line (dp), for the box's last line.
     */
    private fun placeLimitSign(screenW: Int, circleEndDp: Int, circleTopDp: Int, innerBottomDp: Float?): Int? {
        val bigR = SPEED_CIRCLE_DP / 2f
        val r = LIMIT_SIGN_DP / 2f
        val cy = circleTopDp + bigR
        // Centre of the sign, in dp from the top.
        val sy = if (innerBottomDp != null) innerBottomDp - r else circleTopDp + r
        val dy = Math.abs(sy - cy)
        val dx = if (dy >= bigR + r) 0f else Math.sqrt(((bigR + r) * (bigR + r) - dy * dy).toDouble()).toFloat()
        // dp from the screen's right edge to the sign's right edge (+0.5 dp: rounding never overlaps).
        val endDp = circleEndDp + bigR + dx - r + 0.5f
        val d = resources.displayMetrics.density
        val lp = speedLimitSign.layoutParams as FrameLayout.LayoutParams
        val end = Math.ceil((endDp * d).toDouble()).toInt()
        val top = Math.round((sy - r) * d)
        if (lp.gravity != (android.view.Gravity.TOP or android.view.Gravity.END) || lp.marginEnd != end || lp.topMargin != top) {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.marginEnd = end
            lp.topMargin = top
            speedLimitSign.layoutParams = lp
        }
        // From the box's outer right edge (circleEnd - 4) to the sign's left edge.
        return if (innerBottomDp != null) Math.ceil((endDp + 2 * r - (circleEndDp - 4)).toDouble()).toInt() else null
    }

    /** Changes a view's gravity inside the main frame (no-op if unchanged). */
    private fun moveTo(id: Int, gravity: Int) {
        val v = findViewById<View>(id)
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.gravity != gravity) { lp.gravity = gravity; v.layoutParams = lp }
    }

    /** Space between the stacked chips. */
    private fun chipSpacing(chip: View) {
        val lp = chip.layoutParams as LinearLayout.LayoutParams
        lp.marginEnd = 0
        lp.bottomMargin = dp(4)
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

    /**
     * 1.8.0 (Alex): screen brightness while the map screen is in front (it
     * never sleeps there):
     * - something to see (alert, sight card, message, instruction, speed
     *   alarm, a touch, weak GPS): full for [WAKE_MS];
     * - moving: 60 %;
     * - not moving: 30 % when charging or navigating, almost off on battery.
     */
    private fun wakeDisplay() {
        fullUntilMs = System.currentTimeMillis() + WAKE_MS
        applyBrightness()
    }

    private var fullUntilMs = 0L
    private var shownBrightness = -2f

    private fun applyBrightness() {
        val now = System.currentTimeMillis()
        val fix = LocationHub.lastGoodFix
        val moving = fix != null && fix.hasSpeed() && fix.speed > 1.5f && now - lastRealLocationAtMs < 10_000L
        val charging = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)?.isCharging == true
        val level = when {
            // A popup or the keyboard in front (their touches never reach this window).
            now < fullUntilMs || isGpsDegraded || isBlinking || keyboardOpen || !hasWindowFocus() -> 1.0f
            moving -> 0.6f
            charging || navUiActive -> 0.3f
            else -> 0.01f
        }
        setWindowBrightness(level)
    }

    private fun setWindowBrightness(level: Float) {
        if (level == shownBrightness) return
        shownBrightness = level
        val params = window.attributes
        params.screenBrightness = level
        window.attributes = params
    }

    private val brightnessTick = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            applyBrightness()
            handler.postDelayed(this, BRIGHTNESS_TICK_MS)
        }
    }

    /** How long the last touch lasted (1.9.0: accidental touches on the map are ignored). */
    private var touchDownMs = 0L
    private var lastTouchMs = Long.MAX_VALUE

    /** A touch anywhere: full brightness for a while. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { wakeDisplay(); touchDownMs = ev.eventTime }
            android.view.MotionEvent.ACTION_UP -> lastTouchMs = ev.eventTime - touchDownMs
        }
        return super.dispatchTouchEvent(ev)
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
        background.setStroke(dp(4), Color.rgb(r, g, b))
    }

    /** During a GPS gap (3-15 s), keeps the arrow and camera moving with the
     *  last speed and bearing -- visual only; navigation decisions stay on
     *  real fixes. */
    private val deadReckoningRunnable = object : Runnable {
        override fun run() {
            if (!navUiActive) return
            val gapMs = System.currentTimeMillis() - lastRealLocationAtMs
            val fix = LocationHub.lastGoodFix
            // A gap = clearly longer than the GPS pace asked (1.1.0: 1-5 s by mode).
            val pace = LocationHub.currentIntervalMs.coerceAtLeast(1000L)
            if (gapMs in (pace + 2000L)..15000L && fix != null && fix.hasSpeed() && fix.hasBearing() && fix.speed > 0.5f) {
                deadReckoningActive = true
                val projected = Geo.project(LatLon(fix.latitude, fix.longitude), fix.bearing.toDouble(), fix.speed * gapMs / 1000.0)
                val loc = Location(fix).apply {
                    latitude = projected.lat
                    longitude = projected.lon
                }
                if (puckActive()) map?.locationComponent?.forceLocationUpdate(loc)
            } else if (gapMs > 15000 && deadReckoningActive) {
                deadReckoningActive = false
                showMessage(getString(R.string.main_gps_lost))
            }
            handler.postDelayed(this, 1000L)
        }
    }

    // ---------- 1.1.1 Services (multi-select) ----------

    /** Services you ticked, in panel order. Emergency is added by itself in Emergency mode. */
    private val selectedServices = LinkedHashSet<PoiCategory>()

    private fun loadServices() {
        val prefs = getSharedPreferences("backpack_toggle_state", MODE_PRIVATE)
        selectedServices.clear()
        val saved = prefs.getString("services", null)
        if (saved != null) {
            saved.split(',').mapNotNull { n -> PoiCategory.values().firstOrNull { it.name == n } }
                .filter { it != PoiCategory.NONE }.forEach { selectedServices.add(it) }
        } else {
            // Before 1.1.1: one category (Sights / Off ignored).
            PoiCategory.values().firstOrNull { it.name == prefs.getString("poi_category", null) }
                ?.takeIf { it != PoiCategory.NONE }?.let { selectedServices.add(it) }
        }
    }

    private fun saveServices() {
        getSharedPreferences("backpack_toggle_state", MODE_PRIVATE).edit()
            .putString("services", PoiCategory.values().filter { it in selectedServices }.joinToString(",") { it.name })
            .apply()
    }

    /** What is shown: your ticks, plus Emergency in Emergency mode. */
    private fun activeServices(): List<PoiCategory> = PoiCategory.values().filter {
        it != PoiCategory.NONE && (it in selectedServices || (it == PoiCategory.EMERGENCY && urgencyMode == UrgencyMode.EMERGENCY))
    }

    /** Services button: icon of the first ticked service in its colour, and
     *  a small count when more than one is on; red ring when some are shown. */
    private fun refreshServicesButton(found: Boolean = poiResults.isNotEmpty()) {
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        val active = activeServices()
        // 1.2.0: always the red backpack in its own colours (no tint), faded
        // while nothing is ticked; the badge says how many services are on.
        if (button.tag != R.drawable.ic_backpack) { button.tag = R.drawable.ic_backpack; button.setImageResource(R.drawable.ic_backpack) }
        button.imageTintList = null
        button.imageAlpha = if (active.isEmpty()) 110 else 255
        setStatusRing(button, active.isNotEmpty() && found)
        findViewById<TextView>(R.id.poi_count).apply {
            visibility = if (active.isNotEmpty()) View.VISIBLE else View.GONE
            text = active.size.toString()
        }
    }

    private fun setupPoiToggle() {
        val button = findViewById<ImageButton>(R.id.poi_toggle_button)
        refreshServicesButton(false)
        button.setOnClickListener { showServicesPanel(button) }
    }

    /** Two groups, two columns, ticks: several services at once. Ticking
     *  only filters what is already on the phone (no new search). */
    private fun showServicesPanel(anchor: View) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.popup_background)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val window = android.widget.PopupWindow(root, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        val textColor = ContextCompat.getColor(this, R.color.rg_text)
        val cells = HashMap<PoiCategory, TextView>()
        fun paint(cat: PoiCategory) {
            val on = cat in selectedServices
            cells[cat]?.apply {
                setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (on) (cat.color ?: activeColor) else textColor)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (on) Color.argb(40, Color.red(cat.color ?: activeColor), Color.green(cat.color ?: activeColor), Color.blue(cat.color ?: activeColor)) else Color.TRANSPARENT)
                    setStroke(Math.max(1, dp(1)), if (on) (cat.color ?: activeColor) else Color.TRANSPARENT)
                }
            }
        }
        fun header(t: String) = root.addView(TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.rg_text_secondary))
            setPadding(dp(6), dp(6), dp(6), dp(2))
        })
        fun group(cats: List<PoiCategory>) {
            for (pair in cats.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (cat in pair) {
                    val cell = TextView(this).apply {
                        text = getString(cat.labelRes)
                        textSize = 14f
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        compoundDrawablePadding = dp(6)
                        setPadding(dp(6), dp(5), dp(8), dp(5))
                        val icon = ContextCompat.getDrawable(this@MainActivity, cat.iconRes)?.mutate()
                        icon?.setBounds(0, 0, dp(22), dp(22))
                        icon?.setTint(cat.color ?: inactiveColor)
                        setCompoundDrawables(icon, null, null, null)
                        setOnClickListener {
                            if (cat in selectedServices) selectedServices.remove(cat) else selectedServices.add(cat)
                            saveServices()
                            paint(cat)
                            servicesChanged()
                        }
                    }
                    cells[cat] = cell
                    row.addView(cell, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    paint(cat)
                }
                root.addView(row)
            }
        }
        header(getString(R.string.main_essentials))
        group(PoiCategory.values().filter { it.essential })
        header(getString(R.string.main_extras))
        group(PoiCategory.values().filter { it != PoiCategory.NONE && !it.essential })
        if (urgencyMode == UrgencyMode.EMERGENCY) header(getString(R.string.main_emergency_always))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
        fun action(label: String, onClick: () -> Unit) = buttons.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(activeColor)
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setOnClickListener { onClick() }
        })
        action(getString(R.string.main_all_off)) {
            selectedServices.clear()
            saveServices()
            PoiCategory.values().forEach { paint(it) }
            servicesChanged()
        }
        action(getString(R.string.main_done)) { window.dismiss() }
        root.addView(buttons)
        // Scrolls when the screen is low (landscape): never cut off.
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        window.contentView = scroll
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenH = findViewById<View>(android.R.id.content).height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val below = screenH - (loc[1] + anchor.height) - dp(12)
        val above = loc[1] - dp(12)
        window.height = minOf(root.measuredHeight, maxOf(below, above, dp(160)))
        window.elevation = dp(6).toFloat()
        window.isOutsideTouchable = true
        window.showAsDropDown(anchor, 0, dp(4))
    }

    /** A tick changed: filter what is on the phone; search only if never searched here. */
    private fun servicesChanged() {
        if (activeServices().isEmpty()) {
            svcSlot.reset()
            refreshPoiPins()
            showMessage(getString(R.string.main_services_off))
            return
        }
        refreshPoiPins()
        // 1.2.1: a newly ticked service is fetched (instant when on the phone).
        if (svcSlot.key == null) queryPois(force = true) else queryPois()
    }

    // ---------- 1.1.1 Sights (own button, kinds, Weekend default) ----------

    private fun sightsPrefs() = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)

    /** 1.2.0: sights only in Weekend mode, with its small camera on (default). */
    private fun sightsSwitchOn(): Boolean =
        urgencyMode == UrgencyMode.WEEKEND && sightsPrefs().getBoolean(Settings.KEY_SIGHTS_WEEKEND, true)

    private fun sightGroups(): Set<com.backpack.app.core.SightGroup> =
        com.backpack.app.core.SightGroup.parse(sightsPrefs().getString(Settings.KEY_SIGHT_GROUPS, null))

    private fun sightKinds(): Set<com.backpack.app.core.TouristCategory> = sightGroups().flatMap { it.kinds }.toSet()

    /** 1.2.0: the small camera next to Weekend (like the toll switch next
     *  to Car): shown in Weekend only; purple = sights on, grey = off. */
    private fun refreshSightsButton() {
        val b = findViewById<ImageButton>(R.id.sights_toggle)
        val v = if (urgencyMode == UrgencyMode.WEEKEND) View.VISIBLE else View.GONE
        if (b.visibility != v) b.visibility = v
        // 1.4.1 (Alex's icons): two-tone camera = sights on, outlined = off.
        val icon = if (touristOn) R.drawable.ic_sights_on else R.drawable.ic_sights_off
        if (b.tag != icon) { b.tag = icon; b.setImageResource(icon) }
        setIconActive(b, touristOn)
        setStatusRing(b, touristOn && touristResults.isNotEmpty())
    }

    private fun setupSightsButton() {
        val b = findViewById<ImageButton>(R.id.sights_toggle)
        b.setOnClickListener {
            val on = !sightsPrefs().getBoolean(Settings.KEY_SIGHTS_WEEKEND, true)
            sightsPrefs().edit().putBoolean(Settings.KEY_SIGHTS_WEEKEND, on).apply()
            sightsChanged()
            showMessage(getString(when {
                !on -> R.string.main_sights_off
                sightGroups().isEmpty() -> R.string.main_sights_no_kinds
                else -> R.string.main_sights_on
            }))
        }
        // Hold: the list of the places shown.
        b.setOnLongClickListener {
            if (touristOn && touristResults.isNotEmpty()) showTouristList() else showMessage(getString(R.string.main_no_sights_shown))
            true
        }
        refreshSightsButton()
    }

    /** On/off or kinds changed: kinds only re-filter what is on the phone. */
    private fun sightsChanged() {
        val wasOn = touristWasOn
        touristModeChanged()
        if (wasOn && touristOn) showTouristFor(if (TripState.plan != null) selectedRoute() else null)
        refreshSightsButton()
    }

    // ---------- Services search (1.0.9 adaptive, 1.1.1 one search for all) ----------

    /** The services search: results, key and retry state. */
    private inner class PoiSlot {
        val finder = PlacesFinder(this@MainActivity)
        /** Every service found (filtered by your ticks when drawn). */
        var results: List<PoiPlace> = emptyList()
        var key: String? = null
        var origin: LatLon? = null
        var sayKey: String? = null
        var gen = 0
        /** 1.2.1: generation of the search in flight (0 = none), for "Searching places...". */
        var busyGen = 0
        var failures = 0
        var retryAtMs = 0L

        fun reset() {
            gen++
            busyGen = 0
            finder.cancel()
            results = emptyList()
            key = null
            origin = null
        }
    }

    private val svcSlot by lazy { PoiSlot() }

    /** All services of a square in one request, kept on the phone 3 days. */
    private val servicesCategory by lazy {
        PlacesFinder.Category("services2", "", com.backpack.app.core.Places.SERVICES.flatMap { it.orsIds.entries }.associate { it.key to it.value }).apply {
            query = { tiles -> com.backpack.app.core.Places.servicesQuery(tiles) }
            maxShown = 600
            // 1.2.1: only the services ticked (faster answers); the rest of
            // their groups follows in the background for the same squares.
            services = { activeServices().map { it.name } }
            backgroundServices = {
                val active = activeServices()
                PoiCategory.values().filter { c ->
                    c != PoiCategory.NONE && c !in active && active.any { it.essential == c.essential }
                }.map { it.name }
            }
        }
    }

    /** Route whose services were fetched ahead (whole route, background). */
    private var prefetchedRouteKey: String? = null

    /** Longest there-and-back detour for places along a route (minutes). */
    private fun detourMinutes(emergencyLayer: Boolean = false): Int {
        val prefs = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
        return when {
            emergencyLayer -> Settings.EMERGENCY_DETOUR_MIN
            urgencyMode == UrgencyMode.WEEKEND -> prefs.getInt(Settings.KEY_DETOUR_WEEKEND_MIN, Settings.DEFAULT_DETOUR_WEEKEND_MIN)
            else -> prefs.getInt(Settings.KEY_DETOUR_WORK_MIN, Settings.DEFAULT_DETOUR_WORK_MIN)
        }
    }

    /** That detour as a corridor half-width around the route. */
    private fun corridorM(emergencyLayer: Boolean = false): Double =
        Tourist.offsetForDetour(detourMinutes(emergencyLayer) * 60.0, currentMode.mode)

    /** Ticked services on the map (each within its own detour along a
     *  route), at most 30 per service; taps look places up here. */
    private fun refreshPoiPins() {
        val active = activeServices()
        val alongRoute = svcSlot.key?.startsWith("R/") == true
        val shown = ArrayList<Pair<PoiPlace, PoiCategory>>()
        val taken = HashSet<String>()
        for (cat in active) {
            val radius = if (alongRoute) corridorM(cat == PoiCategory.EMERGENCY) else Double.MAX_VALUE
            svcSlot.results.asSequence()
                .filter { cat.name in it.services && it.offsetM <= radius && it.key !in taken }
                .take(30)
                .forEach { taken.add(it.key); shown.add(it to cat) }
        }
        poiResults = shown.map { it.first }
        // Counts for the message: a café with Wi-Fi counts for both.
        serviceCounts = active.associateWith { cat ->
            val radius = if (alongRoute) corridorM(cat == PoiCategory.EMERGENCY) else Double.MAX_VALUE
            minOf(30, svcSlot.results.count { cat.name in it.services && it.offsetM <= radius })
        }
        // 1.3.0: closed right now (opening hours known) = faint.
        layers.setPois(shown.map { (p, cat) ->
            MapLayers.Pin(p.key, p.point, cat.iconName, dim = com.backpack.app.core.OpeningHours.isOpenNow(p.openingHours) == false)
        })
        refreshServicesButton()
    }

    private var serviceCounts: Map<PoiCategory, Int> = emptyMap()

    /** Short summary for the message: "5 Fuel/Charging, 3 Food". */
    private fun servicesSummary(): String {
        val counts = activeServices().mapNotNull { cat ->
            val n = serviceCounts[cat] ?: 0
            if (n == 0) null else "$n ${getString(cat.labelRes)}"
        }
        return counts.joinToString(", ")
    }

    /**
     * 1.1.1: one search for every service (filtered on the phone by your
     * ticks). With a route: along it (the route ahead while navigating),
     * as far as the longest detour wanted (Weekend / Work in Settings >
     * Places, emergency 15 min). Without: within reach of you or the map
     * centre (Emergency mode: of you).
     */
    private fun queryPois(force: Boolean = false) {
        val active = activeServices()
        if (active.isEmpty()) { if (svcSlot.key != null || svcSlot.results.isNotEmpty()) { svcSlot.reset(); refreshPoiPins() }; return }
        val slot = svcSlot
        val now = System.currentTimeMillis()
        val route = if (navUiActive) NavigationEngine.route ?: selectedRoute() else selectedRoute()
        val announce: Boolean
        if (TripState.plan != null && route != null && route.points.size >= 2) {
            // As far as the widest detour of the services on (emergency: 15 min).
            val radius = active.maxOf { corridorM(it == PoiCategory.EMERGENCY) }
            val ahead = minOf(Tourist.searchAheadM(currentMode.mode), 60_000.0)
            val from = if (navUiActive) NavigationEngine.lastProgress?.alongM ?: 0.0 else 0.0
            val names = active.joinToString(",") { it.name }
            val key = "R/${System.identityHashCode(route)}/${radius.toInt()}/${(from / (ahead * 0.4)).toInt()}/$names"
            if (!force && key == slot.key) return
            if (!force && now < slot.retryAtMs) return
            val sayKey = "${TripState.destination}/${active.joinToString { it.name }}/${radius.toInt()}"
            announce = force || sayKey != slot.sayKey
            slot.sayKey = sayKey
            slot.key = key
            slot.origin = null
            val gen = ++slot.gen
            slot.busyGen = gen
            pokeSearching()
            val stretch = com.backpack.app.core.SpeedLimits.section(route.line, from, ahead)
            slot.finder.alongRoutes(servicesCategory, listOf(stretch), radius,
                onSwitch = { if (gen == slot.gen && force) onPlacesServerSwitch() }
            ) { results, error, note, serverFailed ->
                onPoiResults(slot, gen, results, error, note, serverFailed, announce, getString(R.string.main_along_your_route))
                // 1.2.1: then the rest of the route, in the background.
                val pk = "${System.identityHashCode(route)}/${radius.toInt()}/$names"
                if (results != null && gen == slot.gen && pk != prefetchedRouteKey) {
                    prefetchedRouteKey = pk
                    val rest = com.backpack.app.core.SpeedLimits.section(route.line, from, route.line.length)
                    slot.finder.prefetchAlong(servicesCategory, rest, radius)
                }
            }
            return
        }
        // No route: within reach of you or the map centre.
        // Emergency mode: always around you (hospitals near you, not near the map centre).
        val aroundMe = urgencyMode == UrgencyMode.EMERGENCY
        val origin = (if (aroundMe) currentPosition() else null) ?: cameraCenter() ?: return
        val minutes = sightsReachMinutes()
        val key = "A/${currentMode.name}/$minutes/$aroundMe/${active.joinToString(",") { it.name }}"
        val step = maxOf(if (aroundMe) 2000.0 else 3000.0, 0.25 * minOf(Reach.radiusM(currentMode.mode, minutes), Reach.MAX_SEARCH_M))
        val moved = slot.origin?.let { Geo.distance(it, origin) >= step } ?: true
        if (!force && key == slot.key && !moved) return
        if (!force && now < slot.retryAtMs) return
        announce = force || key != slot.key
        slot.key = key
        slot.origin = origin
        val gen = ++slot.gen
        slot.busyGen = gen
        pokeSearching()
        val time = if (minutes < 60) "$minutes min" else "${minutes / 60} h"
        val how = when (currentMode.mode) {
            com.backpack.app.core.TravelMode.WALKING -> getString(R.string.main_on_foot)
            com.backpack.app.core.TravelMode.CYCLING -> getString(R.string.main_by_bike)
            com.backpack.app.core.TravelMode.DRIVING -> getString(R.string.main_by_car)
        }
        ReachArea.get(this, origin, currentMode.mode, minutes) { ring, exact ->
            if (isDestroyed || gen != slot.gen) return@get
            val where = reachText(time, how, exact, aroundMe)
            slot.finder.inReach(servicesCategory, ring, origin,
                onSwitch = { if (gen == slot.gen && force) onPlacesServerSwitch() }
            ) { results, error, note, serverFailed ->
                onPoiResults(slot, gen, results, error, note, serverFailed, announce, where)
            }
        }
    }

    private fun onPoiResults(
        slot: PoiSlot, gen: Int, results: List<PoiPlace>?, error: String?, note: String?,
        serverFailed: Boolean, announce: Boolean, where: String
    ) {
        if (gen != slot.gen || isDestroyed) return
        slot.busyGen = 0
        refreshSearching()
        if (results == null) {
            // Allowed again when asked, or after a pause (1, 2, then 5 min).
            slot.key = null
            slot.failures++
            slot.retryAtMs = System.currentTimeMillis() + when (slot.failures) { 1 -> 60_000L; 2 -> 120_000L; else -> 300_000L }
            refreshServicesButton(false)
            showError(error ?: getString(R.string.main_places_search_failed), retry = { queryPois(force = true) })
            return
        }
        if (serverFailed) {
            slot.failures++
            slot.retryAtMs = System.currentTimeMillis() + when (slot.failures) { 1 -> 60_000L; 2 -> 120_000L; else -> 300_000L }
        } else {
            slot.failures = 0
            slot.retryAtMs = 0L
        }
        slot.results = results
        refreshPoiPins()
        val summary = servicesSummary()
        when {
            note != null -> showMessage(note)
            !announce -> {}
            summary.isEmpty() -> showMessage(getString(R.string.main_no_services, activeServices().joinToString(", ") { getString(it.labelRes) }, where))
            else -> showMessage(getString(R.string.main_services_found, summary, where))
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
        if (!driving && !navUiActive) {
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
        rerouteIfPossible(getString(R.string.main_mode_selected, getString(mode.labelRes)))
        // Reach and detour distances depend on the travel mode.
        if (TripState.plan == null && touristOn) refreshTourist()
        // With a route the new plan brings its own search (drawPlan).
        if (TripState.plan == null) queryPois(force = true)
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
        rerouteIfPossible(getString(R.string.main_pref_selected, getString(preference.labelRes)))
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
            rerouteIfPossible(getString(if (avoidTolls) R.string.main_no_toll else R.string.main_tolls_allowed))
        }
    }

    /** The searches for the urgency mode chosen (0.6 s after the last tap). */
    private val urgencyApply = Runnable {
        urgencyApplyPending = false
        touristModeChanged()
        // 1.0.9: Emergency adds the emergency places on its own (your
        // category stays); Weekend / Work change the detour allowed.
        refreshServicesButton()
        val start = resolveStartPoint()
        val end = TripState.destination
        if (start != null && end != null && currentMode == ModeUi.DRIVING && currentPreference == PreferenceUi.RECOMMENDED) {
            showMessage(getString(R.string.main_finding_route))
            requestBestRoute(start, end) // the new plan searches its places
        } else {
            queryPois(force = true)
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
                when (urgencyMode) {
                    UrgencyMode.WEEKEND -> getString(if (sightsSwitchOn()) R.string.main_weekend_sights_on else R.string.main_weekend_sights_off)
                    UrgencyMode.WORK -> getString(R.string.main_work_mode)
                    UrgencyMode.EMERGENCY -> getString(R.string.main_emergency_mode)
                }
            )
            saveToggleState()
            // 1.4.0: tapping through the modes searches only for the one you stop on.
            urgencyApplyPending = true
            urgencyButton.removeCallbacks(urgencyApply)
            urgencyButton.postDelayed(urgencyApply, 600L)
        }
        // Hold: list of well-known places (Weekend).
        urgencyButton.setOnLongClickListener {
            when {
                touristOn && touristResults.isNotEmpty() -> showTouristList()
                touristOn -> showMessage(getString(R.string.main_no_sights_found_yet))
                else -> showMessage(getString(R.string.main_weekend_shows_sights))
            }
            true
        }
    }

    /** Quick access to the Route page of Settings. */
    private fun setupRouteOptionsButton() {
        findViewById<ImageButton>(R.id.route_options_button).setOnClickListener {
            // 1.9.0: Settings hold your key and places: behind the lock (when on).
            com.backpack.app.security.AppLock.unlock(this) { SettingsPopup.show(this, settingsHost, SettingsPopup.Page.ROUTE) }
        }
        refreshRouteOptionsButton()
    }

    private val settingsHost = object : SettingsPopup.Host {
        override val travelMode get() = currentMode.mode
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
            NavigationEngine.languageChanged()
            // A new reach time / detour applies at once; kinds (Settings > Sights)
            // re-filter what is on the phone, or switch sights on / off.
            val wasOn = touristWasOn
            sightsChanged()
            if (wasOn && touristOn) refreshTourist(force = true)
            queryPois(force = true)
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
        override fun onShowTrips() = com.backpack.app.security.AppLock.unlock(this@MainActivity) { showTrips() }
        override fun onKeyGuide() = showKeyGuide()
        override fun onExitApp() {
            com.backpack.app.nav.NavigationService.exitApp(this@MainActivity)
            finishAndRemoveTask()
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
            showMessage(getString(R.string.main_label_finding_route, label))
            requestBestRoute(start, end)
        } else {
            showMessage(label)
        }
    }

    // ---------- Speed limit (alarm rules unchanged) ----------

    /** Green 5+ under, yellow within 5 under, red up to 5 over, blinking
     *  red + high alarm beyond; blue + low alarm when 15+ under on a 100+
     *  road; red + low alarm when stopped on a 100+ road. */
    /** 1.1.0: walking / cycling while navigating: your speed only (no
     *  limit sign, no alarms; those stay for driving). */
    private fun updateSpeedOnly(location: Location) {
        speedWidget.visibility = View.VISIBLE
        val shown = if (useMetric) (location.speed * 3.6).toInt() else (location.speed * 2.23694).toInt()
        speedSignText.text = if (location.hasSpeed()) shown.toString() else "--"
        speedUnitText.text = if (useMetric) "km/h" else "mph"
        speedLimitSign.visibility = View.GONE
        stopBlinking()
        findViewById<com.backpack.app.ui.SpeedGaugeView>(R.id.speed_gauge)?.show(location.speed * 3.6, null)
    }

    /** 1.3.0: the alarm itself runs in [com.backpack.app.speed.SpeedGuard] (also
     *  with the screen off); the screen draws its verdict. */
    private val speedListener = com.backpack.app.speed.SpeedGuard.Listener { location, limit, state, _ ->
        if (isDestroyed || currentMode != ModeUi.DRIVING) return@Listener
        if (state.beep != com.backpack.app.core.SpeedAlarm.Beep.NONE) wakeDisplay()
        drawSpeed(location, limit, state)
    }

    private fun drawSpeed(location: Location, limit: Int?, state: com.backpack.app.core.SpeedAlarm.State) {
        // Hidden only while the keyboard is open (search bar in its place).
        speedWidget.visibility = View.VISIBLE
        // Circle = your speed; sign on top = the road's limit (OSM, km/h).
        val speedKmh = (location.speed * 3.6).toInt()
        val shown = if (useMetric) speedKmh else (location.speed * 2.23694).toInt()
        speedSignText.text = if (location.hasSpeed()) shown.toString() else "--"
        speedUnitText.text = if (useMetric) "km/h" else "mph"
        if (limit == null) speedLimitSign.visibility = View.GONE
        else {
            speedLimitSign.visibility = View.VISIBLE
            speedLimitSign.text = (if (useMetric) limit else Math.round(limit * 0.621371).toInt()).toString()
        }
        // 1.4.0: the ring is a gauge (speed against the limit). 1.7.2: red / blue
        // only while an alarm sound plays ([speedSoundListener]); stopped on a
        // fast road the plain ring flashes red with each buzz.
        findViewById<com.backpack.app.ui.SpeedGaugeView>(R.id.speed_gauge)?.show(location.speed * 3.6,
            if (state.ring == SpeedRing.STOPPED_RED) null else limit)
        isBlinking = state.beep != com.backpack.app.core.SpeedAlarm.Beep.NONE
    }

    /** 1.7.2 (Alex): the ring flashes in step with each beep / buzz, only while it sounds. */
    private val speedSoundListener = com.backpack.app.speed.SpeedGuard.SoundListener { color, ms ->
        if (isDestroyed) return@SoundListener
        val gauge = findViewById<com.backpack.app.ui.SpeedGaugeView>(R.id.speed_gauge) ?: return@SoundListener
        gauge.flashColor = color
        handler.removeCallbacks(endSpeedFlash)
        handler.postDelayed(endSpeedFlash, ms)
    }

    private val endSpeedFlash = Runnable { findViewById<com.backpack.app.ui.SpeedGaugeView>(R.id.speed_gauge)?.flashColor = null }

    private fun stopBlinking() {
        isBlinking = false
        handler.removeCallbacks(endSpeedFlash)
        endSpeedFlash.run()
    }

    // ---------- Map taps ----------

    private fun handleDestinationTap(point: LatLon) {
        stopPlanFollow()
        if (NavigationEngine.isActive) {
            showMessage(getString(R.string.main_stop_nav_first_place))
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
            showError(getString(R.string.main_no_fix_longpress))
            return
        }
        clearRouteVisuals()
        TripState.destination = point
        refreshPins()
        showMessage(getString(R.string.main_finding_route))
        requestBestRoute(start, point)
    }

    private fun handleStartOverride(point: LatLon) {
        if (NavigationEngine.isActive) {
            showMessage(getString(R.string.main_stop_nav_first))
            return
        }
        clearRoute()
        TripState.customStart = point
        refreshPins()
        showMessage(getString(R.string.main_custom_start))
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
        svcSlot.finder.cancelPrefetch()
        prefetchedRouteKey = null
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
        queryPois() // no route: places around you / the map centre again
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.GONE
        refreshResumeButton()
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
        tollRatePerKm = tollRatePerKm,
        // 1.4.0: in Emergency only the fastest counts.
        extraTimeFraction = if (urgencyMode == UrgencyMode.EMERGENCY) 0.0 else Prefs.balanceExtra(this) / 100.0
    )

    /** [autoStart]: navigation starts when this plan arrives (Resume); a newer request drops it. */
    private fun requestBestRoute(from: LatLon, to: LatLon, stops: List<LatLon>? = null, onFailure: (() -> Unit)? = null, autoStart: Boolean = false) {
        val navigating = NavigationEngine.isActive
        // Mid-trip changes (preference, urgency) start from where you are now.
        val request = buildRequest(if (navigating) currentPosition() ?: from else from, to, stops)
        planner.plan(request) { plan, error ->
            if (plan == null) {
                onFailure?.invoke()
                showError(error ?: getString(R.string.main_route_failed))
                // 1.5.0: no key yet: the guide to get one (then the route is asked again).
                if (RoutePlanner.isNoKey(error)) showKeyGuide()
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
            else if (autoStart && !NavigationEngine.isActive) { refreshResumeButton(); startNavigation() }
        }
    }

    private var speedLimitPlan: RoutePlan? = null

    private fun drawPlan(plan: RoutePlan, selectedIndex: Int, fit: Boolean, note: String?, tourist: Boolean = true) {
        val results = plan.routes
        if (results.isEmpty() || results[0].points.isEmpty()) {
            showError(getString(R.string.main_no_geometry))
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
            refreshResumeButton()
            findViewById<LinearLayout>(R.id.route_options_row).apply { removeAllViews(); visibility = View.GONE }
            placeLayout()
            refreshTourist(force = true) // sights of the visible area again
            showMessage(getString(R.string.main_already_there))
            return
        }
        TripState.selectedIndex = sel
        drawRouteLines(results, sel)
        val primary = results[sel]

        setRouteInfoText(primary)
        note?.let { showMessage(it) }
        routeInfo.visibility = View.VISIBLE
        placeLayout()
        populateRouteOptionsRow(results, sel)
        findViewById<ImageButton>(R.id.nav_go_button).visibility = View.VISIBLE
        refreshResumeButton()

        if (fit) fitCamera(results)
        if (tourist) refreshTourist()
        queryPois() // places along this route (new key = new search)
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
        return getString(R.string.main_dist) + if (useMetric) " %.2f km".format(km) else " %.2f mi".format(km * 0.621371)
    }

    private fun etaLabel(seconds: Double): String {
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.SECOND, seconds.toInt())
        return getString(R.string.main_eta) + " %02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** Shows the whole route (and alternatives) above the bottom controls. */
    /**
     * Every proposed route in view, flat and north up (1.0.7), filling 90 %
     * of the free map area (1.0.8).
     */
    private fun fitCamera(results: List<RouteResult>) {
        val m = map ?: return
        if (navUiActive) return // the navigation view keeps its own camera
        stopPlanFollow()
        flattenOnIdle = false
        val all = results.flatMap { it.points }
        if (all.size < 2) return
        try {
            val bounds = LatLngBounds.Builder().includes(all.map { LatLng(it.lat, it.lon) }).build()
            // 1.0.8: the free map area = screen minus the side columns, the
            // top band and the bottom controls (Go/Clear + messages); the
            // routes fill 90 % of it in the direction they reach first
            // (5 % margin each side), portrait or landscape alike.
            val root = findViewById<View>(android.R.id.content)
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            // Bottom: Go/Clear (76), the route line (~30) and, with several
            // routes, the stacked chips (~30 each) -- estimated, as they are
            // only being laid out at this moment.
            val chips = if (results.size >= 2) minOf(results.size, 4) * 30 + 6 else 0
            val bottomUi = dp(MESSAGES_BOTTOM_DP + 30 + chips)
            val left = dp(COLUMN_ZONE_DP); val right = dp(COLUMN_ZONE_DP)
            val top = if (currentMode == ModeUi.DRIVING) dp(100) else dp(56)
            val freeW = (w - left - right).coerceAtLeast(dp(100))
            val freeH = (h - top - bottomUi).coerceAtLeast(dp(100))
            val mx = (freeW * 0.05).toInt(); val my = (freeH * 0.05).toInt()
            val pad = intArrayOf(left + mx, top + my, right + mx, bottomUi + my)
            val cam = m.getCameraForLatLngBounds(bounds, pad, 0.0, 0.0) ?: return
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(cam.target).zoom(cam.zoom).bearing(0.0).tilt(0.0)
                    .padding(0.0, 0.0, 0.0, 0.0).build()
            ), 800)
        } catch (e: Exception) { }
    }

    /** Planning moves: flat, north up (3D is for navigation only). */
    private fun flyFlat(target: LatLng, zoom: Double, keepFollow: Boolean = false) {
        val m = map ?: return
        if (!keepFollow) stopPlanFollow()
        flattenOnIdle = false
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target).zoom(zoom).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
        ))
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
                " · " + getString(R.string.main_chip_unpaved, result.unpavedFraction * 100)
            } else ""
            // Letter badge (same as on the map line) + facts; only the selected
            // chip gets the red frame, no chip turns red.
            val sel = index == selected
            val chip = TextView(this)
            chip.text = "${km}km · $eta$costPart$unpavedPart"
            chip.textSize = 13.2f // 1.0.8: 10 % bigger
            val d = resources.displayMetrics.density
            chip.setPadding((6.6f * d).toInt(), (3.3f * d).toInt(), (11f * d).toInt(), (3.3f * d).toInt())
            chip.gravity = android.view.Gravity.CENTER_VERTICAL
            chip.compoundDrawablePadding = dp(6)
            chip.setTextColor(ContextCompat.getColor(this, R.color.route_chip_text))
            chip.setTypeface(chip.typeface, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (index < 4) {
                val badge = android.graphics.drawable.BitmapDrawable(resources, LetterBadge.bitmap(this, RouteLabels.letter(index), sel, 18.7f))
                chip.setCompoundDrawablesWithIntrinsicBounds(badge, null, null, null)
            }
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.route_chip_bg))
                if (sel) setStroke(Math.max(1, dp(3) / 2), activeColor) else setStroke(Math.max(1, dp(1) / 2), ContextCompat.getColor(this@MainActivity, R.color.rg_control_edge))
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            chipSpacing(chip)
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
            drawPlan(single, 0, fit = false, note = getString(R.string.main_route_switched), tourist = false)
            return
        }
        // A manual pick is final for this plan: the Weekend sights choice won't override it.
        TripState.scenicCheckedPlan = plan
        drawPlan(plan, index, fit = false, note = getString(R.string.main_manual_route))
    }

    // ---------- Find alternative route (during navigation) ----------

    private var altChoiceOpen = false
    /** Route being followed when the choice opened (to restore it even if
     *  navigation stopped meanwhile). */
    private var altOriginalRoute: RouteResult? = null
    private var altRequests: Map<Int, RouteRequest> = emptyMap()
    /** Alternatives under review after "find alternative route" (navigation
     *  stopped) and the request of each, used if that route is started. */
    private var altReviewPlan: RoutePlan?
        get() = TripState.altReviewPlan
        set(v) { TripState.altReviewPlan = v }
    private var altReviewRequests: Map<Int, RouteRequest>
        get() = TripState.altReviewRequests
        set(v) { TripState.altReviewRequests = v }
    private val altPlannerA by lazy { RoutePlanner(this).apply { countsForTrial = false } }
    private val altPlannerB by lazy { RoutePlanner(this).apply { countsForTrial = false } }

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
        val from = currentPosition() ?: run { showError(getString(R.string.main_no_gps_position)); return }
        val stops = NavigationEngine.remainingStops() ?: TripState.waypoints.toList()
        val reqA = buildRequest(from, end, stops)
        val otherPref = if (currentPreference == PreferenceUi.FASTEST) RoutePreference.SHORTEST else RoutePreference.FASTEST
        val reqB = reqA.copy(preference = otherPref)
        setIconActive(findViewById(R.id.alt_route_button), true)
        showMessage(getString(R.string.main_looking_other_routes))
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
                showMessage(getString(R.string.main_route_changed_meanwhile))
                return
            }
            // A = your route from here, routed again (real turn steps, so it
            // can be started); the stitched rest only if that failed.
            val routes = mutableListOf(resA?.routes?.firstOrNull() ?: left)
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
                showMessage(getString(R.string.main_no_other_route))
                return
            }
            // 1.0.5: navigation stops; the routes stay in memory for review
            // (A = the one you were on). Touch one to see its figures, Go to
            // start it -- nothing is recalculated meanwhile.
            val plan = RoutePlan(routes.take(4), 0, null)
            reqs[0] = reqA
            setIconActive(findViewById(R.id.alt_route_button), false)
            NavigationEngine.stop()
            TripState.customStart = null       // from where you are now
            TripState.waypoints.clear()        // only the stops still ahead
            TripState.waypoints.addAll(stops)
            altReviewPlan = plan
            altReviewRequests = reqs
            TripState.plan = plan
            TripState.selectedIndex = 0
            TripState.committed = TripState.copy()
            refreshPins()
            drawPlan(plan, 0, fit = true, note = getString(R.string.main_nav_paused_compare), tourist = false)
        }
        altPlannerA.plan(reqA) { plan, _ -> resA = plan; finish() }
        altPlannerB.plan(reqB) { plan, _ -> resB = plan; finish() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
