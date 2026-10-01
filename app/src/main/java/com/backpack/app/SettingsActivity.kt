package com.backpack.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * A plain settings screen -- this is the one place normal buttons and
 * text fields are appropriate, since the "no buttons, icons only" rule
 * applies to the driving/navigation experience, not a one-time config
 * form. Values are persisted in SharedPreferences and read by
 * MainActivity at startup.
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
        const val DISPLAY_MODE_DAY = "day"
        const val DISPLAY_MODE_NIGHT = "night"
        const val DISPLAY_MODE_AUTO = "auto"

        // Defaults match what was hardcoded before this screen existed.
        const val DEFAULT_VALUE_WEEKEND = 10.0
        const val DEFAULT_VALUE_WORK = 20.0
        const val DEFAULT_VALUE_EMERGENCY = 90.0
        const val DEFAULT_TOLL_RATE = 0.12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val displayGroup = findViewById<RadioGroup>(R.id.display_group)
        when (prefs.getString(KEY_DISPLAY_MODE, DISPLAY_MODE_AUTO)) {
            DISPLAY_MODE_DAY -> displayGroup.check(R.id.display_day)
            DISPLAY_MODE_NIGHT -> displayGroup.check(R.id.display_night)
            else -> displayGroup.check(R.id.display_auto)
        }

        val unitGroup = findViewById<RadioGroup>(R.id.unit_group)
        val isMetric = prefs.getBoolean(KEY_UNIT_METRIC, true)
        unitGroup.check(if (isMetric) R.id.unit_km else R.id.unit_mi)

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

        weekendField.setText(prefs.getFloat(KEY_VALUE_WEEKEND, DEFAULT_VALUE_WEEKEND.toFloat()).toString())
        workField.setText(prefs.getFloat(KEY_VALUE_WORK, DEFAULT_VALUE_WORK.toFloat()).toString())
        emergencyField.setText(prefs.getFloat(KEY_VALUE_EMERGENCY, DEFAULT_VALUE_EMERGENCY.toFloat()).toString())
        tollRateField.setText(prefs.getFloat(KEY_TOLL_RATE, DEFAULT_TOLL_RATE.toFloat()).toString())

        findViewById<Button>(R.id.save_button).setOnClickListener {
            val weekend = weekendField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_WEEKEND.toFloat()
            val work = workField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_WORK.toFloat()
            val emergency = emergencyField.text.toString().toFloatOrNull() ?: DEFAULT_VALUE_EMERGENCY.toFloat()
            val tollRate = tollRateField.text.toString().toFloatOrNull() ?: DEFAULT_TOLL_RATE.toFloat()

            val displayMode = when (displayGroup.checkedRadioButtonId) {
                R.id.display_day -> DISPLAY_MODE_DAY
                R.id.display_night -> DISPLAY_MODE_NIGHT
                else -> DISPLAY_MODE_AUTO
            }

            val defaultPoi = when (defaultPoiGroup.checkedRadioButtonId) {
                R.id.default_poi_fuel -> "FUEL"
                R.id.default_poi_food -> "FOOD"
                R.id.default_poi_emergency -> "EMERGENCY"
                else -> "NONE"
            }

            prefs.edit()
                .putString(KEY_DISPLAY_MODE, displayMode)
                .putString(KEY_DEFAULT_POI, defaultPoi)
                .putBoolean(KEY_UNIT_METRIC, unitGroup.checkedRadioButtonId == R.id.unit_km)
                .putFloat(KEY_VALUE_WEEKEND, weekend)
                .putFloat(KEY_VALUE_WORK, work)
                .putFloat(KEY_VALUE_EMERGENCY, emergency)
                .putFloat(KEY_TOLL_RATE, tollRate)
                .apply()

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
