package com.backpack.app.core

/**
 * The speed-limit alarm rules (unchanged since 1.0.x, only moved here in
 * 1.3.0 so they also run with the screen off):
 * green 5+ under the limit, yellow within 5 under, red up to 5 over,
 * blinking red + high beep beyond; on roads of 100+: blue + low beep when
 * 15+ under, red + low beep when stopped.
 */
object SpeedAlarm {
    enum class Ring { GREY, GREEN, YELLOW, RED, BLINK_RED, BLUE, STOPPED_RED }
    enum class Beep { NONE, HIGH, LOW }
    data class State(val ring: Ring, val beep: Beep)

    fun state(speedKmh: Int, hasRealMovement: Boolean, limit: Int?): State {
        if (limit == null) return State(Ring.GREY, Beep.NONE)
        val diff = speedKmh - limit
        return when {
            !hasRealMovement && limit >= 100 -> State(Ring.STOPPED_RED, Beep.LOW)
            !hasRealMovement -> State(Ring.GREY, Beep.NONE)
            limit >= 100 && diff <= -15 -> State(Ring.BLUE, Beep.LOW)
            diff > 5 -> State(Ring.BLINK_RED, Beep.HIGH)
            diff > 0 -> State(Ring.RED, Beep.NONE)
            diff > -5 -> State(Ring.YELLOW, Beep.NONE)
            else -> State(Ring.GREEN, Beep.NONE)
        }
    }
}
