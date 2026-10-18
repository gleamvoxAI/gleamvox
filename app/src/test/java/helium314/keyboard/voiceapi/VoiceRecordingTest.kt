// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.MicCheck
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.latin.voiceapi.VoiceEngineChoice
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** amplitude of a sine whose peak is [dbfs] */
fun amplitudeOf(dbfs: Double) = 32768.0 * Math.pow(10.0, dbfs / 20)

/**
 * synthetic sound: pieces of noise (Gaussian, standard deviation [noise]) and of a tone (peak [tone]) over the noise,
 * 20 ms per read; [onRead] gets the milliseconds read so far (to press "Done" in the middle)
 */
/** a voice of 140 Hz with formants at 500, 1500 and 2500 Hz, peak 1 (stage 5g-6: what the speech detector takes for speech) */
private val VOICE_HARMONICS: List<Pair<Int, Double>> = (1..26).filter { it * 140 < 3800 }.map { h ->
    val f = h * 140.0
    h to (Math.exp(-Math.pow((f - 500) / 250, 2.0)) + 0.6 * Math.exp(-Math.pow((f - 1500) / 400, 2.0)) + 0.3 * Math.exp(-Math.pow((f - 2500) / 400, 2.0)))
}
private fun rawVoice(i: Int) = VOICE_HARMONICS.sumOf { (h, w) -> w * sin(2 * PI * h * 140 * i / PcmBuffer.SAMPLE_RATE) }
private val VOICE_PEAK = (0 until 800).maxOf { kotlin.math.abs(rawVoice(it)) }
private val VOICE_TABLE = DoubleArray(800) { rawVoice(it) / VOICE_PEAK } // 7 periods of 140 Hz are exactly 800 samples: repeats seamlessly
fun voice(i: Int) = VOICE_TABLE[i % VOICE_TABLE.size]

/** a pure tone of [hz], peak 1 (birds, whistles: no speech) */
fun pureTone(i: Int, hz: Double) = sin(2 * PI * hz * i / PcmBuffer.SAMPLE_RATE)

