// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.Endpoint
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.HttpResponse
import helium314.keyboard.latin.openai.HttpTransport
import helium314.keyboard.latin.openai.NetworkBlockedException
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.TranscriptionClient
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val TEST_KEY = "sk-proj-TESTKEY1234567890abcdefXYZW"

/** a fake api.openai.com: records requests (and a copy of the body as sent) and answers with a fixed response */
class FakeVoiceServer(var status: Int = 200, var answer: String = """{"text":"Привет, как дела?","languages":[{"code":"ru"}],"usage":{"type":"duration","seconds":2.5}}""") : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    val bodiesAsSent = mutableListOf<ByteArray>()
    override fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        request.body?.let { bodiesAsSent.add(it.copyOf()) }
        return HttpResponse(status, mapOf("x-request-id" to "req_1"), answer.toByteArray())
    }
}

/** [seconds] of a 440 Hz tone, loud enough to be speech */
fun toneBuffer(seconds: Double = 1.0, amplitude: Int = 8000): PcmBuffer {
    val b = PcmBuffer()
    val n = (seconds * PcmBuffer.SAMPLE_RATE).toInt()
    val samples = ShortArray(n) { (amplitude * sin(2 * PI * 440 * it / PcmBuffer.SAMPLE_RATE)).toInt().toShort() }
    b.append(samples, n)
    return b
}

/** the parts of a multipart body: name to (headers, content) */
fun multipartParts(body: ByteArray, boundary: String): Map<String, Pair<String, ByteArray>> {
    val text = String(body, Charsets.ISO_8859_1) // one char per byte, so offsets match
    val parts = text.split("--$boundary").drop(1).dropLast(1)
    return parts.associate { p ->
        val headerEnd = p.indexOf("\r\n\r\n")
        val headers = p.substring(2, headerEnd)
        val content = p.substring(headerEnd + 4, p.length - 2)
        val name = Regex("name=\"([^\"]+)\"").find(headers)!!.groupValues[1]
        name to (headers to content.toByteArray(Charsets.ISO_8859_1))
    }
}

@RunWith(RobolectricTestRunner::class)
class TranscriptionClientTest {
    private fun voiceNetwork(server: FakeVoiceServer, internet: Boolean = true, mode: Boolean = true) =
        AikbNetwork({ internet }, server, { it != Endpoint.TRANSCRIPTIONS || mode })

    private fun client(server: FakeVoiceServer, internet: Boolean = true, mode: Boolean = true) =
        TranscriptionClient(voiceNetwork(server, internet, mode), { TEST_KEY }, { "BOUNDARY42" })

