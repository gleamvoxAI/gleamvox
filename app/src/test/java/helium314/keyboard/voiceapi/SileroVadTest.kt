// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SileroVad
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 5g-19: the Silero speech detector with the real model (ONNX Runtime for the computer) on the owner's case: a car
 * on a bad road at speed. Road noise is brown noise (the rumble of the tyres) plus the hum of the engine (80–150 Hz),
 * 60–80 dB in the car, here -40…-24 dBFS; speech is a real recording (J. F. Kennedy, public domain, jfk.wav of
 * whisper.cpp, 16 kHz mono, 11 s).
 */
class SileroVadTest {
    companion object {
        private const val R = PcmBuffer.SAMPLE_RATE
        private val model by lazy {
            SileroVad.load { File("src/aikbPersonal/assets/silero_vad.onnx").readBytes() } ?: error("model: ${SileroVad.failure}")
        }
        private val speech: DoubleArray by lazy {
            val b = SileroVadTest::class.java.getResourceAsStream("/voice/jfk.wav")!!.use { it.readBytes() }
            var i = 12
            while (String(b, i, 4) != "data") i += 8 + ((b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16))
            val start = i + 8
            DoubleArray((b.size - start) / 2) { k -> ((b[start + 2 * k + 1].toInt() shl 8) or (b[start + 2 * k].toInt() and 0xFF)).toShort().toDouble() }
        }

        fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
        fun dbToAmp(db: Double) = 32768.0 * Math.pow(10.0, db / 20)

        /** brown noise (tyres on a bad road) and the engine hum (80, 120, 150 Hz, slowly swelling), at [dbfs] RMS */
        fun road(seconds: Double, dbfs: Double, seed: Long = 7): DoubleArray {
            val rnd = Random(seed)
            val n = (seconds * R).toInt()
            var brown = 0.0
            val o = DoubleArray(n) { i ->
                brown = 0.98 * brown + rnd.nextGaussian()
                val t = i.toDouble() / R
                val hum = (sin(2 * PI * 80 * t) + 0.7 * sin(2 * PI * 120 * t) + 0.5 * sin(2 * PI * 150 * t)) * (1 + 0.3 * sin(2 * PI * 0.7 * t))
                brown + 6 * hum + 0.3 * rnd.nextGaussian() // a little hiss of the wind too
            }
            val k = dbToAmp(dbfs) / rms(o)
            for (i in o.indices) o[i] *= k
            return o
        }

        /** the speech raised or lowered to a peak of [peakDbfs] */
        fun speechAt(peakDbfs: Double): DoubleArray {
            val k = dbToAmp(peakDbfs) / speech.maxOf { kotlin.math.abs(it) }
            return DoubleArray(speech.size) { speech[it] * k }
        }

        fun concat(vararg parts: DoubleArray) = DoubleArray(parts.sumOf { it.size }).also { o -> var p = 0; for (x in parts) { x.copyInto(o, p); p += x.size } }
        fun mix(a: DoubleArray, b: DoubleArray) = DoubleArray(maxOf(a.size, b.size)) { (if (it < a.size) a[it] else 0.0) + (if (it < b.size) b[it] else 0.0) }
        fun silence(seconds: Double) = DoubleArray((seconds * R).toInt())
        fun pcm(x: DoubleArray) = ShortArray(x.size) { x[it].toInt().coerceIn(-32768, 32767).toShort() }
    }

    private class Run(val event: SpeechEndDetector.Event, val atMs: Long, val d: SpeechEndDetector)

    private fun sileroDetector(endMs: Int = 1300, noSpeechMs: Int = 8000) =
        SpeechEndDetector(endSilenceMs = endMs, noSpeechTimeoutMs = noSpeechMs, silero = { model.stream() })

