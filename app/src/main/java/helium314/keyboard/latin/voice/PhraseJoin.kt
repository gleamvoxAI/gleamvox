// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Continuous dictation (stage 5g): how the next phrase joins the text before it. The space is added when it is inserted
 * (as for every dictated phrase); here only the first letter: a capital at the start of the field and after the end of a
 * sentence (. ! ? … or a new line). Otherwise it stays as recognized: OpenAI knew the text before (the prompt) and
 * names keep their capital.
 */
object PhraseJoin {
    private const val SENTENCE_END = ".!?…"

    /** [before]: the text before the cursor (a few characters), null if unknown: then [text] is left as it is */
    fun firstLetter(before: CharSequence?, text: String): String {
        if (before == null) return text
        val b = before.toString()
        val trimmed = b.trimEnd()
        val sentenceStart = trimmed.isEmpty() || trimmed.last() in SENTENCE_END || b.substring(trimmed.length).contains('\n')
        if (!sentenceStart) return text
        val i = text.indexOfFirst { it.isLetter() }
        if (i < 0 || text[i].isUpperCase()) return text
        return text.substring(0, i) + text[i].titlecase() + text.substring(i + 1)
    }
}

/**
 * Stage 5g-17: dictation in the middle of a sentence. Only the few characters around the cursor are looked at, on the
 * phone; nothing of them is sent. Before the cursor the last meaningful character (spaces, quotes and brackets skipped)
 * decides: text and not . ! ? … and no new line = mid-sentence. Then the first letter becomes lowercase, unless the word
 * is an abbreviation (USB), "I" (en) or a name (the lowercase form is not in the main dictionary, or the personal
 * dictionary has the capital one). After the cursor a continuing text (a letter or digit next) takes the final . ! ? off
 * the dictated text; a comma stays. A space is added after the text when a letter follows at once.
 */
object MidSentence {
    private const val END = ".!?…"
    private const val SKIPPED = "\"'«»“”„‘’()[]{}"

    class Adapted(val text: String, val mid: Boolean, val lowered: Boolean, val periodRemoved: Boolean, val spaceAfter: Boolean) {
        /** for the journal, no text */
        fun journal() = (if (mid) "mid-sentence" else "start") + ", case lowered " + (if (lowered) "yes" else "no") +
            ", final period removed " + (if (periodRemoved) "yes" else "no")
    }

    /** the cursor is inside a sentence: text before it that does not end with . ! ? … or a new line */
    fun isMid(before: CharSequence?): Boolean {
        if (before == null) return false
        val b = before.toString()
        var i = b.length
        while (i > 0 && (b[i - 1].isWhitespace() || b[i - 1] in SKIPPED)) {
            if (b[i - 1] == '\n') return false
            i--
        }
        return i > 0 && b[i - 1] !in END
    }

    /** more text of the sentence follows the cursor: the next character (spaces skipped, not a new line) is a letter or digit */
    fun continues(after: CharSequence?): Boolean {
        val a = after?.toString() ?: return false
        val c = a.firstOrNull { !(it.isWhitespace() && it != '\n') } ?: return false
        return c.isLetterOrDigit()
    }

    /** [knownLower]: the lowercase form of a word is a normal word of the language (and not a name of the personal dictionary) */
    fun adapt(before: CharSequence?, after: CharSequence?, text: String, continuous: Boolean = false, knownLower: (String) -> Boolean): Adapted {
        val mid = isMid(before)
        var out = if (continuous && !mid) PhraseJoin.firstLetter(before, text) else text
        var lowered = false
        if (mid) lowerFirst(out, knownLower)?.let { out = it; lowered = true }
        var removed = false
        val spaceAfter = after != null && after.isNotEmpty() && after[0].isLetterOrDigit()
        if (continues(after)) {
            val t = out.trimEnd()
            val cut = t.trimEnd { it in END }
            if (cut.length < t.length && cut.isNotEmpty()) { out = cut + out.substring(t.length); removed = true }
        }
        return Adapted(out, mid, lowered, removed, spaceAfter)
    }

    /** [text] with a lowercase first letter, or null when it stays (not a capital, abbreviation, "I", a name) */
    fun lowerFirst(text: String, knownLower: (String) -> Boolean): String? {
        val i = text.indexOfFirst { !(it in SKIPPED || it.isWhitespace()) }
        if (i < 0 || !text[i].isLetter() || !text[i].isUpperCase()) return null
        var j = i
        while (j < text.length && (text[j].isLetter() || text[j] == '\'' || text[j] == '’' || text[j] == '-')) j++
        val word = text.substring(i, j)
        val letters = word.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return null // an abbreviation: НДС, USB
        if (word == "I" || word.startsWith("I'") || word.startsWith("I’")) return null // English "I", "I'm"
        val lower = word.lowercase()
        if (!knownLower(lower)) return null // a name: Москва, Denis
        return text.substring(0, i) + lower + text.substring(j)
    }
}
