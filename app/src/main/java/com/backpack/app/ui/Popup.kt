package com.backpack.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.backpack.app.R

/**
 * One popup look for the whole app (RedGRPS style): bold small title on a
 * pale band, mid-light grey body, compact buttons centred at the bottom.
 */
class Popup(private val context: Context, title: String) {

    val dialog = Dialog(context, R.style.Theme_Backpack_Popup)
    val body: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(10))
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.popup_background)
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.rg_text))
            background = ContextCompat.getDrawable(context, R.drawable.popup_band)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        })
        root.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt().coerceAtMost(dp(420)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Body scrolls but the popup never grows past 80 % of the screen.
        root.post {
            val max = (context.resources.displayMetrics.heightPixels * 0.8).toInt()
            if (root.height > max) dialog.window?.setLayout(dialog.window!!.attributes.width, max)
        }
    }

    fun section(text: String) {
        body.addView(TextView(context).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(color(R.color.rg_text_secondary))
            setPadding(0, dp(10), 0, dp(3))
        })
    }

    fun text(text: String, sizeSp: Float = 13f): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color(R.color.rg_text))
        setPadding(0, dp(2), 0, dp(2))
        body.addView(this)
    }

    /** A row of one-tap choices; returns a setter to refresh the highlight. */
    fun <T> choices(items: List<T>, label: (T) -> String, selected: () -> T, onPick: (T) -> Unit): () -> Unit {
        val row = FlowRow(context)
        val chips = items.map { item ->
            TextView(context).apply {
                text = label(item)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setOnClickListener { onPick(item); refreshAll() }
                row.addView(this)
            }
        }
        val refresh = {
            val sel = selected()
            items.forEachIndexed { i, item -> styleChip(chips[i], item == sel) }
        }
        refreshers.add(refresh)
        refresh()
        body.addView(row)
        return refresh
    }

    /** An on/off chip. */
    fun toggle(label: String, isOn: () -> Boolean, onChange: (Boolean) -> Unit) {
        val row = FlowRow(context)
        val chip = TextView(context).apply {
            text = label
            textSize = 13f
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setOnClickListener { onChange(!isOn()); refreshAll() }
        }
        row.addView(chip)
        val refresh = { styleChip(chip, isOn()) }
        refreshers.add(refresh)
        refresh()
        body.addView(row)
    }

    /** Several on/off chips on one wrapping row. */
    fun toggles(items: List<Triple<String, () -> Boolean, (Boolean) -> Unit>>) {
        val row = FlowRow(context)
        for ((label, isOn, onChange) in items) {
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setOnClickListener { onChange(!isOn()); refreshAll() }
            }
            row.addView(chip)
            val refresh = { styleChip(chip, isOn()) }
            refreshers.add(refresh)
            refresh()
        }
        body.addView(row)
    }

    private val refreshers = mutableListOf<() -> Unit>()
    private fun refreshAll() = refreshers.forEach { it() }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) {
        buttons.addView(TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(84)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else color(R.color.rg_text))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (primary) color(R.color.rg_active) else color(R.color.rg_popup_band))
            }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()

    private fun styleChip(chip: TextView, on: Boolean) {
        chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else color(R.color.rg_text))
        chip.setTypeface(Typeface.create("sans-serif-condensed", if (on) Typeface.BOLD else Typeface.NORMAL))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (on) color(R.color.rg_active) else color(R.color.rg_chip_off))
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}

/** Minimal wrapping row for chips (no extra library needed). */
class FlowRow(context: Context) : ViewGroup(context) {
    private val gap = (6 * context.resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom + gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
