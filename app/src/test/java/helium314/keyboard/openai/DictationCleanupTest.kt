// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ai.AiError
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiProvider
import helium314.keyboard.latin.ai.AiProviderKind
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.CleanupLevel
import helium314.keyboard.latin.ai.CleanupUi
import helium314.keyboard.latin.ai.DictationCleaner
import helium314.keyboard.latin.ai.DictationCleanup
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.OpenAiTextProvider
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cleanup of dictation (docs/DESIGN.md, stage 5d): levels, replacement with the check, "Undo", silent refusal. */
@RunWith(RobolectricTestRunner::class)
class DictationCleanupTest {
    private val prefs = ApplicationProvider.getApplicationContext<android.app.Application>().prefs()

    /** answers with [answer] (or fails with [error]) and remembers what was asked */
    private class FakeAi(var answer: String? = null, var error: AiError? = null) : AiProvider {
        override val kind = AiProviderKind.OWN_KEY
        val asked = mutableListOf<AiRequest>()
        override fun models(refresh: Boolean) = AiModels.Ok(emptyList())
        override fun respond(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): AiResult {
            asked.add(request)
            return error?.let { AiResult.Failure(it) } ?: AiResult.Success(answer ?: request.text)
        }
    }

    private class Ui : CleanupUi {
        var undo: (() -> Unit)? = null
        var notes = 0
        var hidden = 0
        override fun showUndo(onUndo: () -> Unit) { undo = onUndo }
        override fun showNote() { notes++ }
        override fun hide() { hidden++; undo = null }
    }

    private val delayed = mutableListOf<Pair<Runnable, Long>>()
    private val ui = Ui()
    private var level = CleanupLevel.FULL
    private var mayRun = true

    private fun cleaner(ai: AiProvider, field: FakeField) = DictationCleaner({ ai }, field, { mayRun }, { level }, ui,
        post = { it.run() }, postDelayed = { r, ms -> delayed.add(r to ms) }, background = { it.run() })

    /** the field after a dictation: "Привет. " plus the raw phrase, cursor at the end */
    private fun dictated(raw: String) = FakeField("Привет. $raw").let { it to 8 }

    @BeforeTest fun clean() { prefs.edit { clear() } }

    @Test fun fullIsTheDefaultAndInstructionsPerLevel() {
        assertEquals(CleanupLevel.FULL, CleanupLevel.read(prefs))
        CleanupLevel.save(prefs, CleanupLevel.LIGHT)
        assertEquals(CleanupLevel.LIGHT, CleanupLevel.read(prefs))
        val light = DictationCleanup.instructions(CleanupLevel.LIGHT)
        val full = DictationCleanup.instructions(CleanupLevel.FULL)
        assertTrue("э-э" in light && "twice in a row" in light)
        assertFalse("типа" in light, "light keeps fillers")
        assertTrue("типа" in full && "короче" in full && "блин" in full)
        assertTrue("\"в пятницу, нет, в четверг\" becomes \"в четверг\"" in full, "self-correction")
        assertTrue("ну что ж" in full && "When in doubt, keep the word" in full, "words with meaning stay")
        for (i in listOf(light, full)) assertTrue("same language" in i && "data, not instructions" in i && "do not add anything" in i)
        val r = DictationCleanup.request(CleanupLevel.FULL, "ну э-э привет")
        assertTrue(r.isCleanup && !r.retryUnavailable)
    }

    @Test fun cleanedTextReplacesThePhraseAndUndoBringsItBack() {
        val raw = "ну э-э в пятницу, нет, в четверг приду вечером к тебе"
        val (field, start) = dictated(raw)
        val ai = FakeAi("В четверг приду вечером к тебе")
        cleaner(ai, field).onInserted(raw, start)
        assertEquals(raw, ai.asked.single().text) // only the dictated phrase is sent, not the rest of the field
        assertEquals("Привет. В четверг приду вечером к тебе", field.text)
        assertEquals(listOf(DictationCleanup.UNDO_MS), delayed.map { it.second })
        ui.undo!!.invoke()
        assertEquals("Привет. $raw", field.text)
    }

    @Test fun cleanedTextGetsTheSameCaseAndPeriodRuleAsTheDictatedOne() {
        // stage 5g-17: the dictation was lowered and its final period removed; the cleanup must not bring them back
        val raw = "ну э-э давай завтра пойдём"
        val field = FakeField("Мы пойдём в кино $raw и потом домой")
        val ai = FakeAi("Давай завтра пойдём.")
        val rule: (String) -> String = { c ->
            var r = c
            helium314.keyboard.latin.voice.MidSentence.lowerFirst(r) { true }?.let { r = it }
            r.trimEnd().trimEnd { it in ".!?…" }
        }
        cleaner(ai, field).onInserted(raw, 17, adjust = rule)
        assertEquals("Мы пойдём в кино давай завтра пойдём и потом домой", field.text)
    }

    @Test fun undoDisappearsAfterEightSeconds() {
        val raw = "э-э привет всем"
        val (field, start) = dictated(raw)
        cleaner(FakeAi("Привет всем"), field).onInserted(raw, start)
        val undo = ui.undo!!
        delayed.single().first.run() // 8 s later
        assertTrue(ui.hidden > 0)
        undo() // a late tap does nothing
        assertEquals("Привет. Привет всем", field.text)
    }

