package com.backpack.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.TextView
import com.backpack.app.BuildConfig
import com.backpack.app.Prefs
import com.backpack.app.R
import com.backpack.app.Settings
import com.backpack.app.core.BikeType
import com.backpack.app.core.Hills
import com.backpack.app.core.RouteOptions
import com.backpack.app.core.TravelMode
import com.backpack.app.core.Vehicle
import com.backpack.app.core.WalkType
import com.backpack.app.net.TileCache

/**
 * All settings in one popup, one page per category (tabs at the top).
 * Chips and switches apply at once; text fields are stored as soon as they
 * hold a valid value. The map screen is told what changed when it closes.
 */
object SettingsPopup {

    enum class Page(val labelRes: Int) {
        ROUTE(R.string.set_tab_route), NAVIGATION(R.string.set_tab_navigation), SPEED(R.string.set_tab_speed), SIGHTS(R.string.set_tab_sights), PLACES(R.string.set_tab_places), MAP(R.string.set_tab_map), SERVERS(R.string.set_tab_advanced), CREDITS(R.string.set_tab_credits)
    }

    interface Host {
        val travelMode: TravelMode
        /** The popup opens (e.g. hold the automatic day/night switch meanwhile). */
        fun onSettingsOpened()
        /** Called on close, only when the route options of a mode changed. */
        fun onRouteOptionsChanged(options: RouteOptions)
        /** Called on close: re-read settings (style, night mode, view...). */
        fun onSettingsClosed()
        /** Place caches were emptied. */
        fun onPlacesCleared()
        /** 1.3.0: Exit Backpack (stops everything, closes the map). */
        fun onExitApp()
        /** 1.3.0: the recorded trips. */
        fun onShowTrips()
        /** 1.5.0: the "get your free key" guide. */
        fun onKeyGuide()
    }

