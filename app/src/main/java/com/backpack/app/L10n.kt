package com.backpack.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.backpack.app.core.Txt

/**
 * 1.6.0: screen language (Settings ▸ Navigation ▸ Screen language): the
 * phone's language when it is one of ours, or a fixed one. Voice language
 * stays separate. Texts: the strings files in res/values, values-es, -fr, -it, -de.
 */
object L10n {
    /** "" = the phone's language. */
    val LANGUAGES = listOf("" to "Auto", "en" to "English", "es" to "Español", "fr" to "Français", "it" to "Italiano", "de" to "Deutsch")

    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-')

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    /** A context in the screen language (for the service, the core texts). */
    fun context(c: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return c
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(locales.get(0))
        return c.createConfigurationContext(cfg)
    }

    /** Core texts read from the string resources in the screen language. */
    fun install(c: Context) {
        val app = c.applicationContext
        Txt.lookup = { key ->
            val ctx = context(app)
            val id = ctx.resources.getIdentifier(key, "string", app.packageName)
            if (id != 0) ctx.getString(id) else null
        }
    }
}
