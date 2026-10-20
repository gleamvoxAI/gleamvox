// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ux-5: a field with the "no suggestions" flag (search bar of a shop app) still shows suggestions, without auto-correction. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class TapOnlySuggestionsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val text = InputType.TYPE_CLASS_TEXT
    private val noSug = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    private fun attrs(inputType: Int, imeOptions: Int = 0) = InputAttributes(EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
        packageName = "com.example"
    }, false, latinIME.packageName)

    private val tapOnlyTypes = mapOf(
        "plain search" to (text or noSug),
        "search with sentence capitals" to (text or noSug or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        "multi-line" to (text or noSug or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        "short message" to (text or noSug or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE),
        "filter" to (text or noSug or InputType.TYPE_TEXT_VARIATION_FILTER),
        "name" to (text or noSug or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        // ux-8: the address / search bar of Chrome and Google: ordinary words are typed there
        "address and search bar" to (text or noSug or InputType.TYPE_TEXT_VARIATION_URI),
    )
    private val neverTypes = mapOf(
        "password" to (text or noSug or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        "visible password" to (text or noSug or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        "web password" to (text or noSug or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        "number password" to (InputType.TYPE_CLASS_NUMBER or noSug or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        "e-mail" to (text or noSug or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        "web e-mail" to (text or noSug or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        "web field" to (text or noSug or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
        "number" to (InputType.TYPE_CLASS_NUMBER or noSug),
        "phone" to (InputType.TYPE_CLASS_PHONE or noSug),
        "date" to (InputType.TYPE_CLASS_DATETIME or noSug),
        "no type" to 0,
    )

    @Test fun fieldsWithNoSuggestionsFlagGetTapOnlySuggestions() {
        RichInputMethodManager.init(latinIME)
        tapOnlyTypes.forEach { (name, type) ->
            val a = attrs(type)
            assertTrue(a.mSuggestionsByTapOnly, name)
            assertTrue(a.mShouldShowSuggestions, name)
        }
    }

    @Test fun neverInPrivateOrSpecialFields() {
        RichInputMethodManager.init(latinIME)
        neverTypes.forEach { (name, type) ->
            val a = attrs(type)
            assertFalse(a.mSuggestionsByTapOnly, name)
            assertFalse(a.mShouldShowSuggestions, name)
        }
        // "do not remember" (incognito) field: even an ordinary text type
        val incognito = attrs(text or noSug, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(incognito.mSuggestionsByTapOnly, "do not remember")
        assertFalse(incognito.mShouldShowSuggestions, "do not remember")
    }

    @Test fun ordinaryFieldsAreUnchanged() {
        RichInputMethodManager.init(latinIME)
        val a = attrs(text)
        assertFalse(a.mSuggestionsByTapOnly)
        assertTrue(a.mShouldShowSuggestions)
        assertFalse(attrs(text or InputType.TYPE_TEXT_VARIATION_PASSWORD).mShouldShowSuggestions)
    }

    private fun autoCorrectIn(inputType: Int): Boolean {
        Settings.getInstance().loadSettings(latinIME, Locale.ENGLISH, attrs(inputType))
        return Settings.getValues().mAutoCorrectEnabled
    }

    @Test fun noAutoCorrectionInTapOnlyFieldUnlessFallbackIsOn() {
        RichInputMethodManager.init(latinIME)
        val prefs = latinIME.prefs()
        prefs.edit {
            putBoolean(Settings.PREF_AUTO_CORRECTION, true)
            putBoolean(Settings.PREF_MORE_AUTO_CORRECTION, true)
            putBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, false)
        }
        assertTrue(autoCorrectIn(text), "ordinary field corrects")
        assertFalse(autoCorrectIn(text or noSug), "search bar: tap only")
        assertFalse(autoCorrectIn(text or noSug or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT), "even if the app asks for it with the flag")
        // the fallback "always show suggestions" keeps the full behaviour
        prefs.edit { putBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, true) }
        assertTrue(autoCorrectIn(text or noSug), "fallback: full auto-correction")
        assertEquals(true, Settings.getValues().mSuggestionsEnabled)
    }

    /** ux-8: the address bar never auto-corrects; only ordinary words are learned there, never addresses or e-mails */
    @Test fun addressBarSuggestsByTapAndLearnsOnlyWords() {
        RichInputMethodManager.init(latinIME)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, true); putBoolean(Settings.PREF_MORE_AUTO_CORRECTION, true); putBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, false) }
        val uri = text or InputType.TYPE_TEXT_VARIATION_URI
        assertFalse(autoCorrectIn(uri or noSug), "with the no-suggestions flag")
        assertFalse(autoCorrectIn(uri), "without it")
        assertTrue(attrs(uri).mShouldShowSuggestions)
        assertTrue(InputAttributes.mayLearnInAddressBar(uri, "привет"))
        assertTrue(InputAttributes.mayLearnInAddressBar(uri, "weather"))
        for (w in listOf("google.com", "https://x", "a/b", "me@mail.ru", "localhost:8080"))
            assertFalse(InputAttributes.mayLearnInAddressBar(uri, w), w)
        assertTrue(InputAttributes.mayLearnInAddressBar(text, "file.txt"), "other fields are not changed")
        assertFalse(attrs(uri or noSug, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).mShouldShowSuggestions, "incognito tab: nothing")
    }
}
