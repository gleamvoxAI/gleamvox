// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs

/**
 * The single consent (docs/DESIGN.md, "Единое согласие и включение ИИ одним нажатием", stage 5zh): one step instead of
 * two consents. Sending to OpenAI is never switched on silently: only [giveAll] after the user read the text and pressed
 * "Turn on". In the settings the parts can still be seen and withdrawn one by one (voice; ✨ and the cleanup).
 */
object AiConsent {
    /** "Confirm the consent for ✨" (card on the main settings screen) closed with "Not now" until this time */
    const val PREF_CONFIRM_SNOOZED_UNTIL = "aikb_ai_confirm_snoozed_until"
    const val SNOOZE_MS = 7 * 24 * 60 * 60 * 1000L
    val KEYS = listOf(PREF_CONFIRM_SNOOZED_UNTIL)

    /**
     * Voice, ✨ and the cleanup at once, with the defaults switched on: speech recognition "Auto" (unless voice was on
     * already with a choice of the user), the cleanup "Full" and the long press "Fix" (their defaults). [internet]:
     * the switch "Allow internet access" too (the wizard does it; the card only confirms the consent).
     */
    fun giveAll(prefs: SharedPreferences, internet: Boolean = false) {
        val voiceWasOn = VoiceApiPrefs.consent(prefs) && VoiceApiPrefs.engine(prefs) != SpeechEngineSetting.SYSTEM
        VoiceApiPrefs.giveConsent(prefs)
        if (!voiceWasOn) VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.AUTO)
        AiPrefs.giveTextConsent(prefs)
        prefs.edit {
            remove(PREF_CONFIRM_SNOOZED_UNTIL)
            if (internet) putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true)
        }
    }

    fun hasAll(prefs: SharedPreferences) = VoiceApiPrefs.consent(prefs) && AiPrefs.textConsent(prefs)

    /**
     * The card "Confirm the consent for ✨": an existing installation that uses AI (a key or the voice consent), but the
     * text consent is missing (stage 5d asked for it again after the ChatGPT sign-in was removed). Gone after the consent.
     */
    fun needsConfirmation(prefs: SharedPreferences, available: Boolean, hasKey: Boolean, now: Long): Boolean =
        available && !AiPrefs.textConsent(prefs) && (hasKey || VoiceApiPrefs.consent(prefs)) &&
            now >= prefs.getLong(PREF_CONFIRM_SNOOZED_UNTIL, 0L)

    /** "Not now" on the card: hidden for 7 days */
    fun snooze(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) =
        prefs.edit { putLong(PREF_CONFIRM_SNOOZED_UNTIL, now + SNOOZE_MS) }
}
