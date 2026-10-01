package com.backpack.app.core

import kotlin.math.max
import kotlin.math.roundToInt

/** A GPS fix reduced to what navigation needs. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val bearing: Float?,
    val accuracyM: Float?,
    val timeMs: Long
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

data class Progress(
    val snap: Snap?,
    val alongM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Index of the upcoming maneuver step, or -1 when none is left. */
    val nextStepIndex: Int,
    val distanceToNextM: Double,
    /** Text to speak now, if any. */
    val speech: String?,
    /** True when an early "in 400 meters..." warning was just given. */
    val anticipation: Boolean,
    /** True right after passing a maneuver. */
    val stepAdvanced: Boolean,
    val offRoute: Boolean,
    val arrived: Boolean
)

/**
 * Pure navigation logic: where am I on the route, what is the next
 * maneuver, what should be said and when, am I off route, have I
 * arrived. No Android code, so it is unit-tested on a plain JVM.
 */
class NavigationTracker(
    val route: RouteResult,
    private val mode: TravelMode,
    private val metric: Boolean
) {
    private val line = route.line
    private val stepStart: DoubleArray = DoubleArray(route.steps.size) { line.alongAt(route.steps[it].wayStart) }
    private var hint = -1
    private var offCount = 0
    private var lastNext = -1
    private var arrivedFlag = false
    /** step index -> bit 1 = continue, 2 = early, 4 = now */
    private val announced = HashMap<Int, Int>()

    /** Distance along the route at the last on-route fix. */
    var lastAlongM = 0.0
        private set

    /** Departure sentence, e.g. "Head north on Rue X". */
    fun departureSpeech(): String? = route.steps.firstOrNull()?.instruction

    /** How many intermediate stops have been passed (ORS ends every leg
     *  with a "goal" step; the last one is the destination). Used so a
     *  reroute does not send you back to a stop you already visited. */
    fun passedStops(): Int {
        val goals = route.steps.indices.filter { route.steps[it].maneuver == Maneuver.GOAL }
        if (goals.size <= 1) return 0
        return goals.dropLast(1).count { stepStart[it] <= lastAlongM + ARRIVE_RADIUS_M }
    }

    fun update(fix: Fix): Progress {
        val p = fix.latLon
        val snap = line.snap(p, hint)
        val along = snap?.alongM ?: 0.0
        val offset = snap?.offsetM ?: Double.MAX_VALUE
        val accuracy = (fix.accuracyM ?: 10f).toDouble()
        val offThreshold = max(50.0, accuracy * 1.5)

        val isOff = offset > offThreshold
        offCount = if (isOff) offCount + 1 else 0
        // One wild reading is not enough unless it is far away.
        val offRoute = offCount >= 2 || offset > 150.0
        if (!isOff && snap != null) {
            hint = snap.segmentIndex
            lastAlongM = snap.alongM
        }

        val remaining = (line.length - along).coerceAtLeast(0.0)
        val toEnd = if (route.points.isEmpty()) 0.0 else Geo.distance(p, route.points.last())

        // Upcoming maneuver: first step (after the departure) whose
        // maneuver point is still ahead of us.
        var next = -1
        for (k in 1 until route.steps.size) {
            if (stepStart[k] > along + PASSED_MARGIN_M) { next = k; break }
        }
        val distToNext = if (next >= 0) stepStart[next] - along else remaining
        val stepAdvanced = lastNext != -1 && next != lastNext
        lastNext = next

        val remainingS = remainingSeconds(along, next)

        if (!arrivedFlag && (toEnd < ARRIVE_RADIUS_M || (!isOff && remaining < ARRIVE_RADIUS_M))) {
            arrivedFlag = true
            return Progress(snap, along, remaining, 0.0, -1, 0.0, null, false, stepAdvanced, false, true)
        }

        var speech: String? = null
        var anticipation = false
        if (!isOff && next >= 0) {
            val speed = fix.speedMps?.toDouble()?.takeIf { it > 0.5 } ?: defaultSpeed()
            val earlyDist = max(minEarly(), speed * 30.0)
            val nowDist = max(minNow(), speed * 7.0)
            val flags = announced[next] ?: 0
            val step = route.steps[next]

            if (stepAdvanced && distToNext > max(1000.0, earlyDist * 3) && flags and 1 == 0) {
                announced[next] = flags or 1
                speech = "Continue for ${spokenDistance(distToNext)}"
            } else if (distToNext <= nowDist && flags and 4 == 0) {
                announced[next] = flags or 6
                speech = step.instruction
                // A second maneuver right after this one: say both now.
                val after = next + 1
                if (after < route.steps.size && stepStart[after] - stepStart[next] < nowDist * 2) {
                    speech += ", then " + lowerFirst(route.steps[after].instruction)
                    announced[after] = (announced[after] ?: 0) or 2
                }
            } else if (distToNext <= earlyDist && distToNext > nowDist * 1.5 && flags and 2 == 0) {
                announced[next] = flags or 2
                speech = "In ${spokenDistance(distToNext)}, ${lowerFirst(step.instruction)}"
                anticipation = true
            }
        }

        return Progress(snap, along, remaining, remainingS, next, distToNext, speech, anticipation, stepAdvanced, offRoute, false)
    }

    private fun remainingSeconds(along: Double, next: Int): Double {
        val steps = route.steps
        if (steps.isEmpty()) {
            return if (line.length > 0) route.durationSeconds * (line.length - along) / line.length else 0.0
        }
        val current = if (next == -1) steps.size - 1 else (next - 1).coerceAtLeast(0)
        var total = 0.0
        for (k in current + 1 until steps.size) total += steps[k].durationSeconds
        val curStep = steps[current]
        val curEnd = if (current + 1 < steps.size) stepStart[current + 1] else line.length
        val curLen = (curEnd - stepStart[current]).coerceAtLeast(1.0)
        val left = (curEnd - along).coerceIn(0.0, curLen)
        total += curStep.durationSeconds * left / curLen
        return total
    }

    private fun defaultSpeed() = when (mode) {
        TravelMode.WALKING -> 1.4
        TravelMode.CYCLING -> 4.5
        TravelMode.DRIVING -> 13.0
    }

    private fun minEarly() = when (mode) {
        TravelMode.WALKING -> 50.0
        TravelMode.CYCLING -> 100.0
        TravelMode.DRIVING -> 200.0
    }

    private fun minNow() = when (mode) {
        TravelMode.WALKING -> 15.0
        TravelMode.CYCLING -> 25.0
        TravelMode.DRIVING -> 40.0
    }

    fun spokenDistance(m: Double): String = Speech.distance(m, metric)

    companion object {
        const val PASSED_MARGIN_M = 8.0
        const val ARRIVE_RADIUS_M = 25.0

        fun lowerFirst(s: String): String =
            if (s.isEmpty()) s else s.substring(0, 1).lowercase() + s.substring(1)
    }
}

