// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ailm

import helium314.keyboard.latin.ailm.BpeTokenizer
import helium314.keyboard.latin.ailm.LmRanker
import helium314.keyboard.latin.ailm.OnnxLm
import helium314.keyboard.latin.ailm.TypoCost
import helium314.keyboard.latin.ailm.WordLm
import java.io.File
import kotlin.test.Test

/**
 * ai-dict-1: compares the dictionary (approximation of HeliBoard's choice from HeliBoard's own dictionaries) with small
 * language models on the phrases of resources/ailm/eval.tsv, language by language. Runs only from
 * `tools/aikb/build.sh lmeval`: there every pass is its own Java process without Gradle (a model takes up to ~1 GB, the
 * server is small): "dict" first (candidates of the dictionary, saved for the models), then one process per model, then
 * "summary=dict,model1,..." (the tables). The normal test run skips it. Files in app/build/aikb-lmeval/.
 */
class LocalLmEvalTest {
    @Test fun compare() {
        val mode = System.getProperty("aikb.lmEval").orEmpty()
        if (mode.isEmpty()) return // only from build.sh lmeval
        LmEval(File("build/aikb-lmeval")).run(mode)
    }

    companion object {
        /** build.sh lmeval: args = mode, output folder */
        @JvmStatic fun main(args: Array<String>) = LmEval(File(args[1])).run(args[0])
    }
}

class LmEval(private val out: File) {
    private val tools = File(System.getProperty("user.home"), ".local/ai-keyboard-tools/models")

    data class Item(val n: Int, val kind: String, val lang: String, val context: String, val typed: String, val expected: Set<String>)

    private val items = javaClass.getResourceAsStream("/ailm/eval.tsv")!!.reader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapIndexed { n, it -> val p = it.split('\t'); Item(n, p[0], p[1], p[2], p[3], p[4].split('|').toSet()) }
    private val langs = items.map { it.lang }.distinct()

    fun run(mode: String) {
        out.mkdirs()
        when {
            mode == "dict" -> dictionaryPass()
            mode.startsWith("summary=") -> summary(mode.substringAfter('=').split(','))
            else -> modelPass(mode)
        }
    }

    // ---------------- the dictionary ----------------

    /** word list of one dictionary: lower case -> (form in the dictionary, frequency 0..255); the [cap] most frequent words */
    class Words(file: File, cap: Int) {
        val map = HashMap<String, Pair<String, Int>>(1 shl 20)
        val minFreq: Int
        init {
            val hist = IntArray(256)
            file.forEachLine { l -> l.substringAfter('\t', "").toIntOrNull()?.let { hist[it.coerceIn(0, 255)]++ } }
            var f = 255; var kept = hist[255]
            while (f > 0 && kept + hist[f - 1] <= cap) { f--; kept += hist[f] }
            minFreq = f
            file.forEachLine { l ->
                val t = l.indexOf('\t'); if (t <= 0) return@forEachLine
                val w = l.substring(0, t); val fr = l.substring(t + 1).toIntOrNull() ?: return@forEachLine
                if (fr < minFreq || w.any { it.isDigit() }) return@forEachLine
                val k = w.lowercase()
                val old = map[k]
                if (old == null || old.second < fr) map[k] = w to fr
            }
        }
        private val byLength by lazy { map.entries.groupBy { it.key.length } }
        fun has(w: String) = map.containsKey(w.lowercase())

        /** the dictionary's candidates for a typed word: keyboard edit cost <= 2, best by frequency and cost (as HeliBoard) */
        fun candidates(typed: String, n: Int): List<Pair<String, Double>> {
            val low = typed.lowercase()
            val res = ArrayList<Pair<String, Double>>()
            for (len in (low.length - 2).coerceAtLeast(1)..(low.length + 4)) {
                for (e in byLength[len] ?: continue) {
                    val k = e.key
                    if (k[0] != low[0] && !TypoCost.neighbours(k[0], low[0]) && TypoCost.cost(k.substring(0, 1), low.substring(0, 1)) > TypoCost.ACCENT) continue
                    val c = TypoCost.cost(low, k)
                    if (c > 2.0) continue
                    res.add(e.value.first to dictScore(e.value.second, c))
                }
            }
            return res.sortedByDescending { it.second }.take(n).map { (w, s) -> matchCase(typed, w) to s }
        }
    }

