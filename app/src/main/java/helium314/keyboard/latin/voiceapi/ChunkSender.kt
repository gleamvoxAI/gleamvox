// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import helium314.keyboard.latin.openai.CancelSignal
import java.io.File

/**
 * Stage 5g-20 (journal 4397, 10:50: 98 s of speech sent as one piece of 3 MB, the network failed, everything was lost):
 * the pieces of one dictation (phrases of a continuous dictation, pieces of at most 30 s of one phrase) are sent in a line
 * and their texts given out strictly in the order they were spoken ([Events.onText]).
 *
 * A piece that fails because of the network (or a server error) is never lost and never ends the dictation: its sound
 * stays in memory (and in a file of the cache folder, [store]); it is sent again after 1, 3 and 7 s, and again at once
 * whenever the network comes back ([networkBack]). Meanwhile the panel shows a quiet "waiting for the network…"
 * ([Events.onWaiting]); only after [ASK_RETRY_MS] without success "Retry" is offered ([Events.onRetryNeeded],
 * [retryNow]); after [KEEP_MS] the sound is given up ([Events.onGivenUp]). Pieces after a waiting one are sent too,
 * their texts wait for it, so nothing comes out of order.
 *
 * A wrong key or no money stops everything ([Events.onFatal]); other failures (a bad answer) drop that piece only.
 * All calls and events on the main thread ([post]); requests run on [background].
 */
