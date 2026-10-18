// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Golden yellow of the ✨ and microphone buttons (docs/DESIGN.md, "Цвет значка ✨" and "Жёлтый микрофон"): the color says
 * honestly what a tap does. ✨ is gold with a key, the consent for text and the internet switch on; the microphone is gold
 * only when the next tap sends the sound to OpenAI. Otherwise the theme color as before, so a keyboard without
 * connections has no yellow at all.
 */
object AiTint {
    /** dark theme, on the circle #292A2F (about 9:1) */
    const val GOLD_ON_DARK = 0xFFF6C945.toInt()
    /** light theme: #E5A800 has only 2.1:1 on the white circle, this darker amber 3.3:1 */
    const val GOLD_ON_LIGHT = 0xFFB98300.toInt()
    /** gold text on the light panel background #EBECF0: darker still, so small text stays readable (4.6:1) */
    const val GOLD_TEXT_ON_LIGHT = 0xFF8A6200.toInt()
    /** icon against its circle (owner: not below 3:1) */
    const val MIN_CONTRAST = 3.0

    fun isDark(background: Int) = luminance(background) < 0.18

    /** the gold of an icon drawn on [background] */
    fun goldOn(background: Int) = if (isDark(background)) GOLD_ON_DARK else GOLD_ON_LIGHT

    /** the gold of small text on [background] */
    fun goldTextOn(background: Int) = if (isDark(background)) GOLD_ON_DARK else GOLD_TEXT_ON_LIGHT

    /** ✨: gold exactly when a tap opens the commands (key, consent for text, internet on, field allowed) */
    fun sparkleGold(state: AiGuard.Button) = state == AiGuard.Button.READY

    /**
     * Microphone: gold only when the next tap records for OpenAI: our voice input is on and may record, and the
     * recognizer choice (mode, consent, key, internet, ceiling, balance, excluded app, incognito) says OpenAI.
     */
    fun micGold(sensitive: Boolean, internalVoiceOn: Boolean, hasPermission: Boolean, openAi: Boolean) =
        !sensitive && internalVoiceOn && hasPermission && openAi

    /** relative luminance of an opaque color (WCAG) */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio of two opaque colors */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
