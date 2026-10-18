// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import helium314.keyboard.latin.voiceapi.WebRtcVad
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val R = PcmBuffer.SAMPLE_RATE

/** stage 5g-6: sounds of the street and the room, made of numbers (the same every run) */
object Noises {
    fun white(seconds: Double, sd: Double, seed: Long = 1): DoubleArray { val r = Random(seed); return DoubleArray((seconds * R).toInt()) { r.nextGaussian() * sd } }

    /** pink noise (as much energy per octave: wind, traffic, a window), Paul Kellet's filter; about [amp] / 3 RMS */
    fun pink(seconds: Double, amp: Double, seed: Long = 2): DoubleArray {
        val r = Random(seed)
        val b = DoubleArray(7)
        return DoubleArray((seconds * R).toInt()) {
            val x = r.nextGaussian()
            b[0] = 0.99886 * b[0] + x * 0.0555179; b[1] = 0.99332 * b[1] + x * 0.0750759; b[2] = 0.96900 * b[2] + x * 0.1538520
            b[3] = 0.86650 * b[3] + x * 0.3104856; b[4] = 0.55000 * b[4] + x * 0.5329522; b[5] = -0.7616 * b[5] - x * 0.0168980
            val v = amp * (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + x * 0.5362) * 0.11
            b[6] = x * 0.115926
            v
        }
    }

    /** knocks, steps, a dropped thing: 2.5 ms bursts of [amp], [perSecond] of them, over room noise */
    fun clicks(seconds: Double, amp: Double, perSecond: Int = 3, seed: Long = 3): DoubleArray {
        val r = Random(seed)
        val o = white(seconds, 60.0, seed)
        for (k in o.indices step R / perSecond) for (j in 0 until 40) if (k + j < o.size) o[k + j] += amp * (if (r.nextBoolean()) 1 else -1)
        return o
    }

    /** birds: chirps rising from 2.5 to 4.5 kHz, 120 ms every 300 ms, over room noise */
    fun birds(seconds: Double, amp: Double): DoubleArray {
        val o = white(seconds, 60.0, 4)
        var phase = 0.0
        for (i in o.indices) {
            val ph = (i.toDouble() / R) % 0.3
            if (ph < 0.12) { phase += 2 * PI * (2500 + 2000 * ph / 0.12) / R; o[i] += amp * sin(phase) }
        }
        return o
    }

    /** a voice (see [voice]) in syllables of 0.4 s with 0.1 s between them, peak [amp]; silence around it */
    fun syllables(before: Double, seconds: Double, after: Double, amp: Double): DoubleArray {
        val o = DoubleArray(((before + seconds + after) * R).toInt())
        val start = (before * R).toInt()
        for (i in 0 until (seconds * R).toInt()) if ((i % (R / 2)) < R * 4 / 10) o[start + i] = amp * voice(i)
        return o
    }

    fun mix(vararg parts: DoubleArray): DoubleArray = DoubleArray(parts.maxOf { it.size }) { i -> parts.sumOf { if (i < it.size) it[i] else 0.0 } }
    fun concat(vararg parts: DoubleArray): DoubleArray = parts.fold(DoubleArray(0)) { a, b -> a + b }
    fun pcm(d: DoubleArray) = ShortArray(d.size) { d[it].toInt().coerceIn(-32768, 32767).toShort() }
}

/** plays [samples], 20 ms per read; [onRead] gets the milliseconds read so far */
class SampleSource(private val samples: ShortArray, private val onRead: ((Long) -> Unit)? = null) : PcmSource {
    private var pos = 0
    override fun start() = true
    override fun read(buffer: ShortArray): Int {
        onRead?.invoke(readMs)
        if (pos >= samples.size) return -1
        val n = minOf(buffer.size, samples.size - pos)
        System.arraycopy(samples, pos, buffer, 0, n)
        pos += n
        return n
    }
    override fun stop() = Unit
    val readMs get() = pos * 1000L / R
}

class NoiseDetectorTest {
    private class Run(val event: SpeechEndDetector.Event, val atMs: Long, val d: SpeechEndDetector)

    private fun detect(sound: DoubleArray, d: SpeechEndDetector = SpeechEndDetector()): Run {
        val s = SampleSource(Noises.pcm(sound))
        val frame = ShortArray(320)
        while (true) {
            val n = s.read(frame)
            if (n < 0) return Run(SpeechEndDetector.Event.NONE, s.readMs, d)
            val e = d.feed(frame, n)
            if (e != SpeechEndDetector.Event.NONE) return Run(e, s.readMs, d)
        }
    }

    private fun assertOnlyNoise(name: String, sound: DoubleArray) {
        val r = detect(sound)
        assertEquals(SpeechEndDetector.Event.NO_SPEECH, r.event, name)
        assertEquals(8000L, r.atMs, "$name: the 8 s without speech, not later")
        assertFalse(r.d.hadSpeech, name)
        assertTrue(r.d.onlyNoise(), "$name is noise, not silence")
        assertTrue(r.d.speechFrames * 100 / r.d.frames <= 5, "$name: ${r.d.speechFrames} of ${r.d.frames} frames taken for speech")
    }

