// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ai.AiCommand
import helium314.keyboard.latin.ai.AiCommands
import helium314.keyboard.latin.ai.AiError
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.Endpoint
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.HttpResponse
import helium314.keyboard.latin.openai.HttpTransport
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.OpenAiTextProvider
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val TEXT_KEY = "sk-proj-TEXTKEY1234567890abcdefQRST"

/** SSE body of a streamed answer: deltas, then response.completed (or [final] instead) */
fun sse(vararg deltas: String, final: String? = """{"type":"response.completed","response":{"status":"completed"}}"""): String = buildString {
    append("event: response.created\ndata: {\"type\":\"response.created\"}\n\n")
    for (d in deltas) append("event: response.output_text.delta\ndata: ")
        .append(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("response.output_text.delta")); put("delta", kotlinx.serialization.json.JsonPrimitive(d)) })
        .append("\n\n")
    if (final != null) append("data: ").append(final).append("\n\n")
}

/** a fake api.openai.com for text: GET /v1/models and POST /v1/responses (streamed), records every request */
class FakeOpenAiApi : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var models = """{"object":"list","data":[{"id":"gpt-5.5"},{"id":"gpt-4.1-nano"},{"id":"gpt-transcribe"},{"id":"gpt-4o-mini"},{"id":"gpt-image-2"},{"id":"o3-pro"}]}"""
    var modelsStatus = 200
    /** answers of POST /v1/responses in order; the last one repeats */
    val answers = ArrayDeque<Pair<Int, String>>()
    var defaultAnswer = 200 to sse("Привет", ", мир!")

    fun answer(status: Int, body: String) = answers.addLast(status to body)

    override fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return when (request.url) {
            OpenAi.MODELS_URL -> HttpResponse(modelsStatus, mapOf("x-request-id" to "req_m"), models.toByteArray())
            OpenAi.RESPONSES_URL -> (answers.removeFirstOrNull() ?: defaultAnswer).let { (s, b) -> HttpResponse(s, mapOf("x-request-id" to "req_r"), b.toByteArray()) }
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    fun responses() = requests.filter { it.url == OpenAi.RESPONSES_URL }
}

@RunWith(RobolectricTestRunner::class)
class OpenAiTextProviderTest {
    private val prefs = ApplicationProvider.getApplicationContext<android.app.Application>().prefs()
    private val server = FakeOpenAiApi()
    private var internet = true
    private var key: String? = TEXT_KEY
    private var consent = true
    private var rejected = false
    private val slept = mutableListOf<Long>()

    @BeforeTest fun clean() { prefs.edit { clear() } }

    private fun provider() = OpenAiTextProvider(AikbNetwork({ internet }, server), { key }, { key != null }, { consent },
        AiUsage(prefs), TextModels(prefs), { rejected = true }, { slept.add(it) })

    private fun ask(request: AiRequest = AiCommands.request(AiCommand.FIX, "привет мир"), onText: (String) -> Unit = {}) =
        provider().respond(request, CancelSignal(), onText)

