package com.backpack.app

import android.app.Application
import org.maplibre.android.MapLibre

class BackpackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key: OpenFreeMap needs none, and MapLibre has no telemetry.
        MapLibre.getInstance(this)
        Prefs.applyNightMode(this)
    }
}
