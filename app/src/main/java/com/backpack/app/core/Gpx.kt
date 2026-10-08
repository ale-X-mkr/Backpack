package com.backpack.app.core

/**
 * 1.3.0 trip recording: a recorded track and its GPX text (GPX 1.1, the
 * format every map and hiking app reads). Pure, no Android.
 */
object Gpx {

    /** One recorded point: position, height (m, NaN when unknown), time (ms). */
    data class Point(val lat: Double, val lon: Double, val ele: Double, val timeMs: Long)

    fun write(name: String, points: List<Point>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Backpack\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        for (p in points) {
            sb.append("      <trkpt lat=\"%.6f\" lon=\"%.6f\">".format(java.util.Locale.ROOT, p.lat, p.lon))
            if (!p.ele.isNaN()) sb.append("<ele>%.1f</ele>".format(java.util.Locale.ROOT, p.ele))
            sb.append("<time>").append(iso.format(java.util.Date(p.timeMs))).append("</time></trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Length of a track (m). */
    fun length(points: List<Point>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geo.distance(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon)) }

    /** Compact storage on the phone: "lat,lon,ele,time" per line. */
    fun toLines(points: List<Point>): String = points.joinToString("\n") {
        "%.6f,%.6f,%.1f,%d".format(java.util.Locale.ROOT, it.lat, it.lon, if (it.ele.isNaN()) -9999.0 else it.ele, it.timeMs)
    }

    fun fromLines(text: String): List<Point> = text.lineSequence().mapNotNull { l ->
        val v = l.split(',')
        if (v.size != 4) null else try {
            val e = v[2].toDouble()
            Point(v[0].toDouble(), v[1].toDouble(), if (e <= -9999.0) Double.NaN else e, v[3].toLong())
        } catch (e: Exception) { null }
    }.toList()

    /** Keep a point when moved [minStepM] or [maxGapMs] passed since the last one kept. */
    fun keep(last: Point?, next: Point, minStepM: Double = 10.0, maxGapMs: Long = 30_000L): Boolean =
        last == null || next.timeMs - last.timeMs >= maxGapMs ||
            Geo.distance(LatLon(last.lat, last.lon), LatLon(next.lat, next.lon)) >= minStepM

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
