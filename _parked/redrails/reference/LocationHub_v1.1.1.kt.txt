package com.backpack.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * The app's only source of position: the phone's GPS chip.
 *
 * - GPS_PROVIDER only. No network / Wi-Fi / cell location (on most phones
 *   that is Google's location service, which reports to Google).
 * - PASSIVE_PROVIDER as a free extra: when another app already runs the
 *   GPS, its GPS-chip fixes are reused (provider == "gps" only), at no
 *   battery cost and with nothing sent anywhere.
 *
 * Shared by the screen (map, speed sign) and the navigation service
 * through "leases": GPS runs while at least one lease is held, faster
 * (1 s) while navigating.
 */
object LocationHub {

    fun interface Listener {
        /** [plausible] = false for noisy readings (poor accuracy or an
         *  impossible jump) -- shown as a GPS warning, never navigated on. */
        fun onFix(location: Location, plausible: Boolean)
    }

    const val LEASE_UI = "ui"
    const val LEASE_NAV = "nav"
    /** 1.1.0: transit wake-up alarm (slow, adaptive). */
    const val LEASE_TRANSIT = "transit"

    /**
     * 1.1.0 battery: how often the GPS chip is asked. Navigation sets its
     * pace by mode and movement (driving 1 s, cycling 2 s, walking 3 s,
     * stopped 5 s); the transit alarm by distance to the stop (10 s .. 5 min);
     * the map alone 5 s / 5 m. The fastest wanted pace wins.
     */
    private var navPace = 1000L
    private var transitPace = 30_000L
    private const val UI_PACE = 5000L

    /** Interval currently asked of the GPS (ms); 0 when off. */
    var currentIntervalMs = 0L
        private set

    fun setNavPace(c: Context, ms: Long) {
        if (navPace == ms) return
        navPace = ms
        refresh(c.applicationContext)
    }

    fun setTransitPace(c: Context, ms: Long) {
        if (transitPace == ms) return
        transitPace = ms
        refresh(c.applicationContext)
    }

    private val leases = mutableSetOf<String>()
    private val listeners = mutableListOf<Listener>()
    private var manager: LocationManager? = null
    private var registered: Pair<Long, Float>? = null
    private var lastDeliveredTime = 0L

    var lastFix: Location? = null
        private set
    var lastGoodFix: Location? = null
        private set
    /** Last bearing measured while actually moving (survives screen rebuilds). */
    var lastMovingBearing: Float? = null
        private set

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val passiveListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Only fixes that came from the GPS chip (another app's GPS).
            if (location.provider == LocationManager.GPS_PROVIDER) deliver(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(c: Context) = ContextCompat.checkSelfPermission(
        c, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun acquire(c: Context, lease: String) {
        leases.add(lease)
        refresh(c.applicationContext)
    }

    fun release(c: Context, lease: String) {
        leases.remove(lease)
        refresh(c.applicationContext)
    }

    fun isGpsEnabled(c: Context): Boolean = try {
        (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) { false }

    /** Last GPS-chip position Android remembers (no network fallback). */
    @SuppressLint("MissingPermission")
    fun lastKnownGps(c: Context): Location? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
        val passive = try {
            lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?.takeIf { it.provider == LocationManager.GPS_PROVIDER }
        } catch (e: Exception) { null }
        return listOfNotNull(lastGoodFix, gps, passive).maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun refresh(c: Context) {
        val lm = manager ?: (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { manager = it }
        if (leases.isEmpty() || !hasPermission(c)) {
            stop(lm)
            return
        }
        val wanted = listOfNotNull(
            if (LEASE_NAV in leases) navPace to 0f else null,
            if (LEASE_TRANSIT in leases) transitPace to 0f else null,
            if (LEASE_UI in leases) UI_PACE to 5f else null
        ).minByOrNull { it.first } ?: return
        if (registered == wanted) return
        stop(lm)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, wanted.first, wanted.second, gpsListener, Looper.getMainLooper())
        } catch (e: Exception) {
            // No GPS hardware or provider disabled: nothing else to fall back on, by design.
        }
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, passiveListener, Looper.getMainLooper())
        } catch (e: Exception) { }
        registered = wanted
        currentIntervalMs = wanted.first
    }

    private fun stop(lm: LocationManager) {
        try { lm.removeUpdates(gpsListener) } catch (e: Exception) { }
        try { lm.removeUpdates(passiveListener) } catch (e: Exception) { }
        registered = null
        currentIntervalMs = 0L
    }

    private fun deliver(location: Location) {
        // Own GPS fixes also arrive through the passive provider: drop duplicates.
        if (location.time <= lastDeliveredTime) return
        lastDeliveredTime = location.time
        lastFix = location
        val plausible = isPlausible(location)
        if (plausible) {
            lastGoodFix = location
            if (location.hasBearing() && location.hasSpeed() && location.speed > 0.7f) lastMovingBearing = location.bearing
        }
        for (l in listeners.toList()) l.onFix(location, plausible)
    }

    /** Same rule as Step 38: reject accuracy worse than 50 m, or a jump
     *  implying more than 55 m/s (~200 km/h) from the last good fix. */
    private fun isPlausible(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > 50f) return false
        val previous = lastGoodFix ?: return true
        val elapsedSeconds = (location.time - previous.time) / 1000.0
        if (elapsedSeconds <= 0) return true
        return previous.distanceTo(location) / elapsedSeconds <= 55.0
    }
}
