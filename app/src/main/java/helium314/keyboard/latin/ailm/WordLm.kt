// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

/**
 * ai-dict-1: words, not tokens. How likely is a whole word (followed by a word end) after the text before it, and which
 * whole words are likely next. A word is several tokens of the model; a word ends where the next token starts with a space,
 * a punctuation mark, a line end or the end of text.
 */
interface WordModel {
    /** natural-log probability of each word right after [context] (the text before the word, without the word) */
    fun wordLogProbs(context: String, words: List<String>): DoubleArray
    /** up to [count] likely next words after [context], best first, with their log-probabilities */
    fun nextWords(context: String, count: Int): List<Pair<String, Double>>
    /** ai-dict-3: computes the state of [context] ahead (the next request with it only scores the candidates) */
    fun prepare(context: String) {}
}

class WordLm(private val tok: BpeTokenizer, private val lm: OnnxLm) : WordModel {
    /**
     * the language of the keyboard (ru, en, …). One or two words do not tell the model the language ("Seni" is Turkish,
     * the model goes on in English), the keyboard knows it: a short neutral line in that language goes before the text.
     */
    var language: String = ""
        set(value) { field = value; primer = PRIMERS[value]?.let { tok.encode(it + "\n") } ?: IntArray(0) }
    private var primer = IntArray(0)

    /** tokens that end a word when they come next */
    private val endsWord = BooleanArray(MASK_SIZE) { id -> if (id >= tok.vocabSize) return@BooleanArray false;
        val b = tok.tokenBytes[id]
        b.isEmpty() || id == tok.endOfText || isBoundaryByte(b[0].toInt() and 0xff)
    }
    /** tokens that start a new word with a space and a letter: " слово" */
    private val startsWord = BooleanArray(MASK_SIZE) { id -> if (id >= tok.vocabSize) return@BooleanArray false;
        val b = tok.tokenBytes[id]
        b.size >= 2 && b[0] == ' '.code.toByte() && letterAt(b, 1)
    }
    /** tokens that go on inside a word (letters, also the 2nd byte of a Cyrillic letter) */
    private val inWord = BooleanArray(MASK_SIZE) { id -> if (id >= tok.vocabSize) return@BooleanArray false;
        val b = tok.tokenBytes[id]
        b.isNotEmpty() && !endsWord[id] && (letterAt(b, 0) || (b[0].toInt() and 0xC0) == 0x80 || b[0] == '-'.code.toByte())
    }

    private fun contextTokens(context: String): IntArray {
        val trimmed = context.takeLast(MAX_CONTEXT_CHARS).let { c ->
            if (c.length < context.length) c.substringAfter(' ', c) else c
        }.trimEnd()
        val ids = tok.encode(trimmed)
        // the document starts with the end-of-text token: the base models learned that a text begins after it
        return intArrayOf(tok.endOfText) + primer + ids.takeLast(MAX_CONTEXT_TOKENS)
    }

    private fun wordTokens(context: String, word: String): IntArray =
        tok.encode(if (context.isBlank() || context.endsWith("\n")) word else " $word")

    private fun endMass(lp: FloatArray): Double {
        var s = 0.0
        for (i in lp.indices) if (endsWord[i]) s += Math.exp(lp[i].toDouble())
        return Math.log(s.coerceAtLeast(1e-12))
    }

    override fun prepare(context: String) { lm.state(contextTokens(context)) }

    override fun wordLogProbs(context: String, words: List<String>): DoubleArray {
        val ctx = lm.state(contextTokens(context))
        return lm.score(ctx, words.map { wordTokens(context, it) }) { endMass(it) }
    }

