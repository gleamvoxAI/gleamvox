// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.utils.prefs

/**
 * Stage 5g-21, owner's addition 2 (a probe, personal only): the car radio F0 mutes its own music for a call, so far only
 * as a side effect of the SCO link, and the HONOR phone goes deaf while that link comes up. Hypothesis: the radio mutes on
 * the CALL STATE of hands-free (call / callsetup), not on the SCO sound. So at the tap of the microphone a self-managed
 * call of the Telecom framework is started (MANAGE_OWN_CALLS, own PhoneAccount with CAPABILITY_SELF_MANAGED); its sound
 * goes to the speaker or the earpiece, never Bluetooth (EXTRA_START_CALL_WITH_SPEAKERPHONE, and a move away when Telecom
 * puts it on Bluetooth); no SCO is asked for by the keyboard. The phone records with its MIC at once. The call ends with
 * the phrase. Android's documentation: self-managed calls are visible and controllable from Bluetooth devices such as car
 * head units (developer.android.com, Telecom / ConnectionService). Whether the radio mutes, and whether Android brings up
 * SCO by itself for the call, only the journal in the car can tell.
 *
 * All official APIs; the state of the call and of the route go to [Session.events] (for the journal, names only).
 */
/**
 * Stage 5g-27 (4449 in the car F0: the "call on the phone" let the radio take its music back after 2–4 s, mode 0): how
 * the music of the radio is paused while dictating, chosen in the developer mode and on the voice panel. The journal
 * says the mode and what happened.
 */
enum class CarMuteMode(val key: String) {
    /** a self-managed call whose sound stays on Bluetooth, as the system wants it: the radio holds its music (default) */
    CALL_VIA_CAR("call_car"),
    /** the voice command of the hands-free profile (BVRA, as the Google Assistant does): many radios pause for it */
    VOICE_COMMAND("voice_command"),
    /** the call of 5g-21..26 with its sound on the earpiece (for comparison) */
    CALL_ON_PHONE("call_phone"),
    /** nothing is done with the radio */
    OFF("off");

    /** the car's channel comes up: the phone records with its built-in microphone (CAMCORDER stays on it in every mode) */
    val builtInMic get() = this == CALL_VIA_CAR || this == VOICE_COMMAND

    companion object {
        /** stage 5g-28: a new key: the owner chose the call on the phone after the trial of 4452 (the old choice is not kept) */
        const val PREF = "aikb_car_mute_mode2"
        /** stage 5g-28: what can be chosen; the voice command (no pause, the radio took YouTube into the voice channel) only in the code */
        val choices = listOf(CALL_ON_PHONE, CALL_VIA_CAR, OFF)
        fun read(prefs: SharedPreferences): CarMuteMode {
            prefs.getString(PREF, null)?.let { k -> entries.firstOrNull { it.key == k } }?.let { return it }
            // the switch of 5g-21: off stays off
            return if (prefs.getBoolean(CarMuteCall.PREF, true)) CALL_ON_PHONE else OFF
        }
        fun write(prefs: SharedPreferences, mode: CarMuteMode) { prefs.edit().putString(PREF, mode.key).apply() }
        fun next(mode: CarMuteMode) = choices[(choices.indexOf(mode) + 1) % choices.size]
    }
}

/**
 * Stage 5g-30: what the call tells the car: like an ordinary call ("dialing" first, the car may pause at once; default) or
 * "active" at once (as in 4452–4457). Developer mode, for comparison.
 */
enum class CallSignal(val key: String) {
    LIKE_ORDINARY("dialing_then_active"), ACTIVE_AT_ONCE("active_at_once");
    companion object {
        const val PREF = "aikb_car_call_signal"
        fun read(prefs: SharedPreferences) = prefs.getString(PREF, null)?.let { k -> entries.firstOrNull { it.key == k } } ?: LIKE_ORDINARY
    }
}

object CarMuteCall {
    const val PREF = "aikb_car_mute_call"
    /** stage 5g-27: on unless the mode is "no muting" (the old switch off is that mode) */
    fun enabled(prefs: SharedPreferences) = CarMuteMode.read(prefs) != CarMuteMode.OFF
    /** the probe at this tap: a car-like device, its link not warm (a warm link mutes the radio already), switch on */
    fun shouldStart(carLike: Boolean, warm: Boolean, on: Boolean) = carLike && !warm && on // Android 8+: checked when placed