    private fun detect(sound: DoubleArray, d: SpeechEndDetector): Run {
        val s = pcm(sound)
        val f = ShortArray(320)
        var pos = 0
        while (pos + 320 <= s.size) {
            System.arraycopy(s, pos, f, 0, 320)
            pos += 320
            val e = d.feed(f, 320)
            if (e != SpeechEndDetector.Event.NONE) return Run(e, pos * 1000L / R, d)
        }
        return Run(SpeechEndDetector.Event.NONE, pos * 1000L / R, d)
    }

    @BeforeTest fun loaded() { assertTrue(model.stream().probability(FloatArray(SileroVad.CHUNK)) < 0.2f, "silence is no speech") }

    /** the real speech is speech for Silero, alone and on loud road noise */
    @Test fun sileroHearsTheRealSpeech() {
        val d = sileroDetector()
        val r = detect(concat(speechAt(-6.0), silence(3.0)), d)
        assertEquals("silero", d.vadName)
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, r.event)
        assertTrue(d.probMax > 0.9f, "max ${d.probMax}")
    }

    /** the owner's case: road noise alone at 60–80 dB in the car: never speech, "only noise" in the time of the settings */
    @Test fun roadNoiseIsNoSpeech() {
        for (db in listOf(-40.0, -32.0, -24.0)) {
            val d = sileroDetector()
            val r = detect(road(12.0, db), d)
            assertEquals(SpeechEndDetector.Event.NO_SPEECH, r.event, "noise $db dBFS")
            assertFalse(d.hadSpeech, "noise $db dBFS")
            assertTrue(d.speechHeardMs < 300, "noise $db dBFS: ${d.speechHeardMs} ms heard as speech, prob max ${d.probMax}")
            println("road $db dBFS: prob avg ${d.probAvg} max ${d.probMax}, threshold ${d.threshold}, noise ${d.noiseDbfs}")
            assertTrue(d.threshold >= 0.6f, "a loud place, a surer threshold: ${d.threshold}")
        }
    }

    /** speech on road noise (about 10 dB above it), then the road alone: the pause comes about 1.3 s after the speech */
    @Test fun pauseComesOnTheRoad() {
        for (db in listOf(-40.0, -32.0)) {
            val d = sileroDetector()
            val noise = road(16.0, db, seed = 11)
            val r = detect(mix(concat(silence(1.0), speechAt(db + 22.0), silence(4.0)), noise), d)
            assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, r.event, "noise $db dBFS")
            // the speech ends at 12 s (1 s before, 11 s of it); it has short pauses of its own, so not before 3 s
            assertTrue(r.atMs in 3000L..14_000L, "noise $db dBFS: the pause at ${r.atMs} ms")
            assertTrue(d.speechHeardMs >= 1000, "noise $db dBFS: ${d.speechHeardMs}")
        }
    }

    /** the old detector on the same road, for comparison (the journal of the owner: it kept on listening); not asserted */
    @Test fun webRtcOnTheSameRoadForTheRecord() {
        val d = SpeechEndDetector(endSilenceMs = 1300, noSpeechTimeoutMs = 8000)
        val r = detect(road(12.0, -24.0), d)
        println("WebRTC on road noise -24 dBFS: ${r.event} at ${r.atMs} ms, speech heard ${d.speechHeardMs} ms, vad ${d.vadName}")
    }

    /** quiet speech (a far phone: peak -40 dBFS) in a quiet room is not lost */
    @Test fun quietSpeechIsNotLost() {
        val loud = sileroDetector().also { detect(mix(concat(speechAt(-6.0), silence(3.0)), road(14.0, -70.0)), it) }
        val d = sileroDetector()
        val r = detect(mix(concat(speechAt(-40.0), silence(3.0)), road(14.0, -70.0)), d)
        println("speech heard: loud ${loud.speechHeardMs} ms, quiet ${d.speechHeardMs} ms")
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, r.event)
        assertTrue(d.speechHeardMs * 10 >= loud.speechHeardMs * 7, "quiet ${d.speechHeardMs} ms of loud ${loud.speechHeardMs} ms")
        assertEquals(0.5f, d.threshold, "a quiet place: the base threshold")
    }

    /** the thresholds follow the noise; with hysteresis (off below on) */
    @Test fun thresholdsFollowTheNoise() {
        assertEquals(0.5f to 0.35f, SpeechEndDetector.sileroThresholds(-60.0))
        assertEquals(0.5f to 0.35f, SpeechEndDetector.sileroThresholds(-45.0))
        val (on40, off40) = SpeechEndDetector.sileroThresholds(-40.0)
        assertTrue(on40 in 0.64f..0.66f && off40 in 0.39f..0.41f, "$on40 $off40")
        val (on20, off20) = SpeechEndDetector.sileroThresholds(-20.0)
        assertTrue(on20 in 0.69f..0.71f && off20 in 0.44f..0.46f, "$on20 $off20")
    }

    /** a continuous dictation on the road with nobody speaking stops as "only noise" after 10 s; nothing is sent */
    @Test fun continuousDictationOnTheRoadStopsAsNoise() {
        val s = pcm(road(30.0, -28.0, seed = 3))
        var pos = 0
        val source = object : PcmSource {
            override fun start() = true
            override fun read(buffer: ShortArray): Int {
                if (pos >= s.size) return -1
                val n = minOf(buffer.size, s.size - pos); System.arraycopy(s, pos, buffer, 0, n); pos += n; return n
            }
            override fun stop() = Unit
        }
        var end: String? = null
        var phrases = 0
        var vad: String? = null
        val l = object : PcmRecorder.Listener {
            override fun onLevel(level: Float) = Unit
            override fun onEnd(hadSpeech: Boolean, byUser: Boolean) = Unit
            override fun onNoSpeech() = Unit
            override fun onFailed() = Unit
            override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) { phrases++ }
            override fun onContinuousEnd(reason: String) { end = reason }
            override fun onVad(name: String, probAvg: Float, probMax: Float, threshold: Float) { vad = name }
        }
        val r = PcmRecorder({ source }, helium314.keyboard.latin.voiceapi.Loudness.SOURCE_MIC, 300_000, l, { it.run() },
            detectorFactory = { SpeechEndDetector(endSilenceMs = 1300, noSpeechTimeoutMs = 60_000, silero = { model.stream() }) },
            threadFactory = { r -> object : Thread() { override fun start() = r.run() } },
            continuous = PcmRecorder.Continuous(maxPhraseMs = 60_000, maxSpeechMs = 600_000))
        assertTrue(r.start())
        assertEquals("noise", end)
        assertEquals(0, phrases, "nothing sent")
        val stoppedAt = pos * 1000L / R
        assertTrue(stoppedAt in 9_500L..11_500L, "stopped at $stoppedAt ms")
        assertEquals("silero", vad)
    }

    /** without the model the detector is the one of 5g-6 and says why */
    @Test fun withoutTheModelWebRtcAsBefore() {
        val d = SpeechEndDetector(endSilenceMs = 1300, silero = { null })
        detect(silence(0.1), d)
        assertTrue(d.vadName.startsWith("webrtc (silero: "), d.vadName)
        assertFalse(d.usesSilero)
    }

    /** a chunk of 32 ms takes well under 5 ms (here on the computer, one thread; arm64 phones are of the same order) */
    @Test fun fastEnough() {
        val st = model.stream()
        val chunk = FloatArray(SileroVad.CHUNK)
        val x = speechAt(-6.0)
        repeat(20) { st.probability(chunk) } // warm up
        val chunks = x.size / SileroVad.CHUNK
        val t0 = System.nanoTime()
        for (c in 0 until chunks) {
            for (i in 0 until SileroVad.CHUNK) chunk[i] = (x[c * SileroVad.CHUNK + i] / 32768).toFloat()
            st.probability(chunk)
        }
        val perChunkMs = (System.nanoTime() - t0) / 1e6 / chunks
        println("Silero: ${"%.3f".format(perChunkMs)} ms per chunk of 32 ms ($chunks chunks)")
        assertTrue(perChunkMs < 5.0, "$perChunkMs ms")
    }
}
