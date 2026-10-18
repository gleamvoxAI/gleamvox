// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit

/** "Connect AI: paste an OpenAI API key" card at the top of the main settings screen (personal variant only). */
object AiInvite {
    const val PREF_SNOOZED_UNTIL = "aikb_ai_invite_snoozed_until"
    const val SNOOZE_MS = 7 * 24 * 60 * 60 * 1000L

    /** shown while there is no key and the card was not closed with "Later" in the last 7 days */
    fun shouldShow(available: Boolean, hasKey: Boolean, snoozedUntil: Long, now: Long): Boolean =
        available && !hasKey && now >= snoozedUntil

    /** "Later": hidden for 7 days */
    fun snooze(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) =
        prefs.edit { putLong(PREF_SNOOZED_UNTIL, now + SNOOZE_MS) }
}
