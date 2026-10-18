// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.ai.AiGuard

/**
 * What the AI screen shows first when the dimmed ✨ opened it (stage 5zh, item 9): the consent window, the key or the
 * internet switch. Set from the intent of the keyboard, taken once by the screen.
 */
object AiScreenTarget {
    @Volatile private var pending = AiGuard.SettingsTarget.NONE

    fun set(value: String?) { pending = AiGuard.SettingsTarget.of(value) }

    fun take(): AiGuard.SettingsTarget = pending.also { pending = AiGuard.SettingsTarget.NONE }
}
