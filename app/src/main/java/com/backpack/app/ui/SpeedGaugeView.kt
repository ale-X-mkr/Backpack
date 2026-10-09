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
 * 1.4.0: the speed circle's ring as a gauge (Alex). Grey ring; a coloured
 * arc grows clockwise from the lower left with your speed: blue, green at
 * the top (half the limit), yellow near it, the limit at the lower right.
 * Over the limit the arc goes on in red and the whole ring turns red;
 * blinking (set from outside) flashes it red / white.
 */
class SpeedGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val ringW = 5f * d
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringW }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringW; strokeCap = Paint.Cap.BUTT }
    private val box = RectF()

    private var speedKmh = 0.0
    private var limit: Int? = null
    private var over = false
    /** Ring colour when no gauge is drawn (walking / cycling, no limit known). */
    private var plainColor = 0xFF9E9E9E.toInt()
    /** null = no blinking; true / false = the two phases. */
    var blinkPhase: Boolean? = null
        set(v) { field = v; invalidate() }

    fun show(speedKmh: Double, limit: Int?, plain: Int = 0xFF9E9E9E.toInt()) {
        this.speedKmh = speedKmh
        this.limit = limit
        this.over = limit != null && speedKmh > limit
        this.plainColor = plain
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = ringW / 2 + 0.5f
        box.set(inset, inset, width - inset, height - inset)
        val l = limit
        val blink = blinkPhase
        ring.color = when {
            blink != null -> if (blink) 0xFFC0392B.toInt() else Color.WHITE
            over -> 0xFFC0392B.toInt()
            l == null -> plainColor
            else -> 0xFFBDBDBD.toInt()
        }
        canvas.drawOval(box, ring)
        if (l == null || blink == false) return
        val (legal, overSweep) = SpeedAlarm.sweep(speedKmh, l)
        // Coloured by position: each small step its own colour (a gradient along the arc).
        var a = 0f
        while (a < legal) {
            val step = minOf(6f, legal - a)
            arc.color = SpeedAlarm.colorAt(((a + step / 2) / SpeedAlarm.LEGAL_SWEEP).toDouble())
            canvas.drawArc(box, SpeedAlarm.START_DEG + a, step + 0.6f, false, arc)
            a += step
        }
        if (overSweep > 0f) {
            arc.color = 0xFFB71C1C.toInt()
            canvas.drawArc(box, SpeedAlarm.START_DEG + SpeedAlarm.LEGAL_SWEEP, overSweep, false, arc)
        }
    }
}
