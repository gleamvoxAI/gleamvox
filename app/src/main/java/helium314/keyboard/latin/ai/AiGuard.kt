// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.voice.SensitiveInput

/**
 * Where the ✨ button is shown and when text may leave the keyboard (CLAUDE.md, docs/AI.md):
 * never in sensitive fields (the same check as voice input: passwords, e-mail, numbers, "do not remember"; the address bar is allowed since 5g-8),
 * in excluded apps or in incognito mode. Checked when the button is shown and again right before every request.
 */
object AiGuard {
    enum class Button {
        HIDDEN,
        /** visible but dimmed: internet switch off, no key or no consent; a tap opens the AI screen */
        OPENS_SETTINGS,
        READY,
    }

    /** no text from this field may be sent */
    fun blocked(editorInfo: EditorInfo?, excludedApps: Set<String>, incognito: Boolean): Boolean =
        SensitiveInput.isSensitive(editorInfo) || incognito || editorInfo?.packageName in excludedApps

    fun button(inBuild: Boolean, blocked: Boolean, internetOn: Boolean, ready: Boolean): Button = when {
        !inBuild || blocked -> Button.HIDDEN
        !internetOn || !ready -> Button.OPENS_SETTINGS
        else -> Button.READY
    }

    /** right before sending: every condition again */
    fun maySend(editorInfo: EditorInfo?, excludedApps: Set<String>, incognito: Boolean, internetOn: Boolean, ready: Boolean) =
        !blocked(editorInfo, excludedApps, incognito) && internetOn && ready

    /** what a tap on the dimmed ✨ opens (stage 5zh): only the consent missing → the consent window at once */
    enum class SettingsTarget(val value: String) {
        INTERNET("internet"), KEY("key"), CONSENT("consent"), NONE("none");

        companion object {
            fun of(value: String?) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    fun settingsTarget(reason: AiError): SettingsTarget = when (reason) {
        AiError.INTERNET_OFF -> SettingsTarget.INTERNET
        AiError.NO_KEY, AiError.KEY_INVALID -> SettingsTarget.KEY
        AiError.NO_CONSENT -> SettingsTarget.CONSENT
        else -> SettingsTarget.NONE
    }

    /** why ✨ is not ready (dimmed): what the tap and the long press say in the strip */
    fun notReadyReason(internetOn: Boolean, hasKey: Boolean, keyRejected: Boolean, consent: Boolean): AiError = when {
        !internetOn -> AiError.INTERNET_OFF
        !hasKey -> AiError.NO_KEY
        keyRejected -> AiError.KEY_INVALID
        !consent -> AiError.NO_CONSENT
        else -> AiError.FAILED
    }
}
