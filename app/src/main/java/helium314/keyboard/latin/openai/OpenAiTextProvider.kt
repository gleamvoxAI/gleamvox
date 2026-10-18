// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.Context
import helium314.keyboard.latin.ai.AiError
import helium314.keyboard.latin.ai.AiModel
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiProvider
import helium314.keyboard.latin.ai.AiProviderKind
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.ai.AiText
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.TranscriptionClient
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * ✨ and the cleanup of dictation with the user's own OpenAI API key (docs/AI.md), only through [AikbNetwork]:
 * GET /v1/models and POST /v1/responses with exactly model, instructions, input, store:false, stream:true.
 * Success only on response.completed. The key goes only in the Authorization header, only to these two addresses.
 * Text of requests and answers is never logged or stored; the log gets only states, HTTP status and error codes.
 * error.message of the server is never read (for 401 it holds a part of the key).
 */
class OpenAiTextProvider(
    private val network: AikbNetwork,
    /** the key; null if there is none or it can't be read now */
    private val key: () -> String?,
    /** a key file exists (null from [key] then means: phone locked) */
    private val hasKey: () -> Boolean,
    private val consent: () -> Boolean,
    private val usage: AiUsage,
    private val models: TextModels,
    /** OpenAI answered 401: the key is marked wrong for voice and text until it is checked or replaced */
    private val onKeyRejected: () -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val scheduler: ScheduledExecutorService = timer,
    /** stage 5g-5: OpenAI answered (true) or could not be reached (false), for the card "OpenAI does not answer" */
    private val onReach: (Boolean) -> Unit = {},
) : AiProvider {
    override val kind = AiProviderKind.OWN_KEY

    private class Stop(val failure: AiResult.Failure) : Exception()

    private fun fail(error: AiError, status: Int? = null, code: String? = null, requestId: String? = null): Nothing =
        throw Stop(AiResult.Failure(error, status, code, requestId))

    private fun apiKey(): String = key() ?: fail(if (hasKey()) AiError.PHONE_LOCKED else AiError.NO_KEY)

    override fun models(refresh: Boolean): AiModels {
        if (!refresh && models.isFresh()) return AiModels.Ok(models.cached()!!)
        return try {
            AiModels.Ok(fetchModels())
        } catch (e: Stop) {
            AiModels.Failed(e.failure)
        }
    }

    private fun fetchModels(): List<AiModel> {
        if (!network.isOn()) fail(AiError.INTERNET_OFF)
        val k = apiKey()
        val r = try {
            network.execute(HttpRequest("GET", OpenAi.MODELS_URL, mapOf("Authorization" to "Bearer $k", "Accept" to "application/json")))
        } catch (e: NetworkBlockedException) {
            fail(blocked(e))
        } catch (e: IOException) {
            fail(AiError.NETWORK)
        }
        if (r.status != 200) {
            val (code, type) = TranscriptionClient.errorCodeAndType(r.body)
            fail(onHttpError(r.status, code, type), r.status, code ?: type, r.requestId)
        }
        val list = try { TextModels.parse(r.body.toString(Charsets.UTF_8)) } catch (e: Exception) { fail(AiError.FAILED, 200, "invalid_response", r.requestId) }
        if (list.isEmpty()) fail(AiError.NO_MODEL, 200, null, r.requestId)
        models.save(list)
        Log.i(TAG, "models: ${list.size}")
        return list
    }

    override fun respond(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): AiResult {
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = scheduler.schedule({ timedOut.set(true); cancel.cancel() }, request.totalTimeoutMs, TimeUnit.MILLISECONDS)
        val result = try {
            AiResult.Success(run(request, cancel, onText))
        } catch (e: Stop) {
            if (cancel.isCancelled) AiResult.Failure(if (timedOut.get()) AiError.TIMEOUT else AiError.CANCELLED) else e.failure
        } finally {
            timeout.cancel(false)
        }
        Log.i(TAG, "request: $result") // length or error code, never text
        when {
            result is AiResult.Success -> onReach(true)
            result is AiResult.Failure && (result.error == AiError.NETWORK || result.error == AiError.TIMEOUT) -> onReach(false)
            result is AiResult.Failure && result.httpStatus != null -> onReach(true) // an answer, even an error, means it is reachable
        }
        return result
    }

    private fun run(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): String {
        // nothing is sent while the switch is off, without a key or consent, during a pause or over the daily ceiling
        if (!network.isOn()) fail(AiError.INTERNET_OFF)
        val k = apiKey()
        if (!consent()) fail(AiError.NO_CONSENT)
        usage.pausedError()?.let { fail(it) }
        if (usage.capReached()) fail(AiError.DAILY_CAP)
        if (!models.isFresh()) {
            try { fetchModels() } catch (e: Stop) { if (models.cached().isNullOrEmpty()) throw e } // an older list is still fine
        }
        var model = request.model?.let { AiModel(it, it) } ?: models.current() ?: fail(AiError.NO_MODEL)
        var body = requestBody(model.id, request.instructions, request.text)
        var unavailableRetries = 0
        var modelSwitches = 0
        while (true) {
            if (cancel.isCancelled) fail(AiError.CANCELLED)
            if (usage.capReached()) fail(AiError.DAILY_CAP)
            usage.recordSent(cleanup = request.isCleanup)
            val http = HttpRequest("POST", OpenAi.RESPONSES_URL, mapOf(
                "Authorization" to "Bearer $k",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ), body)
            val stream = try {
                network.stream(http, cancel)
            } catch (e: NetworkBlockedException) {
                fail(blocked(e))
            } catch (e: IOException) {
                fail(if (cancel.isCancelled) AiError.CANCELLED else AiError.NETWORK)
            }
            val next = stream.use {
                if (it.status == 200) return readEvents(it, request, cancel, onText)
                val errorBody = try { readLimited(it.body, 64 * 1024) } catch (e: IOException) { ByteArray(0) }
                val (code, type) = TranscriptionClient.errorCodeAndType(errorBody)
                errorBody.fill(0)
                Log.i(TAG, "responses: HTTP ${it.status} ${code ?: type ?: ""} ${it.requestId ?: ""}")
                if (it.status in 500..599 && request.retryUnavailable && unavailableRetries < RETRY_DELAYS_MS.size) {
                    pause(RETRY_DELAYS_MS[unavailableRetries++], cancel)
                    null
                } else if (it.status == 404 && request.model == null && modelSwitches < MAX_MODEL_SWITCHES) {
                    // the model is gone for this key: silently the next one of the preference list (stage 5zh)
                    models.markUnavailable(model.id)
                    models.current() ?: fail(onHttpError(it.status, code, type), it.status, code ?: type, it.requestId)
                } else {
                    fail(onHttpError(it.status, code, type), it.status, code ?: type, it.requestId)
                }
            }
            if (next != null) {
                modelSwitches++
                Log.i(TAG, "model not available, next one: ${next.id}") // model names are not secret
                model = next
                body = requestBody(model.id, request.instructions, request.text)
            }
        }
    }

    /** the table of docs/AI.md, section 6; also remembers what the next requests must know (key rejected, pauses) */
    private fun onHttpError(status: Int, code: String?, type: String?): AiError {
        val error = errorFor(status, code, type)
        when (error) {
            AiError.KEY_INVALID -> onKeyRejected()
            AiError.NO_BALANCE -> usage.pause(AiError.NO_BALANCE, BALANCE_PAUSE_MS)
            AiError.RATE_LIMIT -> usage.pause(AiError.RATE_LIMIT, RATE_PAUSE_MS)
            AiError.NO_MODEL -> models.invalidate() // the chosen model is gone: read the list again next time
            else -> Unit
        }
        return error
    }

    private fun blocked(e: NetworkBlockedException) = when (e.reason) {
        NetworkBlockedException.Reason.INTERNET_OFF -> AiError.INTERNET_OFF
        NetworkBlockedException.Reason.TEXT_API_OFF -> if (!consent()) AiError.NO_CONSENT else AiError.NO_KEY
        else -> AiError.NETWORK
    }

    /** waits, but stops at once when cancelled */
    private fun pause(ms: Long, cancel: CancelSignal) {
        var left = ms
        while (left > 0) {
            if (cancel.isCancelled) fail(AiError.CANCELLED)
            val step = minOf(left, 100)
            sleep(step)
            left -= step
        }
    }

    private fun readEvents(stream: HttpStream, request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): String {
        val reader = SseReader(stream.body)
        val text = StringBuilder()
        while (true) {
            val event = try {
                reader.next()
            } catch (e: IOException) {
                fail(if (cancel.isCancelled) AiError.CANCELLED else AiError.NETWORK, 200, null, stream.requestId)
            } ?: fail(AiError.INCOMPLETE, 200, "stream_ended", stream.requestId) // no final event: never a success
            if (cancel.isCancelled) fail(AiError.CANCELLED)
            val data = try { Json.parseToJsonElement(event.data).jsonObject } catch (e: Exception) { null }
            when (event.name ?: data?.string("type")) {
                "response.output_text.delta" -> {
                    text.append(data?.string("delta") ?: "")
                    if (text.length > request.maxAnswerChars) {
                        stream.close()
                        fail(AiError.TOO_LONG, 200, null, stream.requestId)
                    }
                    onText(AiText.clean(text.toString()))
                }
                "response.completed" -> {
                    if (text.isEmpty()) data?.let { outputText(it) }?.let { text.append(it) } // no deltas: take the final text
                    if (text.length > request.maxAnswerChars) fail(AiError.TOO_LONG, 200, null, stream.requestId)
                    return AiText.clean(text.toString())
                }
                "response.failed" -> {
                    val error = (data?.get("response") as? JsonObject)?.get("error") as? JsonObject
                    val code = error?.string("code")
                    fail(onStreamError(code), null, code, stream.requestId)
                }
                "response.incomplete" -> fail(AiError.INCOMPLETE, null, "response.incomplete", stream.requestId)
                "error" -> {
                    val code = data?.string("code") ?: (data?.get("error") as? JsonObject)?.string("code")
                    fail(onStreamError(code), null, code, stream.requestId)
                }
                else -> Unit // created, in_progress, output_item.added, …
            }
        }
    }

    private fun onStreamError(code: String?): AiError = when (code) {
        in TranscriptionClient.BALANCE_CODES -> onHttpError(429, code, null)
        "rate_limit_exceeded" -> onHttpError(429, code, null)
        else -> AiError.FAILED
    }

    companion object {
        private const val TAG = "OpenAiText"
        /** "try again later" (5xx): two repeats after 2 and 8 s, only for ✨ requests the user started */
        val RETRY_DELAYS_MS = longArrayOf(2_000, 8_000)
        /** at most this many other models are tried after a 404 within one request */
        const val MAX_MODEL_SWITCHES = 3
        /** no new text request after "no balance" for this long (docs/AI.md, section 6) */
        const val BALANCE_PAUSE_MS = 5 * 60_000L
        /** after "too many requests" */
        const val RATE_PAUSE_MS = 60_000L

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { Thread(it, "aikb-ai-timeout").apply { isDaemon = true } }
        }

        /** the implementation with the saved key of this phone */
        fun get(context: Context): OpenAiTextProvider {
            val app = context.applicationContext
            val prefs = app.prefs()
            return OpenAiTextProvider(AikbNetwork.get(app), { VoiceApi.key(app) }, { VoiceApi.hasKey(app) },
                { AiPrefs.textConsent(prefs) }, AiUsage(prefs), TextModels(prefs), { VoiceApiPrefs.setKeyRejected(prefs, true) },
                onReach = { if (it) OpenAiReach.ok(prefs) else OpenAiReach.problem(prefs) })
        }

        /** exactly the five fields; the role and rules go into instructions, never a system message; no other field */
        fun requestBody(model: String, instructions: String, text: String): ByteArray = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", text) }) })
            put("store", false)
            put("stream", true)
        }.toString().toByteArray(Charsets.UTF_8)

        /** docs/AI.md, section 6 (the same codes as voice, docs/VOICE_API.md section 5) */
        fun errorFor(status: Int, code: String?, type: String?): AiError = when {
            status == 401 -> AiError.KEY_INVALID
            status == 402 -> AiError.NO_BALANCE
            status == 429 && (code in TranscriptionClient.BALANCE_CODES || type in TranscriptionClient.BALANCE_CODES) -> AiError.NO_BALANCE
            status == 429 -> AiError.RATE_LIMIT
            status == 403 -> AiError.REGION_BLOCKED
            status == 404 -> AiError.NO_MODEL
            status in 500..599 -> AiError.UNAVAILABLE
            else -> AiError.FAILED
        }

        /** the text of response.completed: response.output[].content[] with type output_text */
        private fun outputText(data: JsonObject): String? {
            val output = (data["response"] as? JsonObject)?.get("output") as? JsonArray ?: return null
            val parts = output.flatMap { item -> ((item as? JsonObject)?.get("content") as? JsonArray).orEmpty() }
                .mapNotNull { (it as? JsonObject)?.takeIf { c -> c.string("type") == "output_text" }?.string("text") }
            return parts.joinToString("").ifEmpty { null }
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun readLimited(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < max) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, minOf(n, max - out.size()))
            }
            return out.toByteArray()
        }
    }
}
