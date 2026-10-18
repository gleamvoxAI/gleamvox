// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** "AI quality" in Advanced (stage 5zh): no model names and no prices for the user, only these two words */
enum class AiQuality(val value: String) {
    /** the default: the newest inexpensive models (luna, then mini; nano only as a fallback) */
    ECONOMY("economy"),
    /** stronger models first, the economy list after them */
    BETTER("better");

    companion object {
        const val PREF = "aikb_ai_quality"
        fun read(prefs: SharedPreferences) = entries.firstOrNull { it.value == prefs.getString(PREF, null) } ?: ECONOMY
        fun save(prefs: SharedPreferences, q: AiQuality) = prefs.edit { putString(PREF, q.value) }
    }
}

/**
 * Text models of the user's API key for ✨ and the cleanup of dictation (docs/AI.md, section 5). The user never picks a
 * model (stage 5zh): GET /v1/models gives every model of the key, only text generation models are kept, and the first
 * one of the preference list ([ECONOMY_ORDER] or [BETTER_ORDER]) that the key offers is used. A model that answered 404
 * is skipped until the list is read again. Model names are not secret; the list is cached for a day.
 */
class TextModels(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    fun cached(): List<AiModel>? = readIds(PREF_MODELS)?.map { AiModel(it, it) }

    fun isFresh(): Boolean = cached() != null && now() - prefs.getLong(PREF_MODELS_AT, 0) in 0 until MAX_AGE_MS

    /** a new list from OpenAI: models that were gone may be back */
    fun save(models: List<AiModel>) = prefs.edit {
        putString(PREF_MODELS, buildJsonArray { models.forEach { add(JsonPrimitive(it.id)) } }.toString())
        putLong(PREF_MODELS_AT, now())
        remove(PREF_UNAVAILABLE)
    }

    /** the list is old: read it again before the next request */
    fun invalidate() = prefs.edit { remove(PREF_MODELS_AT) }

    /** the model answered 404: the next one of the preference list is used from now on, without a message */
    fun markUnavailable(id: String) = prefs.edit {
        putString(PREF_UNAVAILABLE, buildJsonArray { (unavailable() + id).forEach { add(JsonPrimitive(it)) } }.toString())
    }

    fun unavailable(): Set<String> = readIds(PREF_UNAVAILABLE)?.toSet().orEmpty()

    fun quality(): AiQuality = AiQuality.read(prefs)

    /** the models in the order they are tried, without the ones that answered 404 */
    fun candidates(quality: AiQuality = quality()): List<String> {
        val ids = cached()?.map { it.id } ?: return emptyList()
        val gone = unavailable()
        return order(ids, quality).filter { it !in gone }
    }

    /** the model used now, chosen automatically */
    fun current(): AiModel? = candidates().firstOrNull()?.let { AiModel(it, it) }

    private fun readIds(key: String): List<String>? = prefs.getString(key, null)?.let { s ->
        try {
            Json.parseToJsonElement(s).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } catch (_: Exception) { null }
    }

    companion object {
        const val PREF_MODELS = "aikb_ai_text_models"
        const val PREF_MODELS_AT = "aikb_ai_text_models_at"
        /** the manual choice of stages 5d and 5e; no longer read, still erased by "Delete all" */
        const val PREF_MODEL = "aikb_ai_text_model"
        const val PREF_UNAVAILABLE = "aikb_ai_text_models_gone"
        val KEYS = listOf(PREF_MODELS, PREF_MODELS_AT, PREF_MODEL, PREF_UNAVAILABLE, AiQuality.PREF)
        const val MAX_AGE_MS = 24 * 3600_000L

        /**
         * "Economy" (owner, 2026-10-14): the newest inexpensive models first (luna), then mini, nano only as a fallback.
         * gpt-6-luna costs about as much as gpt-4.1-nano per request (0.07 against 0.06 dollars per 1000) and is much
         * more capable. The list is updated with the app; the comparison of the candidates is in docs/AI.md, section 7.
         */
        val ECONOMY_ORDER = listOf(
            "gpt-6-luna", "gpt-5.6-luna",
            "gpt-5.4-mini", "gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini",
            "gpt-5.4-nano", "gpt-4.1-nano", "gpt-5-nano",
        )

        /** "Better": stronger models first, then the economy list */
        val BETTER_ORDER = listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-5.6-terra", "gpt-4.1") + ECONOMY_ORDER

        /** never chosen automatically: old (gpt-3.5), slow reasoning (o1, o3) and moving aliases (chat-latest, …-latest) */
        private val NEVER_AUTO = Regex("^(gpt-3\\.5|o1|o3|chat-latest)|-latest$")

        /**
         * the preference list first, in its order; after it the other text models of the key that may be chosen at all,
         * cheapest first (a new model OpenAI adds later still works until the list of the app knows it)
         */
        fun order(ids: List<String>, quality: AiQuality): List<String> {
            val preferred = if (quality == AiQuality.BETTER) BETTER_ORDER else ECONOMY_ORDER
            val known = preferred.filter { it in ids }
            val others = sort(ids.filter { it !in preferred && isTextModel(it) && !NEVER_AUTO.containsMatchIn(it) })
            return known + others
        }

        /**
         * Dollars per 1 million tokens (input, output), standard tier, from https://developers.openai.com/api/docs/pricing
         * read 2026-10-13. Prices may change; they only order models outside the preference list and fill the
         * "Details" line of Advanced, nothing is billed by them.
         */
        val PRICES: Map<String, Pair<Double, Double>> = mapOf(
            "gpt-6-luna" to (0.10 to 0.50), "gpt-6-sol" to (2.00 to 10.00), "gpt-6.1-sol" to (2.00 to 10.00), "gpt-6-astra" to (10.00 to 50.00),
            "gpt-5.6-luna" to (0.20 to 1.20), "gpt-5.6-terra" to (2.00 to 12.00), "gpt-5.6-sol" to (4.00 to 20.00),
            "gpt-5.5" to (5.00 to 30.00), "gpt-5.4" to (2.50 to 15.00), "gpt-5.4-mini" to (0.75 to 4.50), "gpt-5.4-nano" to (0.20 to 1.25),
            "gpt-5.2" to (1.75 to 14.00), "gpt-5.1" to (1.25 to 10.00), "gpt-5" to (1.25 to 10.00), "gpt-5-mini" to (0.25 to 2.00),
            "gpt-5-nano" to (0.05 to 0.40), "gpt-4.1" to (2.00 to 8.00), "gpt-4.1-mini" to (0.40 to 1.60), "gpt-4.1-nano" to (0.10 to 0.40),
            "gpt-4o" to (2.50 to 10.00), "gpt-4o-mini" to (0.15 to 0.60), "o3" to (2.00 to 8.00), "o4-mini" to (1.10 to 4.40),
            "o3-mini" to (1.10 to 4.40), "o1" to (15.00 to 60.00), "gpt-3.5-turbo" to (0.50 to 1.50), "chat-latest" to (5.00 to 30.00),
        )

        /** a typical ✨ or cleanup request: about 150 tokens in, 100 out (docs/DECISIONS.md, economics) */
        private const val TYPICAL_IN = 150
        private const val TYPICAL_OUT = 100
        /** reasoning models also bill hidden reasoning tokens as output; a cautious estimate for such a short task */
        private const val REASONING_EXTRA_OUT = 200

        private val NOT_TEXT = Regex("audio|realtime|transcribe|tts|whisper|image|dall-e|embedding|moderation|search|codex|" +
            "-pro|deep-research|instruct|computer-use|live|translate|diarize|cyber|rosalind|sora|babbage|davinci")
        private val SNAPSHOT = Regex(".*-\\d{4}-\\d{2}-\\d{2}$|.*-\\d{4}$")
        private val TEXT_FAMILY = Regex("^(gpt-|o\\d|chat-latest).*")

        /** a model of the key that can answer ✨ requests: a chat model, not audio, image, search, a dated snapshot … */
        fun isTextModel(id: String): Boolean = TEXT_FAMILY.matches(id) && !NOT_TEXT.containsMatchIn(id) && !SNAPSHOT.matches(id)

        private fun isReasoning(id: String) = id.startsWith("gpt-5") || Regex("^o\\d.*").matches(id)

        /** estimated price of a typical request in millionths of a dollar; null when the model is not in [PRICES] */
        fun typicalCost(id: String): Double? {
            val (input, output) = PRICES[id] ?: return null
            val out = TYPICAL_OUT + if (isReasoning(id)) REASONING_EXTRA_OUT else 0
            return input * TYPICAL_IN + output * out
        }

        /** the text models of a /v1/models answer {"object":"list","data":[{"id":…}]}, cheapest first, unknown prices last */
        fun parse(body: String): List<AiModel> {
            val data = (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
            val ids = data.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() && it.length <= 100 && isTextModel(it) }.distinct()
            return sort(ids).map { AiModel(it, it) }
        }

        fun sort(ids: List<String>): List<String> =
            ids.sortedWith(compareBy<String>({ typicalCost(it) == null }, { typicalCost(it) ?: 0.0 }, { it }))
    }
}
