package com.backpack.app.security

import android.content.Context
import com.backpack.app.Settings

/**
 * 1.9.0: where each private item lives in the [Vault], and the one-time
 * move of what older versions kept in plain form.
 */
object PrivateData {

    const val ORS_KEY = "ors_key"
    const val RECENT_SEARCHES = "recent_searches"
    const val LAST_TRIP = "last_trip"
    const val MY_PLACES = "my_places"

    @Volatile private var migrated = false
    private val lock = Any()

    /**
     * Once per start (cheap after the first time), any thread: the small
     * values are moved before anyone reads them (callers wait); the trip files
     * are sealed afterwards on a background thread (no freeze at the first start).
     */
    fun migrate(c: Context) {
        if (migrated) return
        synchronized(lock) {
            if (migrated) return
            val app = c.applicationContext
            Vault.migrate(app, Settings.PREFS_NAME, Settings.KEY_ORS_KEY, ORS_KEY)
            Vault.migrate(app, Settings.PREFS_NAME, Settings.KEY_RECENT_SEARCHES, RECENT_SEARCHES)
            Vault.migrate(app, com.backpack.app.nav.LastTrip.PREFS, "trip", LAST_TRIP)
            migrated = true
            Thread {
                java.io.File(app.filesDir, "trips").listFiles { f -> f.name.endsWith(".trip") }?.forEach { Vault.sealFileIfPlain(it) }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
    }
}