    private const val ACCOUNT_ID = "aikb_dictation"
    private const val NUMBER = "dictation@aikb"

    /** one call of one dictation (stage 5g-26: of the whole continuous session); times in ms from [start] */
    class Session(val startedAt: Long, val mode: CarMuteMode = CarMuteMode.CALL_ON_PHONE, val signal: CallSignal = CallSignal.LIKE_ORDINARY) {
        /** stage 5g-27: the voice command is stopped with this (null: not a voice command, or not started) */
        @Volatile var stopVoice: (() -> Unit)? = null
        /** stage 5g-27: the audio mode went back to normal while the dictation still ran (the radio may play again), ms */
        @Volatile var brokeAt = -1L
        /** stage 5g-28: when the call was asked for (placeCall), reached our service, and was dialing (wall clock; -1: not) */
        @Volatile var placedAt = -1L
        @Volatile var serviceAt = -1L
        @Volatile var dialingAt = -1L
        /** stage 5g-28: why the call became active (5g-29: always "at once") */
        @Volatile var activeBy: String? = null
        /** stage 5g-29: how often the system refused to move the call off Bluetooth */
        @Volatile var routeFailures = 0
        /** stage 5g-28: when the phone went into the call mode (MODE_IN_COMMUNICATION/IN_CALL), wall clock; -1: not yet */
        @Volatile var inCallAt = -1L
        /** stage 5g-28: the fast path at the touch (the call device set to the earpiece and the call mode by us), for the journal */
        @Volatile var fastPath: String? = null
        /** stage 5g-26: placed at the touch of the microphone (before the tap) */
        var fromTouch = false
        /** stage 5g-26: what ended it ("phrase end", "cancel", "hidden", "watchdog" …) */
        var endedBy: String? = null
        val events = mutableListOf<String>()
        var activeAt = -1L
        var endedAt = -1L
        var sco: Boolean? = null
        @Volatile var connection: Connection? = null
        @Volatile var endRequested = false
        @Synchronized fun log(e: String) { if (events.size < 40) events.add("${System.currentTimeMillis() - startedAt} ms $e") }
        /** "car mute: call state sent at N ms, sco: no, phone level during first 2 s X dBFS, call ended at N ms" */
        fun summary(phoneDbfs: Double?): String =
            (if (inCallAt >= 0) "touch to call mode ${inCallAt - startedAt} ms (${if (inCallAt - startedAt <= FAST_MS) "fast" else "slow"}), " else "call mode never seen, ") +
            (fastPath?.let { "fast path: $it, " } ?: "") +
            // stage 5g-29: the pause of the radio follows "active": fast within 200 ms of the touch
            (if (activeAt >= 0) "touch to active ${activeAt - startedAt} ms (${if (activeAt - startedAt <= ACTIVE_FAST_MS) "fast" else "slow"}), " else "") +
            "route refusals $routeFailures, " +
            "call state sent at " + (if (activeAt >= 0) "${activeAt - startedAt} ms" else "never") + steps() +
                ", sco: " + (when (sco) { true -> "yes"; false -> "no"; null -> "?" }) +
                ", phone level during first 2 s " + (phoneDbfs?.let { "${it.toInt()} dBFS" } ?: "?") +
                ", call ended at " + (if (endedAt >= 0) "${endedAt - startedAt} ms" else "?") +
                ", mode ${mode.key}, signal ${signal.key}, pause " + (if (brokeAt >= 0) "may have broken at $brokeAt ms (audio mode normal)" else "held to the end") +
                (endedBy?.let { " by $it" } ?: "") + (if (fromTouch) ", placed at the touch" else "") +
                // stage 5g-26: what goes to OpenAI: the quiet call mode is raised before sending (Loudness.gainFor)
                (phoneDbfs?.let { db -> helium314.keyboard.latin.voiceapi.Loudness.gainFor(db).let { g ->
                    if (g > 1.0) ", sent raised by ${Math.round(20 * kotlin.math.log10(g))} dB to about ${Math.round(db + 20 * kotlin.math.log10(g))} dBFS" else "" } } ?: "")
        fun system(): String = synchronized(this) { events.joinToString("; ") }
        /** stage 5g-28: " (placeCall N ms, service N ms, dialing N ms, active by …)": where our part of the delay goes */
        private fun steps(): String {
            if (placedAt < 0) return ""
            val parts = listOfNotNull("placeCall ${placedAt - startedAt} ms",
                serviceAt.takeIf { it >= 0 }?.let { "service ${it - startedAt} ms" },
                dialingAt.takeIf { it >= 0 }?.let { "dialing ${it - startedAt} ms" },
                activeBy?.let { "active by $it" })
            return " (" + parts.joinToString(", ") + ")"
        }
    }

