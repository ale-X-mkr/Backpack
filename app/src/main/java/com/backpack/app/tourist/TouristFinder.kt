package com.backpack.app.tourist

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.core.LatLon
import com.backpack.app.core.Polyline
import com.backpack.app.core.Reach
import com.backpack.app.core.SpeedLimits
import com.backpack.app.core.Tile
import com.backpack.app.core.Tiles
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.core.TravelMode
import com.backpack.app.net.OverpassClient
import com.backpack.app.net.TileCache
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Well-known places (Weekend mode and the Sights POI category), network side.
 *
 * 1.0.0: fetched per map square (~5 km) and kept on the phone for two
 * weeks. A route asks only for the squares it crosses that are not known
 * yet (up to [Tourist.searchAheadM] ahead), with one light Overpass request
 * per 25 squares; the server learns which squares, not the route line.
 * Wikidata gives how widely each place is written about and the Wikipedia
 * article to read; those answers are kept for a month.
 *
 * Answers with every place within the corridor of the given lines (or in
 * the box). Only the newest search answers; callbacks on the main thread.
 */
class TouristFinder(context: Context) {

    fun interface ResultCallback {
        /** [coveredM]: how far along the first line the search reached (route searches). */
        fun onResult(pois: List<TouristPoi>?, error: String?, coveredM: Double)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // 1.1.0: new kinds of places -> a new cache (the old squares lack them).
    private val cache = TileCache.of(app, "sights2", 14L * DAY_MS)
    @Volatile private var generation = 0

    fun cancel() { generation++ }

    /**
     * Around one or more route lines, from [fromM] metres along them (your
     * position while navigating) up to the mode's search distance ahead.
     */
    fun alongRoutes(lines: List<List<LatLon>>, mode: TravelMode, fromM: Double, radiusM: Double, onSwitch: () -> Unit, callback: ResultCallback) {
        val gen = ++generation
        // 1.0.9: corridor = the longest detour allowed (Settings > Places).
        val radius = radiusM
        val ahead = Tourist.searchAheadM(mode)
        worker.execute {
            val full = lines.filter { it.size >= 2 }.map { Polyline(it) }
            val parts = full.map { SpeedLimits.section(it, fromM, ahead) }
            val ordered = Tiles.alongLinesOrdered(parts, radius)
            val tiles = ordered.take(MAX_TILES).map { it.first }
            // Cut list: the search reaches only as far as the last square kept.
            val reach = if (ordered.size > MAX_TILES) (ordered[MAX_TILES - 1].second - radius).coerceAtLeast(0.0) else ahead
            val coveredM = full.firstOrNull()?.let { minOf(it.length, fromM + reach) } ?: 0.0
            // Matching on thinned lines (≤ 400 points): accurate enough for a
            // corridor several hundred metres wide, and fast.
            val match = parts.map { Polyline(Tourist.sampleLine(it, maxPoints = 400, minStepM = 50.0)) }
            load(gen, tiles, match, radius, coveredM, onSwitch, callback)
        }
    }

    fun inBox(south: Double, west: Double, north: Double, east: Double, onSwitch: () -> Unit, callback: ResultCallback) {
        val gen = ++generation
        worker.execute {
            val tiles = Tiles.inBox(south, west, north, east, max = 16)
            load(gen, tiles, emptyList(), 0.0, 0.0, onSwitch, callback, box = doubleArrayOf(south, west, north, east))
        }
    }

    /**
     * 1.0.7: inside the area reachable in the chosen time ([ring]). Small
     * areas (walking, cycling) use the cached squares; a big one (by car)
     * is one Overpass request with the polygon itself.
     */
    fun inReach(ring: List<LatLon>, key: String, onSwitch: () -> Unit, callback: ResultCallback) {
        val gen = ++generation
        worker.execute {
            val b = Reach.bounds(ring)
            val tiles = Tiles.inBox(b[0], b[1], b[2], b[3], max = Int.MAX_VALUE)
            if (tiles.size <= MAX_TILES) {
                load(gen, tiles, emptyList(), 0.0, 0.0, onSwitch, callback, ring = ring)
                return@execute
            }
            val deliver = { pois: List<TouristPoi>?, error: String? ->
                main.post { if (gen == generation) callback.onResult(pois, error, 0.0) }
            }
            // Same area as last time (kept in memory): no new request.
            val cached = synchronized(reachMemory) { if (reachMemory.first == key) reachMemory.second else null }
            if (cached != null) { withPopularity(gen, cached) { deliver(it, null) }; return@execute }
            main.post {
                if (gen != generation) return@post
                OverpassClient.query(app, Tourist.reachQuery(ring), onSwitch = { if (gen == generation) onSwitch() }, singleRound = true) { body, failure ->
                    store.execute {
                        val parsed = body?.let { try { Tourist.parseOverpass(it) } catch (e: Exception) { null } }
                        val list = parsed ?: run {
                            // 1.1.0: Overpass failed: Wikipedia's nearby articles around the centre.
                            val b = Reach.bounds(ring)
                            wikiNearby(gen, listOf(LatLon((b[0] + b[2]) / 2, (b[1] + b[3]) / 2))).ifEmpty { null }
                        }
                        if (list == null) {
                            deliver(null, if (body == null) failure?.message ?: "Places search failed" else OverpassClient.Failure.BAD_ANSWER.message)
                            return@execute
                        }
                        val inside = list.distinctBy { it.identity }.filter { Reach.contains(ring, it.point) }
                        synchronized(reachMemory) { reachMemory = key to inside }
                        if (gen == generation) withPopularity(gen, inside) { deliver(it, null) }
                    }
                }
            }
        }
    }

    /** Worker thread: cached squares, then the missing ones from Overpass. */
    private fun load(
        gen: Int, tiles: List<Tile>, lines: List<Polyline>, radius: Double, coveredM: Double,
        onSwitch: () -> Unit, callback: ResultCallback, box: DoubleArray? = null, ring: List<LatLon>? = null
    ) {
        val deliver = { pois: List<TouristPoi>?, error: String? ->
            main.post { if (gen == generation) callback.onResult(pois, error, coveredM) }
        }
        val found = ArrayList<TouristPoi>()
        val missing = ArrayList<Tile>()
        for (t in tiles) {
            val text = cache.get(t)
            val list = text?.let { try { Tourist.fromJson(it) } catch (e: Exception) { null } }
            if (list == null) missing.add(t) else found.addAll(list)
        }
        var error: String? = null
        // Squares another search is already fetching: wait for those instead
        // of asking the server twice.
        val waitFor = missing.mapNotNull { t -> inFlight[t]?.let { t to it } }
        val toFetch = missing.filter { !inFlight.containsKey(it) }
        // One request at a time (public servers allow 2 at once per user):
        // every square is marked first so other searches wait for it.
        val chunks = toFetch.chunked(TILES_PER_REQUEST).map { chunk ->
            val latch = CountDownLatch(1)
            chunk.forEach { inFlight[it] = latch }
            chunk to latch
        }
        fun release(from: Int) {
            for (k in from until chunks.size) {
                val (chunk, latch) = chunks[k]
                chunk.forEach { inFlight.remove(it, latch) }
                latch.countDown()
            }
        }
        for ((k, pair) in chunks.withIndex()) {
            val (chunk, latch) = pair
            // Replaced, or the servers already failed: don't ask again now.
            if (gen != generation || error != null) { release(k); break }
            val outcome = fetch(chunk, latch) { main.post { if (gen == generation) onSwitch() } }
            if (!await(gen, latch)) { release(k + 1); return }
            val failure = outcome.get()
            if (failure != null) { error = failure; continue }
            for (t in chunk) cache.get(t)?.let { text -> try { found.addAll(Tourist.fromJson(text)) } catch (e: Exception) { } }
        }
        for ((t, latch) in waitFor) {
            if (!await(gen, latch)) return
            val list = cache.get(t)?.let { try { Tourist.fromJson(it) } catch (e: Exception) { null } }
            if (list != null) found.addAll(list) else if (error == null) error = "Places search failed"
        }
        if (gen != generation) return
        // 1.1.0: Overpass failed (part): Wikipedia's nearby articles fill in.
        if (error != null) {
            val probes = when {
                lines.isNotEmpty() -> lines.first().let { l ->
                    val step = maxOf(10_000.0, l.length / 4)
                    generateSequence(0.0) { it + step }.takeWhile { it <= l.length }.take(5)
                        .map { com.backpack.app.core.SpeedLimits.pointAt(l, it) }.toList()
                }
                ring != null -> Reach.bounds(ring).let { b -> listOf(LatLon((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) }
                box != null -> listOf(LatLon((box[0] + box[2]) / 2, (box[1] + box[3]) / 2))
                else -> emptyList()
            }
            val extra = wikiNearby(gen, probes)
            if (extra.isNotEmpty()) { found.addAll(extra); error = null }
        }
        if (found.isEmpty() && error != null) { deliver(null, error); return }

        // Inside the corridor (or the box) only; distance to the NEAREST line,
        // so places along an alternative count as much as along the first route.
        val inside = found.distinctBy { it.identity }.mapNotNull { p ->
            if (lines.isEmpty()) {
                if (ring != null) { if (Reach.contains(ring, p.point)) p else null }
                else if (box != null && (p.point.lat < box[0] || p.point.lat > box[2] || p.point.lon < box[1] || p.point.lon > box[3])) null else p
            } else {
                val best = lines.mapNotNull { it.snap(p.point) }.minByOrNull { it.offsetM }
                if (best == null || best.offsetM > radius * 1.05) null else p.copy(offsetM = best.offsetM, alongM = best.alongM)
            }
        }
        withPopularity(gen, inside) { scored -> deliver(scored, error) }
    }

    /** Sitelink counts + article links, from the phone first, Wikidata for the rest. */
    private fun withPopularity(gen: Int, pois: List<TouristPoi>, done: (List<TouristPoi>) -> Unit) {
        val known = SiteInfoStore.load(app)
        // Keep the Wikidata request small: with a Wikipedia tag first, then
        // areas (castles, cathedrals...) before points, then closest to the route.
        val ask = pois.filter { it.wikidata.isNotEmpty() && it.wikidata !in known }
            .sortedWith(compareBy<TouristPoi> { if (it.wikipedia != null) 0 else 1 }
                .thenBy { if (it.key.startsWith("n")) 1 else 0 }
                .thenBy { it.offsetM })
            .take(MAX_WIKIDATA).map { it.wikidata }.distinct()
        fun finish(info: Map<String, Tourist.SiteInfo>) {
            done(pois.map { p ->
                val i = info[p.wikidata] ?: known[p.wikidata]
                // Unknown (Wikidata unreachable): "has a Wikipedia tag" counts as known.
                p.copy(sitelinks = i?.sitelinks ?: if (p.wikipedia != null) 1 else 0, articleUrl = i?.articleUrl, voyageUrl = i?.voyageUrl)
            })
        }
        if (ask.isEmpty() || gen != generation) { finish(emptyMap()); return }
        val batches = ask.chunked(50)
        val result = HashMap<String, Tourist.SiteInfo>()
        // Wikipedia codes for the phone's language (Java still reports some legacy codes).
        val lang = when (val l = java.util.Locale.getDefault().language) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; "nb" -> "no"; else -> l }
        val langs = listOf(lang, "en").filter { it.isNotBlank() }.distinct()
        // One request at a time (Wikimedia API etiquette).
        fun next(k: Int) {
            if (k >= batches.size || gen != generation) {
                worker.execute {
                    val copy = synchronized(result) { HashMap(result) }
                    if (copy.isNotEmpty()) SiteInfoStore.add(app, copy)
                    finish(copy)
                }
                return
            }
            val req = Request.Builder().url(Tourist.wikidataUrl(batches[k]))
                .addHeader("User-Agent", OverpassClient.USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()
            RoutePlanner.http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = next(k + 1)
                override fun onResponse(call: Call, response: Response) {
                    val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                    val part = body?.let { try { Tourist.parseSiteInfo(it, langs) } catch (e: Exception) { null } }
                    synchronized(result) { if (part != null) result.putAll(part) }
                    next(k + 1)
                }
            })
        }
        next(0)
    }

    /**
     * Fires one Overpass request for [chunk]; its answer is stored per square
     * (even if the search that asked was replaced meanwhile: nothing wasted),
     * then [latch] opens. The returned holder gets the error text, if any.
     */
    private fun fetch(chunk: List<Tile>, latch: CountDownLatch, onSwitch: () -> Unit): java.util.concurrent.atomic.AtomicReference<String?> {
        val outcome = java.util.concurrent.atomic.AtomicReference<String?>(null)
        main.post {
            OverpassClient.query(app, Tourist.tileQuery(chunk), onSwitch = onSwitch) { body, failure ->
                store.execute {
                    val parsed = body?.let { try { Tourist.parseOverpass(it) } catch (e: Exception) { null } }
                    if (parsed == null) {
                        outcome.set(if (body == null) failure?.message ?: "Places search failed" else OverpassClient.Failure.BAD_ANSWER.message)
                    } else {
                        for ((t, list) in Tourist.byTile(parsed, chunk)) cache.put(t, Tourist.toJson(list))
                    }
                    chunk.forEach { inFlight.remove(it, latch) }
                    latch.countDown()
                }
            }
        }
        return outcome
    }

    /**
     * 1.1.0 second source, blocking (worker / store thread): Wikipedia
     * articles with coordinates near [probes] (10 km each), kept only when
     * Wikidata says they are a place to visit (castle, museum, waterfall...).
     */
    private fun wikiNearby(gen: Int, probes: List<LatLon>): List<TouristPoi> {
        if (probes.isEmpty()) return emptyList()
        val lang = when (val l = java.util.Locale.getDefault().language) { "in" -> "id"; "iw" -> "he"; "nb" -> "no"; else -> l }.ifBlank { "en" }
        fun get(url: String): String? = try {
            RoutePlanner.http.newCall(Request.Builder().url(url).addHeader("User-Agent", OverpassClient.USER_AGENT)
                .addHeader("Accept", "application/json").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) { null }
        val hits = LinkedHashMap<String, Pair<Tourist.WikiHit, String>>()
        for (p in probes) {
            if (gen != generation) return emptyList()
            for (l in listOf(lang, "en").distinct()) {
                val body = get(Tourist.wikiNearbyUrl(l, p, 10_000)) ?: continue
                val list = try { Tourist.parseWikiNearby(body) } catch (e: Exception) { emptyList() }
                for (h in list) if (h.wikidata !in hits) hits[h.wikidata] = h to l
                if (list.isNotEmpty()) break
            }
        }
        if (hits.isEmpty()) return emptyList()
        val kinds = HashMap<String, com.backpack.app.core.TouristCategory>()
        for (chunk in hits.keys.toList().chunked(100)) {
            if (gen != generation) return emptyList()
            val url = "https://query.wikidata.org/sparql?format=json&query=" +
                java.net.URLEncoder.encode(Tourist.kindsQuery(chunk), "UTF-8")
            get(url)?.let { b -> try { kinds.putAll(Tourist.parseKinds(b)) } catch (e: Exception) { } }
        }
        return hits.values.mapNotNull { (h, l) ->
            val cat = kinds[h.wikidata] ?: return@mapNotNull null
            TouristPoi("q" + h.wikidata, h.title, h.point, cat, h.wikidata, "$l:${h.title}")
        }
    }

    /** Waits for [latch]; false when this search was replaced meanwhile. */
    private fun await(gen: Int, latch: CountDownLatch): Boolean {
        while (!latch.await(500, TimeUnit.MILLISECONDS)) if (gen != generation) return false
        return gen == generation
    }

    companion object {
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Parses and stores answers (never blocked by a waiting search). */
        private val store = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val inFlight = java.util.concurrent.ConcurrentHashMap<Tile, CountDownLatch>()
        /** Last big reach area and its places (before popularity). */
        private var reachMemory: Pair<String?, List<TouristPoi>> = null to emptyList()
        private const val DAY_MS = 24L * 3600_000L

        const val USER_AGENT = OverpassClient.USER_AGENT
        /** Squares per Overpass request, and per search. */
        private const val TILES_PER_REQUEST = 25
        private const val MAX_TILES = 150
        private const val MAX_WIKIDATA = 300
        const val ROUTE_LIMIT = 25
        const val AREA_LIMIT = 20
        /** Sights within reach (1.0.7). */
        const val REACH_LIMIT = 40
    }
}

/** Wikidata answers kept on the phone for a month (id -> sitelinks, article). */
internal object SiteInfoStore {
    private const val FILE = "sights_wikidata.json"
    private const val MAX_AGE_MS = 30L * 24 * 3600_000L
    private var memory: HashMap<String, Pair<Long, Tourist.SiteInfo>>? = null

    @Synchronized
    fun load(c: Context): Map<String, Tourist.SiteInfo> {
        val m = memory ?: read(c).also { memory = it }
        val now = System.currentTimeMillis()
        return m.filterValues { now - it.first < MAX_AGE_MS }.mapValues { it.value.second }
    }

    @Synchronized
    fun add(c: Context, info: Map<String, Tourist.SiteInfo>) {
        val m = memory ?: read(c).also { memory = it }
        val now = System.currentTimeMillis()
        for ((id, i) in info) m[id] = now to i
        // Oldest out beyond 20 000 places.
        if (m.size > 20_000) m.entries.sortedBy { it.value.first }.take(m.size - 20_000).forEach { m.remove(it.key) }
        try {
            val o = JSONObject()
            for ((id, v) in m) {
                val a = JSONArray().put(v.first).put(v.second.sitelinks)
                    .put(v.second.articleUrl ?: "").put(v.second.voyageUrl ?: "")
                o.put(id, a)
            }
            File(c.cacheDir, "places").mkdirs()
            File(File(c.cacheDir, "places"), FILE).writeText(o.toString())
        } catch (e: Exception) { }
    }

    @Synchronized
    fun forget() { memory = null }

    private fun read(c: Context): HashMap<String, Pair<Long, Tourist.SiteInfo>> {
        val out = HashMap<String, Pair<Long, Tourist.SiteInfo>>()
        try {
            val f = File(File(c.cacheDir, "places"), FILE)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            val keys = o.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val a = o.optJSONArray(id) ?: continue
                out[id] = a.optLong(0) to Tourist.SiteInfo(a.optInt(1), a.optString(2, "").ifBlank { null }, a.optString(3, "").ifBlank { null })
            }
        } catch (e: Exception) { }
        return out
    }
}
