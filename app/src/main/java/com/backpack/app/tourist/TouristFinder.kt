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
    // 1.1.1: the squares saved before are used again ("sights"); the new
    // kinds come in their own small cache on top ("sights_x").
    private val cache = TileCache.of(app, "sights", 14L * DAY_MS)
    private val cacheX = TileCache.of(app, "sights_x", 14L * DAY_MS)
    /** 1.2.1: Wikipedia cells (0.1°), a month. */
    private val wikiCache = TileCache.of(app, "wiki10", 30L * DAY_MS)

    /** A square to fetch: everything, or only the new kinds. */
    private data class Need(val tile: Tile, val extras: Boolean)

    private fun read(c: TileCache, t: Tile): List<TouristPoi>? =
        c.get(t)?.let { try { Tourist.fromJson(it) } catch (e: Exception) { null } }
    @Volatile private var generation = 0

    fun cancel() { generation++ }

    /**
     * Around one or more route lines, from [fromM] metres along them (your
     * position while navigating) up to the mode's search distance ahead.
     */
    fun alongRoutes(
        lines: List<List<LatLon>>, mode: TravelMode, fromM: Double, radiusM: Double, onSwitch: () -> Unit,
        aheadM: Double? = null, onPartial: ((List<TouristPoi>) -> Unit)? = null, callback: ResultCallback
    ) {
        val gen = ++generation
        // 1.0.9: corridor = the longest detour allowed (Settings > Places).
        val radius = radiusM
        // 1.1.1: before Go a shorter stretch (faster); the rest follows while driving.
        val ahead = aheadM ?: Tourist.searchAheadM(mode)
        worker.execute {
            val full = lines.filter { it.size >= 2 }.map { Polyline(it) }
            val parts = full.map { SpeedLimits.section(it, fromM, ahead) }
            val ordered = Tiles.alongLinesOrdered(parts, radius)
            val tiles = ordered.take(MAX_TILES).map { it.first }
            // Cut list: the search reaches only as far as the last square kept.
            val reach = if (ordered.size > MAX_TILES) (ordered[MAX_TILES - 1].second - radius).coerceAtLeast(0.0) else ahead
            val coveredM = full.firstOrNull()?.let { minOf(it.length, fromM + reach) } ?: 0.0
            // 1.2.1: the whole rest of the route (Wikipedia, fast); OpenStreetMap
            // (Overpass) adds the spots without an article on the stretch above.
            val rest = full.map { SpeedLimits.section(it, fromM, it.length) }
            // Matching on thinned lines: accurate enough for a corridor several
            // hundred metres wide, and fast.
            val match = rest.map { Polyline(Tourist.sampleLine(it, maxPoints = 800, minStepM = 50.0)) }
            // Wikipedia for the stretch OpenStreetMap covers first (quick), then
            // the rest of the route on its own thread, added as it comes.
            val near = Tourist.wikiCellsAlong(parts, radius).map { it.first }
            val nearSet = near.toHashSet()
            val far = Tourist.wikiCellsAlong(rest, radius).map { it.first }.filter { it !in nearSet }
                .take((MAX_WIKI_CELLS - near.size).coerceAtLeast(0))
            val wiki = wikiCells(gen, near) { soFar ->
                if (onPartial != null) { val part = withKnown(select(soFar, match, radius)); main.post { if (gen == generation) onPartial(part) } }
            }
            if (gen != generation) return@execute
            val farFound = java.util.concurrent.atomic.AtomicReference<List<TouristPoi>>(emptyList())
            /** The search's final list, once delivered (later far cells are added to it). */
            val finalList = java.util.concurrent.atomic.AtomicReference<List<TouristPoi>?>(null)
            fun pushFar() {
                val base = finalList.get() ?: return
                if (onPartial == null) return
                val part = withKnown(select(base + farFound.get(), match, radius))
                main.post { if (gen == generation) onPartial(part) }
            }
            if (far.isNotEmpty()) farWorker.execute {
                if (gen != generation) return@execute
                wikiCells(gen, far) { soFar -> farFound.set(soFar); pushFar() }
            }
            load(gen, tiles, match, radius, coveredM, onSwitch, callback, onPartial = onPartial, seed = wiki,
                extra = { farFound.get() },
                onFinal = { list, usedFar -> finalList.set(list); if (farFound.get().size != usedFar) pushFar() })
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
    fun inReach(ring: List<LatLon>, key: String, onSwitch: () -> Unit,
                onPartial: ((List<TouristPoi>) -> Unit)? = null, callback: ResultCallback) {
        val gen = ++generation
        worker.execute {
            val b = Reach.bounds(ring)
            // 1.2.1: Wikipedia first (fast, nearest cells first), shown as it comes.
            val wiki = wikiCells(gen, Tourist.wikiCellsInBox(b[0], b[1], b[2], b[3], MAX_WIKI_AREA_CELLS)) { soFar ->
                if (onPartial != null) { val part = withKnown(select(soFar, emptyList(), 0.0, ring = ring)); main.post { if (gen == generation) onPartial(part) } }
            }
            if (gen != generation) return@execute
            val tiles = Tiles.inBox(b[0], b[1], b[2], b[3], max = Int.MAX_VALUE)
            if (tiles.size <= MAX_TILES) {
                load(gen, tiles, emptyList(), 0.0, 0.0, onSwitch, callback, ring = ring, seed = wiki)
                return@execute
            }
            val deliver = { pois: List<TouristPoi>?, error: String? ->
                main.post { if (gen == generation) callback.onResult(pois, error, 0.0) }
            }
            // Same area as last time (kept in memory): no new request.
            val cached = synchronized(reachMemory) { if (reachMemory.first == key) reachMemory.second else null }
            val wikiInside = wiki.filter { Reach.contains(ring, it.point) }
            if (cached != null) { withPopularity(gen, (wikiInside + cached).distinctBy { it.identity }) { deliver(it, null) }; return@execute }
            main.post {
                if (gen != generation) return@post
                OverpassClient.query(app, Tourist.reachQuery(ring), onSwitch = { if (gen == generation) onSwitch() }, singleRound = true) { body, failure ->
                    // Own thread: the Wikipedia fallback may wait on the network, and
                    // "store" must stay free to release other searches' squares.
                    slow.execute {
                        val parsed = body?.let { try { Tourist.parseOverpass(it) } catch (e: Exception) { null } }
                        // 1.2.1: Overpass failed: what Wikipedia gave is enough.
                        val list = parsed ?: wiki.ifEmpty { null }
                        if (list == null) {
                            deliver(null, if (body == null) failure?.message ?: "Places search failed" else OverpassClient.Failure.BAD_ANSWER.message)
                            return@execute
                        }
                        val inside = (wiki + list).distinctBy { it.identity }.filter { Reach.contains(ring, it.point) }
                        if (parsed != null) synchronized(reachMemory) { reachMemory = key to inside }
                        if (gen == generation) withPopularity(gen, inside) { deliver(it, null) }
                    }
                }
            }
        }
    }

    /** Worker thread: cached squares, then the missing ones from Overpass. */
    private fun load(
        gen: Int, tiles: List<Tile>, lines: List<Polyline>, radius: Double, coveredM: Double,
        onSwitch: () -> Unit, callback: ResultCallback, box: DoubleArray? = null, ring: List<LatLon>? = null,
        onPartial: ((List<TouristPoi>) -> Unit)? = null, seed: List<TouristPoi> = emptyList(),
        /** 1.2.1: places found meanwhile on the far part of the route. */
        extra: () -> List<TouristPoi> = { emptyList() },
        /** The list delivered, and how many [extra] places it included. */
        onFinal: (List<TouristPoi>, Int) -> Unit = { _, _ -> }
    ) {
        fun select(list: List<TouristPoi>): List<TouristPoi> = select(list, lines, radius, box, ring)
        val deliver = { pois: List<TouristPoi>?, error: String? ->
            main.post { if (gen == generation) callback.onResult(pois, error, coveredM) }
        }
        // 1.2.1: Wikipedia's places first (same place twice: one entry).
        val found = ArrayList<TouristPoi>(seed)
        // 1.1.1: squares already on the phone are used as they are; only the
        // new kinds are fetched "on top" of them (a small query).
        val missing = ArrayList<Need>()
        for (t in tiles) {
            val base = read(cache, t)
            if (base == null) { missing.add(Need(t, false)); continue }
            found.addAll(base)
            val extra = read(cacheX, t)
            if (extra == null) missing.add(Need(t, true)) else found.addAll(extra)
        }
        var error: String? = null
        // Squares another search is already fetching: wait for those instead
        // of asking the server twice.
        val waitFor = missing.mapNotNull { n -> inFlight[n]?.let { n to it } }
        val toFetch = missing.filter { !inFlight.containsKey(it) }
        // Every square is marked first so other searches wait for it.
        val chunks = (toFetch.filter { !it.extras }.chunked(TILES_PER_REQUEST) +
            toFetch.filter { it.extras }.chunked(TILES_PER_REQUEST)).map { chunk ->
            val latch = CountDownLatch(1)
            chunk.forEach { inFlight[it] = latch }
            chunk to latch
        }
        // 1.1.1: all chunks queued at once (the client runs two at a time);
        // a replaced search or a failed chunk lets the rest be skipped.
        val abort = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcomes = chunks.map { (chunk, latch) ->
            fetch(chunk, latch, { gen != generation || abort.get() }) { main.post { if (gen == generation) onSwitch() } }
        }
        for ((k, pair) in chunks.withIndex()) {
            val (chunk, latch) = pair
            if (!await(gen, latch)) { abort.set(true); return }
            val failure = outcomes[k].get()
            if (failure != null) { if (error == null) error = failure; abort.set(true); continue }
            for (n in chunk) read(if (n.extras) cacheX else cache, n.tile)?.let { found.addAll(it) }
            // First answer (the nearest squares): shown at once, ranked with what the phone knows.
            if (k == 0 && chunks.size > 1 && onPartial != null) {
                val part = withKnown(select(found))
                main.post { if (gen == generation) onPartial(part) }
            }
        }
        // Squares another (maybe replaced) search had reserved: if it gave up
        // on them without fetching, this search fetches them itself.
        val again = ArrayList<Need>()
        for ((n, latch) in waitFor) {
            if (!await(gen, latch)) return
            val list = read(if (n.extras) cacheX else cache, n.tile)
            if (list != null) found.addAll(list) else again.add(n)
        }
        if (again.isNotEmpty() && error == null && gen == generation) {
            for (chunk in again.filter { !inFlight.containsKey(it) }.groupBy { it.extras }.values.flatMap { it.chunked(TILES_PER_REQUEST) }) {
                val latch = CountDownLatch(1)
                chunk.forEach { inFlight[it] = latch }
                val outcome = fetch(chunk, latch, { gen != generation }) { main.post { if (gen == generation) onSwitch() } }
                if (!await(gen, latch)) return
                val failure = outcome.get()
                if (failure != null) { error = failure; break }
                for (n in chunk) read(if (n.extras) cacheX else cache, n.tile)?.let { found.addAll(it) }
            }
        }
        if (gen != generation) return
        // 1.2.1: Wikipedia already gave the places: an OpenStreetMap failure only
        // means a few spots without an article may be missing -- not worth a warning.
        if (error != null && seed.isNotEmpty()) error = null
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
        val more = extra()
        withPopularity(gen, select(found + more)) { scored ->
            // Final first, then (posted after it) any far places that came meanwhile.
            deliver(scored, error)
            onFinal(scored, more.size)
        }
    }

    /** Inside the corridor (or box / area); distance to the NEAREST line,
     *  so places along an alternative count as much as along the first route. */
    private fun select(
        list: List<TouristPoi>, lines: List<Polyline>, radius: Double,
        box: DoubleArray? = null, ring: List<LatLon>? = null
    ): List<TouristPoi> = list.distinctBy { it.identity }.mapNotNull { p ->
        if (lines.isEmpty()) {
            if (ring != null) { if (Reach.contains(ring, p.point)) p else null }
            else if (box != null && (p.point.lat < box[0] || p.point.lat > box[2] || p.point.lon < box[1] || p.point.lon > box[3])) null else p
        } else {
            val best = lines.mapNotNull { it.snap(p.point) }.minByOrNull { it.offsetM }
            if (best == null || best.offsetM > radius * 1.05) null else p.copy(offsetM = best.offsetM, alongM = best.alongM)
        }
    }

    /** Popularity already on the phone (no request): for results shown early. */
    private fun withKnown(list: List<TouristPoi>): List<TouristPoi> {
        val known = SiteInfoStore.load(app)
        return list.map { p ->
            known[p.wikidata]?.let { p.copy(sitelinks = maxOf(p.sitelinks, it.sitelinks), articleUrl = it.articleUrl, voyageUrl = it.voyageUrl) } ?: p
        }
    }

    /**
     * 1.2.1 main source, blocking (worker thread): Wikipedia articles with
     * coordinates in each cell (3 cells at a time), kept when Wikidata says
     * they are a place to visit (castle, museum, waterfall...), with how
     * widely each is written about. Cells are kept on the phone for a month;
     * [onProgress] gets everything found so far after each batch.
     */
    private fun wikiCells(gen: Int, cells: List<Tile>, onProgress: (List<TouristPoi>) -> Unit): List<TouristPoi> {
        val found = ArrayList<TouristPoi>()
        val missing = ArrayList<Tile>()
        for (c in cells) { val l = read(wikiCache, c); if (l == null) missing.add(c) else found.addAll(l) }
        if (found.isNotEmpty()) onProgress(ArrayList(found))
        val info = HashMap<String, Tourist.SiteInfo>()
        for (batch in missing.chunked(WIKI_BATCH)) {
            if (gen != generation) break
            val answers = batch.map { c -> wikiPool.submit(java.util.concurrent.Callable { c to wikiHits(c) }) }
                .mapNotNull { f -> try { f.get(12, TimeUnit.SECONDS) } catch (e: Exception) { null } }
                .mapNotNull { (c, h) -> h?.let { c to it } }
            if (answers.isEmpty()) continue
            val ids = answers.flatMap { (c, h) -> h.filter { Tourist.wikiCellOf(it.point) == c }.map { it.wikidata } }.distinct()
            // Kinds unknown (Wikidata busy): those cells are asked again next time.
            val kinds = if (ids.isEmpty()) emptyMap() else wikiKinds(ids) ?: continue
            for ((c, h) in answers) {
                val places = Tourist.wikiPlaces(c, h, kinds)
                wikiCache.put(c, Tourist.toJson(places))
                found.addAll(places)
                for (p in places) info[p.wikidata] = Tourist.SiteInfo(p.sitelinks, p.wikipediaUrl, null)
            }
            onProgress(ArrayList(found))
        }
        if (info.isNotEmpty()) {
            // Popularity known from now on (no second Wikidata request for these).
            val known = SiteInfoStore.load(app)
            SiteInfoStore.add(app, info.filterKeys { it !in known })
        }
        return found.map { p -> info[p.wikidata]?.let { p.copy(sitelinks = it.sitelinks) } ?: p }
    }

    private fun wikiHits(cell: Tile): List<Tourist.WikiHit>? = try {
        wikiHttp.newCall(Request.Builder().url(Tourist.wikiCellUrl(cell)).addHeader("User-Agent", OverpassClient.USER_AGENT)
            .addHeader("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { Tourist.parseWikiNearby(it) }
        }
    } catch (e: Exception) { null }

    /** Kinds + popularity for [ids] (Wikidata Query Service, POST, 400 at a time); null on failure. */
    private fun wikiKinds(ids: List<String>): Map<String, Pair<com.backpack.app.core.TouristCategory, Int>>? {
        val out = HashMap<String, Pair<com.backpack.app.core.TouristCategory, Int>>()
        for (chunk in ids.chunked(400)) {
            val body = try {
                val form = okhttp3.FormBody.Builder().add("query", Tourist.kindsLinksQuery(chunk)).build()
                wikiHttp.newCall(Request.Builder().url("https://query.wikidata.org/sparql").post(form)
                    .addHeader("User-Agent", OverpassClient.USER_AGENT)
                    .addHeader("Accept", "application/sparql-results+json").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            } catch (e: Exception) { null } ?: return null
            try { out.putAll(Tourist.parseKindsLinks(body)) } catch (e: Exception) { return null }
        }
        return out
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
    private fun fetch(chunk: List<Need>, latch: CountDownLatch, isStale: () -> Boolean, onSwitch: () -> Unit): java.util.concurrent.atomic.AtomicReference<String?> {
        val outcome = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val extras = chunk.first().extras
        val tiles = chunk.map { it.tile }
        main.post {
            OverpassClient.query(app, Tourist.tileQuery(tiles, extrasOnly = extras), onSwitch = onSwitch, isStale = isStale) { body, failure ->
                store.execute {
                    val parsed = body?.let { try { Tourist.parseOverpass(it) } catch (e: Exception) { null } }
                    if (parsed == null) {
                        outcome.set(if (body == null) failure?.message ?: "Places search failed" else OverpassClient.Failure.BAD_ANSWER.message)
                    } else {
                        for ((t, list) in Tourist.byTile(parsed, tiles)) {
                            if (extras) cacheX.put(t, Tourist.toJson(list))
                            else {
                                // A full fetch has the new kinds too: nothing "on top" needed.
                                cache.put(t, Tourist.toJson(list))
                                cacheX.put(t, "[]")
                            }
                        }
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
        // 1.1.1: never more than ~10 s in all (short timeouts, then give up).
        val deadline = System.currentTimeMillis() + 10_000L
        val lang = when (val l = java.util.Locale.getDefault().language) { "in" -> "id"; "iw" -> "he"; "nb" -> "no"; else -> l }.ifBlank { "en" }
        fun get(url: String): String? = if (System.currentTimeMillis() > deadline) null else try {
            quickHttp.newCall(Request.Builder().url(url).addHeader("User-Agent", OverpassClient.USER_AGENT)
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
        /** 1.2.1: Wikipedia for the far part of a route (never holds up other searches). */
        private val farWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Parses and stores answers (never blocked by a waiting search). */
        private val store = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val inFlight = java.util.concurrent.ConcurrentHashMap<Any, CountDownLatch>()
        /** Wikipedia fallback requests: 4 s each at most. */
        private val quickHttp = RoutePlanner.http.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        /** Big reach areas and their Wikipedia fallback (may wait on the network). */
        private val slow = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Last big reach area and its places (before popularity). */
        private var reachMemory: Pair<String?, List<TouristPoi>> = null to emptyList()
        private const val DAY_MS = 24L * 3600_000L

        const val USER_AGENT = OverpassClient.USER_AGENT
        /** Squares per Overpass request, and per search. */
        private const val TILES_PER_REQUEST = 25
        private const val MAX_TILES = 150
        private const val MAX_WIKIDATA = 300
        /** 1.2.1 Wikipedia: cells per route (~1000 km of corridor), per area, per round. */
        private const val MAX_WIKI_CELLS = 300
        private const val MAX_WIKI_AREA_CELLS = 36
        private const val WIKI_BATCH = 6
        /** Three cells asked at once (Wikimedia API: modest parallel reads). */
        private val wikiPool = java.util.concurrent.Executors.newFixedThreadPool(3)
        private val wikiHttp = RoutePlanner.http.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
        const val ROUTE_LIMIT = 25
        const val AREA_LIMIT = 20
        /** Sights within reach (1.0.7). */
        const val REACH_LIMIT = 40
    }
}

/** Wikidata answers kept on the phone for a month (id -> sitelinks, article). */
internal object SiteInfoStore {
    // 1.1.0: entries now also carry the Wikivoyage page -> fresh file.
    private const val FILE = "sights_wikidata2.json"
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
