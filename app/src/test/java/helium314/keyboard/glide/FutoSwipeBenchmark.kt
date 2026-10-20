// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.glide

import helium314.keyboard.latin.glide.GlideIndex
import helium314.keyboard.latin.glide.GlideKeys
import helium314.keyboard.latin.glide.GlideMatcher
import helium314.keyboard.latin.glide.GlideParams
import org.junit.Assume
import java.io.File
import kotlin.test.Test

/**
 * swipe-4: our open glide typing on REAL swipes: the test split of the FUTO swipe dataset (MIT, swipe.futo.org; English,
 * QWERTY; in ~/.local/ai-keyboard-tools/datasets/futo-swipe, not in the repository). Runs only from
 * tools/aikb/build.sh swipebench. The parameters are tuned on one part (records 0..3999) and checked on another
 * (records 10000..19999), so the numbers of the check are not tuned to themselves.
 */
class FutoSwipeBenchmark {
    private val dir = File(System.getProperty("user.home") + "/.local/ai-keyboard-tools/datasets/futo-swipe")
    private val words = File(System.getProperty("user.home") + "/.local/ai-keyboard-tools/models/wordlists/en_old.tsv")
    private val out = File("build/aikb-swipebench").apply { mkdirs() }

    private class Swipe(val word: String, val w: Float, val h: Float, val xs: FloatArray, val ys: FloatArray)

    private fun num(line: String, key: String): Float? {
        val i = line.indexOf("\"$key\":"); if (i < 0) return null
        var j = i + key.length + 3; val st = j
        while (j < line.length && (line[j].isDigit() || line[j] == '.' || line[j] == '-' || line[j] == 'e' || line[j] == 'E')) j++
        return line.substring(st, j).toFloatOrNull()
    }

    private fun parse(line: String): Swipe? {
        val wi = line.indexOf("\"word\":\""); if (wi < 0) return null
        val word = line.substring(wi + 8, line.indexOf('"', wi + 8))
        val w = num(line, "canvas_width") ?: return null; val h = num(line, "canvas_height") ?: return null
        val d = line.indexOf("\"data\":["); if (d < 0) return null
        val end = line.indexOf(']', d)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var p = d
        while (true) {
            val xi = line.indexOf("\"x\":", p); if (xi < 0 || xi > end) break
            val yi = line.indexOf("\"y\":", xi)
            val x = num(line.substring(xi), "x") ?: break; val y = num(line.substring(yi), "y") ?: break
            xs.add(x); ys.add(y); p = yi + 4
        }
        return Swipe(word, w, h, xs.toFloatArray(), ys.toFloatArray())
    }

    private fun load(from: Int, to: Int): List<Swipe> {
        val r = ArrayList<Swipe>()
        File(dir, "test.jsonl").bufferedReader().useLines { lines ->
            for ((i, l) in lines.withIndex()) { if (i >= to) break; if (i >= from) parse(l)?.let { r.add(it) } }
        }
        return r
    }

