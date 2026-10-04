package com.backpack.app.tourist

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.Prefs
import com.backpack.app.core.LatLon
import com.backpack.app.core.Reach
import com.backpack.app.core.TravelMode
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 1.0.7: the area reachable from a point in a given time, from
 * openrouteservice's isochrone service (one request, same keys as routing);
 * a circle estimate when it can't answer. The last answers are kept in
 * memory (same point within ~100 m, mode and time: no new request).
 */
object ReachArea {

    /** [exact]: false = circle estimate. Called on the main thread. */
    fun interface Done { fun onArea(ring: List<LatLon>, exact: Boolean) }

    private val main = Handler(Looper.getMainLooper())
    private val memory = LinkedHashMap<String, List<LatLon>>()
    @Volatile private var failedAtMs = 0L

    fun get(c: Context, center: LatLon, mode: TravelMode, minutes: Int, done: Done) {
        val app = c.applicationContext
        val key = "%.3f,%.3f,%s,%d".format(java.util.Locale.ROOT, center.lat, center.lon, mode.name, minutes)
        synchronized(memory) { memory[key] }?.let { done.onArea(it, true); return }
        // Never farther than Reach.MAX_SEARCH_M (one light request): cut = estimate.
        val circle = { done.onArea(Reach.circle(center, minOf(Reach.radiusM(mode, minutes), Reach.MAX_SEARCH_M)), false) }
        val keys = Prefs.orsKeys(app)
        val base = Prefs.orsBaseUrl(app)
        // Public service: driving only up to 1 h; no key = no request. After a
        // failure, the estimate for 10 min (the daily quota is shared with routing).
        if (!Reach.orsCanAnswer(mode, minutes) || (keys.isEmpty() && base == Prefs.DEFAULT_ORS_URL) ||
            System.currentTimeMillis() - failedAtMs < 10 * 60_000L
        ) { circle(); return }
        fun attempt(k: Int) {
            val b = Request.Builder().url("$base/v2/isochrones/${Reach.orsProfile(mode)}")
                .addHeader("Accept", "application/json, application/geo+json")
                .post(Reach.orsBody(center, minutes).toString().toRequestBody("application/json".toMediaType()))
            keys.getOrNull(k)?.let { b.addHeader("Authorization", it) }
            RoutePlanner.http.newCall(b.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failedAtMs = System.currentTimeMillis(); main.post(circle) }
                override fun onResponse(call: Call, response: Response) {
                    val text = response.use { if (it.isSuccessful) it.body?.string() else null }
                    if (text == null && (response.code == 401 || response.code == 403 || response.code == 429) && k + 1 < keys.size) {
                        attempt(k + 1); return
                    }
                    val ring = text?.let { try { Reach.parseIsochrone(it) } catch (e: Exception) { null } }
                        ?.let { Reach.clip(it, center).first }
                    if (ring == null) { failedAtMs = System.currentTimeMillis(); main.post(circle); return }
                    synchronized(memory) {
                        memory[key] = ring
                        while (memory.size > 8) memory.remove(memory.keys.first())
                    }
                    main.post { done.onArea(ring, true) }
                }
            })
        }
        attempt(0)
    }
}
