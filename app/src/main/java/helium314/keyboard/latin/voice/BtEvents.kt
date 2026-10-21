// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs

/**
 * Stage 5g-30 (the owner: an ordinary call pauses YouTube of the car radio at once, ours after 0.5–2 s): what the phone
 * tells the car, step by step, with the time: the audio channel of the hands-free profile (SCO), the connection, the audio
 * mode, the call device, what plays. The same recorder runs for our call (into its journal) and as a "reference" during an
 * ordinary call of the owner (developer mode), so both can be compared line by line. No names, only states and types.
 */
class BtEvents(private val context: Context, private val log: (String) -> Unit) {
    private val start = System.currentTimeMillis()
    private val am = context.getSystemService(AudioManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var modeListener: Any? = null
    private var deviceListener: Any? = null
    private var playback: AudioManager.AudioPlaybackCallback? = null

    private fun ms() = System.currentTimeMillis() - start
    private fun say(s: String) = log("${ms()} ms $s")

    fun start() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> say("bt audio ${audio(i.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1))}")
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> say("bt headset ${conn(i.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1))}")
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> say("sco ${sco(i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1))}")
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
            receiver = r
        } catch (e: Exception) { say("no bluetooth events: ${e.javaClass.simpleName}") }
        val a = am ?: return
        say("start: mode ${a.mode}" + (if (Build.VERSION.SDK_INT >= 31) ", call device type ${try { a.communicationDevice?.type } catch (_: Exception) { null }}" else ""))
        if (Build.VERSION.SDK_INT >= 31) try {
            val ml = AudioManager.OnModeChangedListener { m -> say("mode $m") }
            a.addOnModeChangedListener(context.mainExecutor, ml); modeListener = ml
            val dl = AudioManager.OnCommunicationDeviceChangedListener { d -> say("call device type ${d?.type}") }
            a.addOnCommunicationDeviceChangedListener(context.mainExecutor, dl); deviceListener = dl
        } catch (e: Exception) { say("no audio listeners: ${e.javaClass.simpleName}") }
        try {
            val p = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                    say("playing on the phone: " + configs.joinToString(",") { c -> "usage ${c.audioAttributes.usage}" }.ifEmpty { "nothing" })
                }
            }
            a.registerAudioPlaybackCallback(p, Handler(Looper.getMainLooper())); playback = p
        } catch (_: Exception) { }
    }

    fun stop() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        val a = am ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { try { a.removeOnModeChangedListener(it) } catch (_: Exception) { } }
            (deviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { try { a.removeOnCommunicationDeviceChangedListener(it) } catch (_: Exception) { } }
        }
        playback?.let { try { a.unregisterAudioPlaybackCallback(it) } catch (_: Exception) { } }
        modeListener = null; deviceListener = null; playback = null
    }

    companion object {
        fun audio(s: Int) = when (s) { 10 -> "disconnected"; 11 -> "connecting"; 12 -> "connected"; else -> "state $s" }
        fun conn(s: Int) = when (s) { 0 -> "disconnected"; 1 -> "connecting"; 2 -> "connected"; 3 -> "disconnecting"; else -> "state $s" }
        fun sco(s: Int) = when (s) { 0 -> "disconnected"; 1 -> "connected"; 2 -> "connecting"; -1 -> "error"; else -> "state $s" }

        /** the reference: the owner makes an ordinary call while this records for [seconds] (developer mode) */
        const val PREF_REFERENCE = "aikb_bt_reference_log"
        const val REFERENCE_S = 60
        @Volatile private var running: BtEvents? = null
        fun referenceRunning() = running != null

        fun startReference(context: Context) {
            if (running != null) return
            val app = context.applicationContext
            val lines = ArrayList<String>()
            val rec = BtEvents(app) { synchronized(lines) { if (lines.size < 300) lines.add(it) } }
            running = rec
            rec.start()
            Handler(Looper.getMainLooper()).postDelayed({
                rec.stop(); running = null
                app.prefs().edit { putString(PREF_REFERENCE, synchronized(lines) { lines.joinToString("\n") }) }
            }, REFERENCE_S * 1000L)
        }
    }
}
