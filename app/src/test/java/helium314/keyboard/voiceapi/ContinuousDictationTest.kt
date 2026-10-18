// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voice.Cancellable
import helium314.keyboard.latin.voice.PhraseJoin
import helium314.keyboard.latin.voice.RecordingFocus
import helium314.keyboard.latin.voice.Scheduler
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.VoiceAttempt
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceHost
import helium314.keyboard.latin.voice.VoiceInputController
import helium314.keyboard.latin.voice.VoiceInterruption
import helium314.keyboard.latin.voice.Interruption
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.latin.voice.VoiceTrace
import helium314.keyboard.latin.voiceapi.ContinuousSpeechEngine
import helium314.keyboard.latin.voiceapi.FallbackSpeechEngine
import helium314.keyboard.latin.voiceapi.HeadsetMic
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.MicCheck
import helium314.keyboard.latin.voiceapi.MicChoice
import helium314.keyboard.latin.voiceapi.MicDecision
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import helium314.keyboard.latin.voiceapi.TranscriptionClient
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.latin.voiceapi.micName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** a recording thread that runs in place: the whole recording happens inside start() */
private val inPlace: (Runnable) -> Thread = { r -> object : Thread() { override fun start() = r.run() } }

/** detector of a continuous dictation: pause 1.5 s ends a phrase, [silenceMs] without speech ends the dictation */
private fun detector(silenceMs: Int = 3000) = { SpeechEndDetector(endSilenceMs = 1500, noSpeechTimeoutMs = silenceMs) }

/**
 * Continuous dictation, stage 5g (docs/DESIGN.md, "Этап 5г"): the recorder cuts phrases at the pauses without sending
 * the silence between them; the session ends after the silence of the settings, at its limit, at the minutes left this
 * month, or by a tap. Plain JVM, synthetic sound.
 */
class ContinuousRecorderTest {
    private class Events : PcmRecorder.Listener {
        val phrases = mutableListOf<PcmBuffer>()
        val ends = mutableListOf<String>()
        val other = mutableListOf<String>()
        override fun onLevel(level: Float) = Unit
        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) { other.add("end") }
        override fun onNoSpeech() { other.add("no speech") }
        override fun onFailed() { other.add("failed") }
        override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) { phrases.add(audio) }
        override fun onContinuousEnd(reason: String) { ends.add(reason) }
        override fun onMic(headset: Boolean, fellBack: Boolean) { other.add("mic $headset $fellBack") }
    }

    private fun record(sound: List<Pair<Double, Boolean>>, maxMs: Long = 60_000, maxPhraseMs: Long = 120_000,
                       maxSpeechMs: Long = 60 * 60_000, silenceMs: Int = 3000, onRead: ((PcmRecorder, Long) -> Unit)? = null): Pair<PcmRecorder, Events> {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        var rec: PcmRecorder? = null
        val r = PcmRecorder({ SyntheticSource(sound, onRead = { ms -> onRead?.invoke(rec!!, ms) }) }, Loudness.SOURCE_MIC, maxMs, events,
            { posted.add(it) }, detectorFactory = detector(silenceMs), threadFactory = inPlace,
            continuous = PcmRecorder.Continuous(maxPhraseMs = maxPhraseMs, maxSpeechMs = maxSpeechMs))
        rec = r
        assertTrue(r.start())
        posted.forEach { it.run() }
        return r to events
    }

    /** two phrases with a pause between them; the dictation ends after 3 s without speech */
    private val twoPhrases = listOf(0.5 to false) + syllables(1.5) + listOf(2.5 to false) + syllables(2.0) + listOf(9.0 to false)

    @Test fun cutsPhrasesAtPausesWithoutTheSilenceBetween() {
        val (r, e) = record(twoPhrases)
        assertEquals(listOf("silence"), e.ends, "3 s without speech end the dictation")
        assertEquals(2, e.phrases.size)
        val (p1, p2) = e.phrases.map { it.durationMs }
        // the first phrase: 0.7 s before the speech is heard, its speech and the 1.5 s pause after it
        assertTrue(p1 in 3100L..3600L, "first phrase $p1 ms")
        // the second: 0.7 s before its speech is heard, then speech and the pause: never the whole gap
        assertTrue(p2 in 3500L..4000L, "second phrase $p2 ms")
        assertEquals(p1 + p2, r.phrasesMs)
        assertEquals(2, r.phrases)
        assertTrue(e.other.isEmpty(), e.other.toString())
    }

    /** minutes count only what is sent: the silence between the phrases and at the end is not in it */
    @Test fun minutesCountOnlyThePhrases() {
        val (r, e) = record(twoPhrases)
        val recorded = 0.5 + 1.5 + 2.5 + 2.0 + 1.5 + 3.0 // until the stop after 3 s of silence
        assertTrue(r.phrasesMs < recorded * 1000 - 2000, "${r.phrasesMs} ms sent of ${recorded * 1000} ms recorded")
        // what is sent is what is billed (no "usage" in the answer)
        assertEquals(e.phrases.sumOf { it.durationMs },
            e.phrases.sumOf { VoiceApiUsage.billedMs(TranscriptionResult.Ok("x", null), it.durationMs) })
    }

    @Test fun silenceOnlyEndsAfterTheSilenceOfTheSettings() {
        val (r, e) = record(listOf(20.0 to false), silenceMs = 10_000)
        assertEquals(listOf("silence"), e.ends)
        assertEquals(0, e.phrases.size)
        assertEquals(0L, r.phrasesMs)
    }

    @Test fun sessionLimitEndsIt() {
        val (r, e) = record(syllables(20.0), maxMs = 6000)
        assertEquals(listOf("limit"), e.ends)
        assertTrue(e.phrases.isNotEmpty(), "what was said before the limit is handed over")
        assertTrue(r.phrasesMs <= 6000)
    }

    @Test fun monthlyMinutesEndIt() {
        val (r, e) = record(syllables(10.0), maxSpeechMs = 2500)
        assertEquals(listOf("cap"), e.ends)
        assertTrue(r.phrasesMs in 2000L..2540L, "${r.phrasesMs}")
    }

    @Test fun aVeryLongPhraseIsCut() {
        val (_, e) = record(syllables(5.0) + listOf(6.0 to false), maxPhraseMs = 2000)
        assertTrue(e.phrases.size >= 3, "${e.phrases.size} phrases")
        assertTrue(e.phrases.all { it.durationMs <= 2020 }, e.phrases.map { it.durationMs }.toString())
    }

    @Test fun stopByTheUserHandsOverTheLastPhrase() {
        val (_, e) = record(syllables(6.0) + listOf(6.0 to false), onRead = { r, ms -> if (ms >= 2500) r.stop() })
        assertEquals(listOf("done"), e.ends)
        assertEquals(1, e.phrases.size)
        assertTrue(e.phrases[0].durationMs in 2000L..2600L)
    }

    @Test fun cancelHandsOverNothing() {
        val (_, e) = record(twoPhrases, onRead = { r, ms -> if (ms >= 5000) r.cancel() })
        assertTrue(e.phrases.isEmpty() && e.ends.isEmpty() && e.other.isEmpty(), "${e.phrases.size} ${e.ends} ${e.other}")
    }

    @Test fun detectorKeepsTheNoiseLevelForTheNextPhrase() {
        val d = SpeechEndDetector(endSilenceMs = 1500, noSpeechTimeoutMs = 3000)
        val (event, _) = detect(SyntheticSource(syllables(1.5) + listOf(2.0 to false)), d)
        assertEquals(SpeechEndDetector.Event.END_OF_SPEECH, event)
        d.nextPhrase()
        assertFalse(d.hadSpeech)
        val (next, at) = detect(SyntheticSource(listOf(5.0 to false)), d)
        assertEquals(SpeechEndDetector.Event.NO_SPEECH, next)
        assertEquals(3000L, at, "the time without speech counts from the last phrase")
    }

    @Test fun takeAllMovesTheSound() {
        val b = PcmBuffer()
        b.append(ShortArray(20_000) { 100 }, 20_000)
        val moved = b.takeAll()
        assertEquals(0, b.byteCount)
        assertEquals(40_000, moved.byteCount)
        assertEquals(100, moved.peak())
        b.append(ShortArray(10) { 5 }, 10)
        assertEquals(20, b.byteCount)
        assertEquals(40_000, moved.byteCount, "the moved sound is not touched")
    }
}

/**
 * The continuous engine with a fake OpenAI: at most 2 requests at once, insertion in the spoken order, the context of
 * the session, a failed phrase does not stop the others, 401 stops the dictation, "didn't catch that", cancel.
 */