    @Volatile var session: Session? = null
        private set

    /** stage 5g-28: the call mode this soon after the touch is "fast" (the owner: the pause ~0.5 s); slower is "slow" (~1.5 s) */
    const val FAST_MS = 400L
    /** stage 5g-29: "active" this soon after the touch is "fast" */
    const val ACTIVE_FAST_MS = 200L

    fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, AikbCallService::class.java), ACCOUNT_ID)

    /**
     * places the call (stage 5g-27: or starts the voice command) in [mode]; the result comes to the [Session]. [carTail]:
     * the end of the address of the car (the voice command goes to that device)
     */
    @RequiresApi(26)
    fun start(context: Context, mode: CarMuteMode = CarMuteMode.CALL_ON_PHONE, carTail: String? = null): Session {
        val s = Session(System.currentTimeMillis(), mode, CallSignal.read(context.prefs()))
        session = s
        val app = context.applicationContext
        if (mode == CarMuteMode.VOICE_COMMAND) { startVoiceCommand(app, s, carTail); return s }
        placeCall(app, s)
        return s
    }

    @Volatile private var registered = false

    /**
     * stage 5g-28: the phone account of the keyboard is registered ahead (when the keyboard shows a field with a car
     * there), not at the tap: one step less between the touch and the call
     */
    @RequiresApi(26)
    fun prepare(context: Context) {
        if (registered) return
        val app = context.applicationContext
        try {
            app.getSystemService(TelecomManager::class.java)?.registerPhoneAccount(PhoneAccount.builder(handle(app), "AI Keyboard")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                // stage 5g-30: the call is given to the in-call services that take self-managed calls (Bluetooth among
                // them), so the car hears of it as of an ordinary call
                .setExtras(android.os.Bundle().apply {
                    if (Build.VERSION.SDK_INT >= 31) putBoolean(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE, true)
                })
                .build()) ?: return
            registered = true
        } catch (_: Exception) { }
    }

    @RequiresApi(26)
    private fun placeCall(app: Context, s: Session) {
        val tm = app.getSystemService(TelecomManager::class.java)
        if (tm == null) { s.log("no telecom"); return }
        try {
            val h = handle(app)
            prepare(app)
            if (!tm.isOutgoingCallPermitted(h)) { s.log("outgoing call not permitted"); return }
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
                // the call on the phone starts on the speaker; the call via the car as the system wants it (Bluetooth)
                if (s.mode == CarMuteMode.CALL_ON_PHONE) putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            s.placedAt = System.currentTimeMillis()
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, NUMBER, null), extras)
            s.log("placeCall")
        } catch (e: SecurityException) { s.log("SecurityException") } catch (e: Exception) { s.log("error ${e.javaClass.simpleName}") }
    }

    /**
     * stage 5g-27: the voice command of the hands-free profile (BluetoothHeadset.startVoiceRecognition, the AT command BVRA):
     * needs "Nearby devices" (asked once, 5g-25); without it the call via the car is used, and the journal says so
     */
    @android.annotation.SuppressLint("MissingPermission")
    @RequiresApi(26)
    private fun startVoiceCommand(app: Context, s: Session, carTail: String?) {
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            s.log("voice command: no Bluetooth permission, call via the car instead")
            placeCall(app, s); return
        }
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null) { s.log("voice command: no Bluetooth adapter"); return }
        try {
            val asked = adapter.getProfileProxy(app, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    val hs = proxy as android.bluetooth.BluetoothHeadset
                    fun close() { try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.HEADSET, hs) } catch (_: Exception) { } }
                    try {
                        val devices = hs.connectedDevices
                        val dev = devices.firstOrNull { d -> carTail != null && d.address?.endsWith(carTail, ignoreCase = true) == true }
                            ?: devices.firstOrNull()
                        if (dev == null) { s.log("voice command: no hands-free device"); close(); return }
                        if (s.endRequested) { s.log("voice command: ended before it started"); close(); return }
                        val ok = hs.startVoiceRecognition(dev)
                        s.log("voice command started: $ok")
                        if (ok) s.activeAt = System.currentTimeMillis()
                        s.stopVoice = {
                            try { s.log("voice command stopped: " + hs.stopVoiceRecognition(dev)) } catch (e: Exception) { s.log("voice command stop: ${e.javaClass.simpleName}") }
                            close()
                        }
                        if (s.endRequested) { s.stopVoice?.invoke(); s.stopVoice = null }
                    } catch (e: Exception) { s.log("voice command: ${e.javaClass.simpleName}"); close() }
                }
                override fun onServiceDisconnected(profile: Int) { s.log("voice command: headset service gone") }
            }, android.bluetooth.BluetoothProfile.HEADSET)
            s.log("voice command asked: $asked")
        } catch (e: Exception) { s.log("voice command: ${e.javaClass.simpleName}") }
    }

    /**
     * the phrase is over: the call ends (also if Telecom did not create it yet: it ends at once then). Stage 5g-26: once
     * per call; [by] goes to the journal ("call ended by …")
     */
    fun end(by: String = "phrase end") {
        val s = session ?: return
        if (!s.endRequested) { s.endedBy = by; s.log("call ended by $by") }
        s.endRequested = true
        if (s.endedAt < 0) s.endedAt = System.currentTimeMillis()
        s.stopVoice?.let { it(); s.stopVoice = null } // stage 5g-27: the voice command
        val c = s.connection ?: return
        try { c.setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); c.destroy() } catch (_: Exception) { }
        s.connection = null
        s.log("disconnected")
    }

    fun clear() { session = null }
}

