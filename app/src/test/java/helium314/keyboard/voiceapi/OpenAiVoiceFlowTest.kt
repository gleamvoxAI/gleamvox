// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.Cancellable
import helium314.keyboard.latin.voice.Scheduler
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceHost
import helium314.keyboard.latin.voice.VoiceInputController
import helium314.keyboard.latin.voice.VoiceInputView
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.latin.voice.VoiceTrace
import helium314.keyboard.latin.voiceapi.FallbackSpeechEngine
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.OpenAiSpeechEngine
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole dictation with OpenAI on plain JVM: synthetic sound → end of speech → one request (fake) → text inserted;
 * errors with one switch to the system recognizer; cancel. The recorder thread runs in place, callbacks are queued.
 */
class OpenAiVoiceFlowTest {
    private class FakeSystemEngine : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var cancelled = false
        override fun start(languageTag: String, listener: SpeechEngine.Listener) { this.listener = listener; listener.onListening() }
        override fun stop() = Unit
        override fun cancel() { cancelled = true }
        override fun destroy() = Unit
    }

    private class NoTimers : Scheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) = Cancellable { }
    }

    private inner class Host : VoiceHost {
        val states = mutableListOf<VoiceState>()
        val committed = mutableListOf<String>()
        val notices = mutableListOf<Pair<VoiceApiProblem?, Boolean>>()
        var lastError: VoiceError? = null
        override fun hasPermission() = true
        override fun requestPermission() = Unit
        override fun languageTag() = "ru-RU"
        override fun commitText(text: String) { committed.add(text) }
        override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) { states.add(state); lastError = error }
        override fun onLevel(level: Float) = Unit
        override fun onNotice(problem: VoiceApiProblem?, switched: Boolean) { notices.add(problem to switched) }
        var signal: Pair<Double?, String?>? = null
        override fun onSignal(peakDbfs: Double?, source: String?) { signal = peakDbfs to source }
        val traces = mutableListOf<VoiceTrace>()
        override fun onTrace(trace: VoiceTrace) { traces.add(trace) }
    }

    private val posted = ArrayDeque<Runnable>()
    private val background = ArrayDeque<Runnable>()
    private var runBackgroundAtOnce = true
    private var result: TranscriptionResult = TranscriptionResult.Ok("Привет, как дела?", 3.2)
    private var transcribeCalls = 0
    private var sentLanguage = ""
    private var sentBytes = 0
    private var sentPeakDbfs = 0.0
    private val results = mutableListOf<Pair<TranscriptionResult, Long>>()
    private var recorder: PcmRecorder? = null
    private val systemEngines = mutableListOf<FakeSystemEngine>()
    private var canFallBack = true
    private lateinit var host: Host
    private lateinit var controller: VoiceInputController

    private val speech = listOf(0.4 to false, 1.5 to true, 3.0 to false)
    private val silence = listOf(10.0 to false)

    private fun drain() {
        while (posted.isNotEmpty() || (runBackgroundAtOnce && background.isNotEmpty())) {
            if (runBackgroundAtOnce && background.isNotEmpty()) background.removeFirst().run()
            else posted.removeFirst().run()
        }
    }

    private fun engine(source: () -> PcmSource): SpeechEngine {
        val openAi = OpenAiSpeechEngine(
            recorderFactory = { l ->
                PcmRecorder({ _ -> source() }, Loudness.SOURCE_MIC, 120_000, l, { posted.add(it) },
                    threadFactory = { r -> object : Thread() { override fun start() = r.run() } }).also { recorder = it }
            },
            transcribe = { audio, language, _, onSent ->
                transcribeCalls++; sentLanguage = language; sentBytes = audio.byteCount
                onSent()
                sentPeakDbfs = Loudness.dbfs(audio.peak().toDouble())
                result
            },
            background = { background.add(it) },
            post = { posted.add(it) },
            onResult = { r, ms -> results.add(r to ms) },
            recordMs = 120_000,
        )
        return FallbackSpeechEngine(openAi, { FakeSystemEngine().also { systemEngines.add(it) } }, { canFallBack })
    }

    private fun startWith(sound: List<Pair<Double, Boolean>>) = startWith { SyntheticSource(sound) }

    private fun startWith(source: () -> PcmSource) {
        host = Host()
        controller = VoiceInputController({ engine(source) }, host, NoTimers())
        controller.onMicPressed()
        drain()
    }

    @BeforeTest fun reset() { posted.clear(); background.clear() }

    /** stage 5zh: every step reaches the journal and the panel status */
    @Test fun stepsOfASuccessfulDictation() {
        startWith(speech)
        val t = host.traces
        assertEquals("MIC", (t[0] as VoiceTrace.RecordingStarted).source)
        val rec = t.filterIsInstance<VoiceTrace.Recorded>().single()
        assertTrue(rec.speech && rec.end == "pause" && rec.peakDbfs > -30, "${rec.end} ${rec.peakDbfs}")
        val up = t.filterIsInstance<VoiceTrace.Uploading>().single()
        assertTrue(up.bytes in 100_000L..120_000L, "${up.bytes}")
        assertTrue(VoiceTrace.Sent in t)
        assertEquals(200, t.filterIsInstance<VoiceTrace.Answer>().single().status)
        assertTrue(t.indexOf(up) < t.indexOf(VoiceTrace.Sent))
    }

    private fun failingSource(f: PcmSource.Failure) = object : PcmSource {
        override fun start() = false
        override fun read(buffer: ShortArray) = -1
        override fun stop() = Unit
        override val failure = f
    }

    @Test fun microphoneTakenIsSaidAtOnce() {
        startWith { failingSource(PcmSource.Failure.BUSY) }
        assertEquals(VoiceError.MIC_BUSY, host.lastError)
        assertEquals("busy", (host.traces.single() as VoiceTrace.RecordingFailed).reason)
        assertEquals(0, transcribeCalls)
        assertTrue(systemEngines.isEmpty(), "not an OpenAI problem: no switch to the system recognizer")
        assertTrue(VoiceError.MIC_BUSY in VoiceInputController.STAYING_ERRORS, "stays on the panel until the user acts")
    }

    @Test fun noPermissionIsSaidAtOnce() {
        startWith { failingSource(PcmSource.Failure.PERMISSION) }
        assertEquals(VoiceError.PERMISSION, host.lastError)
        assertEquals("permission", (host.traces.single() as VoiceTrace.RecordingFailed).reason)
    }

    @Test fun silencedMicrophoneIsToldAfterTwoSecondsPerSourceNotEight() {
        var reads = 0L
        startWith { SyntheticSource(listOf(12.0 to false), noise = 0.0, onRead = { reads = it }) }
        assertEquals(VoiceError.MIC_SILENCED, host.lastError)
        assertEquals(0, transcribeCalls)
        val rec = host.traces.filterIsInstance<VoiceTrace.Recorded>().single()
        assertEquals("silenced", rec.end)
        assertTrue(reads <= 4_100, "both sources 2 s each: $reads ms")
    }

    @Test fun phraseIsRecognizedAndInserted() {
        startWith(speech)
        assertEquals(listOf("Привет, как дела?"), host.committed)
        assertEquals(listOf(VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.DONE, VoiceState.IDLE), host.states)
        assertEquals(1, transcribeCalls)
        assertEquals("ru-RU", sentLanguage)
        assertTrue(sentBytes in 99_000..108_000, "about 3.2 s of 16 kHz PCM16 (pause before sending 1.3 s, stage 5g-19): $sentBytes")
        assertEquals(0, recorder!!.audio.byteCount, "the sound is wiped after sending")
        assertEquals(1, results.size)
        assertTrue(systemEngines.isEmpty())
    }

    /** stage 5b: no silent closing; the panel says "didn't catch that" with the signal level and source */
    @Test fun noSpeechSendsNothingAndSaysSo() {
        startWith(silence)
        assertEquals(0, transcribeCalls)
        assertTrue(host.committed.isEmpty())
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
        val (peak, source) = host.signal!!
        assertEquals("MIC", source)
        assertTrue(peak!! < -30 && peak > -50, "noise peak $peak")
        assertTrue(results.isEmpty())
    }

    /**
     * stage 5b: Done sends (at least 0.6 s, above -70 dBFS), even if the detector heard no full phrase; quiet sound is
     * raised. Stage 5g-6: only if some speech was heard in it (syllables with pauses, as real speech has them)
     */
    @Test fun manualStopSendsQuietRecordingWithGain() {
        startWith { SyntheticSource(syllables(3.0), noise = 0.3, tone = amplitudeOf(-63.0), onRead = { ms -> if (ms >= 1000) controller.finish() }) }
        assertEquals(1, transcribeCalls)
        assertEquals(listOf("Привет, как дела?"), host.committed)
        assertTrue(sentPeakDbfs > -34 && sentPeakDbfs < -32, "-63 dBFS raised by 30 dB at most: $sentPeakDbfs")
    }

    @Test fun manualStopOfSilenceSendsNothing() {
        startWith { SyntheticSource(listOf(3.0 to false), noise = amplitudeOf(-90.0), onRead = { ms -> if (ms >= 1000) controller.finish() }) }
        assertEquals(0, transcribeCalls)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
        assertTrue(host.signal!!.first!! < -70)
    }

    @Test fun emptyAnswerSaysDidntCatchThat() {
        result = TranscriptionResult.Ok("  ", 1.0)
        startWith(speech)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
        assertEquals("MIC", host.signal!!.second)
    }

    @Test fun errorSwitchesToSystemOnceWithReason() {
        // stage 5g-20: the network, a server error and 429 are no reason to switch: the sound waits (test below)
        for (p in listOf(VoiceApiProblem.KEY_INVALID, VoiceApiProblem.NO_BALANCE,
                VoiceApiProblem.TOO_LARGE, VoiceApiProblem.BAD_REQUEST)) {
            systemEngines.clear()
            result = TranscriptionResult.Failed(p, 400)
            startWith(speech)
            assertEquals(VoiceState.LISTENING, controller.state, "$p")
            assertEquals(listOf<Pair<VoiceApiProblem?, Boolean>>(p to true), host.notices, "$p")
            assertEquals(1, systemEngines.size)
            systemEngines.single().listener!!.onFinal("системный текст")
            assertEquals(listOf("системный текст"), host.committed)
            assertEquals(VoiceState.IDLE, controller.state)
        }
    }

    /** stage 5g-20: no network: no switch, no error; the sound is kept and sent again */
    @Test fun networkFailureWaitsInsteadOfSwitching() {
        for (p in listOf(VoiceApiProblem.NETWORK, VoiceApiProblem.SERVER, VoiceApiProblem.RATE_LIMIT)) {
            systemEngines.clear()
            result = TranscriptionResult.Failed(p, 503)
            startWith(speech)
            assertEquals(VoiceState.PROCESSING, controller.state, "$p")
            assertTrue(systemEngines.isEmpty(), "$p")
            assertTrue(host.committed.isEmpty())
            controller.cancel()
        }
    }

    @Test fun noSecondSwitch() {
        result = TranscriptionResult.Failed(VoiceApiProblem.BAD_REQUEST, 400)
        startWith(speech)
        systemEngines.single().listener!!.onError(VoiceError.NETWORK)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(1, systemEngines.size)
    }

    @Test fun withoutSystemRecognizerTheOpenAiErrorIsShown() {
        canFallBack = false
        result = TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401)
        startWith(speech)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.OPENAI, host.lastError)
        assertEquals(listOf<Pair<VoiceApiProblem?, Boolean>>(VoiceApiProblem.KEY_INVALID to false), host.notices)
        assertTrue(host.committed.isEmpty())
    }

    @Test fun cancelWhileRecognizingSendsNothingAndWipes() {
        runBackgroundAtOnce = false
        startWith(speech)
        assertEquals(VoiceState.PROCESSING, controller.state)
        controller.cancel()
        while (background.isNotEmpty()) background.removeFirst().run()
        drain()
        assertEquals(0, transcribeCalls)
        assertTrue(host.committed.isEmpty())
        assertEquals(0, recorder!!.audio.byteCount)
        assertEquals(VoiceState.IDLE, controller.state)
    }

    @Test fun answersChangeTheNextDictations() {
        val prefs = MemoryPrefs()
        val now = 1_760_000_000_000L
        VoiceApi.applyResult(prefs, TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401), 3000, now)
        assertTrue(VoiceApiPrefs.keyRejected(prefs))
        VoiceApi.applyResult(prefs, TranscriptionResult.Failed(VoiceApiProblem.NO_BALANCE, 429), 3000, now)
        assertEquals(VoiceApiProblem.PAUSED_BALANCE, VoiceApi.pausedProblem(prefs, now + 60_000))
        assertNull(VoiceApi.pausedProblem(prefs, now + VoiceApi.BALANCE_PAUSE_MS + 1))
        VoiceApi.applyResult(prefs, TranscriptionResult.Failed(VoiceApiProblem.RATE_LIMIT, 429), 3000, now)
        assertEquals(VoiceApiProblem.PAUSED_RATE, VoiceApi.pausedProblem(prefs, now + 1000))
        assertNull(VoiceApi.pausedProblem(prefs, now + VoiceApi.RATE_PAUSE_MS + 1))
        // refused requests are not counted, transcriptions are
        assertEquals(0L, VoiceApiUsage(prefs, { now }).usedMs())
        VoiceApiUsage(prefs, { now }).setCapMinutes(1)
        assertTrue(VoiceApi.applyResult(prefs, TranscriptionResult.Ok("x", 50.0), 50_000, now), "80 percent of 1 minute: warn")
        assertEquals(50_000L, VoiceApiUsage(prefs, { now }).usedMs())
        assertFalse(VoiceApi.applyResult(prefs, TranscriptionResult.Ok("x", 5.0), 5_000, now))
    }
}

