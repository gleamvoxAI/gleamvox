// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import android.app.KeyguardManager
import helium314.keyboard.latin.ai.AiAssist
import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiTint
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.CleanupLevel
import helium314.keyboard.latin.ai.CleanupUi
import helium314.keyboard.latin.ai.DictationCleaner
import helium314.keyboard.latin.ai.ImeFieldAccess
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.OpenAiTextProvider
import helium314.keyboard.latin.voiceapi.AudioRecordSource
import helium314.keyboard.latin.voiceapi.ContinuousSpeechEngine
import helium314.keyboard.latin.voiceapi.AndroidHeadsetMic
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.SilentMics
import helium314.keyboard.latin.voiceapi.HeadsetWatcher
import helium314.keyboard.latin.voiceapi.MicRouting
import helium314.keyboard.latin.voiceapi.KnownCars
import helium314.keyboard.latin.voiceapi.CarDetect
import helium314.keyboard.latin.voiceapi.NotCars
import helium314.keyboard.latin.voiceapi.AudioDevice
import helium314.keyboard.latin.voiceapi.PreCapture
import helium314.keyboard.latin.voiceapi.HeadsetMic
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.ChunkStore
import helium314.keyboard.latin.voiceapi.PhoneProbe
import helium314.keyboard.latin.voiceapi.SileroVad
import kotlin.math.roundToInt
import helium314.keyboard.latin.voiceapi.SpeechProbability
import helium314.keyboard.latin.voiceapi.MicChoice
import helium314.keyboard.latin.voiceapi.MicDecision
import helium314.keyboard.latin.voiceapi.FallbackSpeechEngine
import helium314.keyboard.latin.voiceapi.OpenAiSpeechEngine
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiBalance
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.latin.voiceapi.VoiceEngineChoice
import java.util.Locale

