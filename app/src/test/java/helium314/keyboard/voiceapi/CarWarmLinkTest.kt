// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voice.CarWarmLink
import helium314.keyboard.latin.voiceapi.HeadsetMic
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.MicRouting
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 5g-21: one phrase from the phone, then the car's link is held warm; a phrase in that time records from the car
 * at once (nothing lost); after the time, media, a hidden keyboard, a call it is let go; earbuds are not touched.
 */
class CarWarmLinkTest {
    /** time and delays run by hand */
    private class Clock {
        var now = 0L
        val q = mutableListOf<Pair<Long, Runnable>>()
        val later: (Long, Runnable) -> (() -> Unit) = { ms, r -> val e = (now + ms) to r; q.add(e); { q.remove(e) } }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = q.filter { it.first <= until }.minByOrNull { it.first } ?: break
                q.remove(next); now = next.first; next.second.run()
            }
            now = until
        }
    }

    private class Car(val linkMs: Long, val clock: Clock) : HeadsetMic {
        var connects = 0
        var releases = 0
        var at = -1L
        override val deviceKey = "7:F0:12"
        override fun connect(): Boolean { connects++; at = clock.now; return true }
        override fun ready() = at >= 0 && clock.now - at >= linkMs
        override fun isUp() = ready()
        override fun source(): PcmSource = SyntheticSource(listOf(10.0 to false))
        override fun release() { releases++; at = -1 }
    }

    private class Setup(linkMs: Long = 1_500) {
        val clock = Clock()
        val car = Car(linkMs, clock)
        var media = false
        var call = false
        val released = mutableListOf<String>()
        val warm = CarWarmLink(clock.later, { clock.now }, { media }, { call }) { released.add(it) }
    }

    private val key = "7:F0:12"

    /** one phrase from the phone → the link comes up in the background and is warm; after 60 s it is let go */
    @Test fun warmAfterAPhraseThenTimeout() {
        val s = Setup()
        assertTrue(s.warm.warmUp(s.car, key, 60_000))
        assertEquals(1, s.car.connects)
        assertFalse(s.warm.isWarm(key), "the link is not up yet")
        s.clock.advance(1_600)
        assertTrue(s.warm.isWarm(key), "warm: the next phrase may record from the car")
        assertFalse(s.warm.isWarm("7:AE:01"), "only this device")
        s.clock.advance(59_000)
        assertFalse(s.warm.isWarm(key), "the time is over: the phone again")
        assertEquals(1, s.car.releases)
        assertTrue(s.released.single().endsWith("released by timeout"), s.released.toString())
        assertTrue(s.released.single().startsWith("held 60000 ms"), s.released.toString())
    }

    /** a phrase in the window holds the link while it records and from its end anew */
    @Test fun aPhraseInTheWindowHoldsItAgain() {
        val s = Setup()
        s.warm.warmUp(s.car, key, 60_000)
        s.clock.advance(50_000)
        val leased = assertNotNull(s.warm.lease())
        s.clock.advance(20_000) // records 20 s: the 60 s are over meanwhile, still held
        leased.release() // the recorder gives it back at the end: it stays warm
        assertEquals(0, s.car.releases)
        s.warm.endUse(keep = true)
        s.clock.advance(59_000)
        assertTrue(s.warm.isWarm(key))
        s.clock.advance(2_000)
        assertFalse(s.warm.isWarm(key))
    }

    /** the car did not hear (probation): let go at once */
    @Test fun aSilentCarIsLetGo() {
        val s = Setup()
        s.warm.warmUp(s.car, key, 60_000); s.clock.advance(1_600)
        s.warm.lease()
        s.warm.endUse(keep = false)
        assertEquals(1, s.car.releases)
        assertTrue(s.released.single().endsWith(CarWarmLink.REASON_REJECTED))
    }

    /** media starts on the phone, a call, a hidden keyboard, the screen, the car gone: let go at once */
    @Test fun releasedByMediaCallHideAndOthers() {
        Setup().let { s ->
            s.warm.warmUp(s.car, key, 60_000); s.clock.advance(2_000)
            s.media = true; s.clock.advance(1_000)
            assertTrue(s.released.single().endsWith("media")); assertEquals(1, s.car.releases)
        }
        Setup().let { s ->
            s.warm.warmUp(s.car, key, 60_000); s.clock.advance(2_000)
            s.call = true; s.clock.advance(1_000)
            assertTrue(s.released.single().endsWith("call"))
        }
        Setup().let { s ->
            s.warm.warmUp(s.car, key, 60_000); s.clock.advance(2_000)
            s.warm.onHidden(); s.clock.advance(5_000); s.warm.onShown(); s.clock.advance(6_000)
            assertTrue(s.warm.isWarm(key), "shown again within 10 s: still warm")
            s.warm.onHidden(); s.clock.advance(10_000)
            assertTrue(s.released.single().endsWith("hide"))
        }
        Setup().let { s ->
            s.warm.warmUp(s.car, key, 60_000); s.clock.advance(2_000)
            s.warm.release(CarWarmLink.REASON_DISCONNECT)
            assertTrue(s.released.single().endsWith("disconnect")); assertEquals(1, s.car.releases)
        }
        // no media while a dictation records from it: nothing is let go in that time
        Setup().let { s ->
            s.warm.warmUp(s.car, key, 60_000); s.clock.advance(2_000)
            s.warm.lease(); s.media = true; s.clock.advance(5_000)
            assertTrue(s.released.isEmpty())
        }
    }

    /** a link that never comes up is given back after 3 s */
    @Test fun noLinkNoWarm() {
        val s = Setup(linkMs = Long.MAX_VALUE / 2)
        s.warm.warmUp(s.car, key, 60_000)
        s.clock.advance(3_100)
        assertTrue(s.released.single().endsWith(CarWarmLink.REASON_NO_LINK))
        assertFalse(s.warm.isWarm(key))
    }

    /** when to warm: one phrase of the phone with a car (or on the warm car) and a text in; never for earbuds or "off" */
    @Test fun whenToWarm() {
        assertTrue(CarWarmLink.shouldWarm(MicRouting.CarPlan.PHONE_ONLY, false, true, 60_000))
        assertTrue(CarWarmLink.shouldWarm(MicRouting.CarPlan.NONE, true, true, 60_000))
        assertFalse(CarWarmLink.shouldWarm(MicRouting.CarPlan.NONE, false, true, 60_000), "earbuds, no Bluetooth: nothing")
        assertFalse(CarWarmLink.shouldWarm(MicRouting.CarPlan.AFTER_FIRST_PHRASE, false, true, 60_000), "continuous: the pause of 5g-20")
        assertFalse(CarWarmLink.shouldWarm(MicRouting.CarPlan.PHONE_ONLY, false, false, 60_000), "no text")
        assertFalse(CarWarmLink.shouldWarm(MicRouting.CarPlan.PHONE_ONLY, false, true, 0), "off")
        assertEquals(listOf(30, 60, 120, 0), CarWarmLink.CHOICES_S.toList())
        assertEquals(60, CarWarmLink.DEFAULT_S)
    }

    /** the second phrase in the window: from the warm car at once, from its first millisecond (nothing lost) */
    @Test fun secondPhraseOnTheWarmCarLosesNothing() {
        val s = Setup(linkMs = 0)
        val speech = listOf(0.0 to false) + syllables(3.0) + listOf(3.0 to false)
        val car = FakeHeadset(deviceKey = key) { SyntheticSource(speech, tone = amplitudeOf(-12.0)) }
        assertTrue(s.warm.warmUp(car, key, 60_000)); s.clock.advance(200)
        assertTrue(s.warm.isWarm(key))
        val leased = s.warm.lease()!!
        val ends = mutableListOf<String>()
        val mics = mutableListOf<Boolean>()
        val r = PcmRecorder({ SyntheticSource(speech, tone = amplitudeOf(-30.0)) }, Loudness.SOURCE_MIC, 60_000, object : PcmRecorder.Listener {
            override fun onLevel(level: Float) = Unit
            override fun onEnd(hadSpeech: Boolean, byUser: Boolean) { ends.add("end $hadSpeech") }
            override fun onNoSpeech() { ends.add("no speech") }
            override fun onFailed() { ends.add("failed") }
            override fun onMic(headset: Boolean, fellBack: Boolean) { mics.add(headset) }
        }, { it.run() }, detectorFactory = { SpeechEndDetector(endSilenceMs = 1300) },
            threadFactory = { t -> object : Thread() { override fun start() = t.run() } }, headset = leased,
            headsetEager = true, headsetCar = true, phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER)
        assertTrue(r.start())
        assertEquals(listOf("end true"), ends)
        assertEquals(listOf(true), mics, "the car records")
        assertTrue(r.onHeadset)
        val b = ByteArray(r.audio.byteCount).also { r.audio.copyTo(it, 0) }
        val first300 = (0 until 300 * 16).maxOf { kotlin.math.abs(((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xFF)).toShort().toInt()) }
        assertTrue(first300 > 3_000, "speech from 0 ms, from the car (-12 dBFS): $first300")
        assertEquals(0, car.released, "the warm link stays after the phrase")
        s.warm.endUse(keep = true)
        assertTrue(s.warm.isWarm(key))
    }

    /** owner's addition 2: the probe "mute the radio with a call": never with earbuds, on a warm link or switched off; its journal line */
    @Test fun muteCallProbe() {
        val m = helium314.keyboard.latin.voice.CarMuteCall
        assertFalse(m.shouldStart(carLike = false, warm = false, on = true), "earbuds, no Bluetooth")
        assertFalse(m.shouldStart(carLike = true, warm = true, on = true), "a warm link mutes the radio already")
        assertFalse(m.shouldStart(carLike = true, warm = false, on = false), "switched off")
        val session = helium314.keyboard.latin.voice.CarMuteCall.Session(1_000)
        session.activeAt = 1_120; session.sco = false; session.endedAt = 4_000
        assertEquals("call state sent at 120 ms, sco: no, phone level during first 2 s -12 dBFS, call ended at 3000 ms", session.summary(-12.4))
        assertEquals("call state sent at never, sco: ?, phone level during first 2 s ?, call ended at ?", helium314.keyboard.latin.voice.CarMuteCall.Session(0).summary(null))
    }

    // ---- stage 5g-22: the check of 4401 in the car ----

    private val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val a2dp = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private fun dev(type: Int) = helium314.keyboard.latin.voiceapi.AudioDevice(type, "XX:XX:XX:XX:12:F0")

    /** a failed check on the warm link is no strike; a mark never stops the mute call (the car is still the car) */
    @Test fun aMarkNeverStopsTheMuteCallAndTheWarmCheckIsNoStrike() {
        assertFalse(MicRouting.strikeAllowed(provenWithMedia = false, onWarm = true), "the warm car proved it hears")
        assertTrue(MicRouting.strikeAllowed(provenWithMedia = false, onWarm = false))
        val marked = MicRouting.decide(helium314.keyboard.latin.voiceapi.MicChoice.AUTO, listOf(dev(sco)), listOf(dev(sco)), emptyList(),
            listOf(dev(sco)), notHeadset = { true })
        assertTrue(marked.decision.skipped, "the mark keeps its microphone out")
        assertFalse(marked.carLike)
        assertNotNull(marked.carDevice, "but the car is there")
        assertTrue(helium314.keyboard.latin.voice.CarMuteCall.shouldStart(marked.carDevice != null, false, true), "so the radio is muted again")
        assertEquals(MicRouting.CarPlan.PHONE_ONLY, MicRouting.carPlan(marked.carDevice != null, continuous = false), "and warmed after the phrase")
    }

    /** F0 got a media profile ("in sco, out sco, media"): a known car stays a car, it is not taken for earbuds */
    @Test fun aKnownCarWithMediaStaysACar() {
        val inputs = listOf(dev(sco)); val outputs = listOf(dev(sco), dev(a2dp)); val media = listOf(dev(a2dp))
        val unknown = MicRouting.decide(helium314.keyboard.latin.voiceapi.MicChoice.AUTO, inputs, outputs, media, listOf(dev(sco)), notHeadset = { false })
        assertEquals("headset proven", unknown.reason)
        val known = MicRouting.decide(helium314.keyboard.latin.voiceapi.MicChoice.AUTO, inputs, outputs, media, listOf(dev(sco)),
            notHeadset = { false }, knownCar = { true })
        assertTrue(known.carLike, known.reason)
        assertNotNull(known.carDevice)
    }

    /** the warm link went down by itself: found before the dictation, given up; the phone (and the mute call) then */
    @Test fun aLostWarmLinkIsColdAgain() {
        val s = Setup(linkMs = 0)
        s.warm.warmUp(s.car, key, 60_000); s.clock.advance(200)
        assertTrue(s.warm.isWarm(key))
        assertEquals(null, s.warm.lost(), "alive")
        s.car.at = -1 // the car connected its media: the link is gone
        val why = s.warm.lost()
        assertNotNull(why)
        assertFalse(s.warm.isWarm(key))
        assertTrue(s.released.single().startsWith("held") && "lost" in s.released.single(), s.released.toString())
        // the same found by the tick of every second
        val t = Setup(linkMs = 0)
        t.warm.warmUp(t.car, key, 60_000); t.clock.advance(200)
        t.car.at = -1; t.clock.advance(1_100)
        assertTrue(t.released.single().contains("lost"), t.released.toString())
    }

    /** the phone records first, the call comes after its first frame; the level of the first 2 s is measured */
    @Test fun phoneFirstThenTheCallAndTheEarlyLevel() {
        val order = mutableListOf<String>()
        val src = object : PcmSource {
            val inner = SyntheticSource(listOf(0.3 to false) + syllables(1.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0))
            override fun start(): Boolean { order.add("mic"); return inner.start() }
            override fun read(buffer: ShortArray) = inner.read(buffer)
            override fun stop() = inner.stop()
        }
        val r = PcmRecorder({ src }, Loudness.SOURCE_MIC, 60_000, object : PcmRecorder.Listener {
            override fun onLevel(level: Float) = Unit
            override fun onEnd(hadSpeech: Boolean, byUser: Boolean) = Unit
            override fun onNoSpeech() = Unit
            override fun onFailed() = Unit
            override fun onFirstFrame(atMs: Long) { order.add("call") } // VoiceInput places the call here
        }, { it.run() }, detectorFactory = { SpeechEndDetector(endSilenceMs = 1300) },
            threadFactory = { t -> object : Thread() { override fun start() = t.run() } })
        assertTrue(r.start())
        assertEquals(listOf("mic", "call"), order)
        assertTrue(r.earlyPeakDbfs > -10, "the level of the first 2 s: ${r.earlyPeakDbfs}")
    }
}
