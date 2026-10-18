// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * End of speech (docs/VOICE_API.md, section 4). Stage 5g-6 (the owner: rustling, steps, birds, noise from the window and
 * the dictation never stops): a frame of 20 ms counts as speech only when ALL of this holds:
 * 1. the voice activity detector of WebRTC ([WebRtcVad], aggressive mode 2, without its hangover) calls it speech;
 * 2. it is clearly above the noise: louder than 3 times the noise level (the quietest 20 ms of the last 1.5 s, measured
 *    all the time, also in the pauses) and above an absolute floor of -60 dBFS;
 * 3. it is shaped like a voice: the bands below 1 kHz are not 10 dB below the bands above (birds, chirps, hiss are high).
 * Noise without speech is silence for the pause before sending (1.5 s) and for the automatic stop. End: at least 0.3 s
 * of speech, then the pause of the settings (0.8–2.5 s, 1.5 s by default). No speech for 8 s: nothing to send.
 * [vad] null: the loudness alone, as before stage 5g-6 (only for tests of the old behaviour).
 *
 * Stage 5g-19 (the owner: in the car on a bad road at speed the dictation never stops): with [silero] the Silero VAD
 * decides "speech or not" in chunks of 32 ms instead of WebRTC VAD and the voice shape. Its threshold follows the noise
 * ([sileroThresholds]: on 0.5 / off 0.35 in a quiet place; with the noise above -45 dBFS on 0.6…0.7 / off 0.35…0.45), with
 * hysteresis; the frame must be above -72 dBFS and 1.5 times the noise; speech counts after 250 ms; "only noise" stops
 * after 10 s without Silero speech. When the model is not there, WebRTC VAD as before ([vadName] says which).
 */
class SpeechEndDetector(
    private val frameMs: Int = FRAME_MS,
    private val endSilenceMs: Int = VoiceApiPrefs.DEFAULT_END_PAUSE_MS,
    private val minSpeechMs: Int = 300,
    private val noSpeechTimeoutMs: Int = 8000,
    /** RMS in PCM16 units: -60 dBFS */
    private val minLevel: Double = MIN_LEVEL,
    private val noiseFactor: Double = 3.0,
    private val vad: WebRtcVad? = WebRtcVad(VAD_MODE),
    /** stage 5g-19: a Silero stream for this detector (asked for at the first frame, on the recording thread); null: WebRTC */
    private val silero: (() -> SpeechProbability?)? = null,
) {
    enum class Event { NONE, END_OF_SPEECH, NO_SPEECH }

    /** RMS of the last 1.5 s: the quietest of them is the noise level (minimum statistics) */
    private val recent = ArrayDeque<Double>()
    private var totalMs = 0
    private var speechMs = 0
    private var silenceMs = 0
    /** at least [minSpeechMs] of speech was heard */
    var hadSpeech = false
        private set
    /**
     * Stage 5g-2: nothing like speech for [ms], and the noise level is known (at least half a second heard: before that
     * speech right at the start can't be told from noise). Only then may the microphone change.
     */
    fun quietFor(ms: Int) = !hadSpeech && speechMs == 0 && knownQuietMs >= ms

    /** time without anything like speech, counted only while the noise level is known */
    private var knownQuietMs = 0

    // ---- stage 5g-6: what the journal says about the noise ----
    /** frames heard, frames of speech, frames with sound that was no speech (noise) */
    var frames = 0
        private set
    var speechFrames = 0
        private set
    /** since the last speech: time, and the time with sound that was no speech */
    var msSinceSpeech = 0
        private set
    var noisyMsSinceSpeech = 0
        private set
    /** the noise level now, dBFS */
    val noiseDbfs: Double get() = Loudness.dbfs(recent.minOrNull() ?: 0.0)
    /** all speech heard (ms), also before [hadSpeech]: a stop by the user sends only with some speech */
    var speechHeardMs = 0
        private set

    /** only noise since the last speech: at least 10 % of that time had sound (knocks, steps, birds), none of it speech */
    fun onlyNoise() = msSinceSpeech > 0 && noisyMsSinceSpeech * 10 >= msSinceSpeech

    // ---- stage 5g-19: Silero ----
    private var sileroAsked = false
    private var stream: SpeechProbability? = null
    /** which detector decides: "silero", or "webrtc" with the reason Silero is not used (for the journal) */
    var vadName = "webrtc"
        private set
    val usesSilero get() = stream != null
    private val chunk = FloatArray(SileroVad.CHUNK)
    private var chunkN = 0
    private var sileroSpeech = false
    /** the probabilities of speech: sum, count, the highest; the last threshold "on" */
    private var probSum = 0.0
    var probCount = 0
        private set
    var probMax = 0f
        private set
    var threshold = 0f
        private set
    val probAvg: Float get() = if (probCount > 0) (probSum / probCount).toFloat() else 0f
    /** speech counts after this much; "only noise" stops after this long without a phrase of speech */
    private val minSpeech get() = if (usesSilero) SILERO_MIN_SPEECH_MS else minSpeechMs
    val onlyNoiseMs get() = if (usesSilero) SILERO_ONLY_NOISE_MS else WEBRTC_ONLY_NOISE_MS

    private fun startSilero() {
        sileroAsked = true
        val f = silero ?: return
        stream = try { f() } catch (e: Throwable) { null }
        vadName = if (stream != null) "silero" else "webrtc (silero: ${SileroVad.failure ?: "not loaded"})"
    }

    /**
     * the level of the background for the threshold: the quieter quarter of the last 1.5 s (the quietest 20 ms alone is
     * too low for the rumble of a road, which swings from frame to frame)
     */
    private fun roadDbfs(): Double {
        if (recent.isEmpty()) return Loudness.dbfs(0.0)
        val sorted = recent.sorted()
        return Loudness.dbfs(sorted[sorted.size / 4])
    }

    /** Silero over the (raised) copy of the frame: a decision every 32 ms, with hysteresis; the last one in between */
    private fun sileroVoice(s: SpeechProbability, f: ShortArray, n: Int): Boolean {
        for (i in 0 until n) {
            chunk[chunkN++] = f[i] / 32768f
            if (chunkN < SileroVad.CHUNK) continue
            chunkN = 0
            val p = try { s.probability(chunk) } catch (e: Throwable) {
                stream = null; vadName = "webrtc (silero failed: ${e.javaClass.simpleName})"; chunk.fill(0f); return lastVoice
            }
            probSum += p; probCount++; if (p > probMax) probMax = p
            val (on, off) = sileroThresholds(roadDbfs())
            threshold = on
            sileroSpeech = if (sileroSpeech) p >= off else p >= on
        }
        return sileroSpeech
    }

    /** one frame of the microphone (stage 5g-6): the voice activity detector, the noise level and the voice shape */
    fun feed(frame: ShortArray, n: Int): Event {
        if (!sileroAsked) startSilero()
        val rms = rms(frame, n)
        val v = vad ?: return feed(rms, true)
        // a quiet microphone (-45 … -55 dBFS, stage 5b) is below the minimum energy of the VAD: the VAD gets a copy raised
        // so that the noise level (the quietest 20 ms of the last 1.5 s) is near -50 dBFS, by at most 30 times and never
        // over -6 dBFS; what is sent is not changed. By the noise, not by the loudest sound: the gain stays the same when
        // speech begins, so the VAD never hears raised noise as loud as the speech after it (measured 2026-10-14)
        recentPeaks.addLast(Loudness.peak(frame, n))
        if (recentPeaks.size > NOISE_WINDOW_FRAMES) recentPeaks.removeFirst()
        val floor = min(recent.minOrNull() ?: rms, rms).coerceAtLeast(1.0)
        val gain = minOf(VAD_MAX_GAIN, VAD_NOISE_TARGET / floor, VAD_PEAK_LIMIT / recentPeaks.max().coerceAtLeast(1)).coerceAtLeast(1.0)
        if (vadFrame.size < n) vadFrame = ShortArray(n)
        for (i in 0 until n) vadFrame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        // the VAD needs whole frames of 10, 20 or 30 ms; a short read keeps the last decision
        val s = stream
        val voice = if (s != null) sileroVoice(s, vadFrame, n)
            else if (n == 320 || n == 160 || n == 480) v.process(PcmBuffer.SAMPLE_RATE, vadFrame, n) && v.lastRaw && voiceShaped(v.features)
            else lastVoice
        vadFrame.fill(0, 0, n)
        lastVoice = voice
        return feed(rms, voice)
    }
    private var lastVoice = false
    private var heardRun = 0
    private var speechRun = 0
    private val recentPeaks = ArrayDeque<Int>()
    private var vadFrame = ShortArray(320)

    /** the loudness alone (before stage 5g-6, tests of the old behaviour) */
    fun feed(rms: Double): Event = feed(rms, true)

    private fun feed(rms: Double, voice: Boolean): Event {
        totalMs += frameMs
        recent.addLast(rms)
        if (recent.size > NOISE_WINDOW_FRAMES) recent.removeFirst()
        val noise = recent.min()
        // stage 5g-6: what the VAD calls a voice may be quieter than the floor of the loudness alone (a quiet microphone)
        // stage 5g-19: Silero tells speech from noise itself; the frame must only stand out a little from the noise
        val factor = if (usesSilero) SILERO_NOISE_FACTOR else noiseFactor
        val loud = rms > max(if (voice && vad != null) MIN_VOICE_LEVEL else minLevel, noise * factor)
        // stage 5g-6: speech only in runs of at least 60 ms; a click, a knock or a step (one frame) neither starts speech
        // nor breaks the pause after it
        speechRun = if (loud && voice) speechRun + 1 else 0
        val speech = speechRun >= HEARD_RUN_FRAMES
        frames++
        // speech heard, also quiet speech below the absolute floor; only in runs of at least 60 ms: a click, a knock or
        // a step is one frame (measured 2026-10-14), a syllable is longer
        if (voice && rms > noise * factor) {
            heardRun++
            if (heardRun == HEARD_RUN_FRAMES) speechHeardMs += HEARD_RUN_FRAMES * frameMs
            else if (heardRun > HEARD_RUN_FRAMES) speechHeardMs += frameMs
        } else heardRun = 0
        if (speech) {
            val counted = if (speechRun == HEARD_RUN_FRAMES) HEARD_RUN_FRAMES else 1 // the run counts from its first frame
            speechFrames += counted
            speechMs += counted * frameMs
            silenceMs = 0
            msSinceSpeech = 0
            noisyMsSinceSpeech = 0
        } else {
            silenceMs += frameMs
            msSinceSpeech += frameMs
            if (rms > max(minLevel, noise * noiseFactor) || rms > NOISY_LEVEL) noisyMsSinceSpeech += frameMs
            // single loud frames in noise are no speech: before speech counts, a pause of 0.3 s starts it again
            if (!hadSpeech && silenceMs >= minSpeech) speechMs = 0
        }
        if (!hadSpeech && speechMs >= minSpeech) hadSpeech = true
        knownQuietMs = if (recent.size * frameMs < NOISE_KNOWN_MS || speech) 0 else knownQuietMs + frameMs
        return when {
            hadSpeech && silenceMs >= endSilenceMs -> Event.END_OF_SPEECH
            !hadSpeech && totalMs >= noSpeechTimeoutMs -> Event.NO_SPEECH
            else -> Event.NONE
        }
    }

    /**
     * Continuous dictation (stage 5g): the phrase was handed over, the next one starts. The noise level of the last 1.5 s
     * is kept; the time without speech counts again from now (it ends the dictation after the silence of the settings).
     */
    fun nextPhrase() {
        speechHeardMs = 0
        heardRun = 0
        speechRun = 0
        totalMs = 0
        knownQuietMs = 0
        speechMs = 0
        silenceMs = 0
        hadSpeech = false
    }

    companion object {
        const val FRAME_MS = 20
        /** stage 5g-19: Silero: speech counts after 250 ms, "only noise" after 10 s, a frame 1.5 times (3.5 dB) above the noise */
        const val SILERO_MIN_SPEECH_MS = 250
        const val SILERO_ONLY_NOISE_MS = 10_000
        const val WEBRTC_ONLY_NOISE_MS = 20_000
        const val SILERO_NOISE_FACTOR = 1.5
        /** stage 5g-19: noise above this (dBFS) is loud (a road): Silero needs to be surer */
        const val LOUD_NOISE_DBFS = -45.0

        /**
         * the thresholds of the probability of speech (on, off) for the noise now: in a quiet place 0.5 / 0.35; with the
         * noise above -45 dBFS from 0.6 / 0.35 up to 0.7 / 0.45 at -35 dBFS and above
         */
        fun sileroThresholds(noiseDbfs: Double): Pair<Float, Float> {
            if (noiseDbfs <= LOUD_NOISE_DBFS) return 0.5f to 0.35f
            val t = ((noiseDbfs - LOUD_NOISE_DBFS) / 10.0).coerceIn(0.0, 1.0).toFloat()
            return (0.6f + 0.1f * t) to (0.35f + 0.1f * t)
        }
        /** WebRTC VAD mode: 2 "aggressive" (measured 2026-10-14: mode 3 lost quiet speech, modes 0 and 1 took tones and noise) */
        const val VAD_MODE = 2
        /** voice shape: the loudest band below 1 kHz may be at most this much (10 dB, Q4) below the loudest band above */
        const val VOICE_SHAPE_Q4 = 160
        /** the VAD hears a quiet microphone raised so that its noise is about -50 dBFS (RMS), by at most 30 times (29.5 dB), peaks at most -6 dBFS */
        const val VAD_NOISE_TARGET = 104.0
        const val VAD_MAX_GAIN = 30.0
        const val VAD_PEAK_LIMIT = 16384.0
        /** speech counts as heard ([speechHeardMs]) only in runs of at least this many frames */
        const val HEARD_RUN_FRAMES = 3
        /** a frame this loud (-50 dBFS) with no speech is noise for "only noise" */
        const val NOISY_LEVEL = 32768.0 / 316.0
        fun voiceShaped(f: ShortArray): Boolean {
            val low = maxOf(f[0].toInt(), f[1].toInt(), f[2].toInt())
            val high = maxOf(f[3].toInt(), f[4].toInt(), f[5].toInt())
            return low >= high - VOICE_SHAPE_Q4
        }
        const val NOISE_WINDOW_FRAMES = 75
        /** the noise level counts as known after this much sound */
        const val NOISE_KNOWN_MS = 500
        /** -60 dBFS as RMS of PCM16 */
        const val MIN_LEVEL = 32768.0 / 1000.0
        /** -72 dBFS as RMS: the floor for what the VAD calls a voice (speech with a peak of -55 dBFS has an RMS near -67) */
        const val MIN_VOICE_LEVEL = 32768.0 / 3981.0

        fun rms(samples: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) { val s = samples[i].toDouble(); sum += s * s }
            return sqrt(sum / count)
        }

        /** 0..1 for the ring around the microphone: -60 dBFS and below is 0, -10 dBFS and above is 1 */
        fun level(rms: Double): Float = ((20 * log10(max(rms, 1.0) / 32768.0) + 60) / 50).toFloat().coerceIn(0f, 1f)
    }
}

