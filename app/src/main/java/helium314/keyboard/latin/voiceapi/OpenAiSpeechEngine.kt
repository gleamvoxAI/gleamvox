// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceTrace

/**
 * Voice through the OpenAI API (docs/VOICE_API.md): the app records one phrase itself ([PcmRecorder]), then sends it
 * once to gpt-transcribe and gives back the text. Sound and text stay only in memory; the sound is wiped after sending
 * and on cancel. Nothing is sent without at least 0.3 s of speech. Callbacks arrive through [post] (main thread).
 *
 * Stage 5g-20 (journal 4397: 98 s sent as one piece of 3 MB, lost with the network): a long recording is sent in pieces
 * of at most 30 s ([PcmRecorder.onChunk]) while it goes on, through [ChunkSender]: a piece that fails because of the
 * network is kept and sent again (1, 3, 7 s, and when the network comes back); the texts are joined in order.
 */
class OpenAiSpeechEngine(
    private val recorderFactory: (PcmRecorder.Listener) -> PcmRecorder,
    /** the last parameter is called when the whole sound is sent (the answer is awaited then) */
    private val transcribe: (PcmBuffer, String, CancelSignal, () -> Unit) -> TranscriptionResult,
    private val background: (Runnable) -> Unit,
    private val post: (Runnable) -> Unit,
    /** on the main thread after every answer, with the length of the sent sound: usage, pauses, key rejected */
    private val onResult: (TranscriptionResult, Long) -> Unit,
    /** the recorder stops itself at its own maximum; this is only the safety net of the controller */
    recordMs: Long,
    /** a microphone source gave a signal: remember it (stage 5b) */
    private val onSourceWorked: (Int) -> Unit = {},
    /** stage 5g-20: delays on the main thread (the retries of [ChunkSender]); the returned function cancels */
    private val later: (Long, Runnable) -> (() -> Unit) = { _, _ -> {} },
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: ChunkStore? = null,
) : SpeechEngine {
    override val maxListenMs: Long = recordMs + 3_000
    private val maxRecordMs = recordMs
    /** a long recording over a slow network; stage 5g-20: pieces may wait for the network up to 10 min */
    override val resultTimeoutMs: Long = ChunkSender.KEEP_MS + 60_000

    /** stage 5g-20: the texts of the pieces, in order; the recording has ended and its last piece is in the line */
    private val texts = mutableListOf<String>()
    private var ended = false
    private var done = false
    private var fatal: VoiceApiProblem? = null
    private var dropped: VoiceApiProblem? = null
    private var recordedMs = 0L
    private val sender = ChunkSender(
        transcribe = { audio, _, cancel -> transcribe(audio, language, cancel) { post(Runnable { listener?.onTrace(VoiceTrace.Sent) }) } },
        background = { background(it) }, post = post, later = later, clock = clock, onResult = onResult, store = store,
        withContext = false, maxInFlight = 1,
        events = object : ChunkSender.Events {
            override fun onUploading(bytes: Long) { listener?.onTrace(VoiceTrace.Uploading(bytes)) }
            override fun onAnswer(status: Int?, ms: Long, problem: String?) { listener?.onTrace(VoiceTrace.Answer(status, ms, problem)) }
            override fun onText(text: String) { if (text.isNotBlank()) texts.add(text.trim()) }
            override fun onWaiting(on: Boolean) { listener?.onNetwork(on, false) }
            override fun onRetryNeeded() { listener?.onNetwork(true, true) }
            override fun onDropped(problem: VoiceApiProblem) { dropped = problem }
            override fun onGivenUp(problem: VoiceApiProblem) { dropped = problem; listener?.onNetwork(false, false) }
            override fun onFatal(problem: VoiceApiProblem) { fatal = problem; recorder?.stop() }
            override fun onIdle() { maybeDeliver() }
        },
    )

    /** stage 5g-20, for the journal */
    val chunks get() = sender.chunks
    val retried get() = sender.retried
    val keptForRetry get() = sender.keptForRetry

    /** "Retry" and the network coming back: the waiting pieces are sent again now */
    fun retryNow() = sender.retryNow()
    fun networkBack() = sender.networkBack()

    /** all pieces are in: the joined text, or what went wrong */
    private fun maybeDeliver() {
        if (!ended || done || sender.busy) return
        done = true
        val l = listener ?: return
        val f = fatal
        val text = texts.joinToString(" ")
        texts.clear()
        when {
            f != null -> { onResultFailed(f) }
            text.isNotBlank() -> l.onFinal(text)
            dropped != null && dropped != VoiceApiProblem.CANCELLED -> onResultFailed(dropped!!)
            else -> notHeard()
        }
    }

    private fun onResultFailed(problem: VoiceApiProblem) {
        listener?.onNotice(problem)
        listener?.onError(VoiceError.OPENAI)
    }

    private var listener: SpeechEngine.Listener? = null
    companion object {
        /** stage 5g-20: after pieces, a rest shorter than this is not sent */
        const val MIN_REST_MS = 300L
    }
    private var recorder: PcmRecorder? = null
    private val cancelSignal = CancelSignal()
    private var language = ""
    /** level of the last recording before any gain, for "didn't catch that" */
    private var lastPeakDbfs = -100.0
    private var lastSource = Loudness.name(Loudness.SOURCE_MIC)

    private fun notHeard() {
        listener?.onSignal(lastPeakDbfs, lastSource)
        // only zeros came: the microphone was silenced (another app records, a call) rather than "didn't catch that"
        listener?.onError(if (lastPeakDbfs <= Loudness.DIGITAL_SILENCE_DBFS) VoiceError.MIC_SILENCED else VoiceError.NO_SPEECH)
    }

    override fun start(languageTag: String, listener: SpeechEngine.Listener) {
        this.listener = listener
        language = languageTag
        val r = recorderFactory(RecorderCallbacks())
        recorder = r
        if (!r.start()) {
            r.cancel()
            // say at once why nothing is recorded (stage 5zh): the microphone is taken or not allowed
            val permission = r.startFailure == PcmSource.Failure.PERMISSION
            listener.onTrace(VoiceTrace.RecordingFailed(if (permission) "permission" else "busy"))
            listener.onError(if (permission) VoiceError.PERMISSION else VoiceError.MIC_BUSY)
            return
        }
        listener.onTrace(VoiceTrace.RecordingStarted(Loudness.name(r.sourceUsed), micName(r)))
        listener.onMic(r.onHeadset, r.headsetFellBack)
        listener.onListening()
    }

    override fun stop() { recorder?.stop() }

    override fun cancel() {
        listener = null
        recorder?.cancel()
        cancelSignal.cancel()
        sender.cancel()
        texts.clear()
    }

    override fun destroy() = cancel()

    private inner class RecorderCallbacks : PcmRecorder.Listener {
        override fun onLevel(level: Float) { listener?.onLevel(level) }

        override fun onSourceWorked(source: Int) = this@OpenAiSpeechEngine.onSourceWorked(source)

        override fun onFirstFrame(atMs: Long) { listener?.onTrace(VoiceTrace.FirstFrame(atMs)) }

        override fun onHeadsetTried(up: Boolean, ms: Long) { listener?.onTrace(VoiceTrace.HeadsetTried(up, ms)) }

        override fun onHeadsetRejected() { listener?.onTrace(VoiceTrace.HeadsetRejected) }

        override fun onHeadsetHeard() { listener?.onTrace(VoiceTrace.HeadsetHeard) }

        override fun onPhoneLevels(beforeDbfs: Double, afterDbfs: Double) { listener?.onTrace(VoiceTrace.PhoneLevels(beforeDbfs, afterDbfs)) }
        override fun onEarlyLevels(dbfs: List<Int>) { listener?.onTrace(VoiceTrace.EarlyLevels(dbfs)) }
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

        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) {
            val r = recorder ?: return
            remember(r)
            val total = r.chunkedMs + r.audio.durationMs
            val end = when { byUser -> "done"; hadSpeech && total < maxRecordMs -> "pause"; else -> "max" }
            listener?.onTrace(VoiceTrace.Recorded(r.peakDbfs, hadSpeech, end, total))
            ended = true
            if (r.chunks == 0 && !Loudness.shouldSend(hadSpeech, byUser, r.audio.durationMs, r.peakDbfs, r.speechHeardMs)) { // nothing worth sending (and paying for)
                r.audio.wipe()
                done = true
                notHeard()
                return
            }
            listener?.onEndOfSpeech()
            // stage 5g-20: after pieces, the rest goes too if there is something in it
            val rest = r.audio.takeAll()
            val restPeak = Loudness.dbfs(rest.peak().toDouble())
            if (r.chunks > 0 && (rest.durationMs < MIN_REST_MS || restPeak < Loudness.SILENCE_DBFS)) rest.wipe()
            else {
                // a quiet microphone: raise the level before sending (only in memory, at most 30 dB)
                rest.applyGain(Loudness.gainFor(if (r.chunks > 0) restPeak else r.peakDbfs))
                sender.add(rest)
            }
            maybeDeliver()
        }

        /** stage 5g-20: a piece of a long recording: sent now, the recording goes on */
        override fun onChunk(audio: PcmBuffer, peakDbfs: Double) {
            if (done || fatal != null || peakDbfs < Loudness.SILENCE_DBFS) { audio.wipe(); return }
            audio.applyGain(Loudness.gainFor(peakDbfs))
            sender.add(audio)
        }

        override fun onNoiseStats(speechPercent: Int, noiseDbfs: Double, droppedPhrases: Int) {
            listener?.onTrace(VoiceTrace.Noise(speechPercent, noiseDbfs, droppedPhrases))
        }

        /** stage 5g-6: only noise, no speech: "Stopped: only noise", nothing is sent */
        override fun onOnlyNoise() {
            if ((recorder?.chunks ?: 0) > 0) return onEnd(true, false) // stage 5g-20: the pieces before are kept
            recorder?.let {
                remember(it)
                listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "noise", it.audio.durationMs))
                it.audio.wipe()
            }
            listener?.onSignal(lastPeakDbfs, lastSource)
            listener?.onError(VoiceError.ONLY_NOISE)
        }

        override fun onNoSpeech() {
            if ((recorder?.chunks ?: 0) > 0) return onEnd(true, false)
            recorder?.let {
                remember(it)
                listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "no_speech", it.audio.durationMs))
                it.audio.wipe()
            }
            notHeard()
        }

        private fun remember(r: PcmRecorder) {
            lastPeakDbfs = r.peakDbfs
            lastSource = Loudness.name(r.sourceUsed)
        }

        override fun onSilenced() {
            recorder?.let {
                remember(it)
                listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "silenced", it.audio.durationMs))
                it.audio.wipe()
            }
            listener?.onSignal(lastPeakDbfs, lastSource)
            listener?.onError(VoiceError.MIC_SILENCED)
        }

        override fun onFailed() {
            recorder?.let { listener?.onTrace(VoiceTrace.Recorded(it.peakDbfs, false, "failed", it.audio.durationMs)) }
            recorder?.audio?.wipe()
            listener?.onError(VoiceError.AUDIO)
        }
    }
}
