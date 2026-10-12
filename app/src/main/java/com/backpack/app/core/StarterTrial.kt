package com.backpack.app.core

/**
 * 1.7.0: the shared starter key (APK for others, "share" build only): new
 * users can plan routes at once, for [DAYS] days or [TOTAL] routes, at most
 * [PER_DAY] a day, counted on the phone. After that, their own free key
 * (key guide). Days are local calendar days (epoch day numbers). Plain JVM.
 */
object StarterTrial {
    const val DAYS = 30
    const val TOTAL = 200
    const val PER_DAY = 40

    data class State(val firstDay: Long, val total: Int, val day: Long, val dayCount: Int)

    enum class Verdict { OK, DAILY_LIMIT, USED_UP, EXPIRED }

    fun verdict(s: State?, today: Long): Verdict {
        if (s == null) return Verdict.OK
        if (today - s.firstDay >= DAYS) return Verdict.EXPIRED
        if (s.total >= TOTAL) return Verdict.USED_UP
        if (s.day == today && s.dayCount >= PER_DAY) return Verdict.DAILY_LIMIT
        return Verdict.OK
    }

    /** One more route planned with the starter key. */
    fun after(s: State?, today: Long): State {
        if (s == null) return State(today, 1, today, 1)
        val dayCount = if (s.day == today) s.dayCount + 1 else 1
        return State(s.firstDay, s.total + 1, today, dayCount)
    }

    fun encode(s: State): String = "${s.firstDay},${s.total},${s.day},${s.dayCount}"

    fun decode(v: String?): State? {
        val p = v?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return null
        return if (p.size == 4) State(p[0], p[1].toInt(), p[2], p[3].toInt()) else null
    }
}
