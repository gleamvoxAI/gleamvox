package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LAYOUT
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import org.junit.Assume
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class
])
class SubtypeTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val params = KeyboardParams()

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        params.mPopupKeyOrder.add(POPUP_KEYS_LAYOUT)
        LocaleKeyboardInfos.addLocaleKeyTextsToParams(latinIME, params, LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
    }

    @Test fun emptyAdditionalSubtypesResultsInEmptyList() {
        // avoid issues where empty string results in additional subtype for undefined locale
        val prefs = latinIME.prefs()
        prefs.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, "").apply()
        assertTrue(SubtypeSettings.getAdditionalSubtypes().isEmpty())
        val from = SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first()

        // no change, and "changed" subtype actually is resource subtype -> still expect empty list
        SubtypeUtilsAdditional.changeAdditionalSubtype(from.toSettingsSubtype(), from.toSettingsSubtype(), latinIME)
        assertEquals(emptyList(), SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
    }

    @Test fun subtypeStaysEnabledOnEdits() {
        val prefs = latinIME.prefs()
        prefs.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, "").apply() // clear it for convenience

        // edit enabled resource subtype
        val from = SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first()
        SubtypeSettings.addEnabledSubtype(prefs, from)
        val to = from.toSettingsSubtype().withLayout(LayoutType.SYMBOLS, "symbols_arabic")
        SubtypeUtilsAdditional.changeAdditionalSubtype(from.toSettingsSubtype(), to, latinIME)
        assertEquals(to, SubtypeSettings.getEnabledSubtypes(false).single().toSettingsSubtype())

        // change the new subtype to effectively be the same as original resource subtype
        val toNew = to.withoutLayout(LayoutType.SYMBOLS)
        assertEquals(from.toSettingsSubtype(), toNew)
        SubtypeUtilsAdditional.changeAdditionalSubtype(to, toNew, latinIME)
        assertEquals(emptyList(), SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
        assertEquals(from.toSettingsSubtype(), SubtypeSettings.getEnabledSubtypes(false).single().toSettingsSubtype())
    }

    /** ux-11: a clean install: the system languages and English (US), the language key at once with 2+ languages */
    @Test fun cleanInstallHasSystemLanguagesAndEnglish() {
        latinIME.prefs().edit().remove(Settings.PREF_ENABLED_SUBTYPES).apply()
        SubtypeSettings.reloadEnabledSubtypes(latinIME) // other tests of this class enabled some in memory
        fun defaults(vararg tags: String): List<String> {
            SubtypeSettings.setSystemLocalesForTest(tags.map { it.constructLocale() })
            return SubtypeSettings.getEnabledSubtypes(true).map { it.locale().toLanguageTag() }
        }
        assertEquals(listOf("ru", "en-US"), defaults("ru"), "a Russian phone: Russian and English")
        assertEquals(listOf("en-US"), defaults("en-US"), "an English phone: only English, nothing forced on it")
        assertEquals(listOf("ru", "en-US"), defaults("ru", "en-US"), "both: no duplicate")
        assertEquals(listOf("ru", "en-GB"), defaults("ru", "en-GB"), "any English counts as English")
        assertEquals(listOf("de", "en-US"), defaults("de"), "a German phone: German and English")
        assertEquals(listOf("ru", "en-US"), defaults("ru", "ru"), "no duplicate languages")
        // lang-1: ALL languages of the phone, not only the first one
        assertEquals(listOf("ru", "uk", "kk", "de", "en-US"), defaults("ru", "uk", "kk", "de"), "every language of the phone")
        assertEquals(listOf("fr", "ar", "en-GB", "fi"), defaults("fr", "ar", "en-GB", "fi"), "English among them: not added twice")
        // the language key: on by default; settings of the person are kept
        assertTrue(helium314.keyboard.latin.settings.Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
        // existing settings (the person chose only Spanish): nothing is added
        SubtypeSettings.addEnabledSubtype(latinIME.prefs(), SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first())
        assertEquals(listOf("es"), SubtypeSettings.getEnabledSubtypes(true).map { it.locale().language })
    }

    /** lang-1: every new language has its own layout and finds its main and emoji dictionaries in the APK (Hebrew is "iw",
     *  Indonesian "in" in Android). The file names come from a personal or play build (build/aikb-personal-assets). */
    @Test fun newLanguagesFindTheirDictionaries() {
        val dir = File("build/aikb-personal-assets/dicts")
        Assume.assumeTrue("the dictionaries are prepared by a personal or play build", dir.isDirectory)
        val files = dir.list()!!.toList()
        val layouts = mapOf("uk" to "ukrainian", "kk" to "russian", "ar" to "arabic", "in" to "qwerty", "cs" to "qwertz",
            "sk" to "qwerty", "hr" to "qwertz", "fi" to "qwerty+", "da" to "qwerty+", "nb" to "qwerty+", "iw" to "hebrew",
            "fa" to "farsi", "az_AZ" to "qwerty")
        for ((tag, layout) in layouts) {
            val subtype = SubtypeSettings.getResourceSubtypesForLocale(tag.constructLocale()).firstOrNull()
            assertNotNull(subtype, "a layout for $tag")
            assertEquals(layout, subtype.toSettingsSubtype().mainLayoutName() ?: "qwerty", "the layout of $tag")
            for (type in listOf("main", "emoji")) {
                val match = LocaleUtils.getBestMatch(subtype.locale(), files.filter { it.startsWith(type + "_") }) {
                    DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it)
                }
                assertNotNull(match, "the $type dictionary of $tag in $files")
            }
        }
    }
}
