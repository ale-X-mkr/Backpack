package com.backpack.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.backpack.app.core.RouteOptions

/** One place for settings that several classes read. Keys stay those of
 *  SettingsActivity so existing saved values keep working. */
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

    private fun settings(c: Context) = c.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private fun toggles(c: Context) = c.getSharedPreferences(TOGGLES, Context.MODE_PRIVATE)

    /** Day / Night / Auto now drives the whole app, map included. */
    fun applyNightMode(c: Context) {
        val mode = when (settings(c).getString(SettingsActivity.KEY_DISPLAY_MODE, SettingsActivity.DISPLAY_MODE_AUTO)) {
            SettingsActivity.DISPLAY_MODE_DAY -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsActivity.DISPLAY_MODE_NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun isNight(c: Context): Boolean =
        (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun mapStyleUrl(c: Context): String {
        val custom = settings(c).getString(SettingsActivity.KEY_CUSTOM_STYLE, "")?.trim().orEmpty()
        if (custom.isNotEmpty() && !isNight(c)) return custom
        if (isNight(c)) return STYLE_DARK
        return when (settings(c).getString(SettingsActivity.KEY_MAP_STYLE, "liberty")) {
            "bright" -> STYLE_BRIGHT
            "positron" -> STYLE_POSITRON
            else -> STYLE_LIBERTY
        }
    }

    fun orsBaseUrl(c: Context): String =
        settings(c).getString(SettingsActivity.KEY_ORS_URL, "")?.trim()?.trimEnd('/')
            ?.ifEmpty { null } ?: DEFAULT_ORS_URL

    /** Own key from Settings first, then the two built-in keys. */
    fun orsKeys(c: Context): List<String> {
        val own = settings(c).getString(SettingsActivity.KEY_ORS_KEY, "")?.trim().orEmpty()
        return listOf(own, BuildConfig.ORS_KEY_1, BuildConfig.ORS_KEY_2).filter { it.isNotEmpty() }.distinct()
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

    fun useMetric(c: Context) = settings(c).getBoolean(SettingsActivity.KEY_UNIT_METRIC, true)
}
