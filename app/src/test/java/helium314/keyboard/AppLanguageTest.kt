// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppLanguage
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ux-6: the language of the app's screens chosen inside the settings. */
@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun listComesFromLocalesConfig() {
        val tags = AppLanguage.availableTags(context)
        assertEquals(104, tags.size)
        listOf("en", "ru", "es", "de", "fr", "ar", "hi", "zh-CN").forEach { assertTrue(it in tags, it) }
        assertEquals(tags.size, tags.distinct().size)
    }

    @Test fun namesAreInTheLanguagesThemselves() {
        assertEquals("Русский", AppLanguage.nativeName("ru"))
        assertEquals("Español", AppLanguage.nativeName("es"))
        assertEquals("English", AppLanguage.nativeName("en"))
        assertEquals("Deutsch", AppLanguage.nativeName("de"))
        assertEquals("Français", AppLanguage.nativeName("fr"))
    }

    @Test fun followsTheSystemByDefault() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.current(context))
    }

    @Test @Config(sdk = [34]) fun androidThirteenPlusUsesTheSystemPerAppLanguage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLanguage.set(activity, "ru")
        assertEquals("ru", AppLanguage.current(activity))
        AppLanguage.set(activity, AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, AppLanguage.current(activity))
    }

    @Test @Config(sdk = [30]) fun olderAndroidKeepsTheTagAndWrapsTheContext() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLanguage.set(activity, "ru")
        assertEquals("ru", AppLanguage.current(activity))
        val wrapped = AppLanguage.wrap(context)
        assertEquals("Как в системе", wrapped.getString(helium314.keyboard.latin.R.string.app_language_system))
        context.prefs().edit { putString(AppLanguage.PREF_APP_LANGUAGE, AppLanguage.SYSTEM) }
        assertEquals("As in the system", AppLanguage.wrap(context).getString(helium314.keyboard.latin.R.string.app_language_system))
    }
}