    fun show(c: Context, host: Host, start: Page = Page.ROUTE) {
        val prefs = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val originalOptions = Prefs.routeOptions(c)
        var o = originalOptions
        val p = Popup(c, c.getString(R.string.set_title, BuildConfig.VERSION_NAME))

        // Tabs (1.0.5): one row of text tabs, the current one bold with a
        // red underline; scrolls sideways when they don't all fit.
        val d = c.resources.displayMetrics.density
        val tabRow = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val tabScroll = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        val tabViews = HashMap<Page, TextView>()
        var current = start
        fun build(page: Page) {
            current = page
            Page.values().forEach { tab -> tabViews[tab]?.let { p.styleTab(it, tab == page) } }
            tabViews[page]?.let { t -> tabScroll.post { tabScroll.requestChildRectangleOnScreen(t, android.graphics.Rect(0, 0, t.width, t.height), false) } }
            p.clearBody()
            when (page) {
                // Saved at once: a screen rebuild while the popup is open loses nothing.
                Page.ROUTE -> routePage(c, p, host.travelMode, { o }, { o = it; Prefs.saveRouteOptions(c, it) }, prefs)
                Page.NAVIGATION -> navigationPage(c, p, prefs, host)
                Page.SPEED -> speedPage(c, p, prefs)
                Page.SIGHTS -> sightsPage(c, p, prefs)
                Page.PLACES -> placesPage(c, p, prefs, host)
                Page.MAP -> mapPage(c, p, prefs)
                Page.SERVERS -> serversPage(c, p, prefs, host)
                Page.CREDITS -> creditsPage(c, p)
            }
            p.fit()
        }
        for (page in Page.values()) {
            val tab = TextView(c).apply {
                text = c.getString(page.labelRes)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
                setOnClickListener { if (current != page) build(page) }
            }
            tabViews[page] = tab
            tabRow.addView(tab)
        }
        p.header.addView(tabScroll)
        // Thin line under the whole tab row.
        p.header.addView(android.view.View(c).apply {
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, com.backpack.app.R.color.rg_divider))
        }, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, d.toInt())))
        p.header.visibility = android.view.View.VISIBLE
        build(start)

        p.button(c.getString(R.string.set_done), primary = true) { p.dismiss() }
        p.dialog.setOnDismissListener {
            host.onSettingsClosed()
            if (o != originalOptions) host.onRouteOptionsChanged(o)
        }
        host.onSettingsOpened()
        p.show()
    }

    // ---------- Pages ----------

    private fun routePage(
        c: Context, p: Popup, mode: TravelMode, get: () -> RouteOptions, set: (RouteOptions) -> Unit,
        prefs: android.content.SharedPreferences
    ) {
        p.section(when (mode) {
            TravelMode.DRIVING -> c.getString(R.string.set_route_driving)
            TravelMode.CYCLING -> c.getString(R.string.set_route_cycling)
            TravelMode.WALKING -> c.getString(R.string.set_route_walking)
        })
        when (mode) {
            TravelMode.DRIVING -> {
                p.choices(Vehicle.values().toList(), { it.label }, { get().vehicle }) { set(get().copy(vehicle = it)) }
                p.section(c.getString(R.string.set_avoid))
                p.toggles(listOf(
                    Triple(c.getString(R.string.set_motorways), { get().carAvoidMotorways }, { v: Boolean -> set(get().copy(carAvoidMotorways = v)) }),
                    Triple(c.getString(R.string.set_ferries), { get().carAvoidFerries }, { v: Boolean -> set(get().copy(carAvoidFerries = v)) }),
                    Triple(c.getString(R.string.set_fords), { get().carAvoidFords }, { v: Boolean -> set(get().copy(carAvoidFords = v)) })
                ))
                p.section(c.getString(R.string.set_surface))
                p.toggle(c.getString(R.string.set_prefer_paved), { get().carPreferPaved }) { set(get().copy(carPreferPaved = it)) }
                p.text(pct(c, R.string.set_car_note), 11f)
            }
            TravelMode.CYCLING -> {
                p.choices(BikeType.values().toList(), { it.label }, { get().bikeType }) { set(get().copy(bikeType = it)) }
                p.section(c.getString(R.string.set_hills))
                p.choices(Hills.values().toList(), { it.label }, { get().bikeHills }) { set(get().copy(bikeHills = it)) }
                p.section(c.getString(R.string.set_avoid))
                p.toggles(listOf(
                    Triple(c.getString(R.string.set_steps), { get().bikeAvoidSteps }, { v: Boolean -> set(get().copy(bikeAvoidSteps = v)) }),
                    Triple(c.getString(R.string.set_ferries), { get().bikeAvoidFerries }, { v: Boolean -> set(get().copy(bikeAvoidFerries = v)) })
                ))
                p.section(c.getString(R.string.set_surface))
                p.toggle(c.getString(R.string.set_prefer_paved), { get().bikePreferPaved }) { set(get().copy(bikePreferPaved = it)) }
            }
            TravelMode.WALKING -> {
                p.choices(WalkType.values().toList(), { it.label }, { get().walkType }) { set(get().copy(walkType = it)) }
                p.section(c.getString(R.string.set_prefer))
                p.toggles(listOf(
                    Triple(c.getString(R.string.set_green_areas), { get().walkGreen }, { v: Boolean -> set(get().copy(walkGreen = v)) }),
                    Triple(c.getString(R.string.set_quiet_streets), { get().walkQuiet }, { v: Boolean -> set(get().copy(walkQuiet = v)) })
                ))
                p.section(c.getString(R.string.set_avoid))
                p.toggles(listOf(
                    Triple(c.getString(R.string.set_steps), { get().walkAvoidSteps }, { v: Boolean -> set(get().copy(walkAvoidSteps = v)) }),
                    Triple(c.getString(R.string.set_ferries), { get().walkAvoidFerries }, { v: Boolean -> set(get().copy(walkAvoidFerries = v)) })
                ))
            }
        }
        p.bodyButton(c.getString(R.string.set_reset_mode)) {
            val d = RouteOptions()
            val cur = get()
            set(when (mode) {
                TravelMode.DRIVING -> cur.copy(vehicle = d.vehicle, carAvoidMotorways = d.carAvoidMotorways,
                    carAvoidFerries = d.carAvoidFerries, carAvoidFords = d.carAvoidFords, carPreferPaved = d.carPreferPaved)
                TravelMode.CYCLING -> cur.copy(bikeType = d.bikeType, bikeHills = d.bikeHills,
                    bikeAvoidSteps = d.bikeAvoidSteps, bikeAvoidFerries = d.bikeAvoidFerries, bikePreferPaved = d.bikePreferPaved)
                TravelMode.WALKING -> cur.copy(walkType = d.walkType, walkGreen = d.walkGreen, walkQuiet = d.walkQuiet,
                    walkAvoidSteps = d.walkAvoidSteps, walkAvoidFerries = d.walkAvoidFerries)
            })
        }
        // 1.4.0: Balanced accepts a little more time for a clearly shorter route.
        p.section(c.getString(R.string.set_balanced_title))
        val pct = prefs.getInt(Settings.KEY_BALANCE_EXTRA, Settings.DEFAULT_BALANCE_EXTRA).coerceIn(0, 25)
        val label = p.text(if (pct == 0) c.getString(R.string.set_balanced_off) else c.getString(R.string.set_balanced_up_to, pct), 12f)
        p.custom(android.widget.SeekBar(c).apply {
            max = 25
            progress = pct
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    label.text = if (v == 0) c.getString(R.string.set_balanced_off) else c.getString(R.string.set_balanced_up_to, v)
                    if (fromUser) prefs.edit().putInt(Settings.KEY_BALANCE_EXTRA, v).apply()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })
        p.text(pct(c, R.string.set_balanced_help), 11f)
        p.section(c.getString(R.string.set_time_value))
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        floatField(p, prefs, c.getString(R.string.set_weekend), Settings.KEY_VALUE_WEEKEND, Settings.DEFAULT_VALUE_WEEKEND, num)
        floatField(p, prefs, c.getString(R.string.set_work), Settings.KEY_VALUE_WORK, Settings.DEFAULT_VALUE_WORK, num)
        floatField(p, prefs, c.getString(R.string.set_emergency), Settings.KEY_VALUE_EMERGENCY, Settings.DEFAULT_VALUE_EMERGENCY, num)
        p.section(c.getString(R.string.set_toll_estimate))
        floatField(p, prefs, c.getString(R.string.set_toll_rate), Settings.KEY_TOLL_RATE, Settings.DEFAULT_TOLL_RATE, num)
        p.text(c.getString(R.string.set_toll_help), 11f)
    }

    private fun navigationPage(c: Context, p: Popup, prefs: android.content.SharedPreferences, host: Host) {
        p.section(c.getString(R.string.set_3d_view))
        val tilts = listOf(-1 to c.getString(R.string.set_tilt_adaptive), 0 to c.getString(R.string.set_tilt_flat), 30 to "30°", 45 to "45°", 60 to "60°")
        p.choices(tilts, { it.second }, { tilts.firstOrNull { t -> t.first == prefs.getInt(Settings.KEY_NAV_TILT, -1) } ?: tilts[0] }) {
            prefs.edit().putInt(Settings.KEY_NAV_TILT, it.first).apply()
        }
        p.text(c.getString(R.string.set_tilt_help), 11f)
        p.section(c.getString(R.string.set_zoom_title))
        val zooms = listOf(1 to c.getString(R.string.set_zoom_closer), 0 to c.getString(R.string.set_zoom_normal), -1 to c.getString(R.string.set_zoom_farther))
        p.choices(zooms, { it.second }, { zooms.firstOrNull { z -> z.first == prefs.getInt(Settings.KEY_NAV_ZOOM, 0) } ?: zooms[1] }) {
            prefs.edit().putInt(Settings.KEY_NAV_ZOOM, it.first).apply()
        }
        p.text(c.getString(R.string.set_zoom_help), 11f)
        p.section(c.getString(R.string.set_voice))
        p.toggle(c.getString(R.string.set_spoken), { prefs.getBoolean(Settings.KEY_VOICE, true) }) {
            prefs.edit().putBoolean(Settings.KEY_VOICE, it).apply()
        }
        val detail = listOf(false to c.getString(R.string.set_voice_full), true to c.getString(R.string.set_voice_short))
        p.choices(detail, { it.second }, { detail.first { d -> d.first == prefs.getBoolean(Settings.KEY_VOICE_SHORT, false) } }) {
            prefs.edit().putBoolean(Settings.KEY_VOICE_SHORT, it.first).apply()
        }
        p.text(c.getString(R.string.set_voice_help), 11f)
        // 1.6.0: screen language (texts); the voice language below stays separate.
        p.section(c.getString(com.backpack.app.R.string.lang_screen))
        val screenLangs = com.backpack.app.L10n.LANGUAGES
        p.choices(screenLangs, { if (it.first.isEmpty()) c.getString(com.backpack.app.R.string.lang_auto) else it.second },
            { screenLangs.firstOrNull { l -> l.first == com.backpack.app.L10n.current() } ?: screenLangs[0] }) {
            if (it.first != com.backpack.app.L10n.current()) { p.dismiss(); com.backpack.app.L10n.set(it.first) }
        }
        // 1.3.0: voice language (instructions, heads-ups, the guide's readings).
        p.section(c.getString(R.string.set_voice_lang))
        val langs = com.backpack.app.core.VoiceText.LANGUAGES
        p.choices(langs, { it.second }, { langs.firstOrNull { l -> l.first == (prefs.getString(Settings.KEY_VOICE_LANG, null) ?: com.backpack.app.core.VoiceText.defaultFor(java.util.Locale.getDefault().language)) } ?: langs[0] }) {
            prefs.edit().putString(Settings.KEY_VOICE_LANG, it.first).apply()
        }
        p.text(c.getString(R.string.set_voice_lang_help), 11f)
        // 1.3.0: Backpack keeps running with the screen off until this (or Exit in the notification).
        // 1.3.0: trip recording.
        p.section(c.getString(R.string.set_trips))
        p.toggle(c.getString(R.string.set_record_trips), { prefs.getBoolean(Settings.KEY_RECORD_TRIPS, true) }) {
            prefs.edit().putBoolean(Settings.KEY_RECORD_TRIPS, it).apply()
        }
        p.bodyButton(c.getString(R.string.set_my_trips)) { host.onShowTrips() }
        p.section(c.getString(R.string.set_background))
        p.text(c.getString(R.string.set_background_help), 11f)
        p.bodyButton(c.getString(R.string.set_exit)) { host.onExitApp() }
    }

    private fun speedPage(c: Context, p: Popup, prefs: android.content.SharedPreferences) {
        p.section(c.getString(R.string.set_units))
        val units = listOf(true to c.getString(R.string.set_km), false to c.getString(R.string.set_miles))
        p.choices(units, { it.second }, { units.first { u -> u.first == prefs.getBoolean(Settings.KEY_UNIT_METRIC, true) } }) {
            prefs.edit().putBoolean(Settings.KEY_UNIT_METRIC, it.first).apply()
        }
        p.section(c.getString(R.string.set_speed_alarm))
        p.toggle(c.getString(R.string.set_alarm_sound), { prefs.getBoolean(Settings.KEY_ALARM_SOUND, true) }) {
            prefs.edit().putBoolean(Settings.KEY_ALARM_SOUND, it).apply()
        }
        p.text(pct(c, R.string.set_speed_help), 11f)
        // 1.4.0: fixed speed cameras, off by default.
        p.section(c.getString(R.string.set_cameras))
        p.toggle(c.getString(R.string.set_cameras_toggle), { prefs.getBoolean(Settings.KEY_CAMERAS, false) }) {
            prefs.edit().putBoolean(Settings.KEY_CAMERAS, it).apply()
        }
        p.text(c.getString(R.string.set_cameras_help), 11f)
    }

    /**
     * 1.2.0: everything about sights in one page. All values are times: the
     * travel mode turns them into distances.
     */
    private fun sightsPage(c: Context, p: Popup, prefs: android.content.SharedPreferences) {
        p.text(c.getString(R.string.set_sights_intro), 12f)
        p.section(c.getString(R.string.set_kinds))
        for (g in com.backpack.app.core.SightGroup.values()) {
            p.toggle(g.label, { g in com.backpack.app.core.SightGroup.parse(prefs.getString(Settings.KEY_SIGHT_GROUPS, null)) }) { on ->
                val now = com.backpack.app.core.SightGroup.parse(prefs.getString(Settings.KEY_SIGHT_GROUPS, null)).toMutableSet()
                if (on) now.add(g) else now.remove(g)
                prefs.edit().putString(Settings.KEY_SIGHT_GROUPS, com.backpack.app.core.SightGroup.save(now)).apply()
            }
        }
        // 1.4.0: priority of the kinds (first = most wanted; tap ↑ to move one up).
        p.section(c.getString(R.string.set_priority))
        val list = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            val order = com.backpack.app.core.SightGroup.parseOrder(prefs.getString(Settings.KEY_SIGHT_ORDER, null))
            for ((i, g) in order.withIndex()) {
                val row = android.widget.TextView(c).apply {
                    text = "${i + 1}.  ${g.label.substringBefore(" (")}" + if (i > 0) "      \u2191" else ""
                    textSize = 14f
                    val d = c.resources.displayMetrics.density
                    setPadding(0, (7 * d).toInt(), 0, (7 * d).toInt())
                    if (i > 0) setOnClickListener {
                        val m = order.toMutableList()
                        m.removeAt(i); m.add(i - 1, g)
                        prefs.edit().putString(Settings.KEY_SIGHT_ORDER, com.backpack.app.core.SightGroup.saveOrder(m)).apply()
                        com.backpack.app.core.Tourist.order = m
                        fill()
                    }
                }
                list.addView(row)
            }
        }
        fill()
        p.custom(list)
        p.text(c.getString(R.string.set_priority_help), 11f)
        p.section(c.getString(R.string.set_no_dest))
        val reach = com.backpack.app.core.Reach.MINUTES.map { it to (if (it < 60) "$it min" else "${it / 60} h") }
        p.choices(reach, { it.second }, {
            val cur = prefs.getInt(Settings.KEY_SIGHTS_REACH_MIN, com.backpack.app.core.Reach.DEFAULT_MINUTES)
            reach.firstOrNull { it.first == cur } ?: reach[1]
        }) { prefs.edit().putInt(Settings.KEY_SIGHTS_REACH_MIN, it.first).apply() }
        p.text(c.getString(R.string.set_no_dest_help), 11f)
        p.section(c.getString(R.string.set_choosing_route))
        intField(p, prefs, c.getString(R.string.set_longest_detour), Settings.KEY_DETOUR_WEEKEND_MIN, Settings.DEFAULT_DETOUR_WEEKEND_MIN, 1, 60)
        p.text(c.getString(R.string.set_detour_help), 11f)
        p.section(c.getString(R.string.set_headsup_title))
        p.toggle(c.getString(R.string.set_headsup_toggle), { prefs.getBoolean(Settings.KEY_SIGHT_HEADSUP, true) }) {
            prefs.edit().putBoolean(Settings.KEY_SIGHT_HEADSUP, it).apply()
        }
        intField(p, prefs, c.getString(R.string.set_headsup_min), Settings.KEY_SIGHT_HEADSUP_MIN, Settings.DEFAULT_SIGHT_HEADSUP_MIN, 1, 15)
        p.text(c.getString(R.string.set_headsup_help), 11f)
        p.section(c.getString(R.string.set_sights_route))
        intField(p, prefs, pct(c, R.string.set_sights_max), Settings.KEY_SIGHTS_MAX_PCT, Settings.DEFAULT_SIGHTS_MAX_PCT, 0, 100)
        p.text(c.getString(R.string.set_sights_max_help), 11f)
    }

    private fun placesPage(c: Context, p: Popup, prefs: android.content.SharedPreferences, host: Host) {
        p.section(c.getString(R.string.set_services_detour))
        intField(p, prefs, c.getString(R.string.set_work_detour), Settings.KEY_DETOUR_WORK_MIN, Settings.DEFAULT_DETOUR_WORK_MIN, 1, 30)
        p.text(c.getString(R.string.set_services_help), 11f)
        p.section(c.getString(R.string.set_saved_places))
        p.text(c.getString(R.string.set_saved_places_help), 11f)
        p.bodyButton(c.getString(R.string.set_clear_recent)) {
            prefs.edit().remove(Settings.KEY_RECENT_SEARCHES).apply()
            android.widget.Toast.makeText(c, c.getString(R.string.set_recent_cleared), android.widget.Toast.LENGTH_SHORT).show()
        }
        p.bodyButton(c.getString(R.string.set_clear_places)) {
            TileCache.clearAll(c)
            com.backpack.app.tourist.SiteInfoStore.forget()
            host.onPlacesCleared()
            android.widget.Toast.makeText(c, c.getString(R.string.set_places_cleared), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 1.0.8: who makes Backpack possible, with their licences and, where
     * they accept donations, a link to their own official donation page.
     * Backpack collects nothing itself.
     */
    private fun creditsPage(c: Context, p: Popup) {
        p.text(c.getString(R.string.set_credits_intro))
        // 1.6.0: Backpack's own licence and source.
        p.link(c.getString(R.string.set_cr_license), "https://github.com/ale-X-mkr/Backpack", 12f)
        fun source(name: String, what: String, site: String, donate: String?) {
            p.section(name)
            p.text(what, 12f)
            p.link(c.getString(R.string.set_website), site, 12f)
            donate?.let { p.link(c.getString(R.string.set_support_them), it, 12f) }
        }
        source(c.getString(R.string.set_osm_contributors), c.getString(R.string.set_cr_osm),
            "https://www.openstreetmap.org/copyright", "https://supporting.openstreetmap.org/donate/")
        source("OpenFreeMap \u00b7 OpenMapTiles", c.getString(R.string.set_cr_tiles),
            "https://openfreemap.org", "https://github.com/sponsors/hyperknot")
        source("MapLibre", c.getString(R.string.set_cr_maplibre),
            "https://maplibre.org", "https://opencollective.com/maplibre")
        source("openrouteservice \u00b7 HeiGIT", c.getString(R.string.set_cr_ors),
            "https://openrouteservice.org", null)
        source("Overpass API", c.getString(R.string.set_cr_overpass),
            "https://overpass-api.de", null)
        source("Photon \u00b7 komoot", c.getString(R.string.set_cr_photon),
            "https://photon.komoot.io", null)
        source("Wikipedia \u00b7 Wikidata \u00b7 Wikimedia", c.getString(R.string.set_cr_wiki),
            "https://www.wikidata.org", "https://donate.wikimedia.org")
        source("Material icons \u00b7 Google", c.getString(R.string.set_cr_icons),
            "https://fonts.google.com/icons", null)
    }

    private fun mapPage(c: Context, p: Popup, prefs: android.content.SharedPreferences) {
        p.section(c.getString(R.string.set_day_night))
        val modes = listOf(Settings.DISPLAY_MODE_DAY to c.getString(R.string.set_mode_day), Settings.DISPLAY_MODE_NIGHT to c.getString(R.string.set_mode_night),
            Settings.DISPLAY_MODE_AUTO to c.getString(R.string.set_mode_sunset), Settings.DISPLAY_MODE_SYSTEM to c.getString(R.string.set_mode_system))
        p.choices(modes, { it.second }, {
            val cur = prefs.getString(Settings.KEY_DISPLAY_MODE, Settings.DISPLAY_MODE_AUTO)
            modes.firstOrNull { it.first == cur } ?: modes[2]
        }) { prefs.edit().putString(Settings.KEY_DISPLAY_MODE, it.first).apply() }
        p.text(c.getString(R.string.set_day_night_help), 11f)
        p.section(c.getString(R.string.set_map_style))
        val day = Prefs.DAY_STYLES
        p.choices(day, { it.second.first }, {
            val cur = prefs.getString(Settings.KEY_MAP_STYLE, "liberty")
            day.firstOrNull { it.first == cur } ?: day[0]
        }) { prefs.edit().putString(Settings.KEY_MAP_STYLE, it.first).apply() }
        p.section(c.getString(R.string.set_night_style))
        val night = Prefs.NIGHT_STYLES
        p.choices(night, { it.second.first }, {
            val cur = prefs.getString(Settings.KEY_NIGHT_STYLE, "dark")
            night.firstOrNull { it.first == cur } ?: night[0]
        }) { prefs.edit().putString(Settings.KEY_NIGHT_STYLE, it.first).apply() }
        p.text(c.getString(R.string.set_style_help), 11f)
    }

    /**
     * 1.4.0 "Advanced": Backpack works without touching any of this. Privacy
     * in plain view; servers, keys and an own map style folded away, each
     * with a short line and a "?" for the details.
     */
    private fun serversPage(c: Context, p: Popup, prefs: android.content.SharedPreferences, host: Host) {
        p.section(c.getString(R.string.set_privacy))
        p.text(c.getString(R.string.set_privacy_help), 11f)
        p.link(c.getString(R.string.set_map_data_link), "https://www.openstreetmap.org/copyright")

        p.section(c.getString(R.string.set_servers_keys))
        p.text(c.getString(R.string.set_optional), 11f)
        val folded = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val opener = p.bodyButton(c.getString(R.string.set_show)) {}
        opener.setOnClickListener {
            val open = folded.visibility != android.view.View.VISIBLE
            folded.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            opener.text = if (open) c.getString(R.string.set_hide) else c.getString(R.string.set_show)
            p.fit()
        }
        p.custom(folded)
        p.into(folded) {
            p.section(c.getString(R.string.set_routing))
            urlField(c, p, prefs, c.getString(R.string.set_routing_server), Settings.KEY_ORS_URL, "https://...")
            p.field(c.getString(R.string.set_own_ors_key), prefs.getString(Settings.KEY_ORS_KEY, "").orEmpty(),
                c.getString(R.string.set_key_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                prefs.edit().putString(Settings.KEY_ORS_KEY, it).apply()
            }
            p.bodyButton(c.getString(R.string.set_get_key)) { host.onKeyGuide() }
            help(c, p, c.getString(R.string.set_routing_help))

            p.section(c.getString(R.string.set_places_servers))
            val none = "none" to c.getString(R.string.set_provider_none)
            val providers = listOf(none) + com.backpack.app.core.OverpassProviders.ALL.map { it.id to it.label }
            p.text(c.getString(R.string.set_faster_server), 12f)
            p.choices(providers, { it.second }, {
                val cur = prefs.getString(Settings.KEY_OVERPASS_PROVIDER, null)
                providers.firstOrNull { it.first == cur } ?: none
            }) { prefs.edit().putString(Settings.KEY_OVERPASS_PROVIDER, it.first).apply() }
            var keyField: android.widget.EditText? = null
            keyField = p.field(c.getString(R.string.set_server_key), prefs.getString(Settings.KEY_OVERPASS_KEY, "").orEmpty(),
                c.getString(R.string.set_key_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { text ->
                if (text.isEmpty() || com.backpack.app.core.OverpassProviders.ALL.first().url(text) != null) {
                    prefs.edit().putString(Settings.KEY_OVERPASS_KEY, text.trim()).apply()
                    keyField?.error = null
                } else keyField?.error = c.getString(R.string.set_key_chars)
            }
            urlField(c, p, prefs, c.getString(R.string.set_own_overpass), Settings.KEY_OVERPASS_URL, "https://.../api/interpreter")
            help(c, p, com.backpack.app.core.OverpassProviders.ALL.joinToString(" \u00b7 ") { "${it.label}: ${it.note}" } +
                c.getString(R.string.set_overpass_help))

            p.section(c.getString(R.string.set_own_style))
            urlField(c, p, prefs, c.getString(R.string.set_style_url), Settings.KEY_CUSTOM_STYLE, "https://.../style.json")
            help(c, p, c.getString(R.string.set_style_url_help))
        }
    }

    /** A "?" that unfolds a longer explanation. */
    private fun help(c: Context, p: Popup, text: String) {
        val q = p.text(c.getString(R.string.set_more), 11f)
        val more = p.text(text, 11f).apply { visibility = android.view.View.GONE }
        q.setTextColor(androidx.core.content.ContextCompat.getColor(c, com.backpack.app.R.color.rg_active))
        q.setOnClickListener {
            val open = more.visibility != android.view.View.VISIBLE
            more.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            q.text = if (open) c.getString(R.string.set_less) else c.getString(R.string.set_more)
            p.fit()
        }
    }

    // ---------- Fields ----------

    /** A text with a literal %% in the resource: formatted so it shows as one %. */
    private fun pct(c: Context, id: Int): String = c.getString(id, *emptyArray<Any>())


    private fun floatField(p: Popup, prefs: android.content.SharedPreferences, label: String, key: String, def: Double, type: Int) {
        val e = p.field(label, prefs.getFloat(key, def.toFloat()).toString(), def.toString(), type) { text ->
            text.toFloatOrNull()?.takeIf { it >= 0f }?.let { prefs.edit().putFloat(key, it).apply() }
        }
        e.setOnFocusChangeListener { _, has -> if (!has && e.text.toString().toFloatOrNull() == null) e.setText(prefs.getFloat(key, def.toFloat()).toString()) }
    }

    private fun intField(p: Popup, prefs: android.content.SharedPreferences, label: String, key: String, def: Int, min: Int, max: Int) {
        p.field(label, prefs.getInt(key, def).toString(), def.toString(), InputType.TYPE_CLASS_NUMBER) { text ->
            text.toIntOrNull()?.let { prefs.edit().putInt(key, it.coerceIn(min, max)).apply() }
        }
    }

    /** Stored only when empty (= default) or a http(s) address; else the field says so. */
    private fun urlField(c: Context, p: Popup, prefs: android.content.SharedPreferences, label: String, key: String, hint: String) {
        var field: android.widget.EditText? = null
        field = p.field(label, prefs.getString(key, "").orEmpty(), hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
            if (text.isEmpty() || text.startsWith("https://") || text.startsWith("http://")) {
                prefs.edit().putString(key, text).apply()
                field?.error = null
            } else {
                field?.error = c.getString(R.string.set_must_https)
            }
        }
    }
}
