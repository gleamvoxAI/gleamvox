// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.NetworkBlockedException
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.utils.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Locale

/** Why OpenAI is not used now, or what went wrong (docs/VOICE_API.md, sections 3 and 5) */
enum class VoiceApiProblem {
    // known before recording
    INTERNET_OFF, NO_KEY, KEY_REJECTED, CAP_REACHED, PAUSED_BALANCE, PAUSED_RATE, LOCKED,
    // answers and failures of a request
    KEY_INVALID, NO_BALANCE, RATE_LIMIT, TOO_LARGE, BAD_REQUEST, FORBIDDEN, MODEL_NOT_AVAILABLE, SERVER, NETWORK,
    MODE_OFF, BAD_ANSWER, CANCELLED,
}

sealed class TranscriptionResult {
    /** [billedSeconds] from the answer ("usage" of type "duration") if OpenAI sent it */
    class Ok(val text: String, val billedSeconds: Double?) : TranscriptionResult()

    /**
     * [code] and [type] are error.code and error.type of the answer (letters, digits, _ . - only). error.message is never
     * kept: for 401 OpenAI puts a part of the key into it.
     */
    class Failed(val problem: VoiceApiProblem, val status: Int? = null, val code: String? = null, val type: String? = null) : TranscriptionResult() {
        /** for the screen and "Copy details"; nothing secret */
        fun details(): String = listOfNotNull(problem.name.lowercase(), status?.let { "HTTP $it" }, code, type).joinToString(", ")
    }
}

/**
 * POST /v1/audio/transcriptions with gpt-transcribe (docs/VOICE_API.md, section 1) and the key check (GET /v1/models),
 * through [AikbNetwork]. Sends only the sound, the language of the layout and a fixed
 * prompt; never text of the field, contact names or anything else. Never logs the key, the sound or the text.
 */