    companion object {
        fun dictScore(freq: Int, cost: Double) = freq * 0.08 - 4.0 * cost
        fun matchCase(typed: String, w: String) =
            if (typed.firstOrNull()?.isUpperCase() == true) w.replaceFirstChar { it.uppercaseChar() } else w
        /** the dictionary's own autocorrection (approximation): a non-word is replaced by the best candidate if it is close */
        const val DICT_MAX_COST = 1.6
        /** words of one dictionary kept in memory (the rarest are left out; they hardly ever win) */
        const val WORDS_CAP = 500_000
    }

    private fun wordsFile(lang: String) = when (lang) {
        "ru" -> File(tools, "wordlists/ru_exp.tsv")
        "en" -> File(tools, "wordlists/en_exp.tsv")
        else -> File(tools, "wordlists/${lang}_words.tsv")
    }

    private fun bigrams(lang: String): Map<String, List<String>> {
        val f = File(tools, "wordlists/bigrams_$lang.tsv")
        if (!f.isFile) return emptyMap()
        return f.readLines().associate { l -> l.substringBefore('\t') to l.substringAfter('\t').split(' ').filter { it.isNotEmpty() }.map { it.substringBeforeLast(':') } }
    }

    private class Res { var typoOk = 0; var typos = 0; var top3 = 0; var nextOk = 0; var nexts = 0 }

    private fun writeResults(label: String, res: Map<String, Res>) =
        File(out, "results-$label.tsv").writeText(langs.joinToString("") { l -> val r = res[l] ?: Res(); "$l\t${r.typoOk}\t${r.typos}\t${r.top3}\t${r.nextOk}\t${r.nexts}\n" })

    private fun dictionaryPass() {
        val res = HashMap<String, Res>()
        val lines = StringBuilder()
        val cands = StringBuilder()
        val meta = StringBuilder()
        for (lang in langs) {
            val file = wordsFile(lang)
            val words = if (file.isFile) Words(file, WORDS_CAP) else null
            val bg = bigrams(lang)
            meta.append("  $lang: ${if (words == null) "no HeliBoard dictionary" else "${file.name}, ${words.map.size} words (frequency >= ${words.minFreq})"}, " +
                "${if (bg.values.any { it.isNotEmpty() }) "next-word pairs" else "no next-word pairs"}\n")
            for (it in items.filter { i -> i.lang == lang }) {
                val r = res.getOrPut(lang) { Res() }
                if (it.kind == "T") {
                    val c = words?.candidates(it.typed, LmRanker.MAX_CANDIDATES) ?: emptyList()
                    val has = words?.has(it.typed) ?: false
                    val final = if (has) it.typed
                        else c.firstOrNull()?.takeIf { (w, _) -> TypoCost.cost(it.typed, w) <= DICT_MAX_COST }?.first ?: it.typed
                    cands.append("${it.n}\t${if (has) 1 else 0}\t$final\t${c.joinToString("|") { p -> p.first }}\n")
                    val ok = final in it.expected
                    if (ok) r.typoOk++; r.typos++
                    if (c.take(3).any { (w, _) -> w in it.expected } || it.typed in it.expected) r.top3++
                    lines.append("  ${if (ok) "+" else "-"} [$lang] «${it.context} ${it.typed}» → $final  (${c.take(3).joinToString { p -> p.first }})\n")
                } else {
                    val prev = it.context.trim().substringAfterLast(' ').trim(',', '.', '¿', '?', '!')
                    val next = (bg[prev] ?: bg[prev.lowercase()] ?: emptyList()).take(3)
                    val ok = next.any { w -> w in it.expected }
                    if (ok) r.nextOk++; r.nexts++
                    lines.append("  ${if (ok) "+" else "-"} [$lang] «${it.context} …» → ${next.joinToString(" | ")}\n")
                }
            }
            System.gc()
        }
        File(out, "cands.tsv").writeText(cands.toString())
        writeResults("dict", res)
        File(out, "report-dict.txt").writeText("== dictionary (HeliBoard's dictionaries; choice like HeliBoard: frequency and keyboard distance)\n$meta$lines")
        println("dictionary pass done")
    }

    // ---------------- a model ----------------

    private fun rssMb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS") }
        ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1

