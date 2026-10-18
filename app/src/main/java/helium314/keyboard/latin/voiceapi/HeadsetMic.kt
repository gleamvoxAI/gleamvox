// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

/** "Microphone for voice" (stage 5g, docs/DESIGN.md "Голос: микрофон Bluetooth-наушников"): auto by default */
enum class MicChoice(val value: String) {
    /** the headset if one with a microphone is connected, else the phone */
    AUTO("auto"),
    PHONE("phone"),
    /** the headset; without one the phone (and the panel says so) */
    HEADSET("headset");

    companion object {
        const val PREF = "aikb_voice_mic"
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: AUTO
        fun read(prefs: SharedPreferences) = of(prefs.getString(PREF, null))
        fun save(prefs: SharedPreferences, choice: MicChoice) = prefs.edit { putString(PREF, choice.value) }
    }
}

/**
 * The microphone of a Bluetooth headset (SCO, and LE Audio on Android 12+). The channel is asked for before recording
 * and must always be given back afterwards ([release], also after a cancel), so the music in the headset comes back.
 * All methods may be called from any thread; [release] may be called more than once.
 */
interface HeadsetMic {
    /** the device this is about (type and end of the address), for [SilentMics]; null: unknown */
    val deviceKey: String? get() = null
    /** asks the system to take the input from the headset; false: no headset, or the system refused */
    fun connect(): Boolean
    /** the headset channel is up (Android 12+: it is the communication device; before: SCO connected) */
    fun isUp(): Boolean
    /**
     * stage 5g-14: its recording may be opened now ([source]); false while the link of a car comes up before the call
     * mode is switched on (asked again for every frame of the phone)
     */
    fun ready(): Boolean = true
    /** a recording from the headset (only after [connect] and [ready]) */
    fun source(): PcmSource
    /** stage 5g-22: why the channel is not up now (null: it is up), for the journal of the warm link */
    fun downReason(): String? = if (isUp()) null else "down"
    /** gives the channel back; nothing happens if it was not taken */
    fun release()
}

/**
 * Which microphone a recording tries: [headset] if a real headset is proven (stage 5g-3) and wanted; [missing]:
 * "Headset" chosen, none there; [skipped]: "Auto", but this device was found to be no headset or did not come up twice
 * ([SilentMics]): the phone at once, without waiting.
 */
data class MicDecision(val headset: Boolean, val missing: Boolean, val skipped: Boolean = false) {
    companion object {
        fun of(choice: MicChoice, headsetConnected: Boolean, failedBefore: Boolean = false) = when (choice) {
            MicChoice.PHONE -> MicDecision(headset = false, missing = false)
            MicChoice.AUTO -> if (headsetConnected && failedBefore) MicDecision(headset = false, missing = false, skipped = true)
                else MicDecision(headset = headsetConnected, missing = false)
            MicChoice.HEADSET -> MicDecision(headset = headsetConnected, missing = !headsetConnected)
        }
    }
}

/**
 * An audio device as the routing sees it (stage 5g-3): its type and the end of its address. Without BLUETOOTH_CONNECT
 * Android gives Bluetooth addresses as "XX:XX:XX:XX:EE:FF" (checked in the Android sources, frameworks/av, 2026-10-14):
 * the last two bytes are enough to tell whether an input and an output belong to the same device.
 */
data class AudioDevice(val type: Int, val address: String) {
    /** "EE:FF", or null when the address says nothing (empty, all hidden) */
    val tail: String? get() = address.takeLast(5).takeIf { it.length == 5 && it.any { c -> c.isLetterOrDigit() && c != 'X' } }
}

/**
 * Stage 5g-3 (journal of the owner on 4365): watches and speakers with hands-free give a Bluetooth SCO input too, and
 * as soon as their channel is asked for, the phone microphone of the HONOR becomes quiet. So "Auto" touches nothing in
 * the routing of sound unless a REAL headset is proven: the same device (end of the address) has an input of sound
 * (SCO or LE Audio headset), an output for music (A2DP or LE Audio headset), and that output plays the media right now.
 * Watches (calls only, no music), speakers without a microphone, a band: the phone as in 4351, 0 ms of waiting.
 */
object MicRouting {
    /**
     * Stage 5g-3 (journal of the owner: "bluetooth inputs: none", and still a headset was tried for 1389 ms): a device
     * that connects during a recording is tried only in a continuous dictation (a single phrase is over long before it
     * could help), only when it is proven to be a headset now and not marked as silent.
     */
    fun offerAllowed(continuous: Boolean, choice: MicChoice, provenNow: Boolean, marked: Boolean) =
        continuous && choice != MicChoice.PHONE && provenNow && !marked