class ContinuousEngineTest {
    private class FakeSystemEngine : SpeechEngine {
        var started = false
        override fun start(languageTag: String, listener: SpeechEngine.Listener) { started = true; listener.onListening() }
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private class NoTimers : Scheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) = Cancellable { }
    }

    private class Host : VoiceHost {
        val states = mutableListOf<VoiceState>()
        val committed = mutableListOf<String>()
        val progress = mutableListOf<Triple<Int, Int, Int>>()
        val notices = mutableListOf<Pair<VoiceApiProblem?, Boolean>>()
        val traces = mutableListOf<VoiceTrace>()
        var done = 0
        var lastError: VoiceError? = null
        override fun hasPermission() = true
        override fun requestPermission() = Unit
        override fun languageTag() = "ru-RU"
        override fun commitText(text: String) { committed.add(text) }
        override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) { states.add(state); lastError = error }
        override fun onLevel(level: Float) = Unit
        override fun onNotice(problem: VoiceApiProblem?, switched: Boolean) { notices.add(problem to switched) }
        override fun onProgress(phrases: Int, recognizing: Int, failed: Int) { progress.add(Triple(phrases, recognizing, failed)) }
        override fun onContinuousDone() { done++ }
        override fun onTrace(trace: VoiceTrace) { traces.add(trace) }
    }

    private val posted = ArrayDeque<Runnable>()
    private val background = mutableListOf<Runnable>()
    /** the context of each request, by the number of the request (in the order they were sent) */
    private val contexts = mutableMapOf<Int, String?>()
    private var sent = 0
    private var running = -1
    /** answers by the number of the request (0, 1, 2, …), in the order they were sent */
    private val answers = mutableMapOf<Int, TranscriptionResult>()
    private val billed = mutableListOf<Long>()
    private val systems = mutableListOf<FakeSystemEngine>()
    private var engine: ContinuousSpeechEngine? = null
    private lateinit var host: Host
    private lateinit var controller: VoiceInputController
    private var recorder: PcmRecorder? = null

    private val threePhrases = listOf(0.5 to false) + syllables(1.0) + listOf(2.0 to false) + syllables(1.0) + listOf(2.0 to false) +
        syllables(1.0) + listOf(6.0 to false)

    @BeforeTest fun reset() { posted.clear(); background.clear(); contexts.clear(); answers.clear(); billed.clear(); systems.clear(); sent = 0 }

    private fun drainPosted() { while (posted.isNotEmpty()) posted.removeFirst().run() }

    /** the request number [i] answers now (its result comes on the main thread) */
    private fun answer(i: Int) { background[i].run(); drainPosted() }

    private fun start(sound: List<Pair<Double, Boolean>>, onRead: ((Long) -> Unit)? = null) {
        host = Host()
        controller = VoiceInputController({
            val e = ContinuousSpeechEngine(
                recorderFactory = { l ->
                    PcmRecorder({ SyntheticSource(sound, onRead = onRead) }, Loudness.SOURCE_MIC, 300_000, l, { posted.add(it) },
                        detectorFactory = detector(), threadFactory = inPlace,
                        continuous = PcmRecorder.Continuous(120_000, 60 * 60_000)).also { recorder = it }
                },
                transcribe = { audio, _, context, cancel, _ ->
                    val i = running
                    contexts[i] = context
                    if (cancel.isCancelled) TranscriptionResult.Failed(VoiceApiProblem.CANCELLED)
                    else answers[i] ?: TranscriptionResult.Ok("Фраза ${i + 1}.", null).also { assertTrue(audio.byteCount > 0) }
                },
                background = { r -> val i = sent++; background.add(Runnable { running = i; r.run() }) },
                post = { posted.add(it) },
                onResult = { _, ms -> billed.add(ms) },
                sessionMs = 300_000,
            )
            engine = e
            FallbackSpeechEngine(e, { FakeSystemEngine().also { systems.add(it) } }, { true })
        }, host, NoTimers())
        controller.onMicPressed()
        drainPosted()
    }

    @Test fun atMostTwoRequestsAtOnceAndInsertedInOrder() {
        start(threePhrases)
        assertEquals(2, background.size, "three phrases recorded, only two sent")
        assertEquals(VoiceState.PROCESSING, controller.state, "the recording ended by itself (silence)")
        answer(1) // the second phrase answers first
        assertEquals(emptyList(), host.committed, "never before the first one")
        assertEquals(3, background.size, "a free place: the third phrase is sent")
        answer(0)
        assertEquals(listOf("Фраза 1.", "Фраза 2."), host.committed)
        answer(2)
        assertEquals(listOf("Фраза 1.", "Фраза 2.", "Фраза 3."), host.committed)
        assertEquals(1, host.done)
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(VoiceState.DONE in host.states)
        assertEquals(Triple(3, 0, 0), host.progress.last())
    }

    /** the prompt of a phrase gets only what was recognized before in this session */
    @Test fun contextIsOnlyTheTextOfTheSession() {
        start(threePhrases)
        answer(0)
        answer(1)
        answer(2)
        assertNull(contexts[0], "nothing before the first phrase")
        assertNull(contexts[1], "sent while the first was still recognized")
        assertEquals("Фраза 1.", contexts[2], "sent when the first phrase was in")
        // in the request: the fixed prompt, then the tail of that text; at most 200 characters of it
        val prompt = TranscriptionClient.formFields("ru-RU", "Фраза 1. Фраза 2.").first { it.first == "prompt" }.second
        assertEquals(TranscriptionClient.prompt("ru") + " Фраза 1. Фраза 2.", prompt)
        val long = "а".repeat(150) + " " + "б".repeat(150)
        val p2 = TranscriptionClient.promptWith("ru", long)
        assertTrue(p2.endsWith("б".repeat(150)) && p2.length <= TranscriptionClient.prompt("ru").length + 1 + 200)
        assertEquals(TranscriptionClient.prompt("en"), TranscriptionClient.promptWith("en", "  "), "no context: the fixed prompt only")
    }

    /** stage 5g-20: a phrase that failed (a server error) is kept and sent again; it keeps its place in the order */
    @Test fun aFailedPhraseIsSentAgainAndKeepsItsPlace() {
        answers[1] = TranscriptionResult.Failed(VoiceApiProblem.SERVER, 503)
        start(threePhrases)
        answer(1)
        assertEquals(Triple(0, 3, 0), host.progress.last(), "nothing is lost: three phrases still on their way")
        engine!!.retryFailed() // "Retry" (or the network back) while the others are still recognized
        answer(0)
        answer(2)
        answer(3)
        // request 3 is the second phrase sent again: its text comes second, the third phrase after it
        assertEquals(listOf("Фраза 1.", "Фраза 4.", "Фраза 3."), host.committed, "in the spoken order")
        assertEquals(1, engine!!.retried)
        assertEquals(1, host.done)
        assertEquals(VoiceState.IDLE, controller.state)
        assertTrue(systems.isEmpty(), "no switch to the system recognizer")
    }

    /** a phrase OpenAI can't take (a bad request: no sense in sending it again) is dropped, the rest is inserted */
    @Test fun aFailedPhraseWithoutRetryIsDroppedAndTheRestIsInserted() {
        answers[0] = TranscriptionResult.Failed(VoiceApiProblem.BAD_REQUEST, 400)
        start(threePhrases)
        answer(0); answer(1); answer(2)
        assertEquals(listOf("Фраза 2.", "Фраза 3."), host.committed)
        assertEquals(1, host.done)
        assertEquals(1, host.progress.last().third)
    }

    @Test fun wrongKeyStopsTheDictationAndSwitchesToTheSystemRecognizer() {
        answers[1] = TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401)
        start(threePhrases)
        answer(0)
        assertEquals(listOf("Фраза 1."), host.committed)
        assertEquals(3, background.size, "the third phrase went out after the first")
        answer(1)
        // stage 5g-20: the request still on its way is cancelled, the switch comes at once
        answer(2) // cancelled by the 401: nothing of it is inserted
        assertEquals(3, background.size, "nothing more is sent after 401")
        assertEquals(listOf("Фраза 1."), host.committed, "what was inserted stays")
        assertEquals(VoiceApiProblem.KEY_INVALID to true, host.notices.last(), "said and switched")
        assertEquals(1, systems.size)
        assertTrue(systems[0].started)
        assertEquals(VoiceState.LISTENING, controller.state, "the system recognizer listens once")
    }

    @Test fun nothingHeardIsDidNotCatchThat() {
        start(listOf(8.0 to false))
        assertEquals(0, background.size)
        assertEquals(VoiceState.ERROR, controller.state)
        assertEquals(VoiceError.NO_SPEECH, host.lastError)
    }

    @Test fun minutesAreBilledOnlyForThePhrases() {
        start(threePhrases)
        answer(0); answer(1); answer(2)
        assertEquals(recorder!!.phrasesMs, billed.sum())
        assertTrue(billed.sum() < 9_500, "${billed.sum()} ms of about 12 s recorded")
    }

    @Test fun tapStopsAndInsertsWhatWasSaid() {
        start(syllables(1.0) + listOf(2.0 to false) + syllables(4.0) + listOf(20.0 to false)) { ms ->
            if (ms == 4000L) controller.finish() // the circle while the second phrase is spoken
        }
        assertEquals(VoiceState.PROCESSING, controller.state)
        assertEquals(2, background.size, "the phrase being spoken is sent at once")
        answer(0); answer(1)
        assertEquals(listOf("Фраза 1.", "Фраза 2."), host.committed)
        assertEquals(VoiceState.IDLE, controller.state)
        assertEquals("done", host.traces.filterIsInstance<VoiceTrace.Recorded>().single().end)
    }

    @Test fun cancelDropsWhatIsNotInsertedYet() {
        start(threePhrases)
        answer(0)
        controller.cancel()
        answer(1)
        assertEquals(listOf("Фраза 1."), host.committed, "inserted stays, the rest is dropped")
        assertEquals(VoiceState.IDLE, controller.state)
    }
}

/** Joining phrases, an interrupted continuous dictation, the journal (stage 5g) */
class ContinuousJoinTest {
    @Test fun capitalAfterTheEndOfASentence() {
        assertEquals("Как дела?", PhraseJoin.firstLetter("Привет. ", "как дела?"))
        assertEquals("Как дела?", PhraseJoin.firstLetter("", "как дела?"), "start of the field")
        assertEquals("Как дела?", PhraseJoin.firstLetter("Привет!\n", "как дела?"))
        assertEquals("и ещё", PhraseJoin.firstLetter("купи хлеба", "и ещё"), "inside a sentence: as recognized")
        assertEquals("Маша придёт", PhraseJoin.firstLetter("да, ", "Маша придёт"), "a name keeps its capital")
        assertEquals("как дела?", PhraseJoin.firstLetter(null, "как дела?"), "unknown: as recognized")
        assertEquals("«Да»", PhraseJoin.firstLetter("Ну. ", "«да»"))
    }

    @Test fun phrasesAfterAnInterruptionWaitTogether() {
        val i = VoiceInterruption { 0L }
        assertEquals(VoiceInterruption.Action.FINISH, i.onInterrupt(VoiceState.LISTENING, Interruption.HIDDEN))
        assertFalse(i.onText("Первая фраза.", false, "field"))
        assertFalse(i.onText("Вторая фраза.", true, "field"), "behind a waiting phrase: waits too, the order stays")
        i.onEnded()
        val show = i.take("field") as VoiceInterruption.Show.InsertNow
        assertEquals("Первая фраза. Вторая фраза.", show.text)
    }

    @Test fun journalKeepsTheContinuousFields() {
        val a = VoiceAttempt(1000L).apply {
            engine = "openai"; mode = "continuous"; phrases = 4; failedPhrases = 1; mic = "headset→phone"; stop = "silence"; words = 30
            firstFrameMs = 120; headsetMs = 1500; btDevices = "B2: in sco, out sco+a2dp, media"; phoneBeforeDb = -50; phoneAfterDb = -6
        }
        val b = VoiceAttempt.fromJson(a.toJson())!!
        assertEquals("continuous", b.mode)
        assertEquals(4, b.phrases)
        assertEquals(1, b.failedPhrases)
        assertEquals("headset→phone", b.mic)
        assertEquals(120L, b.firstFrameMs)
        assertEquals(1500L, b.headsetMs)
        val line = b.line()
        assertTrue("continuous" in line && "phrases 4, failed 1" in line && "stop: silence" in line, line)
        assertTrue("first sound after 120 ms" in line && "headset tried 1500 ms" in line, line)
        assertTrue("bluetooth: B2: in sco, out sco+a2dp, media" in line && "phone -50 dBFS before the headset, -6 dBFS after" in line, line)
    }
}

/** A fake Bluetooth headset: what was asked for and given back */
class FakeHeadset(var connects: Boolean = true, var up: Boolean = true, override val deviceKey: String? = "7:Buds:12",
                  val sound: () -> PcmSource) : HeadsetMic {
    var connected = 0
    var released = 0
    private var taken = false
    /** stage 5g-7: told when the channel is asked for (what happens first) */
    var onConnect: (() -> Unit)? = null
    override fun connect(): Boolean { onConnect?.invoke(); connected++; taken = connects; return connects }
    override fun isUp() = up && taken
    override fun source() = sound()
    override fun release() { released++; taken = false }
    val holding get() = taken
}

/** the HONOR phone of the owner (stage 5g-3): its microphone is 30 dB quieter while a headset channel is held */
class HonorPhone(private val inner: SyntheticSource, private val headset: FakeHeadset) : PcmSource {
    override fun start() = inner.start()
    override fun read(buffer: ShortArray): Int {
        val n = inner.read(buffer)
        if (n > 0 && headset.holding) for (i in 0 until n) buffer[i] = (buffer[i] * 0.03).toInt().toShort()
        return n
    }
    override fun stop() = inner.stop()
    val readMs get() = inner.readMs
}

/**
 * Microphone of a Bluetooth headset (stage 5g … 5g-3): only a proven headset is tried; the phone records from the first
 * frame; the channel is asked for only in a quiet moment and let go at once when speech begins; on probation the
 * headset must hear what the phone hears, else the phone's sound is used (nothing lost) and the device is no headset.
 */
