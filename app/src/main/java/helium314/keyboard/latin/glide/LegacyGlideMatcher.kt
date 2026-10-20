// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

/*
 * swipe-3 (owner's rule 2026-10-19: remarks about Gleamvox change only the play build): the matcher of swipe-1/swipe-2,
 * unchanged, used by the personal build ("Open glide engine" to compare); the play build uses the faster GlideMatcher.
 */

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * swipe-1: own open implementation of glide typing (no closed library). The idea is the well-known "shape writing"
 * one (Zhai & Kristensson, SHARK²): the path of the finger is compared with the ideal path of every candidate word
 * (a line through the centres of its letter keys); the best fitting words, weighted by their frequency in the
 * dictionary, are the suggestions. Written from scratch for this project (GPL-3.0), no code of other keyboards.
 * Pure Kotlin without Android classes, so it is tested on the JVM (GlideMatcherTest).
 */

/** centres of the letter keys (key code -> x, y in the coordinates of the touch points) and the size of a key */
class LegacyGlideKeys(val centers: Map<Int, FloatArray>, val keyWidth: Float, val keyHeight: Float)

/** words of one dictionary: text and probability (0..255); [byFirst] lists word numbers by their first letter (folded) */
class LegacyGlideIndex(val words: Array<String>, val probabilities: ByteArray) {
    private val byFirst: Map<Int, IntArray>
    init {
        val lists = HashMap<Int, ArrayList<Int>>()
        for (i in words.indices) {
            val first = LegacyGlideMatcher.fold(words[i]).firstOrNull() ?: continue
            lists.getOrPut(first) { ArrayList() }.add(i)
        }
        byFirst = lists.mapValues { it.value.toIntArray() }
    }
    fun withFirstLetter(code: Int): IntArray = byFirst[code] ?: IntArray(0)
    fun probability(i: Int) = probabilities[i].toInt() and 0xFF
    val size get() = words.size
}

class LegacyGlideResult(val word: String, val cost: Double, val score: Int)

object LegacyGlideMatcher {
    private const val SAMPLES = 40
    /** a letter is in the path only when the finger passed at most this many key widths from its centre */
    private const val PASS_DISTANCE = 0.75
    private const val START_RADIUS = 1.5
    private const val END_RADIUS = 1.9
    private const val FREQUENCY_WEIGHT = 0.22
    private const val COVERAGE_WEIGHT = 0.45
    private val LOOK_ALIKE = mapOf('ъ'.code to 'ь'.code)

