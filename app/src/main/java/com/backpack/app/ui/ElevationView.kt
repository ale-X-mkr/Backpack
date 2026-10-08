package com.backpack.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 1.3.0: the route's height profile (filled), lowest and highest point written. */
class ElevationView(context: Context, private val points: List<Pair<Double, Double>>, private val metric: Boolean) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#661E8BD0"); style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E8BD0"); style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#707070"); textSize = 11f * d }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val left = 4f * d
        val top = 16f * d
        val w = width - 2 * left
        val h = height - top - 16f * d
        val maxX = points.last().first.coerceAtLeast(1.0)
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val span = (hi - lo).coerceAtLeast(20.0) // flat routes stay flat
        fun x(v: Double) = left + (v / maxX * w).toFloat()
        fun y(v: Double) = top + (h - ((v - lo) / span * h)).toFloat()
        val p = Path()
        p.moveTo(x(points[0].first), y(points[0].second))
        for (pt in points.drop(1)) p.lineTo(x(pt.first), y(pt.second))
        val f = Path(p)
        f.lineTo(x(maxX), top + h)
        f.lineTo(left, top + h)
        f.close()
        canvas.drawPath(f, fill)
        canvas.drawPath(p, line)
        fun hLabel(v: Double) = if (metric) "${v.toInt()} m" else "${(v * 3.28084).toInt()} ft"
        canvas.drawText("▲ " + hLabel(hi), left, 12f * d, text)
        canvas.drawText("▼ " + hLabel(lo), left, height - 3f * d, text)
        val dist = if (metric) "%.1f km".format(maxX / 1000.0) else "%.1f mi".format(maxX / 1609.344)
        canvas.drawText(dist, width - left - text.measureText(dist), height - 3f * d, text)
    }
}
