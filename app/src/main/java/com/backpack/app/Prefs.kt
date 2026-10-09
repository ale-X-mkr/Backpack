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
    /** 1.4.0: more free styles (no key): OpenFreeMap Fiord (night), VersaTiles (rate-limited, no guarantee). */
    const val STYLE_FIORD = "https://tiles.openfreemap.org/styles/fiord"
    const val STYLE_VT_COLORFUL = "https://tiles.versatiles.org/assets/styles/colorful/style.json"
    const val STYLE_VT_GRAYBEARD = "https://tiles.versatiles.org/assets/styles/graybeard/style.json"
    const val STYLE_VT_ECLIPSE = "https://tiles.versatiles.org/assets/styles/eclipse/style.json"

    /** Day styles: id -> (label, URL). */
    val DAY_STYLES = listOf(
        "liberty" to ("Liberty" to STYLE_LIBERTY), "bright" to ("Bright" to STYLE_BRIGHT),
        "positron" to ("Light grey" to STYLE_POSITRON), "vt_colorful" to ("Colorful" to STYLE_VT_COLORFUL),
        "vt_graybeard" to ("Graybeard" to STYLE_VT_GRAYBEARD)
    )
    val NIGHT_STYLES = listOf(
        "dark" to ("Dark" to STYLE_DARK), "fiord" to ("Fiord" to STYLE_FIORD), "vt_eclipse" to ("Eclipse" to STYLE_VT_ECLIPSE)
    )

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
        if (isNight(c)) {
            val id = settings(c).getString(Settings.KEY_NIGHT_STYLE, "dark")
            return NIGHT_STYLES.firstOrNull { it.first == id }?.second?.second ?: STYLE_DARK
        }
        val id = settings(c).getString(Settings.KEY_MAP_STYLE, "liberty")
        return DAY_STYLES.firstOrNull { it.first == id }?.second?.second ?: STYLE_LIBERTY
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
}
