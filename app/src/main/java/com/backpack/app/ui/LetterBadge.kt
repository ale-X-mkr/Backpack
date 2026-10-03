package com.backpack.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.backpack.app.Prefs
import com.backpack.app.R

/**
 * Small circled route letter (A, B, C...), the same on the map and on the
 * route chips. Only the selected one gets the red frame.
 */
object LetterBadge {

    fun bitmap(c: Context, letter: Char, selected: Boolean, sizeDp: Float = 22f): Bitmap {
        val d = c.resources.displayMetrics.density
        val size = (sizeDp * d).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val night = Prefs.isNight(c)
        val ring = if (selected) 2.5f * d else 1.2f * d
        val r = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt() }
        canvas.drawCircle(r, r, r - ring / 2f, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ring
            color = if (selected) ContextCompat.getColor(c, R.color.rg_active) else 0xFF8E8E8E.toInt()
        }
        canvas.drawCircle(r, r, r - ring / 2f, stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) 0xFFEEEEEE.toInt() else 0xFF1A1A1A.toInt()
            textSize = size * 0.58f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // Centred on the glyph itself, not on the font's line box.
        val b = Rect()
        text.getTextBounds(letter.toString(), 0, 1, b)
        canvas.drawText(letter.toString(), r, r - b.exactCenterY(), text)
        return bmp
    }
}