    private fun modelPass(spec: String) {
        val label = spec.replace(':', '_')
        val cands = File(out, "cands.tsv").readLines().associate { l ->
            val p = l.split('\t'); p[0].toInt() to Triple(p[1] == "1", p[2], p.getOrElse(3) { "" }.split('|').filter { it.isNotEmpty() })
        }
        // "folder" or "folder:file.onnx" (default model_int8.onnx)
        val dir = File(tools, spec.substringBefore(':'))
        val modelFile = File(dir, spec.substringAfter(':', "model_int8.onnx"))
        if (!modelFile.isFile) { File(out, "report-$label.txt").writeText("== $spec: no model file\n"); return }
        val rss0 = rssMb()
        var t = System.nanoTime()
        val tok = BpeTokenizer.load(File(dir, "tokenizer.json"))
        val tokMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val lm = OnnxLm(modelFile, threads = 2)
        val loadMs = (System.nanoTime() - t) / 1_000_000
        val wl = WordLm(tok, lm)
        val ranker = LmRanker(wl)
        val res = HashMap<String, Res>()
        val lines = StringBuilder()
        val rankMs = ArrayList<Long>(); val nextMs = ArrayList<Long>()
        var changed = 0
        for (it in items) {
            val r = res.getOrPut(it.lang) { Res() }
            wl.language = if (System.getProperty("aikb.noPrimer") != null) "" else it.lang
            if (it.kind == "T") {
                val (has, dictFinal, c) = cands[it.n] ?: Triple(false, it.typed, emptyList())
                val o = ranker.rank(it.context, it.typed, c, has)
                rankMs.add(o.ms)
                val final = o.autocorrectTo ?: it.typed
                if (final != dictFinal) changed++
                val ok = final in it.expected
                if (ok) r.typoOk++; r.typos++
                if (o.ranked.take(3).any { s -> s.word in it.expected }) r.top3++
                lines.append("  ${if (ok) "+" else "-"} [${it.lang}] «${it.context} ${it.typed}» → $final  (${o.ranked.take(3).joinToString { s -> "%s %.1f".format(s.word, s.score) }}) ${o.ms} ms\n")
            } else {
                t = System.nanoTime()
                val next = wl.nextWords(it.context, 3)
                val ms = (System.nanoTime() - t) / 1_000_000
                nextMs.add(ms)
                val ok = next.any { (w, _) -> w in it.expected }
                if (ok) r.nextOk++; r.nexts++
                lines.append("  ${if (ok) "+" else "-"} [${it.lang}] «${it.context} …» → ${next.joinToString(" | ") { p -> "%s %.1f".format(p.first, p.second) }}  $ms ms\n")
            }
        }
        val rss1 = rssMb()
        fun stats(l: List<Long>) = if (l.isEmpty()) "-" else "median ${l.sorted()[l.size / 2]} ms, max ${l.maxOrNull()} ms"
        val report = StringBuilder()
        report.append("== $spec (${modelFile.length() / 1_000_000} MB): tokenizer ${tokMs} ms, model load ${loadMs} ms, memory +${rss1 - rss0} MB (process ${rss1} MB), model steps ${lm.steps}\n")
        report.append("  scoring candidates: ${stats(rankMs)}; next words: ${stats(nextMs)}; final word differs from the dictionary in $changed cases\n")
        report.append(tokenizerCheck(tok))
        report.append(examples(tok, lm, wl))
        report.append(lines)
        File(out, "report-$label.txt").writeText(report.toString())
        File(out, "meta-$label.txt").writeText("$spec\t${modelFile.length() / 1_000_000}\t${rss1 - rss0}\t$loadMs\t${stats(rankMs)}\t${stats(nextMs)}\n")
        writeResults(label, res)
        lm.close()
        println("model pass $spec done")
    }

