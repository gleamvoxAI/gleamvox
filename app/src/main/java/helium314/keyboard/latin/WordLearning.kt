// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.provider.UserDictionary
import helium314.keyboard.latin.utils.Log
import java.util.Locale

/**
 * ux-1 (owner's decision 2026-10-18): a new word that the person types and leaves is remembered at once, from the first
 * time, in the personal dictionary of the current language; it is suggested from the next typing and never autocorrected
 * away. Protection from remembering typos (it does not cancel "at once"):
 *  - a word that is erased or replaced right away (within the same phrase) is removed again ([RECENT_MS]);
 *  - a word that looks like a typo of a frequent dictionary word (one letter different, and the dictionary offered
 *    that word) is not remembered at once; it goes the old way (after 3 uses), unless the person taps it in the strip;
 *  - never in password, e-mail, number, "do not remember" fields, incognito, in the excluded apps (checked by the caller).
 * Nothing but the word itself is stored (the Android personal dictionary), no text around it.
 */
object WordLearning {
    private const val TAG = "WordLearning"
    /** how long a remembered word is still taken back when the person erases or replaces it */
    const val RECENT_MS = 2 * 60 * 1000L
    private const val MAX_RECENT = 8
    const val FREQUENCY = 250

    private val recent = LinkedHashMap<String, Long>() // "locale\u0000word" -> time of adding

    private fun key(locale: Locale, word: String) = "$locale\u0000$word"

    @Synchronized fun noteAdded(locale: Locale, word: String, now: Long = System.currentTimeMillis()) {
        recent[key(locale, word)] = now
        while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
    }

    /** true (once) if [word] was added automatically a moment ago: then erasing it takes it back */
    @Synchronized fun takeIfRecent(locale: Locale, word: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = recent.remove(key(locale, word)) ?: return false
        return now - t <= RECENT_MS
    }

    @Synchronized fun clearRecent() = recent.clear()

    // ---- counts for the owner (ux-3): how many words were remembered by themselves, by a tap, and why not ----
    private const val PREF_STATS = "aikb_word_learning_stats"
    enum class Stat { AUTO, TAP, SKIPPED_TYPO, SKIPPED_KNOWN_LOWER }

    @Synchronized fun count(prefs: SharedPreferences, stat: Stat) {
        val v = readStats(prefs)
        v[stat.ordinal]++
        prefs.edit { putString(PREF_STATS, v.joinToString(",")) }
    }

    private fun readStats(prefs: SharedPreferences): IntArray {
        val parts = prefs.getString(PREF_STATS, "")?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        return IntArray(Stat.entries.size) { parts.getOrElse(it) { 0 } }
    }

    /** counts only, never words: shown on the AI dictionary screen */
    fun summary(prefs: SharedPreferences, personalWords: Int): String {
        val v = readStats(prefs)
        return "Свои слова: $personalWords (само ${v[Stat.AUTO.ordinal]}, нажатием ${v[Stat.TAP.ordinal]}); " +
            "не запомнено как опечатка ${v[Stat.SKIPPED_TYPO.ordinal]}, как обычное слово с большой буквы ${v[Stat.SKIPPED_KNOWN_LOWER.ordinal]}"
    }

    /** writes the word into the Android personal dictionary (the provider); off the main thread */
    fun insertIntoProvider(context: Context, word: String, locale: Locale) {
        try {
            val values = ContentValues().apply {
                put(UserDictionary.Words.WORD, word)
                put(UserDictionary.Words.FREQUENCY, FREQUENCY)
                put(UserDictionary.Words.LOCALE, locale.toString())
                put(UserDictionary.Words.APP_ID, 0)
            }
            context.contentResolver.insert(UserDictionary.Words.CONTENT_URI, values)
        } catch (e: Exception) { // some devices have no personal dictionary provider (HeliBoard knows it, too)
            Log.w(TAG, "cannot add the word to the personal dictionary: ${e.javaClass.simpleName}")
        }
    }

    /** removes the word from the Android personal dictionary (the provider) */
    fun deleteFromProvider(context: Context, word: String) {
        try {
            context.contentResolver.delete(UserDictionary.Words.CONTENT_URI, "${UserDictionary.Words.WORD}=?", arrayOf(word))
        } catch (e: Exception) {
            Log.w(TAG, "cannot remove the word from the personal dictionary: ${e.javaClass.simpleName}")
        }
    }

    /** Damerau-Levenshtein distance (one swap of neighbours counts as one), lower case */
    fun distance(a0: String, b0: String): Int {
        val a = a0.lowercase(); val b = b0.lowercase()
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }

    /**
     * The typed [word] is probably a typo if the dictionary offered a word [offered] (the first suggestions that come from
     * the main dictionary) that is one letter away from it, with at least 4 letters (short words are too often names).
     */
    fun looksLikeTypo(word: String, offered: List<String>): Boolean {
        if (word.length < 4) return false
        return offered.any { it.length >= 4 && !it.equals(word, ignoreCase = true) && distance(word, it) == 1 }
    }
}
