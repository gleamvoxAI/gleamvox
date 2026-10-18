// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceError

/**
 * OpenAI first; if OpenAI fails ([VoiceError.OPENAI]), one quiet switch to the system recognizer within the same
 * dictation, with the reason on the panel (docs/VOICE_API.md, section 5). The recorded phrase is not kept, so the
 * user says it again. Never a second switch. Without a system recognizer the OpenAI error is shown.
 */
class FallbackSpeechEngine(
    private val primary: SpeechEngine,
    private val fallbackFactory: () -> SpeechEngine,
    private val canFallBack: () -> Boolean,
) : SpeechEngine {
    private var current: SpeechEngine = primary
    private var outer: SpeechEngine.Listener? = null
    private var language = ""
    private var pendingNotice: VoiceApiProblem? = null
    var switched = false
        private set

    override val maxListenMs: Long get() = current.maxListenMs
    override val resultTimeoutMs: Long get() = current.resultTimeoutMs

    override fun start(languageTag: String, listener: SpeechEngine.Listener) {
        outer = listener
        language = languageTag
        primary.start(languageTag, PrimaryListener())
    }

    override fun stop() = current.stop()

    override fun cancel() {
        outer = null
        current.cancel()
    }

    override fun destroy() {
        outer = null
        current.destroy()
        if (current !== primary) primary.destroy()
    }

    /** forwards to the controller until cancelled */
    private open inner class Forward : SpeechEngine.Listener {
        override fun onListening() { outer?.onListening() }
        override fun onLevel(level: Float) { outer?.onLevel(level) }
        override fun onPartial(text: String) { outer?.onPartial(text) }
        override fun onEndOfSpeech() { outer?.onEndOfSpeech() }
        override fun onFinal(text: String) { outer?.onFinal(text) }
        override fun onError(error: VoiceError) { outer?.onError(error) }
        override fun onNotice(problem: VoiceApiProblem) { outer?.onNotice(problem) }
        override fun onSignal(peakDbfs: Double, source: String) { outer?.onSignal(peakDbfs, source) }
        override fun onTrace(trace: helium314.keyboard.latin.voice.VoiceTrace) { outer?.onTrace(trace) }
        override fun onPhrase(text: String) { outer?.onPhrase(text) }
        override fun onProgress(phrases: Int, recognizing: Int, failed: Int) { outer?.onProgress(phrases, recognizing, failed) }
        override fun onContinuousDone() { outer?.onContinuousDone() }
        override fun onMic(headset: Boolean, fellBack: Boolean) { outer?.onMic(headset, fellBack) }
        override fun onNetwork(waiting: Boolean, askRetry: Boolean) { outer?.onNetwork(waiting, askRetry) }
    }

    private inner class PrimaryListener : Forward() {
        override fun onNotice(problem: VoiceApiProblem) { pendingNotice = problem }

        override fun onError(error: VoiceError) {
            val notice = pendingNotice
            pendingNotice = null
            if (error == VoiceError.OPENAI && !switched && outer != null && canFallBack()) {
                switched = true
                primary.destroy()
                val f = fallbackFactory()
                current = f
                outer?.onSwitchedToSystem(notice ?: VoiceApiProblem.NETWORK)
                f.start(language, Forward())
                return
            }
            notice?.let { outer?.onNotice(it) }
            outer?.onError(error)
        }
    }
}
