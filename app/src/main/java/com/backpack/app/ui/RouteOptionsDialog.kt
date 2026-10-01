package com.backpack.app.ui

import android.content.Context
import com.backpack.app.Prefs
import com.backpack.app.core.BikeType
import com.backpack.app.core.Hills
import com.backpack.app.core.RouteOptions
import com.backpack.app.core.TravelMode
import com.backpack.app.core.Vehicle
import com.backpack.app.core.WalkType

/**
 * One popup, one-tap chips: the route rules for the current travel mode.
 * Tolls keep their own map button (and the Balanced toll logic).
 * [onApply] gets the new options only when something changed.
 */
object RouteOptionsDialog {

    fun show(context: Context, mode: TravelMode, onApply: (RouteOptions) -> Unit) {
        val original = Prefs.routeOptions(context)
        var o = original
        val title = when (mode) {
            TravelMode.DRIVING -> "Route options · Driving"
            TravelMode.CYCLING -> "Route options · Cycling"
            TravelMode.WALKING -> "Route options · Walking"
        }
        val p = Popup(context, title)

        when (mode) {
            TravelMode.DRIVING -> {
                p.section("Vehicle")
                p.choices(Vehicle.values().toList(), { it.label }, { o.vehicle }) { o = o.copy(vehicle = it) }
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Motorways", { o.carAvoidMotorways }, { v: Boolean -> o = o.copy(carAvoidMotorways = v) }),
                    Triple("Ferries", { o.carAvoidFerries }, { v: Boolean -> o = o.copy(carAvoidFerries = v) }),
                    Triple("Fords", { o.carAvoidFords }, { v: Boolean -> o = o.copy(carAvoidFords = v) })
                ))
                p.section("Surface")
                p.toggle("Prefer paved roads", { o.carPreferPaved }) { o = o.copy(carPreferPaved = it) }
                p.text("Tolls: use the toll button on the map. Motorbike is routed like a car (no motorbike profile on the routing server). \"Prefer paved\" picks the alternative with the least unpaved road, if it costs under 25 % more time.", 11f)
            }
            TravelMode.CYCLING -> {
                p.section("Bike")
                p.choices(BikeType.values().toList(), { it.label }, { o.bikeType }) { o = o.copy(bikeType = it) }
                p.section("Hills")
                p.choices(Hills.values().toList(), { it.label }, { o.bikeHills }) { o = o.copy(bikeHills = it) }
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Steps", { o.bikeAvoidSteps }, { v: Boolean -> o = o.copy(bikeAvoidSteps = v) }),
                    Triple("Ferries", { o.bikeAvoidFerries }, { v: Boolean -> o = o.copy(bikeAvoidFerries = v) })
                ))
                p.section("Surface")
                p.toggle("Prefer paved roads", { o.bikePreferPaved }) { o = o.copy(bikePreferPaved = it) }
                p.text("Hills: Flat avoids climbs, Any takes the most direct way.", 11f)
            }
            TravelMode.WALKING -> {
                p.section("Type")
                p.choices(WalkType.values().toList(), { it.label }, { o.walkType }) { o = o.copy(walkType = it) }
                p.section("Prefer")
                p.toggles(listOf(
                    Triple("Green areas", { o.walkGreen }, { v: Boolean -> o = o.copy(walkGreen = v) }),
                    Triple("Quiet streets", { o.walkQuiet }, { v: Boolean -> o = o.copy(walkQuiet = v) })
                ))
                p.section("Avoid")
                p.toggles(listOf(
                    Triple("Steps", { o.walkAvoidSteps }, { v: Boolean -> o = o.copy(walkAvoidSteps = v) }),
                    Triple("Ferries", { o.walkAvoidFerries }, { v: Boolean -> o = o.copy(walkAvoidFerries = v) })
                ))
                p.text("Hike uses footpaths and trails more readily.", 11f)
            }
        }

        p.button("Reset") {
            val d = RouteOptions()
            o = when (mode) {
                TravelMode.DRIVING -> o.copy(vehicle = d.vehicle, carAvoidMotorways = d.carAvoidMotorways,
                    carAvoidFerries = d.carAvoidFerries, carAvoidFords = d.carAvoidFords, carPreferPaved = d.carPreferPaved)
                TravelMode.CYCLING -> o.copy(bikeType = d.bikeType, bikeHills = d.bikeHills,
                    bikeAvoidSteps = d.bikeAvoidSteps, bikeAvoidFerries = d.bikeAvoidFerries, bikePreferPaved = d.bikePreferPaved)
                TravelMode.WALKING -> o.copy(walkType = d.walkType, walkGreen = d.walkGreen, walkQuiet = d.walkQuiet,
                    walkAvoidSteps = d.walkAvoidSteps, walkAvoidFerries = d.walkAvoidFerries)
            }
            p.dismiss()
            if (o != original) { Prefs.saveRouteOptions(context, o); onApply(o) }
        }
        p.button("Done", primary = true) {
            p.dismiss()
            if (o != original) { Prefs.saveRouteOptions(context, o); onApply(o) }
        }
        p.show()
    }
}