class SyntheticSource(private val pieces: List<Pair<Double, Boolean>>, private val noise: Double = 60.0,
                      private val tone: Double = 6000.0, private val onRead: ((Long) -> Unit)? = null) : PcmSource {
    private val samples: ShortArray
    private var pos = 0
    var started = false
    var stopped = false
    private val random = Random(1)

    init {
        val all = mutableListOf<Short>()
        for ((seconds, speech) in pieces) {
            val n = (seconds * PcmBuffer.SAMPLE_RATE).toInt()
            for (i in 0 until n) {
                // stage 5g-6: "speech" is shaped like a voice (a 140 Hz voice with formants at 500, 1500, 2500 Hz, peak
                // [tone]), so that the speech detector of WebRTC hears it; a pure tone it rightly takes for no speech
                val v = if (speech) tone * voice(i) + random.nextGaussian() * noise
                        else random.nextGaussian() * noise
                all.add(v.toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        samples = all.toShortArray()
    }

    override fun start(): Boolean { started = true; return true }
    override fun read(buffer: ShortArray): Int {
        onRead?.invoke(readMs)
        if (pos >= samples.size) return -1
        val n = minOf(buffer.size, samples.size - pos)
        System.arraycopy(samples, pos, buffer, 0, n)
        pos += n
        return n
    }
    override fun stop() { stopped = true }
    val readMs get() = pos * 1000L / PcmBuffer.SAMPLE_RATE
}

/** speech-like sound: 0.4 s of tone, 0.1 s pause, repeated (real speech always has short pauses between syllables) */
fun syllables(seconds: Double): List<Pair<Double, Boolean>> = (0 until (seconds / 0.5).toInt()).flatMap { listOf(0.4 to true, 0.1 to false) }

/** runs [source] through the detector frame by frame; returns the event and when it happened (ms) */
fun detect(source: SyntheticSource, detector: SpeechEndDetector = SpeechEndDetector()): Pair<SpeechEndDetector.Event, Long> {
    val frame = ShortArray(320)
    source.start()
    while (true) {
        val n = source.read(frame)
        if (n < 0) return SpeechEndDetector.Event.NONE to source.readMs
        val e = detector.feed(frame, n)
        if (e != SpeechEndDetector.Event.NONE) return e to source.readMs
    }
}

class SpeechEndDetectorTest {
    @Test fun endAfterAboutOneSecondOfSilence() {
        val (event, at) = detect(SyntheticSource(listOf(0.5 to false, 1.5 to true, 3.0 to false)))
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event)
        assertTrue(at in 3200L..3400L, "end at $at ms, speech ended at 2000 ms") // 2000 + 1300 ± a frame (5g-19: 1.3 s)
    }

    @Test fun shortPauseInsideSpeechIsNotTheEnd() {
        val (event, at) = detect(SyntheticSource(listOf(0.3 to false, 1.0 to true, 0.6 to false, 1.0 to true, 2.0 to false)))
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event)
        assertTrue(at > 2900, "not ended in the 0.6 s pause: $at")
    }

    @Test fun silenceOnlyIsNoSpeech() {
        val (event, at) = detect(SyntheticSource(listOf(10.0 to false)))
        assertEquals(SpeechEndDetector.Event.NO_SPEECH, event)
        assertEquals(8000L, at)
    }

    @Test fun clickIsNotSpeech() {
        val d = SpeechEndDetector()
        val (event, _) = detect(SyntheticSource(listOf(0.5 to false, 0.1 to true, 9.0 to false)), d)
        assertEquals(SpeechEndDetector.Event.NO_SPEECH, event)
        assertFalse(d.hadSpeech)
    }

    @Test fun speechRightAtTheStart() {
        val (event, _) = detect(SyntheticSource(syllables(1.5) + listOf(2.0 to false)))
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event)
    }

    /** stage 5b: quiet speech of a quiet microphone is heard (peak -45 and -55 dBFS over noise at -75 dBFS) */
    @Test fun quietSpeechIsHeard() {
        for (db in listOf(-45.0, -55.0)) {
            val (event, at) = detect(SyntheticSource(listOf(0.5 to false, 1.5 to true, 3.0 to false), noise = amplitudeOf(-78.0), tone = amplitudeOf(db)))
            assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event, "$db dBFS")
            assertTrue(at in 3200L..3400L, "$db dBFS: end at $at")
        }
    }

    /** steady room noise is never taken for speech, also when it is louder than the absolute floor */
    @Test fun steadyNoiseIsNoSpeech() {
        for (db in listOf(-65.0, -50.0, -40.0)) {
            val d = SpeechEndDetector()
            val (event, at) = detect(SyntheticSource(listOf(10.0 to false), noise = amplitudeOf(db)), d)
            assertEquals(SpeechEndDetector.Event.NO_SPEECH, event, "noise $db dBFS")
            assertEquals(8000L, at)
            assertFalse(d.hadSpeech)
        }
    }

    @Test fun speechOverLoudNoiseEnds() {
        val (event, _) = detect(SyntheticSource(listOf(0.5 to false, 1.5 to true, 3.0 to false), noise = amplitudeOf(-45.0), tone = amplitudeOf(-15.0)))
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event)
    }

    @Test fun levelRange() {
        assertEquals(0f, SpeechEndDetector.level(0.0))
        assertEquals(1f, SpeechEndDetector.level(32767.0))
        assertTrue(SpeechEndDetector.level(3000.0) in 0.3f..0.9f)
    }
}