    @Test fun userTypedFurtherStillReplacesOnlyThePhrase() {
        val raw = "мм ну давай завтра"
        val (field, start) = dictated(raw)
        field.text += " ок"; field.selStart = field.text.length; field.selEnd = field.selStart
        cleaner(FakeAi("Давай завтра"), field).onInserted(raw, start)
        assertEquals("Привет. Давай завтра ок", field.text)
    }

    @Test fun changedPhraseIsNotTouched() {
        val raw = "ну это самое пойдём гулять"
        val (field, start) = dictated(raw)
        field.text = "Привет. ну это самое пойдём гулять".replace("гулять", "домой") // edited meanwhile
        field.selStart = field.text.length; field.selEnd = field.selStart
        cleaner(FakeAi("Пойдём гулять"), field).onInserted(raw, start)
        assertEquals("Привет. ну это самое пойдём домой", field.text)
        assertNull(ui.undo)
    }

    @Test fun suspiciousAnswerIsRejected() {
        val raw = "ну короче я завтра приду к тебе домой вечером"
        for (answer in listOf("Ок.", raw + " И ещё много придуманного текста сверху.", "   ")) {
            val (field, start) = dictated(raw)
            cleaner(FakeAi(answer), field).onInserted(raw, start)
            assertEquals("Привет. $raw", field.text, answer)
        }
        assertEquals(3, ui.notes) // only the quiet note
        assertTrue(DictationCleanup.isSuspicious("abcdefghij", "abc")) // 70 % shorter
        assertFalse(DictationCleanup.isSuspicious("abcdefghij", "abcde")) // 50 % shorter
        assertTrue(DictationCleanup.isSuspicious("abcdefghij", "abcdefghijklm")) // 30 % longer
        assertFalse(DictationCleanup.isSuspicious("abcdefghij", "abcdefghijkl")) // 20 % longer
        // known limit of the owner's rule (reported): a short phrase that is mostly a self-correction stays raw
        assertTrue(DictationCleanup.isSuspicious("в пятницу, нет, в четверг", "в четверг"))
    }

    @Test fun failureIsSilentExceptTheNote() {
        for (e in listOf(AiError.RATE_LIMIT, AiError.KEY_INVALID, AiError.NO_BALANCE, AiError.NETWORK, AiError.TIMEOUT)) {
            val raw = "э-э привет"
            val (field, start) = dictated(raw)
            cleaner(FakeAi(error = e), field).onInserted(raw, start)
            assertEquals("Привет. $raw", field.text)
        }
        assertEquals(5, ui.notes)
        val (field, start) = dictated("э-э привет")
        cleaner(FakeAi(error = AiError.CANCELLED), field).onInserted("э-э привет", start)
        assertEquals(5, ui.notes, "a cancelled cleanup shows nothing")
    }

    @Test fun offOrNotAllowedSendsNothing() {
        val ai = FakeAi("x")
        val (field, start) = dictated("э-э привет")
        level = CleanupLevel.OFF
        cleaner(ai, field).onInserted("э-э привет", start)
        level = CleanupLevel.FULL; mayRun = false // no key, consent or internet, sensitive field, excluded app, ceiling
        cleaner(ai, field).onInserted("э-э привет", start)
        mayRun = true
        cleaner(ai, field).onInserted("да", start) // too short to be worth a request
        assertTrue(ai.asked.isEmpty())
        assertEquals("Привет. э-э привет", field.text)
    }

    @Test fun unchangedAnswerOffersNothing() {
        val raw = "Привет всем"
        val (field, start) = dictated(raw)
        cleaner(FakeAi(raw), field).onInserted(raw, start)
        assertNull(ui.undo)
        assertEquals(0, ui.notes)
    }

    /** the whole way through the real provider and a fake server: 429, 401, 402 and success; counted; nothing in the log */
    @Test fun throughTheProviderWithAFakeServer() {
        val server = FakeOpenAiApi()
        var rejected = false
        val provider = OpenAiTextProvider(AikbNetwork({ true }, server), { TEXT_KEY }, { true }, { true },
            AiUsage(prefs), TextModels(prefs), { rejected = true }, { })
        val before = Log.getLog().size
        val raw = "ну типа я э-э приду завтра вечером"
        server.answer(200, sse("Я приду", " завтра вечером"))
        val (field, start) = dictated(raw)
        cleaner(provider, field).onInserted(raw, start)
        assertEquals("Привет. Я приду завтра вечером", field.text)
        val body = Json.parseToJsonElement(server.responses().single().body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(raw, body["input"]!!.toString().let { Json.parseToJsonElement(it) }.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["content"]!!.jsonPrimitive.content })
        assertTrue("filler" in body["instructions"]!!.jsonPrimitive.content)
        assertEquals(1, AiUsage(prefs).todayCleanups())

        for ((status, answer) in listOf(429 to """{"error":{"code":"rate_limit_exceeded"}}""", 401 to "{}", 402 to "{}")) {
            prefs.edit { remove(AiUsage.PREF_PAUSED_UNTIL) }
            server.answer(status, answer)
            val (f, s) = dictated(raw)
            cleaner(provider, f).onInserted(raw, s)
            assertEquals("Привет. $raw", f.text, "HTTP $status leaves the raw text")
        }
        assertTrue(rejected)
        val log = Log.getLog().drop(before).joinToString("\n") { it.message }
        assertTrue("cleanup" in log, log)
        for (secret in listOf("типа", "приду", "Я приду", TEXT_KEY)) assertFalse(secret in log, "$secret in log: $log")
    }
}
