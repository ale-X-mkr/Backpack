package com.backpack.app.tourist

import android.content.Context
import com.backpack.app.Prefs
import com.backpack.app.Settings
import com.backpack.app.TripState
import com.backpack.app.core.Progress
import com.backpack.app.core.RouteResult
import com.backpack.app.core.SightGroup
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.core.VoiceQueue
import com.backpack.app.core.VoiceText
import com.backpack.app.nav.NavigationEngine

/**
 * Weekend, navigating: well-known sights within your Weekend detour, said
 * in a gap between instructions; a place already passed is dropped. Runs
 * with the navigation itself (screen off too); the map screen, when shown,
 * adds the card / message.
 *
 * 1.4.0: 3 or more in the next 5 minutes -> one "several sights" sentence;
 * the first and last 10 minutes of the trip are quiet -> one summary each
 * ("N sights around your starting point / destination").
 */
object SightHeadsUp {

    interface Ui {
        fun onHeadsUp(poi: TouristPoi, detourLabel: String)
        fun onSeveral(pois: List<TouristPoi>) {}
    }

    /** The map screen while it is shown (null with the screen off). */
    var ui: Ui? = null
    private val said = HashSet<String>()
    private var startDone = false
    private var destinationDone = false

    const val QUIET_S = 600.0
    const val GROUP_S = 300.0
    const val GROUP_MIN = 3

    fun reset() { said.clear(); startDone = false; destinationDone = false }

    fun onProgress(c: Context, p: Progress, route: RouteResult) {
        if (p.offRoute || p.arrived) return
        val toggles = c.getSharedPreferences("backpack_toggle_state", Context.MODE_PRIVATE)
        val prefs = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        if (toggles.getString("urgency", "WEEKEND") != "WEEKEND") return
        if (!prefs.getBoolean(Settings.KEY_SIGHTS_WEEKEND, true)) return
        if (SightGroup.parse(prefs.getString(Settings.KEY_SIGHT_GROUPS, null)).isEmpty()) return
        if (!prefs.getBoolean(Settings.KEY_SIGHT_HEADSUP, true)) return
        Tourist.order = SightGroup.parseOrder(prefs.getString(Settings.KEY_SIGHT_ORDER, null))
        // At most two waiting: more would be stale by the time a gap comes.
        if (NavigationEngine.pendingInfo(VoiceQueue.Kind.HEADS_UP) >= 2) return
        val mode = when (toggles.getString("mode", null)) {
            "CYCLING" -> TravelMode.CYCLING
            "DRIVING" -> TravelMode.DRIVING
            else -> TravelMode.WALKING
        }
        val words = VoiceText.forLanguage(Prefs.voiceLanguage(c))
        // Route's own average speed: seconds <-> metres along the route.
        val speed = if (route.durationSeconds > 60.0 && route.distanceMeters > 0.0) route.distanceMeters / route.durationSeconds else 10.0
        val maxDetour = prefs.getInt(Settings.KEY_DETOUR_WEEKEND_MIN, Settings.DEFAULT_DETOUR_WEEKEND_MIN) * 60.0
        val dim = TripState.touristDimKeys
        val all = TripState.touristResults.filter {
            it.key !in dim && it.sitelinks >= 1 && Tourist.estimateDetourSeconds(it.offsetM, mode) <= maxDetour
        }
        if (all.isEmpty()) return
        val length = route.line.length
        val quietM = QUIET_S * speed
        val sinceStartM = p.alongM
        val toEndM = (length - p.alongM).coerceAtLeast(0.0)

        // Start: one summary of the places near the start (once the places are known).
        if (!startDone) {
            startDone = true
            val near = all.filter { it.alongM < quietM }
            near.forEach { said.add(it.key) }
            if (near.isNotEmpty() && sinceStartM < quietM) say(words.startSummary(near.size)) { ui?.onSeveral(near) }
        }
        // Destination: one summary when the last 10 minutes begin.
        if (!destinationDone && toEndM < quietM) {
            destinationDone = true
            val near = all.filter { length - it.alongM < quietM && it.alongM > p.alongM - 200.0 }
            near.forEach { said.add(it.key) }
            if (near.isNotEmpty()) say(words.destinationSummary(near.size)) { ui?.onSeveral(near) }
        }
        if (sinceStartM < quietM || toEndM < quietM) return // quiet zones

        // Next 5 minutes: three or more -> one sentence for all of them.
        val soon = all.filter { it.key !in said && (it.alongM - p.alongM) in 0.0..(GROUP_S * speed) }
        if (soon.size >= GROUP_MIN) {
            soon.forEach { said.add(it.key) }
            say(words.severalNear(soon.size)) { ui?.onSeveral(soon) }
            return
        }

        // One place: minutes from Settings, turned into metres with the route's speed.
        val minutes = prefs.getInt(Settings.KEY_SIGHT_HEADSUP_MIN, Settings.DEFAULT_SIGHT_HEADSUP_MIN)
        val lead = Tourist.headsUpLeadM(minutes, route.distanceMeters, route.durationSeconds, mode)
        val poi = all.asSequence()
            .filter { it.key !in said && (it.alongM - p.alongM) in 0.0..lead }
            .maxByOrNull { Tourist.score(it) } ?: return
        said.add(poi.key)
        val detour = Tourist.estimateDetourSeconds(poi.offsetM, mode)
        val mins = Tourist.minutesLabel(detour)
        fun aheadM() = NavigationEngine.lastProgress?.let { poi.alongM - it.alongM }
        NavigationEngine.speakInfo(VoiceQueue.Item(
            VoiceQueue.Kind.HEADS_UP,
            text = {
                // Worded when said: the time is still right after waiting for a gap.
                val inMin = Math.round(((aheadM() ?: 0.0).coerceAtLeast(0.0) / lead) * minutes).toInt().coerceAtLeast(1)
                words.headsUp(inMin, poi.name, poi.category, Math.round(detour / 60.0).toInt().coerceAtLeast(1))
            },
            valid = { NavigationEngine.isActive && (aheadM() ?: -1.0) > 150.0 },
            onStart = { ui?.onHeadsUp(poi, mins) }
        ))
    }

    private fun say(text: String, onStart: () -> Unit) {
        NavigationEngine.speakInfo(VoiceQueue.Item(VoiceQueue.Kind.HEADS_UP, { text }, { NavigationEngine.isActive }, onStart))
    }
}