class HeadsetMicTest {
    private class Events : PcmRecorder.Listener {
        val list = mutableListOf<String>()
        val phrases = mutableListOf<PcmBuffer>()
        val tried = mutableListOf<Pair<Boolean, Long>>()
        var rejected = 0
        var heard = 0
        var levels: Pair<Double, Double>? = null
        var firstSoundAt: Long? = null
        override fun onLevel(level: Float) = Unit
        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) { list.add("end $hadSpeech") }
        override fun onNoSpeech() { list.add("no speech") }
        override fun onFailed() { list.add("failed") }
        override fun onMic(headset: Boolean, fellBack: Boolean) { list.add("mic $headset $fellBack") }
        override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) { phrases.add(audio) }
        override fun onContinuousEnd(reason: String) { list.add("continuous end $reason") }
        override fun onFirstFrame(atMs: Long) { firstSoundAt = atMs }
        override fun onHeadsetTried(up: Boolean, ms: Long) { tried.add(up to ms) }
        override fun onHeadsetRejected() { rejected++ }
        override fun onHeadsetHeard() { heard++ }
        override fun onPhoneLevels(beforeDbfs: Double, afterDbfs: Double) { levels = beforeDbfs to afterDbfs }
        var probation: Pair<Double, Double>? = null
        var probationUpMs = 0L
        var probationFrames = 0
        override fun onProbationLevels(headsetDbfs: Double, phoneDbfs: Double, afterUpMs: Long, speechFrames: Int) {
            probation = headsetDbfs to phoneDbfs; probationUpMs = afterUpMs; probationFrames = speechFrames
        }
        var undecided = 0
        override fun onHeadsetUndecided() { undecided++ }
        var same: Pair<Int, Int>? = null
        override fun onSameSound(same: Int, frames: Int) { this.same = same to frames }
        var sources: Pair<Long, Long>? = null
        override fun onSourcesMs(phoneMs: Long, headsetMs: Long) { sources = phoneMs to headsetMs }
        var gap: Triple<Long, Long, Double>? = null
        override fun onGap(deafMs: Long, connectMs: Long, phonePeakDbfs: Double) { gap = Triple(deafMs, connectMs, phonePeakDbfs) }
        var probe: Pair<String, Long>? = null
        override fun onProbe(summary: String, filledMs: Long) { probe = summary to filledMs }
    }

    private val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val a2dp = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val ble = android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
    private val builtInMic = android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
    private val speaker = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private fun dev(type: Int, tail: String) = helium314.keyboard.latin.voiceapi.AudioDevice(type, "XX:XX:XX:XX:$tail")
    private val phoneMic = helium314.keyboard.latin.voiceapi.AudioDevice(builtInMic, "bottom")
    private val phoneSpeaker = helium314.keyboard.latin.voiceapi.AudioDevice(speaker, "")
    private fun route(choice: MicChoice, inputs: List<helium314.keyboard.latin.voiceapi.AudioDevice>,
                      outputs: List<helium314.keyboard.latin.voiceapi.AudioDevice>, media: List<helium314.keyboard.latin.voiceapi.AudioDevice>?,
                      notHeadset: Boolean = false) =
        helium314.keyboard.latin.voiceapi.MicRouting.decide(choice, inputs, outputs, media, outputs.filter { it.type == sco || it.type == ble }) { notHeadset }

    /**
     * stage 5g-3 / 5g-7: a watch with calls (an SCO input) and a speaker. Since 5g-7 (the owner: the car's microphone like
     * a call) such a device is tried once like a car; a silent one is marked and skipped from then on (test below)
     */
    @Test fun watchesAndSpeakersAreNoHeadset() {
        // the watch: microphone input and calls, no music; the speaker plays the music, has no input
        val watchAndSpeaker = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "11:22")),
            listOf(phoneSpeaker, dev(sco, "11:22"), dev(a2dp, "33:44")), listOf(dev(a2dp, "33:44")))
        assertTrue(watchAndSpeaker.decision.headset && watchAndSpeaker.carLike, "not proven, tried like a car")
        assertEquals(dev(sco, "11:22"), watchAndSpeaker.headset)
        assertEquals("car-like (sco, no media) tried", watchAndSpeaker.reason)
        assertEquals("22: in sco, out sco; 44: out a2dp, media", watchAndSpeaker.devices)
        // marked as silent: the phone, nothing touched
        val marked = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "11:22")),
            listOf(phoneSpeaker, dev(sco, "11:22"), dev(a2dp, "33:44")), listOf(dev(a2dp, "33:44")), notHeadset = true)
        assertTrue(marked.decision.skipped && marked.headset == null)
        // a speaker for music only: no Bluetooth input at all
        val speakerOnly = route(MicChoice.AUTO, listOf(phoneMic), listOf(phoneSpeaker, dev(a2dp, "33:44")), listOf(dev(a2dp, "33:44")))
        assertFalse(speakerOnly.decision.headset)
        assertEquals("no bluetooth input", speakerOnly.reason)
        // Android before 13: where media plays is unknown: never proven (since 5g-7 tried like a car)
        assertTrue(route(MicChoice.AUTO, listOf(dev(sco, "A1:B2")), listOf(dev(sco, "A1:B2"), dev(a2dp, "A1:B2")), null).carLike)
        // a hidden address: can't be matched, never proven; and no device for calls: not tried at all
        val hidden = route(MicChoice.AUTO, listOf(helium314.keyboard.latin.voiceapi.AudioDevice(sco, "")),
            listOf(helium314.keyboard.latin.voiceapi.AudioDevice(a2dp, "")), listOf(helium314.keyboard.latin.voiceapi.AudioDevice(a2dp, "")))
        assertFalse(hidden.decision.headset)
        assertEquals("bluetooth input, not usable for calls", hidden.reason)
        // and so the recording has no headset: the phone from the first frame, nothing tried, 0 ms of waiting
        val events = Events()
        val phone = SyntheticSource(speechAtOnce)
        val r = PcmRecorder({ phone }, Loudness.SOURCE_MIC, 120_000, events, { it.run() }, threadFactory = inPlace, headset = null)
        assertTrue(r.start())
        assertEquals(emptyList(), events.tried)
        assertEquals(phone.readMs, r.audio.durationMs)
        assertEquals("phone", micName(r))
    }

    /** real earbuds: their microphone input and the music output share the address, and the music plays on them */
    @Test fun realEarbudsAreProven() {
        val buds = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "A1:B2"), dev(sco, "11:22")),
            listOf(phoneSpeaker, dev(sco, "A1:B2"), dev(a2dp, "A1:B2"), dev(sco, "11:22")), listOf(dev(a2dp, "A1:B2")))
        assertTrue(buds.decision.headset)
        assertEquals(dev(sco, "A1:B2"), buds.headset, "the earbuds, not the watch")
        assertEquals("headset proven", buds.reason)
        assertEquals("B2: in sco, out sco+a2dp, media; 22: in sco, out sco", buds.devices)
        // LE Audio earbuds
        assertTrue(route(MicChoice.AUTO, listOf(dev(ble, "C3:D4")), listOf(dev(ble, "C3:D4")), listOf(dev(ble, "C3:D4"))).decision.headset)
        // found to be no headset before: not tried until it connects again (stage 5g-10: unless it is proven earbuds with
        // media; so here the media plays on the phone)
        val known = route(MicChoice.AUTO, listOf(dev(sco, "A1:B2")), listOf(dev(sco, "A1:B2"), dev(a2dp, "A1:B2")), listOf(phoneSpeaker), notHeadset = true)
        assertTrue(known.decision.skipped && !known.decision.headset)
        assertEquals("device without sound (marked)", known.reason)
        // "Phone" chosen: never; "Headset" chosen by hand: tried even without the proof
        assertFalse(route(MicChoice.PHONE, listOf(dev(sco, "A1:B2")), listOf(dev(a2dp, "A1:B2")), listOf(dev(a2dp, "A1:B2"))).decision.headset)
        assertTrue(route(MicChoice.HEADSET, listOf(dev(sco, "11:22")), listOf(dev(sco, "11:22")), listOf(phoneSpeaker)).decision.headset)
    }

    @Test fun theChoice() {
        assertEquals(MicChoice.AUTO, MicChoice.of(null), "Auto by default")
        assertEquals(MicDecision(headset = false, missing = false), MicDecision.of(MicChoice.PHONE, true), "Phone: never the headset")
        assertEquals(MicDecision(headset = false, missing = true), MicDecision.of(MicChoice.HEADSET, false))
        assertEquals(MicDecision(headset = false, missing = false, skipped = true), MicDecision.of(MicChoice.AUTO, true, failedBefore = true))
    }

    private val speech = listOf(0.5 to false, 1.5 to true, 3.0 to false)
    private val speechAtOnce = syllables(2.0) + listOf(3.0 to false)

    private class Run(val r: PcmRecorder, val e: Events, val opened: List<Int>)

    private fun record(headset: FakeHeadset, phone: (FakeHeadset) -> PcmSource, continuous: PcmRecorder.Continuous? = null,
                       before: (PcmRecorder) -> Unit = {}): Run {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        val opened = mutableListOf<Int>()
        var current: PcmSource? = null
        val r = PcmRecorder({ src -> opened.add(src); phone(headset).also { current = it } }, Loudness.SOURCE_MIC, 120_000, events, { posted.add(it) },
            detectorFactory = detector(), threadFactory = inPlace, continuous = continuous, headset = headset,
            clock = { 1000L + ((current as? HonorPhone)?.readMs ?: (current as? SyntheticSource)?.readMs ?: 0L) })
        before(r)
        assertTrue(r.start())
        posted.forEach { it.run() }
        return Run(r, events, opened)
    }

    /** proven earbuds, the owner waits a moment and speaks into them: they take over; the channel is given back after */
    @Test fun earbudsTakeOverInAPauseAndHearTheSpeaker() {
        val h = FakeHeadset { SyntheticSource(listOf(1.5 to false) + syllables(1.5) + listOf(4.0 to false), tone = amplitudeOf(-12.0)) }
        val run = record(h, { HonorPhone(SyntheticSource(listOf(8.0 to false)), it) })
        assertEquals(1, h.connected)
        assertTrue(run.r.onHeadset)
        assertEquals(Loudness.SOURCE_HEADSET, run.r.sourceUsed)
        assertEquals(listOf("mic true false", "end true"), run.e.list)
        assertEquals(0, run.e.rejected)
        assertEquals(1, run.e.heard, "it heard the speaker: a mark of it goes away")
        assertFalse(h.holding, "given back after the recording")
        assertEquals("phone→headset", micName(run.r))
    }

    /**
     * the headset is a watch or speaker after all: silence where the phone (quiet while the channel is up) hears the
     * speaker: the channel is given back, the phone's sound is used, nothing is lost, the phone is loud again
     */
    @Test fun aSilentHeadsetFallsBackToThePhoneWithoutLoss() {
        val h = FakeHeadset { SyntheticSource(listOf(10.0 to false), noise = amplitudeOf(-75.0)) }
        var phone: HonorPhone? = null
        val run = record(h, { HonorPhone(SyntheticSource(listOf(1.2 to false) + syllables(5.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it).also { p -> phone = p } })
        assertEquals(1, run.e.rejected, "no headset: remembered by the keyboard")
        assertTrue(run.r.headsetRejected)
        assertFalse(run.r.onHeadset)
        assertTrue(run.e.list.first() == "mic false true", run.e.list.toString())
        assertEquals("end true", run.e.list.last())
        assertEquals(phone!!.readMs, run.r.audio.durationMs, "every frame of the phone is in the recording")
        assertFalse(h.holding)
        val (before, after) = run.e.levels!!
        assertTrue(after > -10.0, "the phone is loud again after the channel was given back: $after dBFS (before $before)")
        assertEquals("headset silent→phone", micName(run.r))
    }

    /** speech begins on the phone while the channel comes up: it is let go at once, the phone hears the phrase fully */
    @Test fun speechBeforeTheHeadsetLetsItGoAtOnce() {
        val h = FakeHeadset(up = false) { SyntheticSource(listOf(10.0 to false)) }
        var phone: HonorPhone? = null
        val run = record(h, { HonorPhone(SyntheticSource(listOf(1.0 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it).also { p -> phone = p } })
        assertTrue(run.r.headsetAborted)
        assertFalse(h.holding)
        assertEquals("end true", run.e.list.last())
        assertEquals(phone!!.readMs, run.r.audio.durationMs)
        // the phrase was recorded at full level: the channel was given back as soon as the speech began
        assertTrue(run.r.audio.peak() > amplitudeOf(-12.0), "peak ${Loudness.dbfs(run.r.audio.peak().toDouble())}")
        assertTrue(run.e.levels!!.second > -10.0)
        assertEquals("phone (speech began, headset let go)", micName(run.r))
    }

    /** speech right away: the headset is never even asked for (no quiet moment): exactly 4351 */
    @Test fun speechAtOnceNeverTouchesTheHeadset() {
        val h = FakeHeadset { SyntheticSource(listOf(10.0 to true)) }
        val phone = SyntheticSource(speechAtOnce)
        val run = record(h, { phone })
        assertEquals(0, h.connected)
        assertEquals(listOf("end true"), run.e.list)
        assertEquals(phone.readMs, run.r.audio.durationMs)
        assertEquals("phone", micName(run.r))
    }

    @Test fun notUpIn1_5sKeepsThePhoneAndLosesNothing() {
        val h = FakeHeadset(up = false) { SyntheticSource(listOf(10.0 to false)) }
        var phone: SyntheticSource? = null
        val run = record(h, { SyntheticSource(listOf(2.5 to false) + syllables(1.5) + listOf(3.0 to false)).also { p -> phone = p } })
        assertTrue(run.r.headsetFellBack)
        assertEquals(false, run.e.tried.single().first)
        assertTrue(run.e.tried.single().second in 1400L..1700L, "${run.e.tried}")
        assertEquals(listOf("mic false true", "end true"), run.e.list)
        assertEquals(phone!!.readMs, run.r.audio.durationMs)
        assertFalse(h.holding)
    }

    /** continuous dictation: one channel per session, taken over between phrases, the phrase begun on the phone ends on it */
    @Test fun oneChannelPerSessionAndTheSwitchBetweenPhrases() {
        // the earbuds are asked for in the pause after the first phrase (about 3.2 s); the owner speaks into them 2 s later
        val h = FakeHeadset { SyntheticSource(listOf(2.0 to false) + syllables(1.5) + listOf(30.0 to false), tone = amplitudeOf(-12.0)) }
        val run = record(h, { SyntheticSource(syllables(1.5) + listOf(30.0 to false)) }, continuous = PcmRecorder.Continuous(120_000, 60 * 60_000))
        assertEquals(1, h.connected, "once for the whole session")
        assertTrue(run.r.onHeadset)
        assertEquals(2, run.e.phrases.size)
        assertTrue(run.e.phrases[0].durationMs >= 2400, "the first phrase is complete")
        assertEquals(listOf("mic true false", "continuous end silence"), run.e.list)
        assertFalse(h.holding)
    }

    /** section 3 of 5g-2: earbuds put on in the middle of a continuous dictation take over in a pause */
    @Test fun headsetConnectedDuringTheSessionTakesOverInAPause() {
        val events = Events()
        var rec: PcmRecorder? = null
        val h = FakeHeadset { SyntheticSource(listOf(2.0 to false) + syllables(1.5) + listOf(30.0 to false), tone = amplitudeOf(-12.0)) }
        val phone = SyntheticSource(syllables(1.5) + listOf(30.0 to false), onRead = { ms -> if (ms == 500L) rec!!.offerHeadset(h) })
        val r = PcmRecorder({ phone }, Loudness.SOURCE_MIC, 120_000, events, { it.run() }, detectorFactory = detector(), threadFactory = inPlace,
            continuous = PcmRecorder.Continuous(120_000, 60 * 60_000))
        rec = r
        assertTrue(r.start())
        assertEquals(1, h.connected)
        assertTrue(r.onHeadset)
        assertEquals(2, events.phrases.size)
        assertEquals(listOf("mic true false", "continuous end silence"), events.list)
        assertFalse(h.holding)
    }

    /** the earbuds are taken off in the middle of the session: the phone goes on at once, nothing stops */
    @Test fun headsetGoneDuringTheSessionBackToThePhone() {
        val events = Events()
        var rec: PcmRecorder? = null
        val opened = mutableListOf<Int>()
        val h = FakeHeadset { SyntheticSource(listOf(1.5 to false) + syllables(1.5) + listOf(30.0 to false), tone = amplitudeOf(-12.0),
            onRead = { ms -> if (ms == 6000L) rec!!.headsetGone() }) }
        val r = PcmRecorder({ src -> opened.add(src); SyntheticSource(listOf(30.0 to false)) },
            Loudness.SOURCE_MIC, 120_000, events, { it.run() }, detectorFactory = detector(), threadFactory = inPlace,
            continuous = PcmRecorder.Continuous(120_000, 60 * 60_000), headset = h)
        rec = r
        assertTrue(r.start())
        assertEquals(listOf("mic true false", "mic false false", "continuous end silence"), events.list)
        assertEquals(2, opened.size, "the phone opened again when the headset went away")
        assertFalse(r.onHeadset)
        assertTrue(r.headsetLeft)
        assertEquals("headset, then phone (headset gone)", micName(r))
        assertEquals(1, events.phrases.size)
        assertFalse(h.holding)
    }

    /**
     * stage 5g-3, the owner's car: its microphone input and its music output share the address and the music plays on
     * it, so it is tried like a headset (switched on like a call); when it gives silence the phone's sound is used and
     * the car is marked, and the next tap uses the phone at once (0 ms, nothing touched)
     */
    @Test fun aSilentCarIsMarkedAndSkippedNextTime() {
        val car = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "C4:R5")), listOf(phoneSpeaker, dev(sco, "C4:R5"), dev(a2dp, "C4:R5")), listOf(dev(a2dp, "C4:R5")))
        assertTrue(car.decision.headset, "the car can't be told from earbuds by the lists")
        val dir = java.io.File("build/tmp/silent-mics").apply { mkdirs() }
        val file = java.io.File(dir, "car.txt").apply { delete() }
        val marks = helium314.keyboard.latin.voiceapi.SilentMics(file)
        val key = helium314.keyboard.latin.voiceapi.MicRouting.key(car.headset!!)
        // the recording: silence from the car, the phone (quiet while the call mode is on) hears the speaker
        val h = FakeHeadset(deviceKey = key) { SyntheticSource(listOf(10.0 to false), noise = amplitudeOf(-75.0)) }
        val run = record(h, { HonorPhone(SyntheticSource(listOf(1.2 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it) })
        assertEquals(1, run.e.rejected)
        if (run.e.rejected > 0) marks.mark(key) // what the keyboard does on onHeadsetRejected
        assertFalse(h.holding, "no call mode left after the recording")
        // next tap: skipped at once, for good (also after the car connects again: the mark is kept in a file)
        val next = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "C4:R5")), listOf(dev(sco, "C4:R5"), dev(a2dp, "C4:R5")),
            listOf(phoneSpeaker), notHeadset = helium314.keyboard.latin.voiceapi.SilentMics(file).has(key))
        assertTrue(next.decision.skipped && !next.decision.headset && next.headset == null)
        assertEquals("device without sound (marked)", next.reason)
        // once it hears the speaker (only with "Headset" chosen by hand), the mark goes away
        marks.unmark(key)
        assertFalse(helium314.keyboard.latin.voiceapi.SilentMics(file).has(key))
        file.delete()
    }

    /** stage 5g-3, journal 13:43:29: no Bluetooth input at the tap, and still a headset was tried: never again */
    @Test fun noBluetoothInputMeansNoTryAtAll() {
        val none = route(MicChoice.AUTO, listOf(phoneMic), listOf(phoneSpeaker), listOf(phoneSpeaker))
        assertEquals("no bluetooth input", none.reason)
        assertEquals("none", none.devices)
        assertNull(none.headset)
        val offer = helium314.keyboard.latin.voiceapi.MicRouting::offerAllowed
        assertFalse(offer(false, MicChoice.AUTO, true, false), "a single phrase never takes a device that connects meanwhile")
        assertFalse(offer(true, MicChoice.AUTO, false, false), "not proven: no")
        assertFalse(offer(true, MicChoice.AUTO, true, true), "marked as silent: no")
        assertFalse(offer(true, MicChoice.PHONE, true, false))
        assertTrue(offer(true, MicChoice.AUTO, true, false))
    }

    /** stage 5g-3, journal 13:47:46: a click at full scale right after the channel is given back is no speech, no peak */
    @Test fun theClickAfterTheChannelIsNotCounted() {
        val h = FakeHeadset(up = false) { SyntheticSource(listOf(10.0 to false)) }
        var clicked = false
        val run = record(h, { headset -> object : PcmSource {
            val inner = HonorPhone(SyntheticSource(listOf(1.0 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), headset)
            var wasHeld = false
            override fun start() = inner.start()
            override fun read(buffer: ShortArray): Int {
                val n = inner.read(buffer)
                if (wasHeld && !headset.holding && !clicked && n > 0) { buffer.fill(32767, 0, n); clicked = true } // the switch clicks
                wasHeld = headset.holding
                return n
            }
            override fun stop() = inner.stop()
        } })
        assertTrue(clicked)
        assertTrue(run.r.headsetAborted)
        assertTrue(run.r.peakDbfs < -1.0, "the click is not the peak: ${run.r.peakDbfs}")
        assertTrue(run.r.audio.peak() < 32000, "the click is silenced in the recording")
        assertEquals("end true", run.e.list.last(), "the speech is found")
    }

    /** stage 5g-3: the call mode is switched on with the device and always back to normal: after the end, a failure, twice */
    @Test fun theCallModeAlwaysGoesBackToNormal() {
        var mode = android.media.AudioManager.MODE_NORMAL
        var selected = false
        var selectWorks = true
        val c = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { selected = selectWorks; selectWorks }, { selected = false })
        assertTrue(c.open())
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, mode, "like a call")
        assertTrue(selected)
        c.close()
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode, "the music goes on")
        assertFalse(selected)
        c.close() // twice: nothing
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode)
        selectWorks = false
        assertFalse(c.open())
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode, "the device could not be chosen: back to normal at once")
        val failing = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { throw IllegalStateException() }, { })
        assertFalse(failing.open())
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode, "an error: back to normal")
        // somebody else (a real call) changed the mode meanwhile: it is left alone
        val c2 = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { true }, { })
        c2.open(); mode = android.media.AudioManager.MODE_IN_CALL; c2.close()
        assertEquals(android.media.AudioManager.MODE_IN_CALL, mode)
    }

    @Test fun silentMarksAreKeptInAFile() {
        val file = java.io.File("build/tmp/silent-mics/marks.txt").apply { parentFile?.mkdirs(); delete() }
        val m = helium314.keyboard.latin.voiceapi.SilentMics(file)
        assertFalse(m.has("7:C4:R5"))
        m.mark("7:C4:R5"); m.mark("26:A1:B2"); m.mark("7:C4:R5")
        assertEquals(2, m.count())
        val again = helium314.keyboard.latin.voiceapi.SilentMics(file) // the next day, after the car connected again
        assertTrue(again.has("7:C4:R5") && again.has("26:A1:B2"))
        again.unmark("26:A1:B2")
        assertEquals(1, helium314.keyboard.latin.voiceapi.SilentMics(file).count())
        again.clear() // "Delete all" or the diagnostics screen
        assertFalse(file.exists())
        assertEquals(0, helium314.keyboard.latin.voiceapi.SilentMics(file).count())
        assertFalse(m.has(null))
    }

    private fun recordEager(headset: FakeHeadset, phone: (FakeHeadset) -> PcmSource, upAtMs: Long = 600, car: Boolean = false,
                            continuous: PcmRecorder.Continuous? = null, before: (PcmRecorder) -> Unit = {}): Run {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        val opened = mutableListOf<Int>()
        headset.up = false
        var current: PcmSource? = null
        val r = PcmRecorder({ src -> opened.add(src); phone(headset).also { current = it } }, Loudness.SOURCE_MIC, 120_000, events, { posted.add(it) },
            detectorFactory = detector(), threadFactory = inPlace, headset = object : HeadsetMic by headset {
                // the channel comes up after [upAtMs] of the phone's sound
                override fun isUp() = headset.holding && ((current as? HonorPhone)?.readMs ?: (current as? DeafPhone)?.readMs ?: (current as? DeafAfterRelease)?.readMs ?: (current as? SyntheticSource)?.readMs ?: 0L) >= upAtMs
            }, clock = { 1000L }, headsetEager = true, headsetCar = car, continuous = continuous)
        before(r)
        assertTrue(r.start())
        posted.forEach { it.run() }
        return Run(r, events, opened)
    }

    /** stage 5g-5, the owner in earbuds speaks at once: the channel is asked for at the tap and kept; the start comes from the phone */
    @Test fun provenEarbudsAndSpeechAtOnceTakeOverWithoutAGap() {
        var earbuds: SyntheticSource? = null
        val h = FakeHeadset { SyntheticSource(syllables(3.0) + listOf(3.0 to false), tone = amplitudeOf(-10.0)).also { earbuds = it } }
        var phone: HonorPhone? = null
        val run = recordEager(h, { HonorPhone(SyntheticSource(syllables(3.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it).also { p -> phone = p } })
        assertEquals(1, h.connected, "asked for at the tap, no waiting for silence")
        assertFalse(run.r.headsetAborted, "not let go because the speech began")
        assertTrue(run.r.onHeadset)
        assertEquals("phone→headset", micName(run.r))
        assertEquals(listOf("mic true false", "end true"), run.e.list)
        // no gap and no repeat: the phone up to the joint, the earbuds after it, as long as the time that passed
        assertTrue(kotlin.math.abs(run.r.audio.durationMs - earbuds!!.readMs) <= 40, "${run.r.audio.durationMs} ms recorded, ${earbuds!!.readMs} ms passed")
        assertTrue(phone!!.readMs in 500L..800L, "the phone gave the start until the earbuds were up: ${phone!!.readMs} ms")
        assertFalse(h.holding)
    }

    /** the phone hears the speaker only very quietly (-45 dBFS): with proven earbuds a strong sign: they are kept */
    @Test fun quietPhoneKeepsProvenEarbuds() {
        val h = FakeHeadset { SyntheticSource(listOf(0.5 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-48.0), noise = amplitudeOf(-70.0)) }
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(0.5 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-15.0), noise = amplitudeOf(-70.0)), it) })
        assertEquals(0, run.e.rejected, "the phone at -45 dBFS does not reject proven earbuds")
        assertTrue(run.r.onHeadset)
        assertFalse(run.r.headsetAborted)
    }

    /** proven earbuds that are dead (almost nothing) while the phone hears clearly more: back to the phone, nothing lost */
    @Test fun deadProvenEarbudsStillFallBack() {
        val h = FakeHeadset { SyntheticSource(listOf(10.0 to false), noise = amplitudeOf(-75.0)) }
        var phone: HonorPhone? = null
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(0.5 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-3.0)), it).also { p -> phone = p } })
        assertEquals(1, run.e.rejected)
        assertFalse(run.r.onHeadset)
        assertEquals(phone!!.readMs, run.r.audio.durationMs, "every frame of the phone is in the recording")
    }

    // ---- stage 5g-7: the car's microphone, switched on like a call ----

    /** the owner's car ("28: in sco, out sco", no music output): tried at once, like a call; a watch that was silent is skipped */
    @Test fun carIsTriedLikeACall() {
        val car = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "C4:28")), listOf(phoneSpeaker, dev(sco, "C4:28")), listOf(phoneSpeaker))
        assertTrue(car.decision.headset && car.carLike)
        assertEquals("car-like (sco, no media) tried", car.reason)
        assertEquals("28: in sco, out sco", car.devices)
        // a watch marked as silent and the car: the car
        val watchKey = helium314.keyboard.latin.voiceapi.MicRouting.key(dev(sco, "11:22"))
        val both = helium314.keyboard.latin.voiceapi.MicRouting.decide(MicChoice.AUTO, listOf(dev(sco, "11:22"), dev(sco, "C4:28")),
            listOf(dev(sco, "11:22"), dev(sco, "C4:28")), listOf(phoneSpeaker), listOf(dev(sco, "11:22"), dev(sco, "C4:28"))) {
            helium314.keyboard.latin.voiceapi.MicRouting.key(it) == watchKey }
        assertEquals(dev(sco, "C4:28"), both.headset)
        // proven earbuds come first, and stay proven (5g-5)
        val buds = route(MicChoice.AUTO, listOf(dev(sco, "C4:28"), dev(sco, "A1:B2")),
            listOf(dev(sco, "C4:28"), dev(sco, "A1:B2"), dev(a2dp, "A1:B2")), listOf(dev(a2dp, "A1:B2")))
        assertEquals(dev(sco, "A1:B2"), buds.headset)
        assertFalse(buds.carLike)
        assertEquals("headset proven", buds.reason)
        // "Phone" chosen: never
        assertNull(route(MicChoice.PHONE, listOf(dev(sco, "C4:28")), listOf(dev(sco, "C4:28")), listOf(phoneSpeaker)).headset)
    }

    /** the car hears the speaker: the channel at the tap, the start of the phrase from the phone (opened BEFORE the call mode), then the car */
    @Test fun carTakesOverWithTheStartFromThePhone() {
        val order = mutableListOf<String>()
        var carSound: SyntheticSource? = null
        val tracked = FakeHeadset { SyntheticSource(syllables(3.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0)).also { carSound = it } }
        tracked.onConnect = { order.add("connect") }
        var phone: HonorPhone? = null
        // the phone source is made and started (PcmRecorder.open) before anything else
        val run = recordEager(tracked, { HonorPhone(SyntheticSource(syllables(3.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it).also { p -> phone = p; order.add("phone") } }, car = true)
        assertEquals(listOf("phone", "connect"), order.take(2), "the phone records before the call mode is switched on (the HONOR gets quiet then)")
        assertEquals(1, tracked.connected)
        assertFalse(run.r.headsetAborted, "not let go because the speech began")
        assertTrue(run.r.onHeadset)
        assertEquals(Loudness.SOURCE_HEADSET, run.r.sourceUsed)
        assertEquals(listOf("mic true false", "end true"), run.e.list)
        assertEquals(1, run.e.heard)
        assertTrue(kotlin.math.abs(run.r.audio.durationMs - carSound!!.readMs) <= 40, "no gap: ${run.r.audio.durationMs} ms of ${carSound!!.readMs} ms")
        assertTrue(phone!!.readMs in 2000L..2300L, "the phone gives the start and goes on until the decision 1.5 s after the channel is up: ${phone!!.readMs} ms")
        assertFalse(tracked.holding, "the channel and the call mode are given back")
        val (carDb, phoneDb) = run.e.probation!!
        assertTrue(carDb > -20 && phoneDb < carDb, "car $carDb dBFS, phone $phoneDb dBFS")
    }

    /** the car (or a watch) is silent while the phone hears the speaker: back to the phone, nothing lost, marked; next time the phone at once */
    @Test fun silentCarFallsBackAndIsSkippedNextTime() {
        val h = FakeHeadset { SyntheticSource(listOf(10.0 to false), noise = amplitudeOf(-75.0)) }
        var phone: HonorPhone? = null
        // the HONOR phone is 30 dB quieter while the channel is held: the speaker at -6 dBFS is heard at about -36 dBFS
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(0.5 to false) + syllables(5.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it).also { p -> phone = p } }, car = true)
        assertEquals(1, run.e.rejected, "the strict probation of 5g-3, not the patient one of proven earbuds")
        assertFalse(run.r.onHeadset)
        assertTrue(run.r.headsetRejected)
        assertEquals(phone!!.readMs, run.r.audio.durationMs, "every frame of the phone is in the recording")
        assertEquals("end true", run.e.list.last())
        assertFalse(h.holding)
        // the keyboard marks it (onHeadsetRejected), and the next tap takes the phone at once
        val next = route(MicChoice.AUTO, listOf(phoneMic, dev(sco, "C4:28")), listOf(dev(sco, "C4:28")), listOf(phoneSpeaker), notHeadset = true)
        assertTrue(next.decision.skipped && next.headset == null)
    }

    /** a continuous dictation keeps the car's channel for the whole session, not per phrase */
    @Test fun carChannelForTheWholeSession() {
        val h = FakeHeadset { SyntheticSource(syllables(2.0) + listOf(2.5 to false) + syllables(1.5) + listOf(6.0 to false), tone = amplitudeOf(-12.0)) }
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(20.0 to false)), it) }, car = true,
            continuous = PcmRecorder.Continuous(maxPhraseMs = 120_000, maxSpeechMs = 3_600_000))
        assertEquals(2, run.e.phrases.size)
        assertEquals(1, h.connected, "asked for once")
        assertEquals(1, h.released, "given back once, at the end")
        assertFalse(h.holding)
    }

    // ---- stage 5g-10: the check "silent" decides late, on speech, and marks only after two failures ----

    /** the phone of the HONOR: deaf (zeros) from the call mode until the channel is up, then 30 dB quieter */
    private class DeafPhone(private val inner: SyntheticSource, private val headset: FakeHeadset, private val upAtMs: Long) : PcmSource {
        override fun start() = inner.start()
        override fun read(buffer: ShortArray): Int {
            val before = inner.readMs
            val n = inner.read(buffer)
            if (n > 0 && headset.holding) {
                if (before < upAtMs) buffer.fill(0, 0, n) else for (i in 0 until n) buffer[i] = (buffer[i] * 0.03).toInt().toShort()
            }
            return n
        }
        override fun stop() = inner.stop()
        val readMs get() = inner.readMs
    }

    /** the first microphone of the HONOR: quiet while the channel is held and dead once it is given back (−90 dBFS) */
    private class DeafAfterRelease(private val inner: SyntheticSource, private val headset: FakeHeadset, private val deadOnRelease: Boolean) : PcmSource {
        override fun start() = inner.start()
        override fun read(buffer: ShortArray): Int {
            val n = inner.read(buffer)
            if (n > 0 && deadOnRelease) {
                if (headset.holding) for (i in 0 until n) buffer[i] = (buffer[i] * 0.03).toInt().toShort()
                else if (headset.released > 0) buffer.fill(0, 0, n)
            } else if (n > 0 && headset.holding) for (i in 0 until n) buffer[i] = (buffer[i] * 0.03).toInt().toShort()
            return n
        }
        override fun stop() = inner.stop()
        val readMs get() = inner.readMs
    }

    /** journal 08:19:27: the channel up, 128 ms of silence from it, the phone hears something: that is no verdict */
    @Test fun silenceInTheFirstMomentsIsNoVerdictAndNoMark() {
        val h = FakeHeadset { SyntheticSource(listOf(0.6 to false) + syllables(3.5) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0)) }
        val run = recordEager(h, { HonorPhone(SyntheticSource(syllables(3.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it) }, upAtMs = 128, car = true)
        assertEquals(0, run.e.rejected, "no failure: the earbuds were only slow to start")
        assertEquals(0, run.e.undecided)
        assertTrue(run.r.onHeadset, "kept once the speech was heard in them")
        assertTrue(run.e.probationUpMs >= 1500L, "decided ${run.e.probationUpMs} ms after the channel was up")
        assertTrue(run.e.probationFrames >= 10, "on ${run.e.probationFrames} frames with speech")
    }

    /** the owner's car: the channel needs 1.7 s; the phone is deaf meanwhile, and the gap is measured */
    @Test fun carThatNeedsOnePointSevenSecondsIsAHeadset() {
        val h = FakeHeadset { SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0)) }
        val run = recordEager(h, { DeafPhone(SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it, 1700) }, upAtMs = 1700, car = true)
        assertEquals(0, run.e.rejected)
        assertTrue(run.r.onHeadset)
        assertEquals(Loudness.SOURCE_HEADSET, run.r.sourceUsed)
        val (deaf, total, peakDb) = run.e.gap!!
        assertTrue(deaf in 1500L..1800L, "the phone was deaf for $deaf ms")
        assertTrue(total in 1500L..1800L, "$total ms")
        assertTrue(peakDb < -80, "$peakDb")
    }

    /** a watch: after the channel is up it is silent where the phone hears the speaker; marked only the second time */
    @Test fun watchIsMarkedAfterTwoFailedChecks() {
        val file = java.io.File("build/tmp/silent-mics/watch.txt").apply { parentFile?.mkdirs(); delete() }
        val marks = helium314.keyboard.latin.voiceapi.SilentMics(file)
        val key = "7:11:22"
        fun silentWatch(): Run {
            val h = FakeHeadset(deviceKey = key) { SyntheticSource(listOf(12.0 to false), noise = amplitudeOf(-75.0)) }
            return recordEager(h, { HonorPhone(SyntheticSource(syllables(4.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)), it) }, upAtMs = 128, car = true)
        }
        val first = silentWatch()
        assertEquals(1, first.e.rejected)
        assertEquals(1, marks.strike(key)); assertFalse(marks.has(key), "one failed check marks nothing")
        assertEquals("end true", first.e.list.last(), "the phone lost nothing")
        val second = silentWatch()
        assertEquals(1, second.e.rejected)
        assertEquals(2, marks.strike(key)); assertTrue(marks.has(key), "marked after the second")
        assertEquals(1, helium314.keyboard.latin.voiceapi.SilentMics(file).count(), "kept in the file")
        // a good sound once: nothing stays
        marks.unmark(key)
        assertFalse(marks.has(key)); assertEquals(0, marks.count())
        assertEquals(1, marks.strike(key), "counting starts anew")
        file.delete()
    }

    /** nobody speaks while the device is tried: both are listened to for 10 s, then the phone goes on, no failure counted */
    @Test fun nobodySpeaksMeansNoVerdict() {
        val h = FakeHeadset { SyntheticSource(listOf(25.0 to false), noise = amplitudeOf(-75.0)) }
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(25.0 to false), noise = amplitudeOf(-75.0)), it) }, upAtMs = 128, car = true)
        assertEquals(0, run.e.rejected)
        assertEquals(1, run.e.undecided)
        assertFalse(run.r.onHeadset)
        assertFalse(h.holding)
    }

    // ---- stage 5g-11: journal 4383, the owner's car FE ("in sco, out sco+a2dp, media": proven) ----

    /** 09:24:14: the verdict "silent" came 220 ms after the channel was up on 0 frames with speech; the car was only slow */
    @Test fun provenSlowCarIsNotRejectedEarly() {
        // the car's sound starts 2.2 s after the channel is up; the phone hears the speaker quietly (−33 dBFS) all the time
        val h = FakeHeadset { SyntheticSource(listOf(2.2 to false) + syllables(4.0) + listOf(3.0 to false), tone = amplitudeOf(-17.0), noise = amplitudeOf(-75.0)) }
        val run = recordEager(h, { HonorPhone(SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-3.0)), it) }, upAtMs = 300)
        assertEquals(0, run.e.rejected, "not 'silent': it was slow")
        assertEquals(0, run.e.undecided)
        assertTrue(run.r.onHeadset)
        assertTrue(run.e.probationUpMs >= 1500L, "decided ${run.e.probationUpMs} ms after the channel was up")
        assertTrue(run.e.probationFrames >= 10, "on ${run.e.probationFrames} frames with speech")
    }

    /** a proven car that really is silent: rolled back only after 3 s of speech on the phone, and nothing of the speech is lost */
    @Test fun provenSilentCarRollsBackLateWithoutLoss() {
        val h = FakeHeadset { SyntheticSource(listOf(25.0 to false), noise = amplitudeOf(-75.0)) }
        var phone: HonorPhone? = null
        val run = recordEager(h, { HonorPhone(SyntheticSource(syllables(8.0) + listOf(4.0 to false), tone = amplitudeOf(-3.0)), it).also { p -> phone = p } }, upAtMs = 300)
        assertEquals(1, run.e.rejected)
        assertTrue(run.e.probationUpMs >= 3000L, "decided ${run.e.probationUpMs} ms after the channel was up")
        assertEquals(phone!!.readMs, run.r.audio.durationMs, "every frame of the phone is in the recording")
        assertEquals("end true", run.e.list.last())
        assertEquals(0, run.r.phoneReopened, "the phone heard: left alone")
        assertFalse(h.holding)
    }

    /** the phone of the HONOR stays deaf after the call mode is given back (−90 dBFS before and after): opened anew, the speech arrives */
    @Test fun deafPhoneAfterRollbackIsOpenedAnew() {
        val h = FakeHeadset { SyntheticSource(listOf(25.0 to false), noise = amplitudeOf(-75.0)) }
        var created = 0
        val run = recordEager(h, { headset ->
            val nth = ++created
            DeafAfterRelease(SyntheticSource(syllables(8.0) + listOf(4.0 to false), tone = amplitudeOf(-3.0)), headset, deadOnRelease = nth == 1)
        }, upAtMs = 300)
        assertEquals(1, run.e.rejected)
        assertEquals(1, run.r.phoneReopened, "the dead microphone was replaced")
        assertEquals(listOf(Loudness.SOURCE_MIC, Loudness.SOURCE_MIC), run.opened.take(2))
        assertTrue(run.r.audio.peak() > 10_000, "the speech of the new microphone is in the recording: ${run.r.audio.peak()}")
        assertEquals("end true", run.e.list.last())
    }

    // ---- stage 5g-12: journal 4385, car F0 ("in sco, out sco", car-like): "раз, два, три, четыре" → "Три, четыре" ----

    /**
     * The audio policy of the call mode: the phone's recording with the MIC source goes to the hands-free device too, so it
     * gives zeros until the channel is up and then the car's sound (the same stream as the headset); CAMCORDER stays on
     * the built-in microphone.
     */
    private fun carF0(swap: Boolean): Run {
        val events = Events()
        val posted = mutableListOf<Runnable>()
        val opened = mutableListOf<Int>()
        val upAtMs = 1600L
        val h = FakeHeadset { SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0)) }
        h.up = false
        var phone: SyntheticSource? = null
        fun phoneMs() = phone?.readMs ?: 0L
        val r = PcmRecorder({ src ->
            opened.add(src)
            val inner = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)).also { if (phone == null) phone = it }
            if (src == Loudness.SOURCE_CAMCORDER) inner
            else object : PcmSource { // MIC: routed to the car while its channel is asked for
                val car = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0))
                override fun start() = inner.start().also { car.start() }
                override fun read(buffer: ShortArray): Int {
                    val n = inner.read(buffer)
                    val carFrame = ShortArray(buffer.size).also { car.read(it) }
                    if (n > 0 && h.holding) { if (phoneMs() < upAtMs) buffer.fill(0, 0, n) else System.arraycopy(carFrame, 0, buffer, 0, n) }
                    return n
                }
                override fun stop() = inner.stop()
            }
        }, Loudness.SOURCE_MIC, 120_000, events, { posted.add(it) },
            detectorFactory = detector(), threadFactory = inPlace, headset = object : HeadsetMic by h {
                override fun isUp() = h.holding && phoneMs() >= upAtMs
            }, clock = { 1000L }, headsetEager = true, headsetCar = true,
            phoneSourceWhileHeadset = if (swap) Loudness.SOURCE_CAMCORDER else null)
        assertTrue(r.start())
        posted.forEach { it.run() }
        return Run(r, events, opened)
    }

    @Test fun carF0KeepsTheStartOfThePhrase() {
        val run = carF0(swap = true)
        assertEquals(Loudness.SOURCE_CAMCORDER, run.opened.first(), "the phone records with CAMCORDER while the car is tried")
        assertTrue(run.r.phoneSourceSwapped)
        val (deaf, total, _) = run.e.gap!!
        assertTrue(deaf <= 150, "gap $deaf ms of $total ms")
        assertTrue(run.r.onHeadset, "the car takes over after its probation")
        // the sound of the first 300 ms (the first syllable "раз") is in the recording
        val first = ByteArray(run.r.audio.byteCount).also { run.r.audio.copyTo(it, 0) }.copyOf(300 * 32)
        val peak = (0 until first.size / 2).maxOf { kotlin.math.abs(((first[2 * it + 1].toInt() shl 8) or (first[2 * it].toInt() and 0xFF)).toShort().toInt()) }
        assertTrue(peak > 3000, "speech from 0 ms is sent: peak $peak")
        val (phoneMs, headsetMs) = run.e.sources!!
        assertTrue(phoneMs in 1500L..1800L && headsetMs > 2000, "recorded: phone $phoneMs ms, headset $headsetMs ms")
    }

    /** the same car without the swap: the phone is deaf until the channel is up (what the journal of 4385 showed) */
    @Test fun withoutTheSwapTheStartIsLost() {
        val run = carF0(swap = false)
        val (deaf, _, _) = run.e.gap!!
        assertTrue(deaf >= 1400, "the phone heard nothing for $deaf ms")
        // on probation the phone gives the car's sound: the same stream, and the journal says so
        val (same, frames) = run.e.same!!
        assertTrue(frames > 0 && same * 10 >= frames * 9, "the same sound in both: $same of $frames frames")
        // with the swap the phone is its own microphone: not the same sound
        val (s2, f2) = carF0(swap = true).e.same!!
        assertTrue(s2 * 2 < f2, "different sounds: $s2 of $f2 frames")
    }

    // ---- stage 5g-14: journal 4389, car F0 (car-like, CAMCORDER): "один, два, три, четыре, пять" → "Два, три, четыре, пять" ----

    /**
     * The HONOR phone of the journals 4387/4389: with the call mode on and the car's link not up yet EVERY source of the
     * phone gives zeros (CAMCORDER too: gap 1100–1700 ms of 1580–2260); the switch of the mode itself costs about 100 ms
     * (earbuds AE and car FE: gap 80–100 ms). The car's link comes up [linkAtMs] after it is chosen, with the mode or
     * without it (journal 4365: "headset tried" 1573–1797 ms without the mode); its microphone gives sound only in the mode.
     */
    private class CarF0(val modeAfterLink: Boolean, val linkAtMs: Long = 1600L) {
        var mode = android.media.AudioManager.MODE_NORMAL
        var modeAt = -1L
        var modeEver = false
        var selectedAt = -1L
        var phone: SyntheticSource? = null
        fun now() = phone?.readMs ?: 0L
        fun linked() = selectedAt >= 0 && now() - selectedAt >= linkAtMs
        fun inMode() = mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val channel = helium314.keyboard.latin.voiceapi.CallChannel({ mode },
            { mode = it; if (inMode()) { modeAt = now(); modeEver = true } }, { selectedAt = now(); true }, { selectedAt = -1 },
            modeAfterLink = modeAfterLink, linked = { linked() })
        val car = object : HeadsetMic {
            override fun connect() = channel.open()
            override fun ready() = channel.ready()
            override fun isUp() = channel.isOpen && inMode() && linked()
            override fun source(): PcmSource = object : PcmSource {
                val inner = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0))
                override fun start() = inner.start()
                override fun read(buffer: ShortArray): Int { val n = inner.read(buffer); if (n > 0 && !(linked() && inMode())) buffer.fill(0, 0, n); return n }
                override fun stop() = inner.stop()
            }
            override fun release() = channel.close()
        }
        fun phoneSource(): PcmSource = object : PcmSource {
            val inner = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)).also { if (phone == null) phone = it }
            override fun start() = inner.start()
            override fun read(buffer: ShortArray): Int {
                val deaf = inMode() && (!linked() || now() - modeAt < 100)
                val n = inner.read(buffer)
                if (n > 0 && deaf) buffer.fill(0, 0, n)
                return n
            }
            override fun stop() = inner.stop()
        }
    }

    private fun recordCarF0(car: CarF0): Run {
        val events = Events()
        val opened = mutableListOf<Int>()
        val r = PcmRecorder({ src -> opened.add(src); car.phoneSource() }, Loudness.SOURCE_MIC, 120_000, events, { it.run() },
            detectorFactory = detector(), threadFactory = inPlace, headset = car.car, clock = { 1000L }, headsetEager = true,
            headsetCar = true, phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER)
        assertTrue(r.start())
        return Run(r, events, opened)
    }

    private fun firstMsPeak(r: PcmRecorder, ms: Int): Int {
        val first = ByteArray(r.audio.byteCount).also { r.audio.copyTo(it, 0) }.copyOf(ms * 32)
        return (0 until first.size / 2).maxOf { kotlin.math.abs(((first[2 * it + 1].toInt() shl 8) or (first[2 * it].toInt() and 0xFF)).toShort().toInt()) }
    }

    /** the order of 4389 (the mode first): the phone is deaf until the car's link is up, the first word is lost */
    @Test fun carF0ModeFirstLosesTheFirstWord() {
        val run = recordCarF0(CarF0(modeAfterLink = false))
        val (deaf, total, _) = run.e.gap!!
        assertTrue(deaf >= 1100, "gap $deaf ms of $total ms, as in the journal of 4389")
        assertTrue(firstMsPeak(run.r, 300) < 100, "nothing of the first 300 ms")
    }

    /** stage 5g-14: the car first, the mode once its link is up: the phone hears all the time, gap ≤ 200 ms */
    @Test fun carF0LinkFirstKeepsTheFirstWord() {
        val f0 = CarF0(modeAfterLink = true)
        val run = recordCarF0(f0)
        val (deaf, total, _) = run.e.gap!!
        assertTrue(deaf <= 200, "gap $deaf ms of $total ms")
        assertTrue(f0.modeAt >= f0.linkAtMs, "the mode only after the link: ${f0.modeAt} ms")
        assertTrue(firstMsPeak(run.r, 300) > 3000, "speech from 0 ms is sent")
        assertEquals(listOf(true), run.e.tried.map { it.first })
        assertTrue(run.r.onHeadset, "the car takes over after its probation")
        assertEquals(android.media.AudioManager.MODE_NORMAL, f0.mode, "the mode is given back")
        assertEquals(-1L, f0.selectedAt, "the device is given back")
    }

    /** a car whose link does not come up without the mode: the phone records all of it, the mode is never switched on */
    @Test fun carWithoutLinkStaysOnThePhone() {
        val f0 = CarF0(modeAfterLink = true, linkAtMs = Long.MAX_VALUE / 2)
        val run = recordCarF0(f0)
        assertEquals(listOf(false), run.e.tried.map { it.first }, "given up after 3 s")
        assertFalse(f0.modeEver, "the call mode was never on")
        assertEquals(0L, run.e.gap!!.first, "the phone heard all the time")
        assertTrue(firstMsPeak(run.r, 300) > 3000)
        assertEquals(-1L, f0.selectedAt)
    }

    /** the order of the call channel: with [modeAfterLink] the device first, the mode at the first [CallChannel.ready] after the link */
    @Test fun callChannelSwitchesTheModeAfterTheLink() {
        var mode = android.media.AudioManager.MODE_NORMAL
        var selected = false
        var link = false
        val c = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { selected = true; true }, { selected = false },
            modeAfterLink = true, linked = { link })
        assertTrue(c.open())
        assertTrue(selected)
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode, "no mode before the link")
        assertFalse(c.ready())
        link = true
        assertTrue(c.ready())
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, mode)
        c.close()
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode)
        assertFalse(selected)
        // without it, as before: the mode at once, ready at once
        val old = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { true }, { })
        assertTrue(old.open())
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, mode)
        assertTrue(old.ready())
        old.close()
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode)
    }

    // ---- stage 5g-18: journal 4394, car F0: the phone is deaf from the START of the link, whatever the mode ----

    /**
     * The HONOR phone of 4394 (the device first, the mode after the link, gap still 1460–1600 ms of 1480–1620): from the
     * moment the car is chosen until its link is up the phone's sources give zeros; those named in [alive] do not (the
     * survivor the probe looks for). [failing]: these sources cannot be opened next to the others.
     */
    private class Honor4394(val alive: Set<String> = emptySet(), val failing: Set<String> = emptySet(), val linkAtMs: Long = 1500L) {
        var mode = android.media.AudioManager.MODE_NORMAL
        var selectedAt = -1L
        var phone: SyntheticSource? = null
        val opened = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        fun now() = phone?.readMs ?: 0L
        fun linked() = selectedAt >= 0 && now() - selectedAt >= linkAtMs
        fun inMode() = mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        fun deaf() = selectedAt >= 0 && !linked()
        val channel = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { selectedAt = now(); true }, { selectedAt = -1 },
            modeAfterLink = true, linked = { linked() })
        val car = object : HeadsetMic {
            override fun connect() = channel.open()
            override fun ready() = channel.ready()
            override fun isUp() = channel.isOpen && inMode() && linked()
            override fun source(): PcmSource = object : PcmSource {
                val inner = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0), noise = amplitudeOf(-75.0))
                override fun start() = inner.start()
                override fun read(buffer: ShortArray): Int { val n = inner.read(buffer); if (n > 0 && !(linked() && inMode())) buffer.fill(0, 0, n); return n }
                override fun stop() = inner.stop()
            }
            override fun release() = channel.close()
        }
        /** a source of the phone; the recording's own one ([name] null) sets the time */
        fun source(name: String?, gain: Double = 1.0): PcmSource = object : PcmSource {
            val inner = SyntheticSource(syllables(6.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0) * gain).also { if (name == null && phone == null) phone = it }
            override fun start(): Boolean { if (name in failing) return false; name?.let { opened.add(it) }; return inner.start() }
            override fun read(buffer: ShortArray): Int {
                val n = inner.read(buffer)
                if (n > 0 && deaf() && name !in alive) buffer.fill(0, 0, n)
                return n
            }
            override fun stop() { name?.let { stopped.add(it) }; inner.stop() }
        }
    }

    private fun probeOf(h: Honor4394, names: List<String>, wait: Long = 0L, gains: Map<String, Double> = emptyMap()) =
        helium314.keyboard.latin.voiceapi.PhoneProbe(names.map { helium314.keyboard.latin.voiceapi.PhoneProbe.Candidate(it, 0) }, wait) { c ->
            h.source(c.name, gains[c.name] ?: 1.0) }

    private fun recordHonor(h: Honor4394, probe: helium314.keyboard.latin.voiceapi.PhoneProbe?): Run {
        val events = Events()
        val r = PcmRecorder({ h.source(null) }, Loudness.SOURCE_MIC, 120_000, events, { it.run() },
            detectorFactory = detector(), threadFactory = inPlace, headset = h.car, clock = { 1000L }, headsetEager = true,
            headsetCar = true, phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER, probe = probe)
        assertTrue(r.start())
        return Run(r, events, emptyList())
    }

    /** without the probe: 4394 as it was, the first word is lost */
    @Test fun honor4394LosesTheFirstWordWithoutTheProbe() {
        val run = recordHonor(Honor4394(), null)
        assertTrue(run.e.gap!!.first >= 1300, "gap ${run.e.gap}")
        assertTrue(firstMsPeak(run.r, 300) < 100)
        assertNull(run.e.probe)
    }

    /** a source that is not silenced fills the gap: the first word is sent; the journal says which source was what */
    @Test fun probeFillsTheGapFromTheSourceThatHears() {
        val h = Honor4394(alive = setOf("VOICE_PERFORMANCE"), failing = setOf("UNPROCESSED"))
        val run = recordHonor(h, probeOf(h, listOf("MIC", "UNPROCESSED", "VOICE_PERFORMANCE", "DEFAULT")))
        assertTrue(run.e.gap!!.first >= 1300, "the recording's own source is still deaf: ${run.e.gap}")
        val (summary, filled) = run.e.probe!!
        assertTrue(filled >= 1300, "filled $filled ms: $summary")
        assertTrue(firstMsPeak(run.r, 300) > 3000, "speech from 0 ms is sent")
        for (part in listOf("order: device at once", "link up after 1500 ms", "filled ", "from VOICE_PERFORMANCE", "alive: VOICE_PERFORMANCE |",
                "MIC: deaf", "UNPROCESSED: not opened", "VOICE_PERFORMANCE: alive", "DEFAULT: deaf", "CAMCORDER (recording): deaf", "-90/-90"))
            assertTrue(part in summary, "$part in $summary")
        assertEquals(listOf("MIC", "VOICE_PERFORMANCE", "DEFAULT"), h.opened)
        assertEquals(h.opened, h.stopped, "all closed")
        assertTrue(run.r.onHeadset, "the car takes over as before")
        assertEquals(android.media.AudioManager.MODE_NORMAL, h.mode)
    }

    /** every source deaf: nothing is filled, the recording is the one of 4394 */
    @Test fun allSourcesDeafChangeNothing() {
        val h = Honor4394()
        val run = recordHonor(h, probeOf(h, listOf("MIC", "VOICE_RECOGNITION")))
        val (summary, filled) = run.e.probe!!
        assertEquals(0L, filled)
        assertTrue("alive: none" in summary && "filled nothing" in summary, summary)
        assertTrue(firstMsPeak(run.r, 300) < 100)
        assertTrue(run.r.onHeadset)
    }

    /** the other order: the phone sources open, the car is chosen 300 ms later; their level before it is measured */
    @Test fun phoneSourcesFirstThenTheDevice() {
        val h = Honor4394(alive = setOf("MIC"))
        val run = recordHonor(h, probeOf(h, listOf("MIC"), wait = 300))
        val summary = run.e.probe!!.first
        assertTrue("order: phone sources 300 ms before the device" in summary, summary)
        assertTrue(Regex("MIC: alive, \\d+ ms, before -\\d+/-\\d+").containsMatchIn(summary), summary)
        assertTrue(firstMsPeak(run.r, 300) > 3000)
        assertTrue(run.e.gap!!.first in 1300..1500, "the gap starts with the device: ${run.e.gap}")
    }

    /** of two sources that hear the louder one is taken */
    @Test fun theLoudestSurvivorIsTaken() {
        val h = Honor4394(alive = setOf("QUIET", "LOUD"))
        val run = recordHonor(h, probeOf(h, listOf("QUIET", "LOUD"), gains = mapOf("QUIET" to 0.1)))
        assertTrue("filled " in run.e.probe!!.first && "from LOUD;" in run.e.probe!!.first, run.e.probe!!.first)
    }

    /** the order of the sources turns at each tap; the order of the link alternates */
    @Test fun probeOrderTurns() {
        val p = helium314.keyboard.latin.voiceapi.PhoneProbe
        assertEquals(listOf(1, 2, 3), p.rotated(listOf(1, 2, 3), 0))
        assertEquals(listOf(2, 3, 1), p.rotated(listOf(1, 2, 3), 1))
        assertEquals(listOf(1, 2, 3), p.rotated(listOf(1, 2, 3), 3))
        assertEquals(300L, p.waitFor(0))
        assertEquals(0L, p.waitFor(1))
    }

    /** proven devices (with media) are never marked for good; only devices without the proof count */
    @Test fun provenDeviceIsNeverMarkedForGood() {
        assertFalse(helium314.keyboard.latin.voiceapi.MicRouting.countsTowardsMark(provenWithMedia = true))
        assertTrue(helium314.keyboard.latin.voiceapi.MicRouting.countsTowardsMark(provenWithMedia = false))
    }

    /** proven earbuds are tried even if the device was marked before; the mark goes away */
    @Test fun provenEarbudsIgnoreAndClearTheMark() {
        val buds = route(MicChoice.AUTO, listOf(dev(sco, "A1:AE")), listOf(dev(sco, "A1:AE"), dev(a2dp, "A1:AE")), listOf(dev(a2dp, "A1:AE")), notHeadset = true)
        assertTrue(buds.decision.headset && !buds.decision.skipped, "marked, but proven: tried")
        assertEquals("headset proven", buds.reason)
        // the same device without media (YouTube on pause): a car-like device, skipped while marked
        val paused = route(MicChoice.AUTO, listOf(dev(sco, "A1:AE")), listOf(dev(sco, "A1:AE"), dev(a2dp, "A1:AE")), listOf(phoneSpeaker), notHeadset = true)
        assertTrue(paused.decision.skipped && paused.headset == null)
        val file = java.io.File("build/tmp/silent-mics/proven.txt").apply { parentFile?.mkdirs(); delete() }
        val marks = helium314.keyboard.latin.voiceapi.SilentMics(file)
        val key = helium314.keyboard.latin.voiceapi.MicRouting.key(buds.headset!!)
        marks.mark(key); marks.unmark(key) // what the keyboard does when the device is proven
        assertFalse(marks.has(key))
        file.delete()
    }

    /** the first start of this build forgets all marks (some were set by the old check), once */
    @Test fun migrationForgetsOldMarksOnce() {
        val dir = java.io.File("build/tmp/silent-mics/migration").apply { deleteRecursively(); mkdirs() }
        val file = java.io.File(dir, helium314.keyboard.latin.voiceapi.SilentMics.FILE_NAME)
        helium314.keyboard.latin.voiceapi.SilentMics(file).apply { mark("7:A1:AE"); mark("7:C4:28"); strike("7:11:22") }
        assertEquals(2, helium314.keyboard.latin.voiceapi.SilentMics(file).count())
        assertTrue(helium314.keyboard.latin.voiceapi.SilentMics.migrate(dir, file))
        assertFalse(file.exists())
        assertEquals(0, helium314.keyboard.latin.voiceapi.SilentMics(file).count())
        // marks set after the migration stay
        helium314.keyboard.latin.voiceapi.SilentMics(file).apply { mark("7:C4:28") }
        assertFalse(helium314.keyboard.latin.voiceapi.SilentMics.migrate(dir, file), "only once")
        assertEquals(1, helium314.keyboard.latin.voiceapi.SilentMics(file).count())
        dir.deleteRecursively()
    }

    /** the call mode never stays on: after a cancel, after the car broke, after it did not come up */
    @Test fun carCallModeAlwaysGoesBackToNormal() {
        fun callHeadset(sound: () -> PcmSource, upEver: Boolean = true): Pair<HeadsetMic, () -> Int> {
            var mode = android.media.AudioManager.MODE_NORMAL
            var selected = false
            val c = helium314.keyboard.latin.voiceapi.CallChannel({ mode }, { mode = it }, { selected = true; true }, { selected = false })
            return object : HeadsetMic {
                override fun connect() = c.open()
                override fun isUp() = upEver && selected && mode == android.media.AudioManager.MODE_IN_COMMUNICATION
                override fun source() = sound()
                override fun release() = c.close()
            } to { mode }
        }
        val speech = { SyntheticSource(syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-12.0)) }
        // cancelled in the middle
        val (h1, mode1) = callHeadset(speech)
        lateinit var rec: PcmRecorder
        val events = Events()
        rec = PcmRecorder({ SyntheticSource(listOf(6.0 to false), onRead = { ms -> if (ms >= 1000) rec.cancel() }) }, Loudness.SOURCE_MIC, 120_000, events, { },
            detectorFactory = detector(), threadFactory = inPlace, headset = h1, headsetEager = true, headsetCar = true)
        assertTrue(rec.start())
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode1(), "cancel")
        // the car's recording breaks (read fails) on probation
        val (h2, mode2) = callHeadset({ object : PcmSource {
            var n = 0
            override fun start() = true
            override fun read(buffer: ShortArray): Int { if (++n > 10) return -1; buffer.fill(3000); return buffer.size }
            override fun stop() = Unit
        } })
        PcmRecorder({ SyntheticSource(syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)) }, Loudness.SOURCE_MIC, 120_000, Events(), { },
            detectorFactory = detector(), threadFactory = inPlace, headset = h2, headsetEager = true, headsetCar = true).start()
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode2(), "the car broke")
        // the channel never comes up
        val (h3, mode3) = callHeadset(speech, upEver = false)
        val e3 = Events()
        PcmRecorder({ SyntheticSource(listOf(0.5 to false) + syllables(4.0) + listOf(3.0 to false), tone = amplitudeOf(-6.0)) }, Loudness.SOURCE_MIC, 120_000, e3, { it.run() },
            detectorFactory = detector(), threadFactory = inPlace, headset = h3, headsetEager = true, headsetCar = true).start()
        assertEquals(android.media.AudioManager.MODE_NORMAL, mode3(), "not up")
        assertEquals(listOf(false), e3.tried.map { it.first }, "given up (and marked by the keyboard)")
    }

    /** stage 5g-5, journal 16:33:25 (peak 0 dBFS): a click at the joint phone → earbuds is not sent */
    @Test fun noClickAtTheJoint() {
        var first = true
        val h = FakeHeadset { object : PcmSource {
            val inner = SyntheticSource(listOf(1.0 to false) + syllables(2.0) + listOf(3.0 to false), tone = amplitudeOf(-10.0))
            override fun start() = inner.start()
            override fun read(buffer: ShortArray): Int { val n = inner.read(buffer); if (first && n > 0 && inner.readMs > 600) { buffer.fill(32767, 0, n); first = false }; return n }
            override fun stop() = inner.stop()
        } }
        val run = recordEager(h, { HonorPhone(SyntheticSource(listOf(8.0 to false)), it) })
        assertFalse(first, "the click came")
        assertTrue(run.r.onHeadset)
        assertTrue(run.r.audio.peak() < 32000, "no click in what is sent")
    }

    @Test fun theWatcherSeesNewAndGoneHeadsets() {
        var new = 0; var gone = 0
        val w = helium314.keyboard.latin.voiceapi.HeadsetWatcher({ new++ }, { gone++ })
        w.added(listOf(sco)) // the devices connected already when it starts
        assertEquals(0, new)
        w.added(listOf(a2dp))
        assertEquals(0, new)
        w.added(listOf(sco))
        assertEquals(1, new, "something with a microphone connected: what was learned is forgotten")
        w.removed(listOf(a2dp))
        assertEquals(0, gone)
        w.removed(listOf(sco))
        assertEquals(1, gone)
    }

    @Test fun cancelGivesTheChannelBack() {
        var rec: PcmRecorder? = null
        val h = FakeHeadset(up = false) { SyntheticSource(listOf(10.0 to false)) }
        val events = Events()
        val r = PcmRecorder({ SyntheticSource(listOf(10.0 to false), onRead = { ms -> if (ms >= 1200) rec!!.cancel() }) }, Loudness.SOURCE_MIC,
            120_000, events, { it.run() }, detectorFactory = detector(), threadFactory = inPlace, headset = h)
        rec = r
        assertTrue(r.start())
        assertEquals(1, h.connected, "asked for in the quiet moment")
        assertTrue(events.list.isEmpty())
        assertFalse(h.holding, "cancelled: the music in the headset comes back")
    }

    @Test fun microphoneCheckKnowsTheHeadsetAndGivesItBack() {
        val h = FakeHeadset { SyntheticSource(listOf(3.0 to true), tone = amplitudeOf(-20.0)) }
        val results = MicCheck.run({ SyntheticSource(listOf(3.0 to false), noise = amplitudeOf(-80.0)) }, msPerSource = 500,
            headset = h, sleep = { })
        assertEquals(listOf(Loudness.SOURCE_MIC, Loudness.SOURCE_VOICE_RECOGNITION, Loudness.SOURCE_HEADSET), results.map { it.source })
        assertEquals(MicCheck.Verdict.GOOD, results.last().verdict)
        assertFalse(h.holding)
        assertNull(MicCheck.best(results), "the headset is never taken as the phone source")
    }
}

