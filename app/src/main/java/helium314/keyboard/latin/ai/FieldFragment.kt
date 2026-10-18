// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

/** reading and writing the current field; on the phone through the input connection, a fake field in tests */
interface FieldAccess {
    /** absolute positions in the field, -1 if unknown */
    fun selectionStart(): Int
    fun selectionEnd(): Int
    fun selectedText(): CharSequence?
    fun textBefore(n: Int): CharSequence?
    fun textAfter(n: Int): CharSequence?
    /** replaces [start, end) in one batch edit */
    fun replace(start: Int, end: Int, text: String): Boolean
    fun insertAt(position: Int, text: String): Boolean
    /** at the cursor (replacing a selection, like typing) */
    fun insertAtCursor(text: String): Boolean
    /** puts the cursor at [position] (stage 5g: back where it was after a phrase before it was cleaned) */
    fun setCursor(position: Int): Boolean = false
}

/** what was sent: the text and where it was in the field */
class Fragment(val text: String, val start: Int, val end: Int, val fromSelection: Boolean) {
    override fun toString() = "Fragment($start..$end, ${if (fromSelection) "selection" else "field"})" // never the text
}

sealed class Capture {
    class Ok(val fragment: Fragment) : Capture()
    object Empty : Capture()
    /** longer than [FieldFragment.MAX_CHARS]: the user is asked to select a part */
    object TooLong : Capture()
    /** the field does not tell its text or cursor */
    object Unknown : Capture()
}

/**
 * The source of a ✨ command (docs/CHATGPT.md, section 4.1): the selection, without a selection the whole field;
 * at most 4000 characters. Before "Replace" the field is checked: the fragment must still be exactly there.
 * Nothing outside the fragment is ever changed.
 */
object FieldFragment {
    const val MAX_CHARS = 4000

    fun capture(f: FieldAccess): Capture {
        val start = f.selectionStart()
        val end = f.selectionEnd()
        if (start < 0 || end < 0) return Capture.Unknown
        if (end > start) {
            if (end - start > MAX_CHARS) return Capture.TooLong
            val selected = f.selectedText()?.toString() ?: return Capture.Unknown
            if (selected.length > MAX_CHARS) return Capture.TooLong
            if (selected.isBlank()) return Capture.Empty
            return Capture.Ok(Fragment(selected, start, end, true))
        }
        val whole = wholeField(f, start) ?: return Capture.TooLong
        if (whole.second.isBlank()) return Capture.Empty
        return Capture.Ok(Fragment(whole.second, 0, whole.second.length, false))
    }

    /** (cursor, text) of the whole field when it is at most [MAX_CHARS] long and could be read completely */
    private fun wholeField(f: FieldAccess, cursor: Int): Pair<Int, String>? {
        val before = f.textBefore(MAX_CHARS + 1)?.toString() ?: return null
        val selected = if (f.selectionEnd() > cursor) f.selectedText()?.toString() ?: return null else ""
        val after = f.textAfter(MAX_CHARS + 1)?.toString() ?: return null
        if (before.length != cursor) return null // the field did not give everything before the cursor
        val text = before + selected + after
        if (text.length > MAX_CHARS) return null
        return cursor to text
    }

    /** the fragment is still exactly where it was (selection: same selection and text; field: same whole text) */
    fun isUnchanged(f: FieldAccess, fragment: Fragment): Boolean {
        val start = f.selectionStart()
        val end = f.selectionEnd()
        if (start < 0 || end < 0) return false
        if (fragment.fromSelection)
            return start == fragment.start && end == fragment.end && f.selectedText()?.toString() == fragment.text
        return wholeField(f, start)?.second == fragment.text
    }

    /** "Replace": only if the fragment is unchanged; one batch edit of exactly the fragment */
    fun replace(f: FieldAccess, fragment: Fragment, answer: String): Boolean =
        isUnchanged(f, fragment) && f.replace(fragment.start, fragment.end, answer)

    /** "Insert next to": right after the fragment if it is unchanged, else at the cursor ("insert as it is") */
    fun insertNextTo(f: FieldAccess, fragment: Fragment, answer: String): Boolean =
        if (isUnchanged(f, fragment)) f.insertAt(fragment.end, joiner(fragment.text, answer) + answer)
        else f.insertAtCursor(answer)

    /** a space between the fragment and the inserted answer, unless one of them already has it or it starts with punctuation */
    fun joiner(before: String, answer: String): String {
        val last = before.lastOrNull() ?: return ""
        val first = answer.firstOrNull() ?: return ""
        return if (last.isWhitespace() || first.isWhitespace() || first in ".,;:!?)»”") "" else " "
    }
}
