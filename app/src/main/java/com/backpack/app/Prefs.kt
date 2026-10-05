package com.backpack.app

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

    const val DEFAULT_ORS_URL = "https://api.openrouteservice.org"

    /** OpenFreeMap: no key, no registration, no cookies. */
    const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"
    const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

    private fun settings(c: Context) = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
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
        if (isNight(c)) return STYLE_DARK
        return when (settings(c).getString(Settings.KEY_MAP_STYLE, "liberty")) {
            "bright" -> STYLE_BRIGHT
            "positron" -> STYLE_POSITRON
            else -> STYLE_LIBERTY
        }
    }

    fun orsBaseUrl(c: Context): String =
        settings(c).getString(Settings.KEY_ORS_URL, "")?.trim()?.trimEnd('/')
            ?.ifEmpty { null } ?: DEFAULT_ORS_URL

    /** Own key from Settings first, then the two built-in keys. The
     *  built-in keys are only ever sent to openrouteservice.org itself,
     *  never to a custom server set in Settings. */
    fun orsKeys(c: Context): List<String> {
        val own = settings(c).getString(Settings.KEY_ORS_KEY, "")?.trim().orEmpty()
        val builtIn = if (orsBaseUrl(c) == DEFAULT_ORS_URL) listOf(BuildConfig.ORS_KEY_1, BuildConfig.ORS_KEY_2) else emptyList()
        return (listOf(own) + builtIn).filter { it.isNotEmpty() }.distinct()
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
        return if (t < 0) (if (driving) 45.0 else 25.0) else t.coerceIn(0, 60).toDouble()
    }

    /** Zoom levels added to the navigation zoom (+1 closer, -1 farther). */
    fun navZoomOffset(c: Context): Double = settings(c).getInt(Settings.KEY_NAV_ZOOM, 0).coerceIn(-2, 2).toDouble()

    fun voiceOn(c: Context) = settings(c).getBoolean(Settings.KEY_VOICE, true)

    fun alarmSound(c: Context) = settings(c).getBoolean(Settings.KEY_ALARM_SOUND, true)
}