    /** does the tokenizer give back the very same text (else the model sees something else than was typed)? */
    private fun tokenizerCheck(tok: BpeTokenizer): String {
        val bad = HashMap<String, Int>(); val per = HashMap<String, Pair<Int, Int>>()
        val sb = StringBuilder()
        for (it in items) {
            for (text in listOf(it.context, "${it.context} ${it.typed}".trim(), "${it.context} ${it.expected.first()}".trim())) {
                if (text.isEmpty()) continue
                val ids = tok.encode(text)
                val back = String(ids.flatMap { id -> tok.tokenBytes[id].toList() }.toByteArray(), Charsets.UTF_8)
                val (n, c) = per[it.lang] ?: (0 to 0)
                per[it.lang] = (n + 1) to (c + ids.size)
                if (back != java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)) {
                    bad[it.lang] = (bad[it.lang] ?: 0) + 1
                    if (sb.length < 600) sb.append("    differs: «$text» → «$back»\n")
                }
            }
        }
        return "  tokenizer: text → tokens → text identical for all phrases except ${bad.values.sum()}; tokens per phrase: " +
            per.entries.joinToString { (l, p) -> "$l %.1f".format(p.second.toDouble() / p.first) } + "\n" + sb
    }

    /** the first next-word phrases of some languages: tokens the model sees and its most likely next tokens */
    private fun examples(tok: BpeTokenizer, lm: OnnxLm, wl: WordLm): String {
        val sb = StringBuilder("  examples (what the model sees and its 6 most likely next tokens, natural log):\n")
        for (lang in listOf("ru", "uk", "hi", "ar")) for (it in items.filter { i -> i.kind == "N" && i.lang == lang }.take(if (lang == "ru") 3 else 1)) {
            wl.language = it.lang
            val ids = intArrayOf(tok.endOfText) + (WordLm.PRIMERS[it.lang]?.let { p -> tok.encode(p + "\n") } ?: IntArray(0)) + tok.encode(it.context.trim())
            fun piece(id: Int) = if (id == tok.endOfText) "<start>" else if (id == -1) "" else String(tok.tokenBytes[id], Charsets.UTF_8).replace("�", "�").replace(" ", "␣")
            val st = lm.state(ids)
            val top = st.logProbs.indices.sortedByDescending { i -> st.logProbs[i] }.take(6)
            sb.append("    «${it.context}» tokens: ${ids.joinToString(" ") { id -> "[${piece(id)}]" }}\n")
            sb.append("      next tokens: ${top.joinToString(", ") { i -> "[${piece(i)}] %.2f".format(st.logProbs[i]) }}\n")
            sb.append("      words: ${wl.nextWords(it.context, 3).joinToString(" | ") { p -> "%s %.2f".format(p.first, p.second) }}; expected ${it.expected.joinToString("|")}\n")
        }
        return sb.toString()
    }

    // ---------------- the tables ----------------

    private fun summary(labels: List<String>) {
        val res = labels.associateWith { l ->
            val f = File(out, "results-$l.tsv")
            if (!f.isFile) emptyMap() else f.readLines().associate { line -> val p = line.split('\t'); p[0] to p.drop(1).map { it.toInt() } }
        }
        val sb = StringBuilder()
        sb.append("== models\n")
        for (l in labels.drop(1)) File(out, "meta-$l.txt").takeIf { it.isFile }?.readText()?.let { m ->
            val p = m.trim().split('\t')
            sb.append("  ${p[0]}: file ${p[1]} MB, memory +${p[2]} MB, load ${p[3]} ms, scoring ${p[4]}, next words ${p[5]}\n")
        }
        val w = labels.maxOf { it.length }.coerceAtLeast(10)
        fun table(title: String, cell: (List<Int>) -> String) {
            sb.append("\n== $title\n")
            sb.append("lang  " + labels.joinToString("  ") { it.padEnd(w) } + "\n")
            for (lang in langs) {
                sb.append(lang.padEnd(6) + labels.joinToString("  ") { l -> (res[l]?.get(lang)?.let(cell) ?: "-").padEnd(w) } + "\n")
            }
        }
        table("typos: right word after the space (right/all)") { r -> if (r[1] == 0) "-" else "${r[0]}/${r[1]}" }
        table("typos: expected word among the first 3 suggestions") { r -> if (r[1] == 0) "-" else "${r[2]}/${r[1]}" }
        table("next word: expected among the first 3") { r -> if (r[4] == 0) "-" else "${r[3]}/${r[4]}" }
        sb.append("\n== sum of the new languages (es pt fr de it uk tr pl id hi ar; Korean has no dictionary and is left out)\n")
        for (l in labels) {
            val rows = res[l]?.filterKeys { it !in setOf("ru", "en", "ko") }?.values ?: emptyList()
            val s = IntArray(5); rows.forEach { r -> for (i in 0 until 5) s[i] += r[i] }
            sb.append("  ${l.padEnd(w)} typos ${s[0]}/${s[1]}, first 3 ${s[2]}/${s[1]}, next word ${s[3]}/${s[4]}\n")
        }
        File(out, "summary.txt").writeText(sb.toString())
        println(sb)
    }
}
