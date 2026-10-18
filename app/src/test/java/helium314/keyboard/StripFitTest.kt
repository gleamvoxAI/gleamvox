// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ux-1: long words in the suggestion strip are made smaller, not cut; the typed word is always whole and visible */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class], qualifiers = "w383dp-h880dp-port-night-480dpi")
class StripFitTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    private fun info(word: String, kind: Int = SuggestedWordInfo.KIND_CORRECTION, dict: Dictionary = Dictionary.DICTIONARY_USER_TYPED) =
        SuggestedWordInfo(word, "", 100, kind, dict, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun forceLayoutAll(v: View) { v.forceLayout(); if (v is ViewGroup) for (i in 0 until v.childCount) forceLayoutAll(v.getChildAt(i)) }

    private fun words(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.tag is Int) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) words(v.getChildAt(i), out)
        return out
    }

    /** shows [list] (typed word first, as Suggest builds it) and returns the word views in the order of the strip */
    private fun show(list: List<SuggestedWordInfo>, typed: String, valid: Boolean, willAutoCorrect: Boolean): Pair<List<TextView>, Float> {
        latinIME.prefs().edit { clear(); putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true) }
        Settings.getInstance().loadSettings(latinIME)
        RichInputMethodManager.init(latinIME)
        val ks = KeyboardSwitcher.getInstance()
        val view = ks.onCreateInputView(latinIME, false)
        ks.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)
        view.findViewById<View>(R.id.strip_container).visibility = View.VISIBLE
        val strip = view.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        strip.visibility = View.VISIBLE
        forceLayoutAll(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1148, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2492, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // the normal font size of the theme (the strip makes it smaller only when words are long)
        strip.setSuggestions(SuggestedWords(arrayListOf(info("a", SuggestedWordInfo.KIND_TYPED), info("b"), info("c")), null, info("a", SuggestedWordInfo.KIND_TYPED),
            true, false, false, SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER), false)
        forceLayoutAll(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1148, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2492, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val base = words(strip).first().textSize
        val sw = SuggestedWords(ArrayList(list), null, info(typed, SuggestedWordInfo.KIND_TYPED), valid, willAutoCorrect, false,
            SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER)
        strip.setSuggestions(sw, false)
        forceLayoutAll(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1148, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2492, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return words(strip).filter { it.parent != null && !it.text.isNullOrEmpty() } to base
    }

    @Test fun shortWordsKeepTheNormalFont() {
        // typed "helo" (unknown), autocorrection "hello" (the typed word is copied behind it), other suggestion "help"
        val (shown, base) = show(listOf(info("helo", SuggestedWordInfo.KIND_TYPED), info("hello"), info("helo", SuggestedWordInfo.KIND_TYPED), info("help")),
            "helo", valid = false, willAutoCorrect = true)
        assertEquals(listOf("“helo”", "hello", "help"), shown.map { it.text.toString() })
        assertTrue(shown.all { it.textSize == base }, "the normal font: ${shown.map { it.textSize }} base $base")
    }

    @Test fun typedLongWordIsNeverCut() {
        val typed = "Gleamvoxxxxxxxxxxxxxxxx"
        val (shown, base) = show(listOf(info(typed, SuggestedWordInfo.KIND_TYPED), info("Gleam of"), info(typed, SuggestedWordInfo.KIND_TYPED), info("Gleambox")),
            typed, valid = false, willAutoCorrect = true)
        val typedView = shown.first { it.text.toString().contains(typed) }
        assertEquals("“$typed”", typedView.text.toString(), "the whole typed word, in quotes")
        assertFalse(shown.any { it.text.contains("…") }, "nothing is cut: ${shown.map { it.text }}")
        assertTrue(typedView.textSize <= base)
    }

    @Test fun veryLongWordsDropTheLessImportantSuggestion() {
        val typed = "Antidisestablishmentarianismxxxxxxxx"
        val (shown, base) = show(listOf(info(typed, SuggestedWordInfo.KIND_TYPED), info("Antidisestablishmentarianism"), info(typed, SuggestedWordInfo.KIND_TYPED), info("Antidisestablishmentarianismus")),
            typed, valid = false, willAutoCorrect = true)
        assertTrue(shown.any { it.text.toString() == "“$typed”" }, "the typed word is whole: ${shown.map { it.text }}")
        assertTrue(shown.size <= 2, "two suggestions instead of three: ${shown.map { it.text }}")
        assertTrue(shown.all { it.textSize < base }, "a smaller font")
    }

    @Test fun typedWordIsKnown() {
        // a known typed word is not in quotes
        val (shown, _) = show(listOf(info("hello", SuggestedWordInfo.KIND_TYPED), info("help"), info("hell")),
            "hello", valid = true, willAutoCorrect = false)
        assertTrue(shown.any { it.text.toString() == "hello" })
        assertFalse(shown.any { it.text.contains("“") })
    }

    @Test fun typedWordIsVisibleWithoutAutocorrection() {
        // the old strip hid it: [typed, s1, s2, s3] showed s2 | s1 | s3. Now: typed | s1 | s2, the typed word first (left)
        val (shown, _) = show(listOf(info("Gleamvox", SuggestedWordInfo.KIND_TYPED), info("Gleaming"), info("Gleam"), info("Gleams")),
            "Gleamvox", valid = false, willAutoCorrect = false)
        assertEquals(listOf("“Gleamvox”", "Gleaming", "Gleam"), shown.map { it.text.toString() })
    }
}