/** Connects [VoiceInputController] with the keyboard service: panel, permission, inserting text, stop triggers. */
class VoiceInput @JvmOverloads constructor(
    private val ime: LatinIME,
    /** stage 5g: the audio focus of the phone; a fake one in tests */
    private val focusApi: RecordingFocus.Api = AndroidRecordingFocus(ime),
    private val recognizerAvailable: (Context) -> Boolean = { AndroidSpeechEngine.isAvailable(it) },
) : VoiceHost, VoiceInputView.Actions {
    enum class MicAction { INTERNAL, FALLBACK, NONE }

    private val handler = Handler(Looper.getMainLooper())
    val controller = VoiceInputController(
        { createEngine() },
        this,
        { delayMs, action ->
            val r = Runnable(action)
            handler.postDelayed(r, delayMs)
            Cancellable { handler.removeCallbacks(r) }
        },
        { Log.i(TAG, it) } // only states and error codes, never text
    )
    private var panel: VoiceInputView? = null
    private val autoStart = PermissionAutoStart()
    private var autoStartRunnable: Runnable? = null
    private var lastField: String? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    /** this dictation goes to OpenAI (docs/VOICE_API.md); the reason line of the panel */
    private var useOpenAi = false
    private var notice: VoiceApiProblem? = null
    private var noticeSwitched = false
    private var signalPeak: Double? = null
    private var signalSource: String? = null
    /** the field the dictation started in */
    private var sessionField: String? = null
    /** stage 5zh: the screen went off or the keyboard closed while recording: finished, sent, inserted later */
    private val interruption = VoiceInterruption()
    /** stage 5zh: the steps of the current dictation for the voice journal (no text, no sound) */
    private var attempt: VoiceAttempt? = null
    private val journal by lazy { VoiceJournal.get(ime) }
    /** stage 5g: players pause while recording (both microphones) and go on right after */
    val focus = RecordingFocus(focusApi) { what ->
        resumeAttempt?.let { a -> a.resume = what; if (a !== attempt) journal.changed() }
        resumeAttempt = null
    }
    /** stage 5g-6: the dictation whose music is checked after the end */
    private var resumeAttempt: VoiceAttempt? = null
    /** stage 5g: a long press on the microphone asks for continuous dictation once */
    private var continuousRequested = false
    /** this dictation is continuous (OpenAI); its engine, for "Retry" of failed phrases */
    private var continuousOn = false
    private var continuousEngine: ContinuousSpeechEngine? = null
    /** a long press asked for continuous dictation but this one goes through the system recognizer */
    private var continuousUnavailable = false
    private var continuousWords = 0
    private var continuousFailed = 0
    /** stage 5g: the microphone of this dictation (null: not known, the system recognizer) */
    private var micHeadset: Boolean? = null
    private var micFellBack = false
    /** "Headset" chosen in the settings, but none is connected */
    private var micMissing = false

    /** stage 5g-20: delays on the main thread; the returned function cancels */
    private val later: (Long, Runnable) -> (() -> Unit) = { ms, r -> handler.postDelayed(r, ms); { handler.removeCallbacks(r) } }
    /** stage 5g-20: the screen stays on while recording and 5 s after the text went in */
    private val screen = ScreenKeeper({ on -> keepScreenOn(on) }, later)
    /** stage 5g-20: pieces waiting for the network, also in the cache folder; files left from before are deleted */
    private val chunkStore by lazy { ChunkStore.of(ime.cacheDir).also { it.clear() } }
    /** stage 5g-20: the engine of a single dictation through OpenAI ("Retry", the network back) */
    private var singleEngine: OpenAiSpeechEngine? = null
    /** stage 5g-20: where the next text of this dictation goes (the end of what it inserted), -1: at the cursor */
    private var nextPos = -1
    /** stage 5g-20: the dictation's texts copied to the clipboard because its field was gone */
    private val clipped = StringBuilder()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * stage 5g-21: after one phrase from the phone the car's link is held warm (the radio mutes its music), and the next
     * dictation records from the car at once
     */
    private val warm = CarWarmLink(later, System::currentTimeMillis,
        mediaPlaying = { focusApi.playing() },
        inCall = { ime.getSystemService(android.media.AudioManager::class.java)?.mode.let { it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_RINGTONE } },
        onReleased = { line -> warmAttempt?.let { a -> a.car = (a.car ?: "") + "; " + line; journal.changed() }; warmAttempt = null },
    )
    /** the dictation whose entry in the journal tells about the warm link */
    private var warmAttempt: VoiceAttempt? = null
    /** stage 5g-21: this dictation records from the warm car; the plan of the car of this dictation; the car to warm */
    private var onWarm = false
    private var carPlan = MicRouting.CarPlan.NONE
    private var warmCandidate: Pair<android.media.AudioDeviceInfo, String>? = null

    /** the cleanup of a dictated phrase (docs/DESIGN.md, stage 5d), for OpenAI and for the system recognizer */
    private val cleaner = DictationCleaner(
        provider = { if (AiAssist.inBuild()) OpenAiTextProvider.get(ime) else null },
        field = ImeFieldAccess(ime),
        mayRun = { cleanupMayRun() },
        level = { CleanupLevel.read(ime.prefs()) },
        ui = StripCleanupUi(),
        post = { handler.post(it) },
        postDelayed = { r, ms -> handler.postDelayed(r, ms) },
        background = { cleanupWorker.execute(it) },
    )

    // ---- stage 5g-24: the microphone opens at the touch of the key ----
    private var pre: PreCapture? = null
    private var preSourceType = -1
    private var preAudio: AudioRecordSource? = null
    private val preGiveUp = Runnable { discardPre("no tap") }

    /**
     * The touch of the microphone key (before its release, the tap): the phone's microphone opens now and keeps the sound
     * in memory, for the OpenAI recording only, where it would start (permission, screen on, not a sensitive field, no
     * dictation running). The tap or the long press takes the sound over; otherwise it is wiped.
     */
    fun onMicDown(editorInfo: EditorInfo?) {
        if (pre != null || controller.isActive || interruption.isRunning || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        if (SensitiveInput.isSensitive(editorInfo) || !Settings.getValues().mVoiceInputInternal || !hasPermission() || !screenIsOn()) return
        // stage 5g-26: a known car: the "quiet call" that pauses its music is placed at the touch already (the radio needs
        // 0.5-1 s to react, and the route of the call settles before the words begin); it ends if no tap follows
        if (muteSession?.endRequested != false && android.os.Build.VERSION.SDK_INT >= 26 && CarMuteCall.enabled(ime.prefs()) && knownCarConnected()) {
            startMuteCall(fromTouch = true)
            muteTouchPending = true
            handler.postDelayed(muteGiveUp, PreCapture.GIVE_UP_MS)
        }
        if (!(VoiceApiPrefs.isAvailable() && engineChoice(editorInfo).openAi)) return
        val prefs = ime.prefs()
        preSourceType = VoiceApiPrefs.audioSource(prefs)
        val a = AudioRecordSource(preSourceType, nonBlocking = true, effects = true)
        preAudio = a
        pre = PreCapture(a)
        handler.postDelayed(preGiveUp, PreCapture.GIVE_UP_MS)
    }

    /** the touch was cancelled or slid away from the key */
    fun onMicTouchCancelled() {
        discardPre("cancelled")
        if (muteTouchPending && !controller.isActive) endMuteCall("touch cancelled")
    }

    /** stage 5g-26: the call placed at the touch waits for the tap; no tap within 2 s: it ends */
    private var muteTouchPending = false
    private val muteGiveUp = Runnable { if (muteTouchPending && !controller.isActive) endMuteCall("no tap") }

    /**
     * stage 5g-26: a Bluetooth device with a call input that is known to be a car (its class says so, or it once gave
     * speech as a car), and not marked "not a car": the quiet call may be placed at the touch
     */
    private fun knownCarConnected(): Boolean = try {
        val prefs = ime.prefs()
        ime.getSystemService(android.media.AudioManager::class.java)?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).orEmpty().any { d ->
            if (!AndroidHeadsetMic.isHeadsetWithMicrophone(d.type)) return@any false
            val dev = helium314.keyboard.latin.voiceapi.AudioDevice(d.type, d.address ?: "")
            val key = MicRouting.key(dev)
            !NotCars.has(prefs, key) && (KnownCars.has(prefs, key) ||
                CarDetect.info(ime, dev.tail, d.productName).let { CarDetect.kind(it.major, it.deviceClass) } == CarDetect.Kind.CAR)
        }
    } catch (e: Exception) { false }

    private fun discardPre(why: String) {
        handler.removeCallbacks(preGiveUp)
        val p = pre ?: return
        pre = null; preAudio = null
        Log.i(TAG, "microphone opened at the touch: given up ($why)")
        p.discard()
    }

    /** the recording opens the phone: the sound of the touch if it is the same source, else a new one */
    private fun takePre(source: Int, plain: Boolean): PcmSource? {
        handler.removeCallbacks(preGiveUp)
        val p = pre ?: return null
        val audio = preAudio
        pre = null; preAudio = null
        if (source != preSourceType || !plain || audio == null) { p.discard(); return null }
        val primed = p.adopt() ?: return null
        val a = attempt
        if (a != null) {
            val tapAfter = a.at - p.touchedAt
            a.startInfo = "touch ${tapAfter} ms before the tap, ${primed.primedMs} ms of sound from it; microphone opened in ${p.openMs} ms (" +
                (audio.timing ?: "?") + "), first sound ${if (p.firstFrameAt > 0) p.firstFrameAt - p.touchedAt else -1} ms after the touch; " + coldInfo()
            preFirstFrameAt = p.firstFrameAt
        }
        lastPhoneSource = audio
        return primed
    }

    /** stage 5g-24: the first sound of a recording that began at the touch (wall clock), 0: none */
    private var preFirstFrameAt = 0L

    /** the process and Silero at the tap (after an idle the process may be new, Silero not loaded) */
    private fun coldInfo(): String {
        val age = if (android.os.Build.VERSION.SDK_INT >= 24) (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) / 1000 else -1
        val idle = if (lastDictationEnd > 0) (System.currentTimeMillis() - lastDictationEnd) / 1000 else -1
        return "process ${age} s old, idle ${if (idle >= 0) "$idle s" else "first since start"}, ${SileroVad.state()}"
    }
    private var lastDictationEnd = 0L

    /** Microphone key. Returns false if the old behaviour (switch to another keyboard with voice) should be used. */
    fun onMicKey(editorInfo: EditorInfo?): Boolean {
        lastField = fieldKey(editorInfo)
        handler.removeCallbacks(muteGiveUp) // stage 5g-26: the tap came
        val touchCall = muteTouchPending
        muteTouchPending = false
        if (interruption.isRunning) { // a recording of before the screen went off is still sent: never two at once
            Log.i(TAG, "microphone key while an interrupted recording is sent")
            return true
        }
        if (controller.isActive) {
            if (controller.state == VoiceState.LISTENING) attempt?.let { it.stop = it.stop ?: "done" }
            controller.onMicPressed()
            return true
        }
        val action = decide(
            sensitive = SensitiveInput.isSensitive(editorInfo),
            enabled = Settings.getValues().mVoiceInputInternal,
            available = recognizerAvailable(ime) || engineChoice().openAi,
            hasPermission = hasPermission(),
            askedBefore = ime.prefs().getBoolean(PREF_PERMISSION_ASKED, false)
        )
        Log.i(TAG, "microphone key: $action")
        when (action) {
            MicAction.INTERNAL -> {
                val a = VoiceAttempt(System.currentTimeMillis())
                attempt = a
                // stage 5g-20: a player is paused at the tap, before the recording starts (YouTube came into the recording)
                if (hasPermission() && screenIsOn()) takeFocus(a)
                controller.onMicPressed()
                discardPre("not used") // stage 5g-24: the recording took the sound of the touch, or it is not needed
                if (controller.state != VoiceState.LISTENING && controller.state != VoiceState.PROCESSING) focus.release()
                // nothing started: the permission dialog, or the screen is off; the journal says which
                when (controller.state) {
                    VoiceState.REQUESTING_PERMISSION -> notStarted("permission_asked")
                    VoiceState.IDLE -> notStarted(if (screenIsOn()) "unknown" else "screen_off")
                    else -> Unit
                }
                if (touchCall && !controller.isActive) endMuteCall("not started")
            }
            MicAction.FALLBACK -> if (!RichInputMethodManager.getInstance().isShortcutImeReady)
                Toast.makeText(ime, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            MicAction.NONE -> Unit
        }
        if (touchCall && action != MicAction.INTERNAL) endMuteCall("not started")
        return action != MicAction.FALLBACK
    }

    /**
     * Long press on the microphone (stage 5g): a continuous dictation, once, even when it is off in the settings. While a
     * dictation runs it is the same as a tap. Returns false if the old behaviour should be used.
     */
    fun onMicLongPress(editorInfo: EditorInfo?): Boolean {
        if (controller.isActive || interruption.isRunning) return onMicKey(editorInfo)
        continuousRequested = true
        try { return onMicKey(editorInfo) } finally { continuousRequested = false }
    }

    /**
     * The pinned microphone is golden only when the next tap records for OpenAI (docs/DESIGN.md, "Жёлтый микрофон");
     * in every other case it keeps the theme color. Called when a field is shown, a setting changes and after a dictation.
     */
    fun updateMicKey(editorInfo: EditorInfo?) {
        val sensitive = SensitiveInput.isSensitive(editorInfo)
        val gold = AiTint.micGold(sensitive, Settings.getValues().mVoiceInputInternal, hasPermission(),
            !sensitive && VoiceApiPrefs.isAvailable() && engineChoice(editorInfo).openAi)
        ime.setMicState(gold)
        // stage 5g-24: the first tap after an idle as fast as the next ones: the speech detector is loaded when the keyboard
        // shows a field where the OpenAI voice records (in the background, nothing is recorded)
        if (gold) SileroVad.preload(ime)
    }

    private fun fieldKey(info: EditorInfo?) = PermissionAutoStart.fieldKey(info?.packageName, info?.fieldId ?: 0)

    /** another panel (✨) or the service destroyed: stop without inserting; the cleanup ends too */
    fun stop() {
        if (controller.isActive) attempt?.let { it.stop = it.stop ?: "cancel" }
        controller.cancel()
        cleaner.stop()
    }

    /**
     * The screen went off, the keyboard was hidden, the app or field changed while recording (stage 5zh, item 5): the
     * recording is finished and sent (what was said is not lost); its text is inserted when the keyboard comes back in
     * the same field, with "The screen went off, recording finished". Nothing else (an error, the permission) is kept.
     */
    fun interrupt(reason: Interruption) {
        cleaner.stop()
        endMuteCall(if (!screenIsOn()) "screen off" else "hidden") // stage 5g-26: the music comes back at once
        screen.release() // stage 5g-20: the keyboard is gone: the normal timeout
        // stage 5g-21: the warm car: the screen off lets it go at once, a hidden keyboard after 10 s
        if (!screenIsOn()) warm.release(CarWarmLink.REASON_SCREEN) else warm.onHidden()
        val r = if (!screenIsOn()) Interruption.SCREEN_OFF else reason
        val state = controller.state
        when (interruption.onInterrupt(state, r)) {
            VoiceInterruption.Action.FINISH -> {
                Log.i(TAG, "interrupted while recording: $r")
                attempt?.let { it.stop = it.stop ?: if (r == Interruption.SCREEN_OFF) "screen_off" else "hidden" }
                removePanel()
                controller.finish() // stop and send what was recorded
            }
            VoiceInterruption.Action.WAIT -> { Log.i(TAG, "interrupted while recognizing: $r"); removePanel() }
            VoiceInterruption.Action.CANCEL -> controller.cancel()
        }
    }

    private fun screenIsOn() = ime.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false

    /** the system asks the keyboard to be shown for a field (also right after the permission dialog) */
    fun onInputViewStarted(editorInfo: EditorInfo?) {
        warm.onShown()
        if (interruption.hasSomethingToSay) handler.post { showInterruptionResult(editorInfo) }
        if (!autoStart.isPending) return
        val action = autoStart.onInputViewStarted(
            System.currentTimeMillis(), fieldKey(editorInfo), hasPermission(), SensitiveInput.isSensitive(editorInfo))
        Log.i(TAG, "after permission dialog: $action")
        when (action) {
            PermissionAutoStart.Action.START -> {
                // wait until the keyboard is laid out (the panel needs its height), then listen, once
                val r = Runnable { autoStartRunnable = null; if (!controller.isActive) controller.start() }
                autoStartRunnable = r
                handler.postDelayed(r, AUTO_START_DELAY_MS)
            }
            PermissionAutoStart.Action.SHOW_DENIED -> Toast.makeText(ime, R.string.voice_error_permission, Toast.LENGTH_LONG).show()
            PermissionAutoStart.Action.NONE -> Unit
        }
    }

    /** input started for a field (view may be hidden): bring the keyboard back after the permission dialog */
    fun onInputStarted(editorInfo: EditorInfo?) {
        if (autoStart.isPending && !ime.isInputViewShown && fieldKey(editorInfo) == lastField)
            handler.postDelayed({ if (autoStart.isPending && !ime.isInputViewShown) ime.requestShowSelf(0) }, 150)
    }

    /** screen off or a new session: nothing is expected any more */
    private fun resetAutoStart() {
        autoStart.reset()
        autoStartRunnable?.let { handler.removeCallbacks(it) }
        autoStartRunnable = null
    }

    /** stage 5g-20: the audio focus and what played at the tap, for the journal */
    private fun takeFocus(a: VoiceAttempt) {
        if (focus.held || a.musicPlaying != null) return
        focus.acquire()
        a.musicPlaying = focus.wasPlaying; a.focusTaken = focus.held; a.playbackConfigs = focus.configs
        if (focus.held) a.focusAtMs = System.currentTimeMillis() - a.at
        if (focus.wasPlaying == false) a.resume = RecordingFocus.RESUME_NOT_NEEDED
    }

    /** stage 5g-20: a key of the keyboard was pressed: the screen keeps to the normal timeout again */
    fun onUserTouch() { if (!controller.isActive) screen.release() }

    private fun keepScreenOn(on: Boolean) {
        try {
            val w = ime.window?.window ?: return
            if (on) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) { }
    }

    fun destroy() {
        endMuteCall("keyboard closed")
        warm.release("keyboard closed")
        screen.release()
        unwatchNetwork()
        focus.release()
        if (android.os.Build.VERSION.SDK_INT >= 23) try {
            ime.getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) { }
        interruption.clear()
        finishAttempt()
        controller.destroy()
        cleaner.stop()
        removePanel()
    }

    // VoiceHost

    override fun hasPermission() = ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** recording never starts while the screen is off */
    override fun screenOn() = screenIsOn()

    override fun requestPermission() {
        attempt?.notStarted = "permission_asked"
        ime.prefs().edit { putBoolean(PREF_PERMISSION_ASKED, true) }
        autoStart.expectStart(System.currentTimeMillis(), lastField ?: "")
        registerScreenOff() // screen off drops the expected start
        handler.postDelayed({ autoStart.reset() }, PermissionAutoStart.TIMEOUT_MS)
        try {
            ime.startActivity(Intent(ime, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "can't show permission dialog")
        }
        // the activity hides the keyboard, which ends the permission state; the user presses the microphone again
        controller.onPermissionFlowEnded()
    }

    override fun languageTag(): String = currentLocale().toLanguageTag()

    /** OpenAI or the system recognizer, decided again for every dictation (docs/VOICE_API.md, section 3) */
    private fun engineChoice(info: EditorInfo? = ime.currentInputEditorInfo): VoiceEngineChoice.Choice {
        val prefs = ime.prefs()
        return VoiceEngineChoice.decide(VoiceEngineChoice.Inputs(
            inBuild = VoiceApiPrefs.isAvailable(),
            setting = VoiceApiPrefs.engine(prefs),
            consent = VoiceApiPrefs.consent(prefs),
            hasKey = VoiceApi.hasKey(ime),
            keyRejected = VoiceApiPrefs.keyRejected(prefs),
            internetOn = AikbNetwork.internetAllowed(ime),
            locked = ime.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true,
            fieldBlocked = AiPrefs.isExcluded(prefs, info?.packageName) || Settings.getValues().mIncognitoModeEnabled,
            capReached = VoiceApiUsage(prefs).capReached(),
            paused = VoiceApi.pausedProblem(prefs, System.currentTimeMillis()),
            balanceExhausted = VoiceApiBalance(prefs).exhausted(),
        ))
    }

    private fun createEngine(): SpeechEngine {
        val choice = engineChoice()
        useOpenAi = choice.openAi
        val a = attempt ?: VoiceAttempt(System.currentTimeMillis()).also { attempt = it }
        a.engine = if (choice.openAi) "openai" else "system"
        notice = choice.notice
        noticeSwitched = false
        signalPeak = null; signalSource = null
        // stage 5g: continuous only through OpenAI (the setting, or once by a long press)
        val wantContinuous = continuousRequested || VoiceApiPrefs.continuous(ime.prefs())
        continuousOn = choice.openAi && wantContinuous
        continuousUnavailable = continuousRequested && !choice.openAi
        continuousWords = 0; continuousFailed = 0
        continuousEngine = null
        singleEngine = null
        if (continuousOn) { a.mode = "continuous"; a.phrases = 0 }
        Log.i(TAG, "recognizer: " + (if (choice.openAi) "OpenAI" else "system") + if (continuousOn) ", continuous" else "")
        if (!choice.openAi) return AndroidSpeechEngine(ime)
        val primary = if (continuousOn) continuousOpenAiEngine().also { continuousEngine = it } else openAiEngine().also { singleEngine = it }
        // if OpenAI fails: one quiet switch to the system recognizer, if the phone has one
        return FallbackSpeechEngine(primary, { AndroidSpeechEngine(ime) }, { AndroidSpeechEngine.isAvailable(ime) })
    }

    /** stage 5g-5: the headset of this recording is proven earbuds (asked for at once, a slow channel is no mark) */
    private var headsetEager = false
    /** proven earbuds (a slow channel is no mark), and stage 5g-7: a car-like device tried like a call */
    private var headsetProven = false
    private var headsetCar = false
    /** the badge "connecting": proven earbuds asked for, no sound from them yet */
    private var micConnecting = false
    /** the phone source of the last recording: which system effects were on (for the journal) */
    private var lastPhoneSource: AudioRecordSource? = null
    /** the headset tried in this recording, for [SilentMics] */
    private var headsetKey: String? = null
    /** the recorder of the running OpenAI dictation: a headset that comes or goes is told to it (stage 5g-2) */
    private var activeRecorder: PcmRecorder? = null

    /**
     * "Microphone for voice" (stage 5g … 5g-3), decided anew at every tap. With "Auto" nothing in the routing of sound
     * is touched unless a REAL headset is proven ([MicRouting]: the same device has a microphone input and the music
     * output that plays the media now); watches, speakers and bands: the phone as in 4351, 0 ms. A device found to be no
     * headset (or whose channel did not come up twice) is not tried again until it connects again. Nothing is asked.
     */
    private fun headsetMic(continuous: Boolean): HeadsetMic? {
        val choice = MicChoice.read(ime.prefs())
        headsetKey = null
        carMic = null
        val am = ime.getSystemService(android.media.AudioManager::class.java)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P || am == null) { micMissing = choice == MicChoice.HEADSET; return null }
        val snap = AndroidHeadsetMic.snapshot(am) // only reads lists, changes nothing
        val marks = SilentMics.get(ime)
        val prefs = ime.prefs()
        // stage 5g-25: a car only by its Bluetooth class (or, while the class is unknown, by a proof and the name):
        // watches and the car alarm have a call input and no media, too
        val btInfos = HashMap<String, CarDetect.Info>()
        fun infoOf(d: AudioDevice) = btInfos.getOrPut(MicRouting.key(d)) { CarDetect.info(ime, d.tail, snap.inputInfo(d)?.productName) }
        fun kindOf(d: AudioDevice) = infoOf(d).let { CarDetect.kind(it.major, it.deviceClass) }
        val routing = MicRouting.decide(choice, snap.inputs, snap.outputs, snap.media, snap.communication,
            notHeadset = { marks.has(MicRouting.key(it)) },
            knownCar = { KnownCars.has(prefs, MicRouting.key(it)) || kindOf(it) == CarDetect.Kind.CAR },
            mayBeCar = { d -> CarDetect.mayBeCar(kindOf(d), infoOf(d).name, KnownCars.has(prefs, MicRouting.key(d)), NotCars.has(prefs, MicRouting.key(d))) })
        micMissing = routing.decision.missing
        val callInputs = snap.inputs.filter { AndroidHeadsetMic.isHeadsetWithMicrophone(it.type) }
        val classes = callInputs.joinToString("; ") { d ->
            "${d.tail?.takeLast(2) ?: "??"} = ${CarDetect.describe(infoOf(d))}" + (if (NotCars.has(prefs, MicRouting.key(d))) ", marked not a car" else "")
        }
        attempt?.let { it.btDevices = routing.devices + (if (classes.isNotEmpty()) " [$classes]" else ""); it.micReason = routing.reason }
        NotCars.rememberLast(prefs, callInputs.map { MicRouting.key(it) })
        askBluetoothClass = callInputs.any { kindOf(it) == CarDetect.Kind.UNKNOWN } &&
            CarDetect.shouldAsk(prefs, true, CarDetect.permitted(ime)) && CarDetect.declared(ime)
        Log.i(TAG, "microphone: ${routing.reason}; bluetooth: ${routing.devices}") // types and 2 characters of the address only
        if (routing.decision.skipped) attempt?.mic = "phone, marked device skipped"
        headsetProven = routing.reason == "headset proven"
        // stage 5g-10: a device proven as earbuds playing the media loses its mark
        if (headsetProven) routing.headset?.let { marks.unmark(MicRouting.key(it)) }
        // stage 5g-7: a car (or another hands-free device) like a call; stage 5g-20 (probe 4397: every source of the
        // HONOR phone is deaf while the car's link comes up): never at the tap. One phrase: the phone alone; a continuous
        // dictation: the phone first, the car in the first pause after a phrase
        headsetCar = routing.carLike
        onWarm = false
        warmCandidate = null
        // stage 5g-22: the car there is, marked "without sound" or not: a mark never stops the mute call or the warm link
        val carDevice = routing.carDevice
        val carKey = carDevice?.let { MicRouting.key(it) }
        carPlan = MicRouting.carPlan(carDevice != null, continuous)
        // stage 5g-26 (4436: the warm link after a phrase made the music come back and the "call" come again and hang):
        // where the quiet call is used there is no warm link and no car in a pause: the phone records the whole session,
        // one call from the touch to the end
        val muteWanted = carDevice != null && CarMuteCall.enabled(prefs) && android.os.Build.VERSION.SDK_INT >= 26
        if (!muteWanted && muteSession != null) endMuteCall("no car at the tap")
        if (muteWanted) {
            warm.release("quiet call used")
            carPlan = MicRouting.CarPlan.PHONE_ONLY
            if (muteSession?.endRequested != false) startMuteCall(fromTouch = false) // at once, not at the first frame
        }
        // stage 5g-21: the car's link is warm (a phrase a moment ago): the car records at once, the probation as in 5g-20;
        // stage 5g-22: only if the link is really up (it went down when the car connected its media in 4401)
        if (carKey != null && warm.isWarm(carKey)) {
            val lost = warm.lost()
            if (lost == null) warm.lease()?.let { leased ->
                onWarm = true
                headsetEager = true
                headsetCar = true
                headsetKey = carKey
                attempt?.let { it.car = "warm link: alive; started on warm link"; it.micReason = (it.micReason ?: "") + "; phone while trying: CAMCORDER" }
                return leased
            }
            attempt?.car = "warm link: lost ($lost)"
        }
        // stage 5g-21, owner's addition 2: the probe "mute the radio with a call" (a self-managed call, no SCO);
        // stage 5g-22: it starts once the phone records (the first frame), so the first words never depend on it
        mutePending = false // stage 5g-26: placed above (at the touch or at once), never at the first frame
        if (carPlan == MicRouting.CarPlan.PHONE_ONLY) {
            attempt?.car = (attempt?.car?.let { "$it; " } ?: "") + "single phrase phone only"
            carDevice?.let { d -> snap.inputInfo(d)?.let { warmCandidate = it to carKey!! } }
            headsetCar = false; headsetEager = false
            return null
        }
        headsetEager = headsetProven
        val device = routing.headset ?: return null
        val info = snap.inputInfo(device) ?: return null
        headsetKey = MicRouting.key(device)
        // stage 5g-12: while the device is tried the phone records with CAMCORDER (stays on the built-in microphone)
        attempt?.let { it.micReason = (it.micReason ?: "") + "; phone while trying: CAMCORDER" }
        // stage 5g-5: proven earbuds are asked for at the tap: the badge shows "connecting" at once
        if (headsetEager) { micConnecting = true; handler.post { panel?.setBluetoothConnecting(true, headsetCar) } }
        if (headsetCar) attempt?.car = "phone first"
        // stage 5g-14: a car-like device is chosen first, the call mode comes once its link is up (the phone keeps hearing)
        return AndroidHeadsetMic(ime, info, modeAfterLink = headsetCar && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            .also { carMic = it.takeIf { m -> m.modeAfterLink } }
    }

    /** stage 5g-25: the class of a Bluetooth device with a call input was unknown: the permission is asked after this dictation */
    private var askBluetoothClass = false

    /** stage 5g-14: the car-like device of this recording, for the journal (when its link came up) */
    private var carMic: AndroidHeadsetMic? = null

    /**
     * Stage 5g-18 (diagnostic build): with a car-like device every source of the phone is opened next to the recording
     * while the car's link comes up ([PhoneProbe]): MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED (where the phone says it
     * has it), VOICE_PERFORMANCE (Android 10+), DEFAULT and MIC with the built-in microphone preferred. The order turns by
     * one at every such tap (sources that did not open come first later), and the order of the link alternates: the
     * device 300 ms after the phone sources, or at once.
     */
    private fun carProbe(h: HeadsetMic?): PhoneProbe? {
        if (h == null || !headsetCar || onWarm || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        // stage 5g-20: probe 4397 found every source deaf; only with "Probe the inputs" of the developer mode (another phone)
        val p = ime.prefs()
        if (!helium314.keyboard.latin.ai.DeveloperMode.on(p) || !p.getBoolean(PhoneProbe.PREF, false)) return null
        val am = ime.getSystemService(android.media.AudioManager::class.java) ?: return null
        val list = mutableListOf(
            PhoneProbe.Candidate("MIC", android.media.MediaRecorder.AudioSource.MIC),
            PhoneProbe.Candidate("CAMCORDER", android.media.MediaRecorder.AudioSource.CAMCORDER),
            PhoneProbe.Candidate("VOICE_RECOGNITION", android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION),
        )
        val unprocessed = try { am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true" } catch (e: Exception) { false }
        if (unprocessed) list.add(PhoneProbe.Candidate("UNPROCESSED", android.media.MediaRecorder.AudioSource.UNPROCESSED))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            list.add(PhoneProbe.Candidate("VOICE_PERFORMANCE", android.media.MediaRecorder.AudioSource.VOICE_PERFORMANCE))
        list.add(PhoneProbe.Candidate("DEFAULT", android.media.MediaRecorder.AudioSource.DEFAULT))
        val builtIn = AndroidHeadsetMic.builtInMic(ime)
        if (builtIn != null) list.add(PhoneProbe.Candidate("MIC@builtin", android.media.MediaRecorder.AudioSource.MIC, builtIn = true))
        val prefs = ime.prefs()
        val round = prefs.getInt(PROBE_ROUND_PREF, 0)
        prefs.edit().putInt(PROBE_ROUND_PREF, round + 1).apply()
        if (!unprocessed) attempt?.let { it.micReason = (it.micReason ?: "") + "; UNPROCESSED not supported" }
        return PhoneProbe(PhoneProbe.rotated(list, round), PhoneProbe.waitFor(round)) { c ->
            AudioRecordSource(c.source, preferredDevice = if (c.builtIn) builtIn else null, nonBlocking = true)
        }
    }

    /**
     * the phone: from the built-in microphone only while a proven headset is tried in this recording (stage 5g-3:
     * otherwise nothing is chosen, exactly as in 4351); read without waiting while the headset is on probation
     */
    private fun phoneSources(headsetTried: Boolean): (Int) -> PcmSource {
        val builtIn = if (headsetTried && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) AndroidHeadsetMic.builtInMic(ime) else null
        // stage 5g-6: the noise suppressor and the gain control of the phone, where it has them
        // stage 5g-24: the first source of a recording is the one opened at the touch, if there is one
        return { src -> (if (!headsetTried) takePre(src, builtIn == null) else null.also { discardPre("headset tried") })
            ?: AudioRecordSource(src, preferredDevice = builtIn, nonBlocking = true, effects = true).also { s -> lastPhoneSource = s } }
    }

    /**
     * Stage 5g-2: headsets come and go many times a day. A new one forgets what was learned about the devices, and when
     * it is a proven headset during a dictation it is asked for at the next pause; one that goes away gives the
     * recording back to the phone.
     */
    private val headsetWatcher = HeadsetWatcher(
        onNewHeadset = {
            // stage 5g-3: the marks of silent devices stay (the car connects every day); only a proven headset, only in
            // a continuous dictation, is asked for at the next pause
            val r = activeRecorder
            if (r != null && controller.state == VoiceState.LISTENING && !r.onHeadset && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val h = AndroidHeadsetMic.proven(ime)
                if (MicRouting.offerAllowed(continuousOn, MicChoice.read(ime.prefs()), h != null, SilentMics.get(ime).has(h?.deviceKey))) {
                    headsetKey = h!!.deviceKey
                    attempt?.let { it.micReason = (it.micReason ?: "") + "; headset connected during the dictation" }
                    Log.i(TAG, "headset connected while recording: tried at the next pause")
                    headsetEager = true; headsetProven = true; headsetCar = false; micConnecting = true; panel?.setBluetoothConnecting(true)
                    r.offerHeadset(h, eager = true) // offers are proven earbuds only
                }
            }
        },
        onHeadsetGone = { activeRecorder?.let { Log.i(TAG, "headset gone while recording: the phone goes on"); it.headsetGone() } },
    )

    private val deviceCallback = if (android.os.Build.VERSION.SDK_INT >= 23) object : android.media.AudioDeviceCallback() {
        private var primed = false
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
            val sources = added.filter { it.isSource }
            // stage 5g-22: a device that connects again loses its mark "without sound" (not the first report: what is there)
            if (primed) sources.filter { AndroidHeadsetMic.isHeadsetWithMicrophone(it.type) }
                .forEach { SilentMics.get(ime).unmark(MicRouting.key(helium314.keyboard.latin.voiceapi.AudioDevice(it.type, it.address ?: ""))) }
            primed = true
            headsetWatcher.added(sources.map { it.type })
        }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) {
            val types = removed.filter { it.isSource }.map { it.type }
            // stage 5g-21: the car went away: its warm link is given up
            if (types.any { AndroidHeadsetMic.isHeadsetWithMicrophone(it) }) warm.release(CarWarmLink.REASON_DISCONNECT)
            headsetWatcher.removed(types)
        }
    } else null

    init {
        if (android.os.Build.VERSION.SDK_INT >= 23) try {
            ime.getSystemService(android.media.AudioManager::class.java)?.registerAudioDeviceCallback(deviceCallback, handler)
        } catch (_: Exception) { }
    }

    /** a failed check of the device: the first is a strike, the second a mark for good (stage 5g-10) */
    private fun strike() {
        val n = SilentMics.get(ime).strike(headsetKey)
        if (n > 0) attempt?.strike = "$n/${SilentMics.STRIKES_TO_MARK}"
    }

    /** the headset or the phone records: the panel says which (and that the headset did not answer) */
    override fun onMic(headset: Boolean, fellBack: Boolean) {
        // stage 5g-20: the car records from now on (it was asked for in a pause)
        if (headset && headsetCar && !onWarm) attempt?.let { it.car = "car joined in pause after ${System.currentTimeMillis() - it.at} ms" }
        micHeadset = headset
        micConnecting = false // sound from the earbuds (bright badge), or back to the phone (no badge)
        panel?.setBluetoothConnecting(false, false)
        micFellBack = fellBack
        Log.i(TAG, "microphone: " + (if (headset) "headset" else "phone") + if (fellBack) " (headset did not answer)" else "")
        panel?.setMic(headset, fellBack, micMissing)
    }

    /** what an answer of OpenAI means: minutes, pauses, the key; said once (80 % of the ceiling, little money left) */
    private fun onOpenAiResult(r: helium314.keyboard.latin.voiceapi.TranscriptionResult, sentMs: Long) {
        val prefs = ime.prefs()
        val usage = VoiceApiUsage(prefs)
        val outcome = VoiceApi.applyResultFully(prefs, r, sentMs, System.currentTimeMillis())
        if (outcome.usageWarning)
            Toast.makeText(ime, ime.getString(R.string.voice_api_usage_warning_toast,
                usage.usedMinutes().toInt(), usage.capMinutes()), Toast.LENGTH_LONG).show()
        // once: little money left, or the balance is used up (from now on the system recognizer)
        when (outcome.balance) {
            VoiceApiBalance.Event.LOW -> Toast.makeText(ime, ime.getString(R.string.voice_balance_low_toast,
                VoiceApiBalance(prefs).remainingDollars()?.let { VoiceApiBalance.format(it, Locale.getDefault()) } ?: "0"),
                Toast.LENGTH_LONG).show()
            VoiceApiBalance.Event.EXHAUSTED -> Toast.makeText(ime, R.string.voice_balance_exhausted_toast, Toast.LENGTH_LONG).show()
            VoiceApiBalance.Event.NONE -> Unit
        }
    }

    /**
     * Continuous dictation (docs/DESIGN.md, 5г): one open microphone cut at the pauses, at most 2 phrases recognized at
     * once, inserted in order; ends after the silence of the settings (10 s), the session limit (5 min), the minutes left
     * this month, or a tap. Each phrase is at most the longest recording of the settings.
     */
    /**
     * stage 5g-19: the Silero speech detector (personal variant): the model is loaded in the background when an OpenAI
     * engine is made; each detector gets its own stream (null: WebRTC VAD, the journal says why)
     */
    private val sileroStream: () -> SpeechProbability? = { SileroVad.stream() }

    private fun continuousOpenAiEngine(): ContinuousSpeechEngine {
        SileroVad.preload(ime)
        val prefs = ime.prefs()
        val sessionMs = VoiceApiPrefs.continuousLimitMin(prefs) * 60_000L
        val silenceMs = VoiceApiPrefs.continuousSilenceS(prefs) * 1000
        // stage 5g-20: no phrase longer than 30 s
        val continuous = PcmRecorder.Continuous(maxPhraseMs = minOf(VoiceApiPrefs.maxSeconds(prefs) * 1000L, PcmRecorder.CHUNK_MS),
            maxSpeechMs = VoiceApiUsage(prefs).remainingMs())
        return ContinuousSpeechEngine(
            recorderFactory = { l -> val h = headsetMic(true); PcmRecorder(phoneSources(h != null), VoiceApiPrefs.audioSource(prefs), sessionMs, l, { handler.post(it) },
                detectorFactory = { SpeechEndDetector(endSilenceMs = VoiceApiPrefs.endPauseMs(prefs), noSpeechTimeoutMs = silenceMs, silero = sileroStream) },
                continuous = continuous, headset = h, headsetEager = h != null && headsetEager, headsetCar = h != null && headsetCar,
                phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER.takeIf { h != null }, probe = carProbe(h),
                headsetAfterPhrase = h != null && headsetCar && !onWarm, muteStartMs = muteStartMs()).also { activeRecorder = it; lastRecorder = it } },
            later = later, store = chunkStore,
            transcribe = { audio, language, context, cancel, onSent -> VoiceApi.client(ime).transcribe(audio, language, cancel, onSent, context) },
            background = { VoiceApi.phraseWorkers.execute(it) },
            post = { handler.post(it) },
            onResult = { r, sentMs -> onOpenAiResult(r, sentMs) },
            sessionMs = sessionMs,
            onSourceWorked = { source -> if (VoiceApiPrefs.audioSource(prefs) != source) VoiceApiPrefs.setAudioSource(prefs, source) },
        )
    }

    private fun openAiEngine(): OpenAiSpeechEngine {
        SileroVad.preload(ime)
        val prefs = ime.prefs()
        val usage = VoiceApiUsage(prefs)
        // never longer than the minutes left this month
        val recordMs = minOf(VoiceApiPrefs.maxSeconds(prefs) * 1000L, usage.remainingMs())
        return OpenAiSpeechEngine(
            recorderFactory = { l -> val h = headsetMic(false); PcmRecorder(phoneSources(h != null), VoiceApiPrefs.audioSource(prefs), recordMs, l, { handler.post(it) },
                detectorFactory = { SpeechEndDetector(endSilenceMs = VoiceApiPrefs.endPauseMs(prefs), silero = sileroStream) }, headset = h,
                headsetEager = h != null && headsetEager, headsetCar = h != null && headsetCar,
                phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER.takeIf { h != null }, probe = carProbe(h),
                chunkMs = PcmRecorder.CHUNK_MS, muteStartMs = muteStartMs()).also { activeRecorder = it; lastRecorder = it } },
            later = later, store = chunkStore,
            transcribe = { audio, language, cancel, onSent -> VoiceApi.client(ime).transcribe(audio, language, cancel, onSent) },
            background = { VoiceApi.worker.execute(it) },
            post = { handler.post(it) },
            onResult = { r, sentMs -> onOpenAiResult(r, sentMs) },
            recordMs = recordMs,
            onSourceWorked = { source -> if (VoiceApiPrefs.audioSource(prefs) != source) VoiceApiPrefs.setAudioSource(prefs, source) },
        )
    }

    override fun onSignal(peakDbfs: Double?, source: String?) {
        signalPeak = peakDbfs
        signalSource = source
        Log.i(TAG, "signal peak ${peakDbfs?.toInt()} dBFS, source $source") // numbers only
        panel?.setSignal(peakDbfs, source)
    }

    override fun onNotice(problem: VoiceApiProblem?, switched: Boolean) {
        notice = problem
        noticeSwitched = switched
        if (switched) {
            useOpenAi = false
            micHeadset = null; micFellBack = false; micMissing = false // the system recognizer picks its own microphone
            panel?.setMic(null, false, false)
            continuousOn = false // the system recognizer takes one phrase; what was inserted stays
            continuousEngine = null
            panel?.setContinuous(false)
            attempt?.let { it.engine = "openai→system"; it.error = "openai:" + problem?.name?.lowercase(); it.step = "upload" }
        }
        panel?.setNotice(problem, switched)
    }

    /** the phrase is inserted at once as it was recognized; the cleanup may replace it a moment later */
    override fun commitText(text: String) {
        screen.inserted()
        val words = VoiceJournal.words(text)
        attempt?.words = words
        if (interruption.isRunning) {
            val here = ime.isInputViewShown && fieldKey(ime.currentInputEditorInfo) == sessionField
            if (!interruption.onText(text, here, sessionField)) { // kept in memory until the keyboard is back
                attempt?.inserted = "pending"
                copyToClipboard(text)
                return
            }
        }
        attempt?.inserted = "yes"
        insert(text)
        // the last step said on the strip: "Done: N words" (the cleanup may replace it with "Undo" a moment later)
        if (!interruption.isRunning) helium314.keyboard.latin.ai.AiStripChip(ime).show(
            VoiceInputView.labelContextFor(ime, currentLocale()).resources.getQuantityString(R.plurals.voice_done_words, words, words), true, null)
    }

    /**
     * Continuous dictation: one phrase, in order. It goes in with a space before it, a capital after the end of a
     * sentence, and gets its own cleanup while the earlier cleanups go on (docs/DESIGN.md, 5г). No line in the strip:
     * the panel counts the phrases; "Done: N words" comes at the end.
     */
    override fun commitPhrase(text: String) {
        screen.inserted()
        val words = VoiceJournal.words(text)
        continuousWords += words
        attempt?.words = continuousWords
        if (interruption.isRunning) {
            val here = ime.isInputViewShown && fieldKey(ime.currentInputEditorInfo) == sessionField
            if (!interruption.onText(text, here, sessionField)) { attempt?.inserted = "pending"; copyToClipboard(text); return }
        }
        if (attempt?.inserted != "pending") attempt?.inserted = "yes"
        insert(text, continuous = true)
    }

    /**
     * stage 5g-20: the text came (maybe long after, when the network was back) but its field is gone: it is kept in the
     * clipboard as well, and said once; the text of the dictation so far goes there whole, in order
     */
    private fun copyToClipboard(text: String) {
        val first = clipped.isEmpty()
        if (!first) clipped.append(' ')
        clipped.append(text)
        try {
            ime.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("dictation", clipped.toString()))
            if (first) Toast.makeText(ime, VoiceInputView.labelContextFor(ime, currentLocale()).getString(R.string.voice_copied_to_clipboard), Toast.LENGTH_LONG).show()
        } catch (_: Exception) { }
    }

    override fun onProgress(phrases: Int, recognizing: Int, failed: Int) {
        continuousFailed = failed
        attempt?.let { it.phrases = phrases; it.failedPhrases = failed }
        panel?.setProgress(phrases, recognizing, failed)
    }

    /** continuous dictation is over: "Done: N words" in the strip (and how many phrases were not recognized) */
    override fun onContinuousDone() {
        if (interruption.isRunning) return
        val labels = VoiceInputView.labelContextFor(ime, currentLocale())
        val done = labels.resources.getQuantityString(R.plurals.voice_done_words, continuousWords, continuousWords)
        val text = if (continuousFailed > 0) labels.getString(R.string.voice_continuous_done_failed, done, continuousFailed) else done
        // stage 5g-6: the dictation was stopped because there was only noise for 20 s: said
        helium314.keyboard.latin.ai.AiStripChip(ime).show(
            if (attempt?.stop == "noise") labels.getString(R.string.voice_continuous_done_noise, text) else text, true, null)
    }

    /** a step of the dictation: the journal, and on the panel "Sending… N KB" and "Recognizing…" */
    override fun onTrace(trace: VoiceTrace) {
        // a headset whose channel did not come up twice in a row is not tried any more with "Auto" (stage 5g-2)
        // stage 5g-3: a device whose channel did not come up, or which was silent where the phone heard the speaker, is
        // marked for good; one that once heard the speaker loses its mark
        when (trace) {
            // proven earbuds can be slow to come up: that is no mark; only silence where the phone heard the speaker is
            // a car that did not come up is marked (stage 5g-3, 5g-7)
            // stage 5g-10: marked only after two failed checks in a row (proven earbuds are never marked)
            is VoiceTrace.HeadsetTried -> {
                if (!trace.up && MicRouting.strikeAllowed(headsetProven, onWarm)) strike()
                // stage 5g-10: the channel of a car is up: a short tick "speak" (no vibration permission needed)
                if (trace.up && headsetCar) handler.post { panel?.tickReady() }
            }
            VoiceTrace.HeadsetRejected -> if (MicRouting.strikeAllowed(headsetProven, onWarm)) strike()
            VoiceTrace.HeadsetHeard -> {
                SilentMics.get(ime).unmark(headsetKey)
                // stage 5g-25: the proof of a car: its microphone heard the speaker
                if (headsetCar) KnownCars.add(ime.prefs(), headsetKey)
            }
            // stage 5g-20: the car is asked for in a pause: the dimmed badge "connecting the car"
            VoiceTrace.HeadsetAsked -> { micConnecting = true; panel?.setBluetoothConnecting(true, headsetCar); endMuteCall("car asked for") }
            else -> Unit
        }
        val a = attempt ?: return
        when (trace) {
            is VoiceTrace.RecordingStarted -> {
                a.started = true; a.source = trace.source
                lastPhoneSource?.let { a.effects = it.effectsOn }
                // stage 5g-24: a recording that began at the tap (not at the touch): its steps too
                if (a.startInfo == null) a.startInfo = "at the tap; microphone (${lastPhoneSource?.timing ?: "?"}); " + coldInfo()
                if (a.mic?.startsWith("phone, not a headset") != true) trace.mic?.let { a.mic = it }
            }
            is VoiceTrace.EarlyLevels -> a.earlyLevels = trace.dbfs.joinToString(" ")
            is VoiceTrace.Noise -> { a.speechPercent = trace.speechPercent; a.noiseDb = trace.noiseDbfs.toInt(); a.noisePhrases = trace.droppedPhrases }
            is VoiceTrace.FirstFrame -> {
                // stage 5g-24: a recording begun at the touch had its first sound before the tap (then 0)
                a.firstFrameMs = ((if (preFirstFrameAt > 0) preFirstFrameAt else trace.atMs) - a.at).coerceAtLeast(0)
                preFirstFrameAt = 0
                if (mutePending) { mutePending = false; startMuteCall() } // stage 5g-22: the phone records first, then the call
            }
            is VoiceTrace.HeadsetTried -> a.headsetMs = trace.ms
            VoiceTrace.HeadsetRejected, VoiceTrace.HeadsetHeard, VoiceTrace.HeadsetAsked -> Unit
            is VoiceTrace.SameSound -> { a.sameFrames = trace.same; a.bothFrames = trace.frames }
            is VoiceTrace.SourcesMs -> { a.phoneMs = trace.phoneMs; a.headsetRecMs = trace.headsetMs }
            is VoiceTrace.PhoneReopened -> a.micReason = (a.micReason ?: "") + "; phone microphone was deaf after the rollback, opened anew ${trace.times}x"
            VoiceTrace.HeadsetUndecided -> a.micReason = (a.micReason ?: "") + "; nobody spoke while the device was tried: no verdict"
            is VoiceTrace.ProbationLevels -> {
                a.probeHeadsetDb = trace.headsetDbfs.toInt(); a.probePhoneDb = trace.phoneDbfs.toInt()
                a.probeMs = trace.afterUpMs; a.probeFrames = trace.speechFrames
            }
            is VoiceTrace.Gap -> {
                a.gapMs = trace.deafMs; a.gapOfMs = trace.connectMs; a.gapPeakDb = trace.phonePeakDbfs.toInt()
                carMic?.let { m -> a.micReason = (a.micReason ?: "") + (m.linkMs?.let { "; call mode after the link: $it ms" } ?: "; the link did not come up without the call mode") }
            }
            is VoiceTrace.PhoneLevels -> { a.phoneBeforeDb = trace.beforeDbfs.toInt(); a.phoneAfterDb = trace.afterDbfs.toInt() }
            is VoiceTrace.Probe -> { a.probe = trace.summary; a.filledMs = trace.filledMs }
            is VoiceTrace.Vad -> {
                a.vad = trace.name; a.probAvg = (trace.probAvg * 100).roundToInt(); a.probMax = (trace.probMax * 100).roundToInt()
                a.vadThreshold = (trace.threshold * 100).roundToInt()
            }
            is VoiceTrace.RecordingFailed -> { a.started = false; a.notStarted = trace.reason; a.step = "start" }
            is VoiceTrace.Recorded -> {
                a.peakDb = trace.peakDbfs.toInt(); a.speech = trace.speech; a.recordMs = trace.ms
                a.stop = a.stop ?: trace.end
            }
            is VoiceTrace.Uploading -> {
                val kb = (trace.bytes + 1023) / 1024
                // continuous dictation: all phrases together; the panel shows its counters instead
                a.uploadKb = if (continuousOn) (a.uploadKb ?: 0) + kb else kb
                if (!continuousOn) panel?.setPhase(VoiceInputView.Phase.Sending(kb))
            }
            VoiceTrace.Sent -> if (!continuousOn) panel?.setPhase(VoiceInputView.Phase.Recognizing)
            is VoiceTrace.Answer -> { a.uploadStatus = trace.status; a.uploadMs = trace.ms }
        }
    }

    /** the dictation is over: one line in the journal */
    private fun finishAttempt() {
        val a = attempt ?: return
        attempt = null
        journal.add(a)
        lastDictationEnd = System.currentTimeMillis()
        if (askBluetoothClass) {
            askBluetoothClass = false
            CarDetect.markAsked(ime.prefs())
            // stage 5g-25: once, after the dictation: "Nearby devices" tells the car radio from a watch or the car alarm
            handler.postDelayed({
                if (controller.state == VoiceState.LISTENING || controller.state == VoiceState.PROCESSING) return@postDelayed
                Toast.makeText(ime, R.string.voice_bt_class_why, Toast.LENGTH_LONG).show()
                try {
                    ime.startActivity(Intent(ime, VoicePermissionActivity::class.java)
                        .putExtra(VoicePermissionActivity.EXTRA_PERMISSION, android.Manifest.permission.BLUETOOTH_CONNECT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "can't show permission dialog")
                }
            }, 800)
        }
    }


    private fun notStarted(reason: String) {
        attempt?.let { it.notStarted = it.notStarted ?: reason }
        finishAttempt()
    }

    /** where a dictation failed, for the journal */
    private fun failedStep(a: VoiceAttempt) = when {
        a.engine == "openai→system" -> "system"
        a.uploadStatus != null || a.uploadMs != null -> "answer"
        a.uploadKb != null -> "upload"
        a.started == true -> "record"
        else -> "start"
    }

    /**
     * stage 5g-17: [text] goes in as it fits the place: in the middle of a sentence with a lowercase first letter, without
     * the final period when the sentence goes on after the cursor, with a space after it when a letter follows. The
     * ✨ cleanup of the phrase gets the same rule. Only ~50 characters around the cursor are looked at, on the phone.
     */
    private fun insert(text: String, continuous: Boolean = false) {
        // stage 5g-20: the text goes where the dictation was (the end of what it inserted), also when it comes late
        // (the network) and the cursor was moved meanwhile; then the cursor goes back to where the user put it
        val cursor = ime.voiceCursor()
        val from = nextPos
        val moved = from >= 0 && cursor >= 0 && cursor != from
        if (moved) ime.moveVoiceCursor(from)
        try { insertHere(text, continuous) } finally {
            val end = ime.voiceCursor()
            if (end >= 0) nextPos = end
            if (moved && end >= 0) ime.moveVoiceCursor(if (cursor > from) cursor + (end - from) else cursor)
        }
    }

    private fun insertHere(spoken: String, continuous: Boolean) {
        // stage 5g-23: numbers in digits ("сто процентов" → "100%"), before the case and the final period are looked at
        val nums = NumberWords.apply(spoken)
        val text = nums.text
        if (nums.converted > 0) attempt?.let { it.numbers = (it.numbers ?: 0) + nums.converted }
        val ic = ime.currentInputConnection
        val before = try { ic?.getTextBeforeCursor(CONTEXT_CHARS, 0) } catch (e: Exception) { null }
        val after = try { ic?.getTextAfterCursor(CONTEXT_CHARS, 0) } catch (e: Exception) { null }
        val known = { w: String -> ime.isKnownLowercaseWord(w) }
        val a = MidSentence.adapt(before, after, text, continuous, known)
        attempt?.let { if (it.place == null || !continuous) it.place = a.journal() }
        val start = ime.commitVoiceText(a.text, a.spaceAfter)
        if (start < 0) return
        val rule: (String) -> String = { c ->
            var r = c
            if (a.lowered) MidSentence.lowerFirst(r, known)?.let { r = it }
            if (a.periodRemoved) r.trimEnd().trimEnd { it in ".!?…" }.takeIf { it.isNotEmpty() }?.let { r = it }
            NumberWords.apply(r).text // stage 5g-23: the cleaned text keeps digits too
            
        }
        cleaner.onInserted(a.text, start, continuous, rule)
    }

    /**
     * The keyboard is back after an interrupted recording (or the text came while it is shown): the text goes into the
     * same field at once; in another field the line offers "Insert" (the user decides).
     */
    private fun showInterruptionResult(editorInfo: EditorInfo?) {
        val show = interruption.take(fieldKey(editorInfo)) ?: return
        val labels = VoiceInputView.labelContextFor(ime, currentLocale())
        val what = labels.getString(if (show.reason == Interruption.SCREEN_OFF) R.string.voice_interrupted_screen_off else R.string.voice_interrupted_hidden)
        val chip = helium314.keyboard.latin.ai.AiStripChip(ime)
        when (show) {
            is VoiceInterruption.Show.Inserted -> chip.show(labels.getString(R.string.voice_interrupted_inserted, what), true, null)
            is VoiceInterruption.Show.Failed -> chip.show(labels.getString(R.string.voice_interrupted_failed, what), true, null)
            is VoiceInterruption.Show.InsertNow -> {
                insert(show.text)
                chip.show(labels.getString(R.string.voice_interrupted_inserted, what), true, null)
            }
            is VoiceInterruption.Show.Offer -> chip.show(labels.getString(R.string.voice_interrupted_insert, what), false) {
                chip.hide()
                insert(show.text)
            }
        }
    }

    /**
     * Cleanup only where ✨ could send too (docs/AI.md): key, consent for text, internet, field not sensitive, app not
     * excluded, not incognito, no pause, daily ceiling not reached. Checked right before every cleanup request.
     */
    private fun cleanupMayRun(): Boolean {
        val prefs = ime.prefs()
        val usage = AiUsage(prefs)
        return AiAssist.inBuild() && AiGuard.maySend(ime.currentInputEditorInfo, AiPrefs.excludedApps(prefs),
            Settings.getValues().mIncognitoModeEnabled, AikbNetwork.internetAllowed(ime), AiAssist.isReady(ime)) &&
            !usage.capReached() && usage.pausedError() == null
    }

    /** "Undo cleanup" or the quiet note in the suggestion strip, in the language of the layout */
    private inner class StripCleanupUi : CleanupUi {
        private val chip = helium314.keyboard.latin.ai.AiStripChip(ime)

        private fun show(textRes: Int, muted: Boolean, onClick: (() -> Unit)?) =
            chip.show(VoiceInputView.labelContextFor(ime, currentLocale()).getString(textRes), muted, onClick)

        override fun showUndo(onUndo: () -> Unit) = show(R.string.ai_cleanup_undo, false, onUndo)
        override fun showNote() = show(R.string.ai_cleanup_note, true, null)
        override fun hide() = chip.hide()
    }

    override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) {
        // the audio focus only while the microphone records: released after the phrase, a cancel, an error, hiding
        if (state == VoiceState.LISTENING) {
            // the journal: did something play at the tap, was the focus taken (stage 5g-5; 5g-20: at the tap already)
            attempt?.let { takeFocus(it) }
            focus.acquire()
        } else {
            endMuteCall(when (state) { VoiceState.ERROR -> "error"; VoiceState.IDLE -> "cancel"; else -> "phrase end" }) // stage 5g-21: the phrase is recorded: the call ends
            if (focus.held) resumeAttempt = attempt // the music check comes 0.8 s later, often after the entry was written
            focus.release()
        }
        if (state == VoiceState.LISTENING) sessionField = lastField
        // stage 5g-20: the screen stays on while recording, recognizing and inserting; the place of the dictation
        when (state) {
            VoiceState.LISTENING -> { screen.keep(); if (nextPos < 0) nextPos = ime.voiceCursor() }
            VoiceState.PROCESSING, VoiceState.DONE -> screen.keep()
            else -> Unit
        }
        if (state == VoiceState.ERROR) attempt?.let { noteChunks(it) }
        if (state == VoiceState.ERROR && error != null) attempt?.let { a ->
            a.error = error.name.lowercase() + (notice?.let { ":" + it.name.lowercase() } ?: "")
            a.step = failedStep(a)
        }
        if (interruption.isRunning) { // no panel any more: the recording is sent in the background
            when (state) {
                // DONE: the text comes right after it (commitText still needs to know about the interruption)
                VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.DONE -> return
                VoiceState.ERROR -> {
                    interruption.onFailed()
                    controller.cancel() // nobody sees the error now; it is told when the keyboard is back
                    return
                }
                else -> {
                    interruption.onEnded()
                    if (ime.isInputViewShown) handler.post { showInterruptionResult(ime.currentInputEditorInfo) }
                }
            }
        }
        when (state) {
            VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.ERROR -> {
                val p = ensurePanel()
                if (p == null) {
                    // never listen without the visible panel, and never silently (stage 5zh)
                    attempt?.notStarted = "no_panel"
                    Toast.makeText(ime, R.string.voice_error_no_panel, Toast.LENGTH_LONG).show()
                    controller.cancel()
                    return
                }
                p.setLabelLocale(currentLocale())
                p.setOpenAi(useOpenAi)
                p.setContinuous(continuousOn)
                p.setContinuousUnavailable(continuousUnavailable)
                p.setJournalAllowed(helium314.keyboard.latin.ai.DeveloperMode.on(ime.prefs()))
                p.setMic(micHeadset, micFellBack, micMissing)
                p.setBluetoothConnecting(micConnecting, headsetCar)
                p.setCarPaused(muteSession?.let { !it.endRequested } == true) // stage 5g-26: "🚗 music paused" while the call holds
                p.setNotice(notice, noticeSwitched)
                p.setSignal(signalPeak, signalSource)
                p.update(state, partial, error)
                registerScreenOff()
            }
            else -> {
                if (state == VoiceState.IDLE) {
                    attempt?.let { if (it.stop == null && it.inserted == null && it.error == null && it.started != null) it.stop = "cancel" }
                    attempt?.let { noteChunks(it) }
                    afterDictation()
                    finishAttempt()
                    screen.ended() // stage 5g-20: 5 s more after a text went in
                    nextPos = -1
                    clipped.setLength(0)
                    unwatchNetwork()
                    singleEngine = null
                }
                useOpenAi = false; notice = null; noticeSwitched = false; signalPeak = null; signalSource = null
                continuousOn = false; continuousEngine = null; continuousUnavailable = false
                micHeadset = null; micFellBack = false; micMissing = false; micConnecting = false; headsetEager = false; headsetProven = false; headsetCar = false
                activeRecorder = null
                removePanel()
                updateMicKey(ime.currentInputEditorInfo) // minutes, ceiling or a pause may have changed
                if (!autoStart.isPending) unregisterScreenOff()
            }
        }
    }

    override fun onLevel(level: Float) { panel?.setLevel(level) }

    // VoiceInputView.Actions

    /** the circle or "Retry": after an error the old attempt goes to the journal when idle, the retry is a new one */
    override fun onMic() = controller.onMicPressed()
    override fun onDone() {
        if (controller.state == VoiceState.LISTENING) attempt?.let { it.stop = it.stop ?: "done" }
        controller.finish()
    }
    override fun onCancel() {
        attempt?.let { it.stop = it.stop ?: "cancel" }
        controller.cancel()
    }
    /** the "Journal" link under an error: the voice diagnostics, in the settings (no new permission) */
    override fun onOpenJournal() {
        ime.requestHideSelf(0)
        try {
            ime.startActivity(android.content.Intent(ime, helium314.keyboard.settings.SettingsActivity2::class.java)
                .setAction(helium314.keyboard.settings.SettingsActivity.ACTION_OPEN_VOICE_LOG)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "can't open the voice journal")
        }
    }

    /** OpenAI could not be reached: the AI screen, where the card with the proxy and the check waits */
    override fun onOpenConnection() {
        ime.requestHideSelf(0)
        try {
            ime.startActivity(android.content.Intent(ime, helium314.keyboard.settings.SettingsActivity2::class.java)
                .setAction(helium314.keyboard.settings.SettingsActivity.ACTION_OPEN_AI)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "can't open the AI settings")
        }
    }

    /** continuous dictation: the failed phrases are sent again */
    override fun onRetryPhrase() { continuousEngine?.retryFailed() ?: singleEngine?.retryNow() }

    /**
     * stage 5g-20: pieces wait for the network: a quiet line on the panel ("waiting for the network…"), "Retry" after
     * 2 min; the dictation goes on. The network coming back (any network, a VPN too) sends them again at once.
     */
    override fun onNetwork(waiting: Boolean, askRetry: Boolean) {
        panel?.setNetwork(waiting, askRetry)
        if (waiting) watchNetwork() else unwatchNetwork()
    }

    private fun watchNetwork() {
        if (networkCallback != null || android.os.Build.VERSION.SDK_INT < 24) return
        val cm = ime.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { handler.post { networkBack() } }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post { networkBack() }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); networkCallback = cb } catch (e: Exception) { Log.w(TAG, "no network callback") }
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try { ime.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }

    private fun networkBack() {
        continuousEngine?.networkBack()
        singleEngine?.networkBack()
    }

    /** stage 5g-21: the call of the probe of this dictation; stage 5g-22: asked for, placed at the first frame */
    private var muteSession: CarMuteCall.Session? = null
    private var mutePending = false
    /** stage 5g-22: the recorder of this dictation until it is in the journal (activeRecorder goes at DONE already) */
    private var lastRecorder: PcmRecorder? = null

    private fun startMuteCall(fromTouch: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val s = CarMuteCall.start(ime)
        s.fromTouch = fromTouch
        muteSession = s
        panel?.setCarPaused(true)
        handler.removeCallbacks(muteWatchdog)
        handler.postDelayed(muteWatchdog, WATCHDOG_MS)
        // does Android bring up SCO for the call by itself? (then the phone goes deaf as before)
        for (at in longArrayOf(500, 1_000, 2_000)) handler.postDelayed({
            val am = ime.getSystemService(android.media.AudioManager::class.java)
            val type = if (android.os.Build.VERSION.SDK_INT >= 31) try { am?.communicationDevice?.type } catch (e: Exception) { null } else null
            val sco = type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            if (s.sco != true) s.sco = sco
            s.log("at $at ms: mode ${am?.mode}, communication device type $type")
        }, at)
    }

    /**
     * stage 5g-22: after the call: when the phone gives up the Bluetooth call device and the call mode (the radio can
     * only take its music back then); written into the entry of the dictation later
     */
    private fun watchAfterCall(a: VoiceAttempt, m: CarMuteCall.Session) {
        val end = if (m.endedAt >= 0) m.endedAt else System.currentTimeMillis()
        var said = false
        for (at in longArrayOf(500, 1_500, 3_000, 5_000)) handler.postDelayed({
            if (said) return@postDelayed
            val am = ime.getSystemService(android.media.AudioManager::class.java)
            val type = if (android.os.Build.VERSION.SDK_INT >= 31) try { am?.communicationDevice?.type } catch (e: Exception) { null } else null
            val bt = type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            val inCall = am?.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || am?.mode == android.media.AudioManager.MODE_IN_CALL
            val ms = System.currentTimeMillis() - end
            // the warm link holds the car on purpose: then the radio stays muted, that is no hanging call
            val note = when {
                warm.active -> "after call: warm link holds the car ($ms ms)"
                !bt && !inCall -> "car may resume media after $ms ms (no bluetooth call device, mode ${am?.mode})"
                at == 5_000L -> "after call: still mode ${am?.mode}, device type $type at $ms ms"
                else -> null
            } ?: return@postDelayed
            said = true
            a.carMute = (a.carMute ?: "") + "; " + note
            journal.changed()
        }, at)
    }

    /** the phrase is over (or the car is asked for in a pause): the call ends, once ([by] goes to the journal) */
    private fun endMuteCall(by: String) {
        val s = muteSession ?: return
        handler.removeCallbacks(muteGiveUp); muteTouchPending = false
        panel?.setCarPaused(false)
        if (s.endRequested) return
        CarMuteCall.end(by)
    }

    /**
     * stage 5g-26: a call never hangs: every second, a call with no dictation running (and no tap awaited) ends; a call
     * never lasts longer than [MAX_CALL_MS]
     */
    private val muteWatchdog = object : Runnable {
        override fun run() {
            val s = muteSession ?: return
            if (s.endRequested) return
            val idle = !controller.isActive && !muteTouchPending && !interruption.isRunning
            if (idle) { endMuteCall("watchdog"); return }
            if (System.currentTimeMillis() - s.startedAt > MAX_CALL_MS) { endMuteCall("watchdog (too long)"); return }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /**
     * stage 5g-21: one phrase from the phone with a car-like device, text in: the car's link is warmed for the next one;
     * a dictation on the warm car holds it anew (or lets it go when the car did not hear)
     */
    private fun afterDictation() {
        val a = attempt
        mutePending = false
        val rec = lastRecorder
        lastRecorder = null
        handler.removeCallbacks(muteWatchdog)
        panel?.setCarPaused(false)
        muteSession?.let { m ->
            CarMuteCall.end("phrase end")
            a?.carMute = m.summary(rec?.earlyPeakDbfs)
            a?.carMuteSystem = m.system()
            CarMuteCall.clear()
            a?.let { watchAfterCall(it, m) }
        }
        muteSession = null
        val inserted = a?.inserted == "yes" || a?.inserted == "later"
        val holdMs = CarWarmLink.holdMs(ime.prefs())
        if (onWarm) {
            val r = rec
            val rejected = r != null && (r.headsetRejected || r.headsetFellBack)
            if (inserted && !rejected) { warmAttempt = a; a?.let { it.car = (it.car ?: "") + "; held again" } }
            warm.endUse(inserted && !rejected, if (rejected) CarWarmLink.REASON_REJECTED else "no text")
        } else if (CarWarmLink.shouldWarm(carPlan, false, inserted, holdMs) && !continuousOn &&
            !(CarMuteCall.enabled(ime.prefs()) && android.os.Build.VERSION.SDK_INT >= 26)) { // stage 5g-26: the quiet call instead
            val (info, key) = warmCandidate ?: return
            val car = AndroidHeadsetMic(ime, info, modeAfterLink = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            if (warm.warmUp(car, key, holdMs)) {
                warmAttempt = a
                a?.let { it.car = (it.car ?: "") + "; warm up after phrase" }
                Log.i(TAG, "car: warm link for ${holdMs / 1000} s")
            }
        }
        onWarm = false; warmCandidate = null; carPlan = MicRouting.CarPlan.NONE
    }

    /** stage 5g-20: the pieces and retries of this dictation, for the journal */
    private fun noteChunks(a: VoiceAttempt) {
        continuousEngine?.let { a.chunks = it.chunks; a.retried = it.retried; a.keptForRetry = it.keptForRetry }
        singleEngine?.let { a.chunks = it.chunks; a.retried = it.retried; a.keptForRetry = it.keptForRetry }
    }

    /** stage 5g-20: a paused player may still sound for a moment: muted unless it is speech */
    /** stage 5g-24: with the sound of the touch, the player played until the tap: that time is covered too */
    private fun muteStartMs() = if (!focus.held) 0L
        else PcmRecorder.MUTE_START_MS + (pre?.let { (System.currentTimeMillis() - it.touchedAt).coerceIn(0, PreCapture.MAX_MS) } ?: 0L)

    private fun currentLocale(): Locale = RichInputMethodManager.getInstance().currentSubtype.locale

    /** the layout was switched: if the panel is open its labels follow the new layout */
    fun onLayoutChanged() { panel?.setLabelLocale(currentLocale()) }

    private fun ensurePanel(): VoiceInputView? {
        val wrapper = ime.keyboardWrapperForVoice ?: return null
        panel?.let { if (it.parent === wrapper) return it }
        removePanel()
        val p = VoiceInputView(wrapper.context, Settings.getValues().mColors, this)
        // explicit height = the keys area measured now; MATCH_PARENT would stretch the keyboard window over the whole screen
        val height = keysHeight(wrapper)
        if (height <= 0) return null
        wrapper.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
        panel = p
        return p
    }

    private fun keysHeight(wrapper: ViewGroup): Int {
        val keys = KeyboardSwitcher.getInstance().visibleKeyboardView
        return keys?.height?.takeIf { it > 0 } ?: keys?.measuredHeight ?: 0
    }

    private fun removePanel() {
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
    }

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { resetAutoStart(); interrupt(Interruption.SCREEN_OFF) }
        }
        ContextCompat.registerReceiver(ime, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOffReceiver = r
    }

    private fun unregisterScreenOff() {
        screenOffReceiver?.let { try { ime.unregisterReceiver(it) } catch (_: Exception) {} }
        screenOffReceiver = null
    }

    companion object {
        /** stage 5g-26: the watchdog of the quiet call looks every second; a call never lasts longer than 30 min */
        const val WATCHDOG_MS = 1_000L
        const val MAX_CALL_MS = 30 * 60_000L
        private const val CONTEXT_CHARS = 50 // stage 5g-17: text around the cursor looked at for the case of the dictated text
        private const val TAG = "VoiceInput"
        /** stage 5g-18: taps with a car-like device so far: the order of the probed sources and of the link */
        private const val PROBE_ROUND_PREF = "aikb_probe_round"
        private const val AUTO_START_DELAY_MS = 400L
        private val cleanupWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "aikb-cleanup").apply { isDaemon = true } }
        const val PREF_PERMISSION_ASKED = "aikb_voice_permission_asked"

        /**
         * From the settings (features screen): the same invisible activity as from the keyboard, in its own task,
         * so closing it returns to the settings. Listening never starts from here.
         */
        fun requestPermissionFromSettings(context: Context) {
            context.prefs().edit { putBoolean(PREF_PERMISSION_ASKED, true) }
            try {
                context.startActivity(Intent(context, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "can't show permission dialog")
            }
        }

        /**
         * What the microphone key does. Sensitive fields: nothing (the key is hidden there anyway).
         * Internal voice if it's switched on, a recognizer exists and we have (or may still ask for) the permission.
         * Otherwise the old behaviour.
         */
        fun decide(sensitive: Boolean, enabled: Boolean, available: Boolean, hasPermission: Boolean, askedBefore: Boolean): MicAction = when {
            sensitive -> MicAction.NONE
            !enabled || !available -> MicAction.FALLBACK
            hasPermission || !askedBefore -> MicAction.INTERNAL
            else -> MicAction.FALLBACK
        }
    }
}
