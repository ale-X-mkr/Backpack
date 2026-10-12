package com.backpack.app

import com.backpack.app.core.Txt
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteOptions
import com.backpack.app.core.SunPosition
import com.backpack.app.location.LocationHub

/** One place for settings that several classes read. Keys stay those of
 *  Settings so existing saved values keep working. */
object Prefs {
    private const val TOGGLES = "backpack_toggle_state"
    private const val KEY_ROUTE_OPTIONS = "route_options"
    private const val KEY_CAM_LAT = "cam_lat"
    private const val KEY_CAM_LON = "cam_lon"
    private const val KEY_CAM_ZOOM = "cam_zoom"

    /** 1.5.0: openrouteservice moved to api.heigit.org (the old
     *  api.openrouteservice.org gets 10 % of the quota since 27 Aug 2026 and
     *  shuts down 2-6 Nov 2026). Directions / isochrones: this + /v2/... */
    const val DEFAULT_ORS_URL = "https://api.heigit.org/openrouteservice"
    /** The old address, still in some saved settings: read as the default. */
    const val OLD_ORS_URL = "https://api.openrouteservice.org"
    /** POIs moved to their own service. */
    const val ORS_POIS_URL = "https://api.heigit.org/openpoiservice/v0/pois"
    /** Geocoding (Pelias). */
    const val ORS_GEOCODE_URL = "https://api.heigit.org/pelias/v1"
    /** Where a free key is made (HeiGIT account, openrouteservice's home). */
    const val ORS_SIGNUP_URL = "https://account.heigit.org"

    /** OpenFreeMap: no key, no registration, no cookies. */
    const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"
    const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    /** 1.4.0: more free styles (no key): OpenFreeMap Fiord (night), VersaTiles (rate-limited, no guarantee). */
    const val STYLE_FIORD = "https://tiles.openfreemap.org/styles/fiord"
    const val STYLE_VT_COLORFUL = "https://tiles.versatiles.org/assets/styles/colorful/style.json"
    const val STYLE_VT_GRAYBEARD = "https://tiles.versatiles.org/assets/styles/graybeard/style.json"
    const val STYLE_VT_ECLIPSE = "https://tiles.versatiles.org/assets/styles/eclipse/style.json"

    /** Day styles: id -> (label, URL). */
    /** 1.6.0: descriptive names in the screen language; proper names (Liberty, Graybeard, Fiord, Eclipse) stay. */
    val DAY_STYLES: List<Pair<String, Pair<String, String>>> get() = listOf(
        "liberty" to ("Liberty" to STYLE_LIBERTY), "bright" to (Txt.s("other_style_bright", "Bright") to STYLE_BRIGHT),
        "positron" to (Txt.s("other_style_light_grey", "Light grey") to STYLE_POSITRON), "vt_colorful" to (Txt.s("other_style_colorful", "Colorful") to STYLE_VT_COLORFUL),
        "vt_graybeard" to ("Graybeard" to STYLE_VT_GRAYBEARD)
    )
    val NIGHT_STYLES: List<Pair<String, Pair<String, String>>> get() = listOf(
        "dark" to (Txt.s("other_style_dark", "Dark") to STYLE_DARK), "fiord" to ("Fiord" to STYLE_FIORD), "vt_eclipse" to ("Eclipse" to STYLE_VT_ECLIPSE)
    )

    fun settings(c: Context) = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
    private fun toggles(c: Context) = c.getSharedPreferences(TOGGLES, Context.MODE_PRIVATE)

