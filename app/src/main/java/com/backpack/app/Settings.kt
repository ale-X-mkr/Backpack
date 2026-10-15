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
    /** 1.4.0: night map style (dark, fiord, vt_eclipse). */
    const val KEY_NIGHT_STYLE = "night_style"
    const val KEY_CUSTOM_STYLE = "custom_style_url"
    const val KEY_ORS_URL = "ors_url"
    const val KEY_ORS_KEY = "ors_key"
    const val KEY_OVERPASS_URL = "overpass_url"
    /** 1.2.1: a faster Overpass server with your own key (provider id + key). */
    const val KEY_OVERPASS_PROVIDER = "overpass_provider"
    const val KEY_OVERPASS_KEY = "overpass_key"
    const val KEY_SIGHTS_MAX_PCT = "weekend_sights_max_pct"
    /** 1.0.7: Sights category reach, minutes (30 / 60 / 120). */
    const val KEY_SIGHTS_REACH_MIN = "sights_reach_minutes"
    /** 1.0.9: longest there-and-back detour (minutes) for places shown along a route. */
    const val KEY_DETOUR_WEEKEND_MIN = "detour_weekend_minutes"
    const val KEY_DETOUR_WORK_MIN = "detour_work_minutes"
    const val DEFAULT_DETOUR_WEEKEND_MIN = 20
    const val DEFAULT_DETOUR_WORK_MIN = 5
    /** Emergency places: fixed. */
    const val EMERGENCY_DETOUR_MIN = 15
    /** Sights: the camera next to Weekend (default on); KEY_SIGHTS_WORK unused since 1.2.0; kinds; voice heads-up. */
    const val KEY_SIGHTS_WEEKEND = "sights_weekend_on"
    const val KEY_SIGHTS_WORK = "sights_work_on"
    /** 1.4.0: "sight_groups2" (Prehistory added, on by default for everyone). */
    const val KEY_SIGHT_GROUPS = "sight_groups2"
    /** 1.4.0: priority order of the kinds ("PREHISTORY,NATURE,..."). */
    const val KEY_SIGHT_ORDER = "sight_order"
    const val KEY_SIGHT_HEADSUP = "sight_headsup"
    /** 1.2.0: heads-up this many minutes before the sight (route's average speed). */
    const val KEY_SIGHT_HEADSUP_MIN = "sight_headsup_minutes"
    const val DEFAULT_SIGHT_HEADSUP_MIN = 3
    /** 1.7.1: seconds a sight card stays on screen (10 / 15 / 20). */
    const val KEY_SIGHT_CARD_S = "sight_card_seconds"
    const val DEFAULT_SIGHT_CARD_S = 15
    val SIGHT_CARD_CHOICES = listOf(10, 15, 20)
    /** 1.7.1: summaries (start, arrival, 3+ ahead) as a card with Read aloud (default on). */
    const val KEY_SIGHT_SUMMARY_READ = "sight_summary_read"
    /** 1.1.1: last destinations searched (JSON, phone only) and the voice-input notice. */
    const val KEY_RECENT_SEARCHES = "recent_searches"
    /** 1.5.1: new text (FUTO Voice Input, no Sayboard): shown once again. */
    const val KEY_VOICE_INFO_SHOWN = "voice_info_shown2"
    /** 1.0.7: Sights around "me" (true) or the map centre (false). Unused since 1.2.0 (always the map centre). */
    const val KEY_SIGHTS_AROUND_ME = "sights_around_me"
    /** Navigation view tilt: -1 = automatic (45° driving, 25° otherwise), else degrees 0..60. */
    const val KEY_NAV_TILT = "nav_tilt"
    /** Navigation zoom offset in zoom levels: +1 closer, 0 normal, -1 farther. */
    const val KEY_NAV_ZOOM = "nav_zoom"
    const val KEY_VOICE = "voice_on"
    /** 1.1.0: spoken instructions "full" (ORS sentences) or "short" (maneuver only). */
    const val KEY_VOICE_SHORT = "voice_short"
    /** 1.3.0: voice language: en, es (Latin America), fr, it, de. */
    const val KEY_VOICE_LANG = "voice_language"
    /** 1.3.0: record the track while navigating (on this phone only). */
    const val KEY_RECORD_TRIPS = "record_trips"
    const val KEY_ALARM_SOUND = "speed_alarm_sound"
    /** 1.4.0: fixed speed cameras (sound only), off by default. */
    const val KEY_CAMERAS = "speed_cameras"
    /** 1.4.1: sound mode while navigating (FULL / NAV / ALERTS). */
    const val KEY_SOUND_MODE = "sound_mode_nav"
    /** 1.4.0 Balanced: % more time accepted for a clearly shorter route (0..25). */
    const val KEY_BALANCE_EXTRA = "balance_extra_pct"
    const val DEFAULT_BALANCE_EXTRA = 5

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
