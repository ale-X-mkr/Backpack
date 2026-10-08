package com.backpack.app.core

/**
 * 1.3.0 "Open now": the common part of OpenStreetMap's opening_hours.
 * Understood: "24/7"; rules separated by ";" (a later rule replaces an
 * earlier one for its days); days "Mo-Fr", "Sa,Su", "Mo-We,Fr"; times
 * "08:00-12:00,14:00-18:30", past midnight "22:00-02:00", open end "18:00+";
 * "off" / "closed"; holiday rules ("PH off") are left out. Anything else (months, holidays, sunrise...) gives
 * null = unknown, never a wrong "closed".
 */
object OpeningHours {

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    private class Rule(val days: Set<Int>, val ranges: List<IntArray>, val off: Boolean)

    /** [day] 0 = Monday .. 6 = Sunday; [minute] of the day (0..1439). */
    fun isOpen(spec: String?, day: Int, minute: Int): Boolean? {
        val text = spec?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text == "24/7") return true
        val rules = parse(text) ?: return null
        // Last rule naming today wins; yesterday's last rule for a range past midnight.
        val today = rules.lastOrNull { day in it.days }
        val yesterday = rules.lastOrNull { (day + 6) % 7 in it.days }
        if (yesterday != null && !yesterday.off) {
            for (r in yesterday.ranges) if (r[1] > 1440 && minute < r[1] - 1440) return true
        }
        if (today == null) return false
        if (today.off) return false
        return today.ranges.any { minute >= it[0] && minute < it[1] }
    }

    private fun parse(text: String): List<Rule>? {
        if ("||" in text) return null
        val out = ArrayList<Rule>()
        for (raw in text.split(';')) {
            var part = raw.trim()
            if (part.isEmpty()) continue
            var off = false
            if (part.endsWith(" off") || part.endsWith(" closed") || part == "off" || part == "closed") {
                off = true
                part = part.removeSuffix("off").removeSuffix("closed").trim()
            }
            // Days first (optional), then times (optional with "off").
            val tokens = part.split(' ').filter { it.isNotBlank() }
            var days: Set<Int> = (0..6).toSet()
            var timesText: String? = null
            when (tokens.size) {
                0 -> if (!off) return null
                1 -> if (tokens[0][0].isDigit()) timesText = tokens[0] else days = parseDays(tokens[0]) ?: return null
                2 -> { days = parseDays(tokens[0]) ?: return null; timesText = tokens[1] }
                else -> return null
            }
            // Public / school holidays can't be known here: such rules are left out.
            if (days.isEmpty()) continue
            val ranges = if (timesText == null) {
                if (!off) listOf(intArrayOf(0, 1440)) else emptyList()   // "Sa" alone = all day
            } else parseTimes(timesText) ?: return null
            out.add(Rule(days, ranges, off))
        }
        return out.ifEmpty { null }
    }

    private fun parseDays(s: String): Set<Int>? {
        val out = HashSet<Int>()
        for (item in s.split(',')) {
            if (item == "PH" || item == "SH") continue
            val ab = item.split('-')
            val a = DAYS.indexOf(ab[0])
            if (a < 0) return null
            if (ab.size == 1) { out.add(a); continue }
            if (ab.size != 2) return null
            val b = DAYS.indexOf(ab[1])
            if (b < 0) return null
            var d = a
            while (true) { out.add(d); if (d == b) break; d = (d + 1) % 7 }
        }
        return out
    }

    private fun parseTimes(s: String): List<IntArray>? {
        val out = ArrayList<IntArray>()
        for (item in s.split(',')) {
            if (item.endsWith("+")) { val a = minutes(item.dropLast(1)) ?: return null; out.add(intArrayOf(a, 1440)); continue }
            val ab = item.split('-')
            if (ab.size != 2) return null
            val a = minutes(ab[0]) ?: return null
            var b = minutes(ab[1]) ?: return null
            if (b <= a) b += 1440 // past midnight
            out.add(intArrayOf(a, b))
        }
        return out
    }

    private fun minutes(t: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 24 || mi > 59) return null
        return h * 60 + mi
    }

    /** Now, in the phone's time zone. */
    fun isOpenNow(spec: String?): Boolean? {
        val c = java.util.Calendar.getInstance()
        val day = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        return isOpen(spec, day, c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE))
    }
}