/** the ConnectionService of the probe: declared only in the personal variant (src/aikbPersonal/AndroidManifest.xml) */
@RequiresApi(26)
class AikbCallService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val s = CarMuteCall.session
        val c = MuteConnection(s)
        c.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        c.audioModeIsVoip = true
        c.setCallerDisplayName("AI Keyboard", TelecomManager.PRESENTATION_ALLOWED)
        // stage 5g-29 (4453: the radio showed "a call WITH A NUMBER"): no address, only the name, as an icon of a call
        c.setAddress(null, TelecomManager.PRESENTATION_RESTRICTED)
        c.setInitializing()
        s?.serviceAt = System.currentTimeMillis()
        // stage 5g-29 (4453: the "dialing" of 5g-28 made the pause ALWAYS slow, with a click of the radio: the radio got
        // ready for an outgoing call but held its music until "active", and "active" waited for the route): active at once,
        // as in 4452; the route to the phone is asked for alongside, it never holds "active" back.
        // Stage 5g-30 (an ordinary call pauses the radio at once): like an ordinary call: "dialing" (the car is told of an
        // outgoing call at once), then "active" after a fixed [DIALING_MS], never waiting for the route
        if (s?.signal == CallSignal.LIKE_ORDINARY) {
            c.setDialing()
            s?.dialingAt = System.currentTimeMillis(); s?.connection = c; s?.log("dialing")
            c.activeAfter(DIALING_MS)
        } else {
            c.setActive()
            s?.let { it.activeAt = System.currentTimeMillis(); it.activeBy = "at once"; it.connection = c; it.log("active") }
        }
        if (s == null || s.endRequested) {
            s?.log("ended before it was up")
            c.setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); c.destroy()
            s?.connection = null
        }
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        CarMuteCall.session?.log("outgoing connection failed")
    }

    private class MuteConnection(val s: CarMuteCall.Session?) : Connection() {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /** stage 5g-30: "active" after [ms] of "dialing" */
        fun activeAfter(ms: Long) {
            handler.postDelayed({
                if (state == STATE_DIALING) {
                    setActive()
                    s?.let { it.activeAt = System.currentTimeMillis(); it.activeBy = "after dialing $ms ms"; it.log("active") }
                }
            }, ms)
        }

        @Deprecated("CallAudioState is deprecated on Android 14, still delivered")
        override fun onCallAudioStateChanged(state: CallAudioState?) {
            val route = state?.route ?: return
            s?.log("route ${CallAudioState.audioRouteToString(route)}")
            if (s?.mode != CarMuteMode.CALL_ON_PHONE) return
            // never through Bluetooth: that would bring up SCO and make the phone deaf
            if (route == CallAudioState.ROUTE_BLUETOOTH && Build.VERSION.SDK_INT < 34) {
                @Suppress("DEPRECATION")
                setAudioRoute(if (state.supportedRouteMask and CallAudioState.ROUTE_EARPIECE != 0) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                s?.log("moved off bluetooth")
            }
        }

        private var endpoints: List<android.telecom.CallEndpoint> = emptyList()

        /** stage 5g-26: the route was pinned to the phone once (before Telecom moves the call to Bluetooth by itself) */
        private var pinned = false

        @RequiresApi(34)
        override fun onAvailableCallEndpointsChanged(available: MutableList<android.telecom.CallEndpoint>) {
            endpoints = available.toList()
            // stage 5g-26 (4436: EARPIECE -> BLUETOOTH -> EARPIECE in 300-800 ms, the phone deaf for ~400 ms each time):
            // the phone's route is asked for as soon as the routes are known, before Telecom picks Bluetooth itself
            // stage 5g-27: only the call on the phone; the call via the car stays on Bluetooth
            if (s?.mode != CarMuteMode.CALL_ON_PHONE) return
            if (!pinned && endpoints.any { it.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH }) { pinned = true; moveOffBluetooth(0) }
        }

        @RequiresApi(34)
        override fun onCallEndpointChanged(endpoint: android.telecom.CallEndpoint) {
            s?.log("endpoint type ${endpoint.endpointType}")
            if (s?.mode != CarMuteMode.CALL_ON_PHONE) return
            if (endpoint.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH) moveOffBluetooth(0)
        }

        /**
         * stage 5g-22 (4401: "move off bluetooth failed 3", ERROR_ANOTHER_REQUEST: Telecom was still busy with its own
         * route): asked again. Stage 5g-29 (4453: the more refusals, the later everything): less fighting over the route:
         * at most [MOVE_TRIES] requests one after another, then nothing until [MOVE_PAUSE_MS] after the call became active,
         * then one more request
         */
        private var calmUntil = 0L
        private var lateTry = false

        @RequiresApi(34)
        private fun moveOffBluetooth(tries: Int) {
            val now = System.currentTimeMillis()
            if (now < calmUntil) return
            if (tries >= MOVE_TRIES) {
                s?.log("route: $MOVE_TRIES refusals, quiet until ${MOVE_PAUSE_MS} ms after active")
                val since = s?.activeAt?.takeIf { it > 0 } ?: now
                calmUntil = since + MOVE_PAUSE_MS
                if (!lateTry) { lateTry = true; handler.postDelayed({ if (state != STATE_DISCONNECTED) moveOffBluetooth(0) }, (calmUntil - now).coerceAtLeast(0)) }
                return
            }
            val to = endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }
                ?: endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER } ?: return
            requestCallEndpointChange(to, { it.run() }, object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) { s?.log("moved off bluetooth to type ${to.endpointType}") }
                override fun onError(error: android.telecom.CallEndpointException) {
                    s?.log("move off bluetooth failed ${error.code}")
                    s?.let { it.routeFailures++ }
                    if (error.code == android.telecom.CallEndpointException.ERROR_ANOTHER_REQUEST && state != STATE_DISCONNECTED)
                        handler.postDelayed({ moveOffBluetooth(tries + 1) }, MOVE_RETRY_MS)
                }
            })
        }

        override fun onDisconnect() { handler.removeCallbacksAndMessages(null); s?.log("onDisconnect"); setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); destroy() }
        override fun onAbort() { s?.log("onAbort"); setDisconnected(DisconnectCause(DisconnectCause.CANCELED)); destroy() }
        override fun onStateChanged(state: Int) { s?.log("state ${Connection.stateToString(state)}") }
    }

    companion object {
        /** stage 5g-29: the route to the phone: at most 3 requests 150 ms apart, then one more 500 ms after "active" */
        const val MOVE_RETRY_MS = 150L
        const val MOVE_TRIES = 3
        const val MOVE_PAUSE_MS = 500L
        /** stage 5g-30: "dialing" this long before "active" when the call is like an ordinary one */
        const val DIALING_MS = 150L
    }
}
