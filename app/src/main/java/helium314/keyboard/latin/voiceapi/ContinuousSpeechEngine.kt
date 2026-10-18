// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceTrace

/**
 * Continuous dictation through OpenAI (docs/DESIGN.md, "Этап 5г"): one open microphone ([PcmRecorder] in continuous
 * mode) is cut into phrases at the pauses; each phrase goes to gpt-transcribe as soon as it ends, while the user already
 * speaks the next one. At most [MAX_IN_FLIGHT] requests run at once; the texts are given out strictly in the order the
 * phrases were spoken ([SpeechEngine.Listener.onPhrase]).
 *
 * - The prompt of each phrase gets the end of the text recognized before in this session (only that: never text of the
 *   field, never contact names). It lives only in memory and is gone with the engine.
 * - Stage 5g-20: a phrase that fails because of the network (or 429, 5xx) is kept ([ChunkSender]) and sent again (after
 *   1, 3, 7 s and when the network comes back); the phrases after it wait, so the order never changes. Other failures
 *   drop that phrase only (counted).
 * - A wrong key or no money (401, 402, insufficient_quota) stops the whole dictation: the recording ends, what is not
 *   inserted yet is dropped, and the error goes out as with one phrase (then the system recognizer takes over once).
 * - Nothing heard at all: "didn't catch that" as with one phrase.
 * Callbacks arrive through [post] (main thread); requests run on [background].
 */
