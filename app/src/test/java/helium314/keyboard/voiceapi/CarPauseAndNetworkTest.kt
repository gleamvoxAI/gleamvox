// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.voice.Cancellable
import helium314.keyboard.latin.voice.RecordingFocus
import helium314.keyboard.latin.voice.ScreenKeeper
import helium314.keyboard.latin.voice.Scheduler
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceHost
import helium314.keyboard.latin.voice.VoiceInputController
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.latin.voice.startedState
import helium314.keyboard.latin.voiceapi.ChunkSender
import helium314.keyboard.latin.voiceapi.ChunkStore
import helium314.keyboard.latin.voiceapi.ContinuousSpeechEngine
import helium314.keyboard.latin.voiceapi.HeadsetMic
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.MicRouting
import helium314.keyboard.latin.voiceapi.OpenAiSpeechEngine
import helium314.keyboard.latin.voiceapi.PcmBuffer
import helium314.keyboard.latin.voiceapi.PcmRecorder
import helium314.keyboard.latin.voiceapi.PcmSource
import helium314.keyboard.latin.voiceapi.SpeechEndDetector
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 5g-20: the car joins in the first pause (never at the tap), pieces of at most 30 s, nothing lost when the network
 * fails (sent again, in order), the player paused at the tap, the screen kept on after the dictation.
 */
class CarPauseAndNetworkTest {
    private val inPlace: (Runnable) -> Thread = { r -> object : Thread() { override fun start() = r.run() } }

    /** delays of [ChunkSender] and [ScreenKeeper], run by hand */
    private class Later {
        val q = mutableListOf<Pair<Long, Runnable>>()
        val fn: (Long, Runnable) -> (() -> Unit) = { ms, r -> val e = ms to r; q.add(e); { q.remove(e) } }
        fun runAll(maxMs: Long = Long.MAX_VALUE) { val l = q.filter { it.first <= maxMs }; q.removeAll(l); l.forEach { it.second.run() } }
    }

    // ---- the line of pieces ----

    private class Texts : ChunkSender.Events {
        val texts = mutableListOf<String>()
        var waiting = mutableListOf<Boolean>()
        var retryNeeded = 0
        var givenUp: VoiceApiProblem? = null
        var idle = 0
        override fun onText(text: String) { texts.add(text) }
        override fun onWaiting(on: Boolean) { waiting.add(on) }
        override fun onRetryNeeded() { retryNeeded++ }
        override fun onGivenUp(problem: VoiceApiProblem) { givenUp = problem }
        override fun onIdle() { idle++ }
    }

    private fun piece(ms: Int) = PcmBuffer().also { it.append(ShortArray(16 * ms) { 1000 }, 16 * ms) }

    /** supplement 1, item 4: a network error on the 2nd of 4 pieces → after the retry the text is whole and in order */
    @Test fun networkErrorOnTheSecondOfFourPieces() {
        val later = Later()
        val e = Texts()
        val seen = mutableListOf<PcmBuffer>()
        var failSecond = true
        val dir = Files.createTempDirectory("pending").toFile()
        val store = ChunkStore(dir)
        val s = ChunkSender({ a, _, _ ->
            if (a !in seen) seen.add(a)
            val i = seen.indexOf(a)
            if (i == 1 && failSecond) { failSecond = false; TranscriptionResult.Failed(VoiceApiProblem.NETWORK) }
            else TranscriptionResult.Ok("T${i + 1}", null)
        }, { it.run() }, { it.run() }, later.fn, { 0L }, e, store = store)
        for (ms in listOf(100, 110, 120, 130)) s.add(piece(ms))
        assertEquals(listOf("T1"), e.texts, "the 3rd and 4th wait for the 2nd")
        assertEquals(1, store.files().size, "the waiting sound is also in the cache folder")
        assertEquals(listOf(true), e.waiting, "a quiet \"waiting for the network\"")
        assertEquals(1_000L, later.q.minOf { it.first }, "sent again after 1 s")
        later.runAll(1_000)
        assertEquals(listOf("T1", "T2", "T3", "T4"), e.texts, "whole and in order")
        assertEquals(listOf(true, false), e.waiting)
        assertEquals(1, s.retried); assertEquals(4, s.chunks); assertTrue(s.keptForRetry)
        assertTrue(store.files().isEmpty(), "the file is gone after the success")
        assertEquals(0, e.retryNeeded)
    }

