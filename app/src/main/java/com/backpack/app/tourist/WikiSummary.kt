package com.backpack.app.tourist

import android.os.Handler
import android.os.Looper
import com.backpack.app.core.Tourist
import com.backpack.app.core.TouristPoi
import com.backpack.app.net.OverpassClient
import com.backpack.app.route.RoutePlanner
import okhttp3.Request

/**
 * Tourist guide: the first paragraph of a sight's Wikipedia article, to be
 * read aloud. 1.3.0: in the voice language when the place has an article
 * in it, else in English (read with an English voice). The title comes from
 * the place's own link, else from its Wikidata item (one request); the text
 * from Wikipedia's page summary (one request). Kept in memory.
 */
object WikiSummary {

    /** [text] null = no article (or no connection); [lang] = language of the text. */
    fun interface Done { fun onSummary(text: String?, lang: String) }

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val memory = HashMap<String, Pair<String?, String>>()
    private const val MAX_CHARS = 3000

    fun forPoi(poi: TouristPoi, lang: String, done: Done) {
        val key = poi.identity + "/" + lang
        synchronized(memory) { memory[key] }?.let { done.onSummary(it.first, it.second); return }
        io.execute {
            var reached = true
            fun get(url: String): String? = try {
                RoutePlanner.http.newCall(Request.Builder().url(url).addHeader("User-Agent", OverpassClient.USER_AGENT)
                    .addHeader("Accept", "application/json").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: Exception) { reached = false; null }
            fun summaryIn(l: String): String? {
                var title = Tourist.titleIn(l, poi.articleUrl, poi.wikipedia)
                if (title == null && poi.wikidata.isNotEmpty()) {
                    title = get(Tourist.wikiTitleUrl(poi.wikidata, l))?.let { try { Tourist.parseWikiTitle(it, poi.wikidata, l) } catch (e: Exception) { null } }
                }
                if (title == null) return null
                return get(Tourist.summaryUrl(title, l))?.let { try { Tourist.parseSummary(it) } catch (e: Exception) { null } }?.take(MAX_CHARS)
            }
            var used = lang
            var text = summaryIn(lang)
            if (text == null && lang != "en") { used = "en"; text = summaryIn("en") }
            val result = text to used
            // A failed connection is not remembered: asked again next time.
            if (reached) synchronized(memory) { memory[key] = result }
            main.post { done.onSummary(result.first, result.second) }
        }
    }
}
