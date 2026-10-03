package com.backpack.app.route

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.Prefs
import com.backpack.app.core.LatLon
import com.backpack.app.core.Ors
import com.backpack.app.core.RouteOptions
import com.backpack.app.core.RoutePicker
import com.backpack.app.core.RoutePlan
import com.backpack.app.core.RoutePreference
import com.backpack.app.core.RouteRequest
import com.backpack.app.core.RouteResult
import com.backpack.app.core.TollBalance
import com.backpack.app.core.TravelMode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to openrouteservice. The rules are the Step 38 rules:
 * - Driving + Balanced + tolls allowed: fetch a toll and a toll-free route
 *   in parallel and let TollBalance pick (value of time vs toll estimate).
 * - Everything else: one request with alternatives; if ORS refuses the
 *   alternatives, retry without them.
 * New: route options (avoid motorways/ferries/fords/steps, bike type and
 * hills, walk/hike, green/quiet) and the "prefer paved" choice.
 *
 * Every plan() call gets a generation number; a slower, older answer can
 * never replace a newer one. Callbacks run on the main thread.
 */
class RoutePlanner(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun interface PlanCallback {
        fun onResult(plan: RoutePlan?, error: String?)
    }

    fun cancel() { generation++ }

    fun plan(req: RouteRequest, callback: PlanCallback) {
        val gen = ++generation
        val deliver = PlanCallback { plan, error ->
            main.post { if (gen == generation) callback.onResult(plan, error) }
        }
        if (req.mode == TravelMode.DRIVING && req.preference == RoutePreference.RECOMMENDED && !req.avoidTolls) {
            planBalanced(req, deliver)
        } else {
            planSingle(req, alternatives = true, deliver)
        }
    }

    private class Outcome(val routes: List<RouteResult>?, val httpCode: Int?, val body: String?, val failed: Boolean = false, val noKey: Boolean = false)

    private fun planBalanced(req: RouteRequest, cb: PlanCallback) {
        val lock = Any()
        var withTolls: Outcome? = null
        var withoutTolls: Outcome? = null
        var finished = false

        fun tryFinish() {
            val wt: Outcome
            val wo: Outcome
            synchronized(lock) {
                wt = withTolls ?: return
                wo = withoutTolls ?: return
                // Both answers can land at the same moment: deliver once.
                if (finished) return
                finished = true
            }
            val a = wt.routes?.firstOrNull()
            val b = wo.routes?.firstOrNull()
            val plan = TollBalance.plan(a, b, req.valuePerMinute, req.tollRatePerKm)
            if (plan == null) {
                val code = wt.httpCode ?: wo.httpCode
                cb.onResult(null, when {
                    wt.noKey -> NO_KEY
                    code != null -> Ors.friendlyError(code, wt.body ?: wo.body)
                    else -> "Route request failed"
                })
            } else {
                cb.onResult(plan, null)
            }
        }

        // Both fired at once: they run in parallel on OkHttp's dispatcher.
        fetch(req, req.preference, avoidTolls = false, alternatives = false) { o ->
            synchronized(lock) { withTolls = o }
            tryFinish()
        }
        fetch(req, req.preference, avoidTolls = true, alternatives = false) { o ->
            synchronized(lock) { withoutTolls = o }
            tryFinish()
        }
    }

    private fun planSingle(req: RouteRequest, alternatives: Boolean, cb: PlanCallback) {
        fetch(req, req.preference, req.avoidTolls, alternatives) { o ->
            val routes = o.routes
            when {
                routes != null && routes.isNotEmpty() && routes[0].points.isNotEmpty() -> {
                    val plan = if (req.options.preferPaved(req.mode)) RoutePicker.preferPaved(routes)
                    else RoutePlan(routes, 0, null)
                    cb.onResult(plan, null)
                }
                alternatives && !o.failed && o.httpCode != null && o.httpCode !in setOf(401, 403, 429) ->
                    planSingle(req, alternatives = false, cb)
                o.noKey -> cb.onResult(null, NO_KEY)
                o.httpCode != null -> cb.onResult(null, Ors.friendlyError(o.httpCode, o.body))
                routes != null -> cb.onResult(null, "Route returned no geometry")
                else -> cb.onResult(null, "Route request failed -- check the connection")
            }
        }
    }

    private fun fetch(
        req: RouteRequest,
        preference: RoutePreference,
        avoidTolls: Boolean,
        alternatives: Boolean,
        keyIndex: Int = 0,
        done: (Outcome) -> Unit
    ) {
        val keys = Prefs.orsKeys(app)
        val baseUrl = Prefs.orsBaseUrl(app)
        // A self-hosted ORS server may need no key at all.
        if (keys.isEmpty() && baseUrl == Prefs.DEFAULT_ORS_URL) {
            // Builds without built-in keys (e.g. F-Droid): your own free key is needed.
            done(Outcome(null, null, null, failed = true, noKey = true))
            return
        }
        val coords = mutableListOf(req.from).apply { addAll(req.waypoints); add(req.to) }
        val extra = mutableListOf("surface")
        if (req.mode == TravelMode.DRIVING) extra.add("tollways")
        val body = Ors.directionsBody(
            coords,
            preference.orsParam,
            req.options.avoidFeatures(req.mode, avoidTolls),
            req.options.profileParams(req.mode),
            extra,
            alternatives
        )
        val builder = Request.Builder()
            .url("$baseUrl/v2/directions/${req.options.orsProfile(req.mode)}/geojson")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, application/geo+json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        keys.getOrNull(keyIndex)?.let { builder.addHeader("Authorization", it) }

        http.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                done(Outcome(null, null, null, failed = true))
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.use { it.body?.string() }
                // Key refused or daily quota used: try the next key.
                if ((response.code == 401 || response.code == 403) && keyIndex + 1 < keys.size) {
                    fetch(req, preference, avoidTolls, alternatives, keyIndex + 1, done)
                    return
                }
                if (!response.isSuccessful || text == null) {
                    done(Outcome(null, response.code, text))
                    return
                }
                val routes = try { Ors.parseRoutes(text) } catch (e: Exception) { null }
                done(Outcome(routes, if (routes == null) response.code else null, text))
            }
        })
    }

    // ---------- Address search (ORS geocoder, as before; Photon in 0.7) ----------

    fun interface GeocodeCallback {
        fun onResult(point: LatLon?, label: String?, error: String?)
    }

    fun geocode(text: String, focus: LatLon?, keyIndex: Int = 0, callback: GeocodeCallback) {
        val keys = Prefs.orsKeys(app)
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        // Nearby results first, but only a ~1 km area is sent, not the exact position.
        val focusPart = focus?.let {
            "&focus.point.lat=%.2f&focus.point.lon=%.2f".format(java.util.Locale.ROOT, it.lat, it.lon)
        } ?: ""
        val keyPart = keys.getOrNull(keyIndex)?.let { "&api_key=$it" } ?: ""
        val url = "${Prefs.orsBaseUrl(app)}/geocode/search?text=$encoded&size=1$focusPart$keyPart"
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { callback.onResult(null, null, "Search failed -- check the connection") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() }
                if ((response.code == 401 || response.code == 403) && keyIndex + 1 < keys.size) {
                    geocode(text, focus, keyIndex + 1, callback)
                    return
                }
                if (!response.isSuccessful || body == null) {
                    main.post { callback.onResult(null, null, "Search failed (error ${response.code})") }
                    return
                }
                var point: LatLon? = null
                var label: String? = null
                try {
                    val features = JSONObject(body).getJSONArray("features")
                    if (features.length() > 0) {
                        val f = features.getJSONObject(0)
                        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                        point = LatLon(c.getDouble(1), c.getDouble(0))
                        label = f.optJSONObject("properties")?.optString("label", "")
                    }
                } catch (e: Exception) { }
                main.post {
                    if (point == null) callback.onResult(null, null, "Address not found")
                    else callback.onResult(point, label, null)
                }
            }
        })
    }

    companion object {
        const val NO_KEY = "No routing key -- add your free openrouteservice key in Settings > Servers"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun request(
            from: LatLon, to: LatLon, waypoints: List<LatLon>, mode: TravelMode,
            preference: RoutePreference, avoidTolls: Boolean, options: RouteOptions,
            valuePerMinute: Double, tollRatePerKm: Double
        ) = RouteRequest(from, to, waypoints, mode, preference, avoidTolls, options, valuePerMinute, tollRatePerKm)
    }
}