    /**
     * Stage 5g-11 (the owner's car FE: proven earbuds with media, marked after one slow try): a failed try counts towards
     * the permanent mark only for devices WITHOUT the proof (watches and the like). A proven device falls back to the
     * phone in this recording only and is tried again at the next tap.
     */
    fun countsTowardsMark(provenWithMedia: Boolean) = !provenWithMedia

    /**
     * stage 5g-22 (4401: the warm car failed its check twice, got marked, and from then the radio was never muted again):
     * a failed check on the warm link is no strike (the car proved it hears)
     */
    fun strikeAllowed(provenWithMedia: Boolean, onWarm: Boolean) = countsTowardsMark(provenWithMedia) && !onWarm

    /** stage 5g-20: how a car-like device is used: never at the tap (every source of the HONOR phone is deaf meanwhile) */
    enum class CarPlan { NONE, PHONE_ONLY, AFTER_FIRST_PHRASE }
    fun carPlan(carLike: Boolean, continuous: Boolean) = when {
        !carLike -> CarPlan.NONE
        continuous -> CarPlan.AFTER_FIRST_PHRASE
        else -> CarPlan.PHONE_ONLY
    }

    /** what [SilentMics] remembers of a device: its type and the end of its address (nothing else) */
    fun key(d: AudioDevice) = "${d.type}:${d.tail ?: "?"}"

    /**
     * [devices]: the Bluetooth devices for the journal (types and the last 2 characters of the address only); [carLike]:
     * the device tried is no proven headset (stage 5g-7: a car or another hands-free device, switched on like a call)
     */
    class Result(val decision: MicDecision, val reason: String, val devices: String, val headset: AudioDevice?, val carLike: Boolean = false,
                 /**
                  * stage 5g-22: the car-like device there is, marked "without sound" or not: the radio is muted with the
                  * call and the link is warmed for it all the same (a mark only keeps its microphone out of a dictation)
                  */
                 val carDevice: AudioDevice? = null)

    private fun isMusicOutput(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /**
     * The input of a proven headset: an SCO / LE Audio input whose device also has a music output that is the current
     * media route ([media] null: unknown before Android 13: never proven).
     */
    fun provenHeadset(inputs: List<AudioDevice>, outputs: List<AudioDevice>, media: List<AudioDevice>?): AudioDevice? {
        if (media == null) return null
        return inputs.firstOrNull { input ->
            val tail = input.tail
            AndroidHeadsetMic.isHeadsetWithMicrophone(input.type) && tail != null &&
                outputs.any { isMusicOutput(it.type) && it.tail == tail } &&
                media.any { isMusicOutput(it.type) && it.tail == tail }
        }
    }

    /**
     * [communication]: the communication devices (Android 12+, null before): "Headset" chosen by hand tries any SCO / LE
     * Audio input usable for calls even without the proof; [notHeadset]: this device was found to be no headset.
     */
    fun decide(choice: MicChoice, inputs: List<AudioDevice>, outputs: List<AudioDevice>, media: List<AudioDevice>?,
               communication: List<AudioDevice>?,
               /** stage 5g-22: a device known as a car stays a car when it plays media too (F0 got a media profile) */
               knownCar: (AudioDevice) -> Boolean = { false },
               notHeadset: (AudioDevice) -> Boolean): Result {
        val proven = provenHeadset(inputs, outputs, media)?.takeIf { !knownCar(it) }
        val anyCallInput = inputs.firstOrNull { i ->
            AndroidHeadsetMic.isHeadsetWithMicrophone(i.type) && (communication == null || communication.any { it.type == i.type })
        }
        // stage 5g-7 (the owner: the car's microphone, switched on like a call, as other apps do): with "Auto" a device
        // with a call input that is no proven headset is tried too, at once, the first one not marked as silent; a watch
        // or another device without a working microphone is tried once, found silent and marked, and skipped from then on
        val callInputs = inputs.filter { i ->
            AndroidHeadsetMic.isHeadsetWithMicrophone(i.type) && (communication == null || communication.any { it.type == i.type })
        }
        val carLike = if (proven == null) callInputs.firstOrNull { !notHeadset(it) } ?: callInputs.firstOrNull() else null
        val candidate = when (choice) {
            MicChoice.AUTO -> proven ?: carLike
            MicChoice.HEADSET -> proven ?: anyCallInput
            MicChoice.PHONE -> null
        }
        // stage 5g-10: proven earbuds (input + the media output of the same device) are always tried, marked or not
        val decision = MicDecision.of(choice, candidate != null, candidate != null && candidate != proven && notHeadset(candidate))
        val btInput = inputs.any { AndroidHeadsetMic.isHeadsetWithMicrophone(it.type) }
        val reason = when {
            choice == MicChoice.PHONE -> "phone chosen"
            !btInput -> "no bluetooth input"
            candidate == null -> if (media == null) "bluetooth input, media route unknown" else "bluetooth input, not usable for calls"
            decision.skipped -> "device without sound (marked)"
            proven == null && choice == MicChoice.AUTO -> "car-like (${NAMES[candidate.type] ?: candidate.type}, no media) tried"
            proven == null -> "headset chosen, not proven"
            else -> "headset proven"
        }
        return Result(decision, reason, describe(inputs, outputs, media), candidate?.takeIf { decision.headset },
            carLike = proven == null && decision.headset,
            carDevice = if (proven == null && choice != MicChoice.PHONE) callInputs.firstOrNull() else null)
    }

    private val NAMES = mapOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "sco",
        AudioDeviceInfo.TYPE_BLE_HEADSET to "ble_headset",
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "a2dp",
        AudioDeviceInfo.TYPE_BLE_SPEAKER to "ble_speaker",
        AudioDeviceInfo.TYPE_BLE_BROADCAST to "ble_broadcast",
        AudioDeviceInfo.TYPE_HEARING_AID to "hearing_aid",
    )