    /**
     * Day / Night / Auto / System drives the whole app, map included.
     * Auto = night map from sunset to sunrise where you are (sun position
     * computed on the phone). System = follow the phone's dark theme.
     * Changing the mode makes Android rebuild the screens (route is kept).
     */
    fun applyNightMode(c: Context) {
        val mode = when (settings(c).getString(Settings.KEY_DISPLAY_MODE, Settings.DISPLAY_MODE_AUTO)) {
            Settings.DISPLAY_MODE_DAY -> AppCompatDelegate.MODE_NIGHT_NO
            Settings.DISPLAY_MODE_NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
            Settings.DISPLAY_MODE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> {
                val pos = LocationHub.lastGoodFix?.let { LatLon(it.latitude, it.longitude) }
                    ?: camera(c)?.let { LatLon(it.first, it.second) }
                if (pos == null) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else if (SunPosition.isNight(pos.lat, pos.lon, System.currentTimeMillis())) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun isNight(c: Context): Boolean =
        (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun mapStyleUrl(c: Context): String {
        val custom = settings(c).getString(Settings.KEY_CUSTOM_STYLE, "")?.trim().orEmpty()
        if (custom.isNotEmpty() && !isNight(c)) return custom
        if (isNight(c)) {
            val id = settings(c).getString(Settings.KEY_NIGHT_STYLE, "dark")
            return NIGHT_STYLES.firstOrNull { it.first == id }?.second?.second ?: STYLE_DARK
        }
        val id = settings(c).getString(Settings.KEY_MAP_STYLE, "liberty")
        return DAY_STYLES.firstOrNull { it.first == id }?.second?.second ?: STYLE_LIBERTY
    }

    fun orsBaseUrl(c: Context): String {
        val v = settings(c).getString(Settings.KEY_ORS_URL, "")?.trim()?.trimEnd('/').orEmpty()
        // Empty, or the old openrouteservice address typed in by hand: the current one.
        return if (v.isEmpty() || v.equals(OLD_ORS_URL, ignoreCase = true)) DEFAULT_ORS_URL else v
    }

    /** 1.5.0: an own key is set (Settings ▸ Advanced or the first-start guide). */
    fun hasOwnOrsKey(c: Context): Boolean = !settings(c).getString(Settings.KEY_ORS_KEY, "").isNullOrBlank()

    /** Own key from Settings first, then the two built-in keys. The
     *  built-in keys are only ever sent to openrouteservice.org itself,
     *  never to a custom server set in Settings. */
    fun orsKeys(c: Context): List<String> {
        val own = settings(c).getString(Settings.KEY_ORS_KEY, "")?.trim().orEmpty()
        val builtIn = if (orsBaseUrl(c) == DEFAULT_ORS_URL) listOf(BuildConfig.ORS_KEY_1, BuildConfig.ORS_KEY_2) else emptyList()
        return (listOf(own) + builtIn).filter { it.isNotEmpty() }.distinct()
    }

    // ---------- 1.7.0: shared starter key (share build), a trial for new users ----------

    private const val KEY_TRIAL = "starter_trial"

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    private fun trialState(c: Context) = com.backpack.app.core.StarterTrial.decode(settings(c).getString(KEY_TRIAL, null))

    /** True when routes would go through the shared starter key (no own / built-in key). */
    fun usesStarterKey(c: Context): Boolean =
        BuildConfig.ORS_SHARED.isNotEmpty() && orsBaseUrl(c) == DEFAULT_ORS_URL && orsKeys(c).isEmpty()

    fun starterVerdict(c: Context) = com.backpack.app.core.StarterTrial.verdict(trialState(c), today())

    /** One route planned with the starter key. */
    fun noteStarterRoute(c: Context) {
        val s = com.backpack.app.core.StarterTrial.after(trialState(c), today())
        settings(c).edit().putString(KEY_TRIAL, com.backpack.app.core.StarterTrial.encode(s)).apply()
    }

    /** Keys for route requests: own / built-in ones, else the starter key while the trial lasts. */
    fun routingKeys(c: Context): List<String> {
        val keys = orsKeys(c)
        if (keys.isNotEmpty() || !usesStarterKey(c)) return keys
        return if (starterVerdict(c) == com.backpack.app.core.StarterTrial.Verdict.OK) listOf(BuildConfig.ORS_SHARED) else emptyList()
    }

    fun routeOptions(c: Context): RouteOptions = RouteOptions.fromJson(toggles(c).getString(KEY_ROUTE_OPTIONS, null))

    fun saveRouteOptions(c: Context, o: RouteOptions) {
        toggles(c).edit().putString(KEY_ROUTE_OPTIONS, o.toJson()).apply()
    }

    fun saveCamera(c: Context, lat: Double, lon: Double, zoom: Double) {
        toggles(c).edit()
            .putFloat(KEY_CAM_LAT, lat.toFloat())
            .putFloat(KEY_CAM_LON, lon.toFloat())
            .putFloat(KEY_CAM_ZOOM, zoom.toFloat())
            .apply()
    }

    /** Last map view (lat, lon, zoom), or null on first launch. */
    fun camera(c: Context): Triple<Double, Double, Double>? {
        val p = toggles(c)
        if (!p.contains(KEY_CAM_LAT)) return null
        return Triple(
            p.getFloat(KEY_CAM_LAT, 0f).toDouble(),
            p.getFloat(KEY_CAM_LON, 0f).toDouble(),
            p.getFloat(KEY_CAM_ZOOM, 12f).toDouble()
        )
    }

    fun useMetric(c: Context) = settings(c).getBoolean(Settings.KEY_UNIT_METRIC, true)
    fun voiceShort(c: Context) = settings(c).getBoolean(Settings.KEY_VOICE_SHORT, false)

    /** Navigation tilt in degrees for [driving]; Auto = 45° driving, 25° otherwise. */
    fun navTilt(c: Context, driving: Boolean): Double {
        val t = settings(c).getInt(Settings.KEY_NAV_TILT, -1)
        // 1.4.0 Auto = adaptive: 30° when starting, then by speed (navTiltAt).
        return if (t < 0) 30.0 else t.coerceIn(0, 60).toDouble()
    }

    /** 1.4.0: the tilt for a speed; null = a fixed angle is set (no change with speed). */
    fun navTiltAt(c: Context, speedKmh: Double): Double? =
        if (settings(c).getInt(Settings.KEY_NAV_TILT, -1) < 0) com.backpack.app.core.NavTilt.auto(speedKmh) else null

    /** Zoom levels added to the navigation zoom (+1 closer, -1 farther). */
    fun navZoomOffset(c: Context): Double = settings(c).getInt(Settings.KEY_NAV_ZOOM, 0).coerceIn(-2, 2).toDouble()

    fun voiceOn(c: Context) = settings(c).getBoolean(Settings.KEY_VOICE, true)
    /** 1.3.0: voice language; the phone's own when it is one of ours, else English. */
    fun voiceLanguage(c: Context): String = settings(c).getString(Settings.KEY_VOICE_LANG, null)
        ?: com.backpack.app.core.VoiceText.defaultFor(java.util.Locale.getDefault().language)

    fun alarmSound(c: Context) = settings(c).getBoolean(Settings.KEY_ALARM_SOUND, true)
    fun balanceExtra(c: Context): Int = settings(c).getInt(Settings.KEY_BALANCE_EXTRA, Settings.DEFAULT_BALANCE_EXTRA).coerceIn(0, 25)
    /** 1.4.1 sound button: Full (instructions, sight tips, readings), Navigation
     *  (instructions), Alerts only (dangers). Dangers are heard in all three. */
    enum class SoundMode { FULL, NAV, ALERTS }

    /** The mode chosen for navigating (Full until changed). */
    fun navSoundMode(c: Context): SoundMode =
        settings(c).getString(Settings.KEY_SOUND_MODE, null)?.let { v -> SoundMode.values().firstOrNull { it.name == v } } ?: SoundMode.FULL

    fun setNavSoundMode(c: Context, m: SoundMode) = settings(c).edit().putString(Settings.KEY_SOUND_MODE, m.name).apply()

    /** The mode in force: without navigation always Alerts only. */
    fun soundMode(c: Context): SoundMode =
        if (com.backpack.app.nav.NavigationEngine.isActive) navSoundMode(c) else SoundMode.ALERTS

    fun cameras(c: Context) = settings(c).getBoolean(Settings.KEY_CAMERAS, false)
}
