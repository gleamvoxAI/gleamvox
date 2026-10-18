// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * One line of AI Keyboard in the suggestion strip ("Undo", a quiet note, a short error), shown until it is removed or
 * new suggestions come. Used by the cleanup of dictation and by the long press of ✨.
 */
class AiStripChip(private val ime: LatinIME) {
    private var shown: View? = null

    /** [muted]: small grey text; [onClick] non-null: underlined and tappable */
    fun show(text: CharSequence, muted: Boolean, onClick: (() -> Unit)?) {
        hide()
        val colors = Settings.getValues().mColors
        val v = TextView(ime).apply {
            this.text = text
            contentDescription = text
            setTextColor(colors.get(if (muted) ColorType.KEY_HINT_TEXT else ColorType.KEY_TEXT))
            textSize = if (muted) 13f else 16f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (onClick != null) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { onClick() }
            }
        }
        if (ime.showAiStripView(v)) shown = v
    }

    /** only removes the line of this chip if it is still shown */
    fun hide() {
        shown?.let { ime.removeAiStripView(it) }
        shown = null
    }
}