    /**
     * the Bluetooth devices for the journal, by the last 2 characters of the address: "FF: in sco, out sco+a2dp, media;
     * 3C: in sco, out sco" (never names, never more of the address)
     */
    fun describe(inputs: List<AudioDevice>, outputs: List<AudioDevice>, media: List<AudioDevice>?): String {
        val bt = (inputs.map { it to true } + outputs.map { it to false }).filter { it.first.type in NAMES }
        if (bt.isEmpty()) return "none"
        return bt.groupBy { it.first.tail?.takeLast(2) ?: "??" }.entries.joinToString("; ") { (key, list) ->
            val ins = list.filter { it.second }.mapNotNull { NAMES[it.first.type] }.distinct()
            val outs = list.filter { !it.second }.mapNotNull { NAMES[it.first.type] }.distinct()
            val playing = media?.any { m -> m.type in NAMES && m.tail?.takeLast(2) == key } == true
            "$key: " + listOfNotNull(
                ins.takeIf { it.isNotEmpty() }?.let { "in " + it.joinToString("+") },
                outs.takeIf { it.isNotEmpty() }?.let { "out " + it.joinToString("+") },
                "media".takeIf { playing },
            ).joinToString(", ")
        }
    }
}

/**
 * Stage 5g-2: headsets come and go about 20 times a day. Every new connection of a headset with a microphone forgets that
 * it did not answer before ([onNewHeadset]); a headset that appears while recording is offered to the recording, one
 * that goes away takes the recording back to the phone ([onHeadsetGone]). The first report of the system (the devices
 * already connected when the watch starts) is not a new connection.
 */
class HeadsetWatcher(private val onNewHeadset: () -> Unit, private val onHeadsetGone: () -> Unit) {
    private var primed = false

    fun added(types: List<Int>) {
        if (!primed) { primed = true; return }
        if (types.any { AndroidHeadsetMic.isHeadsetWithMicrophone(it) }) onNewHeadset()
    }

    fun removed(types: List<Int>) {
        if (types.any { AndroidHeadsetMic.isHeadsetWithMicrophone(it) }) onHeadsetGone()
    }
}

/**
 * Stage 5g-22: devices once used as a car (a call input, no media): they stay cars when they get a media profile too (F0
 * in 4401: "in sco, out sco, media" and it was taken for earbuds). Only the type and the end of the address, in the prefs.
 */
object KnownCars {
    const val PREF = "aikb_known_cars"
    fun has(prefs: SharedPreferences, key: String?) = key != null && key in prefs.getStringSet(PREF, emptySet()).orEmpty()
    fun add(prefs: SharedPreferences, key: String?) {
        if (key == null || has(prefs, key)) return
        prefs.edit { putStringSet(PREF, prefs.getStringSet(PREF, emptySet()).orEmpty() + key) }
    }
}

