// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Audio focus while recording (stage 5g, the owner 2026-10-14): music and other sounds pause during dictation (else they
 * echo into the microphone, also into the headset microphone) and go on right after. Asked for when the recording starts
 * (AUDIOFOCUS_GAIN_TRANSIENT since 5g-20: players pause) and given back as soon as it ends:
 * after the phrase, a "Cancel", a hidden keyboard or an error. For the phone and the headset microphone alike.
 * [acquire] and [release] may be called any number of times; only a change does something. No permission needed.
 *
 * Stage 5g-5 (the owner: after a dictation the music came on that was paused before): the focus is asked for ONLY when
 * something really plays at the tap ([Api.playing]); with nothing playing it is not touched at all, so no player is
 * told it may go on.
 *
 * Stage 5g-6 (the owner: YouTube paused for the recording but did not go on afterwards; some players do not resume
 * after a transient loss of the focus): [RESUME_CHECK_MS] after the focus was given back, if something played at the tap
 * and nothing plays now, one KEYCODE_MEDIA_PLAY is sent ([Api.play]). PLAY, never PLAY_PAUSE: it can never start what was
 * paused. Nothing played at the tap: nothing is sent (the rule of 5g-5). A continuous dictation gives the focus back only
 * at its very end, so this happens once per session, never between phrases. [onResume] gets for the journal what it was.
 *
 * Stage 5g-20 (the owner: the first phrase of a YouTube video came into the recording, the journal said "music before:
 * no"): "something plays" is also any started player for media or games ([Api.playingConfigs]), not only
 * isMusicActive; the focus (AUDIOFOCUS_GAIN_TRANSIENT) is asked for at the tap, before the recording starts.
 */
class RecordingFocus(private val api: Api, var onResume: ((String) -> Unit)? = null) {
    interface Api {
        /** true if the system gave the focus */
        fun request(): Boolean
        fun abandon()
        /** something plays sound now (AudioManager.isMusicActive, or a started player of media or games) */
        fun playing(): Boolean = true
        /** stage 5g-20: started players of media or games now, for the journal */
        fun playingConfigs(): Int = 0
        /** one media key PLAY to the player that has the media session (stage 5g-6) */
        fun play() {}
        /** runs [action] on the main thread after [delayMs] */
        fun after(delayMs: Long, action: () -> Unit) = action()
    }

    var held = false
        private set
    /** this recording was decided already (playing or not), until [release] */
    private var decided = false
    /** for the journal: something played at the tap, and the focus was taken */
    var wasPlaying: Boolean? = null
        private set
    /** stage 5g-20: the started players of media or games at the tap (for the journal) */
    var configs = 0
        private set

    /** a new recording makes a pending resume check stale: PLAY must never come into a recording */
    private var generation = 0

    fun acquire() {
        if (held || decided) return
        decided = true
        generation++
        val playing = api.playing()
        wasPlaying = playing
        configs = try { api.playingConfigs() } catch (e: Exception) { 0 }
        if (!playing) return // nothing plays: nothing to pause, and nothing must come on afterwards
        held = true // even if refused (a call): abandon() is harmless then
        api.request()
    }

    fun release() {
        decided = false
        if (!held) return
        held = false
        api.abandon()
        val g = generation
        api.after(RESUME_CHECK_MS) {
            if (g != generation || held) return@after // the next recording began meanwhile
            if (api.playing()) onResume?.invoke(RESUME_ITSELF)
            else { api.play(); onResume?.invoke(RESUME_SENT) }
        }
    }

    companion object {
        /** the player gets so long to go on by itself after the focus came back */
        const val RESUME_CHECK_MS = 800L
        const val RESUME_ITSELF = "player resumed itself"
        const val RESUME_SENT = "sent play"
        const val RESUME_NOT_NEEDED = "not needed"
    }
}

/** stage 5g-20: "state:started" in the description of a player (AudioPlaybackConfiguration.toString) */
fun startedState(description: String) = Regex("""\bstate:\s*started\b""").containsMatchIn(description)

/** the audio focus of the phone; a request lives from [request] to [abandon] */
class AndroidRecordingFocus(context: Context) : RecordingFocus.Api {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { } // the keyboard plays nothing: nothing to do on a loss
    private var request: AudioFocusRequest? = null

    override fun playing(): Boolean {
        val music = try { am?.isMusicActive == true } catch (e: Exception) { false }
        return music || playingConfigs() > 0
    }

    /**
     * stage 5g-20: the players of media and games that play now. The state of a player is not public API, but its
     * description says it ("state:started"; paused and stopped players are in the list too and must not count: PLAY after
     * the recording would start them)
     */
    override fun playingConfigs(): Int = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) 0
        else am?.activePlaybackConfigurations.orEmpty().count { c ->
            val usage = c.audioAttributes.usage
            (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME || usage == AudioAttributes.USAGE_UNKNOWN) &&
                startedState(c.toString())
        }
    } catch (e: Exception) { 0 }

    private val handler = Handler(Looper.getMainLooper())
    override fun after(delayMs: Long, action: () -> Unit) { handler.postDelayed(action, delayMs) }

    /** down and up of KEYCODE_MEDIA_PLAY through the audio manager: no permission needed */
    override fun play() {
        val manager = am ?: return
        try {
            val t = SystemClock.uptimeMillis()
            manager.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        } catch (_: Exception) { }
    }

    override fun request(): Boolean {
        val manager = am ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                request = r
                manager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) { false }
    }

    override fun abandon() {
        val manager = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) request?.let { manager.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") manager.abandonAudioFocus(listener)
        } catch (_: Exception) { }
        request = null
    }
}