    /** letters of a word as key codes: lower case, without accents, "ё" is "е"; anything but letters (apostrophe, hyphen) is dropped */
    fun fold(word: String): IntArray {
        val out = ArrayList<Int>(word.length)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val lower = Character.toLowerCase(cp)
            val base = when (lower) {
                'ё'.code -> 'е'.code
                'й'.code -> 'й'.code // a key of its own, the decomposition would turn it into "и"
                else -> Normalizer.normalize(String(Character.toChars(lower)), Normalizer.Form.NFD).codePointAt(0)
            }
            if (out.isEmpty() || out.last() != base) out.add(base) // a double letter is one point of the path
        }
        return out.toIntArray()
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx).toDouble(), (ay - by).toDouble())

    /** [n] points at equal distances along the line through [px], [py] (at least two different points) */
    private fun resample(px: FloatArray, py: FloatArray, n: Int, size: Int = px.size): Pair<FloatArray, FloatArray> {
        val total = DoubleArray(size)
        for (i in 1 until size) total[i] = total[i - 1] + dist(px[i - 1], py[i - 1], px[i], py[i])
        val length = total[size - 1]
        val rx = FloatArray(n); val ry = FloatArray(n)
        if (length <= 0.0) { rx.fill(px[0]); ry.fill(py[0]); return rx to ry }
        var seg = 1
        for (k in 0 until n) {
            val at = length * k / (n - 1)
            while (seg < size - 1 && total[seg] < at) seg++
            val span = total[seg] - total[seg - 1]
            val t = if (span <= 0.0) 0.0 else ((at - total[seg - 1]) / span).coerceIn(0.0, 1.0)
            rx[k] = (px[seg - 1] + (px[seg] - px[seg - 1]) * t).toFloat()
            ry[k] = (py[seg - 1] + (py[seg] - py[seg - 1]) * t).toFloat()
        }
        return rx to ry
    }

    private fun pathLength(px: FloatArray, py: FloatArray, size: Int = px.size): Double {
        var s = 0.0
        for (i in 1 until size) s += dist(px[i - 1], py[i - 1], px[i], py[i])
        return s
    }

    /**
     * The best [maxResults] words for the finger path ([xs], [ys], [count] points), best first. Costs are in key widths:
     * the smaller the better. The score is a positive whole number for the suggestion list (the bigger the better).
     */
    fun match(xs: IntArray, ys: IntArray, count: Int, keys: LegacyGlideKeys, index: LegacyGlideIndex, maxResults: Int = 12): List<LegacyGlideResult> {
        if (count < 2) return emptyList()
        // the path without points standing still
        val gx = ArrayList<Float>(); val gy = ArrayList<Float>()
        for (i in 0 until count) {
            if (gx.isNotEmpty() && dist(gx.last(), gy.last(), xs[i].toFloat(), ys[i].toFloat()) < 1.0) continue
            gx.add(xs[i].toFloat()); gy.add(ys[i].toFloat())
        }
        if (gx.size < 2) return emptyList()
        val rawX = gx.toFloatArray(); val rawY = gy.toFloatArray()
        val kw = keys.keyWidth.toDouble()
        val gestureLength = pathLength(rawX, rawY)
        if (gestureLength < kw * 0.8) return emptyList() // a tap, not a glide
        val (sx, sy) = resample(rawX, rawY, SAMPLES)
        val startX = rawX.first(); val startY = rawY.first(); val endX = rawX.last(); val endY = rawY.last()

        // the first letter: the keys near the start of the path
        val firstCodes = keys.centers.filter { dist(it.value[0], it.value[1], startX, startY) <= START_RADIUS * kw }.keys
        val best = ArrayList<LegacyGlideResult>()
        var worstKept = Double.MAX_VALUE
        val tx = FloatArray(64); val ty = FloatArray(64)
        for (first in firstCodes) {
            for (wi in index.withFirstLetter(first)) {
                val letters = fold(index.words[wi])
                if (letters.size < 2) continue // one letter words are typed by a tap
                // the centres of the keys; a word with a letter that has no key is not for this keyboard
                var ok = letters.size <= 62
                if (ok) for (i in letters.indices) {
                    // swipe-2: a letter without a key of its own is on the key of its look-alike (Russian "ъ" on "ь")
                    val c = keys.centers[letters[i]] ?: LOOK_ALIKE[letters[i]]?.let { keys.centers[it] }
                    if (c == null) { ok = false; break }
                    tx[i] = c[0]; ty[i] = c[1]
                }
                if (!ok) continue
                val last = letters.size - 1
                if (dist(tx[last], ty[last], endX, endY) > END_RADIUS * kw) continue
                val ideal = pathLength(tx, ty, letters.size)
                // a very different length cannot be the word 
                if (letters.size > 1 && (gestureLength > ideal * 2.2 + kw || gestureLength < ideal * 0.45 - kw)) continue
                val frequencyBonus = FREQUENCY_WEIGHT * ln(1.0 + index.probability(wi)) / ln(256.0)
                // cheap lower bound first: the start and the end alone must already be good enough
                val ends = (dist(tx[0], ty[0], startX, startY) + dist(tx[last], ty[last], endX, endY)) / (kw * SAMPLES)
                if (ends - frequencyBonus > worstKept) continue
                val (ix, iy) = resample(tx, ty, SAMPLES, letters.size)
                var sum = 0.0
                for (k in 0 until SAMPLES) sum += dist(sx[k], sy[k], ix[k], iy[k])
                val shape = sum / SAMPLES / kw
                // every letter must have been passed: the shortest distance of the finger path to its key
                var missed = 0.0
                for (i in letters.indices) {
                    var m = Double.MAX_VALUE
                    for (k in 0 until SAMPLES) { val d = dist(sx[k], sy[k], tx[i], ty[i]); if (d < m) m = d }
                    val over = m / kw - PASS_DISTANCE
                    if (over > 0) missed += over
                }
                val cost = shape + COVERAGE_WEIGHT * missed - frequencyBonus
                if (best.size >= maxResults && cost >= worstKept) continue
                best.add(LegacyGlideResult(index.words[wi], cost, 0))
                if (best.size > maxResults * 2) { best.sortBy { it.cost }; while (best.size > maxResults) best.removeAt(best.size - 1) }
                if (best.size >= maxResults) worstKept = best.sortedBy { it.cost }[maxResults - 1].cost
            }
        }
        best.sortBy { it.cost }
        return best.take(maxResults).map { LegacyGlideResult(it.word, it.cost, max(1, (1_000_000 * exp(-1.2 * max(0.0, it.cost + 1.0))).toInt())) }
    }
}
