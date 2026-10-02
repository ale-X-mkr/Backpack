package com.backpack.app.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun elevation from date and position (standard low-precision solar
 * formulas, ~0.1° accuracy). Computed on the phone: no sunrise service,
 * nothing sent anywhere.
 */
object SunPosition {

    /** Sun elevation above the horizon in degrees. */
    fun elevation(lat: Double, lon: Double, timeMs: Long): Double {
        val n = timeMs / 86400000.0 + 2440587.5 - 2451545.0 // days since J2000.0
        val l = norm360(280.460 + 0.9856474 * n)
        val g = Math.toRadians(norm360(357.528 + 0.9856003 * n))
        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = Math.toDegrees(atan2(cos(eps) * sin(lambda), cos(lambda)))
        val dec = asin(sin(eps) * sin(lambda))
        val gmstHours = ((18.697374558 + 24.06570982441908 * n) % 24.0 + 24.0) % 24.0
        val hourAngle = Math.toRadians(norm360(gmstHours * 15.0 + lon - ra))
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(hourAngle)))
    }

    /** Night map from just after sunset until just before sunrise. */
    fun isNight(lat: Double, lon: Double, timeMs: Long): Boolean = elevation(lat, lon, timeMs) < -2.0

    private fun norm360(d: Double) = ((d % 360.0) + 360.0) % 360.0
}
