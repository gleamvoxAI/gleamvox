// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** States of voice input inside the keyboard, see docs/VOICE.md */
enum class VoiceState { IDLE, REQUESTING_PERMISSION, LISTENING, PROCESSING, DONE, ERROR }

/** [OPENAI]: voice through the OpenAI API failed, the reason came with [SpeechEngine.Listener.onNotice] */
enum class VoiceError {
    NO_SPEECH, LANGUAGE_NOT_SUPPORTED, NETWORK, AUDIO, BUSY, PERMISSION, TIMEOUT, OTHER, OPENAI,
    /** the microphone could not be opened although the permission is there: another app records (stage 5zh) */
    MIC_BUSY,
    /** the recording came as pure zeros: the system silenced the microphone (a call, another app) */
    MIC_SILENCED,
    /** stage 5g-6: only noise was heard, no speech: stopped, nothing sent */
    ONLY_NOISE,
}

/** The speech recognizer. All callbacks must arrive on the main thread. */
interface SpeechEngine {
    fun start(languageTag: String, listener: Listener)
    /** stop listening, the final result will follow */
    fun stop()
    /** stop and drop everything, no more callbacks */
    fun cancel()
    fun destroy()

    /** longest listening before an automatic stop */
    val maxListenMs: Long get() = VoiceInputController.MAX_LISTEN_MS
    /** longest wait for the result after listening */
    val resultTimeoutMs: Long get() = VoiceInputController.RESULT_TIMEOUT_MS

    interface Listener {
        fun onListening()
        /** sound level 0..1 for the indicator */
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onEndOfSpeech()
        fun onFinal(text: String)
        fun onError(error: VoiceError)
        /** why voice through OpenAI is not used or failed (docs/VOICE_API.md); shown on the panel */
        fun onNotice(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem) {}
        /** the loudest sample of the recording and the microphone source, before a "didn't catch that" (stage 5b) */
        fun onSignal(peakDbfs: Double, source: String) {}
        /** OpenAI failed and the engine listens again with the system recognizer (once): back to listening */
        fun onSwitchedToSystem(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem) {}
        /** a step for the voice journal and the status of the panel (stage 5zh) */
        fun onTrace(trace: VoiceTrace) {}
        /** continuous dictation (stage 5g): one recognized phrase, strictly in order; the dictation goes on */
        fun onPhrase(text: String) {}
        /** continuous dictation: [phrases] inserted so far, [recognizing] sent or waiting, [failed] not recognized */
        fun onProgress(phrases: Int, recognizing: Int, failed: Int) {}
        /** continuous dictation: everything recorded is inserted (or dropped after a failure); the dictation is over */
        fun onContinuousDone() {}
        /** stage 5g: the microphone of the recording, the headset or the phone; [fellBack]: the headset did not answer */
        /** stage 5g-20: pieces wait for the network ([waiting]); [askRetry]: 2 min without it, "Retry" */
        fun onNetwork(waiting: Boolean, askRetry: Boolean) {}
        fun onMic(headset: Boolean, fellBack: Boolean) {}
    }
}

fun interface Cancellable { fun cancel() }

fun interface Scheduler { fun schedule(delayMs: Long, action: () -> Unit): Cancellable }

/** What the controller needs from the keyboard */
interface VoiceHost {
    fun hasPermission(): Boolean
    /** recording never starts while the screen is off (stage 5zh) */
    fun screenOn(): Boolean = true
    /** shows the system permission dialog; listening never starts by itself afterwards */
    fun requestPermission()
    fun languageTag(): String
    fun commitText(text: String)
    /** [partial] is only kept in memory and shown on the panel */
    fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?)
    fun onLevel(level: Float)
    /** a line about voice through OpenAI under the status (null: none); [switched]: now listening with the system recognizer */
    fun onNotice(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem?, switched: Boolean) {}
    /** signal level of the last recording, shown with "didn't catch that" (null: unknown, system recognizer) */
    fun onSignal(peakDbfs: Double?, source: String?) {}
    /** a step of the current dictation for the journal and the panel status (stage 5zh) */
    fun onTrace(trace: VoiceTrace) {}
    /** continuous dictation (stage 5g): a phrase, inserted with a space before it; the panel stays */
    fun commitPhrase(text: String) = commitText(text)
    /** continuous dictation: the counters of the panel */
    fun onProgress(phrases: Int, recognizing: Int, failed: Int) {}
    /** continuous dictation: all phrases are in, the dictation ended well */
    fun onContinuousDone() {}
    /** the microphone of the recording (stage 5g): shown on the panel, kept in the journal */
    fun onMic(headset: Boolean, fellBack: Boolean) {}
    /** stage 5g-20: pieces of the dictation wait for the network ([waiting]); [askRetry]: "Retry" after 2 min */
    fun onNetwork(waiting: Boolean, askRetry: Boolean) {}
}