/** where the samples come from: the microphone on the phone, synthetic sound in tests. All calls but [start] on the recording thread. */
interface PcmSource {
    fun start(): Boolean
    /** blocks until [buffer] is filled; negative on error */
    fun read(buffer: ShortArray): Int
    fun stop()
    /** why [start] failed (stage 5zh: say it at once) */
    val failure: Failure? get() = null
    /** what is there now without waiting (0: nothing yet); the headset is read so next to the phone (stage 5g-2) */
    fun readNow(buffer: ShortArray): Int = read(buffer)

    enum class Failure { PERMISSION, BUSY }
}

/** Loudness in dBFS (0 = the loudest possible sample) and the two microphone sources we try */
object Loudness {
    const val SOURCE_MIC = MediaRecorder.AudioSource.MIC
    const val SOURCE_VOICE_RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION
    /** stage 5g-12: the phone's microphone while a headset is tried (not routed to the hands-free device in the call mode) */
    const val SOURCE_CAMCORDER = MediaRecorder.AudioSource.CAMCORDER
    /** below this peak in the first 2 s a source counts as empty: the other one is tried once */
    const val EMPTY_SOURCE_DBFS = -60.0
    const val SOURCE_CHECK_MS = 2000L

    fun dbfs(amplitude: Double): Double = 20 * log10(max(amplitude, 1.0) / 32768.0)

    fun peak(samples: ShortArray, count: Int): Int {
        var p = 0
        for (i in 0 until count) { val a = abs(samples[i].toInt()); if (a > p) p = a }
        return p
    }

    /** a peak of 1 or 0: only zeros came, the system silenced the microphone (another app records, a call) */
    const val DIGITAL_SILENCE_DBFS = -90.0

    /** a recording whose peak is below this is silence: never sent (and never amplified) */
    const val SILENCE_DBFS = -70.0
    /** "Done" or the microphone pressed again: sent if at least this long, whatever the speech detector says */
    const val MIN_MANUAL_MS = 600L

    /**
     * Is the recording worth sending (stage 5b)? Silence never. A stop by the user always (if not too short), even
     * when the speech detector heard nothing: the microphone may just be quiet. Otherwise only with speech.
     */
    fun shouldSend(hadSpeech: Boolean, byUser: Boolean, durationMs: Long, peakDbfs: Double, speechHeardMs: Long? = null): Boolean = when {
        peakDbfs < SILENCE_DBFS -> false
        // stage 5g-6: a stop by the user sends a recording without detected speech only if some speech was heard in it
        // (also quiet speech below the floor); pure noise is never sent nor paid for
        byUser -> hadSpeech || (durationMs >= MIN_MANUAL_MS && (speechHeardMs == null || speechHeardMs >= MIN_MANUAL_SPEECH_MS))
        else -> hadSpeech
    }

