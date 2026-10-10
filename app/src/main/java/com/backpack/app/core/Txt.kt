package com.backpack.app.core

/**
 * 1.6.0: on-screen texts made in the plain-Kotlin core (no Android here).
 * Each text has a resource key and its English form; the app installs
 * [lookup] (Android string resources in the screen language), the JVM
 * tests run with English. Keys live in res/values/strings_core.xml.
 */
object Txt {
    /** key -> text in the screen language (null = use the English default). */
    @Volatile var lookup: ((String) -> String?)? = null

    fun s(key: String, en: String, vararg args: Any?): String {
        val pattern = try { lookup?.invoke(key) } catch (e: Exception) { null } ?: en
        return if (args.isEmpty()) pattern else try { String.format(pattern, *args) } catch (e: Exception) { String.format(en, *args) }
    }
}