class PcmRecorderTest {
    private class Events : PcmRecorder.Listener {
        val list = mutableListOf<String>()
        val worked = mutableListOf<Int>()
        override fun onLevel(level: Float) = Unit
        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) { list.add("end $hadSpeech${if (byUser) " by user" else ""}") }
        override fun onNoSpeech() { list.add("no speech") }
        override fun onFailed() { list.add("failed") }
        override fun onSourceWorked(source: Int) { worked.add(source) }
    }

    private val MIC = Loudness.SOURCE_MIC
    private val VR = Loudness.SOURCE_VOICE_RECOGNITION
    private val silent = { SyntheticSource(listOf(12.0 to false), noise = amplitudeOf(-90.0)) }
    private val speech = { SyntheticSource(listOf(0.5 to false, 1.5 to true, 3.0 to false), tone = amplitudeOf(-20.0)) }

    private fun run(sources: Map<Int, () -> SyntheticSource?>, first: Int = MIC, maxMs: Long = 120_000,
                    before: (PcmRecorder) -> Unit = {}): Triple<PcmRecorder, Events, List<Int>> {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        val opened = mutableListOf<Int>()
        val r = PcmRecorder({ src -> opened.add(src); sources[src]?.invoke() ?: object : PcmSource {
            override fun start() = false; override fun read(buffer: ShortArray) = -1; override fun stop() = Unit } },
            first, maxMs, events, { synchronized(posted) { posted.add(it) } })
        before(r)
        assertTrue(r.start())
        r.thread!!.join(10_000)
        synchronized(posted) { posted.forEach { it.run() } }
        return Triple(r, events, opened)
    }

    @Test fun recordsUntilTheEndOfSpeech() {
        val (r, e, opened) = run(mapOf(MIC to speech))
        assertEquals(listOf("end true"), e.list)
        assertTrue(r.audio.durationMs in 3200L..3400L, "${r.audio.durationMs}") // 2000 ms of speech + the 1.3 s pause
        assertEquals(listOf(MIC), opened, "MIC by default, no switch")
        assertEquals(listOf(MIC), e.worked, "MIC gave a signal: remembered")
        assertEquals(MIC, r.sourceUsed)
        assertTrue(r.peakDbfs > -22 && r.peakDbfs < -18, "${r.peakDbfs}")
    }

    /** stage 5b: a silent MIC is replaced once by VOICE_RECOGNITION after 2 s; the silence is wiped */
    @Test fun silentMicSwitchesToVoiceRecognition() {
        val (r, e, opened) = run(mapOf(MIC to silent, VR to speech))
        assertEquals(listOf(MIC, VR), opened)
        assertEquals(VR, r.sourceUsed)
        assertTrue(r.switched)
        assertEquals(listOf(VR), e.worked)
        assertEquals(listOf("end true"), e.list)
        assertTrue(r.audio.durationMs in 3200L..3400L, "only the new source is kept: ${r.audio.durationMs}")
    }

    @Test fun andTheOtherWayRound() {
        val (r, e, opened) = run(mapOf(VR to silent, MIC to speech), first = VR)
        assertEquals(listOf(VR, MIC), opened)
        assertEquals(MIC, r.sourceUsed)
        assertEquals(listOf(MIC), e.worked)
    }

    @Test fun bothSilentSwitchOnlyOnce() {
        val (r, e, opened) = run(mapOf(MIC to silent, VR to silent))
        assertEquals(listOf(MIC, VR), opened)
        assertEquals(listOf("no speech"), e.list)
        assertTrue(e.worked.isEmpty())
        assertTrue(r.peakDbfs < -70)
    }

    @Test fun firstSourceCantBeOpened() {
        val (r, _, opened) = run(mapOf(VR to speech))
        assertEquals(listOf(MIC, VR), opened)
        assertEquals(VR, r.sourceUsed)
    }

    @Test fun maximumLength() {
        val (r, e, _) = run(mapOf(MIC to { SyntheticSource(syllables(10.0)) }), maxMs = 2000)
        assertEquals(listOf("end true"), e.list)
        assertEquals(2000L, r.audio.durationMs)
    }

    @Test fun stopByTheUser() {
        lateinit var rec: PcmRecorder
        val (_, e, _) = run(mapOf(MIC to { SyntheticSource(listOf(5.0 to false), noise = amplitudeOf(-62.0), onRead = { ms -> if (ms >= 1000) rec.stop() }) }),
            before = { rec = it })
        assertEquals(listOf("end false by user"), e.list)
    }

    @Test fun noSpeechSendsNothing() {
        val (_, e, _) = run(mapOf(MIC to { SyntheticSource(listOf(12.0 to false), noise = 60.0) }))
        assertEquals(listOf("no speech"), e.list)
    }

    @Test fun cancelDropsAndWipes() {
        val (r, e, _) = run(mapOf(MIC to speech)) { it.cancel() }
        assertTrue(e.list.isEmpty(), "${e.list}")
        assertEquals(0, r.audio.byteCount)
    }

    @Test fun bufferWipe() {
        val b = toneBuffer(1.5)
        assertEquals(48_000, b.byteCount)
        val copy = ByteArray(b.byteCount).also { b.copyTo(it, 0) }
        assertTrue(copy.any { it != 0.toByte() })
        b.wipe()
        assertEquals(0, b.byteCount)
    }
}

