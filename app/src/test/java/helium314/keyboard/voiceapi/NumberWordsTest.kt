// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voice.NumberWords
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stage 5g-23: numbers of a dictation in digits (RU and EN), what stays in words, money with the sign after the number. */
class NumberWordsTest {
    private fun c(s: String) = NumberWords.apply(s).text
    private fun check(vararg pairs: Pair<String, String>) {
        for ((from, to) in pairs) assertEquals(to, c(from), from)
    }

    @Test fun percentsAndSimpleNumbers() = check(
        "сто процентов" to "100%",
        "Сто процентов." to "100%.",
        "двадцать пять процентов" to "25%",
        "полтора процента" to "1,5%",
        "семь целых пять десятых процента" to "7,5%",
        "мне нужно два часа" to "мне нужно 2 часа",
        "в комнате тридцать восемь человек" to "в комнате 38 человек",
        "ноль целых двадцать пять сотых" to "0,25",
        "ноль целых пять сотых" to "0,05",
        "fifty percent" to "50%",
        "forty-two percent of them" to "42% of them",
        "I need two hours" to "I need 2 hours",
        "a hundred and five" to "105",
    )

    @Test fun decimalsAndFractions() = check(
        "три целых пять десятых" to "3,5",
        "три целых и пять десятых" to "3,5",
        "три запятая пять" to "3,5",
        "три запятая ноль пять" to "3,05",
        "два с половиной килограмма" to "2,5 килограмма",
        "одна вторая" to "1/2",
        "две третьих" to "2/3",
        "три четверти" to "3/4",
        "пять восьмых" to "5/8",
        "three point five" to "3.5",
        "three point zero five" to "3.05",
        "two thirds" to "2/3",
        "one half" to "1/2",
        "three quarters" to "3/4",
        "two and a half" to "2.5",
    )

    @Test fun bigNumbers() = check(
        "сто двадцать тысяч" to "120 000",
        "двести тридцать четыре тысячи пятьсот шестьдесят семь" to "234 567",
        "полтора миллиона" to "1,5 млн",
        "два миллиона" to "2 млн",
        "двенадцать миллионов триста тысяч" to "12 300 000",
        "полторы тысячи" to "1500",
        "тысяча двести" to "1200",
        "one hundred twenty thousand" to "120,000",
        "one and a half million" to "1.5 million",
        "two million" to "2 million",
        "one thousand two hundred and thirty-four" to "1,234",
    )

    @Test fun moneyWithTheSignAfterTheNumber() = check(
        "доллар восемьдесят три" to "1,83$",
        "рубль двадцать" to "1,20₽",
        "два доллара пятьдесят" to "2,50$",
        "три евро пять" to "3,05€",
        "пятьсот рублей" to "500₽",
        "восемьдесят три доллара" to "83$",
        "десять евро пятьдесят центов" to "10,50€",
        "шесть рублей пятьдесят копеек" to "6,50₽",
        "один доллар восемьдесят три" to "1,83$",
        "пять тысяч рублей" to "5000₽",
        "сто двадцать тысяч рублей" to "120 000₽",
        "это стоит двести рублей, а то дорого" to "это стоит 200₽, а то дорого",
        "двадцать фунтов" to "20£",
        "100 процентов" to "100%",
        "500 рублей" to "500₽",
        "a dollar eighty-three" to "$1.83",
        "ten euros fifty cents" to "€10.50",
        "five hundred dollars" to "$500",
        "two dollars fifty" to "$2.50",
        "five hundred thousand dollars" to "$500,000",
    )

    @Test fun datesYearsFloors() = check(
        "семнадцатое октября" to "17 октября",
        "двадцать пятого мая" to "25 мая",
        "тридцать первое декабря две тысячи двадцать шестого года" to "31 декабря 2026 года",
        "две тысячи двадцать шестой год" to "2026 год",
        "первый этаж" to "1-й этаж",
        "на втором этаже" to "на 2-м этаже",
        "twenty twenty-six" to "2026",
        "in nineteen ninety-nine" to "in 1999",
    )

    /** idioms, pronouns and ordinals of plain speech stay words */
    @Test fun whatStaysInWords() {
        for (s in listOf("один раз", "ни одного", "одна из них", "во-первых", "первый раз", "первый вопрос", "оба", "в одиночку",
                "половина команды", "четверть часа", "no one knows", "one of them", "the one", "двадцать первый вопрос",
                "двадцати-пятилетний", "я думаю, что это хорошо", "Тысяча и одна ночь", "доллар вырос", "курс доллара", "Привет, как дела?")) {
            assertEquals(s, c(s), s)
        }
    }

    /** the cases of the owner, as in the task */
    @Test fun ownersExamples() = check(
        "сто процентов" to "100%",
        "доллар восемьдесят три" to "1,83$",
        "рубль двадцать" to "1,20₽",
        "два доллара пятьдесят" to "2,50$",
        "три евро пять" to "3,05€",
    )

    /** a number in the middle of a text; the sentence keeps its punctuation and the case rules of 5g-17 still work */
    @Test fun insideTextAndTheCountIsKept() {
        val r = NumberWords.apply("Мне прислали сто двадцать тысяч рублей и ещё три процента, один раз.")
        assertEquals("Мне прислали 120 000₽ и ещё 3%, один раз.", r.text)
        assertEquals(2, r.converted)
        assertEquals(0, NumberWords.apply("Привет, как дела?").converted)
    }

    /** after the conversion the rule of 5g-17 (lowercase in the middle of a sentence, no final period) works as before */
    @Test fun worksWithTheMidSentenceRule() {
        val a = helium314.keyboard.latin.voice.MidSentence.adapt("Я заплатил", " за это", c("Сто двадцать тысяч рублей."), false) { true }
        assertEquals("120 000₽", a.text)
        assertEquals(true, a.periodRemoved)
    }
}