    /** the clarification: the network is gone for a while; when it is back everything goes in by itself, in order */
    @Test fun theNetworkComesBackAndAllPiecesGoInByThemselves() {
        val later = Later()
        val e = Texts()
        var online = true
        val seen = mutableListOf<PcmBuffer>()
        val s = ChunkSender({ a, _, _ ->
            if (a !in seen) seen.add(a)
            if (!online) TranscriptionResult.Failed(VoiceApiProblem.NETWORK) else TranscriptionResult.Ok("Фраза ${seen.indexOf(a) + 1}.", null)
        }, { it.run() }, { it.run() }, later.fn, { 0L }, e)
        s.add(piece(100))
        online = false
        s.add(piece(110)); s.add(piece(120))
        later.runAll(1_000); later.runAll(3_000); later.runAll(7_000) // three retries, still no network
        assertEquals(listOf("Фраза 1."), e.texts)
        s.add(piece(130))
        online = true
        s.networkBack() // ConnectivityManager: the network is back
        assertEquals(listOf("Фраза 1.", "Фраза 2.", "Фраза 3.", "Фраза 4."), e.texts)
        assertEquals(0, e.retryNeeded, "nobody had to press anything")
    }

    /** no network for 2 min: "Retry"; after 10 min the sound is given up */
    @Test fun retryAfterTwoMinutesAndGivenUpAfterTen() {
        val later = Later()
        val e = Texts()
        val s = ChunkSender({ _, _, _ -> TranscriptionResult.Failed(VoiceApiProblem.NETWORK) }, { it.run() }, { it.run() }, later.fn, { 0L }, e)
        s.add(piece(100))
        later.runAll(ChunkSender.ASK_RETRY_MS)
        assertEquals(1, e.retryNeeded)
        assertNull(e.givenUp)
        later.runAll(ChunkSender.KEEP_MS)
        assertEquals(VoiceApiProblem.NETWORK, e.givenUp)
        assertFalse(s.busy)
    }

