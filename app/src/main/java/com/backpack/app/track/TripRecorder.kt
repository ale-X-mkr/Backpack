package com.backpack.app.track

import android.content.Context
import android.location.Location
import com.backpack.app.Settings
import com.backpack.app.core.Gpx
import com.backpack.app.location.LocationHub
import java.io.File

/**
 * 1.3.0: records your track while navigating (Settings > Navigation, on by
 * default), on this phone only (app files, never sent anywhere). A point
 * every 10 m or 30 s; written to disk every 20 points and at the end.
 * The 50 newest trips are kept. Exported as GPX on request.
 */
object TripRecorder {

    class Trip(val file: File, val startMs: Long, val name: String) {
        // 1.9.0: encrypted on the phone (plain trips from before are read too).
        val points: List<Gpx.Point> by lazy { try { Gpx.fromLines(com.backpack.app.security.Vault.readFile(file)) } catch (e: Exception) { emptyList() } }
    }

    private var app: Context? = null
    private var file: File? = null
    private val points = ArrayList<Gpx.Point>()
    private var unsaved = 0
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private const val MAX_TRIPS = 50

    private val fix = LocationHub.Listener { loc, ok -> if (ok) add(loc) }

    fun enabled(c: Context) = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(Settings.KEY_RECORD_TRIPS, true)

    private fun dir(c: Context) = File(c.filesDir, "trips").also { it.mkdirs() }

    /** Navigation started: a new trip ("to <label>" when known). */
    fun start(c: Context, label: String?) {
        stop()
        if (!enabled(c)) return
        app = c.applicationContext
        val now = System.currentTimeMillis()
        val safe = (label ?: "").replace(Regex("[^\\p{L}\\p{N} ,.-]"), "").take(40)
        file = File(dir(c), "$now${if (safe.isBlank()) "" else "_$safe"}.trip")
        points.clear()
        unsaved = 0
        LocationHub.addListener(fix)
    }

    /** Navigation ended: written; a trip of fewer than 2 points is dropped. */
    fun stop() {
        LocationHub.removeListener(fix)
        val f = file ?: return
        file = null
        val copy = ArrayList(points)
        points.clear()
        val c = app ?: return
        io.execute {
            if (copy.size < 2) { f.delete(); return@execute }
            try { com.backpack.app.security.Vault.writeFile(f, Gpx.toLines(copy)) } catch (e: Exception) { }
            prune(c)
        }
    }

    private fun add(loc: Location) {
        val f = file ?: return
        val p = Gpx.Point(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else Double.NaN, loc.time)
        if (!Gpx.keep(points.lastOrNull(), p)) return
        points.add(p)
        if (++unsaved >= 20) {
            unsaved = 0
            val copy = ArrayList(points)
            io.execute { try { com.backpack.app.security.Vault.writeFile(f, Gpx.toLines(copy)) } catch (e: Exception) { } }
        }
    }

    fun trips(c: Context): List<Trip> = (dir(c).listFiles { x -> x.name.endsWith(".trip") } ?: emptyArray())
        .mapNotNull { f ->
            val base = f.name.removeSuffix(".trip")
            val ms = base.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
            Trip(f, ms, base.substringAfter('_', ""))
        }.sortedByDescending { it.startMs }

    fun delete(t: Trip) { t.file.delete() }

    fun deleteAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }


    private fun prune(c: Context) { trips(c).drop(MAX_TRIPS).forEach { it.file.delete() } }
}
