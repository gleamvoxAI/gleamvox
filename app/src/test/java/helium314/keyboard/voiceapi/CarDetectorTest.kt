// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.SileroVad
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 5g-27 (4449 in the car F0: in the call mode the phone records speech at -25…-32 dBFS, Silero gave it 0.45–0.69 and
 * the recording stopped in the middle): with a car the detectors hear a raised copy, the thresholds are lower, the pause
 * before sending is at least 2 s and there is no end in the first 2 s. Real speech (jfk.wav) and the real Silero model.
 */
class CarDetectorTest {
    private val model by lazy { SileroVad.load { File("src/aikbPersonal/assets/silero_vad.onnx").readBytes() } ?: error("model: ${SileroVad.failure}") }

    private class Run(val event: SpeechEndDetector.Event, val atMs: Long, val d: SpeechEndDetector)

    private fun detect(sound: DoubleArray, d: SpeechEndDetector): Run {
        val s = SileroVadTest.pcm(sound)
        val f = ShortArray(320)
        var pos = 0
        while (pos + 320 <= s.size) {
            System.arraycopy(s, pos, f, 0, 320)
            val e = d.feed(f, 320)
            pos += 320
            if (e != SpeechEndDetector.Event.NONE) return Run(e, pos * 1000L / PcmBuffer.SAMPLE_RATE, d)
        }
        return Run(SpeechEndDetector.Event.NONE, pos * 1000L / PcmBuffer.SAMPLE_RATE, d)
    }

    private fun detector(car: Boolean, endMs: Int = 1300) = SpeechEndDetector(endSilenceMs = endMs, silero = { model.stream() }, car = car)

    /** the call mode of the car: speech with a peak of -32 dBFS, a quiet background (-62 dBFS) */
    private fun callModeSpeech(seconds: Double): DoubleArray {
        val sp = SileroVadTest.speechAt(-32.0)
        val part = sp.copyOf((seconds * PcmBuffer.SAMPLE_RATE).toInt().coerceAtMost(sp.size))
        return SileroVadTest.mix(SileroVadTest.concat(SileroVadTest.silence(0.3), part, SileroVadTest.silence(4.0)),
            SileroVadTest.road(4.3 + seconds, -62.0))
    }

    /**
     * the owner's case: a pause to think in the middle of a phrase (1.6 s); the phone in the call mode, road noise. The
     * plain detector cut the recording in that pause ("stop: pause"); the car detector waits for the end of the phrase
     */
    @Test fun aPauseInTheMiddleDoesNotCutTheRecording() {
        val sp = SileroVadTest.speechAt(-30.0)
        val r = PcmBuffer.SAMPLE_RATE
        // cut in the middle of "my fellow" (the recording has no pause there): the pause is exactly the inserted 1.6 s
        val first = sp.copyOfRange(0, (1.5 * r).toInt()); val second = sp.copyOfRange((1.5 * r).toInt(), (5.0 * r).toInt())
        val speech = SileroVadTest.concat(SileroVadTest.silence(0.3), first, SileroVadTest.silence(1.6), second, SileroVadTest.silence(4.0))
        val sound = SileroVadTest.mix(speech, SileroVadTest.road(speech.size.toDouble() / r, -52.0))
        val plain = detect(sound, detector(false))
        val car = detect(sound, detector(true))
        println("CAR pause: plain ${plain.event} at ${plain.atMs} ms (prob avg ${plain.d.probAvg}); car ${car.event} at ${car.atMs} ms (prob avg ${car.d.probAvg}, gain ${car.d.lastGain})")
        val secondStartsMs = ((0.3 + 1.5 + 1.6) * 1000).toLong()
        val secondEndsMs = secondStartsMs + 3_000
        assertTrue(plain.atMs <= secondStartsMs, "the plain detector cut in the pause: ${plain.atMs}")
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, car.event)
        assertTrue(car.atMs >= secondEndsMs, "the car detector waited for the whole phrase: ${car.atMs}")
    }

    @Test fun neverAnEndInTheFirstTwoSeconds() {
        // a short word, then silence: the plain detector ends after 1.3 s of pause, the car one not before 2 s from the start
        // and not before 2 s of pause
        val sound = callModeSpeech(0.8)
        val plain = detect(sound, detector(false))
        val car = detect(sound, detector(true))
        println("CAR short: plain ${plain.event} at ${plain.atMs} ms, car ${car.event} at ${car.atMs} ms")
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, car.event)
        assertTrue(car.atMs >= SpeechEndDetector.CAR_NO_END_MS, "no end in the first 2 s: ${car.atMs}")
        assertTrue(car.atMs >= plain.atMs + 500, "the pause before sending is longer in the car: ${car.atMs} vs ${plain.atMs}")
    }

    @Test fun theCopyIsRaisedToAboutMinus9Dbfs() {
        val d = detector(true)
        detect(callModeSpeech(3.0), d)
        assertTrue(d.lastGain in 5.0..SpeechEndDetector.CAR_MAX_GAIN, "gain ${d.lastGain}")
        val loud = detector(true)
        detect(SileroVadTest.mix(SileroVadTest.speechAt(-3.0).copyOf(PcmBuffer.SAMPLE_RATE * 3), SileroVadTest.road(3.0, -60.0)), loud)
        assertTrue(loud.lastGain <= 1.5, "a loud microphone is not raised: ${loud.lastGain}")
    }

    @Test fun thresholdsInTheCarAreLower() {
        val d = detector(true)
        detect(callModeSpeech(2.0), d)
        assertTrue(d.threshold <= 0.5f - SpeechEndDetector.CAR_THRESHOLD_DROP + 0.001f, "threshold ${d.threshold}")
    }
}
