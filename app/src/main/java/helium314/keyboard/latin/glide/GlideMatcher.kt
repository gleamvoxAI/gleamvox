// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * swipe-1: own open implementation of glide typing (no closed library). The idea is the well-known "shape writing"
 * one (Zhai & Kristensson, SHARK²): the path of the finger is compared with the ideal path of every candidate word
 * (a line through the centres of its letter keys); the best fitting words, weighted by their frequency in the
 * dictionary, are the suggestions. Written from scratch for this project (GPL-3.0), no code of other keyboards.
 * Pure Kotlin without Android classes, so it is tested on the JVM (GlideMatcherTest).
 *
 * swipe-3 (the owner on 4439: "a wild delay"): the letters of every word are prepared once when the dictionary is read;
 * the keys are looked up in tables; how close the finger came to every key is computed once per glide; the cheap
 * checks (first and last key, length of the path, keys missed) come before the exact comparison; nothing is allocated
 * per word.
 */

/** centres of the letter keys (key code -> x, y in the coordinates of the touch points) and the size of a key */
class GlideKeys(val centers: Map<Int, FloatArray>, val keyWidth: Float, val keyHeight: Float) {
    /** the keys in a fixed order: their centres, and the key of a letter (the folded letter -> number + 1, 0: no key) */
    internal val count = centers.size
    internal val cx = FloatArray(count)
    internal val cy = FloatArray(count)
    internal val keyOf = IntArray(65536)
    /** distance between two keys */
    internal val between: Array<FloatArray>
    init {
        var k = 0
        for ((code, c) in centers) {
            cx[k] = c[0]; cy[k] = c[1]
            if (code in 0..0xFFFF) keyOf[code] = k + 1
            k++
        }
        // a letter without a key of its own is on the key of its look-alike (Russian "ъ" on "ь")
        for ((from, to) in GlideMatcher.LOOK_ALIKE) if (keyOf[from] == 0 && keyOf[to] != 0) keyOf[from] = keyOf[to]
        between = Array(count) { a -> FloatArray(count) { b -> dist(cx[a], cy[a], cx[b], cy[b]) } }
    }
    /** the letters on each key (its own and look-alikes): the first letters of words to look at */
    internal val lettersOf: Array<IntArray> = run {
        val n = IntArray(count)
        for (c in 0 until 65536) if (keyOf[c] > 0) n[keyOf[c] - 1]++
        val out = Array(count) { IntArray(n[it]) }
        val f = IntArray(count)
        for (c in 0 until 65536) if (keyOf[c] > 0) { val k = keyOf[c] - 1; out[k][f[k]++] = c }
        out
    }
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }

/**
 * words of one dictionary: text and probability (0..255). swipe-3: the letters of each word as keys are prepared here
 * once ([letters] from [start] to the next start; lower case, no accents, a double letter once), and the words are
 * listed by their first letter
 */
class GlideIndex(val words: Array<String>, val probabilities: ByteArray) {
    internal val letters: CharArray
    internal val start = IntArray(words.size + 1)
    private val byFirst: Map<Int, IntArray>
    init {
        val sb = StringBuilder(words.size * 7)
        val lists = HashMap<Int, IntArrayBuilder>()
        for (i in words.indices) {
            start[i] = sb.length
            val before = sb.length
            if (!GlideMatcher.foldInto(words[i], sb)) sb.setLength(before) // a letter outside the basic plane: not for glides
            if (sb.length > before) lists.getOrPut(sb[before].code) { IntArrayBuilder() }.add(i)
        }
        start[words.size] = sb.length
        letters = CharArray(sb.length).also { sb.getChars(0, sb.length, it, 0) }
        byFirst = lists.mapValues { it.value.toArray() }
    }
    fun withFirstLetter(code: Int): IntArray = byFirst[code] ?: EMPTY
    fun probability(i: Int) = probabilities[i].toInt() and 0xFF
    val size get() = words.size

