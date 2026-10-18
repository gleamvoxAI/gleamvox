// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Stage 5g-23 (the owner 2026-10-17: numbers of a dictation in digits): "сто процентов" → "100%", "доллар восемьдесят три"
 * → "1,83$", "пятьсот рублей" → "500₽", "три целых пять десятых" → "3,5", "две третьих" → "2/3", "сто двадцать тысяч" →
 * "120 000", "полтора миллиона" → "1,5 млн"; English "a dollar eighty-three" → "$1.83", "three point five" → "3.5".
 * The prompt of the recognizer and the cleanup ask for digits too; this is the sure part, a plain function of the text
 * (no model, no network), run on every dictated phrase before it goes in (VoiceInput.insert) and on the cleaned text.
 *
 * Kept as words: one alone ("один раз", "одна из", "no one", "one of"), which is mostly a pronoun; ordinals in plain speech
 * ("первый вопрос", "во-первых"), "половина команды" (a noun). Written in digits: quantities, sums, percents, decimals,
 * simple fractions, big numbers, dates ("семнадцатое октября" → "17 октября"), years, "1-й этаж", also digits followed by a
 * word of percent or money ("100 процентов" → "100%"). Money: the sign after the number without a space in Russian
 * (1,83$ 500₽ 10,50€), before the number in English ($1.83); "currency + number" is whole and fraction ("доллар
 * восемьдесят три", "два доллара пятьдесят", "три евро пять" → 3,05€).
 */
object NumberWords {
    class Result(val text: String, val converted: Int)

    fun convert(text: String): Result {
        if (text.isBlank()) return Result(text, 0)
        val p = Parser(text)
        return p.run()
    }

    // ---------------------------------------------------------------- tables

    private enum class K { UNIT, TEEN, TENS, HUND, SCALE }
    private class W(val kind: K, val value: Long, val one: Boolean = false)

    private fun ruTable(): Map<String, W> {
        val m = HashMap<String, W>()
        fun add(kind: K, value: Long, vararg forms: String, one: Boolean = false) { for (f in forms) m[f] = W(kind, value, one) }
        add(K.UNIT, 0, "ноль", "нуль", "ноля", "нуля")
        add(K.UNIT, 1, "один", "одна", "одно", "одну", "одного", "одной", "одному", "одним", "одном", "одни", "одних", one = true)
        add(K.UNIT, 2, "два", "две", "двух", "двум", "двумя")
        add(K.UNIT, 3, "три", "трех", "трем", "тремя")
        add(K.UNIT, 4, "четыре", "четырех", "четырем", "четырьмя")
        add(K.UNIT, 5, "пять", "пяти", "пятью")
        add(K.UNIT, 6, "шесть", "шести", "шестью")
        add(K.UNIT, 7, "семь", "семи")
        add(K.UNIT, 8, "восемь", "восьми", "восемью")
        add(K.UNIT, 9, "девять", "девяти", "девятью")
        add(K.TEEN, 10, "десять", "десяти")
        add(K.TEEN, 11, "одиннадцать", "одиннадцати")
        add(K.TEEN, 12, "двенадцать", "двенадцати")
        add(K.TEEN, 13, "тринадцать", "тринадцати")
        add(K.TEEN, 14, "четырнадцать", "четырнадцати")
        add(K.TEEN, 15, "пятнадцать", "пятнадцати")
        add(K.TEEN, 16, "шестнадцать", "шестнадцати")
        add(K.TEEN, 17, "семнадцать", "семнадцати")
        add(K.TEEN, 18, "восемнадцать", "восемнадцати")
        add(K.TEEN, 19, "девятнадцать", "девятнадцати")
        add(K.TENS, 20, "двадцать", "двадцати")
        add(K.TENS, 30, "тридцать", "тридцати")
        add(K.TENS, 40, "сорок", "сорока")
        add(K.TENS, 50, "пятьдесят", "пятидесяти")
        add(K.TENS, 60, "шестьдесят", "шестидесяти")
        add(K.TENS, 70, "семьдесят", "семидесяти")
        add(K.TENS, 80, "восемьдесят", "восьмидесяти")
        add(K.TENS, 90, "девяносто", "девяноста")
        add(K.HUND, 100, "сто", "ста")
        add(K.HUND, 200, "двести", "двухсот")
        add(K.HUND, 300, "триста", "трехсот")
        add(K.HUND, 400, "четыреста", "четырехсот")
        add(K.HUND, 500, "пятьсот", "пятисот")
        add(K.HUND, 600, "шестьсот", "шестисот")
        add(K.HUND, 700, "семьсот", "семисот")
        add(K.HUND, 800, "восемьсот", "восьмисот")
        add(K.HUND, 900, "девятьсот", "девятисот")
        add(K.SCALE, 1_000, "тысяча", "тысячи", "тысяч", "тысячу", "тысячей")
        add(K.SCALE, 1_000_000, "миллион", "миллиона", "миллионов", "миллионы", "миллионом")
        add(K.SCALE, 1_000_000_000, "миллиард", "миллиарда", "миллиардов", "миллиарды")
        return m
    }

    private fun enTable(): Map<String, W> {
        val m = HashMap<String, W>()
        fun add(kind: K, value: Long, form: String, one: Boolean = false) { m[form] = W(kind, value, one) }
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").forEachIndexed { i, w -> add(K.UNIT, i.toLong(), w, one = i == 1) }
        listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
            .forEachIndexed { i, w -> add(K.TEEN, 10L + i, w) }
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").forEachIndexed { i, w -> add(K.TENS, 20L + 10 * i, w) }
        add(K.SCALE, 1_000, "thousand"); add(K.SCALE, 1_000_000, "million"); add(K.SCALE, 1_000_000_000, "billion")
        return m
    }

    private val RU = ruTable()
    private val EN = enTable()

    /** ordinals of Russian, by form: value; stems × endings (the second of the date, the year, the floor) */
    private val RU_ORD: Map<String, Long> by lazy {
        val m = HashMap<String, Long>()
        val hard = listOf("ый", "ой", "ая", "ое", "ого", "ому", "ым", "ом", "ую", "ые", "ых", "ыми", "ою")
        val soft = listOf("ий", "ья", "ье", "ьего", "ьему", "ьим", "ьем", "ью", "ьи", "ьих", "ьими", "ьей")
        listOf("перв" to 1L, "втор" to 2L, "четверт" to 4L, "пят" to 5L, "шест" to 6L, "седьм" to 7L, "восьм" to 8L, "девят" to 9L,
            "десят" to 10L, "одиннадцат" to 11L, "двенадцат" to 12L, "тринадцат" to 13L, "четырнадцат" to 14L, "пятнадцат" to 15L,
            "шестнадцат" to 16L, "семнадцат" to 17L, "восемнадцат" to 18L, "девятнадцат" to 19L, "двадцат" to 20L, "тридцат" to 30L,
            "сороков" to 40L, "пятидесят" to 50L, "шестидесят" to 60L, "семидесят" to 70L, "восьмидесят" to 80L, "девяност" to 90L,
            "сот" to 100L).forEach { (s, v) -> for (e in hard) m[s + e] = v }
        for (e in soft) m["трет$e"] = 3L
        for (e in hard) m["трет$e"] = 3L
        m
    }

    private val RU_DENOM = mapOf(
        "вторая" to 2L, "вторых" to 2L, "третья" to 3L, "третьих" to 3L, "четвертая" to 4L, "четвертых" to 4L, "пятая" to 5L, "пятых" to 5L,
        "шестая" to 6L, "шестых" to 6L, "седьмая" to 7L, "седьмых" to 7L, "восьмая" to 8L, "восьмых" to 8L, "девятая" to 9L, "девятых" to 9L,
        "десятая" to 10L, "десятых" to 10L, "сотая" to 100L, "сотых" to 100L,
        // "две трети", "три четверти", "одна четверть" after a number
        "треть" to 3L, "трети" to 3L, "четверть" to 4L, "четверти" to 4L)
    private val EN_DENOM = mapOf(
        "half" to 2L, "halves" to 2L, "third" to 3L, "thirds" to 3L, "quarter" to 4L, "quarters" to 4L, "fourth" to 4L, "fourths" to 4L,
        "fifth" to 5L, "fifths" to 5L, "sixth" to 6L, "sixths" to 6L, "seventh" to 7L, "sevenths" to 7L, "eighth" to 8L, "eighths" to 8L,
        "ninth" to 9L, "ninths" to 9L, "tenth" to 10L, "tenths" to 10L)

    /** decimal places named: "пять десятых" = 1 place, "сотых" 2, "тысячных" 3 */
    private val RU_PLACES = mapOf("десятых" to 1, "десятая" to 1, "десятые" to 1, "сотых" to 2, "сотая" to 2, "сотые" to 2, "тысячных" to 3, "тысячная" to 3)
    private val RU_WHOLE = setOf("целых", "целая", "целое", "целый")

    private val RU_PERCENT = setOf("процент", "процента", "процентов", "проценты", "процентах")
    private val EN_PERCENT = setOf("percent", "percents")

    private class Cur(val sign: String, val ru: Boolean, val nominative: Boolean, val bareOk: Boolean)
    private val CUR: Map<String, Cur> = buildMap {
        for (f in listOf("рубль", "рубля", "рублей", "рубли", "рублю")) put(f, Cur("₽", true, f == "рубль", f == "рубль"))
        for (f in listOf("доллар", "доллара", "долларов", "доллары", "доллару")) put(f, Cur("$", true, f == "доллар", f == "доллар"))
        for (f in listOf("евро")) put(f, Cur("€", true, false, false))
        for (f in listOf("фунт", "фунта", "фунтов")) put(f, Cur("£", true, f == "фунт", f == "фунт"))
        for (f in listOf("гривна", "гривны", "гривен", "гривну")) put(f, Cur("₴", true, f == "гривна", f == "гривна"))
        for (f in listOf("тенге")) put(f, Cur("₸", true, false, false))
        for (f in listOf("иена", "иены", "иен")) put(f, Cur("¥", true, f == "иена", f == "иена"))
        for (f in listOf("dollar", "dollars")) put(f, Cur("$", false, f == "dollar", f == "dollar"))
        for (f in listOf("euro", "euros")) put(f, Cur("€", false, f == "euro", f == "euro"))
        for (f in listOf("pound", "pounds")) put(f, Cur("£", false, f == "pound", f == "pound"))
        for (f in listOf("ruble", "rubles", "rouble", "roubles")) put(f, Cur("₽", false, f.endsWith("e"), f.endsWith("e")))
        for (f in listOf("yen")) put(f, Cur("¥", false, false, false))
    }
    private val MINOR = setOf("копейка", "копейки", "копеек", "копейку", "цент", "цента", "центов", "центу", "евроцент", "евроцента", "евроцентов",
        "пенс", "пенни", "cent", "cents", "penny", "pence")
    private val MONTHS = setOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val YEAR_WORDS = setOf("год", "года", "году", "годом", "годе")
    private val ORD_NOUNS = setOf("этаж", "этажа", "этажу", "этажом", "этаже", "класс", "класса", "классу", "классом", "классе",
        "курс", "курса", "курсу", "курсом", "курсе", "квартал", "квартала", "кварталу", "кварталом", "квартале")
    /** a minor part without a minor word counts only if the text ends or goes on with one of these (or punctuation) */
    private val FOLLOW_OK = setOf("и", "а", "но", "за", "на", "в", "во", "с", "со", "до", "при", "по", "или", "для", "от", "из",
        "and", "or", "but", "for", "at", "on", "in", "with", "per", "to", "from")

    // ---------------------------------------------------------------- parser

    private class T(val s: String, val start: Int, val end: Int, val word: Boolean) {
        val n = s.lowercase().replace('ё', 'е')
        val cyr = word && s.any { it in 'Ѐ'..'ӿ' }
    }

    private class Num(
        val value: BigDecimal, val end: Int, val ru: Boolean, val one: Boolean, val lastScale: Long, val raw: String?,
        /** "2/3" and the like instead of the value */
        val fraction: String? = null,
        /** a year: no digit grouping */
        val year: Boolean = false,
    )

    private class Match(val out: String, val end: Int)

    private class Parser(val text: String) {
        val toks: List<T> = Regex("\\p{L}+|\\d+(?:[.,]\\d+)?").findAll(text).map { m ->
            T(m.value, m.range.first, m.range.last + 1, m.value[0].isLetter())
        }.toList()
        var count = 0

        fun run(): Result {
            val out = StringBuilder()
            var last = 0
            var i = 0
            while (i < toks.size) {
                val m = try { matchAt(i) } catch (e: Exception) { null }
                if (m != null && guardOk(i, m.end)) {
                    out.append(text, last, toks[i].start).append(m.out)
                    last = toks[m.end - 1].end
                    i = m.end
                    count++
                } else i++
            }
            out.append(text, last, text.length)
            return Result(out.toString(), count)
        }

        /** no conversion inside words joined by a hyphen ("двадцати-пятилетний", "по-двое") */
        fun guardOk(from: Int, to: Int): Boolean {
            val a = toks[from].start
            val b = toks[to - 1].end
            if (a >= 2 && text[a - 1] == '-' && text[a - 2].isLetter()) return false
            if (b + 1 < text.length && text[b] == '-' && text[b + 1].isLetter()) return false
            return true
        }

        fun joinable(a: Int, b: Int): Boolean {
            if (b >= toks.size || a < 0) return false
            val sep = text.substring(toks[a].end, toks[b].start)
            if (sep.isNotEmpty() && sep.all { it == ' ' || it == ' ' || it == '\t' }) return true
            return sep == "-" && toks[a].word && toks[b].word && !toks[a].cyr && !toks[b].cyr
        }

        fun punctBetween(a: Int, b: Int): Boolean = text.substring(toks[a].end, toks[b].start).any { !it.isWhitespace() }

        fun tab(t: T): Map<String, W> = if (t.cyr) RU else EN

        // ------------------------------------------------------------ cardinals

        /**
         * a cardinal from token [i]: group by group (hundreds, tens, units) with the scale words; [ordEnd]: the last word
         * may be an ordinal (the date, the year). Returns null if there is no number at [i]. Words that cannot go on
         * the number start the next one.
         */
        fun cardinal(i: Int, ordEnd: Boolean = false): Num? {
            val t0 = toks.getOrNull(i) ?: return null
            if (!t0.word) {
                val v = BigDecimal(t0.s.replace(',', '.'))
                var j = i + 1
                var value = v
                var lastScale = 0L
                // "5 тысяч", "3 миллиона"
                if (v.scale() == 0 && j < toks.size && joinable(j - 1, j) && toks[j].word) {
                    val w = tab(toks[j])[toks[j].n]
                    if (w != null && w.kind == K.SCALE) { value = v.multiply(BigDecimal(w.value)); lastScale = w.value; j++ }
                }
                return Num(value, j, !t0.s.all { it.isDigit() } && t0.s.contains(','), false, lastScale, if (lastScale == 0L) t0.s else null)
            }
            return if (t0.cyr) ruCardinal(i, ordEnd) else enCardinal(i)
        }

        fun ruCardinal(i: Int, ordEnd: Boolean): Num? {
            val t0 = toks[i]
            // полтора / полторы (and "полторы тысячи")
            if (t0.n == "полтора" || t0.n == "полторы") {
                var j = i + 1
                var v = BigDecimal("1.5")
                var scale = 0L
                if (j < toks.size && joinable(j - 1, j)) {
                    val w = RU[toks[j].n]
                    if (w != null && w.kind == K.SCALE) { v = v.multiply(BigDecimal(w.value)); scale = w.value; j++ }
                }
                return Num(v, j, true, false, scale, null)
            }
            var total = 0L
            var g = 0L
            var stage = 0
            var lastOrder = Long.MAX_VALUE
            var lastScale = 0L
            var j = i
            var any = false
            var oneOnly = false
            var ordinalEnded = false
            while (j < toks.size) {
                val t = toks[j]
                if (!t.word || !t.cyr) break
                if (j > i && !joinable(j - 1, j)) break
                var w = RU[t.n]
                var ord = false
                if (w == null && ordEnd) {
                    val ov = RU_ORD[t.n]
                    if (ov != null) {
                        ord = true
                        w = when {
                            ov >= 100 -> W(K.HUND, ov)
                            ov >= 20 -> W(K.TENS, ov)
                            ov >= 10 -> W(K.TEEN, ov)
                            else -> W(K.UNIT, ov)
                        }
                    }
                }
                if (w == null) break
                var ok = true
                when (w.kind) {
                    K.HUND -> if (stage != 0) ok = false else { g += w.value; stage = 1 }
                    K.TENS -> if (stage >= 2) ok = false else { g += w.value; stage = 2 }
                    K.TEEN -> if (stage >= 2) ok = false else { g += w.value; stage = 3 }
                    K.UNIT -> when {
                        stage >= 3 -> ok = false
                        stage == 2 && w.value == 0L -> ok = false
                        w.value == 0L && (g != 0L || total != 0L) -> ok = false
                        else -> { g += w.value; stage = 3 }
                    }
                    K.SCALE -> {
                        var coef = g
                        if (coef == 0L) {
                            // a bare "тысяча" only starts a number that goes on ("тысяча двести")
                            val next = toks.getOrNull(j + 1)
                            val nw = if (next != null && next.cyr && joinable(j, j + 1)) RU[next.n] else null
                            if (j == i && nw != null && nw.kind != K.SCALE) coef = 1 else ok = false
                        }
                        if (ok && w.value >= lastOrder) ok = false
                        if (ok) { total += coef * w.value; g = 0; stage = 0; lastOrder = w.value; lastScale = w.value }
                    }
                }
                if (!ok) break
                any = true
                if (j == i && w.one && !ord) oneOnly = true
                j++
                if (ord) { ordinalEnded = true; break }
            }
            if (!any) return null
            val value = BigDecimal(total + g)
            val single = j == i + 1 && oneOnly
            val num = Num(value, j, true, single, if (g == 0L && total > 0) lastScale else 0L, null)
            // an ordinal is only used by the callers that ask for it
            return if (ordinalEnded) OrdNum(num) else num
        }

        /** marks a number that ended with an ordinal word (date, year, floor) */
        fun OrdNum(n: Num): Num = Num(n.value, n.end, n.ru, n.one, -1L, null)
        fun isOrd(n: Num) = n.lastScale == -1L

        fun enCardinal(i: Int): Num? {
            var j = i
            var total = 0L
            var g = 0L
            var stage = 0
            var lastOrder = Long.MAX_VALUE
            var lastScale = 0L
            var any = false
            var oneOnly = false
            // "a hundred", "a thousand"
            val t0 = toks[i]
            if ((t0.n == "a" || t0.n == "an") && j + 1 < toks.size && joinable(j, j + 1)) {
                val w = EN[toks[j + 1].n]
                if (toks[j + 1].n == "hundred" || (w != null && w.kind == K.SCALE)) { g = 1; stage = 3; j++; any = true }
            }
            while (j < toks.size) {
                val t = toks[j]
                if (!t.word || t.cyr) break
                if (j > i && !joinable(j - 1, j)) break
                if (t.n == "hundred") {
                    if (!(stage == 2 || stage == 3) || g == 0L || g >= 100) break
                    g *= 100; stage = 1; any = true; j++; continue
                }
                if (t.n == "and") {
                    val next = toks.getOrNull(j + 1)
                    val nw = if (next != null && !next.cyr && joinable(j, j + 1)) EN[next.n] else null
                    val ok = nw != null && nw.kind != K.SCALE && ((stage == 1) || (stage == 0 && total > 0 && g == 0L))
                    if (!ok) break
                    j++; continue
                }
                val w = EN[t.n] ?: break
                var ok = true
                when (w.kind) {
                    K.HUND -> ok = false
                    K.TENS -> if (stage == 2 || stage == 3) ok = false else { g += w.value; stage = 2 }
                    K.TEEN -> if (stage == 2 || stage == 3) ok = false else { g += w.value; stage = 3 }
                    K.UNIT -> when {
                        stage == 3 -> ok = false
                        w.value == 0L && (g != 0L || total != 0L) -> ok = false
                        else -> { g += w.value; stage = 3 }
                    }
                    K.SCALE -> {
                        if (g == 0L || w.value >= lastOrder) ok = false
                        else { total += g * w.value; g = 0; stage = 0; lastOrder = w.value; lastScale = w.value }
                    }
                }
                if (!ok) break
                if (j == i && w.one) oneOnly = true
                any = true
                j++
            }
            if (!any) return null
            // "twenty twenty-six" → 2026 (two numbers of two digits)
            val value = BigDecimal(total + g)
            val single = j == i + 1 && oneOnly
            return Num(value, j, false, single, if (g == 0L && total > 0) lastScale else 0L, null)
        }

        // ------------------------------------------------------------ numbers with a fraction part

        /** a cardinal with what may follow it: decimals, "с половиной", "and a half", a simple fraction */
        fun number(i: Int): Num? {
            var n = cardinal(i) ?: return null
            if (isOrd(n)) return null
            val t = toks.getOrNull(i) ?: return null
            // "двадцать первый вопрос": an ordinal goes on, it is no cardinal
            if (t.word && t.cyr && toks.getOrNull(n.end)?.let { joinable(n.end - 1, n.end) && it.n in RU_ORD && !(n.end == i + 1 && it.n in RU_DENOM) } == true) return null
            if (t.word && t.cyr) n = ruAfter(n, i) else if (t.word) n = enAfter(n, i)
            return n
        }

        fun ruAfter(n: Num, i: Int): Num {
            var j = n.end
            val first = toks.getOrNull(j)?.takeIf { joinable(j - 1, j) && it.cyr } ?: return n
            if (n.fraction != null || n.value.scale() > 0 || n.raw != null) return n
            // три целых пять десятых; три целых и пять сотых
            if (first.n in RU_WHOLE) {
                var k = j + 1
                if (toks.getOrNull(k)?.n == "и" && joinable(k - 1, k)) k++
                val f = cardinal(k)
                if (f != null && !isOrd(f) && f.value.scale() == 0 && k < toks.size && joinable(k - 1, k)) {
                    val d = toks.getOrNull(f.end)?.takeIf { joinable(f.end - 1, f.end) }
                    val places = d?.let { RU_PLACES[it.n] }
                    if (places != null && f.value.toString().length <= places) {
                        val frac = f.value.toString().padStart(places, '0')
                        return Num(BigDecimal(n.value.toPlainString() + "." + frac), f.end + 1, true, false, 0, null)
                    }
                }
                return n
            }
            // три запятая пять, три запятая ноль пять
            if (first.n == "запятая") {
                var k = j + 1
                val sb = StringBuilder()
                while (k < toks.size && joinable(k - 1, k) && RU[toks[k].n]?.let { it.kind == K.UNIT && it.value == 0L } == true) { sb.append('0'); k++ }
                val f = if (k < toks.size && joinable(k - 1, k)) cardinal(k) else null
                if (f != null && !isOrd(f) && f.value.scale() == 0) { sb.append(f.value.toPlainString()); k = f.end }
                if (sb.isNotEmpty()) return Num(BigDecimal(n.value.toPlainString() + "." + sb), k, true, false, 0, null)
                return n
            }
            // два с половиной
            if (first.n == "с" && toks.getOrNull(j + 1)?.n == "половиной" && joinable(j, j + 1)) {
                var v = n.value.add(BigDecimal("0.5"))
                var end = j + 2
                var scale = 0L
                val w = toks.getOrNull(end)?.takeIf { joinable(end - 1, end) && it.cyr }?.let { RU[it.n] }
                if (w != null && w.kind == K.SCALE && n.lastScale == 0L) { v = v.multiply(BigDecimal(w.value)); scale = w.value; end++ }
                return Num(v, end, true, false, scale, null)
            }
            // одна вторая, две третьих, три четверти
            val denom = RU_DENOM[first.n]
            if (denom != null && n.lastScale == 0L && n.value.scale() == 0 && n.end == i + 1) {
                val fem = first.n.endsWith("ая") || first.n.endsWith("ых") || first.n.endsWith("их") || first.n.endsWith("ьи") || first.n.endsWith("ь") || first.n.endsWith("ти")
                if (fem) return Num(n.value, j + 1, true, false, 0, null, fraction = n.value.toPlainString() + "/" + denom)
            }
            return n
        }

        fun enAfter(n: Num, i: Int): Num {
            var j = n.end
            val first = toks.getOrNull(j)?.takeIf { joinable(j - 1, j) && !it.cyr } ?: return n
            if (n.fraction != null || n.value.scale() > 0 || n.raw != null) return n
            // three point five; three point zero five
            if (first.n == "point") {
                var k = j + 1
                val sb = StringBuilder()
                while (k < toks.size && joinable(k - 1, k) && !toks[k].cyr) {
                    val w = EN[toks[k].n] ?: break
                    if (w.kind == K.UNIT) { sb.append(w.value); k++ }
                    else if (w.kind == K.TEEN || w.kind == K.TENS) { sb.append(w.value); k++; break }
                    else break
                }
                if (sb.isNotEmpty()) return Num(BigDecimal(n.value.toPlainString() + "." + sb), k, false, false, 0, null)
                return n
            }
            // one and a half (million)
            if (first.n == "and" && toks.getOrNull(j + 1)?.n.let { it == "a" } && toks.getOrNull(j + 2)?.n == "half" &&
                joinable(j, j + 1) && joinable(j + 1, j + 2)) {
                var v = n.value.add(BigDecimal("0.5"))
                var end = j + 3
                var scale = 0L
                val w = toks.getOrNull(end)?.takeIf { joinable(end - 1, end) && !it.cyr }?.let { EN[it.n] }
                if (w != null && w.kind == K.SCALE && n.lastScale == 0L) { v = v.multiply(BigDecimal(w.value)); scale = w.value; end++ }
                return Num(v, end, false, false, scale, null)
            }
            // one half, two thirds, three quarters
            val denom = EN_DENOM[first.n]
            if (denom != null && n.lastScale == 0L && n.value.scale() == 0 && n.end == i + 1) {
                val num = n.value.toPlainString()
                val plural = first.n.endsWith("s") || n.value == BigDecimal.ONE
                if (plural) return Num(n.value, j + 1, false, false, 0, null, fraction = "$num/$denom")
            }
            return n
        }

        // ------------------------------------------------------------ formatting

        fun group(intPart: String, ru: Boolean, minDigits: Int): String {
            if (intPart.length < minDigits) return intPart
            val sep = if (ru) " " else ","
            return intPart.reversed().chunked(3).joinToString(sep).reversed()
        }

        /** the number as text: Russian "120 000", "3,5", "1,5 млн"; English "120,000", "3.5", "1.5 million" */
        fun fmt(n: Num, ru: Boolean): String {
            n.fraction?.let { return it }
            if (n.raw != null) return if (ru) n.raw.replace('.', ',') else n.raw.replace(',', '.')
            var v = n.value
            var suffix = ""
            if (n.lastScale >= 1_000_000L) {
                val coef = v.divide(BigDecimal(n.lastScale))
                if (coef.compareTo(BigDecimal(1000)) < 0) {
                    v = coef
                    suffix = if (ru) (if (n.lastScale >= 1_000_000_000L) " млрд" else " млн") else (if (n.lastScale >= 1_000_000_000L) " billion" else " million")
                }
            }
            // an integer value is written without places; the places that were spoken stay ("0,50")
            val s = if (v.stripTrailingZeros().scale() <= 0) v.toBigInteger().toString() else v.toPlainString()
            val parts = s.split('.')
            val grouped = group(parts[0], ru, if (ru) 5 else 4)
            val dec = if (parts.size > 1) (if (ru) "," else ".") + parts[1] else ""
            return grouped + dec + suffix
        }

        fun money(n: Num, minor: Int?, cur: Cur): String {
            val ru = cur.ru
            val whole = if (n.raw != null) n.raw else fmt(n, ru)
            if (minor == null) return if (ru) whole + cur.sign else cur.sign + whole
            val mm = minor.toString().padStart(2, '0')
            val body = whole.substringBefore(if (ru) ',' else '.') + (if (ru) "," else ".") + mm
            return if (ru) body + cur.sign else cur.sign + body
        }

        // ------------------------------------------------------------ matching

        fun afterOk(idx: Int): Boolean {
            if (idx >= toks.size) return true
            if (punctBetween(idx - 1, idx)) return true
            return toks[idx].n in FOLLOW_OK
        }

        /** a minor part (1..99) at [k]: the number, the end (after a minor word if there is one) */
        fun minorAt(k: Int): Pair<Int, Int>? {
            if (k >= toks.size || !joinable(k - 1, k)) return null
            val m = cardinal(k) ?: return null
            if (isOrd(m) || m.raw != null || m.value.scale() != 0 || m.lastScale != 0L) return null
            val v = m.value.toInt()
            if (v !in 1..99) return null
            // not a longer number ("... пятьдесят тысяч"): the cardinal() would have taken the scale word
            val mw = toks.getOrNull(m.end)?.takeIf { joinable(m.end - 1, m.end) && it.n in MINOR }
            if (mw != null) return v to (m.end + 1)
            return if (afterOk(m.end)) v to m.end else null
        }

        fun matchAt(i: Int): Match? {
            dateOrYear(i)?.let { return it }
            val t = toks[i]
            // "a dollar eighty-three", "dollar eighty three", "доллар восемьдесят три", "рубль двадцать"
            currencyFirst(i)?.let { return it }
            val n = number(i) ?: return null
            val ru = if (t.word) t.cyr else (toks.getOrNull(n.end)?.cyr ?: false)
            var end = n.end
            val next = toks.getOrNull(end)?.takeIf { joinable(end - 1, end) }
            if (next != null) {
                val nn = next.n
                if (nn in RU_PERCENT || nn in EN_PERCENT) return Match(fmt(n, ru) + "%", end + 1)
                val cur = CUR[nn]
                if (cur != null && n.fraction == null) {
                    val minor = if (n.value.scale() == 0 && n.raw == null) minorAt(end + 1) else null
                    return if (minor != null) Match(money(n, minor.first, cur), minor.second)
                    else Match(money(n, null, cur), end + 1)
                }
            }
            // a plain digit token with nothing to convert, a lone "один"/"one": left as it is
            if (!toks[i].word && n.raw != null && n.end == i + 1) return null
            if (n.one && n.fraction == null) return null
            if (!toks[i].word && n.raw != null && n.lastScale == 0L) return null
            return Match(fmt(n, ru), end)
        }

        fun currencyFirst(i: Int): Match? {
            var k = i
            val t = toks[i]
            if (!t.word) return null
            if (!t.cyr && (t.n == "a" || t.n == "an") && i + 1 < toks.size && joinable(i, i + 1)) k = i + 1
            val cur = CUR[toks[k].n] ?: return null
            if (!cur.bareOk) return null
            if (cur.ru != toks[k].cyr) return null
            // the sign "a dollar" only with a minor part after it
            val m = minorAt(k + 1) ?: return null
            if (m.first !in 1..99) return null
            val one = Num(BigDecimal.ONE, k + 1, cur.ru, false, 0, null)
            return Match(money(one, m.first, cur), m.second)
        }

        // dates, years, floors (Russian ordinals)

        fun dateOrYear(i: Int): Match? {
            val t = toks[i]
            if (!t.word || !t.cyr) return null
            val ord = cardinal(i, ordEnd = true) ?: return null
            if (!isOrd(ord)) return null
            val v = ord.value.toLong()
            val e = ord.end
            val last = toks[e - 1]
            // 17 октября, 17 октября 2026 года
            val month = toks.getOrNull(e)?.takeIf { joinable(e - 1, e) && it.n in MONTHS }
            if (month != null && v in 1..31) {
                var out = "$v ${month.s}"
                var end = e + 1
                val y = yearAt(end)
                if (y != null) { out += " " + y.out; end = y.end }
                return Match(out, end)
            }
            // 2026 года
            val yw = toks.getOrNull(e)?.takeIf { joinable(e - 1, e) && it.n in YEAR_WORDS }
            if (yw != null && v in 1000..2999) return Match("$v ${yw.s}", e + 1)
            // 1-й этаж
            val noun = toks.getOrNull(e)?.takeIf { joinable(e - 1, e) && it.n in ORD_NOUNS }
            if (noun != null && v in 1..99) return Match("$v-${ordEnding(last.n)} ${noun.s}", e + 1)
            return null
        }

        fun yearAt(k: Int): Match? {
            if (k >= toks.size || !joinable(k - 1, k) || !toks[k].cyr) return null
            val y = cardinal(k, ordEnd = true) ?: return null
            if (!isOrd(y)) return null
            val v = y.value.toLong()
            val yw = toks.getOrNull(y.end)?.takeIf { joinable(y.end - 1, y.end) && it.n in YEAR_WORDS } ?: return null
            if (v !in 1000..2999) return null
            return Match("$v ${yw.s}", y.end + 1)
        }

        fun ordEnding(word: String): String {
            val endings = listOf("ого" to "го", "его" to "го", "ому" to "му", "ему" to "му", "ыми" to "ми", "ими" to "ми", "ый" to "й", "ой" to "й",
                "ий" to "й", "ая" to "я", "ья" to "я", "ое" to "е", "ье" to "е", "ую" to "ю", "ью" to "ю", "ым" to "м", "им" to "м", "ом" to "м",
                "ем" to "м", "ых" to "х", "их" to "х", "ые" to "е", "ьи" to "е", "ей" to "й")
            return endings.firstOrNull { word.endsWith(it.first) }?.second ?: "й"
        }
    }

    // ---------------------------------------------------------------- English years

    /** "twenty twenty-six" → "2026", "nineteen ninety-nine" → "1999" (two numbers of two digits, the first 19 or 20) */
    fun years(text: String): Result {
        var count = 0
        val re = Regex("\\b(nineteen|twenty)[ -](?:(ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)|" +
            "((?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?))\\b", RegexOption.IGNORE_CASE)
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        val units = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val out = re.replace(text) { m ->
            val head = if (m.groupValues[1].lowercase() == "nineteen") 19 else 20
            val low = m.groupValues[2].lowercase().takeIf { it.isNotEmpty() }?.let { 10 + teens.indexOf(it) }
                ?: m.groupValues[3].lowercase().split(' ', '-').let { p -> (tens[p[0]] ?: 0) + (if (p.size > 1) units.indexOf(p[1]) + 1 else 0) }
            count++
            (head * 100 + low).toString()
        }
        return Result(out, count)
    }

    /** the whole conversion of a dictated text: years of English speech first, then the rest */
    fun apply(text: String): Result {
        val y = years(text)
        val r = convert(y.text)
        return Result(r.text, r.converted + y.converted)
    }
}
