package com.backpack.app.tourist

import android.content.Context
import com.backpack.app.Settings
import com.backpack.app.TripState
import com.backpack.app.core.Progress
import com.backpack.app.core.RouteResult
import com.backpack.app.core.SightGroup
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.core.VoiceQueue
import com.backpack.app.nav.NavigationEngine

/**
 * Weekend, navigating: a well-known sight within your Weekend detour is
 * coming up (minutes from Settings > Sights): queued once per place; the
 * voice says it in a gap between instructions, and a place already passed
 * is dropped. 1.3.0: runs with the navigation itself (screen off too); the
 * map screen, when shown, adds the message and the Listen button.
 */
object SightHeadsUp {

    fun interface Ui { fun onHeadsUp(poi: TouristPoi, detourLabel: String) }

    /** The map screen while it is shown (null with the screen off). */
    var ui: Ui? = null
    private val said = HashSet<String>()

    fun reset() = said.clear()

    fun onProgress(c: Context, p: Progress, route: RouteResult) {
        if (p.offRoute || p.arrived) return
        val toggles = c.getSharedPreferences("backpack_toggle_state", Context.MODE_PRIVATE)
        val prefs = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        if (toggles.getString("urgency", "WEEKEND") != "WEEKEND") return
        if (!prefs.getBoolean(Settings.KEY_SIGHTS_WEEKEND, true)) return
        if (SightGroup.parse(prefs.getString(Settings.KEY_SIGHT_GROUPS, null)).isEmpty()) return
        if (!prefs.getBoolean(Settings.KEY_SIGHT_HEADSUP, true)) return
        // At most two waiting: more would be stale by the time a gap comes.
        if (NavigationEngine.pendingInfo(VoiceQueue.Kind.HEADS_UP) >= 2) return
        val mode = when (toggles.getString("mode", null)) {
            "CYCLING" -> TravelMode.CYCLING
            "DRIVING" -> TravelMode.DRIVING
            else -> TravelMode.WALKING
        }
        // Set in minutes, turned into metres with the route's own average speed.
        val minutes = prefs.getInt(Settings.KEY_SIGHT_HEADSUP_MIN, Settings.DEFAULT_SIGHT_HEADSUP_MIN)
        val lead = Tourist.headsUpLeadM(minutes, route.distanceMeters, route.durationSeconds, mode)
        val maxDetour = prefs.getInt(Settings.KEY_DETOUR_WEEKEND_MIN, Settings.DEFAULT_DETOUR_WEEKEND_MIN) * 60.0
        val dim = TripState.touristDimKeys
        val poi = TripState.touristResults.asSequence()
            .filter { it.key !in dim && it.key !in said && it.sitelinks >= 1 }
            .filter { (it.alongM - p.alongM) in 0.0..lead }
            .filter { Tourist.estimateDetourSeconds(it.offsetM, mode) <= maxDetour }
            .maxByOrNull { it.sitelinks } ?: return
        said.add(poi.key)
        val detour = Tourist.estimateDetourSeconds(poi.offsetM, mode)
        val mins = Tourist.minutesLabel(detour)
        fun aheadM() = NavigationEngine.lastProgress?.let { poi.alongM - it.alongM }
        NavigationEngine.speakInfo(VoiceQueue.Item(
            VoiceQueue.Kind.HEADS_UP,
            text = {
                // Worded when said: the time is still right after waiting for a gap.
                val inMin = Math.round(((aheadM() ?: 0.0).coerceAtLeast(0.0) / lead) * minutes).toInt().coerceAtLeast(1)
                com.backpack.app.core.VoiceText.forLanguage(com.backpack.app.Prefs.voiceLanguage(c)).headsUp(inMin, poi.name, poi.category, Math.round(detour / 60.0).toInt().coerceAtLeast(1))
            },
            valid = { NavigationEngine.isActive && (aheadM() ?: -1.0) > 150.0 },
            onStart = { ui?.onHeadsUp(poi, mins) }
        ))
    }
}