object Speech {
    /** "300 meters", "1.5 kilometers", "500 feet", "2 miles". */
    fun distance(m: Double, metric: Boolean): String {
        if (metric) {
            if (m < 1000) {
                val step = if (m >= 100) 50 else 10
                val v = max(step, ((m / step).roundToInt() * step))
                return if (v >= 1000) "1 kilometer" else "$v meters"
            }
            val km = (m / 100.0).roundToInt() / 10.0
            return if (km == Math.floor(km)) "${km.toInt()} kilometer${if (km.toInt() == 1) "" else "s"}" else "$km kilometers"
        }
        val feet = m * 3.28084
        if (feet < 1000) {
            val v = max(50, ((feet / 50).roundToInt() * 50))
            return "$v feet"
        }
        val mi = (m / 1609.344 * 10).roundToInt() / 10.0
        return if (mi == Math.floor(mi)) "${mi.toInt()} mile${if (mi.toInt() == 1) "" else "s"}" else "$mi miles"
    }

    /** Short on-screen form: "350 m", "1.2 km", "800 ft", "3.4 mi". */
    fun shortDistance(m: Double, metric: Boolean): String {
        if (metric) {
            return if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 10).roundToInt() * 10} m"
        }
        val feet = m * 3.28084
        return if (feet < 1000) "${(feet / 10).roundToInt() * 10} ft" else "%.1f mi".format(m / 1609.344)
    }
}