/** Audio focus while recording (stage 5g, 5g-5): only when something plays at the tap, asked for once, given back once */
class RecordingFocusTest {
    private class Api(var playingNow: Boolean = true) : RecordingFocus.Api {
        val calls = mutableListOf<String>()
        override fun request(): Boolean { calls.add("request"); return true }
        override fun abandon() { calls.add("abandon") }
        override fun playing() = playingNow
        override fun play() { calls.add("play") }
        val later = mutableListOf<Pair<Long, () -> Unit>>()
        override fun after(delayMs: Long, action: () -> Unit) { later.add(delayMs to action) }
        fun runLater() { val l = later.toList(); later.clear(); l.forEach { it.second() } }
    }

    /** stage 5g-6, the owner: YouTube paused and did not go on by itself: one PLAY, 0.8 s after the focus came back */
    @Test fun playerThatDoesNotResumeGetsOnePlay() {
        val api = Api(playingNow = true)
        val said = mutableListOf<String>()
        val f = RecordingFocus(api) { said.add(it) }
        f.acquire()
        api.playingNow = false // the player paused for the recording
        f.release()
        assertEquals(listOf("request", "abandon"), api.calls, "not at once: the player gets time to go on")
        assertEquals(RecordingFocus.RESUME_CHECK_MS, api.later.single().first)
        api.runLater()
        assertEquals(listOf("request", "abandon", "play"), api.calls)
        assertEquals(listOf(RecordingFocus.RESUME_SENT), said)
        f.release(); api.runLater()
        assertEquals(1, api.calls.count { it == "play" }, "once")
    }

