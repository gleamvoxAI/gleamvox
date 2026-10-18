// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs

/** Settings of the AI part (docs/AI.md). The API key is never stored in settings (see [helium314.keyboard.latin.voiceapi.ApiKeyStore]). */
object OpenAiPrefs {
    /** a restored backup file must never switch the network or a consent on by itself */
    val RESET_ON_RESTORE = listOf(AikbNetwork.PREF_INTERNET_ALLOWED) + AiPrefs.RESET_ON_RESTORE + VoiceApiPrefs.RESET_ON_RESTORE +
        helium314.keyboard.latin.ai.DeveloperMode.PREF // developer mode never comes back from a backup (stage 5g-4)

    fun resetAfterRestore(prefs: SharedPreferences) = prefs.edit { RESET_ON_RESTORE.forEach { remove(it) } }

    /**
     * Everything "Delete all" removes from the settings: internet switch, consents, proxy, model list and choice,
     * request counters and ceiling, pauses, excluded apps, translation language, cleanup level, invitation, voice through
     * OpenAI (consent, choice, minutes, balance). The API key itself is in its store.
     */
    val ALL_KEYS = listOf(AikbNetwork.PREF_INTERNET_ALLOWED, OpenAiProxy.PREF_TYPE, OpenAiProxy.PREF_HOST, OpenAiProxy.PREF_PORT,
        OpenAiReach.PREF_PROBLEM_AT) +
        TextModels.KEYS + AiUsage.KEYS + AiPrefs.KEYS + VoiceApiPrefs.KEYS

    fun eraseSettings(prefs: SharedPreferences) = prefs.edit { ALL_KEYS.forEach { remove(it) } }

    /** only the personal variant has the INTERNET permission; the Keystore key needs Android 9 */
    fun isAvailable() = inBuild() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** the personal variant; replaced in tests (the unit tests run without the AI part) */
    @JvmField
    var inBuild: () -> Boolean = { BuildConfig.AIKB_OPENAI }
}