    private class IntArrayBuilder {
        var a = IntArray(16); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
    private companion object { val EMPTY = IntArray(0) }
}

class GlideResult(val word: String, val cost: Double, val score: Int)

/**
 * swipe-4: the tuning of the matcher. The defaults were tuned on 4000 real swipes of the FUTO swipe dataset (MIT,
 * swipe.futo.org; English QWERTY) and checked on 10 000 others: first 62% -> 83%, in three 77% -> 91%
 * (tools/aikb/build.sh swipebench). [SWIPE1] are the values of swipe-1..3.
 */
data class GlideParams(
    val samples: Int = 40,
    /** a letter is in the path only when the finger passed at most this many key widths from its centre */
    val passDistance: Float = 0.5f,
    val startRadius: Float = 0.9f,
    val endRadius: Float = 1.6f,
    val frequencyWeight: Double = 6.5,
    val coverageWeight: Double = 0.3,
    val longest: Float = 2.0f,
    val shortest: Float = 0.45f,
) {
    companion object {
        val DEFAULT = GlideParams()
        val SWIPE1 = GlideParams(passDistance = 0.75f, startRadius = 1.5f, endRadius = 1.9f, frequencyWeight = 0.22, coverageWeight = 0.45, longest = 2.2f)
    }
}

object GlideMatcher {
    internal val LOOK_ALIKE = mapOf('ъ'.code to 'ь'.code)
    private const val MAX_LETTERS = 62

    /** letters of a word as key codes: lower case, without accents, "ё" is "е"; anything but letters (apostrophe, hyphen) is dropped */
    fun fold(word: String): IntArray {
        val sb = StringBuilder(word.length)
        foldInto(word, sb)
        return IntArray(sb.length) { sb[it].code }
    }

    /** [fold] into [out]; false if a letter is outside the basic plane (no key has it) */
    internal fun foldInto(word: String, out: StringBuilder): Boolean {
        val from = out.length
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val base = baseOf(Character.toLowerCase(cp))
            if (base > 0xFFFF) return false
            if (out.length == from || out[out.length - 1].code != base) out.append(base.toChar()) // a double letter is one point
        }
        return true
    }

    private fun baseOf(lower: Int): Int = when {
        lower == 'ё'.code -> 'е'.code
        lower == 'й'.code -> lower // a key of its own, the decomposition would turn it into "и"
        lower < 0xC0 -> lower // plain Latin: nothing to decompose (the common case, fast)
        lower in 0x400..0x4FF -> lower // Cyrillic letters are keys of their own
        else -> Normalizer.normalize(String(Character.toChars(lower)), Normalizer.Form.NFD).codePointAt(0)
    }

    /** [n] points at equal distances along the line through the first [size] points of [px], [py] into [rx], [ry] */
    private fun resample(px: FloatArray, py: FloatArray, size: Int, n: Int, rx: FloatArray, ry: FloatArray, total: FloatArray) {
        total[0] = 0f
        for (i in 1 until size) total[i] = total[i - 1] + dist(px[i - 1], py[i - 1], px[i], py[i])
        val length = total[size - 1]
        if (length <= 0f) { rx.fill(px[0], 0, n); ry.fill(py[0], 0, n); return }
        var seg = 1
        for (k in 0 until n) {
            val at = length * k / (n - 1)
            while (seg < size - 1 && total[seg] < at) seg++
            val span = total[seg] - total[seg - 1]
            val t = if (span <= 0f) 0f else ((at - total[seg - 1]) / span).coerceIn(0f, 1f)
            rx[k] = px[seg - 1] + (px[seg] - px[seg - 1]) * t
            ry[k] = py[seg - 1] + (py[seg] - py[seg - 1]) * t
        }
    }

