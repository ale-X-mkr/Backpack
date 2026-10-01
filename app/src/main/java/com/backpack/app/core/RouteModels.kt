package com.backpack.app.core

enum class TravelMode { WALKING, CYCLING, DRIVING }

enum class RoutePreference(val orsParam: String) {
    FASTEST("fastest"),
    SHORTEST("shortest"),
    RECOMMENDED("recommended")
}

/** ORS instruction "type" codes (openrouteservice docs, instruction types). */
enum class Maneuver {
    LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, STRAIGHT,
    ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, UTURN, GOAL, DEPART, KEEP_LEFT, KEEP_RIGHT, UNKNOWN;

    companion object {
        fun fromOrs(type: Int): Maneuver = when (type) {
            0 -> LEFT
            1 -> RIGHT
            2 -> SHARP_LEFT
            3 -> SHARP_RIGHT
            4 -> SLIGHT_LEFT
            5 -> SLIGHT_RIGHT
            6 -> STRAIGHT
            7 -> ROUNDABOUT_ENTER
            8 -> ROUNDABOUT_EXIT
            9 -> UTURN
            10 -> GOAL
            11 -> DEPART
            12 -> KEEP_LEFT
            13 -> KEEP_RIGHT
            else -> UNKNOWN
        }
    }
}

/**
 * One ORS step. The instruction describes the maneuver at the START of
 * the step ([wayStart]); [distanceMeters] is the length of road AFTER it.
 * (The old code treated the step's end as its maneuver point, so it
 * showed "Head north" while you approached the right turn and only
 * announced "Turn right" when already at the corner.)
 */
data class NavStep(
    val instruction: String,
    val maneuver: Maneuver,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val wayStart: Int,
    val wayEnd: Int,
    val streetName: String = "",
    val exitNumber: Int = 0
)

class RouteResult(
    val points: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val tollDistanceMeters: Double = 0.0,
    val unpavedDistanceMeters: Double = 0.0,
    val steps: List<NavStep> = emptyList()
) {
    val line: Polyline by lazy { Polyline(points) }

    val unpavedFraction: Double
        get() = if (distanceMeters <= 0.0) 0.0 else (unpavedDistanceMeters / distanceMeters).coerceIn(0.0, 1.0)
}

/** What the planner returns: all candidate routes, which one is primary,
 *  and an optional explanation (toll decision, paved preference...). */
data class RoutePlan(val routes: List<RouteResult>, val primaryIndex: Int, val note: String?)

/** Everything needed to (re)compute a route. Snapshotted when navigation
 *  starts, so a reroute uses exactly the same rules as the original
 *  calculation -- including the Balanced toll comparison. */
data class RouteRequest(
    val from: LatLon,
    val to: LatLon,
    val waypoints: List<LatLon>,
    val mode: TravelMode,
    val preference: RoutePreference,
    val avoidTolls: Boolean,
    val options: RouteOptions,
    val valuePerMinute: Double,
    val tollRatePerKm: Double
)
