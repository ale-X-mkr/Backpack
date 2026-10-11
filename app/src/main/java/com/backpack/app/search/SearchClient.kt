package com.backpack.app.search

import android.os.Handler
import android.os.Looper
import com.backpack.app.core.Geocode
import com.backpack.app.core.LatLon
import com.backpack.app.core.Place
import com.backpack.app.core.Txt
import com.backpack.app.route.RoutePlanner
import com.backpack.app.tourist.TouristFinder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Place search: Photon while typing, Nominatim only on submit and only if
 * Photon found nothing or failed (Nominatim's policy forbids
 * search-as-you-type). Typed coordinates are handled without any server.
 * Only the newest search answers; callbacks on the main thread.
 */
class SearchClient {

    fun interface ResultCallback {
        fun onResult(places: List<Place>, error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var inFlight: Call? = null

    fun cancel() {
        generation++
        inFlight?.cancel()
        inFlight = null
    }

    /** Suggestions while typing (Photon only). */
    fun suggest(text: String, near: LatLon?, callback: ResultCallback) {
        Geocode.parseCoordinates(text)?.let { p ->
            cancel()
            callback.onResult(listOf(Place("%.5f, %.5f".format(p.lat, p.lon), Txt.s("other_search_coordinates", "Coordinates"), p)), null)
            return
        }
        val gen = newGeneration()
        get(Geocode.photonUrl(text, near)) { body, error ->
            val places = body?.let { try { Geocode.parsePhoton(it) } catch (e: Exception) { null } }
            post(gen) { callback.onResult(places ?: emptyList(), if (places == null) error ?: Txt.s("other_search_failed", "Search failed") else null) }
        }
    }

    /** Search button / keyboard Enter: Photon, then Nominatim as a fallback. */
    fun search(text: String, near: LatLon?, callback: ResultCallback) {
        Geocode.parseCoordinates(text)?.let { p ->
            cancel()
            callback.onResult(listOf(Place("%.5f, %.5f".format(p.lat, p.lon), Txt.s("other_search_coordinates", "Coordinates"), p)), null)
            return
        }
        val gen = newGeneration()
        get(Geocode.photonUrl(text, near)) { body, _ ->
            val photon = body?.let { try { Geocode.parsePhoton(it) } catch (e: Exception) { null } }
            if (!photon.isNullOrEmpty()) {
                post(gen) { callback.onResult(photon, null) }
                return@get
            }
            if (gen != generation) return@get
            get(Geocode.nominatimUrl(text)) { nBody, nError ->
                val places = nBody?.let { try { Geocode.parseNominatim(it) } catch (e: Exception) { null } }
                post(gen) {
                    when {
                        places == null -> callback.onResult(emptyList(), nError ?: Txt.s("other_search_failed_connection", "Search failed -- check the connection"))
                        places.isEmpty() -> callback.onResult(emptyList(), Txt.s("other_address_not_found", "Address not found"))
                        else -> callback.onResult(places, null)
                    }
                }
            }
        }
    }

    private fun newGeneration(): Int {
        inFlight?.cancel()
        return ++generation
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    private fun get(url: String, done: (String?, String?) -> Unit) {
        val call = RoutePlanner.http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", TouristFinder.USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()
        )
        inFlight = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(null, Txt.s("other_search_failed_connection", "Search failed -- check the connection"))
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                done(body, if (body == null) Txt.s("other_search_error_http", "Search error (HTTP %1\$d)", response.code) else null)
            }
        })
    }
}
