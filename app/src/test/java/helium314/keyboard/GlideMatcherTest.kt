// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.glide.GlideIndex
import helium314.keyboard.latin.glide.GlideKeys
import helium314.keyboard.latin.glide.GlideMatcher
import org.junit.Assume
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * swipe-1: the open glide typing (GlideMatcher). A finger path is simulated along the keys of a word (with a shaky hand:
 * the centres are missed by up to a quarter of a key, the corners are cut) and the matcher must find the word.
 * The big test uses the real word lists of the keyboard dictionaries if they are on this machine (tools folder), else it is skipped.
 */
class GlideMatcherTest {
    private fun layout(rows: List<String>, keyW: Float, keyH: Float, offsets: List<Float>): GlideKeys {
        val c = HashMap<Int, FloatArray>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, ch -> c[ch.code] = floatArrayOf(offsets[r] + keyW * (i + 0.5f), keyH * (r + 0.5f)) } }
        return GlideKeys(c, keyW, keyH)
    }
    private val qwerty = layout(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), 108f, 150f, listOf(0f, 54f, 162f))
    private val russian = layout(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), 98f, 150f, listOf(0f, 0f, 98f))

    private class Path(val xs: IntArray, val ys: IntArray)

    /** a hand drawn path through the letters of [word]: wobble [shake] key widths, corners cut, points about every 10 px */
    private fun simulate(word: String, keys: GlideKeys, rnd: Random, shake: Double = 0.22): Path? {
        val letters = GlideMatcher.fold(word)
        val way = ArrayList<FloatArray>()
        for ((i, l) in letters.withIndex()) {
            val c = keys.centers[l] ?: return null
            val j = if (i == 0) 0.4 else 1.0 // the finger goes down on the first letter quite exactly
            way.add(floatArrayOf((c[0] + gauss(rnd) * shake * keys.keyWidth * j).toFloat(), (c[1] + gauss(rnd) * shake * keys.keyHeight * j).toFloat()))
        }
        if (way.size < 2) return null
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (i in 0 until way.size - 1) {
            // Catmull-Rom through the points: a smooth curve, corners are cut
            val p0 = way[maxOf(0, i - 1)]; val p1 = way[i]; val p2 = way[i + 1]; val p3 = way[minOf(way.size - 1, i + 2)]
            val len = hypot((p2[0] - p1[0]).toDouble(), (p2[1] - p1[1]).toDouble())
            val steps = maxOf(2, (len / 10).toInt())
            for (s in 0 until steps) {
                val t = s / steps.toDouble()
                fun cr(a: Float, b: Float, c: Float, d: Float) = 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t)
                xs.add(cr(p0[0], p1[0], p2[0], p3[0]).toInt()); ys.add(cr(p0[1], p1[1], p2[1], p3[1]).toInt())
            }
        }
        xs.add(way.last()[0].toInt()); ys.add(way.last()[1].toInt())
        return Path(xs.toIntArray(), ys.toIntArray())
    }

    private fun gauss(rnd: Random) = sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())

    private fun small(vararg words: String) = GlideIndex(arrayOf(*words), ByteArray(words.size) { 100 })

    @Test fun foldsLettersLikeKeys() {
        assertEquals(listOf('h'.code, 'e'.code, 'l'.code, 'o'.code), GlideMatcher.fold("Hello").toList(), "a double letter is one point")
        assertEquals(listOf('c'.code, 'a'.code, 'f'.code, 'e'.code), GlideMatcher.fold("café").toList())
        assertEquals(listOf('е'.code, 'л'.code, 'к'.code, 'а'.code), GlideMatcher.fold("ёлка").toList().let { listOf('е'.code) + it.drop(1) })
        assertEquals(listOf('d'.code, 'o'.code, 'n'.code, 't'.code), GlideMatcher.fold("don't").toList(), "the apostrophe is not a key")
        assertEquals('й'.code, GlideMatcher.fold("йод")[0], "й is a key of its own")
    }

    @Test fun findsTheWordAmongNeighbours() {
        val index = small("hello", "help", "hell", "jello", "hollow", "halo", "held", "yellow", "he", "hot")
        val rnd = Random(1)
        for (w in listOf("hello", "help", "yellow", "hollow", "halo")) {
            val p = simulate(w, qwerty, rnd, 0.12)!!
            val r = GlideMatcher.match(p.xs, p.ys, p.xs.size, qwerty, index)
            assertEquals(w, r.firstOrNull()?.word, "$w -> ${r.map { it.word }}")
        }
    }

    @Test fun aTapIsNotAGlide() {
        assertTrue(GlideMatcher.match(intArrayOf(50, 51, 50), intArrayOf(70, 70, 71), 3, qwerty, small("hello", "he")).isEmpty())
    }

    @Test fun scoresArePositiveAndSorted() {
        val p = simulate("hello", qwerty, Random(2), 0.1)!!
        val r = GlideMatcher.match(p.xs, p.ys, p.xs.size, qwerty, small("hello", "hell", "help", "jello"))
        assertTrue(r.isNotEmpty() && r.all { it.score > 0 }, r.toString())
        assertEquals(r.sortedByDescending { it.score }.map { it.word }, r.map { it.word })
    }

    /** real vocabulary: how often is the wanted word the first / among the first three suggestions */
    private fun benchmark(file: String, keys: GlideKeys, name: String, minTop1: Double, minTop3: Double) {
        val f = File(System.getProperty("user.home") + "/.local/ai-keyboard-tools/models/wordlists/" + file)
        Assume.assumeTrue("no word list $f", f.isFile)
        val entries = f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
            .filter { it.first.length in 2..24 && it.first.all { c -> c.isLetter() || c == '\'' || c == '-' } && it.second > 0 }
            .distinctBy { it.first.lowercase() }
        val index = GlideIndex(entries.map { it.first }.toTypedArray(), ByteArray(entries.size) { entries[it].second.coerceIn(0, 255).toByte() })
        // the wanted words: the common ones (probability >= 150), at least 3 letters
        val wanted = entries.filter { it.second >= 150 && GlideMatcher.fold(it.first).size >= 3 && it.first.all { c -> c.isLetter() } && it.first == it.first.lowercase() }
        val rnd = Random(7)
        val sample = wanted.shuffled(rnd).take(500)
        var top1 = 0; var top3 = 0; var n = 0; var ms = 0L
        for ((w, _) in sample) {
            val p = simulate(w, keys, rnd) ?: continue
            val t = System.nanoTime()
            val r = GlideMatcher.match(p.xs, p.ys, p.xs.size, keys, index).map { it.word.lowercase() }
            ms += (System.nanoTime() - t) / 1_000_000
            n++
            if (r.firstOrNull() == w) top1++
            if (w in r.take(3)) top3++
        }
        println("GLIDE $name: ${entries.size} words in the index, $n glides: first ${100 * top1 / n}%, in the first three ${100 * top3 / n}%, ${ms / n} ms per glide")
        assertTrue(top1.toDouble() / n >= minTop1, "$name first $top1/$n")
        assertTrue(top3.toDouble() / n >= minTop3, "$name first three $top3/$n")
    }

    @Test fun englishVocabulary() = benchmark("en_old.tsv", qwerty, "en", 0.5, 0.75)
    @Test fun russianVocabulary() = benchmark("ru_old.tsv", russian, "ru", 0.5, 0.75)
}