/**
 * Stage 5g-3 (the owner's car: a hands-free kit whose microphone gave silence, and the car connects every day): a device
 * whose microphone did not give sound (its channel did not come up, or it was silent where the phone heard the speaker)
 * is remembered for good, by its type and the end of its address, and "Auto" uses the phone at once next time. The mark
 * goes away when the device once gave normal sound (only possible with "Headset" chosen by hand), with "Delete all" and
 * with the button on the voice diagnostics screen. Kept only on this phone, in the no-backup folder; nothing else.
 */
class SilentMics(private val file: java.io.File) {
    private val lock = Any()
    private var cache: MutableSet<String>? = null

    /**
     * Stage 5g-10: the lines of the file are marks ("7:AE:12") and strikes ("~7:AE:12": one failed check so far). A device
     * is marked only after [STRIKES_TO_MARK] failed checks in a row (a check can be wrong: the owner's earbuds were marked
     * after one check 128 ms after the channel came up).
     */
    private fun loaded(): MutableSet<String> = cache ?: (try {
        if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
    } catch (e: Exception) { mutableSetOf() }).also { cache = it }

    /** a failed check for [key]: returns the number of the failure (1, or 2 = now marked), 0 if there is no key */
    fun strike(key: String?): Int {
        if (key == null) return 0
        synchronized(lock) {
            val set = loaded()
            if (key in set) return STRIKES_TO_MARK
            val strikeKey = STRIKE_PREFIX + key
            val n = if (strikeKey in set) 2 else 1
            if (n >= STRIKES_TO_MARK) { set.remove(strikeKey); set.add(key) } else set.add(strikeKey)
            save(set)
            return n
        }
    }

