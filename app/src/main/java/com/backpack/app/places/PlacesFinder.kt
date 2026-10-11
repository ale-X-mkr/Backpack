package com.backpack.app.places

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.L10n
import com.backpack.app.Prefs
import com.backpack.app.R
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
    class Category(val layer: String, val overpassFilter: String, val orsIds: Map<Int, String>) {
        /** 1.1.1: one request for all services of the squares (or one filter). */
        var query: (List<Tile>) -> String = { tiles -> Places.tileQuery(overpassFilter, tiles) }
        var maxShown: Int = MAX_SHOWN
        /** 1.2.1: services kept per service ("svc_<NAME>" caches): the ones
         *  wanted now (ticked) are fetched first... */
        var services: (() -> List<String>)? = null
        /** ...and these (the rest of their groups) later, in the background. */
        var backgroundServices: (() -> List<String>)? = null
    }

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
        val center = LatLon((south + north) / 2, (west + east) / 2)
        val tiles = Tiles.inBox(south, west, north, east, max = 16)
        search(category, tiles, center, onSwitch, callback) { p ->
            if (p.point.lat in south..north && p.point.lon in west..east) p to Geo.distance(p.point, center) else null
        }
    }

    /**
     * 1.0.9: along route lines (the route ahead), places at most [radiusM]
     * off the nearest line, in route order; squares from the cache first.
     */
    fun alongRoutes(category: Category, lines: List<List<LatLon>>, radiusM: Double, onSwitch: () -> Unit, callback: ResultCallback) {
        // Matched on thinned lines (≤ 400 points): fast, and accurate enough for the corridor.
        val polylines = lines.filter { it.size >= 2 }
            .map { com.backpack.app.core.Polyline(com.backpack.app.core.Tourist.sampleLine(it, maxPoints = 400, minStepM = 50.0)) }
        if (polylines.isEmpty()) { generation++; callback.onResult(emptyList(), null, null, false); return }
        val tiles = Tiles.alongLinesOrdered(lines, radiusM).take(MAX_ROUTE_TILES).map { it.first }
        // Second source (Overpass down) asks around a point ~2 km ahead, not where you are.
        val first = com.backpack.app.core.Polyline(lines.first())
        val center = com.backpack.app.core.SpeedLimits.pointAt(first, minOf(2000.0, first.length))
        search(category, tiles, center, onSwitch, callback) { p ->
            val best = polylines.mapNotNull { it.snap(p.point) }.minByOrNull { it.offsetM }
            if (best == null || best.offsetM > radiusM) null else p.copy(offsetM = best.offsetM) to best.alongM
        }
    }

    /** 1.0.9: inside the reach area [ring] around [origin], nearest first
     *  (the squares nearest the origin, at most [MAX_AREA_TILES]). */
    fun inReach(category: Category, ring: List<LatLon>, origin: LatLon, onSwitch: () -> Unit, callback: ResultCallback) {
        val area = com.backpack.app.core.Reach.thin(ring, 300) // fast inside-test
        val b = com.backpack.app.core.Reach.bounds(area)
        val tiles = Tiles.inBox(b[0], b[1], b[2], b[3], max = Int.MAX_VALUE)
            .sortedBy { Geo.distance(LatLon(it.south + Tiles.SIZE / 2, it.west + Tiles.SIZE / 2), origin) }
            .take(MAX_AREA_TILES)
        search(category, tiles, origin, onSwitch, callback) { p ->
            if (com.backpack.app.core.Reach.contains(area, p.point)) p to Geo.distance(p.point, origin) else null
        }
    }

    /** [rank]: null = left out, else the place (maybe with its offset) and the sort key (smaller first). */
    private fun search(category: Category, tiles: List<Tile>, center: LatLon, onSwitch: () -> Unit, callback: ResultCallback, rank: (PoiPlace) -> Pair<PoiPlace, Double>?) {
        val gen = ++generation
        category.services?.invoke()?.let { names -> searchServices(gen, category, names, tiles, center, onSwitch, callback, rank); return }
        val cache = TileCache.of(app, category.layer, 3L * 24 * 3600_000L)
        // Cache files are read and written off the screen's thread.
        io.execute {
            val found = ArrayList<PoiPlace>()
            val missing = ArrayList<Tile>()
            for (t in tiles) {
                val list = cache.get(t)?.let { try { Places.fromJson(it) } catch (e: Exception) { null } }
                if (list == null) missing.add(t) else found.addAll(list)
            }
            main.post {
                if (gen == generation) fetchMissing(gen, category, if (missing.isEmpty()) null else category.query(missing),
                    { parsed -> for ((t, list) in Places.byTile(parsed, missing)) cache.put(t, Places.toJson(list)) },
                    center, found, onSwitch, callback, rank)
            }
        }
    }

    // ---------- 1.2.1 services: per service, ticked first, the rest in the background ----------

    private fun serviceCache(name: String) = TileCache.of(app, "svc_$name", 3L * 24 * 3600_000L)

    /** (squares, services) still missing for [names] among [tiles]; reads what is on the phone into [found]. */
    private fun missingServices(names: List<String>, tiles: List<Tile>, found: MutableMap<String, PoiPlace>?): Pair<List<Tile>, Set<String>> {
        val mt = ArrayList<Tile>()
        val mn = LinkedHashSet<String>()
        for (t in tiles) {
            var miss = false
            for (n in names) {
                val list = serviceCache(n).get(t)?.let { try { Places.fromJson(it) } catch (e: Exception) { null } }
                if (list == null) { miss = true; mn.add(n) } else found?.let { f -> for (p in list) f.putIfAbsent(p.key, p) }
            }
            if (miss) mt.add(t)
        }
        return mt to mn
    }

    /** Stores an answer per service and square (empty lists too: "none here"). */
    private fun storeServices(parsed: List<PoiPlace>, tiles: List<Tile>, names: Collection<String>) {
        for (t in tiles) for (n in names) serviceCache(n).put(t, Places.toJson(Places.ofService(parsed, n, t)))
    }

    private fun searchServices(
        gen: Int, category: Category, names: List<String>, tiles: List<Tile>, center: LatLon,
        onSwitch: () -> Unit, callback: ResultCallback, rank: (PoiPlace) -> Pair<PoiPlace, Double>?
    ) {
        io.execute {
            val found = LinkedHashMap<String, PoiPlace>()
            val (mt, mn) = missingServices(names, tiles, found)
            val query = if (mt.isEmpty()) null else Places.servicesQuery(mt, names = mn)
            main.post {
                if (gen != generation) return@post
                fetchMissing(gen, category, query, { parsed -> storeServices(parsed, mt, mn) },
                    center, found.values.toList(), onSwitch, callback, rank,
                    afterSuccess = { fillInBackground(category, tiles) })
            }
        }
    }

    /** The other services of the groups ticked, for the same squares: later,
     *  when the servers are idle, so ticking one more is instant there. */
    private fun fillInBackground(category: Category, tiles: List<Tile>) {
        val names = category.backgroundServices?.invoke().orEmpty()
        if (names.isEmpty() || backgroundBusy) return
        backgroundBusy = true
        io.execute {
            val (mt, mn) = missingServices(names, tiles.take(MAX_BACKGROUND_TILES), null)
            if (mt.isEmpty()) { backgroundBusy = false; return@execute }
            main.post {
                OverpassClient.query(app, Places.servicesQuery(mt, names = mn), singleRound = true, background = true) { body, _ ->
                    if (body == null) { backgroundBusy = false; return@query }
                    io.execute {
                        try { Places.parseOverpass(body).let { storeServices(it, mt, mn) } } catch (e: Exception) { }
                        backgroundBusy = false
                    }
                }
            }
        }
    }

    /**
     * 1.2.1: the services you ticked along the whole route, fetched ahead in
     * the background (nearest squares first, 25 per request, only when the
     * servers are idle), so they are on the phone when you get there. A new
     * route replaces the old prefetch.
     */
    fun prefetchAlong(category: Category, line: List<LatLon>, radiusM: Double) {
        val names = category.services?.invoke().orEmpty()
        if (names.isEmpty() || line.size < 2) return
        val gen = ++prefetchGen
        io.execute {
            val tiles = Tiles.alongLinesOrdered(listOf(line), radiusM).take(MAX_PREFETCH_TILES).map { it.first }
            val (mt, mn) = missingServices(names, tiles, null)
            if (mt.isEmpty()) return@execute
            for (chunk in mt.chunked(25)) {
                main.post {
                    OverpassClient.query(app, Places.servicesQuery(chunk, names = mn), singleRound = true, background = true,
                        isStale = { gen != prefetchGen }) { body, _ ->
                        if (body != null) io.execute { try { storeServices(Places.parseOverpass(body), chunk, mn) } catch (e: Exception) { } }
                    }
                }
            }
        }
    }

    fun cancelPrefetch() { prefetchGen++ }

    private fun fetchMissing(
        gen: Int, category: Category, query: String?, store: (List<PoiPlace>) -> Unit, center: LatLon,
        found: List<PoiPlace>, onSwitch: () -> Unit, callback: ResultCallback,
        rank: (PoiPlace) -> Pair<PoiPlace, Double>?, afterSuccess: () -> Unit = {}
    ) {
        fun finish(extra: List<PoiPlace>, error: String?, note: String?, serverFailed: Boolean) {
            if (gen != generation) return
            // Matching against the route / area off the screen's thread (1.0.9).
            io.execute {
                val all = (found + extra).distinctBy { it.key }
                    .mapNotNull { p -> rank(p) }
                    .sortedBy { it.second }
                    .map { it.first }
                    .take(category.maxShown)
                main.post {
                    if (gen != generation) return@post
                    if (all.isEmpty() && error != null) callback.onResult(null, error, null, true)
                    else callback.onResult(all, null, note ?: error?.let { L10n.str(app, R.string.other_places_some_missing, it) }, serverFailed)
                }
            }
        }
        if (query == null) { finish(emptyList(), null, null, false); afterSuccess(); return }
        OverpassClient.query(app, query, onSwitch = { if (gen == generation) onSwitch() }, isStale = { gen != generation }) { body, failure ->
            if (body != null) {
                io.execute {
                    // Stored even if a newer search came meanwhile: nothing wasted.
                    val parsed = try { Places.parseOverpass(body) } catch (e: Exception) { null }
                    if (parsed != null) store(parsed)
                    main.post {
                        if (parsed != null) afterSuccess()
                        if (gen != generation) return@post
                        if (parsed != null) finish(parsed, null, null, false)
                        else fallback(gen, category, center, OverpassClient.Failure.BAD_ANSWER.message, ::finish)
                    }
                }
                return@query
            }
            if (gen != generation) return@query
            fallback(gen, category, center, failure?.message ?: L10n.str(app, R.string.other_places_failed), ::finish)
        }
    }

    /** Second source: openrouteservice places around the centre. */
    private fun fallback(
        gen: Int, category: Category, center: LatLon,
        error: String, finish: (List<PoiPlace>, String?, String?, Boolean) -> Unit
    ) {
        // Around the centre / start (the ranking above keeps only what fits).
        orsPlaces(category, center, 2000) { list ->
            if (gen != generation) return@orsPlaces
            if (list == null) finish(emptyList(), error, null, true)
            else finish(list, null, L10n.str(app, R.string.other_places_ors_fallback), true)
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
            val req = Request.Builder().url(Prefs.ORS_POIS_URL)
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
        private const val MAX_ROUTE_TILES = 60
        private const val MAX_AREA_TILES = 36
        /** 1.2.1: squares filled in the background per search (the nearest). */
        private const val MAX_BACKGROUND_TILES = 25
        @Volatile private var backgroundBusy = false
        @Volatile private var prefetchGen = 0
        /** Whole route: up to ~150 squares (a long trip in a narrow corridor). */
        private const val MAX_PREFETCH_TILES = 150
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var orsFailedAtMs = 0L
    }
}