/**
 * State machine of voice input (Idle, RequestingPermission, Listening, Processing, Done, Error).
 * Never logs or stores recognized text: [log] only receives state names and error codes.
 */
class VoiceInputController(
    private val engineFactory: () -> SpeechEngine,
    private val host: VoiceHost,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
) {
    var state = VoiceState.IDLE
        private set
    var error: VoiceError? = null
        private set
    /** last partial text, only in memory, cleared when the session ends */
    var partial = ""
        private set

    private var engine: SpeechEngine? = null
    private var session = 0
    private var maxDurationTimer: Cancellable? = null
    private var resultTimer: Cancellable? = null
    private var errorTimer: Cancellable? = null

    val isActive get() = state != VoiceState.IDLE

    /** microphone key or the big microphone on the panel */
    fun onMicPressed() {
        when (state) {
            VoiceState.IDLE, VoiceState.DONE -> start()
            VoiceState.ERROR -> { close(); start() }
            VoiceState.LISTENING -> finish()
            VoiceState.PROCESSING, VoiceState.REQUESTING_PERMISSION -> Unit
        }
    }

    fun start() {
        if (state != VoiceState.IDLE && state != VoiceState.DONE) return
        if (!host.hasPermission()) {
            setState(VoiceState.REQUESTING_PERMISSION)
            host.requestPermission()
            return
        }
        if (!host.screenOn()) {
            log("screen off: not started")
            return
        }
        partial = ""
        error = null
        val id = ++session
        val e = engineFactory()
        engine = e
        setState(VoiceState.LISTENING)
        if (id != session) return // host could not show the panel and cancelled: never listen without the panel
        startMaxDurationTimer(id, e)
        e.start(host.languageTag(), SessionListener(id))
    }

    /** the permission dialog is gone (answered or keyboard hidden); the user has to press the microphone again */
    fun onPermissionFlowEnded() {
        if (state == VoiceState.REQUESTING_PERMISSION) setState(VoiceState.IDLE)
    }

    /** "Done" button or microphone pressed again: stop listening and insert what was recognized */
    fun finish() {
        when (state) {
            VoiceState.LISTENING -> {
                maxDurationTimer?.cancel()
                setState(VoiceState.PROCESSING)
                startResultTimer()
                engine?.stop()
            }
            VoiceState.ERROR, VoiceState.REQUESTING_PERMISSION -> close()
            else -> Unit
        }
    }

    /** "Cancel" button, keyboard hidden, other field or app, screen off: nothing is inserted */
    fun cancel() {
        if (state == VoiceState.IDLE) return
        log("cancel in $state")
        close()
    }

    fun destroy() = close()

    private fun startMaxDurationTimer(id: Int, e: SpeechEngine) {
        maxDurationTimer?.cancel()
        val ms = e.maxListenMs
        maxDurationTimer = scheduler.schedule(ms) { if (id == session) { log("auto stop after ${ms / 1000} s"); finish() } }
    }

    private fun startResultTimer() {
        resultTimer?.cancel()
        val id = session
        resultTimer = scheduler.schedule(engine?.resultTimeoutMs ?: RESULT_TIMEOUT_MS) { if (id == session) showError(VoiceError.TIMEOUT) }
    }

    private fun close() {
        session++ // ignore late callbacks of the old engine
        maxDurationTimer?.cancel(); maxDurationTimer = null
        resultTimer?.cancel(); resultTimer = null
        errorTimer?.cancel(); errorTimer = null
        engine?.let { it.cancel(); it.destroy() }
        engine = null
        partial = ""
        error = null
        setState(VoiceState.IDLE)
    }

    private fun showError(e: VoiceError) {
        log("error $e")
        maxDurationTimer?.cancel(); maxDurationTimer = null
        resultTimer?.cancel(); resultTimer = null
        engine?.let { it.cancel(); it.destroy() }
        engine = null
        session++
        partial = ""
        error = e
        setState(VoiceState.ERROR)
        // "didn't catch that" and the microphone problems stay open with "Retry" until the user acts (stage 5b, 5zh)
        if (e !in STAYING_ERRORS) errorTimer = scheduler.schedule(ERROR_SHOWN_MS) { if (state == VoiceState.ERROR) close() }
    }

    private fun deliver(text: String) {
        val result = text.trim().ifEmpty { partial.trim() }
        if (result.isEmpty()) { // nothing recognized: say so, never close silently (stage 5b)
            showError(VoiceError.NO_SPEECH)
            return
        }
        setState(VoiceState.DONE)
        host.commitText(result)
        close()
    }

    private fun setState(newState: VoiceState) {
        if (state == newState && newState != VoiceState.LISTENING) return
        state = newState
        log("state $newState")
        host.onStateChanged(newState, partial, error)
    }

    private inner class SessionListener(private val id: Int) : SpeechEngine.Listener {
        private val current get() = id == session

        override fun onListening() = Unit

        override fun onLevel(level: Float) {
            if (current && state == VoiceState.LISTENING) host.onLevel(level.coerceIn(0f, 1f))
        }

        override fun onPartial(text: String) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            partial = text
            host.onStateChanged(state, partial, null)
        }

        override fun onEndOfSpeech() {
            if (!current || state != VoiceState.LISTENING) return
            maxDurationTimer?.cancel()
            setState(VoiceState.PROCESSING)
            startResultTimer()
        }

        override fun onFinal(text: String) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            deliver(text)
        }

        override fun onError(error: VoiceError) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            if (error == VoiceError.NO_SPEECH) deliver("") // inserts the partial text if there is one, else "didn't catch that"
            else showError(error)
        }

        override fun onSignal(peakDbfs: Double, source: String) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            host.onSignal(peakDbfs, source)
        }

        override fun onTrace(trace: VoiceTrace) {
            if (current) host.onTrace(trace)
        }

        override fun onPhrase(text: String) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING) || text.isBlank()) return
            host.commitPhrase(text.trim())
        }

        override fun onProgress(phrases: Int, recognizing: Int, failed: Int) {
            if (current) host.onProgress(phrases, recognizing, failed)
        }

        override fun onMic(headset: Boolean, fellBack: Boolean) {
            if (current && state == VoiceState.LISTENING) host.onMic(headset, fellBack)
        }

        override fun onNetwork(waiting: Boolean, askRetry: Boolean) {
            if (current) host.onNetwork(waiting, askRetry)
        }

        override fun onContinuousDone() {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            log("continuous dictation done")
            host.onContinuousDone()
            setState(VoiceState.DONE)
            close()
        }

        override fun onNotice(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            log("notice $problem")
            host.onNotice(problem, false)
        }

        override fun onSwitchedToSystem(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem) {
            if (!current || (state != VoiceState.LISTENING && state != VoiceState.PROCESSING)) return
            log("switched to the system recognizer: $problem")
            resultTimer?.cancel(); resultTimer = null
            partial = ""
            host.onNotice(problem, true)
            setState(VoiceState.LISTENING)
            engine?.let { startMaxDurationTimer(id, it) }
        }
    }

    companion object {
        const val MAX_LISTEN_MS = 60_000L
        const val RESULT_TIMEOUT_MS = 10_000L
        const val ERROR_SHOWN_MS = 3_000L
        /** these stay on the panel until "Retry" or "Cancel": the user has to do something about them */
        val STAYING_ERRORS = setOf(VoiceError.NO_SPEECH, VoiceError.MIC_BUSY, VoiceError.MIC_SILENCED, VoiceError.PERMISSION)
    }
}
