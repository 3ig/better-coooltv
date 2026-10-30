package dev.bettercoool.tv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * App language. By default the TV's system language is used (English → English, anything else →
 * Bulgarian, which is the base resource set). Settings can pin Bulgarian or English instead.
 * Applied in [MainActivity.attachBaseContext]; changing it recreates the activity.
 */
object AppLocale {
    const val BULGARIAN = "bg"
    const val ENGLISH = "en"

    private const val PREFS = "session"
    private const val KEY = "language"

    /** The pinned language, or null to follow the system. */
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Pass null to follow the system language again. */
    fun set(context: Context, language: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    /** The language the UI actually shows: "bg" or "en". */
    fun effective(context: Context): String =
        get(context) ?: if (systemLanguage() == ENGLISH) ENGLISH else BULGARIAN

    fun wrap(base: Context): Context {
        val pinned = get(base)
        if (pinned == null) {
            Locale.setDefault(Resources.getSystem().configuration.locales[0]) // undo an earlier pin
            return base
        }
        val locale = Locale(pinned)
        // Default locale also drives date formatting and track-language names.
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun systemLanguage(): String = Resources.getSystem().configuration.locales[0].language
}