    @Test fun multipartRequest() {
        val server = FakeVoiceServer()
        val r = client(server).transcribe(toneBuffer(1.0), "ru-RU", CancelSignal())
        assertIs<TranscriptionResult.Ok>(r)
        assertEquals("Привет, как дела?", r.text)
        assertEquals(2.5, r.billedSeconds)
        val req = server.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", req.url)
        assertEquals("Bearer $TEST_KEY", req.headers["Authorization"])
        assertEquals("multipart/form-data; boundary=BOUNDARY42", req.headers["Content-Type"])
        val parts = multipartParts(server.bodiesAsSent.single(), "BOUNDARY42")
        // exactly these fields: no text of the field, no contact names, no keywords, nothing else
        assertEquals(listOf("model", "languages[]", "prompt", "file"), parts.keys.toList())
        assertEquals("gpt-transcribe", parts["model"]!!.second.toString(Charsets.UTF_8))
        assertEquals("ru", parts["languages[]"]!!.second.toString(Charsets.UTF_8))
        assertEquals(TranscriptionClient.prompt("ru"), parts["prompt"]!!.second.toString(Charsets.UTF_8))
        val file = parts["file"]!!
        assertTrue(file.first.contains("filename=\"speech.wav\"") && file.first.contains("Content-Type: audio/wav"), file.first)
        val wav = file.second
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(44 + 32_000, wav.size) // header + 1 s of 16 kHz PCM16
        assertEquals(16_000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
    }

    @Test fun promptHasNoContactNamesOrFieldText() {
        // the prompt is fixed per language: nothing from the phone gets into it
        listOf("ru", "en", "de", null).forEach { l ->
            val p = TranscriptionClient.prompt(l)
            assertFalse(p.contains("@") || p.contains("Иван") || p.contains("Ivan"), p)
        }
        assertEquals(TranscriptionClient.prompt("ru"), TranscriptionClient.prompt("ru"))
        val fields = TranscriptionClient.formFields("en-US").map { it.first }
        assertEquals(listOf("model", "languages[]", "prompt"), fields)
    }

    @Test fun requestBodyIsWipedAfterSending() {
        val server = FakeVoiceServer()
        client(server).transcribe(toneBuffer(), "en", CancelSignal())
        assertTrue(server.requests.single().body!!.all { it == 0.toByte() })
        assertTrue(server.bodiesAsSent.single().any { it != 0.toByte() })
    }

    @Test fun languageCodes() {
        assertEquals("ru", TranscriptionClient.languageCode("ru-RU"))
        assertEquals("en", TranscriptionClient.languageCode("en_US"))
        assertEquals("he", TranscriptionClient.languageCode("iw"))
        assertEquals("id", TranscriptionClient.languageCode("in"))
        assertNull(TranscriptionClient.languageCode("fil")) // no two letter code: OpenAI detects the language
        assertNull(TranscriptionClient.languageCode(""))
        assertFalse(TranscriptionClient.formFields("fil").any { it.first == "languages[]" })
    }

    private fun failure(status: Int, answer: String): TranscriptionResult.Failed {
        val r = client(FakeVoiceServer(status, answer)).transcribe(toneBuffer(), "ru", CancelSignal())
        assertIs<TranscriptionResult.Failed>(r)
        return r
    }

    @Test fun errorTable() {
        val keyInMessage = """{"error":{"message":"Incorrect API key provided: $TEST_KEY. You can find your API key at ...","type":"invalid_request_error","code":"invalid_api_key"}}"""
        val r401 = failure(401, keyInMessage)
        assertEquals(VoiceApiProblem.KEY_INVALID, r401.problem)
        assertEquals("invalid_api_key", r401.code)
        assertFalse(r401.details().contains(TEST_KEY) || r401.details().contains("TESTKEY"), r401.details())
        assertFalse(r401.details().contains("Incorrect"), r401.details()) // error.message is never kept
        assertEquals(VoiceApiProblem.NO_BALANCE, failure(402, "{}").problem)
        assertEquals(VoiceApiProblem.NO_BALANCE, failure(429, """{"error":{"type":"insufficient_quota","code":"insufficient_quota"}}""").problem)
        assertEquals(VoiceApiProblem.NO_BALANCE, failure(429, """{"error":{"type":"requests","code":"credit_balance_exhausted"}}""").problem)
        assertEquals(VoiceApiProblem.RATE_LIMIT, failure(429, """{"error":{"type":"requests","code":"rate_limit_exceeded"}}""").problem)
        assertEquals(VoiceApiProblem.TOO_LARGE, failure(413, "too large").problem)
        assertEquals(VoiceApiProblem.BAD_REQUEST, failure(400, """{"error":{"type":"invalid_request_error","code":"unsupported_language"}}""").problem)
        assertEquals(VoiceApiProblem.FORBIDDEN, failure(403, "{}").problem)
        assertEquals(VoiceApiProblem.SERVER, failure(503, "{}").problem)
        assertEquals(VoiceApiProblem.BAD_ANSWER, failure(200, "not json").problem)
        // a code with strange characters is dropped, not shown
        assertNull(failure(400, """{"error":{"code":"x y <script>","type":"t"}}""").code)
    }

    @Test fun internetOffOrModeOffSendsNothing() {
        val s1 = FakeVoiceServer()
        assertEquals(VoiceApiProblem.INTERNET_OFF, (client(s1, internet = false).transcribe(toneBuffer(), "ru", CancelSignal()) as TranscriptionResult.Failed).problem)
        val s2 = FakeVoiceServer()
        assertEquals(VoiceApiProblem.MODE_OFF, (client(s2, mode = false).transcribe(toneBuffer(), "ru", CancelSignal()) as TranscriptionResult.Failed).problem)
        assertTrue(s1.requests.isEmpty() && s2.requests.isEmpty())
        // the key check is GET /v1/models: it works without the voice mode (the key is also for ✨)
        assertIs<TranscriptionResult.Ok>(client(FakeVoiceServer(200, """{"object":"list","data":[]}"""), mode = false).checkKey())
    }

    @Test fun noKeyNoRequest() {
        val s = FakeVoiceServer()
        val r = TranscriptionClient(voiceNetwork(s), { null }).transcribe(toneBuffer(), "ru", CancelSignal())
        assertEquals(VoiceApiProblem.NO_KEY, (r as TranscriptionResult.Failed).problem)
        assertTrue(s.requests.isEmpty())
    }

    @Test fun checkKey() {
        val s = FakeVoiceServer(200, """{"object":"list","data":[{"id":"gpt-transcribe","object":"model"},{"id":"gpt-4.1-nano","object":"model"}]}""")
        var ids: List<String>? = null
        assertIs<TranscriptionResult.Ok>(client(s).checkKey { ids = it })
        assertEquals(listOf("gpt-transcribe", "gpt-4.1-nano"), ids)
        assertEquals("GET", s.requests.single().method)
        assertEquals("https://api.openai.com/v1/models", s.requests.single().url)
        assertEquals("Bearer $TEST_KEY", s.requests.single().headers["Authorization"])
        assertNull(s.requests.single().body)
        s.status = 401
        assertEquals(VoiceApiProblem.KEY_INVALID, (client(s).checkKey() as TranscriptionResult.Failed).problem)
        s.status = 200; s.answer = "not a list"
        assertEquals(VoiceApiProblem.BAD_ANSWER, (client(s).checkKey() as TranscriptionResult.Failed).problem)
    }

    @Test fun logHasNoKeyAndNoText() {
        val before = Log.getLog().size
        client(FakeVoiceServer()).transcribe(toneBuffer(), "ru", CancelSignal())
        client(FakeVoiceServer(401, """{"error":{"message":"Incorrect API key provided: $TEST_KEY"}}""")).transcribe(toneBuffer(), "ru", CancelSignal())
        client(FakeVoiceServer()).checkKey()
        val lines = Log.getLog().drop(before).joinToString("\n") { it.message }
        assertTrue(lines.contains("transcription"), lines)
        assertFalse(lines.contains("sk-") || lines.contains("TESTKEY") || lines.contains("Привет"), lines)
    }

    @Test fun cancelledRequest() {
        val cancel = CancelSignal()
        val server = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse { cancel.cancel(); throw java.io.IOException("closed") }
        }
        val r = TranscriptionClient(AikbNetwork({ true }, server), { TEST_KEY }).transcribe(toneBuffer(), "ru", cancel)
        assertEquals(VoiceApiProblem.CANCELLED, (r as TranscriptionResult.Failed).problem)
    }
}