    private fun save(set: Set<String>) {
        try {
            if (set.isEmpty()) { file.delete(); return }
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText(set.joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    fun has(key: String?) = key != null && synchronized(lock) { key in loaded() }
    /** devices marked as silent (strikes are not counted) */
    fun count() = synchronized(lock) { loaded().count { !it.startsWith(STRIKE_PREFIX) } }
    fun mark(key: String?) { if (key != null) synchronized(lock) { if (loaded().add(key)) save(loaded()) } }
    /** the device gave sound (or is proven earbuds): neither a mark nor a strike stays */
    fun unmark(key: String?) {
        if (key != null) synchronized(lock) {
            val a = loaded().remove(key)
            val b = loaded().remove(STRIKE_PREFIX + key)
            if (a || b) save(loaded())
        }
    }
    fun clear() = synchronized(lock) { loaded().clear(); save(emptySet()) }

    companion object {
        const val FILE_NAME = "voice-silent-mics.txt"
        /**
         * stage 5g-10: marks of builds before this one were set by a check that decided too early; forgotten once;
         * stage 5g-22: again once (the warm car of 4401 was marked by checks on the warm link)
         */
        const val MIGRATION_FILE = "voice-silent-mics.v3"
        const val STRIKE_PREFIX = "~"
        const val STRIKES_TO_MARK = 2

        /** forgets all marks and strikes once (the first start of this build); true if it did */
        fun migrate(dir: java.io.File, file: java.io.File): Boolean {
            val done = java.io.File(dir, MIGRATION_FILE)
            if (done.exists()) return false
            try { file.delete() } catch (_: Exception) { }
            try { dir.mkdirs(); done.writeText("1") } catch (_: Exception) { }
            return true
        }

        @Volatile private var instance: SilentMics? = null
        /** in the no-backup folder: never in a backup or a data transfer */
        fun get(context: Context): SilentMics = instance ?: synchronized(this) {
            instance ?: run {
                val dir = context.applicationContext.noBackupFilesDir
                val file = java.io.File(dir, FILE_NAME)
                migrate(dir, file)
                SilentMics(file).also { instance = it }
            }
        }
        /** "Delete all" of the AI screen */
        fun erase(context: Context) = get(context).clear()
    }
}

/**
 * The microphone of a hands-free device is switched on like for a call (stage 5g-3, the owner's car): the audio mode
 * MODE_IN_COMMUNICATION, then the device chosen for communication. Without the mode, cars and many headsets keep their
 * microphone off (5g and 5g-2 chose the device without the mode). [close] gives everything back and puts the mode back
 * to MODE_NORMAL whatever happened, so the music goes on and nothing stays "in a call". setMode needs
 * MODIFY_AUDIO_SETTINGS (checked in the Android sources). The calls to the audio system come in from outside, so this is
 * tested without a phone.
 */
class CallChannel(
    private val getMode: () -> Int,
    private val setMode: (Int) -> Unit,
    /** chooses the device for communication (setCommunicationDevice, or startBluetoothSco before Android 12) */
    private val select: () -> Boolean,
    /** clearCommunicationDevice, or stopBluetoothSco */
    private val unselect: () -> Unit,
    /**
     * stage 5g-14 (journals 4387 and 4389, car F0 "in sco, out sco": with the call mode on before its link is up the
     * HONOR phone hears nothing, CAMCORDER too, for 1.1–1.7 s; in 4365, without the mode, the link of the car came up in
     * about 1.6 s and the phone kept hearing): the device is chosen first, the mode only when the link is up ([linked])
     */
    private val modeAfterLink: Boolean = false,
    /** the chosen device is the communication device now (its link is up) */
    private val linked: () -> Boolean = { true },
) {
    private var modeSet = false
    private var selected = false
    val isOpen get() = selected

    fun open(): Boolean {
        if (selected) return true
        return try {
            if (!modeAfterLink) {
                setMode(AudioManager.MODE_IN_COMMUNICATION)
                modeSet = true
            }
            selected = select()
            if (!selected) close()
            selected
        } catch (e: Exception) { close(); false }
    }

    /** stage 5g-14: the recording of the device may start: the mode is on (switched on now, once the link is up) */
    fun ready(): Boolean {
        if (!selected) return false
        if (modeSet) return true
        if (!linked()) return false
        return try {
            setMode(AudioManager.MODE_IN_COMMUNICATION)
            modeSet = true
            true
        } catch (e: Exception) { false }
    }

    fun close() {
        if (selected) { try { unselect() } catch (_: Exception) { } }
        selected = false
        if (modeSet) {
            try { if (getMode() == AudioManager.MODE_IN_COMMUNICATION) setMode(AudioManager.MODE_NORMAL) } catch (_: Exception) { }
            modeSet = false
        }
    }
}

/**
 * The headset (or car) on the phone, switched on like for a call (stage 5g-3): MODE_IN_COMMUNICATION and then, on
 * Android 12+, [AudioManager.setCommunicationDevice] with the communication device of the same device (same type and end
 * of address); Android 9–11: [AudioManager.startBluetoothSco]. Its recording prefers its input
 * ([AudioRecord.setPreferredDevice]). [release] puts everything back, MODE_NORMAL included. BLUETOOTH_CONNECT is not
 * needed: the device lists of the audio system are read, not the Bluetooth adapter.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.P) // voice through OpenAI needs Android 9 anyway
class AndroidHeadsetMic(
    context: Context,
    private val input: AudioDeviceInfo,
    /** stage 5g-14: a car-like device (Android 12+): the device first, the call mode once its link is up */
    val modeAfterLink: Boolean = false,
) : HeadsetMic {
    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)

    /** type and end of the address: what [SilentMics] remembers */
    override val deviceKey: String = MicRouting.key(AudioDevice(input.type, input.address ?: ""))
    private var communication: AudioDeviceInfo? = null
    private var receiver: BroadcastReceiver? = null
    private val scoUp = AtomicBoolean(false)
    private val channel = CallChannel(
        getMode = { am?.mode ?: AudioManager.MODE_NORMAL },
        setMode = { am?.mode = it },
        select = { selectDevice() },
        unselect = { unselectDevice() },
        modeAfterLink = modeAfterLink && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        linked = { linkedNow() },
    )
    private var connectedAt = 0L
    /** stage 5g-14, for the journal: the link came up this long after the device was asked for, without the mode */
    @Volatile var linkMs: Long? = null
        private set

    /** like a call: MODE_IN_COMMUNICATION, then this device for communication (a car: the mode later, [ready]) */
    @Synchronized
    override fun connect(): Boolean { connectedAt = System.currentTimeMillis(); return am != null && channel.open() }

    @Synchronized
    override fun ready(): Boolean = channel.ready()

    private fun linkedNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val c = communication ?: return false
        val up = try { am?.communicationDevice?.id == c.id } catch (e: Exception) { false }
        if (up) linkMs = System.currentTimeMillis() - connectedAt
        return up
    }

