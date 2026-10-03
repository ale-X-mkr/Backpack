package com.backpack.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.TextView
import com.backpack.app.BuildConfig
import com.backpack.app.Prefs
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

    enum class Page(val label: String) {
        ROUTE("Route"), NAVIGATION("Navigation"), SPEED("Speed"), PLACES("Places"), MAP("Map"), SERVERS("Servers")
    }

    interface Host {
        val travelMode: TravelMode
        /** (name, label) of the POI categories, for "default on launch". */
        val poiChoices: List<Pair<String, String>>
        /** The popup opens (e.g. hold the automatic day/night switch meanwhile). */
        fun onSettingsOpened()
        /** Called on close, only when the route options of a mode changed. */
        fun onRouteOptionsChanged(options: RouteOptions)
        /** Called on close: re-read settings (style, night mode, view...). */
        fun onSettingsClosed()
        /** Place caches were emptied. */
        fun onPlacesCleared()
    }

    fun show(c: Context, host: Host, start: Page = Page.ROUTE) {
        val prefs = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val originalOptions = Prefs.routeOptions(c)
        var o = originalOptions
        val p = Popup(c, "Settings · v${BuildConfig.VERSION_NAME}")

        // Tabs.
        val tabs = FlowRow(c)
        val tabViews = HashMap<Page, TextView>()
        var current = start
        fun build(page: Page) {
            current = page
            Page.values().forEach { tab -> tabViews[tab]?.let { p.styleChip(it, tab == page) } }
            p.clearBody()
            when (page) {
                // Saved at once: a screen rebuild while the popup is open loses nothing.
                Page.ROUTE -> routePage(c, p, host.travelMode, { o }, { o = it; Prefs.saveRouteOptions(c, it) }, prefs)
                Page.NAVIGATION -> navigationPage(p, prefs)
                Page.SPEED -> speedPage(p, prefs)
                Page.PLACES -> placesPage(c, p, prefs, host)
                Page.MAP -> mapPage(p, prefs)
                Page.SERVERS -> serversPage(p, prefs)
            }
            p.fit()
        }
        for (page in Page.values()) {
            val chip = TextView(c).apply {
                text = page.label
                textSize = 13f
                gravity = Gravity.CENTER
                val d = c.resources.displayMetrics.density
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                setOnClickListener { if (current != page) build(page) }
            }
            tabViews[page] = chip
            tabs.addView(chip)
        }
        p.header.addView(tabs)
        p.header.visibility = android.view.View.VISIBLE
        build(start)

        p.button("Done", primary = true) { p.dismiss() }
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
            TravelMode.DRIVING -> "Route options · Driving"
            TravelMode.CYCLING -> "Route options · Cycling"
            TravelMode.WALKING -> "Route options · Walking"
        })
        when (mode) {
            TravelMode.DRIVING -> {
                p.choices(Vehicle.values().toList(), { it.label }, { get().vehicle }) { set(get().copy(vehicle = it)) }
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Motorways", { get().carAvoidMotorways }, { v: Boolean -> set(get().copy(carAvoidMotorways = v)) }),
                    Triple("Ferries", { get().carAvoidFerries }, { v: Boolean -> set(get().copy(carAvoidFerries = v)) }),
                    Triple("Fords", { get().carAvoidFords }, { v: Boolean -> set(get().copy(carAvoidFords = v)) })
                ))
                p.section("Surface")
                p.toggle("Prefer paved roads", { get().carPreferPaved }) { set(get().copy(carPreferPaved = it)) }
                p.text("Tolls: toll button on the map. Motorbike is routed like a car. \"Prefer paved\" takes the alternative with the least unpaved road if it costs under 25 % more time.", 11f)
            }
            TravelMode.CYCLING -> {
                p.choices(BikeType.values().toList(), { it.label }, { get().bikeType }) { set(get().copy(bikeType = it)) }
                p.section("Hills")
                p.choices(Hills.values().toList(), { it.label }, { get().bikeHills }) { set(get().copy(bikeHills = it)) }
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Steps", { get().bikeAvoidSteps }, { v: Boolean -> set(get().copy(bikeAvoidSteps = v)) }),
                    Triple("Ferries", { get().bikeAvoidFerries }, { v: Boolean -> set(get().copy(bikeAvoidFerries = v)) })
                ))
                p.section("Surface")
                p.toggle("Prefer paved roads", { get().bikePreferPaved }) { set(get().copy(bikePreferPaved = it)) }
            }
            TravelMode.WALKING -> {
                p.choices(WalkType.values().toList(), { it.label }, { get().walkType }) { set(get().copy(walkType = it)) }
                p.section("Prefer")
                p.toggles(listOf(
                    Triple("Green areas", { get().walkGreen }, { v: Boolean -> set(get().copy(walkGreen = v)) }),
                    Triple("Quiet streets", { get().walkQuiet }, { v: Boolean -> set(get().copy(walkQuiet = v)) })
                ))
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Steps", { get().walkAvoidSteps }, { v: Boolean -> set(get().copy(walkAvoidSteps = v)) }),
                    Triple("Ferries", { get().walkAvoidFerries }, { v: Boolean -> set(get().copy(walkAvoidFerries = v)) })
                ))
            }
        }
        p.bodyButton("Reset this mode") {
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
        p.section("Value of your time (EUR / hour)")
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        floatField(p, prefs, "Weekend", Settings.KEY_VALUE_WEEKEND, Settings.DEFAULT_VALUE_WEEKEND, num)
        floatField(p, prefs, "Work", Settings.KEY_VALUE_WORK, Settings.DEFAULT_VALUE_WORK, num)
        floatField(p, prefs, "Emergency", Settings.KEY_VALUE_EMERGENCY, Settings.DEFAULT_VALUE_EMERGENCY, num)
        p.section("Toll estimate")
        floatField(p, prefs, "EUR per km of toll road", Settings.KEY_TOLL_RATE, Settings.DEFAULT_TOLL_RATE, num)
        p.text("Balanced mode compares the toll with the time it saves, at the value of the current mode (urgency button).", 11f)
    }

    private fun navigationPage(p: Popup, prefs: android.content.SharedPreferences) {
        p.section("3D view while navigating")
        val tilts = listOf(-1 to "Auto", 0 to "Flat", 30 to "30°", 45 to "45°", 60 to "60°")
        p.choices(tilts, { it.second }, { tilts.firstOrNull { t -> t.first == prefs.getInt(Settings.KEY_NAV_TILT, -1) } ?: tilts[0] }) {
            prefs.edit().putInt(Settings.KEY_NAV_TILT, it.first).apply()
        }
        p.text("Auto: 45° driving, 25° walking and cycling. North up is always flat.", 11f)
        p.section("Zoom while navigating")
        val zooms = listOf(1 to "Closer", 0 to "Normal", -1 to "Farther")
        p.choices(zooms, { it.second }, { zooms.firstOrNull { z -> z.first == prefs.getInt(Settings.KEY_NAV_ZOOM, 0) } ?: zooms[1] }) {
            prefs.edit().putInt(Settings.KEY_NAV_ZOOM, it.first).apply()
        }
        p.text("Driving still zooms out with speed. The overview button (while navigating) shows the rest of the route flat, with the places along it; it comes back to this view by itself.", 11f)
        p.section("Voice")
        p.toggle("Spoken instructions", { prefs.getBoolean(Settings.KEY_VOICE, true) }) {
            prefs.edit().putBoolean(Settings.KEY_VOICE, it).apply()
        }
    }

    private fun speedPage(p: Popup, prefs: android.content.SharedPreferences) {
        p.section("Units")
        val units = listOf(true to "Kilometres", false to "Miles")
        p.choices(units, { it.second }, { units.first { u -> u.first == prefs.getBoolean(Settings.KEY_UNIT_METRIC, true) } }) {
            prefs.edit().putBoolean(Settings.KEY_UNIT_METRIC, it.first).apply()
        }
        p.section("Speed-limit alarm")
        p.toggle("Alarm sound", { prefs.getBoolean(Settings.KEY_ALARM_SOUND, true) }) {
            prefs.edit().putBoolean(Settings.KEY_ALARM_SOUND, it).apply()
        }
        p.text("Ring around your speed: green 5+ under the limit, yellow within 5 under, red up to 5 over, blinking red with a high beep beyond. " +
            "On roads of 100+: blue with a low beep when 15+ under, red with a low beep when stopped. " +
            "Limits come from OpenStreetMap, loaded for the road ahead of your route.", 11f)
    }

    private fun placesPage(c: Context, p: Popup, prefs: android.content.SharedPreferences, host: Host) {
        p.section("POI category when the app starts")
        val choices = listOf("NONE" to "Off") + host.poiChoices.filter { it.first != "NONE" }
        p.choices(choices, { it.second }, {
            val cur = prefs.getString(Settings.KEY_DEFAULT_POI, "NONE")
            choices.firstOrNull { it.first == cur } ?: choices[0]
        }) { prefs.edit().putString(Settings.KEY_DEFAULT_POI, it.first).apply() }
        p.section("Weekend sights route")
        intField(p, prefs, "Max extra time (%), 0 = off", Settings.KEY_SIGHTS_MAX_PCT, Settings.DEFAULT_SIGHTS_MAX_PCT, 0, 100)
        p.text("In Weekend mode, an alternative passing clearly more well-known places is taken if it is at most this much slower.", 11f)
        p.section("Saved places")
        p.text("Places are fetched per square of about 5 km and kept on this phone (everyday places 3 days, sights 2 weeks), so an area already seen needs no server.", 11f)
        p.bodyButton("Clear saved places") {
            TileCache.clearAll(c)
            com.backpack.app.tourist.SiteInfoStore.forget()
            host.onPlacesCleared()
            android.widget.Toast.makeText(c, "Saved places cleared", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun mapPage(p: Popup, prefs: android.content.SharedPreferences) {
        p.section("Day / night")
        val modes = listOf(Settings.DISPLAY_MODE_DAY to "Day", Settings.DISPLAY_MODE_NIGHT to "Night",
            Settings.DISPLAY_MODE_AUTO to "Sunset", Settings.DISPLAY_MODE_SYSTEM to "System")
        p.choices(modes, { it.second }, {
            val cur = prefs.getString(Settings.KEY_DISPLAY_MODE, Settings.DISPLAY_MODE_AUTO)
            modes.firstOrNull { it.first == cur } ?: modes[2]
        }) { prefs.edit().putString(Settings.KEY_DISPLAY_MODE, it.first).apply() }
        p.text("Sunset: night map from sunset to sunrise where you are. Night always uses the dark map.", 11f)
        p.section("Map style (day)")
        val styles = listOf("liberty" to "Liberty", "bright" to "Bright", "positron" to "Light grey")
        p.choices(styles, { it.second }, {
            val cur = prefs.getString(Settings.KEY_MAP_STYLE, "liberty")
            styles.firstOrNull { it.first == cur } ?: styles[0]
        }) { prefs.edit().putString(Settings.KEY_MAP_STYLE, it.first).apply() }
        urlField(p, prefs, "Own map style (URL, optional)", Settings.KEY_CUSTOM_STYLE, "https://...")
        p.text("Maps by OpenFreeMap: no account, no key, no cookies.", 11f)
    }

    private fun serversPage(p: Popup, prefs: android.content.SharedPreferences) {
        p.section("Routing (openrouteservice)")
        urlField(p, prefs, "Routing server (empty = api.openrouteservice.org)", Settings.KEY_ORS_URL, "https://...")
        p.field("Own openrouteservice key (free at openrouteservice.org)", prefs.getString(Settings.KEY_ORS_KEY, "").orEmpty(),
            "key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
            prefs.edit().putString(Settings.KEY_ORS_KEY, it).apply()
        }
        p.section("Places and speed limits (Overpass)")
        urlField(p, prefs, "Own Overpass server, tried first (optional)", Settings.KEY_OVERPASS_URL, "https://.../api/interpreter")
        p.text("Without it: overpass-api.de, then overpass.private.coffee. Each server's free capacity is checked before asking.", 11f)
        p.section("Privacy")
        p.text("No account, no tracking. Routing gets the trip's points; places servers only the ~5 km squares looked at; " +
            "speed limits the road ahead. Search positions are rounded to ~1 km. Nothing about your trips is stored, except the places cache (Places page).", 11f)
        p.link("Map data © OpenStreetMap contributors (ODbL)", "https://www.openstreetmap.org/copyright")
    }

    // ---------- Fields ----------

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
    private fun urlField(p: Popup, prefs: android.content.SharedPreferences, label: String, key: String, hint: String) {
        var field: android.widget.EditText? = null
        field = p.field(label, prefs.getString(key, "").orEmpty(), hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
            if (text.isEmpty() || text.startsWith("https://") || text.startsWith("http://")) {
                prefs.edit().putString(key, text).apply()
                field?.error = null
            } else {
                field?.error = "Must start with https://"
            }
        }
    }
}
