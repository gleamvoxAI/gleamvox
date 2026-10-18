// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.PreCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** stage 5g-24: the microphone opens at the touch; the tap takes the sound over without a gap, a touch that is nothing wipes it */
class PreCaptureTest {
    /** a microphone that counts its samples: sample i has the value i (mod 30000), 20 ms per read like the real one */
    private class CountingMic(val openFails: Boolean = false) : PcmSource {
        var next = 0
        @Volatile var started = false
        @Volatile var stopped = false
        override fun start(): Boolean { started = !openFails; return started }
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(20)
            for (i in buffer.indices) buffer[i] = ((next++) % 30000).toShort()
            return buffer.size
        }
        override fun stop() { stopped = true }
    }

    @Test fun theTapTakesOverTheSoundOfTheTouchWithoutAGap() {
        val mic = CountingMic()
        val p = PreCapture(mic)
        Thread.sleep(150) // the finger is on the key
        val src = assertNotNull(p.adopt())
        assertTrue(src.primedMs >= 60, "${src.primedMs} ms from the touch")
        // the recording reads on: the samples go on 0, 1, 2, ... with nothing lost or doubled at the joint
        val buf = ShortArray(PreCapture.FRAME)
        var expected = 0
        repeat(20) {
            val n = src.read(buf)
            for (i in 0 until n) { assertEquals((expected % 30000).toShort(), buf[i], "sample $expected"); expected++ }
        }
        assertFalse(mic.stopped, "the same microphone goes on")
        src.stop()
        assertTrue(mic.stopped)
    }

    @Test fun aTouchThatIsNothingWipesTheSoundAndClosesTheMicrophone() {
        val mic = CountingMic()
        val p = PreCapture(mic)
        Thread.sleep(80)
        assertTrue(p.bufferedMs() > 0)
        p.discard()
        assertTrue(mic.stopped)
        assertEquals(0, p.bufferedMs())
    }

    @Test fun aMicrophoneThatDoesNotOpenGivesNothing() {
        val p = PreCapture(CountingMic(openFails = true))
        Thread.sleep(30)
        assertNull(p.adopt(), "the recording opens its own microphone then")
    }

    @Test fun aLongHoldKeepsOnlyTheLastSeconds() {
        val mic = CountingMic()
        val p = PreCapture(mic)
        Thread.sleep(PreCapture.MAX_MS + 400)
        assertTrue(p.bufferedMs() <= PreCapture.MAX_MS + 20, "${p.bufferedMs()} ms")
        p.discard()
    }
}
