// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.voice.Cancellable
import helium314.keyboard.latin.voice.Scheduler
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceHost
import helium314.keyboard.latin.voice.VoiceInput
import helium314.keyboard.latin.voice.VoiceInput.MicAction
import helium314.keyboard.latin.voice.VoiceInputController
import helium314.keyboard.latin.voice.VoiceState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** States and exits of voice input (docs/VOICE.md) with a fake recognizer and a fake clock, plain JVM. */
class VoiceInputControllerTest {
    private class FakeEngine : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var language: String? = null
        var stopped = false
        var cancelled = false
        var destroyed = false
        override fun start(languageTag: String, listener: SpeechEngine.Listener) { language = languageTag; this.listener = listener }
        override fun stop() { stopped = true }
        override fun cancel() { cancelled = true }
        override fun destroy() { destroyed = true }
    }

    private class FakeClock : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
            val cancelled = booleanArrayOf(false)
            tasks.add(Triple(now + delayMs, action, cancelled))
            return Cancellable { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = tasks.filter { it.first <= now && !it.third[0] }.minByOrNull { it.first } ?: break
                tasks.remove(due)
                due.second()
            }
        }
    }

    private inner class FakeHost : VoiceHost {
        var permission = true
        var permissionRequests = 0
        val committed = mutableListOf<String>()
        val states = mutableListOf<VoiceState>()
        var lastError: VoiceError? = null
        var lastPartial = ""
        var cancelWhenShown = false
        var screen = true
        override fun hasPermission() = permission
        override fun screenOn() = screen
        override fun requestPermission() { permissionRequests++ }
        override fun languageTag() = "ru"
        override fun commitText(text: String) { committed.add(text) }
        override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) {
            states.add(state); lastError = error; lastPartial = partial
            if (cancelWhenShown && state == VoiceState.LISTENING) controller.cancel()
        }
        override fun onLevel(level: Float) = Unit
    }

    private val engines = mutableListOf<FakeEngine>()
    private val clock = FakeClock()
    private val log = mutableListOf<String>()
    private lateinit var host: FakeHost
    private lateinit var controller: VoiceInputController
    private val engine get() = engines.last()

    @BeforeTest fun setUp() {
        host = FakeHost()
        controller = VoiceInputController({ FakeEngine().also { engines.add(it) } }, host, clock) { log.add(it) }
    }

    /** stage 5zh: never record with the screen off; a recording interrupted by the screen is finished and sent */
    @Test fun noRecordingWhileTheScreenIsOff() {
        host.screen = false
        controller.onMicPressed()
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(engines.isEmpty())
        assertTrue("screen off: not started" in log)
    }

    @Test fun interruptedRecordingIsFinishedAndSentNotDropped() {
        controller.onMicPressed()
        engine.listener!!.onPartial("встреча в")
        controller.finish() // what VoiceInput does when the screen goes off while recording
        assertTrue(engine.stopped && !engine.cancelled, "stopped, the result follows")
        engine.listener!!.onFinal("Встреча в пять.")
        assertEquals(listOf("Встреча в пять."), host.committed)
    }

    @Test fun micStartsListeningInKeyboardLanguage() {
        controller.onMicPressed()
        assertEquals(VoiceState.LISTENING, controller.state)
        assertEquals("ru", engine.language)
    }

    @Test fun secondMicPressStopsAndInsertsText() {
        controller.onMicPressed()
        engine.listener!!.onPartial("привет")
        assertEquals("привет", host.lastPartial)
        controller.onMicPressed()
        assertEquals(VoiceState.PROCESSING, controller.state)
        assertTrue(engine.stopped)
        engine.listener!!.onFinal("привет мир")
        assertEquals(listOf("привет мир"), host.committed)
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(VoiceState.DONE in host.states)
        assertEquals("", controller.partial)
        assertTrue(engine.destroyed)
    }

    @Test fun doneButtonStopsAndInsertsText() {
        controller.onMicPressed()
        controller.finish()
        assertTrue(engine.stopped)
        engine.listener!!.onFinal("готово")
        assertEquals(listOf("готово"), host.committed)
        assertEquals(VoiceState.IDLE, controller.state)
    }

    @Test fun endOfSpeechInsertsText() {
        controller.onMicPressed()
        engine.listener!!.onEndOfSpeech()
        assertEquals(VoiceState.PROCESSING, controller.state)
        engine.listener!!.onFinal("конец речи")
        assertEquals(listOf("конец речи"), host.committed)
        assertEquals(VoiceState.IDLE, controller.state)
    }

    @Test fun emptyFinalUsesLastPartial() {
        controller.onMicPressed()
        engine.listener!!.onPartial("частично")
        controller.finish()
        engine.listener!!.onFinal("")
        assertEquals(listOf("частично"), host.committed)
    }

    @Test fun cancelInsertsNothingAndIgnoresLateResults() {
        controller.onMicPressed()
        val e = engine
        e.listener!!.onPartial("секрет")
        controller.cancel()
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(e.cancelled && e.destroyed)
        e.listener!!.onFinal("секрет") // late callback of the old engine
        assertTrue(host.committed.isEmpty())
        assertEquals("", controller.partial)
    }

    @Test fun autoStopAfter60Seconds() {
        controller.onMicPressed()
        clock.advance(59_000)
        assertEquals(VoiceState.LISTENING, controller.state)
        clock.advance(1_000)
        assertEquals(VoiceState.PROCESSING, controller.state)
        assertTrue(engine.stopped)
        engine.listener!!.onFinal("долго")
        assertEquals(listOf("долго"), host.committed)
    }

    @Test fun noResultAfterStopShowsErrorThenCloses() {
        controller.onMicPressed()
        controller.finish()
        clock.advance(VoiceInputController.RESULT_TIMEOUT_MS)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.TIMEOUT, controller.error)
        clock.advance(VoiceInputController.ERROR_SHOWN_MS)
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(host.committed.isEmpty())
    }

    /** stage 5b: never a silent close; "didn't catch that" stays until Retry or Cancel */
    @Test fun nothingSaidShowsDidntCatchThatAndStays() {
        controller.onMicPressed()
        engine.listener!!.onError(VoiceError.NO_SPEECH)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
        clock.advance(VoiceInputController.ERROR_SHOWN_MS * 10)
        assertEquals(VoiceState.ERROR, controller.state, "no automatic closing")
        assertTrue(host.committed.isEmpty())
        controller.onMicPressed() // Retry
        assertEquals(VoiceState.LISTENING, controller.state)
    }

    @Test fun emptyFinalResultShowsDidntCatchThat() {
        controller.onMicPressed()
        engine.listener!!.onFinal("   ")
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
    }

    @Test fun nothingSaidWithPartialTextInsertsIt() {
        controller.onMicPressed()
        engine.listener!!.onPartial("привет")
        engine.listener!!.onError(VoiceError.NO_SPEECH)
        assertEquals(listOf("привет"), host.committed)
        assertEquals(VoiceState.IDLE, controller.state)
    }

    @Test fun languageErrorIsShownAndMicRetries() {
        controller.onMicPressed()
        engine.listener!!.onError(VoiceError.LANGUAGE_NOT_SUPPORTED)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.LANGUAGE_NOT_SUPPORTED, host.lastError)
        controller.onMicPressed()
        assertEquals(VoiceState.LISTENING, controller.state)
        assertEquals(2, engines.size)
    }

    @Test fun withoutPermissionAsksAndDoesNotListen() {
        host.permission = false
        controller.onMicPressed()
        assertEquals(VoiceState.REQUESTING_PERMISSION, controller.state)
        assertEquals(1, host.permissionRequests)
        assertTrue(engines.isEmpty())
        controller.onPermissionFlowEnded()
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(engines.isEmpty()) // listening never starts by itself
    }

    @Test fun neverListensWithoutPanel() {
        host.cancelWhenShown = true // host could not show the panel
        controller.onMicPressed()
        assertEquals(VoiceState.IDLE, controller.state)
        assertNull(engines.single().language) // engine was never started
    }

    @Test fun keyboardHiddenWhileProcessingInsertsNothing() {
        controller.onMicPressed()
        controller.finish()
        val e = engine
        controller.cancel() // keyboard hidden / other app / screen off
        e.listener!!.onFinal("поздно")
        assertTrue(host.committed.isEmpty())
        assertEquals(VoiceState.IDLE, controller.state)
    }

    @Test fun logNeverContainsText() {
        controller.onMicPressed()
        engine.listener!!.onPartial("тайный текст")
        controller.finish()
        engine.listener!!.onFinal("тайный текст целиком")
        controller.onMicPressed()
        engine.listener!!.onError(VoiceError.NETWORK)
        assertTrue(log.isNotEmpty())
        assertFalse(log.any { it.contains("тайный") })
    }

    @Test fun micKeyDecision() {
        // sensitive field: nothing at all (no fallback to another voice keyboard either)
        assertEquals(MicAction.NONE, VoiceInput.decide(sensitive = true, enabled = true, available = true, hasPermission = true, askedBefore = true))
        assertEquals(MicAction.INTERNAL, VoiceInput.decide(sensitive = false, enabled = true, available = true, hasPermission = true, askedBefore = true))
        // permission not asked yet: ask (internal)
        assertEquals(MicAction.INTERNAL, VoiceInput.decide(sensitive = false, enabled = true, available = true, hasPermission = false, askedBefore = false))
        // old behaviour: denied, switched off, or no recognizer
        assertEquals(MicAction.FALLBACK, VoiceInput.decide(sensitive = false, enabled = true, available = true, hasPermission = false, askedBefore = true))
        assertEquals(MicAction.FALLBACK, VoiceInput.decide(sensitive = false, enabled = false, available = true, hasPermission = true, askedBefore = true))
        assertEquals(MicAction.FALLBACK, VoiceInput.decide(sensitive = false, enabled = true, available = false, hasPermission = true, askedBefore = true))
    }
}
