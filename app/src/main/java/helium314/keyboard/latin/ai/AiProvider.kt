// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.openai.CancelSignal

/**
 * "AI provider" (docs/DECISIONS.md, switchable AI provider): the ✨ commands and the cleanup of dictation work only
 * through this interface, nothing in the commands and the preview depends on a concrete provider.
 * Implementation: [AiProviderKind.OWN_KEY] (the user's own OpenAI API key, docs/AI.md); the AI Keyboard cloud exists
 * only as a name for now. The ChatGPT sign-in was removed in stage 5d.
 * The text of requests and answers is never logged and never stored.
 */
interface AiProvider {
    val kind: AiProviderKind

    /** models offered by the provider, from the cache if it is fresh; on a background thread */
    fun models(refresh: Boolean = false): AiModels

    /**
     * One request, blocking (background thread). [onText] gets the answer so far while it streams (already cleaned),
     * the result is a success only when the provider confirmed the complete answer. [cancel] ends it at once.
     */
    fun respond(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): AiResult
}

enum class AiProviderKind {
    /** own OpenAI API key of the user, paid from the user's balance */
    OWN_KEY,
    /** AI Keyboard cloud (later, our server); not implemented */
    CLOUD,
}

class AiModel(val id: String, val name: String)

sealed class AiModels {
    class Ok(val models: List<AiModel>) : AiModels()
    class Failed(val failure: AiResult.Failure) : AiModels()
}

/**
 * What is sent: fixed [instructions] of the command and the user's [text] (the selection or the field), nothing else:
 * no dictionaries, no contact names, no typing history.
 */
class AiRequest(
    val instructions: String,
    val text: String,
    /** the answer is cut off and the request fails when it gets longer (max_output_tokens can't be sent) */
    val maxAnswerChars: Int,
    /** the whole request, from sending to the last event */
    val totalTimeoutMs: Long = 60_000,
    /** "try again later" answers (503) are repeated twice, after 2 and 8 s; only for requests the user started */
    val retryUnavailable: Boolean = true,
    /** the cleanup of a dictated phrase: counted separately ("Cleanup: N today") */
    val isCleanup: Boolean = false,
    /** only for the comparison of models in Advanced (stage 5zh): this model instead of the automatic one, no switching */
    val model: String? = null,
) {
    override fun toString() = "AiRequest(${text.length} chars)" // never the text
}

enum class AiError {
    INTERNET_OFF,
    /** no API key saved */
    NO_KEY,
    /** OpenAI answered 401: the key is marked as wrong until it is checked again or replaced */
    KEY_INVALID,
    /** no consent for text requests */
    NO_CONSENT,
    PHONE_LOCKED,
    /** 402, 429 insufficient_quota / credit_balance_exhausted, or the pause after it */
    NO_BALANCE,
    /** 429 too many requests, or the pause after it */
    RATE_LIMIT,
    /** the local daily ceiling of AI Keyboard */
    DAILY_CAP,
    UNAVAILABLE, REGION_BLOCKED, NETWORK,
    /** response.incomplete, or the stream ended without a final event */
    INCOMPLETE,
    TOO_LONG, TIMEOUT, CANCELLED, NO_MODEL, FAILED,
}

sealed class AiResult {
    class Success(val text: String) : AiResult() {
        override fun toString() = "Success(${text.length} chars)"
    }

    /** status, code, parameter and request ID of the server; never text */
    class Failure(
        val error: AiError,
        val httpStatus: Int? = null,
        val code: String? = null,
        val requestId: String? = null,
        val param: String? = null,
    ) : AiResult() {
        override fun toString() = "Failure($error${httpStatus?.let { ", HTTP $it" } ?: ""}${code?.let { ", $it" } ?: ""})"
    }
}

/** the answer of a model as plain text: no control characters except line breaks and tabs */
object AiText {
    fun clean(s: String): String = buildString(s.length) {
        for (c in s) if (c == '\n' || c == '\t' || !(c.isISOControl() || c == '‎' || c == '‏' || c in '‪'..'‮' || c in '⁦'..'⁩')) append(c)
    }
}