    /** a stop by the user sends only with at least this much speech heard (stage 5g-6) */
    const val MIN_MANUAL_SPEECH_MS = 200L

    /**
     * Gain before sending (stage 5b): a quiet recording (peak below -20 dBFS) is raised to a peak of about -3 dBFS,
     * by at most 30 dB; silence (below -70 dBFS) and normal recordings are left as they are.
     */
    fun gainFor(peakDbfs: Double): Double = when {
        peakDbfs < SILENCE_DBFS || peakDbfs >= -20.0 -> 1.0
        else -> min(10.0.pow((-3.0 - peakDbfs) / 20), 10.0.pow(30.0 / 20))
    }

    fun other(source: Int) = if (source == SOURCE_MIC) SOURCE_VOICE_RECOGNITION else SOURCE_MIC

    /** not an Android source: the microphone of a Bluetooth headset (stage 5g), in the journal and "Check microphone" */
    const val SOURCE_HEADSET = -1
    /** not an Android source: MIC with the noise suppressor and the gain control of the phone ("Check microphone", 5g-6) */
    const val SOURCE_MIC_EFFECTS = -2

    fun name(source: Int) = when (source) {
        SOURCE_VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        SOURCE_HEADSET -> "HEADSET"
        SOURCE_MIC_EFFECTS -> "MIC+NS/AGC"
        SOURCE_CAMCORDER -> "CAMCORDER"
        else -> "MIC"
    }

    /** the headset channel must be up and give sound within this time, else the phone records (stage 5g) */
    const val HEADSET_CHECK_MS = 1500L
}

/** for the journal (stage 5g … 5g-3): which microphone recorded, and what happened to the headset */
fun micName(r: PcmRecorder): String = when {
    r.onHeadset -> "phone→headset"
    r.headsetLeft -> "headset, then phone (headset gone)"
    r.headsetRejected -> "headset silent→phone"
    r.headsetAborted -> "phone (speech began, headset let go)"
    r.headsetFellBack -> "headset→phone"
    else -> "phone"
}

/**
 * AudioRecord, 16 kHz mono PCM16, from [audioSource] (MIC or VOICE_RECOGNITION). The caller has checked the RECORD_AUDIO
 * permission. [preferredDevice]: the input of a Bluetooth headset (stage 5g), else the system's choice (the phone).
 */
class AudioRecordSource(
    private val audioSource: Int = Loudness.SOURCE_MIC,
    private val sampleRate: Int = PcmBuffer.SAMPLE_RATE,
    private val preferredDevice: android.media.AudioDeviceInfo? = null,
    /** never wait in [readNow] (the headset, read next to the phone) */
    private val nonBlocking: Boolean = false,
    /** stage 5g-6: the noise suppressor and the automatic gain control of the phone, where it has them */
    private val effects: Boolean = false,
) : PcmSource {
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    private var agc: android.media.audiofx.AutomaticGainControl? = null
    /** which effects are on: "ns", "agc", "ns+agc" or "" */
    var effectsOn = ""
        private set
    private var record: AudioRecord? = null
    override var failure: PcmSource.Failure? = null
        private set

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = try {
        failure = PcmSource.Failure.BUSY // anything but a missing permission: the microphone is taken or broken
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) false
        else {
            val r = AudioRecord(audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, sampleRate)) // at least half a second of buffer
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); false }
            else {
                if (android.os.Build.VERSION.SDK_INT >= 23) preferredDevice?.let { r.preferredDevice = it }
                if (effects) enableEffects(r.audioSessionId)
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); false } else { record = r; failure = null; true }
            }
        }
    } catch (e: SecurityException) {
        failure = PcmSource.Failure.PERMISSION
        false
    } catch (e: Exception) { false }

    override fun read(buffer: ShortArray): Int = try { record?.read(buffer, 0, buffer.size) ?: -1 } catch (e: Exception) { -1 }

    override fun readNow(buffer: ShortArray): Int =
        if (!nonBlocking || android.os.Build.VERSION.SDK_INT < 23) read(buffer)
        else try { record?.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING) ?: -1 } catch (e: Exception) { -1 }

    override fun stop() {
        try { ns?.release() } catch (_: Exception) { }
        try { agc?.release() } catch (_: Exception) { }
        ns = null; agc = null
        record?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        record = null
    }

    private fun enableEffects(session: Int) {
        val on = mutableListOf<String>()
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable())
                ns = android.media.audiofx.NoiseSuppressor.create(session)?.also { it.enabled = true; on.add("ns") }
        } catch (_: Exception) { }
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable())
                agc = android.media.audiofx.AutomaticGainControl.create(session)?.also { it.enabled = true; on.add("agc") }
        } catch (_: Exception) { }
        effectsOn = on.joinToString("+")
    }
}

/**
 * Records one phrase on its own thread into memory ([audio]); never to a file. Ends by itself at the end of speech,
 * after 8 s without speech or at [maxMs]; [stop] ends it now (the result follows), [cancel] drops and wipes it
 * without any callback. Callbacks go through [post] (the main thread on the phone).
 *
 * Microphone source (stage 5b, quiet HONOR microphone): starts with [firstSource]; if its peak stays below -60 dBFS
 * in the first 2 s, the recording is opened again once with the other source (MIC ↔ VOICE_RECOGNITION) and what was
 * recorded so far (silence) is wiped. A source that gave a signal is reported through [Listener.onSourceWorked].
 *
 * Bluetooth headset (stage 5g-3, [headset] set only for a PROVEN headset, see [MicRouting]): the phone records from the
 * very first frame as in 4351. Asking for the headset channel makes the phone microphone of the HONOR quiet, so it is
 * asked for only in a quiet moment (no speech for 0.3 s, the noise known), and given back AT ONCE when speech begins on
 * the phone before the headset took over, or when it is not up with sound within 1.5 s. When it is up, it takes over,
 * but on probation: the phone keeps recording next to it and both are only buffered. The first loud sound decides: the
 * headset heard it (peak -30 dBFS or more, or not 12 dB below the phone) → the headset stays and its buffer goes on;
 * the phone heard it and the headset did not → the headset is no headset: its channel is given back, the phone's
 * buffer is used instead (nothing is lost), the device is remembered as "no headset" until it connects again.
 * Silence for [PROBATION_MAX_MS]: the headset stays. A headset that goes away gives the recording back to the phone.
 * The channel is always given back when the recording ends, also after a cancel or a failure.
 *
 * Continuous dictation (stage 5g, [continuous] set): the microphone stays open and the sound is cut into phrases at the
 * pauses. Silence before a phrase is only kept for [Continuous.preRollMs] (wiped as it drops out), so the silence
 * between phrases is never sent nor paid for; a phrase is its speech plus the pause after it. Each phrase is handed
 * over with [Listener.onPhrase]; the recording ends at [stop], after the silence of the settings (the detector's
 * "no speech" time), at [maxMs] (the session limit) or when the phrases reach [Continuous.maxSpeechMs] (the minutes left
 * this month), always with [Listener.onContinuousEnd] after the last phrase.
 */