/** stage 5b: what is sent, and the gain of quiet recordings */
class LoudnessTest {
    @Test fun manualStopAlwaysSendsUnlessSilenceOrTooShort() {
        assertTrue(Loudness.shouldSend(hadSpeech = false, byUser = true, durationMs = 600, peakDbfs = -65.0))
        assertFalse(Loudness.shouldSend(hadSpeech = false, byUser = true, durationMs = 500, peakDbfs = -40.0), "too short")
        assertFalse(Loudness.shouldSend(hadSpeech = true, byUser = true, durationMs = 5000, peakDbfs = -75.0), "silence")
        assertTrue(Loudness.shouldSend(hadSpeech = true, byUser = false, durationMs = 3000, peakDbfs = -30.0))
        assertFalse(Loudness.shouldSend(hadSpeech = false, byUser = false, durationMs = 9000, peakDbfs = -30.0))
    }

    @Test fun gain() {
        assertEquals(1.0, Loudness.gainFor(-10.0))
        assertEquals(1.0, Loudness.gainFor(-20.0))
        assertEquals(1.0, Loudness.gainFor(-75.0), "silence is not amplified")
        assertEquals(Math.pow(10.0, 27.0 / 20), Loudness.gainFor(-30.0), 1e-9)
        assertEquals(Math.pow(10.0, 30.0 / 20), Loudness.gainFor(-40.0), 1e-9, "would need 37 dB: 30 at most")
        assertEquals(Math.pow(10.0, 30.0 / 20), Loudness.gainFor(-60.0), 1e-9, "at most 30 dB")
    }

    @Test fun quietRecordingIsRaisedToAboutMinus3() {
        val b = toneBuffer(0.5, amplitude = amplitudeOf(-30.0).toInt())
        val before = Loudness.dbfs(b.peak().toDouble())
        b.applyGain(Loudness.gainFor(before))
        val after = Loudness.dbfs(b.peak().toDouble())
        assertTrue(after > -3.6 && after < -2.4, "peak after gain $after")
        val veryQuiet = toneBuffer(0.5, amplitude = amplitudeOf(-60.0).toInt())
        veryQuiet.applyGain(Loudness.gainFor(Loudness.dbfs(veryQuiet.peak().toDouble())))
        val v = Loudness.dbfs(veryQuiet.peak().toDouble())
        assertTrue(v > -31 && v < -29, "30 dB at most: $v")
        val loud = toneBuffer(0.5, amplitude = 32000)
        loud.applyGain(3.0) // clipped to -32768..32767, no wrap-around
        assertTrue(loud.peak() in 32767..32768, "${loud.peak()}")
        val bytes = ByteArray(loud.byteCount).also { loud.copyTo(it, 0) }
        val quarter = ((bytes[16].toInt() and 0xFF) or (bytes[17].toInt() shl 8)).toShort().toInt() // sample 8 of a 440 Hz sine: rising
        assertTrue(quarter > 0, "a positive sample stays positive after clipping: $quarter")
    }
}

/** "Check microphone": peak of each source, nothing kept */
class MicCheckTest {
    @Test fun eachSourceIsMeasured() {
        val progress = mutableListOf<Int>()
        val r = MicCheck.run({ src -> if (src == Loudness.SOURCE_MIC) SyntheticSource(listOf(3.0 to false), noise = amplitudeOf(-85.0))
                                     else SyntheticSource(listOf(3.0 to true), tone = amplitudeOf(-20.0)) },
            onProgress = { src, _, _ -> progress.add(src) })
        assertEquals(listOf(Loudness.SOURCE_MIC, Loudness.SOURCE_VOICE_RECOGNITION), r.map { it.source })
        assertEquals(MicCheck.Verdict.SILENT, r[0].verdict)
        assertEquals(MicCheck.Verdict.GOOD, r[1].verdict)
        assertEquals(Loudness.SOURCE_VOICE_RECOGNITION, MicCheck.best(r))
        assertTrue(progress.count { it == Loudness.SOURCE_MIC } in 120..130, "2.5 s of 20 ms frames")
    }

