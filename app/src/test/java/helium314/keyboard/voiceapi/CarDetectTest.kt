// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import android.bluetooth.BluetoothClass.Device
import helium314.keyboard.latin.voiceapi.AudioDevice
import helium314.keyboard.latin.voiceapi.CarDetect
import helium314.keyboard.latin.voiceapi.CarDetect.Kind
import helium314.keyboard.latin.voiceapi.MicChoice
import helium314.keyboard.latin.voiceapi.MicRouting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** stage 5g-25: a watch and the car alarm ("in sco, out sco", no media) are no car radio */
class CarDetectTest {
    @Test fun classes() {
        assertEquals(Kind.CAR, CarDetect.kind(Device.Major.AUDIO_VIDEO, Device.AUDIO_VIDEO_CAR_AUDIO))
        assertEquals(Kind.CAR, CarDetect.kind(Device.Major.AUDIO_VIDEO, Device.AUDIO_VIDEO_HANDSFREE))
        assertEquals(Kind.HEADSET, CarDetect.kind(Device.Major.AUDIO_VIDEO, Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertEquals(Kind.OTHER, CarDetect.kind(Device.Major.AUDIO_VIDEO, Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(Kind.WATCH, CarDetect.kind(Device.Major.WEARABLE, Device.WEARABLE_WRIST_WATCH))
        assertEquals(Kind.OTHER, CarDetect.kind(Device.Major.MISC, 0))                      // alarm modules
        assertEquals(Kind.OTHER, CarDetect.kind(Device.Major.UNCATEGORIZED, Device.Major.UNCATEGORIZED)) // telematics
        assertEquals(Kind.OTHER, CarDetect.kind(Device.Major.PHONE, Device.PHONE_SMART))
        assertEquals(Kind.UNKNOWN, CarDetect.kind(null, null))                              // no permission
    }

    @Test fun verdict() {
        // the class decides when it is known; a proof or the name do not change it
        assertTrue(CarDetect.mayBeCar(Kind.CAR, "Galaxy Watch", heardAsCar = false, notCar = false))
        assertFalse(CarDetect.mayBeCar(Kind.WATCH, "MY CAR", heardAsCar = true, notCar = false))
        assertFalse(CarDetect.mayBeCar(Kind.OTHER, "StarLine", heardAsCar = false, notCar = false))
        // "This is not a car" always wins
        assertFalse(CarDetect.mayBeCar(Kind.CAR, "CAR MULTIMEDIA", heardAsCar = true, notCar = true))
        // class unknown: the name of a watch or an alarm says no, otherwise the old rule (a call input without media)
        assertFalse(CarDetect.mayBeCar(Kind.UNKNOWN, "Galaxy Watch6 (A1B2)", heardAsCar = false, notCar = false))
        assertFalse(CarDetect.mayBeCar(Kind.UNKNOWN, "StarLine S96", heardAsCar = false, notCar = false))
        assertFalse(CarDetect.mayBeCar(Kind.UNKNOWN, "Mi Smart Band 8", heardAsCar = false, notCar = false))
        assertTrue(CarDetect.mayBeCar(Kind.UNKNOWN, "Honda Fit", heardAsCar = false, notCar = false))
        assertTrue(CarDetect.mayBeCar(Kind.UNKNOWN, null, heardAsCar = false, notCar = false))
        assertTrue(CarDetect.mayBeCar(Kind.UNKNOWN, "Pandora watch", heardAsCar = true, notCar = false), "it gave speech as a car")
    }

    private val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val a2dp = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private fun dev(type: Int, tail: String) = AudioDevice(type, "XX:XX:XX:XX:12:$tail")

    /** the owner's case: the watch (F0) and the car alarm (28), not in the car: nothing of the car's way starts */
    @Test fun watchAndAlarmStartNothing() {
        val inputs = listOf(dev(sco, "F0"), dev(sco, "28"))
        val r = MicRouting.decide(MicChoice.AUTO, inputs, inputs, emptyList(), inputs, notHeadset = { false }, mayBeCar = { false })
        assertFalse(r.decision.headset)
        assertFalse(r.carLike)
        assertNull(r.carDevice, "no quiet call, no warm link")
        assertEquals("bluetooth calls only, no car (watch, alarm or similar)", r.reason)
        assertEquals(MicRouting.CarPlan.NONE, MicRouting.carPlan(r.carDevice != null, continuous = true))
    }

    /** several devices: only the real car radio is chosen */
    @Test fun theCarAmongAWatchAndAnAlarm() {
        val watch = dev(sco, "F0"); val alarm = dev(sco, "28"); val car = dev(sco, "3C")
        val inputs = listOf(watch, alarm, car)
        val r = MicRouting.decide(MicChoice.AUTO, inputs, inputs + dev(a2dp, "3C"), emptyList(), inputs, notHeadset = { false },
            mayBeCar = { it == car })
        assertEquals(car, r.carDevice)
        assertTrue(r.carLike)
    }

    /** "Headset" chosen by hand still tries any call input (the person decided) */
    @Test fun handChoiceUnchanged() {
        val inputs = listOf(dev(sco, "F0"))
        val r = MicRouting.decide(MicChoice.HEADSET, inputs, inputs, emptyList(), inputs, notHeadset = { false }, mayBeCar = { false })
        assertTrue(r.decision.headset)
        assertNull(r.carDevice)
    }

    @Test fun journalLine() {
        assertEquals("Galaxy Watc… wearable/0x704 watch", CarDetect.describe(CarDetect.Info("Galaxy Watch6", Device.Major.WEARABLE, Device.WEARABLE_WRIST_WATCH)))
        assertEquals("CAR audio/0x420 car", CarDetect.describe(CarDetect.Info("CAR", Device.Major.AUDIO_VIDEO, Device.AUDIO_VIDEO_CAR_AUDIO)))
        assertEquals("StarLine class unknown unknown", CarDetect.describe(CarDetect.Info("StarLine", null, null)))
    }
}
