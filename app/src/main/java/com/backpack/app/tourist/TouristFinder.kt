package com.backpack.app.tourist

import android.os.Handler
import android.os.Looper
import com.backpack.app.core.Polyline
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tourist mode, network side.
 * 1. Overpass (OpenStreetMap data): places with a Wikidata entry inside the
 *    route corridor, or inside the visible map area without a route.
 * 2. Wikidata: how many Wikipedia/Wikimedia sites have an article about
 *    each one ("well noted"). Wikidata receives only the list of place ids.
 * Only the newest search may answer; callbacks run on the main thread.
 */
class TouristFinder {

    fun interface ResultCallback {
        fun onResult(pois: List<TouristPoi>?, error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun cancel() { generation++ }

    fun alongRoute(points: List<com.backpack.app.core.LatLon>, mode: TravelMode, callback: ResultCallback) {
        val line = Polyline(points)
        val query = Tourist.corridorQuery(Tourist.sampleLine(points), Tourist.corridorRadius(mode))
        run(query, line, ROUTE_LIMIT, callback)
    }

    fun inBox(south: Double, west: Double, north: Double, east: Double, callback: ResultCallback) {
        run(Tourist.boxQuery(south, west, north, east), null, AREA_LIMIT, callback)
    }

    private fun run(query: String, line: Polyline?, limit: Int, callback: ResultCallback) {
        val gen = ++generation
        val deliver = ResultCallback { pois, error -> main.post { if (gen == generation) callback.onResult(pois, error) } }
        overpass(query, useMirror = false) { body, error ->
            if (gen != generation) return@overpass
            if (body == null) { deliver.onResult(null, error); return@overpass }
            val found = try { Tourist.placeOnRoute(Tourist.parseOverpass(body), line) } catch (e: Exception) { null }
            if (found == null) { deliver.onResult(null, "Couldn't read the places answer"); return@overpass }
            if (found.isEmpty()) { deliver.onResult(emptyList(), null); return@overpass }
            // Keep the request small: closest to the route (or with a Wikipedia tag) first.
            val candidates = found.sortedWith(compareBy<TouristPoi> { if (it.wikipedia != null) 0 else 1 }.thenBy { it.offsetM })
                .take(MAX_CANDIDATES)
            popularity(candidates.map { it.wikidata }) { links ->
                val scored = candidates.map { p ->
                    // Wikidata unreachable: fall back to "has a Wikipedia tag".
                    p.copy(sitelinks = links?.get(p.wikidata) ?: if (p.wikipedia != null) 1 else 0)
                }
                deliver.onResult(Tourist.rank(scored, limit), null)
            }
        }
    }

    /** Sitelink counts for all ids (batches of 50, in parallel); null if every batch failed. */
    private fun popularity(ids: List<String>, done: (Map<String, Int>?) -> Unit) {
        val batches = ids.chunked(50)
        val result = HashMap<String, Int>()
        val left = AtomicInteger(batches.size)
        var anyOk = false
        val lock = Any()
        for (batch in batches) {
            val req = Request.Builder().url(Tourist.wikidataUrl(batch))
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()
            RoutePlanner.http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finish(null)
                override fun onResponse(call: Call, response: Response) {
                    val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                    finish(body?.let { try { Tourist.parseSitelinks(it) } catch (e: Exception) { null } })
                }
                fun finish(part: Map<String, Int>?) {
                    synchronized(lock) {
                        if (part != null) { result.putAll(part); anyOk = true }
                    }
                    if (left.decrementAndGet() == 0) done(if (anyOk) result else null)
                }
            })
        }
    }

    /** POST (the corridor query is long), one retry on a second mirror. */
    private fun overpass(query: String, useMirror: Boolean, callback: (String?, String?) -> Unit) {
        val url = if (useMirror) "https://overpass.kumi.systems/api/interpreter" else "https://overpass-api.de/api/interpreter"
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .post(FormBody.Builder().add("data", query).build())
            .build()
        RoutePlanner.http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!useMirror) overpass(query, true, callback) else callback(null, "Places search failed -- check the connection")
            }

            override fun onResponse(call: Call, response: Response) {
                val transient = response.code in setOf(429, 500, 502, 503, 504, 506)
                if (!response.isSuccessful && transient && !useMirror) {
                    response.close()
                    overpass(query, true, callback)
                    return
                }
                val body = response.use { it.body?.string() }
                if (!response.isSuccessful || body == null) callback(null, "Places search error (HTTP ${response.code})")
                else callback(body, null)
            }
        })
    }

    companion object {
        const val USER_AGENT = "Backpack/0.7 (private Android navigation app; OpenStreetMap data)"
        private const val MAX_CANDIDATES = 150
        private const val ROUTE_LIMIT = 25
        private const val AREA_LIMIT = 20
    }
}