    @Test fun verdicts() {
        assertEquals(MicCheck.Verdict.GOOD, MicCheck.verdict(-12.0))
        assertEquals(MicCheck.Verdict.QUIET, MicCheck.verdict(-50.0))
        assertEquals(MicCheck.Verdict.SILENT, MicCheck.verdict(-72.0))
        assertEquals(MicCheck.Verdict.UNAVAILABLE, MicCheck.verdict(null))
    }

    @Test fun sourceThatCantBeOpened() {
        val r = MicCheck.run({ object : PcmSource { override fun start() = false; override fun read(buffer: ShortArray) = -1; override fun stop() = Unit } })
        assertTrue(r.all { it.verdict == MicCheck.Verdict.UNAVAILABLE })
        assertNull(MicCheck.best(r))
    }
}

class VoiceApiUsageTest {
    private var now = 1_760_000_000_000L // 2025-10-09, UTC
    private fun usage(prefs: android.content.SharedPreferences) = VoiceApiUsage(prefs, { now }, { ZoneOffset.UTC })

    @Test fun countsMinutesPerMonthAndWarnsOnceAt80Percent() {
        val prefs = MemoryPrefs()
        val u = usage(prefs)
        u.setCapMinutes(10)
        assertFalse(u.record(7 * 60_000L))
        assertFalse(u.nearCap())
        assertTrue(u.record(60_000L), "8 of 10 minutes: warn")
        assertTrue(u.nearCap())
        assertFalse(u.record(30_000L), "only once")
        assertFalse(u.capReached())
        u.record(90_000L)
        assertTrue(u.capReached())
        assertEquals(0L, u.remainingMs())
        assertEquals(10 * 0.0045, u.estimatedDollars(), 1e-9)
        now += 31L * 24 * 3600 * 1000 // next month
        assertEquals(0L, u.usedMs())
        assertFalse(u.capReached())
    }

    @Test fun ceilingZeroMeansNoOpenAi() {
        val u = usage(MemoryPrefs())
        u.setCapMinutes(0)
        assertTrue(u.capReached())
        assertEquals(300, usage(MemoryPrefs()).capMinutes())
    }

    @Test fun whatIsCounted() {
        assertEquals(2500L, VoiceApiUsage.billedMs(TranscriptionResult.Ok("x", 2.5), 3000))
        assertEquals(3000L, VoiceApiUsage.billedMs(TranscriptionResult.Ok("x", null), 3000))
        assertEquals(3000L, VoiceApiUsage.billedMs(TranscriptionResult.Failed(VoiceApiProblem.NETWORK), 3000))
        assertEquals(0L, VoiceApiUsage.billedMs(TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401), 3000))
        assertEquals(0L, VoiceApiUsage.billedMs(TranscriptionResult.Failed(VoiceApiProblem.NO_BALANCE, 429), 3000))
    }
}

class VoiceEngineChoiceTest {
    private fun inputs(
        inBuild: Boolean = true, setting: SpeechEngineSetting = SpeechEngineSetting.OPENAI, consent: Boolean = true,
        hasKey: Boolean = true, keyRejected: Boolean = false, internetOn: Boolean = true, locked: Boolean = false,
        fieldBlocked: Boolean = false, capReached: Boolean = false, paused: VoiceApiProblem? = null,
    ) = VoiceEngineChoice.Inputs(inBuild, setting, consent, hasKey, keyRejected, internetOn, locked, fieldBlocked, capReached, paused)

    private fun decide(i: VoiceEngineChoice.Inputs) = VoiceEngineChoice.decide(i)

    @Test fun openAiWhenEverythingHolds() {
        assertEquals(VoiceEngineChoice.Choice(true), decide(inputs()))
        assertEquals(VoiceEngineChoice.Choice(true), decide(inputs(setting = SpeechEngineSetting.AUTO)))
    }

