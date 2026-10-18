// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

/**
 * Addresses of the OpenAI API used with the user's own API key (docs/AI.md, docs/VOICE_API.md). Only these three go
 * through [AikbNetwork]; the pages below are opened in the browser, never by the app.
 */
object OpenAi {
    const val API_BASE = "https://api.openai.com/v1"
    /** GET: key check, list of text models, connection check */
    const val MODELS_URL = "$API_BASE/models"
    /** POST: ✨ and cleanup of dictation, store:false, stream:true */
    const val RESPONSES_URL = "$API_BASE/responses"
    /** POST: voice (docs/VOICE_API.md) */
    const val TRANSCRIPTIONS_URL = "$API_BASE/audio/transcriptions"

    const val TRANSCRIBE_MODEL = "gpt-transcribe"

    /** opened in the browser from the consent screens */
    const val PRIVACY_POLICY_URL = "https://openai.com/policies/privacy-policy/"
    const val API_DATA_URL = "https://developers.openai.com/api/docs/guides/your-data"
    /** the exact API balance is only on the OpenAI site (stage 5c) */
    const val BILLING_URL = "https://platform.openai.com/settings/organization/billing/overview"
}