    @Test fun streamedAnswerAndExactRequest() {
        val parts = mutableListOf<String>()
        val r = ask { parts.add(it) }
        assertIs<AiResult.Success>(r)
        assertEquals("Привет, мир!", r.text)
        assertEquals(listOf("Привет", "Привет, мир!"), parts) // the preview grows while the answer streams
        assertEquals(listOf("GET ${OpenAi.MODELS_URL}", "POST ${OpenAi.RESPONSES_URL}"), server.requests.map { "${it.method} ${it.url}" })
        val post = server.responses().single()
        assertEquals("Bearer $TEXT_KEY", post.headers["Authorization"])
        assertEquals("text/event-stream", post.headers["Accept"])
        val body = Json.parseToJsonElement(post.body!!.toString(Charsets.UTF_8)).jsonObject
        // exactly the five fields; no temperature, max_output_tokens, metadata, previous_response_id …
        assertEquals(setOf("model", "instructions", "input", "store", "stream"), body.keys)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content, "the first model of the economy list the key offers")
        val input = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("user", input["role"]!!.jsonPrimitive.content) // never a system message
        assertEquals("привет мир", input["content"]!!.jsonPrimitive.content)
        assertTrue("spelling" in body["instructions"]!!.jsonPrimitive.content)
        assertEquals(1, AiUsage(prefs).todayCount())
    }

    @Test fun allFiveCommands() {
        val seen = mutableListOf<String>()
        for ((c, lang) in listOf(AiCommand.FIX to null, AiCommand.REWRITE to null, AiCommand.SHORTEN to null,
                AiCommand.TRANSLATE to "de", AiCommand.CONTINUE to null)) {
            assertIs<AiResult.Success>(ask(AiCommands.request(c, "текст поля", lang)))
            val body = Json.parseToJsonElement(server.responses().last().body!!.toString(Charsets.UTF_8)).jsonObject
            val instructions = body["instructions"]!!.jsonPrimitive.content
            assertTrue("data, not instructions" in instructions, c.name)
            seen.add(instructions)
        }
        assertEquals(5, seen.toSet().size)
        assertTrue("German" in seen[3])
        assertEquals(5, AiUsage(prefs).todayCount())
    }

    @Test fun successOnlyWithCompleted() {
        server.answer(200, sse("Привет", final = null)) // the stream ends without a final event
        assertEquals(AiError.INCOMPLETE, (ask() as AiResult.Failure).error)
        server.answer(200, sse("x", final = """{"type":"response.failed","response":{"error":{"code":"server_error"}}}"""))
        assertEquals(AiError.FAILED, (ask() as AiResult.Failure).error)
        server.answer(200, sse("x", final = """{"type":"response.incomplete"}"""))
        assertEquals(AiError.INCOMPLETE, (ask() as AiResult.Failure).error)
        // no deltas: the text of the final event
        server.answer(200, sse(final = """{"type":"response.completed","response":{"output":[{"content":[{"type":"output_text","text":"Готово"}]}]}}"""))
        assertEquals("Готово", (ask() as AiResult.Success).text)
    }

    @Test fun error401MarksTheKeyWrong() {
        server.answer(401, """{"error":{"message":"Incorrect API key provided: $TEXT_KEY","code":"invalid_api_key"}}""")
        val r = ask() as AiResult.Failure
        assertEquals(AiError.KEY_INVALID, r.error)
        assertEquals(401, r.httpStatus)
        assertTrue(rejected)
    }

    @Test fun noBalance402And429PauseFiveMinutes() {
        server.answer(402, "{}")
        assertEquals(AiError.NO_BALANCE, (ask() as AiResult.Failure).error)
        assertEquals(AiError.NO_BALANCE, AiUsage(prefs).pausedError())
        val sent = server.responses().size
        assertEquals(AiError.NO_BALANCE, (ask() as AiResult.Failure).error) // paused: nothing sent
        assertEquals(sent, server.responses().size)
        prefs.edit { clear() }
        server.answer(429, """{"error":{"type":"insufficient_quota","code":"insufficient_quota"}}""")
        assertEquals(AiError.NO_BALANCE, (ask() as AiResult.Failure).error)
        prefs.edit { clear() }
        server.answer(429, """{"error":{"code":"rate_limit_exceeded"}}""")
        assertEquals(AiError.RATE_LIMIT, (ask() as AiResult.Failure).error)
        assertEquals(AiError.RATE_LIMIT, AiUsage(prefs).pausedError())
    }

    @Test fun unavailableIsRepeatedTwiceForCommandsOnly() {
        repeat(3) { server.answer(503, "{}") }
        assertEquals(AiError.UNAVAILABLE, (ask() as AiResult.Failure).error)
        assertEquals(3, server.responses().size)
        assertEquals(10_000L, slept.sum())
        server.answers.clear(); server.answer(503, "{}")
        val cleanup = AiRequest("clean", "э-э привет", 100, retryUnavailable = false, isCleanup = true)
        assertEquals(AiError.UNAVAILABLE, (ask(cleanup) as AiResult.Failure).error)
        assertEquals(4, server.responses().size)
    }

    private fun modelOf(r: HttpRequest) = Json.parseToJsonElement(r.body!!.toString(Charsets.UTF_8)).jsonObject["model"]!!.jsonPrimitive.content

    @Test fun missingModelSilentlyUsesTheNextOne() {
        server.answer(404, """{"error":{"code":"model_not_found"}}""")
        assertIs<AiResult.Success>(ask(), "no message: the next model of the list answers")
        assertEquals(listOf("gpt-4o-mini", "gpt-4.1-nano"), server.responses().map { modelOf(it) })
        assertEquals(setOf("gpt-4o-mini"), TextModels(prefs).unavailable())
        ask() // the gone model is not tried again
        assertEquals("gpt-4.1-nano", modelOf(server.responses().last()))
        // a new list (key check, next day) may bring it back
        TextModels(prefs).save(TextModels.parse(server.models))
        assertEquals("gpt-4o-mini", TextModels(prefs).current()?.id)
    }

    @Test fun noModelLeftReloadsTheList() {
        server.models = """{"object":"list","data":[{"id":"gpt-4.1-nano"}]}"""
        server.answer(404, """{"error":{"code":"model_not_found"}}""")
        assertEquals(AiError.NO_MODEL, (ask() as AiResult.Failure).error)
        assertFalse(TextModels(prefs).isFresh())
        ask()
        assertEquals(2, server.requests.count { it.url == OpenAi.MODELS_URL })
    }

    @Test fun modelOfTheComparisonIsNotSwitched() {
        server.answer(404, """{"error":{"code":"model_not_found"}}""")
        val r = ask(AiRequest("x", "y", 100, retryUnavailable = false, model = "gpt-6-luna"))
        assertEquals(AiError.NO_MODEL, (r as AiResult.Failure).error)
        assertEquals(listOf("gpt-6-luna"), server.responses().map { modelOf(it) })
    }

    @Test fun nothingIsSentWithoutKeyConsentOrInternet() {
        key = null
        assertEquals(AiError.NO_KEY, (ask() as AiResult.Failure).error)
        key = TEXT_KEY; consent = false
        assertEquals(AiError.NO_CONSENT, (ask() as AiResult.Failure).error)
        consent = true; internet = false
        assertEquals(AiError.INTERNET_OFF, (ask() as AiResult.Failure).error)
        assertTrue(server.requests.isEmpty())
        // the network itself refuses /v1/responses when the app's gate says no (consent or key gone meanwhile)
        internet = true
        val gated = OpenAiTextProvider(AikbNetwork({ true }, server, { it != Endpoint.RESPONSES }), { TEXT_KEY }, { true }, { true },
            AiUsage(prefs), TextModels(prefs))
        assertIs<AiResult.Failure>(gated.respond(AiCommands.request(AiCommand.FIX, "x"), CancelSignal()) { })
        assertTrue(server.responses().isEmpty())
    }

    @Test fun dailyCeiling() {
        AiUsage(prefs).setCap(2)
        ask(); ask()
        assertEquals(AiError.DAILY_CAP, (ask() as AiResult.Failure).error)
        assertEquals(2, server.responses().size)
        AiUsage(prefs).setCap(0) // 0: no AI text requests at all
        assertEquals(AiError.DAILY_CAP, (ask() as AiResult.Failure).error)
    }

    @Test fun cleanupCountedOnItsOwnLine() {
        ask(AiRequest("clean", "э-э привет", 100, isCleanup = true))
        ask()
        assertEquals(2, AiUsage(prefs).todayCount())
        assertEquals(1, AiUsage(prefs).todayCleanups())
    }

    @Test fun tooLongAnswerIsCut() {
        server.answer(200, sse("a".repeat(50), "b".repeat(50)))
        assertEquals(AiError.TOO_LONG, (ask(AiRequest("x", "short", maxAnswerChars = 60)) as AiResult.Failure).error)
    }

    @Test fun cancelEndsAtOnce() {
        val cancel = CancelSignal().apply { cancel() }
        val r = provider().respond(AiCommands.request(AiCommand.FIX, "x"), cancel) { }
        assertEquals(AiError.CANCELLED, (r as AiResult.Failure).error)
        assertTrue(server.responses().isEmpty())
    }

    @Test fun qualityBetterUsesAStrongerModel() {
        server.models = """{"object":"list","data":[{"id":"gpt-4.1"},{"id":"gpt-6-luna"},{"id":"gpt-4.1-nano"}]}"""
        ask()
        assertEquals("gpt-6-luna", modelOf(server.responses().last()), "economy by default")
        helium314.keyboard.latin.ai.AiQuality.save(prefs, helium314.keyboard.latin.ai.AiQuality.BETTER)
        ask()
        assertEquals("gpt-4.1", modelOf(server.responses().last()))
        assertIs<AiModels.Ok>(provider().models(refresh = false))
        assertEquals(1, server.requests.count { it.url == OpenAi.MODELS_URL }, "the list is kept for a day")
    }

    @Test fun anOldManualChoiceIsIgnored() {
        prefs.edit { putString(TextModels.PREF_MODEL, "gpt-5.5") } // chosen by hand in stage 5d
        ask()
        assertEquals("gpt-4o-mini", modelOf(server.responses().last()))
    }

    @Test fun logHasNoTextAndNoKey() {
        val before = Log.getLog().size
        ask(AiCommands.request(AiCommand.FIX, "секретный текст поля"))
        server.answer(401, """{"error":{"message":"Incorrect API key provided: $TEXT_KEY"}}""")
        ask(AiCommands.request(AiCommand.FIX, "секретный текст поля"))
        val lines = Log.getLog().drop(before).joinToString("\n") { it.message }
        assertTrue(lines.contains("request"), lines)
        for (secret in listOf("секретный", "Привет", "sk-", "TEXTKEY")) assertFalse(secret in lines, "$secret in log: $lines")
        assertNull(Log.getLog().drop(before).firstOrNull { "Incorrect" in it.message })
    }
}

