package com.backpack.app.core

/**
 * The speed-limit alarm rules (moved here in 1.3.0 so they also run with
 * the screen off; 1.4.0 changes asked by Alex):
 * green 5+ under the limit, yellow within 5 under, red up to 5 over,
 * blinking red + the high "priiip" beyond 5 over, the same beep repeated
 * without pause beyond 20 over; on roads of 100+: blue + a low buzz below
 * 70 % of the limit, red + low buzz when stopped.
 * 1.7.2 (Alex, symmetrical to the high beep): more than 20 km/h under that
 * 70 %, the low buzz repeats without pause.
 */
object SpeedAlarm {
    enum class Ring { GREY, GREEN, YELLOW, RED, BLINK_RED, BLUE, STOPPED_RED }
    enum class Beep { NONE, HIGH, LOW, CONTINUOUS, LOW_CONTINUOUS }
    data class State(val ring: Ring, val beep: Beep)

    fun state(speedKmh: Int, hasRealMovement: Boolean, limit: Int?): State {
        if (limit == null) return State(Ring.GREY, Beep.NONE)
        val diff = speedKmh - limit
        return when {
            !hasRealMovement && limit >= 100 -> State(Ring.STOPPED_RED, Beep.LOW)
            !hasRealMovement -> State(Ring.GREY, Beep.NONE)
            limit >= 100 && speedKmh < limit * 0.7 - 20 -> State(Ring.BLUE, Beep.LOW_CONTINUOUS)
            limit >= 100 && speedKmh < limit * 0.7 -> State(Ring.BLUE, Beep.LOW)
            diff > 20 -> State(Ring.BLINK_RED, Beep.CONTINUOUS)
            diff > 5 -> State(Ring.BLINK_RED, Beep.HIGH)
            diff > 0 -> State(Ring.RED, Beep.NONE)
            diff > -5 -> State(Ring.YELLOW, Beep.NONE)
            else -> State(Ring.GREEN, Beep.NONE)
        }
    }

    // ---------- 1.4.0 the ring as a gauge ----------

    /** Gauge from the lower left (8 o'clock) clockwise: 240° up to the limit
     *  (at 4 o'clock, the top is half of it), then red over it up to +30 km/h (6 o'clock). */
    const val START_DEG = 150f
    const val LEGAL_SWEEP = 240f
    const val OVER_SWEEP = 60f

    /** Degrees filled below / over the limit. */
    fun sweep(speedKmh: Double, limit: Int): Pair<Float, Float> {
        if (limit <= 0) return 0f to 0f
        val legal = (speedKmh / limit).coerceIn(0.0, 1.0) * LEGAL_SWEEP
        val over = ((speedKmh - limit) / 30.0).coerceIn(0.0, 1.0) * OVER_SWEEP
        return legal.toFloat() to over.toFloat()
    }

    /** Colour at [f] (0..1 of the way to the limit): blue -> green (top) -> yellow. ARGB. */
    fun colorAt(f: Double): Int {
        val stops = listOf(0.0 to 0xFF1565C0.toInt(), 0.5 to 0xFF2E7D32.toInt(), 0.85 to 0xFFF1C40F.toInt(), 1.0 to 0xFFF39C12.toInt())
        val x = f.coerceIn(0.0, 1.0)
        for (i in 1 until stops.size) {
            val (b, cb) = stops[i]
            val (a, ca) = stops[i - 1]
            if (x <= b) return mix(ca, cb, ((x - a) / (b - a)).toFloat())
        }
        return stops.last().second
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
