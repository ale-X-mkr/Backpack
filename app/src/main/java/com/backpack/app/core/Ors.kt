package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * openrouteservice request building and response parsing. No network
 * code here (that lives in RoutePlanner) so it can be unit-tested.
 */
object Ors {

    /** ORS surface codes counted as unpaved (2 unpaved, 8 compacted
     *  gravel, 10 gravel, 11 dirt, 12 ground, 13 ice, 15 sand, 17 grass,
     *  18 grass paver; 5/9/16 are retired codes, kept for old servers). */
    private val UNPAVED_SURFACES = setOf(2, 5, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18)

    fun directionsBody(
        coordinates: List<LatLon>,
        preference: String,
        avoidFeatures: List<String>,
        profileParams: JSONObject?,
        extraInfo: List<String>,
        alternatives: Boolean,
        /** 1.3.0: instructions in the voice language (en, es, fr, it, de). */
        language: String = "en",
        /** 1.3.0: heights along the route (walking / cycling profile). */
        elevation: Boolean = false
    ): JSONObject {
        val coords = JSONArray()
        for (c in coordinates) coords.put(JSONArray().put(c.lon).put(c.lat))
        val body = JSONObject()
            .put("coordinates", coords)
            .put("preference", preference)
            .put("instructions", true)
            .put("language", language)
            .put("units", "m")
        if (avoidFeatures.isNotEmpty() || profileParams != null) {
            val options = JSONObject()
            if (avoidFeatures.isNotEmpty()) options.put("avoid_features", jsonArrayOf(avoidFeatures))
            if (profileParams != null) options.put("profile_params", profileParams)
            body.put("options", options)
        }
        if (extraInfo.isNotEmpty()) body.put("extra_info", jsonArrayOf(extraInfo))
        if (elevation) body.put("elevation", true)
        // ORS only computes alternatives between exactly two points.
        if (alternatives && coordinates.size == 2) {
            body.put(
                "alternative_routes",
                JSONObject().put("target_count", 2).put("weight_factor", 1.6)
            )
        }
        return body
    }

    /** Parses a /v2/directions/{profile}/geojson response. */
    fun parseRoutes(responseBody: String): List<RouteResult> {
        val json = JSONObject(responseBody)
        val features = json.getJSONArray("features")
        val results = mutableListOf<RouteResult>()

        for (f in 0 until features.length()) {
            val feature = features.getJSONObject(f)
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<LatLon>(coordinates.length())
            val heights = ArrayList<Double>()
            for (i in 0 until coordinates.length()) {
                val pair = coordinates.getJSONArray(i)
                points.add(LatLon(pair.getDouble(1), pair.getDouble(0)))
                // 1.3.0: [lon, lat, height] when elevation was asked.
                if (pair.length() >= 3) heights.add(pair.getDouble(2))
            }

            val properties = feature.getJSONObject("properties")
            val summary = properties.getJSONObject("summary")
            // A zero-length route (start == end) has no distance/duration keys.
            val distanceMeters = summary.optDouble("distance", 0.0)
            val durationSeconds = summary.optDouble("duration", 0.0)

            val extras = properties.optJSONObject("extras")
            val tollDistance = sumExtra(extras, "tollways") { it == 1 }
            val unpavedDistance = sumExtra(extras, "surface") { it in UNPAVED_SURFACES }

            val steps = mutableListOf<NavStep>()
            val segments = properties.optJSONArray("segments")
            if (segments != null) {
                for (s in 0 until segments.length()) {
                    val stepsArray = segments.getJSONObject(s).optJSONArray("steps") ?: continue
                    for (st in 0 until stepsArray.length()) {
                        val step = stepsArray.getJSONObject(st)
                        val wayPoints = step.getJSONArray("way_points")
                        val name = step.optString("name", "")
                        steps.add(
                            NavStep(
                                instruction = step.optString("instruction", "Continue"),
                                maneuver = Maneuver.fromOrs(step.optInt("type", -1)),
                                distanceMeters = step.optDouble("distance", 0.0),
                                durationSeconds = step.optDouble("duration", 0.0),
                                wayStart = wayPoints.getInt(0),
                                wayEnd = wayPoints.getInt(1),
                                // ORS uses "-" for unnamed roads.
                                streetName = if (name == "-") "" else name,
                                exitNumber = step.optInt("exit_number", 0)
                            )
                        )
                    }
                }
            }
            val elevations = if (heights.size == points.size) heights else emptyList()
            results.add(RouteResult(points, distanceMeters, durationSeconds, tollDistance, unpavedDistance, steps,
                elevations, properties.optDouble("ascent", 0.0), properties.optDouble("descent", 0.0)))
        }
        return results
    }

    private fun sumExtra(extras: JSONObject?, key: String, match: (Int) -> Boolean): Double {
        val summary = extras?.optJSONObject(key)?.optJSONArray("summary") ?: return 0.0
        var sum = 0.0
        for (i in 0 until summary.length()) {
            val entry = summary.getJSONObject(i)
            if (match(entry.optInt("value", -1))) sum += entry.optDouble("distance", 0.0)
        }
        return sum
    }

