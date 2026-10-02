package com.backpack.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Plain settings screen. Values are SharedPreferences read by the map
 * screen; keys from Step 38 are unchanged so saved values carry over.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "backpack_settings"
        const val KEY_UNIT_METRIC = "unit_metric"
        const val KEY_VALUE_WEEKEND = "value_weekend"
        const val KEY_VALUE_WORK = "value_work"
        const val KEY_VALUE_EMERGENCY = "value_emergency"
        const val KEY_TOLL_RATE = "toll_rate"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_DEFAULT_POI = "default_poi_category"
        const val KEY_MAP_STYLE = "map_style"
        const val KEY_CUSTOM_STYLE = "custom_style_url"
        const val KEY_ORS_URL = "ors_url"
        const val KEY_ORS_KEY = "ors_key"
        const val DISPLAY_MODE_DAY = "day"
        const val DISPLAY_MODE_NIGHT = "night"
        /** Night map from sunset to sunrise at your position. */
        const val DISPLAY_MODE_AUTO = "auto"
        const val DISPLAY_MODE_SYSTEM = "system"

        const val DEFAULT_VALUE_WEEKEND = 10.0
        const val DEFAULT_VALUE_WORK = 20.0
        const val DEFAULT_VALUE_EMERGENCY = 90.0
        const val DEFAULT_TOLL_RATE = 0.12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settings_title).text = "SETTINGS · v${BuildConfig.VERSION_NAME}"

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val displayGroup = findViewById<RadioGroup>(R.id.display_group)
        when (prefs.getString(KEY_DISPLAY_MODE, DISPLAY_MODE_AUTO)) {
            DISPLAY_MODE_DAY -> displayGroup.check(R.id.display_day)
            DISPLAY_MODE_NIGHT -> displayGroup.check(R.id.display_night)
            DISPLAY_MODE_SYSTEM -> displayGroup.check(R.id.display_system)
            else -> displayGroup.check(R.id.display_auto)
        }

        val styleGroup = findViewById<RadioGroup>(R.id.style_group)
        when (prefs.getString(KEY_MAP_STYLE, "liberty")) {
            "bright" -> styleGroup.check(R.id.style_bright)
            "positron" -> styleGroup.check(R.id.style_positron)
            else -> styleGroup.check(R.id.style_liberty)
        }

        val unitGroup = findViewById<RadioGroup>(R.id.unit_group)
        unitGroup.check(if (prefs.getBoolean(KEY_UNIT_METRIC, true)) R.id.unit_km else R.id.unit_mi)

        val defaultPoiGroup = findViewById<RadioGroup>(R.id.default_poi_group)
        when (prefs.getString(KEY_DEFAULT_POI, "NONE")) {
            "FUEL" -> defaultPoiGroup.check(R.id.default_poi_fuel)
            "FOOD" -> defaultPoiGroup.check(R.id.default_poi_food)
            "EMERGENCY" -> defaultPoiGroup.check(R.id.default_poi_emergency)
            else -> defaultPoiGroup.check(R.id.default_poi_off)
        }

        val weekendField = findViewById<EditText>(R.id.value_weekend)
        val workField = findViewById<EditText>(R.id.value_work)
        val emergencyField = findViewById<EditText>(R.id.value_emergency)
        val tollRateField = findViewById<EditText>(R.id.toll_rate)
        val orsUrlField = findViewById<EditText>(R.id.ors_url)
        val orsKeyField = findViewById<EditText>(R.id.ors_key)
        val customStyleField = findViewById<EditText>(R.id.custom_style)

        weekendField.setText(prefs.getFloat(KEY_VALUE_WEEKEND, DEFAULT_VALUE_WEEKEND.toFloat()).toString())
        workField.setText(prefs.getFloat(KEY_VALUE_WORK, DEFAULT_VALUE_WORK.toFloat()).toString())
        emergencyField.setText(prefs.getFloat(KEY_VALUE_EMERGENCY, DEFAULT_VALUE_EMERGENCY.toFloat()).toString())
        tollRateField.setText(prefs.getFloat(KEY_TOLL_RATE, DEFAULT_TOLL_RATE.toFloat()).toString())
        orsUrlField.setText(prefs.getString(KEY_ORS_URL, ""))
        orsKeyField.setText(prefs.getString(KEY_ORS_KEY, ""))
        customStyleField.setText(prefs.getString(KEY_CUSTOM_STYLE, ""))

        findViewById<Button>(R.id.save_button).setOnClickListener {
            val weekend = weekendField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_WEEKEND.toFloat()
            val work = workField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_WORK.toFloat()
            val emergency = emergencyField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_EMERGENCY.toFloat()
            val tollRate = tollRateField.text.toString().toFloatOrNull() ?: DEFAULT_TOLL_RATE.toFloat()

            val displayMode = when (displayGroup.checkedRadioButtonId) {
                R.id.display_day -> DISPLAY_MODE_DAY
                R.id.display_night -> DISPLAY_MODE_NIGHT
                R.id.display_system -> DISPLAY_MODE_SYSTEM
                else -> DISPLAY_MODE_AUTO
            }
            val mapStyle = when (styleGroup.checkedRadioButtonId) {
                R.id.style_bright -> "bright"
                R.id.style_positron -> "positron"
                else -> "liberty"
            }
            val defaultPoi = when (defaultPoiGroup.checkedRadioButtonId) {
                R.id.default_poi_fuel -> "FUEL"
                R.id.default_poi_food -> "FOOD"
                R.id.default_poi_emergency -> "EMERGENCY"
                else -> "NONE"
            }
            val orsUrl = orsUrlField.text.toString().trim()
            if (orsUrl.isNotEmpty() && !orsUrl.startsWith("https://") && !orsUrl.startsWith("http://")) {
                Toast.makeText(this, "Routing server must start with https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(KEY_DISPLAY_MODE, displayMode)
                .putString(KEY_MAP_STYLE, mapStyle)
                .putString(KEY_DEFAULT_POI, defaultPoi)
                .putBoolean(KEY_UNIT_METRIC, unitGroup.checkedRadioButtonId == R.id.unit_km)
                .putFloat(KEY_VALUE_WEEKEND, weekend)
                .putFloat(KEY_VALUE_WORK, work)
                .putFloat(KEY_VALUE_EMERGENCY, emergency)
                .putFloat(KEY_TOLL_RATE, tollRate)
                .putString(KEY_ORS_URL, orsUrl)
                .putString(KEY_ORS_KEY, orsKeyField.text.toString().trim())
                .putString(KEY_CUSTOM_STYLE, customStyleField.text.toString().trim())
                .apply()

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
            // Day / Night / Auto now also switches the map; Android rebuilds the screens.
            Prefs.applyNightMode(this)
        }
    }
}
