// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

/**
 * Stage 5g-24 (the owner: after a long idle the first word is swallowed; journal 4421: "first sound after" 300–350 ms
 * instead of 120–200): the microphone opens at the TOUCH of the microphone key, not at its release. Frames are kept in
 * memory only; at the release (a tap) or the long press the recording takes them over ([adopt]) and goes on from the same
 * source; a touch that becomes nothing (slid away, cancelled, [MAX_MS] without a tap) wipes them and closes the microphone
 * ([discard]). Never in fields where the voice does not record (the caller checks); nothing is sent before the tap.
 */
class PreCapture(
    private val source: PcmSource,
    private val clock: () -> Long = System::currentTimeMillis,
    threadFactory: (Runnable) -> Thread = { r -> Thread(r, "aikb-precapture").apply { isDaemon = true } },
) {
    /** the touch: when the microphone was asked to open */
    val touchedAt: Long = clock()
    /** the first frame of sound (wall clock), 0 before */
    @Volatile var firstFrameAt = 0L
        private set
    /** how long opening the microphone took (ms), for the journal */
    @Volatile var openMs = -1L
        private set
    private val frames = ArrayList<ShortArray>()
    private val lock = Object()
    @Volatile private var stop = false
    @Volatile private var opened = false
    @Volatile private var failed = false
    private var samples = 0L
    private val thread: Thread

    init {
        thread = threadFactory(Runnable { run() })
        thread.start()
    }

    private fun run() {
        val t0 = clock()
        if (!source.start()) { failed = true; openMs = clock() - t0; return }
        openMs = clock() - t0
        opened = true
        val frame = ShortArray(FRAME)
        while (!stop) {
            val n = source.read(frame)
            if (n < 0) { failed = true; break }
            if (n == 0) continue
            if (firstFrameAt == 0L) firstFrameAt = clock()
            synchronized(lock) {
                frames.add(frame.copyOf(n))
                samples += n
                // a touch held much longer than a long press: the oldest is let go (wiped)
                while (samples * 1000 / PcmBuffer.SAMPLE_RATE > MAX_MS && frames.size > 1) samples -= frames.removeAt(0).also { it.fill(0) }.size
            }
        }
    }

    /** ms of sound in memory now */
    fun bufferedMs(): Long = synchronized(lock) { samples * 1000 / PcmBuffer.SAMPLE_RATE }

    /**
     * the recording takes over: the frames so far first, then the same open microphone; null when the microphone did not
     * open (the recording opens its own then)
     */
    fun adopt(): Primed? {
        stop = true
        thread.join(JOIN_MS)
        if (thread.isAlive || failed || !opened) { discard(); return null }
        val queued = synchronized(lock) { ArrayList(frames).also { frames.clear(); samples = 0 } }
        return Primed(queued, source)
    }

    /** nothing came of the touch: the sound is wiped, the microphone closed */
    fun discard() {
        stop = true
        thread.join(JOIN_MS)
        synchronized(lock) { frames.forEach { it.fill(0) }; frames.clear(); samples = 0 }
        source.stop()
    }

    /** the frames of the touch, then the live source; the recording reads it like any source */
    class Primed(private val queued: MutableList<ShortArray>, val live: PcmSource) : PcmSource {
        /** ms of sound from before the tap given to the recording */
        val primedMs: Long = queued.sumOf { it.size.toLong() } * 1000 / PcmBuffer.SAMPLE_RATE
        private var pos = 0
        override val failure get() = live.failure
        override fun start() = true // already recording
        override fun read(buffer: ShortArray): Int {
            val f = queued.firstOrNull() ?: return live.read(buffer)
            val n = minOf(buffer.size, f.size - pos)
            System.arraycopy(f, pos, buffer, 0, n)
            pos += n
            if (pos >= f.size) { f.fill(0); queued.removeAt(0); pos = 0 }
            return n
        }
        override fun readNow(buffer: ShortArray): Int = if (queued.isNotEmpty()) read(buffer) else live.readNow(buffer)
        override fun stop() { queued.forEach { it.fill(0) }; queued.clear(); live.stop() }
    }

    companion object {
        /** the frame of the recorder (20 ms at 16 kHz) */
        const val FRAME = PcmBuffer.SAMPLE_RATE * 20 / 1000
        /** more than a long press (continuous dictation) with room: then the oldest goes */
        const val MAX_MS = 2500L
        /** a touch without a tap or a long press: given up after this */
        const val GIVE_UP_MS = 2000L
        private const val JOIN_MS = 300L
    }
}
