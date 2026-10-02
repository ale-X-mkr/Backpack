package com.backpack.app.tourist

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.core.LatLon
import com.backpack.app.core.Polyline
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.net.OverpassClient
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Well-known places (Weekend mode and the Sights POI category), network side.
 * 1. Overpass (OpenStreetMap data, via OverpassClient: several servers,
 *    retries): places with a Wikidata entry around one or more route lines,
 *    or inside the visible map area.
 * 2. Wikidata: how many Wikipedia/Wikimedia sites write about each one.
 *    Wikidata receives only the list of place ids.
 * Answers with ALL scored candidates (≤ 300); the caller places them on a
 * route, picks the sights route and ranks. Only the newest search answers;
 * callbacks on the main thread.
 */
class TouristFinder(context: Context) {

    fun interface ResultCallback {
        fun onResult(pois: List<TouristPoi>?, error: String?)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun cancel() { generation++ }

    /** Around one or more route lines (the alternatives, for Weekend). */
    fun alongRoutes(lines: List<List<LatLon>>, mode: TravelMode, onSwitch: () -> Unit, callback: ResultCallback) {
        val radius = Tourist.corridorRadius(mode)
        // Rounded (~100 m) and without the first / last stretch: the exact
        // start and end of the trip are not sent.
        val sampled = lines.map { Tourist.sampleLine(Tourist.trimEnds(it, minOf(500.0, radius / 2.0)), maxPoints = if (lines.size > 1) 50 else 80) }
        run(Tourist.corridorQueryMulti(sampled, radius), lines.firstOrNull()?.let { Polyline(it) }, onSwitch, callback)
    }

    fun inBox(south: Double, west: Double, north: Double, east: Double, onSwitch: () -> Unit, callback: ResultCallback) {
        run(Tourist.boxQuery(south, west, north, east), null, onSwitch, callback)
    }

    private fun run(query: String, line: Polyline?, onSwitch: () -> Unit, callback: ResultCallback) {
        val gen = ++generation
        val deliver = ResultCallback { pois, error -> main.post { if (gen == generation) callback.onResult(pois, error) } }
        OverpassClient.query(app, query, onSwitch = { if (gen == generation) onSwitch() }) { body, failure ->
            if (gen != generation) return@query
            if (body == null) { deliver.onResult(null, failure?.message ?: "Places search failed"); return@query }
            val found = try { Tourist.placeOnRoute(Tourist.parseOverpass(body), line) } catch (e: Exception) { null }
            if (found == null) { deliver.onResult(null, OverpassClient.Failure.BAD_ANSWER.message); return@query }
            if (found.isEmpty()) { deliver.onResult(emptyList(), null); return@query }
            // Keep the Wikidata request small: with a Wikipedia tag first, then
            // areas (castles, cathedrals...) before points, then closest to the route.
            val candidates = found.sortedWith(
                compareBy<TouristPoi> { if (it.wikipedia != null) 0 else 1 }
                    .thenBy { if (it.key.startsWith("n")) 1 else 0 }
                    .thenBy { it.offsetM }
            ).take(MAX_CANDIDATES)
            popularity(candidates.map { it.wikidata }) { links ->
                deliver.onResult(candidates.map { p ->
                    // Wikidata unreachable: fall back to "has a Wikipedia tag".
                    p.copy(sitelinks = links?.get(p.wikidata) ?: if (p.wikipedia != null) 1 else 0)
                }, null)
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
                .addHeader("User-Agent", OverpassClient.USER_AGENT)
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

    companion object {
        const val USER_AGENT = OverpassClient.USER_AGENT
        private const val MAX_CANDIDATES = 300
        const val ROUTE_LIMIT = 25
        const val AREA_LIMIT = 20
    }
}