class PcmRecorder(
    private val sourceFactory: (Int) -> PcmSource,
    private val firstSource: Int,
    private val maxMs: Long,
    private val listener: Listener,
    private val post: (Runnable) -> Unit,
    private val detectorFactory: () -> SpeechEndDetector = { SpeechEndDetector() },
    private val threadFactory: (Runnable) -> Thread = { Thread(it, "aikb-voice-record") },
    private val continuous: Continuous? = null,
    private var headset: HeadsetMic? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * stage 5g-5: the headset is PROVEN (earbuds playing the media): its channel is asked for at the tap, not only in a
     * quiet moment, and not let go when speech begins (people in earbuds speak at once, the phone may lie far away). The
     * start of the phrase comes from the phone until the headset gives sound, then the headset goes on, without a gap.
     */
    private var headsetEager: Boolean = false,
    /**
     * stage 5g-7: the headset is no proven headset but a car or another hands-free device. Asked for at once and kept
     * like earbuds ([headsetEager]), but its probation is the strict one of 5g-3: silence where the phone hears the
     * speaker sends it back to the phone (nothing lost) and marks it.
     */
    private val headsetCar: Boolean = false,
    /**
     * stage 5g-12 (journal 4385, car F0: the phone heard nothing for 1.1–1.5 s while the channel came up, and on probation
     * "headset" and "phone" had the same level in all 6 recordings): in the call mode Android routes the recording of the
     * MIC source to the hands-free device (forced "record" route), also the phone's own recording; so until the channel is
     * up NO microphone records. While a headset is tried the phone records with this source instead (CAMCORDER: the
     * built-in microphone in every mode of the audio policy); null: the source of the settings, as before
     */
    private val phoneSourceWhileHeadset: Int? = null,
    /**
     * stage 5g-18: a car-like device: all the sources of the phone are opened next to the recording while its link comes
     * up ([PhoneProbe]); a deaf frame of the recording is filled from one that hears; null: as before
     */
    private val probe: PhoneProbe? = null,
    /**
     * stage 5g-20 (probe 4397: every source of the HONOR phone is deaf while the car's link comes up): a car-like device
     * in a continuous dictation is asked for only in the first pause AFTER a phrase was handed over; until then the phone
     * records with the source of the settings, as without Bluetooth. Speech that begins while the link comes up keeps
     * the phone; the next pause tries again (at most [CAR_TRIES] times)
     */
    private val headsetAfterPhrase: Boolean = false,
    /** stage 5g-20: one recording (not continuous) is cut into pieces of at most this long ([Listener.onChunk]); null: one piece */
    private val chunkMs: Long? = null,
    /** stage 5g-20: a player was paused at the tap: the first [muteStartMs] are silence unless speech is heard in them */
    private val muteStartMs: Long = 0,
) {
    /** [maxPhraseMs]: a longer phrase is cut and sent (the longest recording of the settings) */
    class Continuous(val maxPhraseMs: Long, val maxSpeechMs: Long, val preRollMs: Long = PRE_ROLL_MS)

    interface Listener {
        fun onLevel(level: Float)
        /** recording ended (end of speech, maximum length or [stop]); [hadSpeech]: the detector heard speech */
        fun onEnd(hadSpeech: Boolean, byUser: Boolean)
        /** 8 s without speech: nothing to send */
        fun onNoSpeech()
        /** both sources gave only zeros for 2 s each: the microphone is silenced, said at once (stage 5zh) */
        fun onSilenced() = onNoSpeech()
        fun onFailed()
        /** [source] gave a signal: remember it for the next time */
        fun onSourceWorked(source: Int) {}
        /** continuous dictation: one phrase, handed over (the receiver wipes it) */
        fun onPhrase(audio: PcmBuffer, peakDbfs: Double) {}
        /** continuous dictation is over, after the last [onPhrase]: [reason] done, silence, limit or cap */
        fun onContinuousEnd(reason: String) {}
        /** the headset records from now on ([headset]), or the phone again ([fellBack]: the headset did not work) */
        fun onMic(headset: Boolean, fellBack: Boolean) {}
        /** the first sound came from the microphone, at [atMs] (wall clock), for the journal (stage 5g-2) */
        fun onFirstFrame(atMs: Long) {}
        /** the headset channel came up with sound ([up]) or was given up, [ms] after it was asked for (stage 5g-2) */
        fun onHeadsetTried(up: Boolean, ms: Long) {}
        /** stage 5g-3: the headset gave silence where the phone heard sound: no headset */
        fun onHeadsetRejected() {}
        /** stage 5g-3: the headset heard the speaker (a loud sound on its microphone): it works */
        fun onHeadsetHeard() {}
        /** stage 5g-7: the peaks of the headset (or car) and of the phone on probation, when it decided (dBFS, for the journal) */
        fun onProbationLevels(headsetDbfs: Double, phoneDbfs: Double, afterUpMs: Long = 0, speechFrames: Int = 0) {}
        /** stage 5g-10: nobody spoke while the channel was tried: the phone goes on, neither a mark nor a strike */
        fun onHeadsetUndecided() {}
        /** stage 5g-11: the phone's microphone was deaf after the rollback and was opened anew ([times] so far) */
        fun onPhoneReopened(times: Int) {}
        /** stage 5g-12: on probation [same] of [frames] frames had the same peak in both microphones (the same stream) */
        fun onSameSound(same: Int, frames: Int) {}
        /** stage 5g-12, at the end: how long the recording came from the phone and from the headset (ms) */
        fun onSourcesMs(phoneMs: Long, headsetMs: Long) {}
        /**
         * stage 5g-10: the time the phone heard nothing while the channel came up (the HONOR phone goes deaf as soon as the
         * call mode is on): [deafMs] of digital silence in [connectMs], the phone's loudest peak in that time
         */
        fun onGap(deafMs: Long, connectMs: Long, phonePeakDbfs: Double) {}
        /** stage 5g-3: the phone's peak before the headset was asked for and after it was given back (dBFS, for the journal) */
        fun onPhoneLevels(beforeDbfs: Double, afterDbfs: Double) {}
        /** stage 5g-6: no speech, only noise (8 s of one phrase, or 20 s of a continuous dictation): stopped */
        fun onOnlyNoise() = onNoSpeech()
        /** stage 5g-6, for the journal at the end: share of speech frames (%), the noise level, phrases dropped as noise */
        fun onNoiseStats(speechPercent: Int, noiseDbfs: Double, droppedPhrases: Int) {}
        /** stage 5g-19, at the end: which detector decided, the probabilities of speech (avg, max) and the last threshold */
        fun onVad(name: String, probAvg: Float, probMax: Float, threshold: Float) {}
        /** stage 5g-20: one recording: a piece of at most [chunkMs] (cut in a quiet moment), the recording goes on */
        fun onChunk(audio: PcmBuffer, peakDbfs: Double) { audio.wipe() }
        /** stage 5g-20: the car-like device is asked for now (in a pause): the badge "connecting" */
        fun onHeadsetAsked() {}
        /** stage 5g-18, at the end: what each source of the phone heard while the link came up ([PhoneProbe.summary]) */
        fun onProbe(summary: String, filledMs: Long) {}
    }

    /** where the headset is: not wanted, waiting for a quiet moment, coming up, on probation, recording, given up */
    private enum class Hs { NONE, WAIT_QUIET, CONNECTING, PROBATION, ON, DONE }

    val audio = PcmBuffer()
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    private var source: PcmSource? = null
    private var detector = detectorFactory()
    /** the source in use now (MIC, VOICE_RECOGNITION or HEADSET) */
    @Volatile var sourceUsed = firstSource
        private set
    /** the loudest sample since the current source was opened */
    @Volatile var peak = 0
        private set
    /** why no source could be opened (null: started, or the reason is unknown) */
    var startFailure: PcmSource.Failure? = null
        private set
    /** the source was switched once already */
    var switched = false
        private set
    var thread: Thread? = null
        private set
    /** continuous dictation: phrases handed over and their length (what is sent and paid for) */
    @Volatile var phrases = 0
        private set
    @Volatile var phrasesMs = 0L
        private set

    @Volatile private var hs = if (headset != null) Hs.WAIT_QUIET else Hs.NONE
    /** the headset records now (accepted after its probation) */
    val onHeadset get() = hs == Hs.ON
    /** the headset was asked for but did not come up with sound in 1.5 s, or could not be opened */
    @Volatile var headsetFellBack = false
        private set
    /** speech began on the phone while the headset came up: it was let go at once (stage 5g-3) */
    @Volatile var headsetAborted = false
        private set
    /** the headset gave silence where the phone heard sound (stage 5g-3) */
    @Volatile var headsetRejected = false
        private set
    /** the headset recorded, then went away: the phone took over (stage 5g-2) */
    @Volatile var headsetLeft = false
        private set
    /** the headset channel was asked for at all in this recording */
    @Volatile var headsetAskedFor = false
        private set

    private var headsetSource: PcmSource? = null
    /** the phone, kept open next to the headset during its probation */
    private var phoneSource: PcmSource? = null
    private var headsetPeak = 0
    private var askedAt = 0L
    private var attemptSamples = 0L
    /** probation: what each microphone recorded since the headset took over (copies, wiped after the decision) */
    private val probeHeadset = mutableListOf<ShortArray>()
    private val probePhone = mutableListOf<ShortArray>()
    private var probeHeadsetPeak = 0
    private var probePhonePeak = 0
    private var probeSamples = 0L
    /** the phone's peak in the last 0.5 s before the headset was asked for, and in the 0.8 s after it was given back */
    private val recentPhonePeaks = ArrayDeque<Int>()
    private var phoneBefore = 0
    private var phoneAfter = 0
    private var afterSamples = -1L

    /** stage 5g-2: a headset connected while recording ([offerHeadset]) or went away ([headsetGone]) */
    @Volatile private var offered: HeadsetMic? = null
    @Volatile private var gone = false

    /** a proven headset was connected while recording: asked for at the next quiet moment */
    fun offerHeadset(h: HeadsetMic, eager: Boolean = false) { offeredEager = eager; offered = h }
    @Volatile private var offeredEager = false

    /** the headset went away while recording: back to the phone at once, the recording goes on */
    fun headsetGone() { gone = true }

    val peakDbfs: Double get() = Loudness.dbfs(peak.toDouble())

    /** false if no microphone source can be opened */
    fun start(): Boolean {
        // the phone first, always: it records from the first millisecond and nothing in the routing is touched yet
        source = open(firstSource) ?: open(Loudness.other(firstSource))?.also { switched = true } ?: return false
        // stage 5g-18: the other sources of the phone open before the device is asked for
        probe?.start(Loudness.name(phoneSourceWhileHeadset ?: firstSource))
        // proven earbuds: at once (stage 5g-5); stage 5g-18: every other tap with a car 300 ms after the phone sources
        if (headset != null && headsetEager && linkDelayMs() == 0L) askForHeadset()
        thread = threadFactory(Runnable { loop() }).also { it.start() }
        return true
    }

    private fun open(audioSource: Int): PcmSource? {
        val tryingHeadset = headset != null && ((hs == Hs.WAIT_QUIET && !headsetAfterPhrase) || hs == Hs.CONNECTING || hs == Hs.PROBATION)
        val physical = if (tryingHeadset) phoneSourceWhileHeadset ?: audioSource else audioSource
        if (physical != audioSource) phoneSourceSwapped = true
        val s = sourceFactory(physical)
        if (!s.start()) {
            if (startFailure != PcmSource.Failure.PERMISSION) startFailure = s.failure
            return null
        }
        sourceUsed = audioSource
        return s
    }

    fun stop() { stopRequested = true }

    fun cancel() {
        cancelled = true
        if (thread?.isAlive != true) audio.wipe() // else the recording thread wipes it when it ends
    }

    private fun deliver(action: Listener.() -> Unit) = post(Runnable { if (!cancelled) listener.action() })

    /** stage 5g-18: how long after the start the device is asked for (the order tried by [PhoneProbe]) */
    private fun linkDelayMs() = probe?.takeIf { !it.closed }?.waitBeforeLinkMs ?: 0L

    /** stage 5g-18: the probe is done (the second after the link is measured, or the recording ends): closed */
    private var probeSaid = false
    private fun endProbe() {
        val p = probe ?: return
        p.close()
        if (probeSaid) return
        probeSaid = true
        val s = p.summary(); val filled = p.filledMs
        deliver { onProbe(s, filled) }
    }

    // ---- the headset (stage 5g-3) ----

    /** a quiet moment: the channel is asked for now; its recording is read next to the phone without waiting */
    private fun askForHeadset() {
        val h = headset ?: return
        headsetAskedFor = true
        phoneBefore = recentPhonePeaks.maxOrNull() ?: 0
        askedAt = clock()
        attemptSamples = 0
        headsetPeak = 0
        probe?.connecting(totalSamples)
        // stage 5g-20: the car is asked for in a pause: the phone moves to the source that stays on the built-in
        // microphone in the call mode (5g-12), so its probation compares two microphones
        if (headsetAfterPhrase) {
            carTries++
            phoneSourceWhileHeadset?.takeIf { it != sourceUsed }?.let { physical ->
                val s = sourceFactory(physical)
                if (s.start()) { source?.stop(); source = s; phoneSourceSwapped = true }
            }
            deliver { onHeadsetAsked() }
        }
        // stage 5g-14: a car's recording is opened only once its link is up and the call mode is on ([HeadsetMic.ready])
        val s = if (!h.connect()) null else if (!h.ready()) WAITING else h.source().takeIf { it.start() }
        if (s == null) { letGoOfHeadset(); headsetFellBack = true; deliver { onHeadsetTried(false, 0) }; return }
        headsetSource = s.takeIf { it !== WAITING }
        hs = Hs.CONNECTING
    }

    /** stage 5g-10: the time the phone heard nothing while the channel came up, said once per recording */
    private var connectSamples = 0L
    private var deafSamples = 0L
    private var connectPeak = 0
    private var gapSaid = false
    private fun reportGap() {
        if (gapSaid || connectSamples == 0L) return
        gapSaid = true
        val deaf = ms(deafSamples); val total = ms(connectSamples); val peakDb = Loudness.dbfs(connectPeak.toDouble())
        deliver { onGap(deaf, total, peakDb) }
    }

    /** the channel is given back at once; the phone's level is measured for 0.8 s from now (for the journal) */
    private fun letGoOfHeadset() {
        probe?.linkEnded(totalSamples, false)
        reportGap()
        if (headsetAskedFor) { deafWatchSamples = 0; deafReopens = 0; deafWatchUntil = totalSamples + PHONE_WATCH_MS * PcmBuffer.SAMPLE_RATE / 1000 }
        headsetSource?.stop()
        headsetSource = null
        headset?.release()
        hs = Hs.DONE
        if (headsetAskedFor) { afterSamples = 0; phoneAfter = 0; settleSamples = 0 }
    }

    /**
     * stage 5g-3 (journal: a peak of 0 dBFS and no speech found after the channel was given back): the phone settles
     * for [SETTLE_MS]; a click in that time (a frame at full scale) is silenced and no frame of it counts for the peak
     */
    private var settleSamples = -1L

    /** stage 5g-21: the loudest sample of the phone in the first 2 s */
    @Volatile private var earlyPeak = 0
    val earlyPeakDbfs: Double get() = Loudness.dbfs(earlyPeak.toDouble())

    /** stage 5g-20: how often the car was asked for in a pause */
    private var carTries = 0
    /** stage 5g-20: one recording, the pieces handed over so far and their length */
    @Volatile var chunks = 0
        private set
    @Volatile var chunkedMs = 0L
        private set

    /** the phone's own source (MIC or VOICE_RECOGNITION) while the headset is on probation */
    private var phoneSourceType = firstSource

    /** the headset is up with sound: it records from now on, on probation; the phone stays open next to it */
    private fun startProbation() {
        val h = headsetSource ?: return
        phoneSource = source
        phoneSourceType = sourceUsed
        source = h
        headsetSource = null
        sourceUsed = Loudness.SOURCE_HEADSET
        probeHeadsetPeak = 0; probePhonePeak = 0; probeSamples = 0
        probeSpeechFrames = 0; hSpeechPeak = 0; pSpeechPeak = 0; levelsSaid = false; probeUndecided = false
        probeBothFrames = 0; probeSameFrames = 0
        probeDetH = detectorFactory(); probeDetP = detectorFactory()
        probe?.linkEnded(totalSamples, true)
        reportGap()
        hs = Hs.PROBATION
    }

    /** the probation decides: null while it does not know yet */
    private fun probationVerdict(): Boolean? = probationRule().also { v ->
        if (v != null && !levelsSaid) {
            levelsSaid = true
            val speech = headsetEager && !headsetCar
            val h = Loudness.dbfs((if (speech) probeHeadsetPeak else hSpeechPeak).toDouble())
            val p = Loudness.dbfs((if (speech) probePhonePeak else pSpeechPeak).toDouble())
            val upMs = ms(probeSamples); val frames = probeSpeechFrames
            val same = probeSameFrames; val both = probeBothFrames
            deliver { onProbationLevels(h, p, upMs, frames) }
            deliver { onSameSound(same, both) }
        }
    }

    /** stage 5g-10: what the probation compared, only on frames where one of the microphones heard speech */
    private var probeDetH: SpeechEndDetector? = null
    private var probeDetP: SpeechEndDetector? = null
    private var probeSpeechFrames = 0
    private var hSpeechPeak = 0
    private var pSpeechPeak = 0
    private var levelsSaid = false
    private var probeBothFrames = 0
    private var probeSameFrames = 0
    /** the probation ended without a verdict (nobody spoke): the phone goes on, no strike */
    private var probeUndecided = false

    private fun speechIn(d: SpeechEndDetector, f: ShortArray, n: Int): Boolean { d.feed(f, n); return d.frames > 0 && d.msSinceSpeech == 0 }

    /**
     * Stage 5g-11 (journal 4383, the owner's car FE: "decided 220 ms after the channel was up on 0 frames", then the
     * recording was lost): ONE rule for every device, proven earbuds and cars alike, and the verdict "silent" only from
     * speech: (1) nothing is decided before [PROBATION_MIN_UP_MS] after the channel is up and [PROBATION_MIN_SPEECH_FRAMES]
     * frames with speech in either microphone; (2) "silent" (false) only after [PROBATION_REJECT_AFTER_MS], slow devices
     * may start their sound late; (3) no speech at all is no verdict: both microphones go on being listened to (the
     * phone's sound is buffered) until speech comes, at most [PROBATION_NO_SPEECH_CAP_MS], then the phone without a verdict.
     * Proven earbuds that clearly hear the speaker (loud) are accepted at once, as before.
     */
    private fun probationRule(): Boolean? {
        val proven = headsetEager && !headsetCar
        val hAll = Loudness.dbfs(probeHeadsetPeak.toDouble())
        val pAll = Loudness.dbfs(probePhonePeak.toDouble())
        if (proven && hAll >= PROBATION_LOUD_DBFS && hAll > pAll - PROBATION_GAP_DB) return true
        val upMs = ms(probeSamples)
        if (upMs < PROBATION_MIN_UP_MS || probeSpeechFrames < PROBATION_MIN_SPEECH_FRAMES) {
            if (upMs < PROBATION_NO_SPEECH_CAP_MS) return null
            probeUndecided = true
            return false
        }
        val hs = Loudness.dbfs(hSpeechPeak.toDouble())
        val ps = Loudness.dbfs(pSpeechPeak.toDouble())
        return when {
            hs >= PROBATION_LOUD_DBFS && hs > ps - PROBATION_GAP_DB -> true // the headset hears the speaker
            upMs < PROBATION_REJECT_AFTER_MS -> null // a slow device may still start
            ps >= (if (proven) EAGER_PHONE_DBFS else PROBATION_PHONE_DBFS) && ps - hs >= PROBATION_GAP_DB -> false // the phone heard what it did not
            ps >= PROBATION_QUIET_DBFS && ps - hs >= PROBATION_LATE_GAP_DB && !proven -> false // both quiet, the phone clearly more
            else -> true // heard about as well as the phone
        }
    }

    /** probation passed: the phone is closed, the headset's buffer goes on as the recording */
    private fun acceptHeadset(): (Listener.() -> Unit)? {
        if (Loudness.dbfs(probeHeadsetPeak.toDouble()) >= PROBATION_LOUD_DBFS) deliver { onHeadsetHeard() }
        phoneSource?.stop(); phoneSource = null
        hs = Hs.ON
        peak = probeHeadsetPeak
        sourceSamples = probeSamples
        // in a quiet moment the detector starts anew (the noise of the headset is another one); proven earbuds may take
        // over in the middle of a phrase: the detector and the phrase go on, so there is no gap and no repeat (5g-5)
        if (!headsetEager) { detector = detectorFactory(); clearRing() }
        deliver { onMic(true, false) }
        val end = replay(probeHeadset)
        wipe(probePhone)
        return end
    }

    /** probation failed: the channel is given back, the phone's buffer is used instead: nothing of the sound is lost */
    private fun rejectHeadset(): (Listener.() -> Unit)? {
        source?.stop()
        source = phoneSource
        phoneSource = null
        sourceUsed = phoneSourceType
        val undecided = probeUndecided
        if (undecided) headsetFellBack = true else headsetRejected = true
        letGoOfHeadset()
        if (undecided) deliver { onHeadsetUndecided() } else deliver { onHeadsetRejected() }
        deliver { onMic(false, true) }
        val end = replay(probePhone)
        wipe(probeHeadset)
        return end
    }

    private fun wipe(list: MutableList<ShortArray>) { list.forEach { it.fill(0) }; list.clear() }

    /** the buffered frames go through the recording now, as if they had just come; the first end stops it */
    private fun replay(frames: MutableList<ShortArray>): (Listener.() -> Unit)? {
        var end: (Listener.() -> Unit)? = null
        for (f in frames) { if (end == null) end = process(f, f.size); f.fill(0) }
        frames.clear()
        return end
    }

    /**
     * Stage 5g-11 (journal 4383: "phone -90 dBFS before the headset, -90 after", the whole recording lost): after the call
     * mode is given back the phone's AudioRecord, opened before the mode was switched, may stay deaf (HONOR). For
     * [PHONE_WATCH_MS] after a rollback, [PHONE_DEAF_MS] of digital silence make the recorder open the phone's source
     * anew (at most [PHONE_REOPENS] times); a phone that hears is left alone.
     */
    private var deafWatchSamples = 0L
    private var deafReopens = 0
    private var deafWatchUntil = -1L
    @Volatile var phoneReopened = 0
        private set
    /** stage 5g-12: the phone recorded with [phoneSourceWhileHeadset] while a headset was tried */
    @Volatile var phoneSourceSwapped = false
        private set
    /** stage 5g-12: how much of the recording came from the phone and from the headset (samples) */
    private var phoneRecSamples = 0L
    private var headsetRecSamples = 0L

    private fun watchPhone(framePeak: Int, n: Int) {
        if (deafWatchUntil < 0) return
        if (totalSamples > deafWatchUntil) { deafWatchUntil = -1; return }
        if (Loudness.dbfs(framePeak.toDouble()) > PHONE_DEAF_DBFS) { deafWatchSamples = 0; return }
        deafWatchSamples += n
        if (ms(deafWatchSamples) < PHONE_DEAF_MS) return
        deafWatchSamples = 0
        if (deafReopens >= PHONE_REOPENS) { deafWatchUntil = -1; return }
        deafReopens++
        val old = source
        val fresh = open(phoneSourceType) ?: return
        old?.stop()
        source = fresh
        phoneReopened++
        val times = phoneReopened
        deliver { onPhoneReopened(times) }
    }

    /** the headset went away: the phone records from now on (in the middle of a phrase too: the phrase goes on) */
    private fun dropHeadset() {
        when (hs) {
            Hs.ON, Hs.PROBATION -> {
                val phone = phoneSource ?: open(firstSource) ?: open(Loudness.other(firstSource))
                phoneSource = null
                if (phone != null) {
                    source?.stop()
                    source = phone
                    sourceUsed = firstSource
                    headsetLeft = true
                    peak = 0
                    sourceSamples = 0
                    if (hs == Hs.PROBATION) { val end = replay(probePhone); wipe(probeHeadset); if (end != null) pendingEnd = end }
                    deliver { onMic(false, false) }
                } // else the headset recording goes on: Android moves it to the phone microphone by itself
            }
            else -> Unit
        }
        if (hs != Hs.NONE) letGoOfHeadset()
    }

    /** nobody speaks now: 0.3 s without anything like speech, the noise known, not inside a phrase */
    private fun quietNow() = !inPhrase && detector.quietFor(QUIET_FOR_SWITCH_MS)

    // ---- the recording thread ----

    /** samples since the current source was opened, and since the start (the session limit) */
    private var sourceSamples = 0L
    private var totalSamples = 0L
    /** continuous: the last 0.7 s before speech (copies, wiped as they drop out) */
    private val ring = ArrayDeque<ShortArray>()
    private var ringSamples = 0
    private var inPhrase = false
    private var phrasePeak = 0
    /** stage 5g-6: phrases dropped because there was too little speech in them */
    @Volatile var droppedPhrases = 0
        private set
    /** stage 5g-6: time since the start or the last phrase handed over */
    private var msWithoutPhrase = 0L
    /** the speech heard in the current recording or phrase (ms), for the stop by the user */
    val speechHeardMs: Long get() = detector.speechHeardMs.toLong()

    /** an end found while replaying the phone's buffer after the headset went away */
    private var pendingEnd: (Listener.() -> Unit)? = null

    private fun ms(samples: Long) = samples * 1000 / PcmBuffer.SAMPLE_RATE

    /** the first 2 s of a source were silent: once, the other source; true if recording can go on */
    private fun switchSource(): Boolean {
        switched = true
        source?.stop()
        val next = open(Loudness.other(sourceUsed)) ?: open(sourceUsed) ?: return false
        source = next
        restartSound()
        return true
    }

    /** another source: what was recorded with the old one is gone */
    private fun restartSound() {
        audio.wipe()
        clearRing()
        inPhrase = false
        phrasePeak = 0
        peak = 0
        sourceSamples = 0
        detector = detectorFactory()
    }

    private fun clearRing() {
        ring.forEach { it.fill(0) }
        ring.clear()
        ringSamples = 0
    }

    /** stage 5g-20: one recording: what is recorded so far goes out as a piece, the recording and its speech go on */
    private fun handOverChunk() {
        val piece = audio.takeAll()
        chunks++
        chunkedMs += piece.durationMs
        val p = Loudness.dbfs(piece.peak().toDouble())
        post(Runnable { if (cancelled) piece.wipe() else listener.onChunk(piece, p) })
    }

    /** continuous: the phrase in [audio] is handed over; the next one starts */
    private fun handOverPhrase() {
        // stage 5g-6: a phrase with too little speech in it (a click, a step, a knock that started it) is not sent
        if (detector.speechHeardMs < MIN_PHRASE_SPEECH_MS) {
            audio.wipe()
            inPhrase = false
            phrasePeak = 0
            detector.nextPhrase()
            droppedPhrases++
            return
        }
        val phrase = audio.takeAll()
        msWithoutPhrase = 0
        val p = Loudness.dbfs(phrasePeak.toDouble())
        inPhrase = false
        phrasePeak = 0
        detector.nextPhrase()
        phrases++
        phrasesMs += phrase.durationMs
        // a cancelled recording never hands anything over: the phrase is wiped right here
        post(Runnable { if (cancelled) phrase.wipe() else listener.onPhrase(phrase, p) })
    }

    /** one frame of the microphone that records: the recording, the speech detector, the phrases; an end, or null */
    private fun process(frame: ShortArray, n: Int): (Listener.() -> Unit)? {
        val event = detector.feed(frame, n)
        // stage 5g-20: the player was paused at the tap; what it still played in the first moment is not sent, unless it
        // was speech (heard by the detector in this frame)
        if (muteStartMs > 0 && ms(totalSamples - n) < muteStartMs && detector.msSinceSpeech > 0) frame.fill(0, 0, n)
        val framePeak = Loudness.peak(frame, n)
        if (continuous == null) audio.append(frame, n)
        msWithoutPhrase += ms(n.toLong())
        if (sourceUsed == Loudness.SOURCE_HEADSET) headsetRecSamples += n else phoneRecSamples += n
        val c = continuous
        if (c == null) {
            return when {
                // stage 5g-6: an "end of speech" with too little speech in it (noise that looked like speech) is not
                // sent: it is wiped, and the listening goes on
                event == SpeechEndDetector.Event.END_OF_SPEECH && detector.speechHeardMs < MIN_PHRASE_SPEECH_MS -> {
                    audio.wipe(); detector.nextPhrase(); droppedPhrases++
                    if (msWithoutPhrase >= detector.onlyNoiseMs) { { onOnlyNoise() } } else null
                }
                event == SpeechEndDetector.Event.END_OF_SPEECH -> { { onEnd(true, false) } }
                // stage 5g-20: a long recording is sent in pieces of at most [chunkMs], cut in a quiet moment
                chunkMs != null && audio.durationMs >= chunkMs - CHUNK_SEEK_MS &&
                    (audio.durationMs >= chunkMs || detector.msSinceSpeech >= QUIET_CUT_MS) -> { handOverChunk(); null }
                event == SpeechEndDetector.Event.NO_SPEECH -> if (detector.onlyNoise() || droppedPhrases > 0) { { onOnlyNoise() } } else { { onNoSpeech() } }
                // 20 s of noise without a phrase of speech in it: stopped
                msWithoutPhrase >= detector.onlyNoiseMs && detector.speechHeardMs < MIN_PHRASE_SPEECH_MS -> { { onOnlyNoise() } }
                chunkedMs + audio.durationMs >= maxMs -> { val had = detector.hadSpeech; { onEnd(had, false) } }
                else -> null
            }
        }
        // continuous dictation: before speech only the last 0.7 s are kept, the phrase is speech plus its pause
        if (inPhrase) {
            audio.append(frame, n)
            phrasePeak = max(phrasePeak, framePeak)
        } else {
            ring.addLast(frame.copyOf(n))
            ringSamples += n
            while (ring.size > 1 && ms(ringSamples.toLong() - ring.first().size) >= c.preRollMs) {
                val old = ring.removeFirst()
                ringSamples -= old.size
                old.fill(0)
            }
            if (detector.hadSpeech) { // speech: the phrase starts with the sound just before it
                inPhrase = true
                for (f in ring) { audio.append(f, f.size); phrasePeak = max(phrasePeak, Loudness.peak(f, f.size)) }
                clearRing()
            }
        }
        // stage 5g-6: 20 s without a phrase of speech (noise only, or phrases of noise that were not sent): stopped; a
        // phrase with speech in it now is handed over first
        if (msWithoutPhrase >= detector.onlyNoiseMs && !(inPhrase && detector.speechHeardMs >= MIN_PHRASE_SPEECH_MS) &&
            (detector.onlyNoise() || droppedPhrases > 0)) {
            if (inPhrase) { audio.wipe(); inPhrase = false; droppedPhrases++ }
            return { onContinuousEnd("noise") }
        }
        when {
            event == SpeechEndDetector.Event.END_OF_SPEECH && inPhrase -> handOverPhrase()
            event == SpeechEndDetector.Event.NO_SPEECH -> return if (detector.onlyNoise()) { { onContinuousEnd("noise") } } else { { onContinuousEnd("silence") } }
            // a very long phrase is cut and sent; stage 5g-20: in a quiet moment of its last 5 s if there is one
            inPhrase && (audio.durationMs >= c.maxPhraseMs ||
                (audio.durationMs >= c.maxPhraseMs - CHUNK_SEEK_MS && detector.msSinceSpeech >= QUIET_CUT_MS)) -> handOverPhrase()
        }
        if (phrasesMs + audio.durationMs >= c.maxSpeechMs) { // the minutes of this month are used up
            if (inPhrase) handOverPhrase()
            return { onContinuousEnd("cap") }
        }
        if (ms(totalSamples) >= maxMs) {
            if (inPhrase) handOverPhrase()
            return { onContinuousEnd("limit") }
        }
        return null
    }

    private val headsetFrame = ShortArray(PcmBuffer.SAMPLE_RATE * SpeechEndDetector.FRAME_MS / 1000)
    private val phoneFrame = ShortArray(PcmBuffer.SAMPLE_RATE * SpeechEndDetector.FRAME_MS / 1000)
    private var firstFrame = true

    /** the phone's level before and after the try, said once when the 0.8 s after it are measured */
    private fun measurePhone(framePeak: Int, n: Int) {
        recentPhonePeaks.addLast(framePeak)
        while (recentPhonePeaks.size > BEFORE_FRAMES) recentPhonePeaks.removeFirst()
        if (afterSamples >= 0) {
            phoneAfter = max(phoneAfter, framePeak)
            afterSamples += n
            if (ms(afterSamples) >= AFTER_MS) {
                afterSamples = -1
                val b = Loudness.dbfs(phoneBefore.toDouble()); val a = Loudness.dbfs(phoneAfter.toDouble())
                deliver { onPhoneLevels(b, a) }
            }
        }
    }

    private fun loop() {
        val frame = ShortArray(PcmBuffer.SAMPLE_RATE * SpeechEndDetector.FRAME_MS / 1000)
        var end: (Listener.() -> Unit)? = null
        var sourceChecked = false
        try {
            while (!cancelled) {
                if (stopRequested) {
                    // what was said on probation counts, from the microphone that heard it
                    if (hs == Hs.PROBATION) {
                        val v = probationVerdict()
                        // a stop before a verdict: proven earbuds go on; a car or another device is not trusted without proof
                        // (stage 5g-11) with no verdict yet the recording comes from the microphone that heard more
                        val headsetWins = v ?: (probeHeadsetPeak.toDouble() > probePhonePeak * 0.25)
                        if (v == null && !headsetWins) probeUndecided = true
                        end = if (headsetWins) acceptHeadset() else rejectHeadset()
                    }
                    if (end == null) {
                        if (continuous != null) {
                            if (inPhrase) handOverPhrase()
                            end = { onContinuousEnd("done") }
                        } else {
                            val had = detector.hadSpeech
                            end = { onEnd(had, true) }
                        }
                    }
                    break
                }
                val n = source?.read(frame) ?: -1
                if (n < 0) {
                    if (hs == Hs.PROBATION) { // the headset broke on probation: the phone goes on with its own sound
                        end = rejectHeadset()
                        if (end != null) break
                        continue
                    }
                    end = { onFailed() }
                    break
                }
                if (n == 0) continue
                sourceSamples += n
                totalSamples += n
                var framePeak = Loudness.peak(frame, n)
                // stage 5g-18: the gap is measured on the phone's own source, before a deaf frame is filled
                val mainPeak = framePeak
                probe?.takeIf { !it.closed }?.let { p ->
                    val phone = hs != Hs.PROBATION && hs != Hs.ON
                    if (phone) p.feedMain(totalSamples, frame, n)
                    val deaf = hs == Hs.CONNECTING && Loudness.dbfs(framePeak.toDouble()) <= GAP_DEAF_DBFS
                    p.feed(totalSamples, frame, n, deaf)?.let { framePeak = it }
                    if (p.finished(totalSamples)) endProbe()
                }
                val settling = settleSamples >= 0 && hs != Hs.PROBATION && hs != Hs.ON
                if (settling) {
                    settleSamples += n
                    if (framePeak >= CLICK_PEAK) { frame.fill(0, 0, n); framePeak = 0 } // a click of the switch, not speech
                    if (ms(settleSamples) >= SETTLE_MS) settleSamples = -1
                } else if (hs == Hs.PROBATION && ms(probeSamples) < SETTLE_MS && framePeak >= CLICK_PEAK) {
                    frame.fill(0, 0, n); framePeak = 0 // stage 5g-5: a click at the joint phone → headset is not sent
                } else peak = max(peak, framePeak)
                // stage 5g-21: the loudest of the first 2 s (the probe "mute the radio with a call": does the phone hear?)
                if (ms(totalSamples) <= EARLY_MS) earlyPeak = max(earlyPeak, mainPeak)
                val level = SpeechEndDetector.level(SpeechEndDetector.rms(frame, n))
                deliver { onLevel(level) }
                if (firstFrame) { firstFrame = false; val at = clock(); deliver { onFirstFrame(at) } }
                if (gone) { gone = false; dropHeadset(); pendingEnd?.let { end = it; pendingEnd = null }; if (end != null) break }
                offered?.let { h -> offered = null; if (hs == Hs.NONE || hs == Hs.DONE) { headset = h; headsetEager = offeredEager; hs = Hs.WAIT_QUIET; headsetFellBack = false; headsetAborted = false } else h.release() }
                if (hs != Hs.PROBATION && hs != Hs.ON && !settling) measurePhone(framePeak, n)
                if (hs == Hs.DONE) watchPhone(framePeak, n)

                if (hs == Hs.PROBATION) {
                    // both microphones are only buffered until the first loud sound tells which one hears the speaker
                    probeHeadset.add(frame.copyOf(n))
                    probeHeadsetPeak = max(probeHeadsetPeak, framePeak)
                    probeSamples += n
                    val pn = phoneSource?.readNow(phoneFrame) ?: -1
                    var pPeak = 0
                    if (pn > 0) { probePhone.add(phoneFrame.copyOf(pn)); pPeak = Loudness.peak(phoneFrame, pn); probePhonePeak = max(probePhonePeak, pPeak) }
                    if (pn > 0) probe?.takeIf { !it.closed }?.feedMain(totalSamples, phoneFrame, pn)
                    // stage 5g-10: levels are compared only on frames where one of the microphones heard speech
                    val heardH = probeDetH?.let { speechIn(it, frame, n) } == true
                    val heardP = pn > 0 && probeDetP?.let { speechIn(it, phoneFrame, pn) } == true
                    if (heardH || heardP) { probeSpeechFrames++; hSpeechPeak = max(hSpeechPeak, framePeak); pSpeechPeak = max(pSpeechPeak, pPeak) }
                    // stage 5g-12: the two microphones give the very same sound (both routed to the headset)?
                    if (pn > 0 && framePeak > 0) { probeBothFrames++; if (abs(framePeak - pPeak) <= max(1, framePeak / 50)) probeSameFrames++ }
                    phoneFrame.fill(0)
                    when (probationVerdict()) {
                        true -> { end = acceptHeadset(); if (end != null) break }
                        false -> { end = rejectHeadset(); if (end != null) break }
                        null -> Unit
                    }
                    continue
                }

                if (hs == Hs.NONE || hs == Hs.DONE || hs == Hs.WAIT_QUIET || hs == Hs.CONNECTING) {
                    if (!sourceChecked && ms(sourceSamples) >= Loudness.SOURCE_CHECK_MS) {
                        if (peakDbfs >= Loudness.EMPTY_SOURCE_DBFS) {
                            sourceChecked = true
                            val worked = sourceUsed
                            deliver { onSourceWorked(worked) }
                        } else if (!switched) {
                            if (!switchSource()) { end = { onFailed() }; break }
                            continue
                        } else {
                            sourceChecked = true
                            if (peakDbfs <= Loudness.DIGITAL_SILENCE_DBFS) { end = { onSilenced() }; break } // zeros from both sources
                        }
                    }
                }
                end = process(frame, n)
                if (end != null) break

                when (hs) {
                    Hs.WAIT_QUIET -> if (when {
                            headsetEager -> ms(totalSamples) >= linkDelayMs()
                            headsetAfterPhrase -> phrases > 0 && quietNow() // stage 5g-20: the first pause after a phrase
                            else -> quietNow()
                        }) askForHeadset()
                    Hs.CONNECTING -> {
                        attemptSamples += n
                        connectSamples += n
                        if (Loudness.dbfs(mainPeak.toDouble()) <= GAP_DEAF_DBFS) deafSamples += n
                        connectPeak = max(connectPeak, mainPeak)
                        // stage 5g-14: the link of a car is not up yet: nothing to read, the phone records alone
                        val waiting = headsetSource == null && headset?.ready() != true
                        if (headsetSource == null && !waiting) headsetSource = headset?.source()?.takeIf { it.start() }
                        val hn = if (waiting) 0 else headsetSource?.readNow(headsetFrame) ?: -1
                        if (hn > 0) headsetPeak = max(headsetPeak, Loudness.peak(headsetFrame, hn))
                        headsetFrame.fill(0)
                        when {
                            // speech began on the phone: the headset is let go at once, so the phone hears it fully
                            !headsetEager && !quietNow() -> {
                                headsetAborted = true; letGoOfHeadset(); deliver { onMic(false, false) }
                                // stage 5g-20: the car is tried again in the next pause
                                if (headsetAfterPhrase && carTries < CAR_TRIES) { hs = Hs.WAIT_QUIET; headsetAborted = false }
                            }
                            hn < 0 -> { headsetFellBack = true; letGoOfHeadset(); val t = clock() - askedAt; deliver { onHeadsetTried(false, t) }; deliver { onMic(false, true) } }
                            headset?.isUp() == true && Loudness.dbfs(headsetPeak.toDouble()) > Loudness.DIGITAL_SILENCE_DBFS -> {
                                val t = clock() - askedAt
                                deliver { onHeadsetTried(true, t) }
                                startProbation()
                            }
                            // stage 5g-20: a car asked for in a pause gets the time of proven earbuds (its link: 1.5–1.6 s)
                            ms(attemptSamples) >= (if (headsetEager || headsetAfterPhrase) EAGER_CHECK_MS else Loudness.HEADSET_CHECK_MS) -> {
                                headsetFellBack = true; letGoOfHeadset(); val t = clock() - askedAt
                                deliver { onHeadsetTried(false, t) }; deliver { onMic(false, true) }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        } finally {
            source?.stop()
            phoneSource?.stop()
            headsetSource?.stop()
            headset?.release() // always: the music in the headset comes back
            probe?.close()
            frame.fill(0)
            clearRing()
            wipe(probeHeadset); wipe(probePhone)
        }
        if (!cancelled && end != null) {
            val pct = if (detector.frames > 0) detector.speechFrames * 100 / detector.frames else 0
            val noise = detector.noiseDbfs
            val dropped = droppedPhrases
            deliver { onNoiseStats(pct, noise, dropped) }
            val d = detector
            val vadName = d.vadName; val avg = d.probAvg; val mx = d.probMax; val th = d.threshold
            deliver { onVad(vadName, avg, mx, th) }
            val pMs = ms(phoneRecSamples); val hMs = ms(headsetRecSamples)
            deliver { onSourcesMs(pMs, hMs) }
            endProbe()
        }
        if (cancelled) audio.wipe() else end?.let { deliver(it) }
    }

    companion object {
        /** stage 5g-14: the headset was asked for, its recording is not opened yet (the link of a car comes up) */
        private val WAITING = object : PcmSource {
            override fun start() = true
            override fun read(buffer: ShortArray) = 0
            override fun stop() = Unit
        }
        /**
         * continuous dictation: this much sound before the speech is kept (the detector needs at least 0.3 s to hear
         * speech; short syllable pauses can make it a little more); the rest of the silence is never sent. The owner on
         * 4360 (stage 5g-2, item 5): with the phone microphone the start is never lost, so it stays 0.7 s.
         */
        const val PRE_ROLL_MS = 700L
        /** stage 5g-20: pieces of at most 30 s; the cut is looked for in the last 5 s, in a moment with 200 ms without speech */
        const val CHUNK_MS = 30_000L
        const val CHUNK_SEEK_MS = 5_000L
        const val QUIET_CUT_MS = 200
        /** stage 5g-20: a car is asked for in at most so many pauses of one dictation */
        const val CAR_TRIES = 3
        /** stage 5g-20: a paused player may still sound this long */
        const val MUTE_START_MS = 150L
        /** stage 5g-21: the first 2 s, measured for the journal */
        const val EARLY_MS = 2_000L
        /** stage 5g-6: a phrase needs at least this much speech in it to be sent */
        const val MIN_PHRASE_SPEECH_MS = 300
        /** the headset is asked for only after this much quiet on the phone (stage 5g-2) */
        const val QUIET_FOR_SWITCH_MS = 300
        /** probation of the headset (stage 5g-3): a loud sound, how much the phone may hear more, how long at most */
        const val PROBATION_LOUD_DBFS = -30.0
        const val PROBATION_PHONE_DBFS = -45.0
        const val PROBATION_GAP_DB = 12.0
        const val PROBATION_MAX_MS = 3000L
        /** stage 5g-10: no verdict before this long after the channel is up and this many frames with speech in them */
        const val PROBATION_MIN_UP_MS = 1500L
        const val PROBATION_MIN_SPEECH_FRAMES = 10
        /** stage 5g-11: "silent" only after this long (slow devices), and no speech at all for this long: no verdict */
        const val PROBATION_REJECT_AFTER_MS = 3000L
        /** the phone after a rollback: watched this long; this much digital silence (below this level) opens it anew, this many times at most */
        const val PHONE_WATCH_MS = 5000L
        const val PHONE_DEAF_MS = 400L
        const val PHONE_DEAF_DBFS = -85.0
        const val PHONE_REOPENS = 3
        const val PROBATION_NO_SPEECH_CAP_MS = 10_000L
        /** the phone heard digital silence (the HONOR phone goes deaf in the call mode) */
        const val GAP_DEAF_DBFS = -80.0
        /** proven earbuds (stage 5g-5): the phone must hear at least this to reject them; they may take this long to come up */
        const val EAGER_PHONE_DBFS = -35.0
        const val EAGER_CHECK_MS = 3000L
        /** proven earbuds that give almost nothing while the phone hears clearly more are dead */
        const val EAGER_DEAD_DBFS = -50.0
        const val EAGER_DEAD_GAP_DB = 15.0
        const val PROBATION_QUIET_DBFS = -40.0
        const val PROBATION_LATE_GAP_DB = 10.0
        /** after the channel is given back the phone settles this long; a frame this loud in that time is a click */
        const val SETTLE_MS = 200L
        private const val CLICK_PEAK = 32_000
        /** the phone's level before the try (0.5 s) and after it (0.8 s), for the journal */
        private const val BEFORE_FRAMES = 25
        private const val AFTER_MS = 800L
    }
}