    /**
     * The best [maxResults] words for the finger path ([xs], [ys], [count] points), best first. Costs are in key widths:
     * the smaller the better. The score is a positive whole number for the suggestion list (the bigger the better).
     */
    fun match(xs: IntArray, ys: IntArray, count: Int, keys: GlideKeys, index: GlideIndex, maxResults: Int = 12,
              params: GlideParams = GlideParams.DEFAULT): List<GlideResult> {
        val SAMPLES = params.samples; val PASS_DISTANCE = params.passDistance; val START_RADIUS = params.startRadius
        val END_RADIUS = params.endRadius; val FREQUENCY_WEIGHT = params.frequencyWeight; val COVERAGE_WEIGHT = params.coverageWeight
        if (count < 2 || keys.count == 0) return emptyList()
        // the path without points standing still
        val gx = FloatArray(count); val gy = FloatArray(count)
        var g = 0
        for (i in 0 until count) {
            val x = xs[i].toFloat(); val y = ys[i].toFloat()
            if (g > 0 && dist(gx[g - 1], gy[g - 1], x, y) < 1f) continue
            gx[g] = x; gy[g] = y; g++
        }
        if (g < 2) return emptyList()
        val kw = keys.keyWidth
        val total = FloatArray(maxOf(g, MAX_LETTERS))
        var gestureLength = 0f
        for (i in 1 until g) gestureLength += dist(gx[i - 1], gy[i - 1], gx[i], gy[i])
        if (gestureLength < kw * 0.8f) return emptyList() // a tap, not a glide
        val sx = FloatArray(SAMPLES); val sy = FloatArray(SAMPLES)
        resample(gx, gy, g, SAMPLES, sx, sy, total)
        val startX = gx[0]; val startY = gy[0]; val endX = gx[g - 1]; val endY = gy[g - 1]

        // per key, once per glide: near the start, near the end, how much the finger missed it
        val nk = keys.count
        val nearEnd = BooleanArray(nk) { dist(keys.cx[it], keys.cy[it], endX, endY) <= END_RADIUS * kw }
        val miss = FloatArray(nk) { k ->
            var m = Float.MAX_VALUE
            for (s in 0 until SAMPLES) { val d = dist(sx[s], sy[s], keys.cx[k], keys.cy[k]); if (d < m) m = d }
            max(0f, m / kw - PASS_DISTANCE)
        }
        val toStart = FloatArray(nk) { dist(keys.cx[it], keys.cy[it], startX, startY) }
        val toEnd = FloatArray(nk) { dist(keys.cx[it], keys.cy[it], endX, endY) }

        // the best ones so far, kept sorted (small: insertion)
        val bestCost = DoubleArray(maxResults); val bestWord = IntArray(maxResults); var kept = 0
        val tk = IntArray(MAX_LETTERS)
        val tx = FloatArray(MAX_LETTERS); val ty = FloatArray(MAX_LETTERS)
        val ix = FloatArray(SAMPLES); val iy = FloatArray(SAMPLES)
        val letters = index.letters
        val logMax = ln(256.0)
        for (first in 0 until nk) {
            if (toStart[first] > START_RADIUS * kw) continue
            for (code in keys.lettersOf[first]) {
                for (wi in index.withFirstLetter(code)) {
                    val from = index.start[wi]; val n = index.start[wi + 1] - from
                    if (n < 2 || n > MAX_LETTERS) continue // one letter words are typed by a tap
                    val lastKey = keys.keyOf[letters[from + n - 1].code] - 1
                    if (lastKey < 0 || !nearEnd[lastKey]) continue
                    // the keys of the word; a word with a letter that has no key is not for this keyboard
                    var ok = true
                    var ideal = 0f
                    var missed = 0f
                    for (j in 0 until n) {
                        val k = keys.keyOf[letters[from + j].code] - 1
                        if (k < 0) { ok = false; break }
                        tk[j] = k
                        missed += miss[k]
                        if (j > 0) ideal += keys.between[tk[j - 1]][k]
                    }
                    if (!ok) continue
                    // a very different length cannot be the word
                    if (gestureLength > ideal * params.longest + kw || gestureLength < ideal * params.shortest - kw) continue
                    val bonus = FREQUENCY_WEIGHT * ln(1.0 + index.probability(wi)) / logMax
                    val worst = if (kept == maxResults) bestCost[kept - 1] else Double.MAX_VALUE
                    // what is known already (keys missed, both ends) must leave room for the shape
                    val known = COVERAGE_WEIGHT * missed - bonus + (toStart[tk[0]] + toEnd[tk[n - 1]]) / (kw * SAMPLES)
                    if (known >= worst) continue
                    for (j in 0 until n) { tx[j] = keys.cx[tk[j]]; ty[j] = keys.cy[tk[j]] }
                    resample(tx, ty, n, SAMPLES, ix, iy, total)
                    val limit = (worst - COVERAGE_WEIGHT * missed + bonus) * SAMPLES * kw
                    var sum = 0.0
                    var s = 0
                    while (s < SAMPLES) { sum += dist(sx[s], sy[s], ix[s], iy[s]); if (sum >= limit) break; s++ }
                    if (s < SAMPLES) continue
                    val cost = sum / SAMPLES / kw + COVERAGE_WEIGHT * missed - bonus
                    if (cost >= worst) continue
                    var p = if (kept < maxResults) kept++ else kept - 1
                    while (p > 0 && bestCost[p - 1] > cost) { bestCost[p] = bestCost[p - 1]; bestWord[p] = bestWord[p - 1]; p-- }
                    bestCost[p] = cost; bestWord[p] = wi
                }
            }
        }
        return (0 until kept).map { val c = bestCost[it]; GlideResult(index.words[bestWord[it]], c, max(1, (1_000_000 * exp(-1.2 * max(0.0, c + 1.0))).toInt())) }
    }
}