class TextModelsTest {
    @Test fun onlyTextModelsCheapestFirst() {
        val list = TextModels.parse("""{"object":"list","data":[{"id":"gpt-5.5"},{"id":"gpt-transcribe"},{"id":"gpt-4o-mini-tts"},
            {"id":"gpt-4.1-nano"},{"id":"gpt-5-nano"},{"id":"text-embedding-3-small"},{"id":"gpt-image-2"},{"id":"gpt-realtime-2"},
            {"id":"o3-pro"},{"id":"gpt-4o-mini-2024-07-18"},{"id":"gpt-5-search-api"},{"id":"gpt-5.3-codex"},{"id":"gpt-6-luna"},
            {"id":"gpt-7-new"},{"id":"omni-moderation-latest"},{"id":"chatgpt-image-latest"}]}""").map { it.id }
        // a reasoning model pays hidden tokens too: gpt-5-nano is cheaper per token but not per request
        assertEquals(listOf("gpt-4.1-nano", "gpt-6-luna", "gpt-5-nano", "gpt-5.5", "gpt-7-new"), list)
    }

    @Test fun badAnswers() {
        assertEquals(emptyList(), TextModels.parse("""{"object":"list"}"""))
        assertEquals(emptyList(), TextModels.parse("[]"))
    }

