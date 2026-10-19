// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * ux-6: language of the app's own screens, chosen inside the settings (not in the system "App languages").
 * Android 13+: the system's per-app language (LocaleManager), applied at once to all screens of the app.
 * Older Android: the tag is kept in the preferences and applied by [wrap] (SettingsActivity.attachBaseContext) + recreate().
 * It does not touch the keyboard layouts and languages (those are the "Languages & Layouts" item).
 */
object AppLanguage {
    const val PREF_APP_LANGUAGE = "app_language_tag"
    /** the value for "as in the system" */
    const val SYSTEM = ""

    /** language tags of res/xml/locales_config.xml (the list the system's "App languages" shows too) */
    fun availableTags(context: Context): List<String> {
        val tags = ArrayList<String>()
        val parser = context.resources.getXml(R.xml.locales_config)
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale")
                    parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let { tags.add(it) }
            }
        } finally {
            parser.close()
        }
        return sortedTags(tags)
    }

    /** by the name in its own language, so the order is the same on every interface language */
    internal fun sortedTags(tags: List<String>) = tags.distinct().sortedBy { nativeName(it).lowercase(Locale.ROOT) }

    /** "Русский", "Español", "English (United Kingdom)": the language's name in that language */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(locale)
        return if (name.isEmpty()) tag else name.replaceFirstChar { it.titlecase(locale) }
    }

    /** the chosen tag, [SYSTEM] if the language follows the system */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM else locales[0].toLanguageTag()
        }
        return context.prefs().getString(PREF_APP_LANGUAGE, SYSTEM) ?: SYSTEM
    }

    /** applies the language at once; on Android 13+ the system restarts the open screens itself */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.prefs().edit { putString(PREF_APP_LANGUAGE, tag) }
            activity.recreate()
        }
    }

    /** Android older than 13: the context of a screen with the chosen language (newer Android does it by itself) */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.prefs().getString(PREF_APP_LANGUAGE, SYSTEM) ?: SYSTEM
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
