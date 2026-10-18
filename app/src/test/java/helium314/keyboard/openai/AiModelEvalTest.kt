// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import helium314.keyboard.latin.ai.AiError
import helium314.keyboard.latin.ai.AiModelEval
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.openai.CancelSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** the comparison of models on 20 test phrases (stage 5zh, docs/AI.md section 7) */
class AiModelEvalTest {
    private val cases = AiModelEval.CASES

    @Test fun twentyPhrasesRussianAndEnglishCleanupAndFix() {
        assertEquals(20, cases.size)
        assertEquals(12, cases.count { it.task == AiModelEval.Task.CLEANUP })
        assertEquals(8, cases.count { it.task == AiModelEval.Task.FIX })
        assertTrue(cases.count { it.cyrillic } >= 10 && cases.count { !it.cyrillic } >= 8)
        // hesitations, fillers, self-corrections, and fillers with meaning that must stay
        val inputs = cases.joinToString(" ") { it.input }
        for (w in listOf("э-э", "ну", "типа", "нет, в четверг", "um", "no, on Thursday", "ну что ж", "как бы")) assertTrue(w in inputs, w)
    }

    @Test fun checksOfAnswers() {
        val selfCorrection = cases.first { it.input.startsWith("встреча в пятницу") }
        assertEquals(emptyList(), AiModelEval.check(selfCorrection, "Встреча в четверг в три."))
        assertTrue(AiModelEval.check(selfCorrection, "Встреча в пятницу, нет, в четверг в три.").any { "пятницу" in it })
        val hesitation = cases.first()
        assertEquals(emptyList(), AiModelEval.check(hesitation, "Привет, как дела?"), "a capital after the removal is fine")
        assertTrue(AiModelEval.check(hesitation, "э-э привет как дела").isNotEmpty())
        val meaningful = cases.first { it.input.startsWith("ну что ж") }
        assertTrue(AiModelEval.check(meaningful, "Давай попробуем.").any { "ну что ж" in it }, "a filler with meaning must stay")
        val fix = cases.first { it.input.startsWith("their going") }
        assertEquals(emptyList(), AiModelEval.check(fix, "They’re going to the park tomorrow."), "a curly apostrophe counts")
        assertTrue(AiModelEval.check(fix, "They're going to the park tomorrow").contains("no final punctuation"))
        val ru = cases.first { it.task == AiModelEval.Task.FIX && it.cyrillic }
        assertTrue(AiModelEval.check(ru, "Hi, how are you? Let's meet tomorrow.").contains("language changed"))
        assertTrue(AiModelEval.check(hesitation, "Привет! Как у тебя дела, дружище, давно не виделись, рассказывай всё!").contains("length"))
    }

    @Test fun runSendsOnlyTestPhrasesWithTheGivenModel() {
        val sent = mutableListOf<AiRequest>()
        val eval = AiModelEval({ r, _ -> sent.add(r); AiResult.Success(r.text) })
        val result = eval.runModel("gpt-6-luna", CancelSignal())
        assertEquals(20, sent.size)
        assertTrue(sent.all { it.model == "gpt-6-luna" && !it.retryUnavailable })
        assertEquals(cases.map { it.input }, sent.map { it.text })
        assertEquals(12, sent.count { it.isCleanup })
        // echoing the input passes only the phrases that need no change
        assertTrue(result.passed in 1..5, result.passed.toString())
    }

    @Test fun stopsWhenTheOtherPhrasesWouldFailToo() {
        var n = 0
        val eval = AiModelEval({ _, _ -> n++; AiResult.Failure(AiError.DAILY_CAP) })
        val r = eval.runModel("gpt-6-luna", CancelSignal())
        assertEquals(1, n)
        assertEquals(AiError.DAILY_CAP, r.cases.single().error)
        val cancel = CancelSignal().apply { cancel() }
        assertTrue(AiModelEval({ _, _ -> AiResult.Success("x") }).runModel("m", cancel).cases.isEmpty())
    }

    @Test fun candidatesLunaFirstNanoAsReference() {
        assertEquals(listOf("gpt-6-luna", "gpt-5.6-luna", "gpt-4.1-mini", "gpt-4.1-nano"),
            AiModelEval.candidates(listOf("gpt-4.1-nano", "gpt-4o-mini", "gpt-4.1-mini", "gpt-5.6-luna", "gpt-6-luna", "gpt-5.5", "o3")))
        assertEquals(listOf("gpt-4o-mini"), AiModelEval.candidates(listOf("gpt-4o-mini", "gpt-5.5")))
        assertEquals(emptyList(), AiModelEval.candidates(emptyList()))
    }

    @Test fun reportHasScoresAndNoKey() {
        val eval = AiModelEval({ r, _ -> AiResult.Success(r.text) }, nanoTime = { 0L })
        val report = AiModelEval.report(listOf(eval.runModel("gpt-6-luna", CancelSignal())), "2026-10-14 12:00")
        assertTrue(report.startsWith("AI Keyboard: comparison of text models, 2026-10-14 12:00, 20 test phrases"))
        assertTrue("gpt-6-luna: " in report && "≈0.07 $ / 1000 requests" in report, report)
        assertTrue("20. [fix] thanks for you help yesterday" in report, report)
        assertFalse("sk-" in report)
    }
}