class TranscriptionClient(
    private val network: AikbNetwork,
    private val key: () -> String?,
    private val boundary: () -> String = ::randomBoundary,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /**
     * [context]: continuous dictation only (stage 5g), the end of what was dictated before in the same session; never text
     * of the field, never contact names (the caller has nothing else to give).
     */
    fun transcribe(audio: PcmBuffer, languageTag: String, cancel: CancelSignal, onSent: () -> Unit = {}, context: String? = null): TranscriptionResult {
        val apiKey = key() ?: return TranscriptionResult.Failed(VoiceApiProblem.NO_KEY)
        val b = boundary()
        val body = WavMultipart.build(b, formFields(languageTag, context), audio)
        log("transcription: ${audio.durationMs} ms of sound, ${body.size} bytes")
        try {
            val request = HttpRequest("POST", OpenAi.TRANSCRIPTIONS_URL, mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "multipart/form-data; boundary=$b",
                "Accept" to "application/json",
            ), body, onSent)
            network.stream(request, cancel).use { response ->
                val answer = readLimited(response.body)
                log("transcription answer: HTTP ${response.status}")
                if (response.status != 200) return failure(response.status, answer)
                return parseOk(answer)
            }
        } catch (e: NetworkBlockedException) {
            log("transcription blocked: ${e.reason}")
            return TranscriptionResult.Failed(blockedProblem(e))
        } catch (e: IOException) {
            if (cancel.isCancelled) return TranscriptionResult.Failed(VoiceApiProblem.CANCELLED)
            log("transcription failed: ${e.javaClass.simpleName}")
            return TranscriptionResult.Failed(VoiceApiProblem.NETWORK)
        } finally {
            body.fill(0) // the sound inside the request is gone right after sending
        }
    }

    /**
     * "Check key": GET /v1/models with the key; checks the key, transcribes nothing and costs nothing. [onModels] gets
     * the ids of all models of the key (to see whether gpt-transcribe is there and to keep the text models).
     */
    fun checkKey(onModels: (List<String>) -> Unit = {}): TranscriptionResult {
        val apiKey = key() ?: return TranscriptionResult.Failed(VoiceApiProblem.NO_KEY)
        return try {
            val r = network.execute(HttpRequest("GET", OpenAi.MODELS_URL, mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")))
            log("key check: HTTP ${r.status}")
            if (r.status != 200) return failure(r.status, r.body)
            val ids = modelIds(r.body)
            r.body.fill(0)
            if (ids == null) TranscriptionResult.Failed(VoiceApiProblem.BAD_ANSWER, 200)
            else { onModels(ids); TranscriptionResult.Ok("", null) }
        } catch (e: NetworkBlockedException) {
            TranscriptionResult.Failed(blockedProblem(e))
        } catch (e: IOException) {
            log("key check failed: ${e.javaClass.simpleName}")
            TranscriptionResult.Failed(VoiceApiProblem.NETWORK)
        }
    }

    private fun parseOk(answer: ByteArray): TranscriptionResult = try {
        val obj = Json.parseToJsonElement(answer.toString(Charsets.UTF_8)).jsonObject
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
        val usage = obj["usage"] as? JsonObject
        val seconds = usage?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "duration" }?.get("seconds")?.jsonPrimitive?.doubleOrNull
        if (text == null) TranscriptionResult.Failed(VoiceApiProblem.BAD_ANSWER, 200)
        else TranscriptionResult.Ok(text.trim(), seconds)
    } catch (e: Exception) {
        TranscriptionResult.Failed(VoiceApiProblem.BAD_ANSWER, 200)
    } finally {
        answer.fill(0)
    }

    private fun failure(status: Int, answer: ByteArray): TranscriptionResult.Failed {
        val (code, type) = errorCodeAndType(answer)
        answer.fill(0)
        return TranscriptionResult.Failed(problemOf(status, code, type), status, code, type)
    }

    companion object {
        private const val TAG = "VoiceApi"
        private const val MAX_ANSWER_BYTES = 1024 * 1024

        private fun randomBoundary(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "aikb-" + bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * The fields before the file: model, language of the layout (if it has a two letter code), fixed prompt; in a
         * continuous dictation the fixed prompt is followed by the end of what was dictated before ([context], stage 5g).
         */
        fun formFields(languageTag: String, context: String? = null): List<Pair<String, String>> {
            val language = languageCode(languageTag)
            return listOfNotNull(
                "model" to OpenAi.TRANSCRIBE_MODEL,
                language?.let { "languages[]" to it },
                "prompt" to promptWith(language, context),
            )
        }

        /** at most this much of the text dictated before in the session goes with the next phrase (docs/DESIGN.md, 5г) */
        const val CONTEXT_CHARS = 200

        /** the fixed prompt, then the last [CONTEXT_CHARS] of [context] on one line */
        fun promptWith(language: String?, context: String?): String {
            val tail = context?.replace(Regex("\\s+"), " ")?.trim()?.takeLast(CONTEXT_CHARS)?.trim()
            return if (tail.isNullOrEmpty()) prompt(language) else prompt(language) + " " + tail
        }

        /** ISO 639-1 code of the layout language; old Java codes mapped; null (let OpenAI detect) if there is none */
        fun languageCode(languageTag: String): String? {
            val l = Locale.forLanguageTag(languageTag.replace('_', '-')).language.lowercase(Locale.ROOT)
            val code = when (l) { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> l }
            return code.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
        }

        /**
         * Fixed context about the recording, in its language (docs/VOICE_API.md, section 1): how the text should look.
         * Never text of the field, never contact names.
         */
        fun prompt(language: String?): String = when (language) {
            "ru" -> "Диктовка сообщения на телефоне. Обычные знаки препинания, заглавные буквы в начале предложений, вопросительные знаки в вопросах. Числа, проценты, суммы и даты — цифрами: 100%, 1,83$, 500₽, 17 октября."
            "uk" -> "Диктування повідомлення на телефоні. Звичайні розділові знаки, великі літери на початку речень, знаки питання в питаннях."
            "de" -> "Diktierte Nachricht auf dem Handy. Normale Satzzeichen, Großbuchstaben am Satzanfang, Fragezeichen bei Fragen."
            "fr" -> "Message dicté sur un téléphone. Ponctuation normale, majuscules en début de phrase, points d'interrogation pour les questions."
            "es" -> "Mensaje dictado en el teléfono. Puntuación normal, mayúsculas al inicio de las frases, signos de interrogación en las preguntas."
            "it" -> "Messaggio dettato al telefono. Punteggiatura normale, maiuscole a inizio frase, punti interrogativi nelle domande."
            "pt" -> "Mensagem ditada no telefone. Pontuação normal, letras maiúsculas no início das frases, pontos de interrogação nas perguntas."
            "pl" -> "Wiadomość dyktowana na telefonie. Normalna interpunkcja, wielkie litery na początku zdań, znaki zapytania w pytaniach."
            "tr" -> "Telefonda dikte edilen mesaj. Normal noktalama, cümle başında büyük harf, sorularda soru işareti."
            else -> "Dictated message on a phone. Normal punctuation, capital letters at the start of sentences, question marks for questions. Write numbers, percents, amounts and dates as digits: 100%, $1.83, 3.5."
        }

        fun blockedProblem(e: NetworkBlockedException) = when (e.reason) {
            NetworkBlockedException.Reason.INTERNET_OFF -> VoiceApiProblem.INTERNET_OFF
            NetworkBlockedException.Reason.VOICE_API_OFF, NetworkBlockedException.Reason.TEXT_API_OFF -> VoiceApiProblem.MODE_OFF
            NetworkBlockedException.Reason.BAD_PROXY -> VoiceApiProblem.NETWORK
            NetworkBlockedException.Reason.NOT_ALLOWED, NetworkBlockedException.Reason.REDIRECT -> VoiceApiProblem.BAD_ANSWER
        }

        /** error table of docs/VOICE_API.md, section 5 */
        fun problemOf(status: Int, code: String?, type: String?): VoiceApiProblem = when {
            status == 401 -> VoiceApiProblem.KEY_INVALID
            status == 402 -> VoiceApiProblem.NO_BALANCE
            status == 429 && (code in BALANCE_CODES || type in BALANCE_CODES) -> VoiceApiProblem.NO_BALANCE
            status == 429 -> VoiceApiProblem.RATE_LIMIT
            status == 413 -> VoiceApiProblem.TOO_LARGE
            status == 403 -> VoiceApiProblem.FORBIDDEN
            status == 404 -> VoiceApiProblem.MODEL_NOT_AVAILABLE
            status in 500..599 -> VoiceApiProblem.SERVER
            status in 400..499 -> VoiceApiProblem.BAD_REQUEST
            else -> VoiceApiProblem.BAD_ANSWER
        }

        val BALANCE_CODES = setOf("insufficient_quota", "credit_balance_exhausted")

        /** the ids of {"object":"list","data":[{"id":…}]}, null if the answer is not such a list */
        fun modelIds(answer: ByteArray): List<String>? = try {
            val data = Json.parseToJsonElement(answer.toString(Charsets.UTF_8)).jsonObject["data"] as? kotlinx.serialization.json.JsonArray
            data?.mapNotNull { ((it as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        } catch (e: Exception) { null }
        private val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,60}")

        /** error.code and error.type only; error.message is never read (it may contain a part of the key) */
        fun errorCodeAndType(answer: ByteArray): Pair<String?, String?> = try {
            val error = Json.parseToJsonElement(answer.toString(Charsets.UTF_8)).jsonObject["error"]?.jsonObject
            fun field(name: String) = (error?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { SAFE_CODE.matches(it) }
            field("code") to field("type")
        } catch (e: Exception) { null to null }

        private fun readLimited(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_ANSWER_BYTES) throw IOException("answer too large")
            }
            return out.toByteArray()
        }
    }
}