    @Test fun systemWithoutMessage() {
        assertEquals(VoiceEngineChoice.Choice(false), decide(inputs(inBuild = false))) // release
        assertEquals(VoiceEngineChoice.Choice(false), decide(inputs(setting = SpeechEngineSetting.SYSTEM)))
        assertEquals(VoiceEngineChoice.Choice(false), decide(inputs(consent = false)))
        assertEquals(VoiceEngineChoice.Choice(false), decide(inputs(fieldBlocked = true))) // excluded app, incognito
    }

    @Test fun reasonsWithOpenAiChosen() {
        assertEquals(VoiceApiProblem.INTERNET_OFF, decide(inputs(internetOn = false)).notice)
        assertEquals(VoiceApiProblem.NO_KEY, decide(inputs(hasKey = false)).notice)
        assertEquals(VoiceApiProblem.KEY_REJECTED, decide(inputs(keyRejected = true)).notice)
        assertEquals(VoiceApiProblem.LOCKED, decide(inputs(locked = true)).notice)
        assertEquals(VoiceApiProblem.CAP_REACHED, decide(inputs(capReached = true)).notice)
        assertEquals(VoiceApiProblem.PAUSED_BALANCE, decide(inputs(paused = VoiceApiProblem.PAUSED_BALANCE)).notice)
        listOf(inputs(internetOn = false), inputs(capReached = true), inputs(hasKey = false)).forEach { assertFalse(decide(it).openAi) }
    }

    @Test fun autoSaysNothing() {
        val c = decide(inputs(setting = SpeechEngineSetting.AUTO, internetOn = false))
        assertFalse(c.openAi)
        assertNull(c.notice)
        assertNull(decide(inputs(setting = SpeechEngineSetting.AUTO, capReached = true)).notice)
    }
}

/** SharedPreferences in memory for plain JVM tests */
class MemoryPrefs : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed.add(key) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(changes)
        }
    }
}

/** stage 5c: "Pause before sending" 0.8–2.5 s, 1.3 s by default (stage 5g-19; 1.5 s before), used by the end-of-speech detector */
class EndPauseSettingTest {
    @Test fun defaultIsOnePointFiveSecondsAlsoForOldInstalls() {
        // nobody could store the old fixed 1.2 s: every install that never moved the slider gets the default, 1.3 s since 5g-19
        val prefs = MemoryPrefs()
        assertEquals(1300, helium314.keyboard.latin.voiceapi.VoiceApiPrefs.endPauseMs(prefs))
    }

    @Test fun rangeAndSteps() {
        val prefs = MemoryPrefs()
        val p = helium314.keyboard.latin.voiceapi.VoiceApiPrefs
        p.setEndPauseMs(prefs, 500); assertEquals(800, p.endPauseMs(prefs))
        p.setEndPauseMs(prefs, 9000); assertEquals(2500, p.endPauseMs(prefs))
        p.setEndPauseMs(prefs, 1234); assertEquals(1200, p.endPauseMs(prefs))
        p.setEndPauseMs(prefs, 1960); assertEquals(2000, p.endPauseMs(prefs))
        prefs.edit().putInt(p.PREF_END_PAUSE_MS, 100).apply() // a wrong stored value is clamped
        assertEquals(800, p.endPauseMs(prefs))
        p.eraseSettings(prefs)
        assertFalse(prefs.contains(p.PREF_END_PAUSE_MS))
    }

    @Test fun detectorEndsAfterTheChosenPause() {
        for (pause in listOf(800, 1500, 2500)) {
            val (event, at) = detect(SyntheticSource(listOf(0.5 to false, 1.5 to true, 4.0 to false)), SpeechEndDetector(endSilenceMs = pause))
            assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event, "$pause")
            assertTrue(at in (2000L + pause - 100)..(2000L + pause + 100), "pause $pause: end at $at ms, speech ended at 2000 ms")
        }
        // the default detector uses the default of the setting
        val (_, at) = detect(SyntheticSource(listOf(0.5 to false, 1.5 to true, 4.0 to false)))
        assertTrue(at in 3200L..3400L, "default (1.3 s): $at")
    }
}
