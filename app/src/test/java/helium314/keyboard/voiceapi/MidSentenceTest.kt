// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voice.MidSentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** stage 5g-17: dictation in the middle of a sentence (case, final period, spaces) */
class MidSentenceTest {
    // words whose lowercase form is a normal word (names like москва / denis are not)
    private val known = setOf("я", "как", "давай", "пойдём", "мы", "завтра", "this", "the", "it", "we", "then", "usb", "что", "потом")
    private val knownLower = { w: String -> w in known }

    private fun adapt(before: String?, after: String?, text: String, continuous: Boolean = false) =
        MidSentence.adapt(before, after, text, continuous, knownLower)

    @Test fun midSentenceRussianLowersTheFirstLetter() {
        val a = adapt("Сегодня мы пойдём,", "", "Как дела?")
        assertEquals("как дела?", a.text)
        assertTrue(a.mid && a.lowered)
        assertEquals("mid-sentence, case lowered yes, final period removed no", a.journal())
    }

    @Test fun midSentenceEnglish() {
        assertEquals("then we go", adapt("I think that", null, "Then we go").text)
    }

    @Test fun afterASentenceEndOrAtTheStartItStaysCapital() {
        for (b in listOf("Привет.", "Привет! ", "Привет? ", "Привет… ", "Привет.\n", "Привет\n", "Привет. \"", "", "   ", "\"", "(", null)) {
            val a = adapt(b, "", "Как дела?")
            assertEquals("Как дела?", a.text, "before=$b")
            assertFalse(a.lowered, "before=$b")
        }
        assertEquals("start, case lowered no, final period removed no", adapt("", "", "Как дела.").journal())
    }

    @Test fun quotesAndBracketsAndSpacesAreSkipped() {
        assertEquals("как", adapt("он сказал: «", "", "Как").text)
        assertEquals("как", adapt("текст (  ", "", "Как").text)
        assertEquals("Как", adapt("Он сказал «Да.» ", "", "Как").text, "the sentence ended inside the quote")
    }

    @Test fun abbreviationsStayCapital() {
        assertEquals("НДС вырос", adapt("в этом году", "", "НДС вырос").text)
        assertEquals("USB порт", adapt("через", "", "USB порт").text)
        assertEquals("A", adapt("через", "", "A").text.uppercase())
    }

    @Test fun namesStayCapital() {
        assertEquals("Москва большая", adapt("мы едем в", "", "Москва большая").text)
        assertEquals("Denis is here", adapt("and", "", "Denis is here").text)
    }

    @Test fun englishIStaysAndRussianYaGoesDown() {
        assertEquals("I think so", adapt("and", "", "I think so").text)
        assertEquals("I'm sure", adapt("and", "", "I'm sure").text)
        assertEquals("I’ll go", adapt("and", "", "I’ll go").text)
        assertEquals("я пойду", adapt("и", "", "Я пойду").text)
    }

    @Test fun textAfterTheCursorTakesTheFinalPeriodOff() {
        val a = adapt("Мы пойдём", " завтра в кино", "Давай вместе.")
        assertEquals("давай вместе", a.text)
        assertTrue(a.periodRemoved)
        assertEquals("давай вместе", adapt("Мы", "завтра", "давай вместе!").text)
        assertEquals("давай вместе", adapt("Мы", "завтра", "давай вместе?!").text)
        assertEquals("давай вместе,", adapt("Мы", "завтра", "давай вместе,").text, "a comma stays")
        assertEquals("давай вместе", adapt("Мы", "1 раз", "давай вместе...").text)
        assertEquals("давай вместе", adapt("Мы", "завтра", "давай вместе…").text)
    }

    @Test fun noTextAfterTheCursorKeepsThePeriod() {
        assertEquals("давай вместе.", adapt("Мы", "", "Давай вместе.").text)
        assertEquals("давай вместе.", adapt("Мы", null, "Давай вместе.").text)
        assertEquals("давай вместе.", adapt("Мы", "\nСледующая строка", "Давай вместе.").text, "a new line ends it")
        assertEquals("давай вместе.", adapt("Мы", "  ", "Давай вместе.").text)
        assertEquals("давай вместе.", adapt("Мы", ". Потом", "Давай вместе.").text, "a period follows")
    }

    @Test fun periodRemovalWorksAtTheStartOfASentenceToo() {
        val a = adapt("Привет. ", "и дальше", "Как дела.")
        assertEquals("Как дела", a.text)
        assertFalse(a.mid)
        assertTrue(a.periodRemoved)
    }

    @Test fun aSpaceAfterOnlyWhenALetterFollowsAtOnce() {
        assertTrue(adapt("Мы", "завтра", "давай").spaceAfter)
        assertTrue(adapt("Мы", "1 раз", "давай").spaceAfter)
        assertFalse(adapt("Мы", " завтра", "давай").spaceAfter, "no double space")
        assertFalse(adapt("Мы", "", "давай").spaceAfter)
        assertFalse(adapt("Мы", ",", "давай").spaceAfter)
        assertFalse(adapt("Мы", null, "давай").spaceAfter)
    }

    @Test fun continuousPhrasesContinueTheSentence() {
        // the first phrase did not end with a sentence end: the next one goes on with a lowercase letter
        assertEquals("потом пойдём", adapt("Сначала мы идём,", "", "Потом пойдём", continuous = true).text)
        // it ended: the next one is a new sentence, with a capital (also if it came lowercase)
        assertEquals("Потом пойдём", adapt("Сначала мы идём.", "", "потом пойдём", continuous = true).text)
        assertEquals("Потом пойдём", adapt("", "", "потом пойдём", continuous = true).text)
        // not continuous: a lowercase phrase at the start is left as it is
        assertEquals("потом пойдём", adapt("Сначала мы идём.", "", "потом пойдём").text)
    }

    @Test fun lowerFirstLeavesNonCapitalsAlone() {
        assertEquals(null, MidSentence.lowerFirst("как", knownLower))
        assertEquals(null, MidSentence.lowerFirst("123 Как", knownLower))
        assertEquals(null, MidSentence.lowerFirst("", knownLower))
        assertEquals("«как» дела", MidSentence.lowerFirst("«Как» дела", knownLower))
    }
}
