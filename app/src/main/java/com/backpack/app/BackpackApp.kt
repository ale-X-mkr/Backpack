package com.backpack.app

import android.app.Application
import org.maplibre.android.MapLibre

class BackpackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key: OpenFreeMap needs none, and MapLibre has no telemetry.
        MapLibre.getInstance(this)
        Prefs.applyNightMode(this)
        // 1.6.0: texts made in the core follow the screen language.
        L10n.install(this)
    }
}
