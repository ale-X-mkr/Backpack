package com.backpack.app

/**
 * Settings keys and defaults. Values are SharedPreferences ([PREFS_NAME]);
 * the keys of earlier versions are unchanged so saved values carry over.
 * Edited in the Settings popup (ui/SettingsPopup).
 */
object Settings {
    const val PREFS_NAME = "backpack_settings"
    const val KEY_UNIT_METRIC = "unit_metric"
    const val KEY_VALUE_WEEKEND = "value_weekend"
    const val KEY_VALUE_WORK = "value_work"
    const val KEY_VALUE_EMERGENCY = "value_emergency"
    const val KEY_TOLL_RATE = "toll_rate"
    const val KEY_DISPLAY_MODE = "display_mode"
    const val KEY_DEFAULT_POI = "default_poi_category"
    const val KEY_MAP_STYLE = "map_style"
    const val KEY_CUSTOM_STYLE = "custom_style_url"
    const val KEY_ORS_URL = "ors_url"
    const val KEY_ORS_KEY = "ors_key"
    const val KEY_OVERPASS_URL = "overpass_url"
    const val KEY_SIGHTS_MAX_PCT = "weekend_sights_max_pct"
    /** Navigation view tilt: -1 = automatic (45° driving, 25° otherwise), else degrees 0..60. */
    const val KEY_NAV_TILT = "nav_tilt"
    /** Navigation zoom offset in zoom levels: +1 closer, 0 normal, -1 farther. */
    const val KEY_NAV_ZOOM = "nav_zoom"
    const val KEY_VOICE = "voice_on"
    const val KEY_ALARM_SOUND = "speed_alarm_sound"

    const val DEFAULT_SIGHTS_MAX_PCT = 20
    const val DISPLAY_MODE_DAY = "day"
    const val DISPLAY_MODE_NIGHT = "night"
    /** Night map from sunset to sunrise at your position. */
    const val DISPLAY_MODE_AUTO = "auto"
    const val DISPLAY_MODE_SYSTEM = "system"

    const val DEFAULT_VALUE_WEEKEND = 10.0
    const val DEFAULT_VALUE_WORK = 20.0
    const val DEFAULT_VALUE_EMERGENCY = 90.0
    const val DEFAULT_TOLL_RATE = 0.12
}
