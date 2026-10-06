package com.backpack.app.tourist

import android.os.Handler
import android.os.Looper
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.net.OverpassClient
import com.backpack.app.route.RoutePlanner
import okhttp3.Request

/**
 * 1.2.0 tourist guide: the first paragraph of a sight's English Wikipedia
 * article, to be read aloud (the voice speaks English). The title comes
 * from the place's own link, else from its Wikidata item (one request);
 * the text from Wikipedia's page summary (one request). Kept in memory.
 */
object WikiSummary {

    /** [text] null = no English article (or no connection). */
    fun interface Done { fun onSummary(text: String?) }

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val memory = HashMap<String, String?>()
    private const val MAX_CHARS = 3000

    fun forPoi(poi: TouristPoi, done: Done) {
        val key = poi.identity
        synchronized(memory) { if (memory.containsKey(key)) { val t = memory[key]; done.onSummary(t); return } }
        io.execute {
            fun get(url: String): String? = try {
                RoutePlanner.http.newCall(Request.Builder().url(url).addHeader("User-Agent", OverpassClient.USER_AGENT)
                    .addHeader("Accept", "application/json").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: Exception) { null }
            var reached = true
            var title = Tourist.englishTitle(poi.articleUrl, poi.wikipedia)
            if (title == null && poi.wikidata.isNotEmpty()) {
                val body = get(Tourist.enwikiTitleUrl(poi.wikidata))
                if (body == null) reached = false
                title = body?.let { try { Tourist.parseEnwikiTitle(it, poi.wikidata) } catch (e: Exception) { null } }
            }
            var text: String? = null
            if (title != null) {
                val body = get(Tourist.summaryUrl(title))
                if (body == null) reached = false
                text = body?.let { try { Tourist.parseSummary(it) } catch (e: Exception) { null } }?.take(MAX_CHARS)
            }
            // A failed connection is not remembered: asked again next time.
            if (reached) synchronized(memory) { memory[key] = text }
            main.post { done.onSummary(text) }
        }
    }
}