    @Test fun whiteNoiseIsNoSpeech() {
        for (sd in listOf(300.0, 1000.0, 3000.0)) assertOnlyNoise("white noise $sd", Noises.white(10.0, sd))
    }

    @Test fun pinkNoiseIsNoSpeech() {
        for (amp in listOf(1000.0, 3000.0, 10000.0)) assertOnlyNoise("pink noise $amp", Noises.pink(10.0, amp))
    }

    @Test fun clicksAndKnocksAreNoSpeech() {
        for (perSecond in listOf(1, 3, 8)) {
            val r = detect(Noises.clicks(10.0, 20000.0, perSecond))
            assertEquals(SpeechEndDetector.Event.NO_SPEECH, r.event, "$perSecond clicks a second")
            assertEquals(0, r.d.speechHeardMs, "a click is too short to be heard as speech")
        }
    }

    @Test fun birdsAreNoSpeech() {
        for (amp in listOf(1000.0, 4000.0, 12000.0)) {
            val r = detect(Noises.birds(10.0, amp))
            assertEquals(SpeechEndDetector.Event.NO_SPEECH, r.event, "birds $amp")
            assertFalse(r.d.hadSpeech)
        }
    }

    @Test fun pureTonesAreNoSpeech() {
        for (hz in listOf(300.0, 1000.0, 2500.0)) {
            val r = detect(Noises.mix(DoubleArray(10 * R) { 6000 * pureTone(it, hz) }, Noises.white(10.0, 60.0)))
            assertEquals(SpeechEndDetector.Event.NO_SPEECH, r.event, "$hz Hz")
        }
    }

    /** the owner's case: speech, then only noise (a window, a street): the pause comes 1.3 s after the speech (5g-19) */
    @Test fun pauseComesInNoise() {
        for (noise in listOf(Noises.pink(10.0, 1500.0), Noises.white(10.0, 300.0), Noises.birds(10.0, 2000.0), Noises.clicks(10.0, 15000.0))) {
            val r = detect(Noises.mix(Noises.syllables(0.5, 2.0, 7.5, 6000.0), noise))
            assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, r.event)
            assertTrue(r.atMs in 3500L..4000L, "speech ended at 2400 ms, the pause at ${r.atMs}") // 2400 + 1300 ± a little
            assertTrue(r.d.speechHeardMs >= 1000, "${r.d.speechHeardMs}")
        }
    }

    /** a quiet microphone (stage 5b): quiet speech over its own quiet noise is not lost */
    @Test fun quietSpeechIsNotLost() {
        for (db in listOf(-40.0, -45.0, -50.0, -55.0)) {
            val r = detect(Noises.mix(Noises.syllables(0.5, 2.0, 3.0, amplitudeOf(db)), Noises.white(5.5, amplitudeOf(-78.0))))
            assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, r.event, "$db dBFS")
            assertTrue(r.atMs in 3700L..4100L, "$db dBFS: ${r.atMs}")
        }
    }

    /** the noise level follows the noise: about the RMS of the quietest 20 ms */
    @Test fun noiseLevelIsMeasured() {
        val r = detect(Noises.white(10.0, amplitudeOf(-50.0)))
        assertTrue(r.d.noiseDbfs in -53.0..-49.0, "${r.d.noiseDbfs}")
    }

    /** the Java port of the WebRTC VAD gives the answers of the original C code (checked 2026-10-14 on these sounds) */
    @Test fun vadFindsSpeechAndNotNoise() {
        fun frames(sound: DoubleArray): Int {
            val v = WebRtcVad(SpeechEndDetector.VAD_MODE)
            val pcm = Noises.pcm(sound)
            var yes = 0
            for (k in 0 until pcm.size / 320) if (v.process(R, pcm.copyOfRange(k * 320, k * 320 + 320), 320)) yes++
            return yes
        }
        assertTrue(frames(Noises.syllables(0.0, 4.0, 0.0, 6000.0)) >= 150, "speech: most of 200 frames")
        assertEquals(0, frames(DoubleArray(4 * R)), "silence")
        assertTrue(frames(DoubleArray(4 * R) { 6000 * pureTone(it, 300.0) }) <= 10, "a pure tone")
    }
}

