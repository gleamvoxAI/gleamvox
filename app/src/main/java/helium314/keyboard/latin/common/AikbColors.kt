// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.graphics.toColorInt
import helium314.keyboard.latin.common.ColorType.*
import java.util.EnumMap

/**
 * Built-in "AI Keyboard" color scheme (own colors, see docs/DESIGN.md). Dark and light variants,
 * the variant is chosen by the caller depending on the system day/night mode.
 */
object AikbColors {
    private class Palette(
        val background: String, val key: String, val functional: String, val accent: String, val onAccent: String,
        val text: String, val muted: String?, val emojiSelected: String, val card: String, val onCard: String,
    )

    private val dark = Palette(
        background = "#121318", key = "#292A2F", functional = "#1A1B20", accent = "#B7C4F9", onAccent = "#0F1B4A",
        text = "#E4E3E9", muted = null, emojiSelected = "#1F2025", card = "#364475", onCard = "#DCE1FF",
    )
    // light colors are taken from the light-theme screenshot, see docs/DESIGN.md
    private val light = Palette(
        background = "#EBECF0", key = "#FFFFFF", functional = "#F7F7F7", accent = "#0C1631", onAccent = "#F4F7FF",
        text = "#191A1F", muted = "#A3A4A6", emojiSelected = "#E1E2E6", card = "#DCE1FF", onCard = "#00164E",
    )

    fun create(themeStyle: String, hasBorders: Boolean, isNight: Boolean, backgroundImage: Drawable?): Colors {
        val p = if (isNight) dark else light
        val background = p.background.toColorInt()
        val key = p.key.toColorInt()
        val functional = p.functional.toColorInt()
        val accent = p.accent.toColorInt()
        val text = p.text.toColorInt()
        val mutedText = p.muted?.toColorInt() ?: ((text and 0x00FFFFFF) or (0x99 shl 24)) // about 60 %
        // start from a complete set of colors, then override what differs
        val base = DefaultColors(themeStyle, hasBorders, accent, background, key, functional, key, text, mutedText,
            spaceBarText = mutedText)
        val map = EnumMap<ColorType, Int>(ColorType::class.java)
        ColorType.entries.forEach { map[it] = base.get(it) }
        map[MAIN_BACKGROUND] = background
        map[STRIP_BACKGROUND] = background
        map[NAVIGATION_BAR] = background
        map[KEY_BACKGROUND] = key
        map[SPACE_BAR_BACKGROUND] = key
        map[FUNCTIONAL_KEY_BACKGROUND] = functional
        map[ACTION_KEY_BACKGROUND] = accent
        map[ACTION_KEY_ICON] = p.onAccent.toColorInt()
        map[ACTION_KEY_POPUP_KEYS_BACKGROUND] = accent
        map[SPACE_BAR_TEXT] = mutedText
        map[KEY_HINT_TEXT] = mutedText
        // suggestions in the strip: same color as the main text of the theme
        listOf(SUGGESTED_WORD, SUGGESTION_TYPED_WORD, SUGGESTION_VALID_WORD, SUGGESTION_AUTO_CORRECT, MORE_SUGGESTIONS_HINT)
            .forEach { map[it] = text }
        // round buttons in the suggestion strip
        map[TOOL_BAR_EXPAND_KEY_BACKGROUND] = key
        map[TOOL_BAR_EXPAND_KEY] = text
        map[TOOL_BAR_KEY] = text
        map[TOOL_BAR_KEY_ENABLED_BACKGROUND] = accent
        // emoji panel
        map[EMOJI_SEARCH_BACKGROUND] = key
        map[EMOJI_CATEGORY_SELECTED] = p.emojiSelected.toColorInt()
        // clipboard
        map[CLIPBOARD_SUGGESTION_BACKGROUND] = p.card.toColorInt()
        map[CLIPBOARD_SUGGESTION_ICON] = p.onCard.toColorInt()
        map[CLIPBOARD_PIN] = accent
        // popups and previews
        map[POPUP_KEYS_BACKGROUND] = key
        map[MORE_SUGGESTIONS_BACKGROUND] = key
        map[MORE_SUGGESTIONS_WORD_BACKGROUND] = key
        map[KEY_PREVIEW_BACKGROUND] = key
        map[AUTOFILL_BACKGROUND_CHIP] = key
        map[GESTURE_PREVIEW] = key
        map[GESTURE_TRAIL] = accent
        return AllColors(map, themeStyle, hasBorders, backgroundImage)
    }
}
