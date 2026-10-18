// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * "Developer mode" (stage 5g-4, the owner: the engineering settings are not for ordinary users), as in Android itself:
 * 7 taps on the version in "About" switch it on, a switch on the "For developer" screen switches it off. Off by default,
 * in personal and release alike. A restored backup never brings it back on ([OpenAiPrefs.RESET_ON_RESTORE]).
 * Only what is for diagnostics hides behind it: the voice journal and its copy button, the checks of the microphone and
 * the connection, the proxy, the model details and prices, the manual choice of the microphone.
 */
object DeveloperMode {
    const val PREF = "aikb_developer_mode"
    /** taps on the version that switch it on */
    const val TAPS = 7
    /** the "N more taps" messages start after this many taps */
    const val HINT_FROM = 3

    fun on(prefs: SharedPreferences) = prefs.getBoolean(PREF, false)
    fun set(prefs: SharedPreferences, on: Boolean) = prefs.edit { putBoolean(PREF, on) }

    /** what a tap on the version does */
    sealed class Tap {
        /** nothing to say yet */
        object Silent : Tap()
        /** "N more taps…" */
        class More(val left: Int) : Tap()
        /** "Developer mode is on" */
        object Enabled : Tap()
        /** it was on already */
        object Already : Tap()
    }

    /** [count] taps so far including this one; switches the mode on at the [TAPS]th */
    fun tap(prefs: SharedPreferences, count: Int): Tap = when {
        on(prefs) -> Tap.Already
        count >= TAPS -> { set(prefs, true); Tap.Enabled }
        count >= HINT_FROM -> Tap.More(TAPS - count)
        else -> Tap.Silent
    }
}