class NoiseRecorderTest {
    private class Events : PcmRecorder.Listener {
        val list = mutableListOf<String>()
        val phrases = mutableListOf<Long>()
        var stats: Triple<Int, Double, Int>? = null
        override fun onLevel(level: Float) = Unit
        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) { list.add("end $hadSpeech${if (byUser) " by user" else ""}") }
        override fun onNoSpeech() { list.add("no speech") }
        override fun onOnlyNoise() { list.add("only noise") }
        override fun onFailed() { list.add("failed") }
        override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) { phrases.add(audio.durationMs); audio.wipe() }
        override fun onContinuousEnd(reason: String) { list.add(reason) }
        override fun onNoiseStats(speechPercent: Int, noiseDbfs: Double, droppedPhrases: Int) { stats = Triple(speechPercent, noiseDbfs, droppedPhrases) }
    }

    private val inPlace: (Runnable) -> Thread = { r -> object : Thread() { override fun start() = r.run() } }

    private fun record(sound: DoubleArray, continuous: Boolean, silenceMs: Int = 10_000,
                       onRead: ((PcmRecorder, Long) -> Unit)? = null): Pair<PcmRecorder, Events> {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        var rec: PcmRecorder? = null
        val pcm = Noises.pcm(sound)
        val r = PcmRecorder({ SampleSource(pcm) { ms -> onRead?.invoke(rec!!, ms) } }, Loudness.SOURCE_MIC, 120_000, events,
            { posted.add(it) }, detectorFactory = { SpeechEndDetector(endSilenceMs = 1500, noSpeechTimeoutMs = if (continuous) silenceMs else 8000) },
            threadFactory = inPlace,
            continuous = if (continuous) PcmRecorder.Continuous(maxPhraseMs = 120_000, maxSpeechMs = 60 * 60_000) else null)
        rec = r
        assertTrue(r.start())
        posted.forEach { it.run() }
        return r to events
    }

    /** one phrase: only noise stops after 8 s with "only noise", nothing to send */
    @Test fun onePhraseOfNoiseStops() {
        for (noise in listOf(Noises.pink(12.0, 3000.0), Noises.birds(12.0, 4000.0), Noises.clicks(12.0, 20000.0, 5))) {
            val (r, e) = record(noise, continuous = false)
            assertEquals(listOf("only noise"), e.list)
            assertTrue(r.audio.durationMs <= 8100, "${r.audio.durationMs}")
            val (pct, db, _) = e.stats!!
            assertTrue(pct <= 5, "speech $pct %")
            assertTrue(db > -70 && db < -20, "noise $db dBFS")
        }
    }

    /** silence is not noise: "no speech" as before */
    @Test fun onePhraseOfSilenceIsNoSpeech() {
        val (_, e) = record(Noises.white(12.0, 5.0), continuous = false)
        assertEquals(listOf("no speech"), e.list)
    }

    /** speech in noise ends at the pause and is sent */
    @Test fun onePhraseOfSpeechInNoiseIsSent() {
        val (r, e) = record(Noises.mix(Noises.syllables(0.5, 2.0, 7.5, 6000.0), Noises.pink(10.0, 1500.0)), continuous = false)
        assertEquals(listOf("end true"), e.list)
        assertTrue(r.audio.durationMs in 3700L..4200L, "${r.audio.durationMs}")
        assertEquals(0, e.stats!!.third)
        assertTrue(e.stats!!.first in 20..60, "speech ${e.stats!!.first} % of frames")
    }

    /** continuous dictation: noise between phrases is no phrase; after the silence of the settings in noise, "noise" */
    @Test fun continuousSpeechThenNoise() {
        val sound = Noises.mix(Noises.concat(Noises.syllables(0.5, 2.0, 5.0, 6000.0), Noises.syllables(0.0, 1.5, 15.0, 6000.0)),
            Noises.pink(24.0, 1500.0))
        val (_, e) = record(sound, continuous = true)
        assertEquals(2, e.phrases.size, "the two phrases, noise not sent")
        assertEquals(listOf("noise"), e.list)
    }

    /** the safety catch: 20 s without a phrase of speech end the dictation even if the silence setting is longer */
    @Test fun twentySecondsOfNoiseStopTheDictation() {
        var stoppedAt = 0L
        val (r, e) = record(Noises.birds(40.0, 4000.0), continuous = true, silenceMs = 60_000, onRead = { _, ms -> stoppedAt = ms })
        assertEquals(listOf("noise"), e.list)
        assertTrue(e.phrases.isEmpty())
        assertTrue(stoppedAt in 19_900L..20_200L, "$stoppedAt")
        assertEquals(0L, r.phrasesMs, "nothing sent, nothing paid")
    }

    /** "Done" in noise without speech sends nothing (stage 5g-6), with some speech it sends (stage 5b) */
    @Test fun doneSendsOnlyWithSpeech() {
        val noise = Noises.pink(6.0, 3000.0)
        val (r1, e1) = record(noise, continuous = false, onRead = { rec, ms -> if (ms >= 3000) rec.stop() })
        assertEquals(listOf("end false by user"), e1.list)
        assertFalse(Loudness.shouldSend(false, true, r1.audio.durationMs, r1.peakDbfs, r1.speechHeardMs), "noise: ${r1.speechHeardMs} ms of speech")
        val word = Noises.mix(Noises.syllables(0.5, 0.5, 5.0, 6000.0), Noises.pink(6.0, 1500.0))
        val (r2, e2) = record(word, continuous = false, onRead = { rec, ms -> if (ms >= 1500) rec.stop() })
        assertTrue(Loudness.shouldSend(e2.list.single() == "end true by user", true, r2.audio.durationMs, r2.peakDbfs, r2.speechHeardMs), "a word: ${r2.speechHeardMs} ms of speech")
    }
}
