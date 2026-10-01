package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

enum class Vehicle(val label: String) {
    CAR("Car"),
    /** openrouteservice has no motorbike profile: routed as a car. */
    MOTORBIKE("Motorbike")
}

enum class BikeType(val orsProfile: String, val label: String) {
    REGULAR("cycling-regular", "City"),
    ROAD("cycling-road", "Road"),
    MOUNTAIN("cycling-mountain", "Mountain"),
    ELECTRIC("cycling-electric", "E-bike")
}

enum class WalkType(val orsProfile: String, val label: String) {
    WALK("foot-walking", "Walk"),
    HIKE("foot-hiking", "Hike")
}

/** Bike hill tolerance -> ORS steepness_difficulty (0 = avoid hills ... 3 = any). */
enum class Hills(val steepnessDifficulty: Int, val label: String) {
    FLAT(0, "Flat"),
    SOME(1, "Some"),
    HILLY(2, "Hilly"),
    ANY(3, "Any")
}

/**
 * Per-mode route options. Only what openrouteservice really supports is
 * sent as a rule; "prefer paved" cannot be a hard rule there, so it is
 * applied by choosing among the returned alternatives (see RoutePicker).
 */
data class RouteOptions(
    // Driving
    val vehicle: Vehicle = Vehicle.CAR,
    val carAvoidMotorways: Boolean = false,
    val carAvoidFerries: Boolean = false,
    val carAvoidFords: Boolean = false,
    val carPreferPaved: Boolean = false,
    // Cycling
    val bikeType: BikeType = BikeType.REGULAR,
    val bikeHills: Hills = Hills.SOME,
    val bikeAvoidSteps: Boolean = true,
    val bikeAvoidFerries: Boolean = false,
    val bikePreferPaved: Boolean = false,
    // Walking
    val walkType: WalkType = WalkType.WALK,
    val walkGreen: Boolean = false,
    val walkQuiet: Boolean = false,
    val walkAvoidSteps: Boolean = false,
    val walkAvoidFerries: Boolean = false
) {
    fun orsProfile(mode: TravelMode): String = when (mode) {
        TravelMode.DRIVING -> "driving-car"
        TravelMode.CYCLING -> bikeType.orsProfile
        TravelMode.WALKING -> walkType.orsProfile
    }

    fun avoidFeatures(mode: TravelMode, avoidTolls: Boolean): List<String> {
        val list = mutableListOf<String>()
        when (mode) {
            TravelMode.DRIVING -> {
                if (carAvoidMotorways) list.add("highways")
                if (avoidTolls) list.add("tollways")
                if (carAvoidFerries) list.add("ferries")
                if (carAvoidFords) list.add("fords")
            }
            TravelMode.CYCLING -> {
                if (bikeAvoidSteps) list.add("steps")
                if (bikeAvoidFerries) list.add("ferries")
            }
            TravelMode.WALKING -> {
                if (walkAvoidSteps) list.add("steps")
                if (walkAvoidFerries) list.add("ferries")
            }
        }
        return list
    }

    /** ORS "profile_params", or null when nothing to send. */
    fun profileParams(mode: TravelMode): JSONObject? {
        val weightings = JSONObject()
        when (mode) {
            TravelMode.CYCLING -> weightings.put("steepness_difficulty", bikeHills.steepnessDifficulty)
            TravelMode.WALKING -> {
                if (walkGreen) weightings.put("green", 1.0)
                if (walkQuiet) weightings.put("quiet", 1.0)
            }
            TravelMode.DRIVING -> {}
        }
        if (weightings.length() == 0) return null
        return JSONObject().put("weightings", weightings)
    }

    fun preferPaved(mode: TravelMode): Boolean = when (mode) {
        TravelMode.DRIVING -> carPreferPaved
        TravelMode.CYCLING -> bikePreferPaved
        TravelMode.WALKING -> false
    }

    /** Short summary for messages, e.g. "No motorways · No ferries". */
    fun summary(mode: TravelMode): String {
        val parts = mutableListOf<String>()
        when (mode) {
            TravelMode.DRIVING -> {
                if (vehicle != Vehicle.CAR) parts.add(vehicle.label)
                if (carAvoidMotorways) parts.add("No motorways")
                if (carAvoidFerries) parts.add("No ferries")
                if (carAvoidFords) parts.add("No fords")
                if (carPreferPaved) parts.add("Paved")
            }
            TravelMode.CYCLING -> {
                parts.add(bikeType.label)
                parts.add("Hills: ${bikeHills.label}")
                if (bikeAvoidSteps) parts.add("No steps")
                if (bikeAvoidFerries) parts.add("No ferries")
                if (bikePreferPaved) parts.add("Paved")
            }
            TravelMode.WALKING -> {
                parts.add(walkType.label)
                if (walkGreen) parts.add("Green")
                if (walkQuiet) parts.add("Quiet")
                if (walkAvoidSteps) parts.add("No steps")
                if (walkAvoidFerries) parts.add("No ferries")
            }
        }
        return parts.joinToString(" · ")
    }

    fun toJson(): String = JSONObject()
        .put("vehicle", vehicle.name)
        .put("carAvoidMotorways", carAvoidMotorways)
        .put("carAvoidFerries", carAvoidFerries)
        .put("carAvoidFords", carAvoidFords)
        .put("carPreferPaved", carPreferPaved)
        .put("bikeType", bikeType.name)
        .put("bikeHills", bikeHills.name)
        .put("bikeAvoidSteps", bikeAvoidSteps)
        .put("bikeAvoidFerries", bikeAvoidFerries)
        .put("bikePreferPaved", bikePreferPaved)
        .put("walkType", walkType.name)
        .put("walkGreen", walkGreen)
        .put("walkQuiet", walkQuiet)
        .put("walkAvoidSteps", walkAvoidSteps)
        .put("walkAvoidFerries", walkAvoidFerries)
        .toString()

    companion object {
        /** Lenient: unknown or missing fields fall back to defaults. */
        fun fromJson(text: String?): RouteOptions {
            if (text.isNullOrBlank()) return RouteOptions()
            return try {
                val j = JSONObject(text)
                val d = RouteOptions()
                RouteOptions(
                    vehicle = enumOr(j.optString("vehicle"), d.vehicle),
                    carAvoidMotorways = j.optBoolean("carAvoidMotorways", d.carAvoidMotorways),
                    carAvoidFerries = j.optBoolean("carAvoidFerries", d.carAvoidFerries),
                    carAvoidFords = j.optBoolean("carAvoidFords", d.carAvoidFords),
                    carPreferPaved = j.optBoolean("carPreferPaved", d.carPreferPaved),
                    bikeType = enumOr(j.optString("bikeType"), d.bikeType),
                    bikeHills = enumOr(j.optString("bikeHills"), d.bikeHills),
                    bikeAvoidSteps = j.optBoolean("bikeAvoidSteps", d.bikeAvoidSteps),
                    bikeAvoidFerries = j.optBoolean("bikeAvoidFerries", d.bikeAvoidFerries),
                    bikePreferPaved = j.optBoolean("bikePreferPaved", d.bikePreferPaved),
                    walkType = enumOr(j.optString("walkType"), d.walkType),
                    walkGreen = j.optBoolean("walkGreen", d.walkGreen),
                    walkQuiet = j.optBoolean("walkQuiet", d.walkQuiet),
                    walkAvoidSteps = j.optBoolean("walkAvoidSteps", d.walkAvoidSteps),
                    walkAvoidFerries = j.optBoolean("walkAvoidFerries", d.walkAvoidFerries)
                )
            } catch (e: Exception) {
                RouteOptions()
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}

internal fun jsonArrayOf(items: List<String>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }
