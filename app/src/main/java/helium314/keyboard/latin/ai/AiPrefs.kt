// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit

/** Settings of the AI functions (no text, no key): consent for text requests, excluded apps, the second translation language. */
object AiPrefs {
    /**
     * The consent screen for text requests with the own API key was accepted (docs/AI.md, section 4): the text of a ✨
     * command and of a dictated phrase (cleanup) goes to OpenAI and is paid from the user's balance. New in stage 5d:
     * the former ChatGPT consent does not count.
     */
    const val PREF_TEXT_CONSENT = "aikb_ai_text_consent"
    /** package names; in these apps the ✨ button is hidden and nothing is sent. Empty by default. */
    const val PREF_EXCLUDED_APPS = "aikb_ai_excluded_apps"
    /** language tag of "Translate to …" besides the language of the active layout */
    const val PREF_TRANSLATE_TO = "aikb_ai_translate_to"
    /** the command a long press on ✨ runs at once (stage 5e), [LongPressCommand.DEFAULT] "Fix" */
    const val PREF_LONG_PRESS_COMMAND = "aikb_ai_long_press_command"
    /** after a long press show the preview panel instead of replacing at once; off by default */
    const val PREF_LONG_PRESS_PREVIEW = "aikb_ai_long_press_preview"
    val KEYS = listOf(PREF_TEXT_CONSENT, PREF_EXCLUDED_APPS, PREF_TRANSLATE_TO, CleanupLevel.PREF, AiInvite.PREF_SNOOZED_UNTIL,
        PREF_LONG_PRESS_COMMAND, PREF_LONG_PRESS_PREVIEW) + AiConsent.KEYS
    /** a restored backup must never switch sending text on by itself */
    val RESET_ON_RESTORE = listOf(PREF_TEXT_CONSENT)

    fun textConsent(prefs: SharedPreferences) = prefs.getBoolean(PREF_TEXT_CONSENT, false)

    fun giveTextConsent(prefs: SharedPreferences) = prefs.edit { putBoolean(PREF_TEXT_CONSENT, true) }

    fun withdrawTextConsent(prefs: SharedPreferences) = prefs.edit { remove(PREF_TEXT_CONSENT) }

    fun excludedApps(prefs: SharedPreferences): Set<String> = prefs.getStringSet(PREF_EXCLUDED_APPS, null)?.toSet() ?: emptySet()

    fun addExcluded(prefs: SharedPreferences, pkg: String) = prefs.edit { putStringSet(PREF_EXCLUDED_APPS, excludedApps(prefs) + pkg) }

    fun removeExcluded(prefs: SharedPreferences, pkg: String) = prefs.edit { putStringSet(PREF_EXCLUDED_APPS, excludedApps(prefs) - pkg) }

    fun isExcluded(prefs: SharedPreferences, pkg: String?): Boolean = pkg != null && pkg in excludedApps(prefs)

    fun longPressCommand(prefs: SharedPreferences) = LongPressCommand.of(prefs.getString(PREF_LONG_PRESS_COMMAND, null))

    fun setLongPressCommand(prefs: SharedPreferences, c: LongPressCommand) = prefs.edit { putString(PREF_LONG_PRESS_COMMAND, c.value) }

    fun longPressPreview(prefs: SharedPreferences) = prefs.getBoolean(PREF_LONG_PRESS_PREVIEW, false)

    fun setLongPressPreview(prefs: SharedPreferences, on: Boolean) = prefs.edit { putBoolean(PREF_LONG_PRESS_PREVIEW, on) }
}

/** what a long press on ✨ does (docs/DESIGN.md, 5е-1); translations go to the layout language or the second language */
enum class LongPressCommand(val value: String, val command: AiCommand) {
    FIX("fix", AiCommand.FIX),
    REWRITE("rewrite", AiCommand.REWRITE),
    SHORTEN("shorten", AiCommand.SHORTEN),
    CONTINUE("continue", AiCommand.CONTINUE),
    TRANSLATE_LAYOUT("translate_layout", AiCommand.TRANSLATE),
    TRANSLATE_SECOND("translate_second", AiCommand.TRANSLATE);

    /** target language of a translation, null for the other commands */
    fun language(layoutLanguage: String, secondLanguage: String): String? = when (this) {
        TRANSLATE_LAYOUT -> layoutLanguage
        TRANSLATE_SECOND -> secondLanguage
        else -> null
    }

    companion object {
        val DEFAULT = FIX
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