    /** ORS error code inside an error body, if any. */
    fun errorCode(body: String?): Int? = try {
        body?.let { JSONObject(it).getJSONObject("error").getInt("code") }
    } catch (e: Exception) {
        null
    }

    fun friendlyError(httpCode: Int, body: String?): String = when (errorCode(body)) {
        2010 -> "No road found near that point -- try tapping closer to a street"
        2009 -> "No route possible between these points"
        2004 -> "Route too long for the routing server"
        2003 -> "Route option not supported for this mode"
        else -> when (httpCode) {
            401, 403 -> "Routing key refused or daily limit reached"
            429 -> "Routing server busy -- try again in a minute"
            else -> "Route request failed (error $httpCode)"
        }
    }
}

/**
 * The Balanced driving rule, unchanged from Step 38: compare a toll and a
 * toll-free route, value the time saved with the current urgency mode's
 * EUR/minute, and take the toll only when the time is worth more than the
 * estimated toll.
 */
object TollBalance {
    fun plan(
        withToll: RouteResult?,
        withoutToll: RouteResult?,
        valuePerMinute: Double,
        tollRatePerKm: Double
    ): RoutePlan? {
        if (withToll == null && withoutToll == null) return null
        if (withToll == null) return RoutePlan(listOf(withoutToll!!), 0, null)
        if (withoutToll == null || withToll.tollDistanceMeters <= 0.0) {
            // No toll road involved at all -- nothing to balance.
            return RoutePlan(listOf(withToll), 0, null)
        }
        val estimatedTollCost = withToll.tollDistanceMeters / 1000.0 * tollRatePerKm
        val timeSavedMinutes = (withoutToll.durationSeconds - withToll.durationSeconds) / 60.0
        val valueOfTimeSaved = timeSavedMinutes * valuePerMinute
        val takeToll = valueOfTimeSaved >= estimatedTollCost
        val note = if (takeToll) {
            "Toll worth it: saves ~%.0f min for an est. €%.0f".format(timeSavedMinutes, estimatedTollCost)
        } else {
            "Skipping toll: only saves ~%.0f min for est. €%.0f".format(timeSavedMinutes, estimatedTollCost)
        }
        val primary = if (takeToll) withToll else withoutToll
        val secondary = if (takeToll) withoutToll else withToll
        return RoutePlan(listOf(primary, secondary), 0, note, tollDecision = true)
    }
}

/**
 * 1.4.0 Balanced: "a few minutes more could be a better route". Among the
 * alternatives, the shortest one that costs at most [extraFraction] more
 * time than the fastest and is clearly shorter (3 % and 500 m), with no
 * more toll road. 0 = always the fastest (as before).
 */
object ShorterPick {
    fun pick(routes: List<RouteResult>?, extraFraction: Double): Int {
        if (routes == null || routes.size < 2 || extraFraction <= 0.0) return 0
        val base = routes[0]
        var best = 0
        for (i in 1 until routes.size) {
            val r = routes[i]
            if (r.points.isEmpty()) continue
            if (r.durationSeconds > base.durationSeconds * (1.0 + extraFraction)) continue
            if (r.tollDistanceMeters > base.tollDistanceMeters + 100.0) continue
            val saved = base.distanceMeters - r.distanceMeters
            if (saved < 500.0 || saved < base.distanceMeters * 0.03) continue
            if (r.distanceMeters < routes[best].distanceMeters) best = i
        }
        return best
    }

    fun note(fastest: RouteResult, chosen: RouteResult, metric: Boolean = true): String =
        "Shorter: %.0f %s less for %.0f min more".format(
            (fastest.distanceMeters - chosen.distanceMeters) / (if (metric) 1000.0 else 1609.344),
            if (metric) "km" else "mi",
            Math.max(1.0, (chosen.durationSeconds - fastest.durationSeconds) / 60.0)
        )
}

/** "Prefer paved": ORS cannot avoid unpaved roads for car or bike, so
 *  among the returned alternatives pick the one with the least unpaved
 *  distance, unless it costs more than 25 % extra time. */
object RoutePicker {
    fun preferPaved(routes: List<RouteResult>): RoutePlan {
        if (routes.size < 2) return RoutePlan(routes, 0, null)
        val base = routes[0]
        var bestIndex = 0
        for (i in routes.indices) {
            val r = routes[i]
            if (r.durationSeconds > base.durationSeconds * 1.25) continue
            if (r.unpavedDistanceMeters < routes[bestIndex].unpavedDistanceMeters - 50.0) bestIndex = i
        }
        if (bestIndex == 0) return RoutePlan(routes, 0, null)
        val chosen = routes[bestIndex]
        val note = "Paved preferred: %.0f%% unpaved instead of %.0f%%".format(
            chosen.unpavedFraction * 100, base.unpavedFraction * 100
        )
        return RoutePlan(routes, bestIndex, note)
    }
}
