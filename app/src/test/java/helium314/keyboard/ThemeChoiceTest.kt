// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** One theme for day and night: "AI Keyboard" follows the phone's mode, the light/dark variants ignore it. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class ThemeChoiceTest {
    private val darkBackground = 0xFF121318.toInt()
    private val lightBackground = 0xFFEBECF0.toInt()

    private fun background(theme: String, phoneNight: Boolean, nightPref: String? = null, dayNightSwitch: Boolean? = null): Int {
        RuntimeEnvironment.setQualifiers(if (phoneNight) "+night" else "+notnight")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.prefs().edit {
            clear()
            putString(Settings.PREF_THEME_COLORS, theme)
            if (nightPref != null) putString(Settings.PREF_THEME_COLORS_NIGHT, nightPref)
            if (dayNightSwitch != null) putBoolean(Settings.PREF_THEME_DAY_NIGHT, dayNightSwitch)
        }
        return KeyboardTheme.getColorsForCurrentTheme(ctx).get(ColorType.MAIN_BACKGROUND)
    }

    @Test fun asInSystemFollowsPhone() {
        assertEquals(lightBackground, background(KeyboardTheme.THEME_AIKB, phoneNight = false))
        assertEquals(darkBackground, background(KeyboardTheme.THEME_AIKB, phoneNight = true))
    }

    @Test fun asInSystemFollowsPhoneEvenWithOldSwitchOff() {
        assertEquals(lightBackground, background(KeyboardTheme.THEME_AIKB, phoneNight = false, dayNightSwitch = false))
        assertEquals(darkBackground, background(KeyboardTheme.THEME_AIKB, phoneNight = true, dayNightSwitch = false))
    }

    @Test fun lightAndDarkIgnorePhone() {
        for (night in listOf(false, true)) {
            assertEquals(lightBackground, background(KeyboardTheme.THEME_AIKB_LIGHT, night))
            assertEquals(darkBackground, background(KeyboardTheme.THEME_AIKB_DARK, night))
        }
    }

    @Test fun oldNightValueIsIgnored() {
        // old installs may still have a different night value: the single chosen theme wins
        assertEquals(lightBackground, background(KeyboardTheme.THEME_AIKB_LIGHT, phoneNight = true, nightPref = KeyboardTheme.THEME_AIKB_DARK))
        assertNotEquals(darkBackground, background(KeyboardTheme.THEME_AIKB_LIGHT, phoneNight = true, nightPref = KeyboardTheme.THEME_AIKB_DARK))
    }
}
