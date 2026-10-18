// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import kotlin.math.min

/**
 * Colors of the voice, command and preview panels in the design of the promo video (docs/DESIGN.md, 5е-3). Dark: the
 * colors of the description; light: light analogs, the gold darker so that it keeps 3:1 against the light panel and the
 * white keys. Background, keys and text follow the theme, so other themes stay coherent.
 */
class PanelPalette(
    val background: Int,
    val key: Int,
    val pressed: Int,
    val text: Int,
    val grey: Int,
    /** gold of icons, rings, bars and filled buttons */
    val gold: Int,
    /** gold of text on the panel background */
    val goldText: Int,
    /** text on a gold button */
    val onGold: Int,
    val red: Int,
    /** the circle of a glowing strip button at full glow */
    val warm: Int,
    val isDark: Boolean,
) {
    companion object {
        const val DARK_BACKGROUND = 0xFF121318.toInt()
        const val DARK_KEY = 0xFF292A2F.toInt()
        const val DARK_PRESSED = 0xFF42434B.toInt()
        const val DARK_GREY = 0xFF8E8E94.toInt()
        const val DARK_ON_GOLD = 0xFF3C2C04.toInt()
        const val DARK_RED = 0xFFF06E64.toInt()
        const val DARK_WARM = 0xFF463A16.toInt()
        /** light theme: 4.1:1 on the panel #EBECF0, 4.9:1 on a white key and under white text (owner: not below 3:1) */
        const val LIGHT_GOLD = 0xFF946A00.toInt()
        const val LIGHT_PRESSED = 0xFFDCDDE2.toInt()
        const val LIGHT_GREY = 0xFF64656B.toInt()
        const val LIGHT_RED = 0xFFC4372D.toInt()
        /** warm circle of the light theme: the gold icon #B98300 keeps 3:1 on it */
        const val LIGHT_WARM = 0xFFFFF6DC.toInt()

        fun from(colors: Colors): PanelPalette {
            val background = colors.get(ColorType.MAIN_BACKGROUND)
            val key = colors.get(ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)
            val text = colors.get(ColorType.KEY_TEXT)
            return if (AiTint.isDark(background))
                PanelPalette(background, key, DARK_PRESSED, text, DARK_GREY, AiTint.GOLD_ON_DARK, AiTint.GOLD_ON_DARK,
                    DARK_ON_GOLD, DARK_RED, DARK_WARM, true)
            else
                PanelPalette(background, key, LIGHT_PRESSED, text, LIGHT_GREY, LIGHT_GOLD, AiTint.GOLD_TEXT_ON_LIGHT,
                    0xFFFFFFFF.toInt(), LIGHT_RED, LIGHT_WARM, false)
        }
    }
}

/**
 * The design of the promo video is drawn for a screen 1148 px wide; y counts from the top of the suggestion strip and the
 * panels replace the keys below it. Horizontal sizes follow the width of the panel; vertical sizes, text and round shapes
 * follow the smaller of the two scales, so the layout fits a lower keyboard (no number row, landscape) and the panel is
 * never higher than the keyboard.
 */
class DesignScale(val width: Int, val height: Int) {
    val sx = width / DESIGN_WIDTH
    /** for heights, text and circles */
    val k = min(sx, height / DESIGN_PANEL_HEIGHT)

    /** x of the design in px */
    fun x(v: Float) = v * sx
    /** design y (from the top of the strip) to px from the top of the panel */
    fun y(v: Float) = (v - DESIGN_STRIP) * k
    fun size(v: Float) = v * k

    companion object {
        const val DESIGN_WIDTH = 1148f
        /** the strip above the panel in the design (measured: keyboard window 940 px, keys area 820 px) */
        const val DESIGN_STRIP = 120f
        const val DESIGN_PANEL_HEIGHT = 820f
    }
}

object AikbMotion {
    /** animations are on unless "Remove animations" (or an animator duration scale of 0) is set in the system */
    fun enabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else try { SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
        catch (e: Exception) { true }

    /** a color between [a] and [b] */
    fun blend(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [color] with the alpha [alpha] (0..255) */
    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
