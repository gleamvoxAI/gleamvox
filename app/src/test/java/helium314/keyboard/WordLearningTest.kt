// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.WordLearning
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ux-1: the rules that protect the personal dictionary from typos */
class WordLearningTest {
    @Test fun distanceIsDamerauLevenshtein() {
        assertEquals(0, WordLearning.distance("Hello", "hello"))
        assertEquals(1, WordLearning.distance("helo", "hello"))
        assertEquals(1, WordLearning.distance("teh", "the"))   // swapped letters are one mistake
        assertEquals(1, WordLearning.distance("hellp", "hello"))
        assertEquals(3, WordLearning.distance("kitten", "sitting"))
    }

    @Test fun aTypoOfAFrequentWordIsNotRememberedAtOnce() {
        assertTrue(WordLearning.looksLikeTypo("hellp", listOf("hello", "help")))   // one letter from an offered word
        assertTrue(WordLearning.looksLikeTypo("recieve", listOf("receive")))       // swapped letters
        // a new name or word: nothing offered is one letter away
        // (the strip may also offer the words of the personal history, but only suggestions of the main dictionary count)
        assertFalse(WordLearning.looksLikeTypo("Gleamvox", listOf("Gleam of", "Gleaming", "Gleams")))
        assertFalse(WordLearning.looksLikeTypo("Kubernetes", listOf("Kubernetes's", "Cybernetics")))
        // short words are often names or abbreviations
        assertFalse(WordLearning.looksLikeTypo("Bob", listOf("Bog", "Bob's")))
        assertFalse(WordLearning.looksLikeTypo("Gleamvox", emptyList()))
    }

    @Test fun anErasedNewWordIsTakenBackOnlyShortlyAfterAdding() {
        val en = Locale.ENGLISH
        WordLearning.clearRecent()
        WordLearning.noteAdded(en, "Gleamvox", now = 1_000)
        assertTrue(WordLearning.takeIfRecent(en, "Gleamvox", now = 1_000 + 60_000))      // within the phrase
        assertFalse(WordLearning.takeIfRecent(en, "Gleamvox", now = 1_000 + 61_000))     // only once
        WordLearning.noteAdded(en, "Zork", now = 1_000)
        assertFalse(WordLearning.takeIfRecent(en, "Zork", now = 1_000 + WordLearning.RECENT_MS + 1)) // later: the person means it
        assertFalse(WordLearning.takeIfRecent(Locale("ru"), "Gleamvox", now = 2_000))    // another language
        WordLearning.clearRecent()
    }
}
