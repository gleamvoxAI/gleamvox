// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import kotlin.math.abs
import kotlin.math.min

/**
 * ai-dict-1: how far a typed word is from a candidate on a phone keyboard. A keyboard-aware edit distance: a neighbour key
 * costs less than a far key, swapped letters cost less than two errors, letters the user has not typed yet (the candidate
 * goes on after the typed word) cost little. Lower case, ё = е.
 */
object TypoCost {
    private val ROWS = listOf(
        "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "ضصثقفغعهخحج", "شسيبلاتنمكط", "",
    )
    /** letters on the key of a letter above: Ukrainian on ЙЦУКЕН */
    private val SAME_KEY = mapOf('і' to 'ы', 'ї' to 'ъ', 'є' to 'э', 'ґ' to 'г')
    private val pos = HashMap<Char, Pair<Int, Float>>().apply {
        ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, (r % 3) + (r / 3) * 10 to i + (r % 3) * 0.5f) } }
    }

    fun neighbours(a: Char, b: Char): Boolean {
        val pa = pos[SAME_KEY[a] ?: base(a)] ?: return false
        val pb = pos[SAME_KEY[b] ?: base(b)] ?: return false
        val dr = abs(pa.first - pb.first)
        return dr <= 1 && abs(pa.second - pb.second) <= 1.01f
    }

    /** the letter without its accent (é → e, ą → a, ş → s, ł → l, ı → i): typed without the long press */
    private fun base(c: Char): Char = when (c) {
        'ł' -> 'l'; 'ı' -> 'i'; 'ø' -> 'o'; 'ß' -> 's'; 'đ' -> 'd'
        else -> if (c.code < 0xC0 || c.code > 0x24F) c else java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0]
    }

    private fun subCost(a: Char, b: Char) = when {
        a == b -> 0.0
        base(a) == base(b) -> ACCENT
        neighbours(a, b) -> 0.6
        else -> 1.0
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    /** cost of turning [typed] into [cand]; 0 = the same word */
    fun cost(typed: String, cand: String): Double {
        val a = norm(typed); val b = norm(cand)
        if (a == b) return 0.0
        val n = a.length; val m = b.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i * 1.0
        for (j in 0..m) d[0][j] = j * 1.0
        for (i in 1..n) for (j in 1..m) {
            val sub = subCost(a[i - 1], b[j - 1])
            var v = min(min(d[i - 1][j] + 1.0, d[i][j - 1] + 1.0), d[i - 1][j - 1] + sub)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 0.7)
            d[i][j] = v
        }
        // the candidate goes on after the typed letters (a completion): the missing end costs less than a typo
        var best = d[n][m]
        for (j in n until m) best = min(best, d[n][j] + (m - j) * COMPLETION_PER_LETTER)
        return best
    }

    const val COMPLETION_PER_LETTER = 0.45
    /** a letter typed without its accent */
    const val ACCENT = 0.3
}

/**
 * ai-dict-1: the dictionary gives candidates, the model scores them in the context of the sentence. Score of a candidate =
 * log P(word | text before it) (language model, natural log) − [typoWeight] × keyboard edit cost. The dictionary choice
 * is kept unless the model is clearly surer: autocorrection only when the best candidate beats the typed word by
 * [correctMargin] and the second candidate by [aloneMargin].
 */
class LmRanker(
    private val model: WordModel,
    private val typoWeight: Double = 4.0,
    private val correctMargin: Double = 2.5,
    private val aloneMargin: Double = 0.7,
    /** ux-2: the model never replaces a word by one that is further than this on the keyboard ("Gle" -> "For" is 1.8) */
    private val maxCorrectCost: Double = 1.5,
) {
    data class Scored(val word: String, val lm: Double, val cost: Double, val score: Double)
    data class Result(val ranked: List<Scored>, val autocorrectTo: String?, val ms: Long)

    /**
     * [candidates] from the dictionary, best first (the typed word may be among them or not); [typedIsWord]: the typed
     * word is in the dictionary. Returns all candidates best first and the word to autocorrect to (null = keep typed).
     */
    fun rank(context: String, typed: String, candidates: List<String>, typedIsWord: Boolean): Result {
        val t0 = System.nanoTime()
        val words = (listOf(typed) + candidates).distinct().take(MAX_CANDIDATES)
        val lp = model.wordLogProbs(context, words)
        val scored = words.mapIndexed { i, w ->
            val c = TypoCost.cost(typed, w)
            Scored(w, lp[i], c, lp[i] - typoWeight * c)
        }.sortedByDescending { it.score }
        val typedScore = scored.first { it.word == typed }.score
        val best = scored.first()
        val second = scored.getOrNull(1)
        val correct = best.cost <= maxCorrectCost && best.word != typed && !best.word.equals(typed, ignoreCase = true) &&
                best.score - typedScore >= (if (typedIsWord) correctMargin * 2 else correctMargin) &&
                (second == null || second.word == typed || best.score - second.score >= aloneMargin)
        return Result(scored, if (correct) best.word else null, (System.nanoTime() - t0) / 1_000_000)
    }

    companion object { const val MAX_CANDIDATES = 6 }
}
