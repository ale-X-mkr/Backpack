package com.backpack.app.tourist

import android.os.Handler
import android.os.Looper
import com.backpack.app.core.LatLon
import com.backpack.app.core.Tourist
import com.backpack.app.net.OverpassClient
import com.backpack.app.route.RoutePlanner
import okhttp3.Request
import org.json.JSONObject

/**
 * 1.1.0: the Wikivoyage travel guide of the town at a point (the
 * destination). Nominatim (town level, one request, its usage policy) gives
 * the town's Wikidata id, Wikidata its Wikivoyage page. Kept in memory per
 * point (~1 km), so tapping the destination again asks nothing.
 */
object TravelGuide {

    /** [name] null = nothing found (no town / no connection). */
    fun interface Done { fun onGuide(name: String?, url: String?) }

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val memory = HashMap<String, Pair<String?, String?>>()

    fun forPoint(point: LatLon, done: Done) {
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, point.lat, point.lon)
        synchronized(memory) { memory[key] }?.let { done.onGuide(it.first, it.second); return }
        io.execute {
            val lang = when (val l = java.util.Locale.getDefault().language) { "in" -> "id"; "iw" -> "he"; "nb" -> "no"; else -> l }.ifBlank { "en" }
            fun get(url: String): String? = try {
                RoutePlanner.http.newCall(Request.Builder().url(url).addHeader("User-Agent", OverpassClient.USER_AGENT)
                    .addHeader("Accept", "application/json").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: Exception) { null }
            val reverse = get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&extratags=1&accept-language=$lang" +
                "&lat=%.5f&lon=%.5f".format(java.util.Locale.ROOT, point.lat, point.lon))
            val town = reverse?.let { try { Tourist.parseReverseTown(it) } catch (e: Exception) { null } }
            var url: String? = null
            val q = town?.second
            if (q != null) {
                val body = get(Tourist.wikidataUrl(listOf(q)))
                url = body?.let {
                    try {
                        val links = JSONObject(it).optJSONObject("entities")?.optJSONObject(q)?.optJSONObject("sitelinks")
                        links?.let { l -> Tourist.voyageUrl(l, listOf(lang, "en").distinct()) }
                    } catch (e: Exception) { null }
                }
            }
            val result = town?.first to url
            if (reverse != null) synchronized(memory) { memory[key] = result }
            main.post { done.onGuide(result.first, result.second) }
        }
    }
}
