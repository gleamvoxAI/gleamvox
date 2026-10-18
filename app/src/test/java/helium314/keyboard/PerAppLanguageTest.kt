// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.text.InputType
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeState
import helium314.keyboard.latin.utils.isLanguageNeutralField
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 5g-9 (the owner: "I came into an app where I had Russian, and the keyboard chose English"): the language the
 * user chose in an app is remembered by default, and it is stronger than the language hint of the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class PerAppLanguageTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val ru: InputMethodSubtype get() = SubtypeSettings.getResourceSubtypesForLocale("ru".constructLocale()).first()
    private val en: InputMethodSubtype get() = SubtypeSettings.getResourceSubtypesForLocale("en_US".constructLocale()).first()
    private val state = SubtypeState { }

    init { ShadowLog.setupLogging(); ShadowLog.stream = System.out }

    @BeforeTest fun setUp() {
        val prefs = latinIME.prefs()
        prefs.edit().clear().apply()
        SubtypeSettings.addEnabledSubtype(prefs, ru)
        SubtypeSettings.addEnabledSubtype(prefs, en)
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
    }

    /** the preferences are shared between tests: leave no enabled languages and no saved apps behind */
    @AfterTest fun tearDown() {
        latinIME.prefs().edit().clear().apply()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
    }

    private fun current(subtype: InputMethodSubtype) = latinIME.switchToSubtype(subtype)
    private fun rich(s: InputMethodSubtype) = RichInputMethodSubtype.get(s)
    private fun locales(vararg tags: String): MutableList<Locale> = tags.map { it.constructLocale() }.toMutableList()
    private fun lang(s: InputMethodSubtype?) = s?.let { rich(it).locale.language }

    @Test fun rememberedByDefaultAlsoOnUpdatedPhones() {
        assertTrue(Defaults.PREF_SAVE_SUBTYPE_PER_APP)
        val prefs = latinIME.prefs()
        assertFalse(prefs.contains(Settings.PREF_SAVE_SUBTYPE_PER_APP), "nothing stored: an old install gets the new default")
        Settings.getInstance().saveSubtypeForApp(rich(ru), "com.example.chat")
        assertEquals("ru", Settings.getInstance().getSubtypeForApp("com.example.chat")?.locale?.language)
        // somebody who switched it off by hand keeps it off
        prefs.edit().putBoolean(Settings.PREF_SAVE_SUBTYPE_PER_APP, false).apply()
        assertNull(Settings.getInstance().getSubtypeForApp("com.example.chat"))
    }

    /** the owner's case: the app hints English, the user chose Russian in this app before: Russian */
    @Test fun savedLanguageBeatsTheHintOfTheField() {
        current(en)
        val saved = rich(ru)
        assertEquals("ru", lang(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales("en"), saved)))
        // and without a hint as well
        assertEquals("ru", lang(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales(), saved)))
    }

    @Test fun savedLanguageThatIsAlreadyInUseDoesNotSwitch() {
        current(ru)
        assertNull(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales("en"), rich(ru)))
    }

    /** a new app: nothing saved, the field hints English: English */
    @Test fun withoutASavedLanguageTheHintIsUsed() {
        current(ru)
        assertEquals("en", lang(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales("en"), null)))
    }

    /** a new app, no hint: the keyboard stays with the last language in use */
    @Test fun withoutAHintAndWithoutASavedLanguageNothingChanges() {
        current(ru)
        assertNull(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales(), null))
        current(en)
        assertNull(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales(), null))
    }

    /** the language of the hint is given up again when the next context has neither a hint nor a saved language */
    @Test fun hintSwitchIsTakenBack() {
        current(ru)
        assertEquals("en", lang(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales("en"), null)))
        current(en) // the keyboard followed the hint
        assertEquals("ru", lang(state.getSubtypeForLocales(RichInputMethodManager.getInstance(), locales(), null)))
    }

    @Test fun passwordEmailAndAddressFieldsDoNotSetTheLanguageOfTheApp() {
        val text = InputType.TYPE_CLASS_TEXT
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI))
            assertTrue(isLanguageNeutralField(text or v), "variation $v")
        assertTrue(isLanguageNeutralField(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isLanguageNeutralField(text))
        assertFalse(isLanguageNeutralField(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(isLanguageNeutralField(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
    }

    @Test fun savedLanguagesStayOutOfTheBackup() {
        val prefs = latinIME.prefs()
        Settings.getInstance().saveSubtypeForApp(rich(ru), "com.example.chat")
        prefs.edit().putString("some_other_setting", "x").apply()
        assertTrue(prefs.all.keys.any { it.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) })
        val out = ByteArrayOutputStream()
        settingsToJsonStream(prefs.all, out)
        val text = out.toString()
        assertFalse(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX in text, "no saved language in the exported settings")
        assertFalse("com.example.chat" in text, "no package names")
        assertTrue("some_other_setting" in text)
    }

    /** the user switches to Russian in an app: remembered for the app; a switch by a field hint is not; a password field neither */
    @Test fun userSwitchIsSavedAndAutomaticSwitchIsNot() {
        val editor = android.view.inputmethod.EditorInfo().apply { packageName = "com.example.chat"; inputType = InputType.TYPE_CLASS_TEXT }
        latinIME.onStartInput(editor, false)
        current(en)
        latinIME.switchToSubtype(ru) // the user (globe key, space swipe, language menu)
        assertEquals("ru", Settings.getInstance().getSubtypeForApp("com.example.chat")?.locale?.language)
        // the keyboard follows the hint of another field: not remembered as the choice of the app
        val hinted = android.view.inputmethod.EditorInfo().apply { packageName = "com.example.hint"; inputType = InputType.TYPE_CLASS_TEXT }
        latinIME.onStartInput(hinted, false)
        val auto = LatinIME::class.java.getDeclaredMethod("switchToSubtypeAutomatically", InputMethodSubtype::class.java).apply { isAccessible = true }
        auto.invoke(latinIME, en)
        assertNull(Settings.getInstance().getSubtypeForApp("com.example.hint"))
        // a switch in a password field is not the language of the app
        val pw = android.view.inputmethod.EditorInfo().apply { packageName = "com.example.bank"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        latinIME.onStartInput(pw, false)
        latinIME.switchToSubtype(ru)
        assertNull(Settings.getInstance().getSubtypeForApp("com.example.bank"))
    }
}
