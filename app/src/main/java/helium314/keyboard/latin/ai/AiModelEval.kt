// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.openai.CancelSignal
import java.util.Locale

/**
 * Comparison of the candidate text models on 20 fixed Russian and English test phrases (stage 5zh, docs/AI.md section 7):
 * 12 for the full cleanup of dictation (hesitations, fillers, self-corrections, and fillers that carry meaning and must
 * stay) and 8 for ✨ "Fix" (punctuation, capitals, spelling). Every answer is checked automatically; the report lists
 * score, time and the failed checks per model. Only these test phrases are sent, never text of the user. Started by
 * hand in Advanced → Details with the user's own key; each phrase is one ordinary request (counted in the daily ceiling).
 */
class AiModelEval(
    /** one request with the model of [AiRequest.model]; blocking, on a background thread */
    private val respond: (AiRequest, CancelSignal) -> AiResult,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Task { CLEANUP, FIX }

    /** [mustContain] exact (case matters), [mustNotContain] whole words, any case; [cyrillic]: the answer stays Russian */
    class Case(val task: Task, val input: String, val mustContain: List<String> = emptyList(),
               val mustNotContain: List<String> = emptyList(), val endsWithPunctuation: Boolean = false) {
        val cyrillic get() = input.any { it in 'а'..'я' || it in 'А'..'Я' }
    }

    class CaseResult(val case: Case, val output: String?, val error: AiError?, val failed: List<String>, val ms: Long) {
        val ok get() = error == null && failed.isEmpty()
    }

    class ModelResult(val model: String, val cases: List<CaseResult>) {
        val passed get() = cases.count { it.ok }
        val averageMs get() = cases.filter { it.error == null }.map { it.ms }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
    }

    fun request(case: Case, model: String): AiRequest = when (case.task) {
        Task.CLEANUP -> DictationCleanup.request(CleanupLevel.FULL, case.input).let {
            AiRequest(it.instructions, it.text, it.maxAnswerChars, it.totalTimeoutMs, retryUnavailable = false, isCleanup = true, model = model)
        }
        Task.FIX -> AiCommands.request(AiCommand.FIX, case.input).let {
            AiRequest(it.instructions, it.text, it.maxAnswerChars, retryUnavailable = false, model = model)
        }
    }

    /** all [CASES] with [model]; [onProgress] after every phrase (done, total) */
    fun runModel(model: String, cancel: CancelSignal, onProgress: (Int, Int) -> Unit = { _, _ -> }): ModelResult {
        val results = mutableListOf<CaseResult>()
        for ((i, case) in CASES.withIndex()) {
            if (cancel.isCancelled) break
            val start = nanoTime()
            val r = respond(request(case, model), cancel)
            val ms = (nanoTime() - start) / 1_000_000
            results.add(when (r) {
                is AiResult.Success -> CaseResult(case, r.text, null, check(case, r.text), ms)
                is AiResult.Failure -> CaseResult(case, null, r.error, emptyList(), ms)
            })
            onProgress(i + 1, CASES.size)
            if (r is AiResult.Failure && r.error in STOPPING) break // no key, no money, ceiling: the other phrases would fail too
        }
        return ModelResult(model, results)
    }

    companion object {
        private val STOPPING = setOf(AiError.NO_KEY, AiError.KEY_INVALID, AiError.NO_CONSENT, AiError.INTERNET_OFF,
            AiError.NO_BALANCE, AiError.DAILY_CAP, AiError.PHONE_LOCKED, AiError.CANCELLED)

        private fun clean(s: String, vararg words: String) = Case(Task.CLEANUP, s, mustNotContain = words.toList())

        val CASES: List<Case> = listOf(
            // cleanup: hesitations, fillers, doubled words, self-corrections
            Case(Task.CLEANUP, "э-э привет как дела", listOf("привет", "дела"), listOf("э-э", "э")),
            Case(Task.CLEANUP, "ну давай встретимся завтра в пять", listOf("встретимся завтра в пять"), listOf("ну")),
            Case(Task.CLEANUP, "я типа не успеваю короче давай позже", listOf("не успеваю", "позже"), listOf("типа", "короче")),
            Case(Task.CLEANUP, "встреча в пятницу, нет, в четверг в три", listOf("четверг", "в три"), listOf("пятницу")),
            Case(Task.CLEANUP, "я я думаю что это хорошая идея", listOf("думаю", "хорошая идея"), listOf("я я")),
            Case(Task.CLEANUP, "мм вот значит купи хлеба и молока", listOf("хлеба и молока"), listOf("мм", "значит")),
            Case(Task.CLEANUP, "uh I think we should um leave at six", listOf("leave at six"), listOf("uh", "um")),
            Case(Task.CLEANUP, "it's like you know really important", listOf("really important"), listOf("you know")),
            Case(Task.CLEANUP, "send it on Friday, no, on Thursday", listOf("Thursday"), listOf("Friday")),
            // fillers that carry meaning: they must stay
            Case(Task.CLEANUP, "ну что ж, давай попробуем", listOf("ну что ж", "попробуем")),
            Case(Task.CLEANUP, "он смотрел на меня как бы с удивлением", listOf("как бы", "с удивлением")),
            Case(Task.CLEANUP, "I like this plan", listOf("I like this plan")),
            // ✨ Fix: punctuation, capitals, spelling
            Case(Task.FIX, "привет как дела давай встретимся завтра", listOf("Привет", "встретимся завтра"), endsWithPunctuation = true),
            Case(Task.FIX, "я сегодня не смогу прийти извини пожалуйста", listOf("Я сегодня", "извини"), endsWithPunctuation = true),
            Case(Task.FIX, "мы встретились в кафе и обсудили проэкт", listOf("проект"), listOf("проэкт"), endsWithPunctuation = true),
            Case(Task.FIX, "когда ты приедешь", listOf("Когда", "?")),
            Case(Task.FIX, "i dont know where the station is", listOf("I don't know", "station"), endsWithPunctuation = true),
            Case(Task.FIX, "their going to the park tomorow", listOf("They're", "tomorrow"), listOf("tomorow"), endsWithPunctuation = true),
            Case(Task.FIX, "can you send me the file", listOf("Can you", "?")),
            Case(Task.FIX, "thanks for you help yesterday", listOf("your help"), endsWithPunctuation = true),
        )

        private fun normalize(s: String) = s.replace('’', '\'').replace('ё', 'е').replace('Ё', 'Е')

        private fun hasWord(text: String, word: String) =
            Regex("(?<![\\p{L}-])" + Regex.escape(word) + "(?![\\p{L}-])", setOf(RegexOption.IGNORE_CASE)).containsMatchIn(text)

        /** the names of the failed checks; empty: the answer is fine */
        fun check(case: Case, answer: String): List<String> {
            val out = normalize(answer.trim())
            val failed = mutableListOf<String>()
            if (out.isEmpty()) return listOf("empty")
            // an expectation in lower case may start a sentence in the answer: then the case does not matter
            case.mustContain.filter { e ->
                val n = normalize(e)
                if (n == n.lowercase()) n !in out.lowercase() else n !in out
            }.forEach { failed.add("missing \"$it\"") }
            case.mustNotContain.filter { hasWord(out, it) }.forEach { failed.add("still \"$it\"") }
            if (case.endsWithPunctuation && out.last() !in ".!?…") failed.add("no final punctuation")
            if (case.cyrillic && out.none { it in 'а'..'я' || it in 'А'..'Я' }) failed.add("language changed")
            if (case.task == Task.CLEANUP && DictationCleanup.isSuspicious(case.input, answer)) failed.add("length")
            return failed
        }

        /**
         * The models compared: the first three of the economy list the key offers, and gpt-4.1-nano (the former default)
         * as the reference if the key has it; at most 4 models, 80 requests.
         */
        fun candidates(ids: List<String>): List<String> {
            val first = TextModels.order(ids, AiQuality.ECONOMY).filter { it in TextModels.ECONOMY_ORDER }.take(3)
            val reference = "gpt-4.1-nano".takeIf { it in ids && it !in first }
            return (first + listOfNotNull(reference)).take(4)
        }

        /** the text of "Copy report": test phrases, answers, checks, time; no key and no text of the user */
        fun report(results: List<ModelResult>, date: String): String = buildString {
            appendLine("AI Keyboard: comparison of text models, $date, ${CASES.size} test phrases (cleanup full + ✨ Fix)")
            for (m in results) appendLine("${m.model}: ${m.passed} of ${m.cases.size} passed, average ${m.averageMs} ms" +
                (TextModels.typicalCost(m.model)?.let { ", ≈" + String.format(Locale.ROOT, "%.2f", it / 1000.0) + " $ / 1000 requests" } ?: ""))
            for (m in results) {
                appendLine()
                appendLine("== ${m.model}")
                for ((i, c) in m.cases.withIndex()) {
                    val verdict = when {
                        c.error != null -> "ERROR ${c.error.name.lowercase()}"
                        c.ok -> "ok"
                        else -> "FAIL " + c.failed.joinToString("; ")
                    }
                    appendLine("${i + 1}. [${c.case.task.name.lowercase()}] ${c.case.input} → ${c.output ?: "-"}  ($verdict, ${c.ms} ms)")
                }
            }
        }
    }
}
