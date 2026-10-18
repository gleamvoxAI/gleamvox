// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log

/**
 * Stage 5g-25 (the owner, not in the car: a watch and the car alarm connected by Bluetooth, "in sco, out sco", were taken
 * for the car radio, the "quiet call" started and ate the first word). "A call input without media" is no proof of a
 * car: watches and alarm modules can only do calls, too. A device is a car when
 *  - its Bluetooth class says so (audio/video: car audio or hands-free kit; needs BLUETOOTH_CONNECT, asked once), or
 *  - the class is unknown (no permission) and the device once gave speech as a car ([KnownCars]), or nothing says
 *    otherwise (the name of a watch or an alarm module, [NotCars]) — the old rule, only while the class is unknown.
 * A watch, a band, a speaker, a phone, a computer, a "misc" or uncategorized device (alarm modules, telematics) is never
 * a car by its class. Nothing is set by hand; "This is not a car" in the voice diagnostics is the fallback.
 */
object CarDetect {
    enum class Kind { CAR, WATCH, HEADSET, OTHER, UNKNOWN }

    /** [major], [deviceClass]: BluetoothClass.majorDeviceClass and deviceClass; null = unknown (no permission) */
    fun kind(major: Int?, deviceClass: Int?): Kind {
        if (major == null || deviceClass == null) return Kind.UNKNOWN
        return when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Kind.CAR
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> Kind.HEADSET
                else -> Kind.OTHER // loudspeaker, portable audio, hi-fi, TV, ...
            }
            BluetoothClass.Device.Major.WEARABLE -> Kind.WATCH
            else -> Kind.OTHER // misc, uncategorized (alarm modules, telematics), phone, computer, health, toy, ...
        }
    }

    /** parts of names of watches, bands and alarm modules (not "fit", "key", "tag": names of cars have them, too) */
    private val NOT_CAR_NAMES = listOf(
        "watch", "band", "часы", "браслет", "amazfit", "galaxy fit", "gear s", "garmin",
        "starline", "pandora", "scher-khan", "alarm", "сигнализ", "tracker",
    )

    /** the name tells a watch, a band or an alarm module (used only while the class is unknown) */
    fun nameSaysNotCar(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return NOT_CAR_NAMES.any { n.contains(it) }
    }

    /**
     * the verdict for one device with a call input and no proven headset; [heardAsCar]: it once gave speech as a car,
     * [notCar]: marked as no car ("This is not a car")
     */
    fun mayBeCar(kind: Kind, name: String?, heardAsCar: Boolean, notCar: Boolean): Boolean = when {
        notCar -> false
        kind == Kind.CAR -> true
        kind != Kind.UNKNOWN -> false
        heardAsCar -> true
        else -> !nameSaysNotCar(name)
    }

    /** what the journal shows about a device: "Galaxy Watc… wearable/0x0704" (the name cut to 12 characters) */
    fun describe(info: Info?): String {
        if (info == null) return "class unknown"
        val name = info.name?.let { if (it.length > 12) it.take(11) + "…" else it } ?: "?"
        val major = when (info.major) {
            null -> "class unknown"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.MISC -> "misc"
            BluetoothClass.Device.Major.UNCATEGORIZED -> "uncategorized"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            else -> "major 0x" + Integer.toHexString(info.major)
        }
        val minor = info.deviceClass?.let { "/0x" + Integer.toHexString(it) } ?: ""
        return "$name $major$minor ${kind(info.major, info.deviceClass).name.lowercase()}"
    }

    /** name and class of a Bluetooth device; null fields: unknown */
    data class Info(val name: String?, val major: Int?, val deviceClass: Int?)

    fun permitted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * the Bluetooth device that ends with the address [tail] ("EE:FF"); the name comes from the audio device (Android gives
     * it without a permission), the class only with BLUETOOTH_CONNECT
     */
    fun info(context: Context, tail: String?, productName: CharSequence?): Info {
        val name = productName?.toString()?.takeIf { it.isNotBlank() }
        if (tail == null || !permitted(context)) return Info(name, null, null)
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Info(name, null, null)
            @Suppress("MissingPermission")
            val d = adapter.bondedDevices?.firstOrNull { it.address?.endsWith(tail, ignoreCase = true) == true }
                ?: return Info(name, null, null)
            @Suppress("MissingPermission")
            val c = d.bluetoothClass
            @Suppress("MissingPermission")
            Info(d.name ?: name, c?.majorDeviceClass, c?.deviceClass)
        } catch (e: Exception) { // SecurityException on some phones, a turned off adapter
            Log.w("CarDetect", "no Bluetooth class: ${e.javaClass.simpleName}")
            Info(name, null, null)
        }
    }

    // ---- the permission: asked once, after a dictation, when a device with a call input was there ----
    const val PREF_ASKED = "aikb_bt_connect_asked"
    fun shouldAsk(prefs: SharedPreferences, callOnlyDevice: Boolean, permitted: Boolean) =
        callOnlyDevice && !permitted && !prefs.getBoolean(PREF_ASKED, false)
    fun markAsked(prefs: SharedPreferences) = prefs.edit { putBoolean(PREF_ASKED, true) }

    /** the permission is in this build (only personal has it) */
    fun declared(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.BLUETOOTH_CONNECT) == true
    } catch (e: Exception) { false }
}

/**
 * Stage 5g-25: devices marked "This is not a car" in the voice diagnostics (the fallback; normally the class decides).
 * Only the type and the end of the address, in the prefs.
 */
object NotCars {
    const val PREF = "aikb_not_cars"
    fun has(prefs: SharedPreferences, key: String?) = key != null && key in prefs.getStringSet(PREF, emptySet()).orEmpty()
    fun all(prefs: SharedPreferences): Set<String> = prefs.getStringSet(PREF, emptySet()).orEmpty()
    fun add(prefs: SharedPreferences, key: String?) {
        if (key == null || has(prefs, key)) return
        prefs.edit { putStringSet(PREF, all(prefs) + key) }
        KnownCars.remove(prefs, key)
    }
    fun clear(prefs: SharedPreferences) = prefs.edit { remove(PREF) }

    /** the devices with a call input at the last tap: what "This is not a car" marks */
    private const val PREF_LAST = "aikb_last_call_devices"
    fun rememberLast(prefs: SharedPreferences, keys: List<String>) {
        if (keys.toSet() != last(prefs)) prefs.edit { putStringSet(PREF_LAST, keys.toSet()) }
    }
    fun last(prefs: SharedPreferences): Set<String> = prefs.getStringSet(PREF_LAST, emptySet()).orEmpty()
    /** "This is not a car": the devices of the last tap that are not marked yet */
    fun markLast(prefs: SharedPreferences) = last(prefs).forEach { add(prefs, it) }
}