class ChunkSender(
    /** sound, the session's text before (null: none), cancel */
    private val transcribe: (PcmBuffer, String?, CancelSignal) -> TranscriptionResult,
    private val background: (Runnable) -> Unit,
    private val post: (Runnable) -> Unit,
    /** runs on the main thread after a delay; the returned function cancels it */
    private val later: (Long, Runnable) -> (() -> Unit),
    private val clock: () -> Long,
    private val events: Events,
    /** after every answer, with the length of the sent sound: usage, pauses, key rejected */
    private val onResult: (TranscriptionResult, Long) -> Unit = { _, _ -> },
    /** the text before goes with the next piece (continuous dictation and the pieces of one long phrase) */
    private val withContext: Boolean = true,
    private val store: ChunkStore? = null,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) {
    interface Events {
        fun onUploading(bytes: Long) {}
        fun onAnswer(status: Int?, ms: Long, problem: String?) {}
        /** the text of the next piece, in the spoken order (may be empty) */
        fun onText(text: String)
        /** a piece waits for the network ([on]), or none does any more */
        fun onWaiting(on: Boolean) {}
        /** [ASK_RETRY_MS] without the network: "Retry" */
        fun onRetryNeeded() {}
        /** a piece was dropped for good ([problem]); its place in the order is skipped */
        fun onDropped(problem: VoiceApiProblem) {}
        /** after [KEEP_MS] the waiting pieces were given up */
        fun onGivenUp(problem: VoiceApiProblem) {}
        /** a wrong key or no money: nothing more is sent */
        fun onFatal(problem: VoiceApiProblem) {}
        /** nothing is in the line any more (all given out) */
        fun onIdle() {}
    }

    private enum class S { QUEUED, SENDING, WAITING, DONE, DROPPED }

    private inner class Piece(var audio: PcmBuffer?, val ms: Long, val n: Int) {
        var s = S.QUEUED
        var text: String? = null
        var tries = 0
        var failedAt = -1L
        var timer: (() -> Unit)? = null
        var file: File? = null
        val cancel = CancelSignal()
        val finished get() = s == S.DONE || s == S.DROPPED
    }

    private val line = ArrayDeque<Piece>()
    private var inFlight = 0
    private var count = 0
    private var stopped = false
    private var fatal: VoiceApiProblem? = null
    private val sessionText = StringBuilder()
    private var waitingSaid = false
    private var retryAsked = false
    private var keepTimer: (() -> Unit)? = null
    private var askTimer: (() -> Unit)? = null

    /** for the journal: pieces sent, pieces sent again, sound kept for a retry at some time */
    var chunks = 0
        private set
    var retried = 0
        private set
    var keptForRetry = false
        private set
    var lastProblem: VoiceApiProblem? = null
        private set
    val waiting get() = line.count { !it.finished }
    val busy get() = line.isNotEmpty()
    val retryOffered get() = retryAsked

    fun add(audio: PcmBuffer) {
        if (stopped || fatal != null) { audio.wipe(); return }
        line.addLast(Piece(audio, audio.durationMs, count++))
        chunks++
        pump()
    }

    /** the network came back (or another network, a VPN): every waiting piece is sent again now */
    fun networkBack() {
        if (stopped || fatal != null) return
        var any = false
        for (p in line) if (p.s == S.WAITING) { p.timer?.invoke(); p.timer = null; p.s = S.QUEUED; any = true }
        if (any) pump()
    }

    /** "Retry": the same as the network coming back, said by the user */
    fun retryNow() = networkBack()

    /** the dictation is cancelled: nothing more comes out, all sound is wiped (memory and files) */
    fun cancel() {
        stopped = true
        for (p in line) { p.cancel.cancel(); p.timer?.invoke(); forget(p) }
        line.clear()
        keepTimer?.invoke(); askTimer?.invoke(); keepTimer = null; askTimer = null
        sessionText.setLength(0)
    }

    private fun forget(p: Piece) {
        p.audio?.wipe(); p.audio = null
        p.file?.let { store?.delete(it) }; p.file = null
    }

    private fun pump() {
        while (inFlight < maxInFlight && fatal == null && !stopped) {
            val p = line.firstOrNull { it.s == S.QUEUED } ?: return
            send(p)
        }
    }

    private fun send(p: Piece) {
        val audio = p.audio ?: run { p.s = S.DROPPED; giveOut(); return }
        p.s = S.SENDING
        inFlight++
        if (p.tries > 0) retried++
        p.tries++
        val context = if (withContext) sessionText.toString().takeLast(TranscriptionClient.CONTEXT_CHARS).ifEmpty { null } else null
        events.onUploading(p.ms * PcmBuffer.SAMPLE_RATE * 2 / 1000 + WavMultipart.WAV_HEADER_BYTES)
        background(Runnable {
            val start = System.nanoTime()
            val result = try {
                if (p.cancel.isCancelled) TranscriptionResult.Failed(VoiceApiProblem.CANCELLED)
                else transcribe(audio, context, p.cancel)
            } catch (e: Exception) { TranscriptionResult.Failed(VoiceApiProblem.NETWORK) }
            val took = (System.nanoTime() - start) / 1_000_000
            post(Runnable { onAnswer(p, result, took) })
        })
    }

    private fun onAnswer(p: Piece, result: TranscriptionResult, took: Long) {
        inFlight--
        val cancelled = result is TranscriptionResult.Failed && result.problem == VoiceApiProblem.CANCELLED
        if (!cancelled) onResult(result, p.ms)
        if (stopped) { forget(p); return }
        events.onAnswer(if (result is TranscriptionResult.Failed) result.status else 200, took,
            (result as? TranscriptionResult.Failed)?.problem?.name?.lowercase())
        when (result) {
            is TranscriptionResult.Ok -> { p.text = result.text.trim(); p.s = S.DONE; forget(p) }
            is TranscriptionResult.Failed -> {
                lastProblem = result.problem
                when {
                    result.problem in FATAL -> { stopAll(result.problem); return }
                    cancelled -> { p.s = S.DROPPED; forget(p) }
                    result.problem in TRANSIENT -> wait(p)
                    else -> { p.s = S.DROPPED; forget(p); events.onDropped(result.problem) }
                }
            }
        }
        giveOut()
        pump()
    }

    /** a failure of the network: the sound is kept, sent again after 1, 3, 7 s and whenever the network comes back */
    private fun wait(p: Piece) {
        p.s = S.WAITING
        keptForRetry = true
        if (p.failedAt < 0) p.failedAt = clock()
        if (p.file == null) p.audio?.let { a -> p.file = store?.save(a) }
        val delay = RETRY_DELAYS_MS.getOrNull(p.tries - 1)
        if (delay != null) p.timer = later(delay, Runnable { p.timer = null; if (p.s == S.WAITING && !stopped) { p.s = S.QUEUED; pump() } })
        if (!waitingSaid) {
            waitingSaid = true
            events.onWaiting(true)
            askTimer = later(ASK_RETRY_MS, Runnable { askTimer = null; if (line.any { it.s == S.WAITING } && !stopped) { retryAsked = true; events.onRetryNeeded() } })
            keepTimer = later(KEEP_MS, Runnable { keepTimer = null; giveUp() })
        }
    }

    /** [KEEP_MS] without the network: the waiting pieces are given up (their sound wiped) */
    private fun giveUp() {
        if (stopped) return
        var any = false
        for (p in line) if (!p.finished && p.s != S.SENDING) { p.cancel.cancel(); p.timer?.invoke(); forget(p); p.s = S.DROPPED; any = true }
        if (any) events.onGivenUp(lastProblem ?: VoiceApiProblem.NETWORK)
        giveOut()
    }

    private fun stopAll(problem: VoiceApiProblem) {
        fatal = problem
        stopped = true
        for (p in line) { p.cancel.cancel(); p.timer?.invoke(); forget(p) }
        line.clear()
        keepTimer?.invoke(); askTimer?.invoke()
        events.onFatal(problem)
        events.onIdle()
    }

    /** the finished pieces at the head of the line go out, in the order they were spoken */
    private fun giveOut() {
        while (line.isNotEmpty() && line.first().finished) {
            val p = line.removeFirst()
            val text = p.text ?: continue
            if (sessionText.isNotEmpty() && text.isNotEmpty()) sessionText.append(' ')
            sessionText.append(text)
            if (sessionText.length > 4 * TranscriptionClient.CONTEXT_CHARS) sessionText.delete(0, sessionText.length - TranscriptionClient.CONTEXT_CHARS)
            events.onText(text)
        }
        if (waitingSaid && line.none { it.s == S.WAITING }) {
            waitingSaid = false; retryAsked = false
            askTimer?.invoke(); keepTimer?.invoke(); askTimer = null; keepTimer = null
            events.onWaiting(false)
        }
        if (line.isEmpty()) events.onIdle()
    }

    companion object {
        /** pieces recognized at the same time (docs/DESIGN.md: "не более 2") */
        const val MAX_IN_FLIGHT = 2
        /** sent again after these pauses; then only when the network comes back or "Retry" is pressed */
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 7_000)
        /** "Retry" is offered after this long without the network; the sound is kept at most [KEEP_MS] */
        const val ASK_RETRY_MS = 120_000L
        const val KEEP_MS = 600_000L
        /** worth sending again: the network, a server error, a moment of too many requests */
        val TRANSIENT = setOf(VoiceApiProblem.NETWORK, VoiceApiProblem.SERVER, VoiceApiProblem.RATE_LIMIT)
        /** these stop the whole dictation: the key is wrong or there is no money */
        val FATAL = setOf(VoiceApiProblem.KEY_INVALID, VoiceApiProblem.KEY_REJECTED, VoiceApiProblem.NO_BALANCE,
            VoiceApiProblem.PAUSED_BALANCE, VoiceApiProblem.NO_KEY, VoiceApiProblem.INTERNET_OFF, VoiceApiProblem.MODE_OFF)
    }
}

/**
 * Stage 5g-20: the sound of pieces waiting for the network, also in files of the cache folder (never in a backup, private
 * to the app). A file lives only while its piece waits: deleted after it is recognized, given up or cancelled, and
 * every file left from before (the process was killed) is deleted when the voice starts ([clear]).
 */
class ChunkStore(private val dir: File) {
    fun save(audio: PcmBuffer): File? = try {
        dir.mkdirs()
        val f = File.createTempFile("piece", ".pcm", dir)
        val bytes = ByteArray(audio.byteCount)
        audio.copyTo(bytes, 0)
        f.writeBytes(bytes)
        bytes.fill(0)
        f
    } catch (e: Exception) { null }

    fun delete(f: File) {
        try { if (f.exists()) { f.writeBytes(ByteArray(f.length().toInt())); f.delete() } } catch (_: Exception) { }
    }

    fun clear() { try { dir.listFiles()?.forEach { delete(it) } } catch (_: Exception) { } }

    fun files(): List<File> = dir.listFiles()?.toList().orEmpty()

    companion object {
        const val DIR = "voice-pending"
        fun of(cacheDir: File) = ChunkStore(File(cacheDir, DIR))
    }
}