    @Test fun playerThatResumesItselfGetsNothing() {
        val api = Api(playingNow = true)
        val said = mutableListOf<String>()
        val f = RecordingFocus(api) { said.add(it) }
        f.acquire(); f.release()
        api.runLater() // still playing: it went on by itself
        assertFalse("play" in api.calls)
        assertEquals(listOf(RecordingFocus.RESUME_ITSELF), said)
    }

    @Test fun nothingPlayedNothingIsSent() {
        val api = Api(playingNow = false)
        val f = RecordingFocus(api)
        f.acquire(); f.release()
        api.runLater()
        assertTrue(api.calls.isEmpty(), "${api.calls}")
        assertTrue(api.later.isEmpty())
    }

    /** a new dictation right after the last one: no PLAY into the recording */
    @Test fun noPlayIntoTheNextRecording() {
        val api = Api(playingNow = true)
        val f = RecordingFocus(api)
        f.acquire(); api.playingNow = false; f.release()
        api.playingNow = true; f.acquire() // something plays again, a new recording takes the focus
        api.playingNow = false
        api.runLater()
        assertFalse("play" in api.calls)
    }

    /** music plays: it pauses for the recording (the focus) and goes on afterwards (the focus given back) */
    @Test fun playingMusicPausesAndGoesOn() {
        val api = Api(playingNow = true)
        val f = RecordingFocus(api)
        f.release()
        assertEquals(emptyList(), api.calls, "nothing to give back")
        f.acquire(); f.acquire()
        assertTrue(f.held)
        assertEquals(true, f.wasPlaying)
        f.release(); f.release()
        assertEquals(listOf("request", "abandon"), api.calls)
        assertFalse(f.held)
    }

    /** stage 5g-5, the owner: music on pause must not come on after the dictation: the focus is not touched at all */
    @Test fun pausedMusicStaysPaused() {
        val api = Api(playingNow = false)
        val f = RecordingFocus(api)
        f.acquire()
        assertFalse(f.held)
        assertEquals(false, f.wasPlaying)
        api.playingNow = true // our own recording or anything during it does not count: decided at the tap
        f.acquire()
        f.release()
        assertEquals(emptyList(), api.calls, "no request, no abandon: no player is told it may play")
        f.acquire() // the next dictation decides again
        assertEquals(listOf("request"), api.calls)
    }
}
