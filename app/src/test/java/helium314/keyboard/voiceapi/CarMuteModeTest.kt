// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voice.CarMuteMode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CarMuteModeTest {
    /** stage 5g-27: the modes of muting the radio: the call via the car by default, the old switch off is "no muting" */
    @Test fun carMuteModes() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("car_modes_test", 0)
        prefs.edit().clear().commit()
        assertEquals(CarMuteMode.CALL_ON_PHONE, CarMuteMode.read(prefs), "the default (5g-28: the owner chose the call on the phone)")
        prefs.edit().putBoolean(helium314.keyboard.latin.voice.CarMuteCall.PREF, false).commit()
        assertEquals(CarMuteMode.OFF, CarMuteMode.read(prefs), "the switch of 5g-21 off stays off")
        assertFalse(helium314.keyboard.latin.voice.CarMuteCall.enabled(prefs))
        CarMuteMode.write(prefs, CarMuteMode.VOICE_COMMAND)
        assertEquals(CarMuteMode.VOICE_COMMAND, CarMuteMode.read(prefs))
        assertTrue(helium314.keyboard.latin.voice.CarMuteCall.enabled(prefs))
        // 5g-28: the voice command is not among the choices (no pause in the car F0); the panel goes phone -> car -> off -> phone
        assertEquals(listOf(CarMuteMode.CALL_ON_PHONE, CarMuteMode.CALL_VIA_CAR, CarMuteMode.OFF), CarMuteMode.choices)
        assertEquals(listOf(CarMuteMode.CALL_VIA_CAR, CarMuteMode.OFF, CarMuteMode.CALL_ON_PHONE),
            listOf(CarMuteMode.next(CarMuteMode.CALL_ON_PHONE), CarMuteMode.next(CarMuteMode.CALL_VIA_CAR), CarMuteMode.next(CarMuteMode.OFF)))
        assertEquals(CarMuteMode.CALL_ON_PHONE, CarMuteMode.next(CarMuteMode.VOICE_COMMAND), "from the hidden mode back to the default")
        assertTrue(CarMuteMode.CALL_VIA_CAR.builtInMic && CarMuteMode.VOICE_COMMAND.builtInMic, "the car's channel comes up: the built-in microphone records")
        assertFalse(CarMuteMode.CALL_ON_PHONE.builtInMic || CarMuteMode.OFF.builtInMic)
        val broken = helium314.keyboard.latin.voice.CarMuteCall.Session(0, CarMuteMode.CALL_VIA_CAR).apply { brokeAt = 1995 }
        assertTrue("mode call_car, pause may have broken at 1995 ms (audio mode normal)" in broken.summary(null), broken.summary(null))
        // 5g-28: the journal says how fast the call mode came after the touch and where our part of the delay went
        val fast = helium314.keyboard.latin.voice.CarMuteCall.Session(1_000, CarMuteMode.CALL_ON_PHONE).apply {
            inCallAt = 1_120; fastPath = "earpiece set, call mode set"; placedAt = 1_030; serviceAt = 1_090; dialingAt = 1_095; activeAt = 1_180; activeBy = "moved off bluetooth"
        }
        assertTrue(fast.summary(null).startsWith("touch to call mode 120 ms (fast), fast path: earpiece set, call mode set, touch to active 180 ms (fast), " +
            "route refusals 0, call state sent at 180 ms (placeCall 30 ms, service 90 ms, dialing 95 ms, active by moved off bluetooth)"), fast.summary(null))
        // 5g-29: "active" later than 200 ms after the touch is slow; the refusals of the route are counted
        val late = helium314.keyboard.latin.voice.CarMuteCall.Session(0).apply { activeAt = 452; routeFailures = 5 }
        assertTrue("touch to active 452 ms (slow), route refusals 5" in late.summary(null), late.summary(null))
        val slow = helium314.keyboard.latin.voice.CarMuteCall.Session(0).apply { inCallAt = 850 }
        assertTrue(slow.summary(null).startsWith("touch to call mode 850 ms (slow)"), slow.summary(null))
    }
}
