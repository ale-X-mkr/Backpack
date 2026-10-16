package com.backpack.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.backpack.app.core.SpeedAlarm

/**
 * 1.4.0: the speed circle's ring as a gauge (Alex). Grey ring; an arc grows
 * clockwise from the lower left with your speed, the limit at the lower
 * right; 1.8.0: the arc is one colour, your speed's (blue, green at half the
 * limit, yellow near it, red from the limit).
 * Over the limit the arc goes on in red, growing with your speed.
 * 1.7.2 (Alex): the ring itself turns red / blue only while an alarm
 * sound plays ([flashColor], set in step with each beep or buzz).
 */
class SpeedGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val ringW = 5f * d
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringW }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringW; strokeCap = Paint.Cap.BUTT }
    private val box = RectF()

    private var speedKmh = 0.0
    private var limit: Int? = null
    /** Ring colour when no gauge is drawn (walking / cycling, no limit known). */
    private var plainColor = 0xFF9E9E9E.toInt()
    /** The ring's colour while an alarm sound plays; null = the usual ring. */
    var flashColor: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    fun show(speedKmh: Double, limit: Int?, plain: Int = 0xFF9E9E9E.toInt()) {
        this.speedKmh = speedKmh
        this.limit = limit
        this.plainColor = plain
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = ringW / 2 + 0.5f
        box.set(inset, inset, width - inset, height - inset)
        val l = limit
        ring.color = flashColor ?: if (l == null) plainColor else 0xFFBDBDBD.toInt()
        canvas.drawOval(box, ring)
        if (l == null) return
        val (legal, overSweep) = SpeedAlarm.sweep(speedKmh, l)
        // 1.8.0 (Alex): the whole arc in ONE colour, your speed's: blue -> green
        // -> yellow towards the limit, red from the limit on (the arc keeps growing).
        arc.color = if (speedKmh > l) 0xFFB71C1C.toInt() else SpeedAlarm.colorAt(legal / SpeedAlarm.LEGAL_SWEEP.toDouble())
        if (legal + overSweep > 0f) canvas.drawArc(box, SpeedAlarm.START_DEG, legal + overSweep, false, arc)
    }
}
