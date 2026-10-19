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
object CarMuteCall {
    const val PREF = "aikb_car_mute_call"
    /** on by default: used only with a car-like device (sco, no media) */
    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(PREF, true)
    /** the probe at this tap: a car-like device, its link not warm (a warm link mutes the radio already), switch on */
    fun shouldStart(carLike: Boolean, warm: Boolean, on: Boolean) = carLike && !warm && on // Android 8+: checked when placed

    private const val ACCOUNT_ID = "aikb_dictation"
    private const val NUMBER = "dictation@aikb"

    /** one call of one dictation (stage 5g-26: of the whole continuous session); times in ms from [start] */
    class Session(val startedAt: Long) {
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
            "call state sent at " + (if (activeAt >= 0) "${activeAt - startedAt} ms" else "never") +
                ", sco: " + (when (sco) { true -> "yes"; false -> "no"; null -> "?" }) +
                ", phone level during first 2 s " + (phoneDbfs?.let { "${it.toInt()} dBFS" } ?: "?") +
                ", call ended at " + (if (endedAt >= 0) "${endedAt - startedAt} ms" else "?") +
                (endedBy?.let { " by $it" } ?: "") + (if (fromTouch) ", placed at the touch" else "") +
                // stage 5g-26: what goes to OpenAI: the quiet call mode is raised before sending (Loudness.gainFor)
                (phoneDbfs?.let { db -> helium314.keyboard.latin.voiceapi.Loudness.gainFor(db).let { g ->
                    if (g > 1.0) ", sent raised by ${Math.round(20 * kotlin.math.log10(g))} dB to about ${Math.round(db + 20 * kotlin.math.log10(g))} dBFS" else "" } } ?: "")
        fun system(): String = synchronized(this) { events.joinToString("; ") }
    }

    @Volatile var session: Session? = null
        private set

    fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, AikbCallService::class.java), ACCOUNT_ID)

    /** places the call; the result comes to the [Session] (on the main thread of Telecom's binding) */
    @RequiresApi(26)
    fun start(context: Context): Session {
        val s = Session(System.currentTimeMillis())
        session = s
        val app = context.applicationContext
        val tm = app.getSystemService(TelecomManager::class.java)
        if (tm == null) { s.log("no telecom"); return s }
        try {
            val h = handle(app)
            tm.registerPhoneAccount(PhoneAccount.builder(h, "AI Keyboard")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .build())
            if (!tm.isOutgoingCallPermitted(h)) { s.log("outgoing call not permitted"); return s }
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, NUMBER, null), extras)
            s.log("placeCall")
        } catch (e: SecurityException) { s.log("SecurityException") } catch (e: Exception) { s.log("error ${e.javaClass.simpleName}") }
        return s
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
        c.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        c.setInitializing()
        c.setActive()
        s?.let { it.activeAt = System.currentTimeMillis(); it.connection = c; it.log("active") }
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
        @Deprecated("CallAudioState is deprecated on Android 14, still delivered")
        override fun onCallAudioStateChanged(state: CallAudioState?) {
            val route = state?.route ?: return
            s?.log("route ${CallAudioState.audioRouteToString(route)}")
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
            if (!pinned && endpoints.any { it.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH }) { pinned = true; moveOffBluetooth(0) }
        }

        @RequiresApi(34)
        override fun onCallEndpointChanged(endpoint: android.telecom.CallEndpoint) {
            s?.log("endpoint type ${endpoint.endpointType}")
            if (endpoint.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH) moveOffBluetooth(0)
        }

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * stage 5g-22 (4401: "move off bluetooth failed 3", ERROR_ANOTHER_REQUEST: Telecom was still busy with its own
         * route): asked again after 300 ms, at most 3 times
         */
        @RequiresApi(34)
        private fun moveOffBluetooth(tries: Int) {
            if (tries >= 3) return
            val to = endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }
                ?: endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER } ?: return
            requestCallEndpointChange(to, { it.run() }, object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) { s?.log("moved off bluetooth to type ${to.endpointType}") }
                override fun onError(error: android.telecom.CallEndpointException) {
                    s?.log("move off bluetooth failed ${error.code}")
                    if (error.code == android.telecom.CallEndpointException.ERROR_ANOTHER_REQUEST && state != STATE_DISCONNECTED)
                        handler.postDelayed({ moveOffBluetooth(tries + 1) }, 300)
                }
            })
        }

        override fun onDisconnect() { s?.log("onDisconnect"); setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); destroy() }
        override fun onAbort() { s?.log("onAbort"); setDisconnected(DisconnectCause(DisconnectCause.CANCELED)); destroy() }
        override fun onStateChanged(state: Int) { s?.log("state ${Connection.stateToString(state)}") }
    }
}