    /** the QWERTY of the dataset (centres as parts of the canvas) in pixels of a canvas [w] x [h] */
    private val layout: List<Triple<Char, Float, Float>> by lazy {
        val t = File(dir, "qwerty.json").readText()
        Regex("\"letter\":\\s*\"(.)\",\\s*\"cx\":\\s*([0-9.]+),\\s*\"cy\":\\s*([0-9.]+)").findAll(t)
            .map { Triple(it.groupValues[1][0], it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }.toList()
    }
    private val keysCache = HashMap<Pair<Float, Float>, GlideKeys>()
    private fun keys(w: Float, h: Float) = keysCache.getOrPut(w to h) {
        GlideKeys(layout.associate { (c, x, y) -> c.code to floatArrayOf(x * w, y * h) }, 0.1f * w, h / 3f)
    }

    private class Score(val top1: Int, val top3: Int, val n: Int, val missing: Int, val ms: Double) {
        override fun toString() = "first ${pct(top1)}%, in three ${pct(top3)}% of $n (not in the dictionary: $missing), ${"%.2f".format(ms)} ms per swipe"
        fun pct(k: Int) = if (n == 0) 0 else Math.round(100.0 * k / n)
    }

    private fun run(swipes: List<Swipe>, index: GlideIndex, known: Set<String>, p: GlideParams): Score {
        var t1 = 0; var t3 = 0; var n = 0; var miss = 0; var ns = 0L
        for (s in swipes) {
            val target = s.word.lowercase()
            if (target !in known) { miss++; continue }
            val xs = IntArray(s.xs.size) { Math.round(s.xs[it] * s.w * 4) }; val ys = IntArray(s.ys.size) { Math.round(s.ys[it] * s.h * 4) }
            val k = keys(s.w * 4, s.h * 4) // 4x: whole pixels like a phone (the canvas is in CSS pixels)
            val t = System.nanoTime()
            val r = GlideMatcher.match(xs, ys, xs.size, k, index, 12, p).map { it.word.lowercase() }
            ns += System.nanoTime() - t
            n++
            if (r.firstOrNull() == target) t1++
            if (target in r.take(3)) t3++
        }
        return Score(t1, t3, n, miss, ns / 1e6 / maxOf(1, n))
    }

    @Test fun benchmark() {
        Assume.assumeTrue(System.getProperty("aikb.swipeBench").orEmpty().isNotEmpty())
        Assume.assumeTrue(File(dir, "test.jsonl").isFile && words.isFile)
        val e = words.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
            .filter { it.first.length in 2..24 && it.second > 0 }.distinctBy { it.first }
        val index = GlideIndex(e.map { it.first }.toTypedArray(), ByteArray(e.size) { e[it].second.coerceIn(0, 255).toByte() })
        val known = e.map { it.first.lowercase() }.toHashSet()
        val tune = load(0, 4000)
        val check = load(10000, 20000)
        val report = StringBuilder()
        fun say(s: String) { println("SWIPEBENCH $s"); report.append(s).append('\n') }
        say("dataset: FUTO swipe test split (MIT); tune ${tune.size} swipes, check ${check.size} swipes; dictionary en_old.tsv ${e.size} words")
        say("default ${GlideParams.DEFAULT}: tune ${run(tune, index, known, GlideParams.DEFAULT)}")
        // round 3 (round 2: pass 0.5, coverage 0.15, frequency 2.5, start/end 1.0/1.6; frequency still at the edge)
        var best = GlideParams(passDistance = 0.5f, coverageWeight = 0.15, frequencyWeight = 2.5, startRadius = 1.0f, endRadius = 1.6f)
        var bestTop1 = run(tune, index, known, best).top1
        say("start $best: tune ${run(tune, index, known, best)}")
        fun tryP(p: GlideParams) { val s = run(tune, index, known, p); if (s.top1 > bestTop1) { best = p; bestTop1 = s.top1; say("better $p: tune $s") } }
        for (freq in listOf(3.0, 3.5, 4.0, 5.0, 6.5)) tryP(best.copy(frequencyWeight = freq))
        for (cov in listOf(0.1, 0.2, 0.3)) tryP(best.copy(coverageWeight = cov))
        for (r in listOf(0.9f to 1.6f, 1.0f to 1.8f, 1.1f to 1.6f)) tryP(best.copy(startRadius = r.first, endRadius = r.second))
        for (l in listOf(2.0f to 0.45f, 2.4f to 0.45f, 2.2f to 0.55f)) tryP(best.copy(longest = l.first, shortest = l.second))
        say("ROUND3 BEST $best")
        say("CHECK default: ${run(check, index, known, GlideParams.DEFAULT)}")
        say("CHECK tuned $best: ${run(check, index, known, best)}")
        File(out, "summary.txt").writeText(report.toString())
    }
}
