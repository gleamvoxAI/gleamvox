// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import java.io.File
import java.io.Reader
import java.text.Normalizer

/**
 * ai-dict-1: the byte-level BPE tokenizer of the small language models (Qwen2.5, Qwen3, SmolLM2), read from their own
 * tokenizer.json. Written from the published algorithm (GPT-2 byte-to-unicode table, merges by rank); no tokenizer code is
 * copied. Only what scoring words needs: encode text, and the bytes of each token (to know where a word ends).
 */
class BpeTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    private val ranks: HashMap<String, Int>,
    private val split: Regex,
    private val digitsAlone: Boolean,
    val endOfText: Int,
) {
    /** the bytes of each token id (the inverse of the byte-to-unicode table applied to its vocabulary string) */
    val tokenBytes: Array<ByteArray>
    private val cache = object : LinkedHashMap<String, IntArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>) = size > 4096
    }

    init {
        val size = (vocab.values.maxOrNull() ?: 0) + 1
        val inv = HashMap<Char, Int>(512).apply { BYTE_TO_CHAR.forEachIndexed { b, c -> put(c, b) } }
        tokenBytes = Array(size) { ByteArray(0) }
        for ((s, id) in vocab) {
            val out = ByteArray(s.length)
            var ok = true
            for (i in s.indices) { val b = inv[s[i]]; if (b == null) { ok = false; break }; out[i] = b.toByte() }
            tokenBytes[id] = if (ok) out else s.toByteArray()
        }
    }

    val vocabSize get() = tokenBytes.size

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        val norm = Normalizer.normalize(text, Normalizer.Form.NFC)
        val pieces = if (digitsAlone) DIGIT.split(norm).flatMap { splitDigits(it) } else listOf(norm)
        for (p in pieces) for (m in split.findAll(p)) {
            val piece = m.value
            val ids = synchronized(cache) { cache[piece] } ?: bpe(piece).also { synchronized(cache) { cache[piece] = it } }
            ids.forEach { out.add(it) }
        }
        return out.toIntArray()
    }

    /** SmolLM2: every digit is its own piece before the byte-level split */
    private fun splitDigits(s: String): List<String> = Regex("\\d|\\D+").findAll(s).map { it.value }.toList()

    private fun bpe(piece: String): IntArray {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val parts = ArrayList<String>(bytes.size)
        for (b in bytes) parts.add(BYTE_TO_CHAR[b.toInt() and 0xff].toString())
        while (parts.size > 1) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks[parts[i] + " " + parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            parts[best] = parts[best] + parts[best + 1]
            parts.removeAt(best + 1)
        }
        return IntArray(parts.size) { vocab[parts[it]] ?: endOfText }
    }

    companion object {
        /** GPT-2 byte-to-unicode: printable bytes stay, the others move to 256+ */
        val BYTE_TO_CHAR: CharArray = CharArray(256).also { t ->
            var n = 0
            for (b in 0 until 256) {
                val printable = b in '!'.code..'~'.code || b in 0xA1..0xAC || b in 0xAE..0xFF
                t[b] = if (printable) b.toChar() else (256 + n++).toChar()
            }
        }
        private val DIGIT = Regex("(?=a)b") // never matches: digits are split inside splitDigits
        // the split of Qwen2/Qwen3 (from their tokenizer.json); GPT-2 for SmolLM2
        private const val QWEN = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"
        private const val GPT2 = "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"

        fun load(file: File): BpeTokenizer = file.reader(Charsets.UTF_8).buffered(1 shl 16).use { read(it) }

        fun read(reader: Reader): BpeTokenizer {
            val vocab = HashMap<String, Int>(160_000)
            val ranks = HashMap<String, Int>(160_000)
            var pattern: String? = null
            var digits = false
            var eot = -1
            val added = HashMap<String, Int>()
            val j = JsonScan(reader)
            j.obj { key ->
                when (key) {
                    "added_tokens" -> j.arr { var c = ""; var id = -1; j.obj { k -> when (k) { "content" -> c = j.string(); "id" -> id = j.int(); else -> j.skip() } }; added[c] = id }
                    "pre_tokenizer" -> { val s = j.raw(); pattern = Regex("\"Regex\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(s)?.groupValues?.get(1)
                        digits = s.contains("\"Digits\"") }
                    "model" -> j.obj { k ->
                        when (k) {
                            "vocab" -> j.obj { w -> vocab[w] = j.int() }
                            "merges" -> { var r = 0; j.arr { if (j.peekArray()) { val a = ArrayList<String>(2); j.arr { a.add(j.string()) }; ranks[a[0] + " " + a[1]] = r++ } else ranks[j.string()] = r++ } }
                            else -> j.skip()
                        }
                    }
                    else -> j.skip()
                }
            }
            for ((c, id) in added) vocab.putIfAbsent(c, id)
            eot = added["<|endoftext|>"] ?: vocab["<|endoftext|>"] ?: 0
            val regex = if (pattern != null) Regex(unescapeJson(pattern!!)) else Regex(if (digits) GPT2 else QWEN)
            return BpeTokenizer(vocab, ranks, regex, digits, eot)
        }

        private fun unescapeJson(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> sb.append(n)
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}

/** a small streaming JSON reader: tokenizer.json of Qwen is 7-9 MB, a full JSON tree would not fit the memory of a keyboard */
internal class JsonScan(private val r: Reader) {
    private var peeked = -2
    private fun next(): Int { if (peeked != -2) { val p = peeked; peeked = -2; return p }; return r.read() }
    private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }
    private fun ws() { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return } }
    private fun expect(c: Char) { ws(); val n = next(); require(n == c.code) { "JSON: expected $c, got ${if (n < 0) "end" else n.toChar()}" } }

    fun obj(onKey: (String) -> Unit) {
        expect('{'); ws()
        if (peek() == '}'.code) { next(); return }
        while (true) {
            val k = string(); expect(':'); onKey(k); ws()
            when (next()) { ','.code -> ws(); '}'.code -> return; else -> error("JSON: bad object") }
        }
    }

    fun arr(onItem: () -> Unit) {
        expect('['); ws()
        if (peek() == ']'.code) { next(); return }
        while (true) {
            onItem(); ws()
            when (next()) { ','.code -> ws(); ']'.code -> return; else -> error("JSON: bad array") }
        }
    }

    fun peekArray(): Boolean { ws(); return peek() == '['.code }

    fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                -1 -> error("JSON: open string")
                '"'.code -> return sb.toString()
                '\\'.code -> when (val e = next()) {
                    'n'.code -> sb.append('\n'); 'r'.code -> sb.append('\r'); 't'.code -> sb.append('\t')
                    'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000c')
                    'u'.code -> { val h = CharArray(4) { next().toChar() }; sb.append(String(h).toInt(16).toChar()) }
                    else -> sb.append(e.toChar())
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    fun int(): Int { ws(); val sb = StringBuilder(); while (true) { val c = peek(); if (c == '-'.code || c in '0'.code..'9'.code) sb.append(next().toChar()) else break }; return sb.toString().toInt() }

    /** any value as raw text (only used for the small pre_tokenizer part) */
    fun raw(): String { val sb = StringBuilder(); copyValue(sb); return sb.toString() }
    fun skip() { copyValue(null) }

    private fun copyValue(sb: StringBuilder?) {
        ws()
        when (peek()) {
            '"'.code -> { val s = string(); sb?.append('"')?.append(s.replace("\\", "\\\\").replace("\"", "\\\""))?.append('"') }
            '{'.code, '['.code -> {
                var depth = 0; var inStr = false; var esc = false
                do {
                    val c = next(); sb?.append(c.toChar())
                    if (inStr) { if (esc) esc = false else if (c == '\\'.code) esc = true else if (c == '"'.code) inStr = false }
                    else when (c) { '"'.code -> inStr = true; '{'.code, '['.code -> depth++; '}'.code, ']'.code -> depth-- }
                } while (depth > 0 && c != -1)
            }
            else -> while (true) { val c = peek(); if (c == ','.code || c == '}'.code || c == ']'.code || c == -1) break; val n = next(); sb?.append(n.toChar()) }
        }
    }
}
