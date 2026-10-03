package com.backpack.app.net

import android.content.Context
import com.backpack.app.core.Tile
import java.io.File

/**
 * Places kept on the phone per map square, so an area already seen needs no
 * server. Stored in the app's cache folder (Android may empty it when space
 * is short; Settings > Servers & privacy empties it on request), never
 * backed up. Text per square, with the time it was fetched.
 */
class TileCache private constructor(context: Context, layer: String, private val maxAgeMs: Long) {

    private val dir = File(context.applicationContext.cacheDir, "places/$layer")

    private val memory = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > 400
    }

    /** Cached text of [tile], or null when missing or too old. */
    @Synchronized
    fun get(tile: Tile): String? {
        val now = System.currentTimeMillis()
        memory[tile.key]?.let { (at, text) -> if (now - at < maxAgeMs) return text }
        val f = File(dir, tile.key)
        if (!f.exists()) return null
        if (now - f.lastModified() >= maxAgeMs) { f.delete(); return null }
        return try {
            f.readText().also { memory[tile.key] = f.lastModified() to it }
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun put(tile: Tile, text: String) {
        val now = System.currentTimeMillis()
        memory[tile.key] = now to text
        try {
            dir.mkdirs()
            File(dir, tile.key).writeText(text)
        } catch (e: Exception) {
            // Memory copy still works for this session.
        }
    }

    @Synchronized
    private fun dropMemory() = memory.clear()

    companion object {
        private val instances = HashMap<String, TileCache>()

        /** One cache per layer ("sights", "FUEL"...). */
        @Synchronized
        fun of(context: Context, layer: String, maxAgeMs: Long): TileCache =
            instances.getOrPut(layer) { TileCache(context, layer, maxAgeMs) }

        /** Empties every place cache (Settings > Servers & privacy). */
        @Synchronized
        fun clearAll(context: Context) {
            instances.values.forEach { it.dropMemory() }
            File(context.applicationContext.cacheDir, "places").deleteRecursively()
        }
    }
}
