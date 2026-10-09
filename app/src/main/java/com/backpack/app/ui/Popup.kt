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
    /** Fixed area under the title (tabs of a multipage popup); hidden while empty. */
    val header: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(0))
        visibility = View.GONE
    }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(10))
    }

    private val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.popup_background)
    }

    init {
        root.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.rg_text))
            background = ContextCompat.getDrawable(context, R.drawable.popup_band)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        })
        root.addView(header)
        root.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt().coerceAtMost(dp(420)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fit()
    }

    /** Body scrolls but the popup never grows past 80 % of the screen
     *  (called again when a multipage popup changes page). */
    fun fit() {
        val w = dialog.window ?: return
        w.setLayout(w.attributes.width.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.post {
            val max = (context.resources.displayMetrics.heightPixels * 0.8).toInt()
            if (root.height > max) w.setLayout(w.attributes.width, max)
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

    /** A text field with a small label; [onChange] gets the trimmed text on every edit. */
    fun field(label: String, value: String, hint: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT, onChange: (String) -> Unit): android.widget.EditText {
        body.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(color(R.color.rg_text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        val e = android.widget.EditText(context).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setSingleLine(true)
            setTextColor(color(R.color.rg_text))
            setHintTextColor(color(R.color.rg_text_secondary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.rg_active))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString()?.trim().orEmpty())
            })
        }
        body.addView(e)
        return e
    }

    /** Tappable text that opens [url] in the browser (underlined, active colour). */
    fun link(text: String, url: String, sizeSp: Float = 13f): TextView = TextView(context).apply {
        this.text = android.text.SpannableString(text).also {
            it.setSpan(android.text.style.UnderlineSpan(), 0, text.length, 0)
        }
        textSize = sizeSp
        setTextColor(color(R.color.rg_active))
        setPadding(0, dp(4), 0, dp(4))
        setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) { }
        }
        body.addView(this)
    }

    /** 1.4.0: any view in the body, as tall as it needs. */
    fun custom(v: android.view.View) {
        body.addView(v, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 1.3.0: any view in the body (e.g. the height profile), [heightDp] tall. */
    fun view(v: android.view.View, heightDp: Int) {
        body.addView(v, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)))
    }

    /** A small button inside the body (not in the bottom row). */
    fun bodyButton(label: String, onClick: () -> Unit): TextView {
        val row = FlowRow(context)
        val b = TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(color(R.color.rg_text))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.rg_popup_band))
                setStroke(dp(1), color(R.color.rg_chip_off))
            }
            setOnClickListener { onClick(); refreshAll() }
        }
        row.addView(b)
        body.addView(row)
        return b
    }

    /** Empties the body (multipage popups rebuild it per page). */
    fun clearBody() {
        body.removeAllViews()
        refreshers.clear()
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

    fun styleChip(chip: TextView, on: Boolean) {
        chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else color(R.color.rg_text))
        chip.setTypeface(Typeface.create("sans-serif-condensed", if (on) Typeface.BOLD else Typeface.NORMAL))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (on) color(R.color.rg_active) else color(R.color.rg_chip_off))
        }
    }

    /** A text tab: the current one bold in red with a red underline, the
     *  others grey on no background. */
    fun styleTab(tab: TextView, on: Boolean) {
        tab.setTextColor(if (on) color(R.color.rg_active) else color(R.color.rg_text_secondary))
        tab.setTypeface(Typeface.create("sans-serif-condensed", if (on) Typeface.BOLD else Typeface.NORMAL))
        tab.background = if (!on) null else android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(color(R.color.rg_active)) }
        )).apply { setLayerInset(0, 0, 0, 0, 0); setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(3)) }
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
