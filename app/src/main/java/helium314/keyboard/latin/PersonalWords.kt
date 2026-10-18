// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.util.Locale

/**
 * ux-2 (owner, build 4417: learned "Gleamvox" was known, but "Gleam" did not offer it): the words of the personal
 * dictionary, kept in memory by language, so that the suggestion strip can offer them as a continuation of the typed
 * beginning ([completions]) without depending on how the native engine scores a completion from a small dictionary.
 *  - filled when the personal dictionary is read from the Android provider, and when a word is remembered at once;
 *  - the newest word comes first (a word that was just learned is the most likely one to be typed again);
 *  - a word is removed when the person removes it (trash, "do not suggest again", take-back).
 * Only words are kept, no text around them; nothing leaves the phone.
 */
object PersonalWords {
    /** the continuation is offered after this many typed letters */
    const val MIN_PREFIX = 2
    private const val MAX_PER_LOCALE = 5000

    private val byLocale = HashMap<String, LinkedHashSet<String>>()

    /** the personal dictionary of [locale] was read again: [words] (oldest first) replace what was known */
    @Synchronized fun replace(locale: String, words: List<String>) {
        byLocale.remove(locale)
        words.forEach { add(locale, it) }
    }

    @Synchronized fun add(locale: String, word: String) {
        if (word.isEmpty() || word.any { it.isWhitespace() }) return
        val set = byLocale.getOrPut(locale) { LinkedHashSet() }
        set.remove(word) // the newest goes last
        set.add(word)
        while (set.size > MAX_PER_LOCALE) set.remove(set.first())
    }

    @Synchronized fun remove(word: String) {
        byLocale.values.forEach { set -> set.removeAll { it.equals(word, ignoreCase = true) } }
    }

    @Synchronized fun clearAll() = byLocale.clear()

    @Synchronized fun size() = byLocale.values.sumOf { it.size }

    /** the words that begin with [typed] (any letter case) and are longer than it, newest first */
    @Synchronized fun completions(locale: String, typed: String, limit: Int, collator: Locale = Locale.ROOT): List<String> {
        if (typed.length < MIN_PREFIX || limit <= 0) return emptyList()
        val set = byLocale[locale] ?: return emptyList()
        val prefix = typed.lowercase(collator)
        val out = ArrayList<String>()
        for (w in set.reversed()) {
            if (w.length > typed.length && w.lowercase(collator).startsWith(prefix)) {
                out.add(w)
                if (out.size >= limit) break
            }
        }
        return out
    }
}
