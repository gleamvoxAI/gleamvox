// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.SharedPreferences
import helium314.keyboard.latin.voiceapi.HeadsetMic
import helium314.keyboard.latin.voiceapi.MicRouting

/**
 * Stage 5g-21 (the owner in the car F0 on 4400: one phrase from the phone is whole and good, the car at the tap loses
 * 1.5–2 s; but YouTube of the car radio no longer paused, it did only because the car took the link for a call): after
 * one phrase dictated with the phone the car's link is brought up in the background and held "warm" for [holdMs]. The
 * radio mutes its own music meanwhile (wanted). A new dictation while the link is warm records from the car at once
 * ([lease]: with the probation "the car hears" and the rollback to the phone of 5g-20); each one holds it anew.
 *
 * Let go at once: the time is over; media starts to play on the phone; the keyboard is hidden for more than [HIDDEN_MS];
 * the car goes away; a real call; the screen goes off (the last three are told from outside, [release]). Every 1 s
 * ([TICK_MS]) [mediaPlaying] and [inCall] are asked. All calls on the main thread.
 */
class CarWarmLink(
    private val later: (Long, Runnable) -> (() -> Unit),
    private val clock: () -> Long,
    private val mediaPlaying: () -> Boolean,
    private val inCall: () -> Boolean,
    /** "held N ms, released by …", for the journal of the dictation that warmed it */
    private val onReleased: (String) -> Unit = {},
) {
    private var mic: HeadsetMic? = null
    /** the device (type and end of the address) that is warm */
    var key: String? = null
        private set
    private var holdMs = 0L
    private var since = 0L
    private var inUse = false
    private var linked = false
    private var holdTimer: (() -> Unit)? = null
    private var tickTimer: (() -> Unit)? = null
    private var hideTimer: (() -> Unit)? = null
    private var linkWait = 0L

    val active get() = mic != null
    /** the link is up: a new dictation may record from the car at once */
    fun isWarm(deviceKey: String?) = mic != null && linked && deviceKey != null && deviceKey == key

    /**
     * stage 5g-22 (4401: the warm link was gone, the car had connected its media; the dictation started on it anyway and
     * failed): checked right before a dictation starts on it. null: alive; else why it is lost (it is let go then)
     */
    fun lost(): String? {
        val m = mic ?: return "none"
        val why = try { m.downReason() } catch (e: Exception) { "error" } ?: return null
        release("$REASON_LOST ($why)")
        return why
    }

    /** after a phrase from the phone: the car's link comes up in the background, then is held [holdMs] */
    fun warmUp(car: HeadsetMic, deviceKey: String?, holdMs: Long): Boolean {
        if (holdMs <= 0 || deviceKey == null) return false
        if (mic != null) release(REASON_REPLACED)
        if (!try { car.connect() } catch (e: Exception) { false }) { try { car.release() } catch (_: Exception) { }; return false }
        mic = car; key = deviceKey; this.holdMs = holdMs; since = clock(); linked = false; linkWait = 0; inUse = false
        hold()
        tick()
        return true
    }

    /** a dictation records from the warm car: the channel stays while it runs; the recorder's release keeps it */
    fun lease(): HeadsetMic? {
        val m = mic ?: return null
        inUse = true
        holdTimer?.invoke(); holdTimer = null
        return object : HeadsetMic by m {
            override fun connect() = true
            override fun release() { /* stays warm: [endUse] decides */ }
        }
    }

    /** the dictation on the warm car is over: [keep] (a text went in, the car heard) holds it again from now, else let go */
    fun endUse(keep: Boolean, reason: String = REASON_REJECTED) {
        if (!inUse) return
        inUse = false
        if (keep && mic != null) { since = clock(); hold(); tick() } else release(reason)
    }

    fun onHidden() {
        if (mic == null || hideTimer != null) return
        hideTimer = later(HIDDEN_MS, Runnable { hideTimer = null; release(REASON_HIDE) })
    }

    fun onShown() { hideTimer?.invoke(); hideTimer = null }

    /** gives the channel back now (the music of the radio comes back) */
    fun release(reason: String) {
        val m = mic ?: return
        mic = null
        holdTimer?.invoke(); tickTimer?.invoke(); hideTimer?.invoke()
        holdTimer = null; tickTimer = null; hideTimer = null
        inUse = false
        try { m.release() } catch (_: Exception) { }
        onReleased("held ${clock() - since} ms, released by $reason")
        key = null
    }

    private fun hold() {
        holdTimer?.invoke()
        holdTimer = later(holdMs, Runnable { holdTimer = null; if (!inUse) release(REASON_TIMEOUT) })
    }

    /** every second (every 100 ms while the link comes up): the link, media, a call */
    private fun tick() {
        tickTimer?.invoke()
        val m = mic ?: return
        if (!inUse) {
            if (!linked) {
                linked = try { m.ready() && m.isUp() } catch (e: Exception) { false }
                if (!linked) {
                    linkWait += LINK_TICK_MS
                    if (linkWait >= LINK_WAIT_MS) { release(REASON_NO_LINK); return }
                }
            } else if (lost() != null) return // stage 5g-22: the link went down by itself
            if (try { inCall() } catch (e: Exception) { false }) { release(REASON_CALL); return }
            if (try { mediaPlaying() } catch (e: Exception) { false }) { release(REASON_MEDIA); return }
        }
        tickTimer = later(if (linked) TICK_MS else LINK_TICK_MS, Runnable { tickTimer = null; tick() })
    }

    companion object {
        const val PREF = "aikb_car_warm_s"
        const val DEFAULT_S = 60
        val CHOICES_S = intArrayOf(30, 60, 120, 0)
        fun holdMs(prefs: SharedPreferences) = prefs.getInt(PREF, DEFAULT_S).coerceAtLeast(0) * 1000L

        const val HIDDEN_MS = 10_000L
        const val TICK_MS = 1_000L
        const val LINK_TICK_MS = 100L
        /** the link of the car must come up within this (F0: 1.5–1.6 s) */
        const val LINK_WAIT_MS = 3_000L

        const val REASON_TIMEOUT = "timeout"
        const val REASON_MEDIA = "media"
        const val REASON_HIDE = "hide"
        const val REASON_CALL = "call"
        const val REASON_SCREEN = "screen"
        const val REASON_DISCONNECT = "disconnect"
        const val REASON_REJECTED = "car silent"
        const val REASON_NO_LINK = "no link"
        const val REASON_LOST = "lost"
        const val REASON_REPLACED = "another device"

        /** warm the car after this dictation: one phrase of the phone with a car-like device (or one on the warm car), text in */
        fun shouldWarm(plan: MicRouting.CarPlan, onWarm: Boolean, inserted: Boolean, holdMs: Long) =
            holdMs > 0 && inserted && (plan == MicRouting.CarPlan.PHONE_ONLY || onWarm)
    }
}
