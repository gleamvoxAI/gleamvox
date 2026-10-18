// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Fields where text must never leave the keyboard by voice or ✨ (AI with the own OpenAI key), see CLAUDE.md:
 * passwords (all variants), e-mail, numeric, phone and other non-text fields,
 * and fields with the "do not remember" flag (IME_FLAG_NO_PERSONALIZED_LEARNING).
 * Stage 5g-8 (the owner, 2026-10-14): the address and search bar of a browser (TYPE_TEXT_VARIATION_URI) is no longer
 * sensitive: voice and ✨ work there. An incognito tab sets "do not remember" and stays blocked.
 */
object SensitiveInput {
    @JvmStatic
    fun isSensitive(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return true
        val noLearning = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return noLearning || isSensitiveInputType(editorInfo.inputType)
    }

    @JvmStatic
    fun isSensitiveInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return true // numbers, phone, date/time, no type
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
            else -> false
        }
    }
}
