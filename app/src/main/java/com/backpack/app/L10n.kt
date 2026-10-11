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

    private const val PREFS = "backpack_l10n"
    private const val KEY = "screen_lang"
    @Volatile private var saved: String? = null

    fun set(tag: String) {
        saved = tag
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, tag)?.apply()
        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    private var appContext: Context? = null

    /** A context in the screen language (for the service, the core texts). Also
     *  before any screen was opened (Android 12 and older load AppCompat's
     *  choice only with the first screen): our own saved copy then. */
    fun context(c: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        val locale = if (!locales.isEmpty) locales.get(0) else {
            val tag = saved ?: c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().also { saved = it }
            if (tag.isEmpty()) null else java.util.Locale.forLanguageTag(tag)
        } ?: return c
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(locale)
        return c.createConfigurationContext(cfg)
    }

    /** A string resource in the screen language (also from the app context or a service). */
    fun str(c: Context, id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context(c).getString(id) else context(c).getString(id, *args)

    /** Core texts read from the string resources in the screen language. */
    fun install(c: Context) {
        val app = c.applicationContext
        appContext = app
        Txt.lookup = { key ->
            val ctx = context(app)
            val id = ctx.resources.getIdentifier(key, "string", app.packageName)
            if (id != 0) ctx.getString(id) else null
        }
    }
}
