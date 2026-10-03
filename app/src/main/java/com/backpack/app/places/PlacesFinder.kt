package com.backpack.app.places

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.Prefs
import com.backpack.app.core.Geo
import com.backpack.app.core.LatLon
import com.backpack.app.core.Places
import com.backpack.app.core.PoiPlace
import com.backpack.app.core.Tile
import com.backpack.app.core.Tiles
import com.backpack.app.net.OverpassClient
import com.backpack.app.net.TileCache
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Everyday places for the POI button (fuel, food, toilets...), per map
 * square, kept on the phone for 3 days: moving the map back and forth or
 * planning again in the same area asks no server.
 *
 * Missing squares come from Overpass in one light request. If every
 * Overpass server fails, openrouteservice's places service (same key as
 * routing, 500 requests a day) is asked instead, around the map centre.
 * Callbacks on the main thread; only the newest search answers.
 */
class PlacesFinder(context: Context) {

    /** Category as the finder needs it. */
    class Category(val layer: String, val overpassFilter: String, val orsIds: Map<Int, String>)

    fun interface ResultCallback {
        /** [note]: shown to the user (fallback used, part missing...).
         *  [serverFailed]: Overpass did not answer (back off before asking again). */
        fun onResult(places: List<PoiPlace>?, error: String?, note: String?, serverFailed: Boolean)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun cancel() { generation++ }

    fun inBox(category: Category, south: Double, west: Double, north: Double, east: Double, onSwitch: () -> Unit, callback: ResultCallback) {
        val gen = ++generation
        val cache = TileCache.of(app, category.layer, 3L * 24 * 3600_000L)
        val center = LatLon((south + north) / 2, (west + east) / 2)
        val tiles = Tiles.inBox(south, west, north, east, max = 16)
        // Cache files are read and written off the screen's thread.
        io.execute {
            val found = ArrayList<PoiPlace>()
            val missing = ArrayList<Tile>()
            for (t in tiles) {
                val list = cache.get(t)?.let { try { Places.fromJson(it) } catch (e: Exception) { null } }
                if (list == null) missing.add(t) else found.addAll(list)
            }
            main.post { if (gen == generation) fetchMissing(gen, category, cache, center, south, west, north, east, found, missing, onSwitch, callback) }
        }
    }

    private fun fetchMissing(
        gen: Int, category: Category, cache: TileCache, center: LatLon,
        south: Double, west: Double, north: Double, east: Double,
        found: List<PoiPlace>, missing: List<Tile>, onSwitch: () -> Unit, callback: ResultCallback
    ) {
        fun finish(extra: List<PoiPlace>, error: String?, note: String?, serverFailed: Boolean) {
            if (gen != generation) return
            val all = (found + extra).distinctBy { it.key }
                .filter { it.point.lat in south..north && it.point.lon in west..east }
                .sortedBy { Geo.distance(it.point, center) }
                .take(MAX_SHOWN)
            if (all.isEmpty() && error != null) callback.onResult(null, error, null, true)
            else callback.onResult(all, null, note ?: error?.let { "Some places may be missing ($it)" }, serverFailed)
        }
        if (missing.isEmpty()) { finish(emptyList(), null, null, false); return }
        val query = Places.tileQuery(category.overpassFilter, missing)
        OverpassClient.query(app, query, onSwitch = { if (gen == generation) onSwitch() }) { body, failure ->
            if (body != null) {
                io.execute {
                    // Stored even if a newer search came meanwhile: nothing wasted.
                    val parsed = try { Places.parseOverpass(body) } catch (e: Exception) { null }
                    if (parsed != null) for ((t, list) in Places.byTile(parsed, missing)) cache.put(t, Places.toJson(list))
                    main.post {
                        if (gen != generation) return@post
                        if (parsed != null) finish(parsed, null, null, false)
                        else fallback(gen, category, center, south, west, north, east, OverpassClient.Failure.BAD_ANSWER.message, ::finish)
                    }
                }
                return@query
            }
            if (gen != generation) return@query
            fallback(gen, category, center, south, west, north, east, failure?.message ?: "Places search failed", ::finish)
        }
    }

    /** Second source: openrouteservice places around the centre. */
    private fun fallback(
        gen: Int, category: Category, center: LatLon, south: Double, west: Double, north: Double, east: Double,
        error: String, finish: (List<PoiPlace>, String?, String?, Boolean) -> Unit
    ) {
        val half = minOf(Geo.distance(LatLon(south, center.lon), LatLon(north, center.lon)),
            Geo.distance(LatLon(center.lat, west), LatLon(center.lat, east))) / 2
        orsPlaces(category, center, half.toInt().coerceIn(300, 2000)) { list ->
            if (gen != generation) return@orsPlaces
            if (list == null) finish(emptyList(), error, null, true)
            else finish(list, null, "Places servers busy -- showing openrouteservice places nearby", true)
        }
    }

    /** openrouteservice /pois (only on openrouteservice.org itself, where the keys belong). */
    private fun orsPlaces(category: Category, center: LatLon, bufferM: Int, done: (List<PoiPlace>?) -> Unit) {
        val keys = Prefs.orsKeys(app)
        if (category.orsIds.isEmpty() || keys.isEmpty() || Prefs.orsBaseUrl(app) != Prefs.DEFAULT_ORS_URL) { done(null); return }
        // After a refusal (quota, key), leave the daily quota alone for 10 min.
        if (System.currentTimeMillis() - orsFailedAtMs < 10 * 60_000L) { done(null); return }
        fun attempt(k: Int) {
            val body = Places.orsBody(center, bufferM, category.orsIds.keys.toList(), MAX_SHOWN).toString()
            val req = Request.Builder().url("${Prefs.DEFAULT_ORS_URL}/pois")
                .addHeader("Authorization", keys[k])
                .addHeader("Accept", "application/json, application/geo+json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            RoutePlanner.http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { orsFailedAtMs = System.currentTimeMillis(); main.post { done(null) } }
                override fun onResponse(call: Call, response: Response) {
                    val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                    if (text == null && (response.code == 401 || response.code == 403 || response.code == 429) && k + 1 < keys.size) {
                        attempt(k + 1)
                        return
                    }
                    val list = text?.let { try { Places.parseOrs(it, category.orsIds) } catch (e: Exception) { null } }
                    if (list == null) orsFailedAtMs = System.currentTimeMillis()
                    main.post { done(list) }
                }
            })
        }
        attempt(0)
    }

    companion object {
        const val MAX_SHOWN = 60
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var orsFailedAtMs = 0L
    }
}