    override fun nextWords(context: String, count: Int): List<Pair<String, Double>> {
        val ctx = lm.state(contextTokens(context))
        val atStart = context.isBlank()
        // first tokens: those that begin a word (with a space; at the very start of a text also without)
        val first = ctx.logProbs.indices.filter { if (atStart) inWord[it] || startsWord[it] else startsWord[it] }
            .sortedByDescending { ctx.logProbs[it] }.take(count + 2)
        if (first.isEmpty()) return emptyList()
        // all candidates grow together, one batched model run per letter group (greedy, the word ends when the model
        // would rather end it)
        val lp = DoubleArray(first.size) { ctx.logProbs[first[it]].toDouble() }
        val bytes = Array(first.size) { ArrayList<Byte>().apply { tok.tokenBytes[first[it]].forEach { b -> add(b) } } }
        val done = BooleanArray(first.size)
        var rows = (first.indices).toList()
        var states = lm.step(rows.map { ctx }, IntArray(rows.size) { first[it] })
        var steps = 0
        while (rows.isNotEmpty()) {
            val nextRows = ArrayList<Int>(); val nextTok = ArrayList<Int>(); val nextFrom = ArrayList<OnnxLm.State>()
            for ((k, r) in rows.withIndex()) {
                val dist = states[k].logProbs
                var best = -1
                for (i in dist.indices) if (inWord[i] && (best < 0 || dist[i] > dist[best])) best = i
                val end = endMass(dist)
                if (best < 0 || end >= dist[best] || steps >= MAX_WORD_TOKENS) { lp[r] += end; done[r] = true; continue }
                lp[r] += dist[best]
                tok.tokenBytes[best].forEach { bytes[r].add(it) }
                nextRows.add(r); nextTok.add(best); nextFrom.add(states[k])
            }
            if (nextRows.isEmpty()) break
            rows = nextRows
            states = lm.step(nextFrom, nextTok.toIntArray())
            steps++
        }
        val out = ArrayList<Pair<String, Double>>()
        for (r in first.indices.sortedByDescending { lp[it] }) {
            val word = String(bytes[r].toByteArray(), Charsets.UTF_8).trim()
            // one form per word: "Вам" and "вам" are the same suggestion (the keyboard capitalises itself)
            if (word.isNotEmpty() && word.none { it == '\uFFFD' } && word.any { it.isLetter() } && out.none { it.first.equals(word, ignoreCase = true) })
                out.add(word to lp[r])
        }
        return out.sortedByDescending { it.second }.take(count)
    }

    companion object {
        /** neutral lines (no words of the checked phrases): "a chat in a messenger" in the language of the keyboard */
        val PRIMERS = mapOf(
            "ru" to "Переписка в мессенджере.", "uk" to "Листування в месенджері.", "en" to "A chat in a messaging app.",
            "es" to "Conversación en una aplicación de mensajería.", "pt" to "Conversa em um aplicativo de mensagens.",
            "fr" to "Conversation dans une application de messagerie.", "de" to "Unterhaltung in einer Messenger-App.",
            "it" to "Conversazione in un'app di messaggistica.", "tr" to "Bir mesajlaşma uygulamasında sohbet.",
            "pl" to "Rozmowa w komunikatorze.", "id" to "Percakapan di aplikasi pesan.", "hi" to "मैसेजिंग ऐप पर बातचीत।",
            "ar" to "محادثة في تطبيق المراسلة.", "ko" to "메신저 앱에서의 대화.",
        )
        /** the model may have more outputs than the tokenizer has tokens (padding of the vocabulary): those never match */
        private const val MASK_SIZE = 1 shl 18
        const val MAX_CONTEXT_CHARS = 160
        const val MAX_CONTEXT_TOKENS = 48
        const val MAX_WORD_TOKENS = 4
        private fun isBoundaryByte(b: Int) = b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code ||
                (b < 0x80 && !Character.isLetterOrDigit(b) && b != '-'.code && b != '\''.code)
        /**
         * the token has a letter (or a vowel sign: Devanagari, Arabic) of any script at [from]. A token of a byte-level model
         * may hold only the first bytes of a letter: then the first byte tells the script (not punctuation, symbols, emoji).
         */
        private fun letterAt(b: ByteArray, from: Int): Boolean {
            if (from >= b.size) return false
            val c = b[from].toInt() and 0xff
            if (c < 0x80) return Character.isLetter(c)
            val len = when { c >= 0xF0 -> 4; c >= 0xE0 -> 3; c >= 0xC2 -> 2; else -> return false }
            if (from + len > b.size) return c in 0xC3..0xDF || (c in 0xE0..0xEE && c != 0xE2)
            val cp = String(b, from, len, Charsets.UTF_8).codePointAt(0)
            val type = Character.getType(cp)
            return Character.isLetter(cp) || type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        }
    }
}
