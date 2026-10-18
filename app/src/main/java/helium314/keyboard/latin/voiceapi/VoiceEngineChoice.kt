// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

/**
 * Which recognizer a dictation uses (docs/VOICE_API.md, section 3). OpenAI only if everything holds at once, checked
 * for every dictation; otherwise the system recognizer. With "Smart recognition" chosen the panel names the reason, with
 * "Auto" it says nothing. In fields that may not leave the keyboard (excluded apps, incognito) there is no message either.
 */
object VoiceEngineChoice {
    class Inputs(
        val inBuild: Boolean,
        val setting: SpeechEngineSetting,
        val consent: Boolean,
        val hasKey: Boolean,
        val keyRejected: Boolean,
        val internetOn: Boolean,
        val locked: Boolean,
        /** excluded app or incognito (sensitive fields have no voice at all) */
        val fieldBlocked: Boolean,
        val capReached: Boolean = false,
        /** a pause after a balance or rate limit error that is still running */
        val paused: VoiceApiProblem? = null,
        /** the balance estimate reached 0 or OpenAI answered 402 / insufficient_quota (stage 5c) */
        val balanceExhausted: Boolean = false,
    )

    data class Choice(val openAi: Boolean, val notice: VoiceApiProblem? = null)

    fun decide(i: Inputs): Choice {
        if (!i.inBuild || i.setting == SpeechEngineSetting.SYSTEM || !i.consent || i.fieldBlocked) return Choice(false)
        val problem = when {
            !i.internetOn -> VoiceApiProblem.INTERNET_OFF
            !i.hasKey -> VoiceApiProblem.NO_KEY
            i.keyRejected -> VoiceApiProblem.KEY_REJECTED
            i.locked -> VoiceApiProblem.LOCKED
            i.capReached -> VoiceApiProblem.CAP_REACHED
            i.balanceExhausted -> VoiceApiProblem.NO_BALANCE
            i.paused != null -> i.paused
            else -> null
        } ?: return Choice(true)
        return Choice(false, problem.takeIf { i.setting == SpeechEngineSetting.OPENAI })
    }
}