    /** the same through the continuous dictation: 4 phrases, the network is gone for ~5 s in the middle */
    @Test fun continuousDictationOfFourPhrasesSurvivesFiveSecondsWithoutNetwork() {
        val later = Later()
        val committed = mutableListOf<String>()
        var phoneMs = 0L
        val seen = mutableListOf<PcmBuffer>()
        val sound = listOf(0.5 to false) + syllables(1.0) + listOf(2.0 to false) + syllables(1.0) + listOf(2.0 to false) +
            syllables(1.0) + listOf(2.0 to false) + syllables(1.0) + listOf(6.0 to false)
        var engine: ContinuousSpeechEngine? = null
        val host = object : VoiceHost {
            override fun hasPermission() = true
            override fun requestPermission() = Unit
            override fun languageTag() = "ru-RU"
            override fun commitText(text: String) { committed.add(text) }
            override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) = Unit
            override fun onLevel(level: Float) = Unit
        }
        val controller = VoiceInputController({
            ContinuousSpeechEngine(
                recorderFactory = { l -> PcmRecorder({ SyntheticSource(sound, onRead = { phoneMs = it }) }, Loudness.SOURCE_MIC, 300_000, l, { it.run() },
                    detectorFactory = { SpeechEndDetector(endSilenceMs = 1300, noSpeechTimeoutMs = 3000) }, threadFactory = inPlace,
                    continuous = PcmRecorder.Continuous(30_000, 60 * 60_000)) },
                transcribe = { a, _, _, _, _ ->
                    if (a !in seen) seen.add(a)
                    // the network is gone from 3 s to 8 s of the dictation (phrases 2 and 3 are sent then)
                    if (phoneMs in 3_000..8_000) TranscriptionResult.Failed(VoiceApiProblem.NETWORK)
                    else TranscriptionResult.Ok("Фраза ${seen.indexOf(a) + 1}.", null)
                },
                background = { it.run() }, post = { it.run() }, onResult = { _, _ -> }, sessionMs = 300_000, later = later.fn,
            ).also { engine = it }
        }, host, object : Scheduler { override fun schedule(delayMs: Long, action: () -> Unit) = Cancellable { } })
        controller.onMicPressed()
        // the recording is over (it ran in place); the retries come with the timers (the network is back by then)
        repeat(4) { later.runAll(7_000) }
        assertEquals(listOf("Фраза 1.", "Фраза 2.", "Фраза 3.", "Фраза 4."), committed, "all four, in order")
        assertTrue(engine!!.retried >= 1)
        assertEquals(VoiceState.IDLE, controller.state)
    }

    // ---- one long recording in pieces of at most 30 s ----

    /** 95 s of speech: 4 pieces (30 + 30 + 30 + the rest); a network error on the 2nd; the text whole, in order */
    @Test fun aLongPhraseGoesInPiecesAndSurvivesTheNetwork() {
        val later = Later()
        val seen = mutableListOf<PcmBuffer>()
        val sizes = mutableListOf<Long>()
        var failSecond = true
        var final: String? = null
        val host = object : VoiceHost {
            override fun hasPermission() = true
            override fun requestPermission() = Unit
            override fun languageTag() = "ru-RU"
            override fun commitText(text: String) { final = text }
            override fun onStateChanged(state: VoiceState, partial: String, error: VoiceError?) = Unit
            override fun onLevel(level: Float) = Unit
        }
        var engine: OpenAiSpeechEngine? = null
        val controller = VoiceInputController({
            OpenAiSpeechEngine(
                recorderFactory = { l -> PcmRecorder({ SyntheticSource(listOf(0.3 to false) + syllables(95.0) + listOf(3.0 to false)) },
                    Loudness.SOURCE_MIC, 120_000, l, { it.run() }, detectorFactory = { SpeechEndDetector(endSilenceMs = 1300) },
                    threadFactory = inPlace, chunkMs = PcmRecorder.CHUNK_MS) },
                transcribe = { a, _, _, _ ->
                    if (a !in seen) { seen.add(a); sizes.add(a.durationMs) }
                    val i = seen.indexOf(a)
                    if (i == 1 && failSecond) { failSecond = false; TranscriptionResult.Failed(VoiceApiProblem.NETWORK) }
                    else TranscriptionResult.Ok("T${i + 1}", null)
                },
                background = { it.run() }, post = { it.run() }, onResult = { _, _ -> }, recordMs = 120_000, later = later.fn,
            ).also { engine = it }
        }, host, object : Scheduler { override fun schedule(delayMs: Long, action: () -> Unit) = Cancellable { } })
        controller.onMicPressed()
        assertNull(final, "the 2nd piece waits")
        later.runAll(1_000)
        assertEquals("T1 T2 T3 T4", final)
        assertEquals(4, sizes.size, "$sizes")
        assertTrue(sizes.take(3).all { it in 25_000L..30_000L }, "$sizes")
        assertEquals(4, engine!!.chunks); assertEquals(1, engine!!.retried)
    }

    // ---- the car in the first pause ----

    /**
     * A car whose link comes up [linkMs] after it is asked for; its microphone gives the speaker's sound only then. The
     * phone hears all the time (a phone that is not deaf while the link comes up). Both on the time of the phone.
     */
    private class Car(val sound: List<Pair<Double, Boolean>>, val linkMs: Long = 1500) {
        var now = 0L
        var askedAt = -1L
        val asked = mutableListOf<Long>()
        var released = 0
        val car = object : HeadsetMic {
            override fun connect(): Boolean { askedAt = now; asked.add(now); return true }
            override fun isUp() = askedAt >= 0 && now - askedAt >= linkMs
            override fun source(): PcmSource = shifted(-12.0, isCar = true)
            override fun release() { askedAt = -1; released++ }
        }
        /** the same sound from the time of the phone on; every source of the phone moves the time, the car does not */
        fun shifted(db: Double, isCar: Boolean = false): PcmSource = object : PcmSource {
            val inner = SyntheticSource(sound, tone = amplitudeOf(db), onRead = if (isCar) null else ({ ms: Long -> now = ms }))
            val skip = ShortArray(320)
            override fun start(): Boolean { val until = now; var at = 0L; while (at < until) { inner.read(skip); at += 20 }; return inner.start() }
            override fun read(buffer: ShortArray): Int {
                val n = inner.read(buffer)
                if (isCar && n > 0 && !car.isUp()) buffer.fill(0, 0, n)
                return n
            }
            override fun stop() = inner.stop()
        }
        fun phone(): PcmSource = shifted(-6.0)
    }

    private class Phrases : PcmRecorder.Listener {
        val phrases = mutableListOf<Long>()
        val mics = mutableListOf<Boolean>()
        var asked = 0
        override fun onLevel(level: Float) = Unit
        override fun onEnd(hadSpeech: Boolean, byUser: Boolean) = Unit
        override fun onNoSpeech() = Unit
        override fun onFailed() = Unit
        override fun onPhrase(audio: PcmBuffer, peakDbfs: Double) { phrases.add(audio.durationMs) }
        override fun onMic(headset: Boolean, fellBack: Boolean) { mics.add(headset) }
        override fun onHeadsetAsked() { asked++ }
    }

    private fun recordCar(c: Car): Pair<PcmRecorder, Phrases> {
        val l = Phrases()
        val r = PcmRecorder({ c.phone() }, Loudness.SOURCE_MIC, 300_000, l, { it.run() },
            detectorFactory = { SpeechEndDetector(endSilenceMs = 1300, noSpeechTimeoutMs = 5000) }, threadFactory = inPlace,
            continuous = PcmRecorder.Continuous(30_000, 60 * 60_000), headset = c.car, headsetCar = true,
            phoneSourceWhileHeadset = Loudness.SOURCE_CAMCORDER, headsetAfterPhrase = true)
        assertTrue(r.start())
        return r to l
    }

    /** the tap: the phone only; the car is asked for in the pause after the first phrase, the next phrases come from it */
    @Test fun carJoinsInTheFirstPauseAfterAPhrase() {
        val c = Car(listOf(0.5 to false) + syllables(1.0) + listOf(4.0 to false) + syllables(3.0) + listOf(2.0 to false) + syllables(1.0) + listOf(6.0 to false))
        val (r, l) = recordCar(c)
        assertTrue(c.asked.isNotEmpty(), "the car was asked for")
        assertTrue(c.asked.first() >= 2_800, "only after the first phrase (ended about 2.8 s): ${c.asked}")
        assertEquals(1, l.asked)
        assertTrue(true in l.mics, "the car records the next phrases: ${l.mics}")
        assertEquals(3, l.phrases.size, "${l.phrases}")
        assertTrue(c.released >= 1, "given back at the end")
        assertTrue(r.onHeadset || r.headsetLeft || c.released > 0)
    }

    /** speech begins while the link comes up: that phrase is finished by the phone, the car is tried in the next pause */
    @Test fun speechBeforeTheLinkIsNotLost() {
        val c = Car(listOf(0.5 to false) + syllables(1.0) + listOf(2.0 to false) + syllables(1.0) + listOf(4.0 to false) + syllables(3.0) + listOf(6.0 to false))
        val (_, l) = recordCar(c)
        assertTrue(c.asked.size >= 2, "asked again in the next pause: ${c.asked}")
        assertEquals(3, l.phrases.size, "no phrase lost: ${l.phrases}")
        assertTrue(l.phrases[1] >= 1_000, "the second phrase whole, by the phone: ${l.phrases}")
    }

    /** one phrase with a car: the phone alone; continuous: the car after the first phrase; no car: nothing */
    @Test fun carPlan() {
        assertEquals(MicRouting.CarPlan.PHONE_ONLY, MicRouting.carPlan(carLike = true, continuous = false))
        assertEquals(MicRouting.CarPlan.AFTER_FIRST_PHRASE, MicRouting.carPlan(carLike = true, continuous = true))
        assertEquals(MicRouting.CarPlan.NONE, MicRouting.carPlan(carLike = false, continuous = true))
    }

    // ---- the player at the tap ----

    /** a player was paused at the tap: what it still played in the first 150 ms is not sent (no speech in it) */
    @Test fun firstMomentOfAPausedPlayerIsMuted() {
        val l = Phrases()
        val r = PcmRecorder({ SyntheticSource(listOf(0.5 to false) + syllables(1.0) + listOf(3.0 to false), noise = amplitudeOf(-30.0)) },
            Loudness.SOURCE_MIC, 60_000, l, { it.run() }, detectorFactory = { SpeechEndDetector(endSilenceMs = 1300) },
            threadFactory = inPlace, muteStartMs = PcmRecorder.MUTE_START_MS)
        assertTrue(r.start())
        val b = ByteArray(r.audio.byteCount).also { r.audio.copyTo(it, 0) }
        assertTrue((0 until 150 * 32).all { b[it] == 0.toByte() }, "the first 150 ms are silence")
        assertTrue((160 * 32 until 400 * 32).any { b[it] != 0.toByte() }, "then the sound")
    }

    /** "something plays": a started player only (paused ones are in the list too and must never get PLAY) */
    @Test fun startedPlayersCount() {
        assertTrue(startedState("AudioPlaybackConfiguration piid:15 deviceIds:[2] type:android.media.MediaPlayer u/pid:10123/4567 state:started attr:AudioAttributes: usage=USAGE_MEDIA"))
        assertFalse(startedState("AudioPlaybackConfiguration piid:15 type:exoplayer u/pid:10123/4567 state:paused attr:…"))
        assertFalse(startedState("AudioPlaybackConfiguration piid:16 state:stopped"))
    }

    /** the focus is taken when a player plays (also seen only in the started players), and PLAY comes only then */
    @Test fun focusAndPlayOnlyWhenSomethingPlayed() {
        class Api(var playingNow: Boolean, val started: Int) : RecordingFocus.Api {
            val calls = mutableListOf<String>()
            override fun request(): Boolean { calls.add("request"); return true }
            override fun abandon() { calls.add("abandon") }
            override fun playing() = playingNow
            override fun playingConfigs() = started
            override fun play() { calls.add("play") }
        }
        val playing = Api(playingNow = true, started = 1)
        val f = RecordingFocus(playing)
        f.acquire()
        assertEquals(listOf("request"), playing.calls, "before anything is recorded")
        assertEquals(1, f.configs)
        playing.playingNow = false
        f.release()
        assertEquals(listOf("request", "abandon", "play"), playing.calls)
        val silent = Api(playingNow = false, started = 0)
        val g = RecordingFocus(silent)
        g.acquire(); g.release()
        assertEquals(emptyList(), silent.calls, "nothing played: nothing at all")
    }

    // ---- the screen ----

    /** supplement 2: on while recording, 5 s after the text went in; a touch or the keyboard closing: the normal timeout */
    @Test fun screenStaysOnFiveSecondsAfterTheText() {
        val later = Later()
        val flags = mutableListOf<Boolean>()
        val k = ScreenKeeper({ flags.add(it) }, later.fn)
        k.keep() // recording
        assertEquals(listOf(true), flags)
        k.inserted(); k.ended()
        assertTrue(k.on, "still on after the dictation")
        assertEquals(ScreenKeeper.AFTER_INSERT_MS, later.q.single().first)
        later.runAll()
        assertEquals(listOf(true, false), flags)
        // a touch before the 5 s are over
        k.keep(); k.inserted(); k.ended(); k.release()
        assertFalse(k.on); assertTrue(later.q.isEmpty())
        // no text (cancel, error): off at once
        k.keep(); k.ended()
        assertFalse(k.on)
    }
}