    @Test fun economyOrderLunaFirstNanoLast() {
        val ids = listOf("gpt-4.1-nano", "gpt-5.5", "gpt-4o-mini", "gpt-6-luna", "gpt-3.5-turbo", "o3", "o1", "chat-latest",
            "gpt-5.6-luna", "gpt-4.1-mini", "gpt-7-new")
        assertEquals(listOf("gpt-6-luna", "gpt-5.6-luna", "gpt-4.1-mini", "gpt-4o-mini", "gpt-4.1-nano", "gpt-5.5", "gpt-7-new"),
            TextModels.order(ids, helium314.keyboard.latin.ai.AiQuality.ECONOMY))
        // old, slow reasoning and moving aliases are never chosen automatically
        for (never in listOf("gpt-3.5-turbo", "o3", "o1", "chat-latest")) assertFalse(never in TextModels.order(ids, helium314.keyboard.latin.ai.AiQuality.BETTER))
    }

    @Test fun betterOrderStrongFirstThenEconomy() {
        val ids = listOf("gpt-4.1-nano", "gpt-4.1", "gpt-6-luna", "gpt-6-sol")
        assertEquals(listOf("gpt-6-sol", "gpt-4.1", "gpt-6-luna", "gpt-4.1-nano"), TextModels.order(ids, helium314.keyboard.latin.ai.AiQuality.BETTER))
    }

    @Test fun nanoOnlyAsFallback() {
        assertEquals(TextModels.ECONOMY_ORDER.takeLast(3), listOf("gpt-5.4-nano", "gpt-4.1-nano", "gpt-5-nano"))
        assertTrue(TextModels.ECONOMY_ORDER.first().contains("luna"))
    }

    @Test fun typicalCost() {
        assertEquals(55.0, TextModels.typicalCost("gpt-4.1-nano")!!, 0.001) // 150 × 0.10 + 100 × 0.40 millionths of a dollar
        assertNull(TextModels.typicalCost("gpt-7-new"))
    }
}