class ContinuousSpeechEngine(
    private val recorderFactory: (PcmRecorder.Listener) -> PcmRecorder,
    /** sound, language, context (the session's text before), cancel, called when the whole sound is sent */
    private val transcribe: (PcmBuffer, String, String?, CancelSignal, () -> Unit) -> TranscriptionResult,
    private val background: (Runnable) -> Unit,
    private val post: (Runnable) -> Unit,
    /** after every answer, with the length of the sent sound: usage, pauses, key rejected */
    private val onResult: (TranscriptionResult, Long) -> Unit,
    /** the longest dictation (the recorder ends itself there; this is the safety net of the controller) */
    sessionMs: Long,
    private val onSourceWorked: (Int) -> Unit = {},
    /** stage 5g-20: delays on the main thread (the retries of [ChunkSender]); the returned function cancels */
    private val later: (Long, Runnable) -> (() -> Unit) = { _, _ -> {} },
    private val clock: () -> Long = System::currentTimeMillis,
    /** stage 5g-20: the sound of phrases waiting for the network, also in files of the cache folder */
    private val store: ChunkStore? = null,
) : SpeechEngine {
    override val maxListenMs: Long = sessionMs + 3_000
    /** stage 5g-20: phrases may wait for the network up to 10 min */
    override val resultTimeoutMs: Long = ChunkSender.KEEP_MS + 60_000

    private var listener: SpeechEngine.Listener? = null
    private var recorder: PcmRecorder? = null
    private var language = ""
    private var lastProblem: VoiceApiProblem? = null
    /** wrong key or no money: the dictation stops */
    private var fatal: VoiceApiProblem? = null
    private var recordingEnded = false
    private var finished = false
    private var inserted = 0
    private var dropped = 0
    private var lastPeakDbfs = -100.0
    /** stage 5g-6: the recording ended because there was only noise */
    private var stoppedByNoise = false
    private var lastSource = Loudness.name(Loudness.SOURCE_MIC)

    /** stage 5g-20: the line of phrases: sent in order, kept and sent again when the network fails */
    private val sender = ChunkSender(
        transcribe = { audio, context, cancel -> transcribe(audio, language, context, cancel) { } },
        background = background, post = post, later = later, clock = clock, onResult = onResult, store = store,
        events = object : ChunkSender.Events {
            override fun onUploading(bytes: Long) { listener?.onTrace(VoiceTrace.Uploading(bytes)) }
            override fun onAnswer(status: Int?, ms: Long, problem: String?) { listener?.onTrace(VoiceTrace.Answer(status, ms, problem)) }
            override fun onText(text: String) {
                if (text.isEmpty() || fatal != null || finished) return
                inserted++
                listener?.onPhrase(text)
                progress()
            }
            override fun onWaiting(on: Boolean) { listener?.onNetwork(on, false) }
            override fun onRetryNeeded() { listener?.onNetwork(true, true) }
            override fun onDropped(problem: VoiceApiProblem) { lastProblem = problem; dropped++; progress() }
            override fun onGivenUp(problem: VoiceApiProblem) { lastProblem = problem; dropped++; listener?.onNetwork(false, false) }
            override fun onFatal(problem: VoiceApiProblem) { lastProblem = problem; fatal = problem; recorder?.stop() }
            override fun onIdle() { progress(); maybeFinish() }
        },
    )

    /** for tests and the panel */
    val insertedPhrases get() = inserted
    val failedPhrases get() = dropped
    val recognizing get() = sender.waiting
    /** stage 5g-20, for the journal */
    val chunks get() = sender.chunks
    val retried get() = sender.retried
    val keptForRetry get() = sender.keptForRetry

    override fun start(languageTag: String, listener: SpeechEngine.Listener) {
        this.listener = listener
        language = languageTag
        val r = recorderFactory(RecorderCallbacks())
        recorder = r
        if (!r.start()) {
            r.cancel()
            val permission = r.startFailure == PcmSource.Failure.PERMISSION
            listener.onTrace(VoiceTrace.RecordingFailed(if (permission) "permission" else "busy"))
            listener.onError(if (permission) VoiceError.PERMISSION else VoiceError.MIC_BUSY)
            return
        }
        listener.onTrace(VoiceTrace.RecordingStarted(Loudness.name(r.sourceUsed), micName(r)))
        listener.onMic(r.onHeadset, r.headsetFellBack)
        listener.onListening()
        progress()
    }

    /** the circle: the last phrase is sent, everything is inserted, then the dictation ends */
    override fun stop() { recorder?.stop() }

    /** "Cancel" (and the end of the service): stops at once; what is not inserted yet is dropped and wiped */
    override fun cancel() {
        listener = null
        finished = true
        recorder?.cancel()
        sender.cancel()
    }

    override fun destroy() = cancel()

    /** "Retry" (stage 5g-20: the network did not come back for 2 min): the waiting phrases are sent again now */
    fun retryFailed() { if (!finished && fatal == null) sender.retryNow() }

    /** stage 5g-20: the network came back: the waiting phrases are sent at once */
    fun networkBack() { if (!finished) sender.networkBack() }

    private fun progress() {
        listener?.onProgress(inserted, sender.waiting, dropped)
    }

    private fun maybeFinish() {
        if (finished || !recordingEnded || sender.busy) return
        finished = true
        val l = listener ?: return
        val f = fatal
        when {
            f != null -> { l.onNotice(f); l.onError(VoiceError.OPENAI) }
            inserted > 0 -> l.onContinuousDone()
            lastProblem != null && lastProblem != VoiceApiProblem.CANCELLED -> { l.onNotice(lastProblem!!); l.onError(VoiceError.OPENAI) }
            else -> {
                l.onSignal(lastPeakDbfs, lastSource)
                l.onError(when {
                    lastPeakDbfs <= Loudness.DIGITAL_SILENCE_DBFS -> VoiceError.MIC_SILENCED
                    stoppedByNoise -> VoiceError.ONLY_NOISE // stage 5g-6: "Stopped: only noise"
                    else -> VoiceError.NO_SPEECH
                })
            }
        }
    }

    private inner class RecorderCallbacks : PcmRecorder.Listener {
        override fun onLevel(level: Float) { listener?.onLevel(level) }

        override fun onSourceWorked(source: Int) = this@ContinuousSpeechEngine.onSourceWorked(source)

        override fun onFirstFrame(atMs: Long) { listener?.onTrace(VoiceTrace.FirstFrame(atMs)) }

        override fun onHeadsetTried(up: Boolean, ms: Long) { listener?.onTrace(VoiceTrace.HeadsetTried(up, ms)) }

        override fun onHeadsetRejected() { listener?.onTrace(VoiceTrace.HeadsetRejected) }

        override fun onHeadsetHeard() { listener?.onTrace(VoiceTrace.HeadsetHeard) }

        override fun onPhoneLevels(beforeDbfs: Double, afterDbfs: Double) { listener?.onTrace(VoiceTrace.PhoneLevels(beforeDbfs, afterDbfs)) }
        override fun onProbationLevels(headsetDbfs: Double, phoneDbfs: Double, afterUpMs: Long, speechFrames: Int) {
            listener?.onTrace(VoiceTrace.ProbationLevels(headsetDbfs, phoneDbfs, afterUpMs, speechFrames))
        }
        override fun onHeadsetUndecided() { listener?.onTrace(VoiceTrace.HeadsetUndecided) }
        override fun onPhoneReopened(times: Int) { listener?.onTrace(VoiceTrace.PhoneReopened(times)) }
        override fun onSameSound(same: Int, frames: Int) { listener?.onTrace(VoiceTrace.SameSound(same, frames)) }
        override fun onSourcesMs(phoneMs: Long, headsetMs: Long) { listener?.onTrace(VoiceTrace.SourcesMs(phoneMs, headsetMs)) }
        override fun onGap(deafMs: Long, connectMs: Long, phonePeakDbfs: Double) { listener?.onTrace(VoiceTrace.Gap(deafMs, connectMs, phonePeakDbfs)) }
        override fun onProbe(summary: String, filledMs: Long) { listener?.onTrace(VoiceTrace.Probe(summary, filledMs)) }
        override fun onHeadsetAsked() { listener?.onTrace(VoiceTrace.HeadsetAsked) }
        override fun onVad(name: String, probAvg: Float, probMax: Float, threshold: Float) { listener?.onTrace(VoiceTrace.Vad(name, probAvg, probMax, threshold)) }

        override fun onMic(headset: Boolean, fellBack: Boolean) {
            recorder?.let { listener?.onTrace(VoiceTrace.RecordingStarted(Loudness.name(it.sourceUsed), micName(it))) }
            listener?.onMic(headset, fellBack)
        }

        override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) {
            if (finished || fatal != null || !Loudness.shouldSend(true, false, audio.durationMs, peakDbfs)) { audio.wipe(); return }
            // a quiet microphone: raise the level before sending (only in memory, at most 30 dB)
            audio.applyGain(Loudness.gainFor(peakDbfs))
            sender.add(audio)
            progress()
        }

        override fun onContinuousEnd(reason: String) {
            val r = recorder ?: return
            remember(r)
            recordingEnded = true
            listener?.onTrace(VoiceTrace.Recorded(r.peakDbfs, r.phrases > 0, reason, r.phrasesMs))
            stoppedByNoise = reason == "noise"
            listener?.onEndOfSpeech() // the controller shows "Recognizing…" until the last phrase is in
            maybeFinish()
        }

        // one-phrase callbacks: in continuous mode only the microphone problems come here
        override fun onNoiseStats(speechPercent: Int, noiseDbfs: Double, droppedPhrases: Int) {
            listener?.onTrace(VoiceTrace.Noise(speechPercent, noiseDbfs, droppedPhrases))
        }

        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) = onContinuousEnd("done")
        override fun onNoSpeech() = onContinuousEnd("silence")

        override fun onSilenced() {
            recorder?.let { remember(it); listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "silenced", it.phrasesMs)) }
            recordingEnded = true
            if (inserted == 0 && !sender.busy) {
                finished = true
                listener?.onSignal(lastPeakDbfs, lastSource)
                listener?.onError(VoiceError.MIC_SILENCED)
            } else { listener?.onEndOfSpeech(); maybeFinish() }
        }

        override fun onFailed() {
            recorder?.let { listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "failed", it.phrasesMs)) }
            recordingEnded = true
            if (inserted == 0 && !sender.busy) {
                finished = true
                listener?.onError(VoiceError.AUDIO)
            } else { listener?.onEndOfSpeech(); maybeFinish() }
        }

        private fun remember(r: PcmRecorder) {
            lastPeakDbfs = r.peakDbfs
            lastSource = Loudness.name(r.sourceUsed)
        }
    }

    companion object {
        /** phrases recognized at the same time (docs/DESIGN.md: "не более 2") */
        const val MAX_IN_FLIGHT = ChunkSender.MAX_IN_FLIGHT
        val FATAL = ChunkSender.FATAL
    }
}
