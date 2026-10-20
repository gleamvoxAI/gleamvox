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
        // the language key: on by default; settings of the person are kept
        assertTrue(helium314.keyboard.latin.settings.Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
        // existing settings (the person chose only Spanish): nothing is added
        SubtypeSettings.addEnabledSubtype(latinIME.prefs(), SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first())
        assertEquals(listOf("es"), SubtypeSettings.getEnabledSubtypes(true).map { it.locale().language })
    }
}
