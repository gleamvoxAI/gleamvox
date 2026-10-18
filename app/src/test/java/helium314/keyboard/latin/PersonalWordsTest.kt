// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalWordsTest {
    @BeforeTest fun reset() = PersonalWords.clearAll()

    @Test fun `continuations by the beginning, any letter case, newest first`() {
        PersonalWords.add("en", "Gleamvox")
        PersonalWords.add("en", "gleamy")
        PersonalWords.add("en", "Glen")
        assertEquals(listOf("gleamy", "Gleamvox"), PersonalWords.completions("en", "GLEAM", 5))
        assertEquals(listOf("gleamy"), PersonalWords.completions("en", "glea", 1))
        assertEquals(listOf("Glen", "gleamy", "Gleamvox"), PersonalWords.completions("en", "Gle", 5))
    }

    @Test fun `not for one letter, not the word itself, not another language`() {
        PersonalWords.add("en", "Gleamvox")
        assertEquals(emptyList(), PersonalWords.completions("en", "G", 5))
        assertEquals(emptyList(), PersonalWords.completions("en", "Gleamvox", 5))
        assertEquals(emptyList(), PersonalWords.completions("ru", "Gle", 5))
    }

    @Test fun `a removed word is gone, a reread dictionary replaces the list`() {
        PersonalWords.add("en", "Gleamvox")
        PersonalWords.remove("gleamvox")
        assertEquals(emptyList(), PersonalWords.completions("en", "Gle", 5))
        PersonalWords.replace("en", listOf("Alpha", "Alphabet"))
        PersonalWords.replace("en", listOf("Alphabet", "Alpha1"))
        assertEquals(listOf("Alpha1", "Alphabet"), PersonalWords.completions("en", "Al", 5))
    }

    @Test fun `words with spaces are not kept`() {
        PersonalWords.add("en", "two words")
        assertEquals(emptyList(), PersonalWords.completions("en", "tw", 5))
    }
}

/** ux-4: the list of the person's words is looked through at every key; it must stay far below a frame (16 ms) */
class PersonalWordsSpeedTest {
    @Test fun lookupInAThousandWordsTakesAFewMilliseconds() {
        PersonalWords.clearAll()
        for (i in 0 until 5000) PersonalWords.add("en", "word" + i.toString(36) + "x")
        PersonalWords.completions("en", "wo", 1) // warm up
        val t = System.nanoTime()
        repeat(100) { PersonalWords.completions("en", "zzq", 1) } // no match: the whole list is looked through
        val perCallMs = (System.nanoTime() - t) / 100 / 1e6
        println("PersonalWords.completions, 5000 words, no match: %.3f ms".format(perCallMs))
        kotlin.test.assertTrue(perCallMs < 8, "$perCallMs ms")
    }
}
