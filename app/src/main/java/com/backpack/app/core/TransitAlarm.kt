package com.backpack.app.core

/**
 * 1.1.0: wake-up alarm on public transport (bus, train). No timetable and no
 * transit routing: the stop you set, your GPS position and your measured
 * speed. The GPS is asked rarely while far away (battery), more often when
 * close.
 */
object TransitAlarm {

    /** When to ring: so many minutes before, or so many km before. */
    data class Lead(val minutes: Int = 0, val km: Int = 0) {
        val label: String get() = if (minutes > 0) "$minutes min before" else "$km km before"
    }

    val LEADS = listOf(Lead(minutes = 2), Lead(minutes = 5), Lead(minutes = 10), Lead(km = 1), Lead(km = 2), Lead(km = 5))
    val DEFAULT_LEAD = Lead(minutes = 5)

    /** Roads and tracks are longer than the straight line: estimate of the
     *  distance still to travel (rail and bus lines bend ~20-30 %). */
    const val TRACK_FACTOR = 1.25

    /** Always ring this close, whatever the lead (missed fixes, slow end). */
    const val SAFETY_M = 400.0

    fun trackDistance(straightM: Double): Double = straightM * TRACK_FACTOR

    /** Seconds to the stop at [speedMps], or null when not really moving. */
    fun etaSeconds(trackM: Double, speedMps: Double?): Double? =
        if (speedMps == null || speedMps < 2.0) null else trackM / speedMps

    fun shouldRing(trackM: Double, speedMps: Double?, lead: Lead): Boolean {
        if (trackM <= SAFETY_M) return true
        if (lead.km > 0) return trackM <= lead.km * 1000.0
        val eta = etaSeconds(trackM, speedMps) ?: return false
        return eta <= lead.minutes * 60.0
    }

    /**
     * Next GPS interval: a quarter of the time left before the alarm point,
     * between 10 s and 5 min (unknown speed: assume 20 m/s, i.e. fast, so the
     * check comes early rather than late).
     */
    fun nextPaceMs(trackM: Double, speedMps: Double?, lead: Lead): Long {
        val v = speedMps?.takeIf { it >= 2.0 } ?: 20.0
        val ringAtM = maxOf(SAFETY_M, if (lead.km > 0) lead.km * 1000.0 else lead.minutes * 60.0 * v)
        val secondsLeft = (trackM - ringAtM) / v
        return (secondsLeft / 4 * 1000).toLong().coerceIn(10_000L, 300_000L)
    }

    /** Smoothed travel speed from GPS fixes (stops at stations pull it down only slowly). */
    class Speed {
        var value: Double? = null
            private set

        fun add(mps: Double) {
            if (mps < 0.5) { value = value?.let { it * 0.9 }; return }
            value = value?.let { it * 0.7 + mps * 0.3 } ?: mps
        }
    }
}