/** the voice panel with OpenAI: label, reason line and error in the language of the layout */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class OpenAiVoicePanelTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val noActions = object : VoiceInputView.Actions {
        override fun onMic() = Unit
        override fun onDone() = Unit
        override fun onCancel() = Unit
    }

    private fun texts(root: View): List<String> =
        (if (root is TextView && root.visibility == View.VISIBLE) listOf(root.text.toString()) else emptyList()) +
            ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())

    private fun panel(layout: Locale): VoiceInputView {
        Settings.getInstance().loadSettings(latinIME)
        val config = Configuration(latinIME.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return VoiceInputView(latinIME.createConfigurationContext(config), Settings.getValues().mColors, noActions)
            .also { it.setLabelLocale(layout) }
    }

    @Test fun listeningAndRecognizingWithOpenAi() {
        val p = panel(Locale.forLanguageTag("ru"))
        p.setOpenAi(true)
        p.update(VoiceState.LISTENING, "", null)
        assertTrue("Слушаю · русский · OpenAI" in texts(p), texts(p).toString())
        p.update(VoiceState.PROCESSING, "", null)
        assertTrue("Распознаю через OpenAI…" in texts(p), texts(p).toString())
    }

    @Test fun switchedToSystemShowsTheReason() {
        val p = panel(Locale.forLanguageTag("ru"))
        p.setOpenAi(true)
        p.update(VoiceState.PROCESSING, "", null)
        p.setNotice(VoiceApiProblem.KEY_INVALID, true)
        p.update(VoiceState.LISTENING, "", null)
        val t = texts(p)
        assertTrue("OpenAI: OpenAI отверг ключ API (401). Повторите: системное распознавание" in t, t.toString())
        assertTrue("Слушаю · русский · системное" in t, "the system recognizer after the switch: $t")
    }

    @Test fun reasonWhenOpenAiCantBeUsed() {
        val p = panel(Locale.ENGLISH)
        p.setNotice(VoiceApiProblem.INTERNET_OFF, false)
        p.update(VoiceState.LISTENING, "", null)
        assertTrue("OpenAI: internet access is off in AI Keyboard. Android recognition" in texts(p), texts(p).toString())
    }

    @Test fun didntCatchThatWithSignalLevelAndRetry() {
        val p = panel(Locale.forLanguageTag("ru"))
        p.setOpenAi(true)
        p.setSignal(-76.4, "MIC")
        p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH)
        val t = texts(p)
        assertTrue("Не расслышал. Говорите ближе к микрофону" in t, t.toString())
        assertTrue("Уровень сигнала: пик −76 dBFS, источник MIC" in t, t.toString())
        assertTrue("Повторить" in t && "Отмена" in t, t.toString())
    }

    @Test fun didntCatchThatWithTheSystemRecognizer() {
        val p = panel(Locale.ENGLISH)
        p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH)
        val t = texts(p)
        assertTrue("Didn't catch that. Speak closer to the microphone" in t, t.toString())
        assertTrue("Recognizer: Android (system)" in t && "Retry" in t, t.toString())
    }

    @Test fun engineIsNamedNextToListening() {
        val p = panel(Locale.ENGLISH)
        p.update(VoiceState.LISTENING, "", null)
        assertTrue("Listening · English · system" in texts(p), texts(p).toString())
    }

    @Test fun openAiError() {
        val p = panel(Locale.forLanguageTag("ru"))
        p.setNotice(VoiceApiProblem.NO_BALANCE, false)
        p.update(VoiceState.ERROR, "", VoiceError.OPENAI)
        val t = texts(p)
        assertTrue("OpenAI: не хватает денег на балансе OpenAI" in t, t.toString())
    }
}