    private fun selectDevice(): Boolean {
        val manager = am ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tail = AudioDevice(input.type, input.address ?: "").tail
            val out = manager.availableCommunicationDevices.firstOrNull {
                it.type == input.type && (tail == null || AudioDevice(it.type, it.address ?: "").tail == tail)
            } ?: return false
            if (!manager.setCommunicationDevice(out)) return false
            communication = out
            return true
        }
        @Suppress("DEPRECATION")
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                scoUp.set(state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
            }
        }
        @Suppress("DEPRECATION")
        ContextCompat.registerReceiver(app, r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        @Suppress("DEPRECATION")
        manager.startBluetoothSco()
        @Suppress("DEPRECATION")
        manager.isBluetoothScoOn = true
        return true
    }

    private fun unselectDevice() {
        val manager = am ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communication != null) manager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            manager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            manager.stopBluetoothSco()
        }
        communication = null
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        scoUp.set(false)
    }

    /** really up: this device is the communication device now, in the call mode (or SCO is connected) */
    override fun isUp(): Boolean {
        val manager = am ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val c = communication ?: return false
            try { manager.communicationDevice?.id == c.id && manager.mode == AudioManager.MODE_IN_COMMUNICATION } catch (e: Exception) { false }
        } else scoUp.get()
    }

    override fun downReason(): String? {
        val manager = am ?: return "no audio manager"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return if (scoUp.get()) null else "sco not connected"
        val c = communication ?: return "not chosen"
        return try {
            val now = manager.communicationDevice
            when {
                now?.id != c.id -> "communication device is " + (now?.type?.let { "type $it" } ?: "none")
                manager.mode != AudioManager.MODE_IN_COMMUNICATION -> "mode ${manager.mode}"
                else -> null
            }
        } catch (e: Exception) { "error" }
    }

    /** read without waiting: the phone records at the same time and must never wait for the headset */
    override fun source(): PcmSource = AudioRecordSource(Loudness.SOURCE_MIC, preferredDevice = input, nonBlocking = true)

    /** gives the channel back and the mode back to MODE_NORMAL: the music goes on */
    @Synchronized
    override fun release() = channel.close()

    companion object {
        /**
         * a type of input of a headset that can record: Bluetooth SCO (hands-free) or LE Audio headset; devices that
         * only play music (A2DP, LE Audio speaker) are no input at all
         */
        fun isHeadsetWithMicrophone(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

        private fun list(devices: Array<AudioDeviceInfo>?) = devices.orEmpty().map { AudioDevice(it.type, it.address ?: "") }

        /** what the routing needs to know now; reading lists changes nothing in the routing of sound */
        class Snapshot(val inputs: List<AudioDevice>, val outputs: List<AudioDevice>, val media: List<AudioDevice>?,
                       val communication: List<AudioDevice>?, private val inputInfos: List<AudioDeviceInfo>) {
            fun inputInfo(d: AudioDevice) = inputInfos.firstOrNull { it.type == d.type && (it.address ?: "") == d.address }
        }

        fun snapshot(am: AudioManager): Snapshot {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Snapshot(emptyList(), emptyList(), null, null, emptyList())
            val inputs = try { am.getDevices(AudioManager.GET_DEVICES_INPUTS) } catch (e: Exception) { emptyArray() }
            val outputs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (e: Exception) { emptyArray() }
            // Android 13+: where media plays now; before there is no way without more permissions: never proven
            val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) try {
                am.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                    .map { AudioDevice(it.type, it.address ?: "") }
            } catch (e: Exception) { null } else null
            val communication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try {
                am.availableCommunicationDevices.map { AudioDevice(it.type, it.address ?: "") }
            } catch (e: Exception) { null } else null
            return Snapshot(list(inputs), list(outputs), media, communication, inputs.toList())
        }

        /** a headset with a microphone is connected at all (for "Check microphone": a proven one only) */
        fun connected(context: Context): Boolean {
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            val s = snapshot(am)
            return MicRouting.provenHeadset(s.inputs, s.outputs, s.media) != null
        }

        /** the built-in microphone: the phone keeps recording from it while a proven headset is tried */
        fun builtInMic(context: Context): AudioDeviceInfo? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return try {
                context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            } catch (e: Exception) { null }
        }

        /** the proven headset of now as a [HeadsetMic], or null */
        fun proven(context: Context): AndroidHeadsetMic? {
            val am = context.getSystemService(AudioManager::class.java) ?: return null
            val s = snapshot(am)
            val d = MicRouting.provenHeadset(s.inputs, s.outputs, s.media) ?: return null
            return s.inputInfo(d)?.let { AndroidHeadsetMic(context, it) }
        }
    }
}