/** the voice request through the single client (docs/AI.md, section 1): exact path and method, only while voice is on */
class VoiceAllowListTest {
    private class Counting : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse { sent.add(request); return HttpResponse(200, emptyMap(), ByteArray(0)) }
    }

    @Test fun voiceAddressIsExact() {
        assertEquals(Endpoint.TRANSCRIPTIONS, Endpoint.of("POST", OpenAi.TRANSCRIPTIONS_URL))
        listOf(
            "GET" to OpenAi.TRANSCRIPTIONS_URL, // wrong method
            "GET" to "https://api.openai.com/v1/models/gpt-transcribe", // the old key check is not allowed any more
            "POST" to "https://api.openai.com/v1/audio/translations",
            "POST" to "https://api.openai.com/v1/audio/transcriptions?x=1",
            "POST" to "https://api.openai.com/v1/audio/transcriptions/",
            "POST" to "https://api.openai.com:443/v1/audio/transcriptions",
            "POST" to "http://api.openai.com/v1/audio/transcriptions",
            "POST" to "https://api.openai.com.evil.example/v1/audio/transcriptions",
            "GET" to "https://api.openai.com/v1/models/gpt-4o",
        ).forEach { (m, u) -> assertEquals(null, Endpoint.of(m, u), "$m $u") }
    }

    @Test fun voiceModeOffSendsNothing() {
        val t = Counting()
        val e = assertFailsWith<NetworkBlockedException> {
            AikbNetwork({ true }, t, { it != Endpoint.TRANSCRIPTIONS }).execute(HttpRequest("POST", OpenAi.TRANSCRIPTIONS_URL))
        }
        assertEquals(NetworkBlockedException.Reason.VOICE_API_OFF, e.reason)
        val off = assertFailsWith<NetworkBlockedException> {
            AikbNetwork({ false }, t).execute(HttpRequest("POST", OpenAi.TRANSCRIPTIONS_URL))
        }
        assertEquals(NetworkBlockedException.Reason.INTERNET_OFF, off.reason)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun redirectIsAnError() {
        val t = object : HttpTransport { override fun execute(request: HttpRequest) = HttpResponse(307, emptyMap(), ByteArray(0)) }
        val e = assertFailsWith<NetworkBlockedException> { AikbNetwork({ true }, t).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        assertEquals(NetworkBlockedException.Reason.REDIRECT, e.reason)
    }
}
