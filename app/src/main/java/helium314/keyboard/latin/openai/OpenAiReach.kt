// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stage 5g-5, the owner: the proxy and "Check connection to OpenAI" are for everybody, but only when they help: when
 * OpenAI did not answer (no network to it, a timeout, DNS or VPN). The last answer of OpenAI (voice, ✨, the cleanup,
 * the connection check) is remembered: a failure of the network shows the card "OpenAI does not answer" with the
 * proxy and the check on the AI screen; any successful answer hides it again. Only a flag and a time, nothing else.
 */
object OpenAiReach {
    const val PREF_PROBLEM_AT = "aikb_openai_unreachable_at"

    fun problem(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) =
        prefs.edit { putLong(PREF_PROBLEM_AT, now) }

    fun ok(prefs: SharedPreferences) { if (prefs.contains(PREF_PROBLEM_AT)) prefs.edit { remove(PREF_PROBLEM_AT) } }

    /** OpenAI did not answer last time: show the proxy and the check to everybody */
    fun unreachable(prefs: SharedPreferences) = prefs.getLong(PREF_PROBLEM_AT, 0L) > 0L
}
