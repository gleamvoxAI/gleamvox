// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stage 5g-18 (journals 4387/4389/4394, car F0 "in sco, out sco": the HONOR phone hears nothing for 1460–1600 ms of the
 * 1480–1620 ms the car's link needs, with MIC and CAMCORDER, with the call mode before or after the link; earbuds AE:
 * 100 ms): while the link of a car-like device comes up, every source of the phone is opened next to the recording, each
 * with its own AudioRecord, to find one the HONOR does not silence. For each the journal gets: opened or not, how long it
 * gave sound, the peak and the mean level before the device was asked for, in 250 ms pieces while the link came up, and
 * in the second after it; deaf (-90 dBFS) or not. A frame of the recording that is digital silence while the link comes
 * up is replaced with the same time from a source that is not deaf (the loudest, kept while it hears), so the start of
 * the phrase is not lost; when all are deaf nothing changes (as in 4394).
 *
 * [waitBeforeLinkMs]: the order tried (item 4 of the task): the phone sources open, and the device is asked for this long
 * after (300 ms), or at once (0). All calls on the recording thread; sound only in memory, wiped when closed.
 */
class PhoneProbe(
    private val candidates: List<Candidate>,
    val waitBeforeLinkMs: Long,
    /** an AudioRecord for [Candidate], not started yet */
    private val sourceFor: (Candidate) -> PcmSource,
) {
    /** [name] for the journal: the Android source, "MIC@builtin" for MIC with the built-in microphone preferred */
    class Candidate(val name: String, val source: Int, val builtIn: Boolean = false)

    /** peak and mean (RMS) of one piece of time */
    class Level {
        var peak = 0
            private set
        var samples = 0L
            private set
        private var sum = 0.0
        fun add(b: ShortArray, from: Int, n: Int) {
            for (i in from until from + n) { val s = b[i].toInt(); val a = abs(s); if (a > peak) peak = a; sum += s.toDouble() * s }
            samples += n
        }
        val peakDb get() = Loudness.dbfs(peak.toDouble())
        val meanDb get() = Loudness.dbfs(if (samples > 0) sqrt(sum / samples) else 0.0)
        /** digital silence (a peak of 1 or 0, -90 dBFS), or no sound came at all */
        val deaf get() = peak <= 1
        override fun toString() = if (samples == 0L) "—" else "${peakDb.toInt()}/${meanDb.toInt()}"
    }

    /** what one source gave: before the device was asked for, while its link came up (250 ms pieces), after */
    class Track(val name: String) {
        var opened = false
        var samples = 0L
        val before = Level()
        val link = mutableListOf<Level>()
        val after = Level()
        internal var source: PcmSource? = null
        internal val fifo = ShortArray(FIFO)
        internal var fifoStart = 0
        internal var fifoSize = 0
        /** the loudest sample of the last reads (about 60 ms), the RMS of the last read */
        internal val recent = ArrayDeque<Int>()
        internal var lastRms = 0.0
        val ms get() = samples * 1000 / PcmBuffer.SAMPLE_RATE
        /** deaf while the link came up: a whole piece (at least half of 250 ms) was digital silence or had no sound */
        val deafOnLink get() = link.withIndex().any { (i, l) -> (i < link.size - 1 || l.samples >= SEGMENT / 2) && l.deaf }
        /** heard all the time the link came up */
        val alive get() = opened && link.isNotEmpty() && !deafOnLink
        internal val hearsNow get() = recent.isNotEmpty() && recent.max() > 1
    }

    private val tracks = candidates.map { Track(it.name) }
    /** the phone source of the recording itself, measured the same way (it is read by the recorder) */
    private var main: Track? = null
    private var connectAt = -1L
    private var linkEndAt = -1L
    private var linkUp = false
    private var chosen: Track? = null
    /** frames of the recording filled from another source, and from which */
    var filledSamples = 0L
        private set
    private val filledFrom = linkedSetOf<String>()
    var closed = false
        private set
    private var buffer = ShortArray(320)

    val filledMs get() = filledSamples * 1000 / PcmBuffer.SAMPLE_RATE
    fun tracks(): List<Track> = listOfNotNull(main) + tracks

    /** opens every source it can, in the order given; one that does not open is noted, the others are tried still */
    fun start(mainName: String) {
        main = Track("$mainName (recording)").also { it.opened = true }
        for ((i, c) in candidates.withIndex()) {
            val s = try { sourceFor(c) } catch (e: Exception) { null } ?: continue
            if (try { s.start() } catch (e: Exception) { false }) { tracks[i].source = s; tracks[i].opened = true }
        }
    }

    /** the device is asked for now ([t]: samples of the recording so far) */
    fun connecting(t: Long) { if (connectAt < 0) connectAt = t }

    /** its link came up ([up]), or it was given up */
    fun linkEnded(t: Long, up: Boolean) { if (connectAt >= 0 && linkEndAt < 0) { linkEndAt = t; linkUp = up } }

    /** the second after the link is measured: the sources can be closed */
    fun finished(t: Long) = linkEndAt >= 0 && t - linkEndAt >= AFTER

    private fun level(track: Track, t: Long): Level? = when {
        connectAt < 0 || t <= connectAt -> track.before
        linkEndAt < 0 -> {
            val i = ((t - connectAt - 1) / SEGMENT).toInt()
            while (track.link.size <= i) track.link.add(Level())
            track.link[i]
        }
        t - linkEndAt <= AFTER -> track.after
        else -> null
    }

    /** a frame of the recording's own phone source, [t] samples into the recording (at its end) */
    fun feedMain(t: Long, frame: ShortArray, n: Int) {
        val m = main ?: return
        if (closed || n <= 0) return
        m.samples += n
        level(m, t)?.add(frame, 0, n)
    }

    /**
     * reads what the other sources have now, without waiting and never ahead of the recording ([t]); [fill] (the
     * recording's frame is digital silence while the link comes up): the frame is replaced with the same time from the
     * loudest source that is not deaf. Returns the peak of the frame put in, or null when nothing was put in.
     */
    fun feed(t: Long, frame: ShortArray, n: Int, fill: Boolean): Int? {
        if (closed) return null
        if (buffer.size < n) buffer = ShortArray(n)
        for (tr in tracks) {
            val s = tr.source ?: continue
            var got = 0
            var reads = 0
            var peak = 0
            var sum = 0.0
            while (tr.samples < t && reads++ < MAX_READS) {
                val k = try { s.readNow(buffer) } catch (e: Exception) { -1 }
                if (k <= 0) break
                tr.samples += k
                level(tr, t)?.add(buffer, 0, k)
                push(tr, buffer, k)
                for (i in 0 until k) { val v = buffer[i].toInt(); peak = max(peak, abs(v)); sum += v.toDouble() * v }
                got += k
            }
            buffer.fill(0)
            if (got > 0) {
                tr.recent.addLast(peak)
                while (tr.recent.size > RECENT_READS) tr.recent.removeFirst()
                tr.lastRms = sqrt(sum / got)
            }
        }
        if (!fill) return null
        val c = chosen?.takeIf { it.hearsNow } ?: tracks.filter { it.source != null && it.hearsNow }.maxByOrNull { it.lastRms }
            ?.also { tr -> chosen = tr; trim(tr, n) } // a new choice: from its newest sound on
        if (c == null) { chosen = null; return null }
        val k = pop(c, frame, n)
        if (k < n) frame.fill(0, k, n)
        filledSamples += n
        filledFrom.add(c.name)
        return Loudness.peak(frame, n)
    }

    private fun push(tr: Track, b: ShortArray, k: Int) {
        for (i in 0 until k) {
            if (tr.fifoSize == FIFO) { tr.fifoStart = (tr.fifoStart + 1) % FIFO; tr.fifoSize-- }
            tr.fifo[(tr.fifoStart + tr.fifoSize) % FIFO] = b[i]
            tr.fifoSize++
        }
    }

    private fun trim(tr: Track, keep: Int) {
        val drop = tr.fifoSize - keep
        if (drop > 0) { tr.fifoStart = (tr.fifoStart + drop) % FIFO; tr.fifoSize -= drop }
    }

    private fun pop(tr: Track, out: ShortArray, n: Int): Int {
        val k = minOf(n, tr.fifoSize)
        for (i in 0 until k) out[i] = tr.fifo[(tr.fifoStart + i) % FIFO]
        tr.fifoStart = (tr.fifoStart + k) % FIFO
        tr.fifoSize -= k
        return k
    }

    /** closes the other sources and wipes their sound; the levels stay for the journal */
    fun close() {
        if (closed) return
        closed = true
        for (tr in tracks) {
            try { tr.source?.stop() } catch (_: Exception) { }
            tr.source = null
            tr.fifo.fill(0); tr.fifoSize = 0
        }
        buffer.fill(0)
    }

    /** the sources that heard all the time the link came up */
    fun alive(): List<String> = tracks.filter { it.alive }.map { it.name }

    /** one line for the journal: the order, what was filled, and each source (names and numbers only) */
    fun summary(): String = buildString {
        append("order: ").append(if (waitBeforeLinkMs > 0) "phone sources ${waitBeforeLinkMs} ms before the device" else "device at once")
        append("; link ").append(when { linkEndAt < 0 -> "not asked"; linkUp -> "up"; else -> "given up" })
        if (linkEndAt >= 0) append(" after ${(linkEndAt - connectAt) * 1000 / PcmBuffer.SAMPLE_RATE} ms")
        append("; filled ").append(if (filledSamples > 0) "$filledMs ms from ${filledFrom.joinToString("+")}" else "nothing")
        val alive = alive()
        append("; alive: ").append(if (alive.isEmpty()) "none" else alive.joinToString(", "))
        for (tr in tracks()) {
            append(" | ").append(tr.name).append(": ")
            if (!tr.opened) { append("not opened"); continue }
            append(when { tr.link.isEmpty() -> "?"; tr.deafOnLink -> "deaf"; else -> "alive" })
            append(", ${tr.ms} ms, before ${tr.before}, link ")
            append(if (tr.link.isEmpty()) "—" else tr.link.joinToString(" "))
            append(", after ${tr.after}")
        }
    }

    companion object {
        /** stage 5g-20: "Probe the inputs" of the developer mode (off): only then the probe runs */
        const val PREF = "aikb_dev_probe"
        /** 250 ms and 1 s at 16 kHz */
        const val SEGMENT = PcmBuffer.SAMPLE_RATE / 4L
        const val AFTER = PcmBuffer.SAMPLE_RATE.toLong()
        /** the order "phone sources first": this long before the device is asked for */
        const val WAIT_BEFORE_LINK_MS = 300L
        /** at most 0.5 s of each source is kept for filling, and so many reads per frame */
        private const val FIFO = PcmBuffer.SAMPLE_RATE / 2
        private const val MAX_READS = 16
        private const val RECENT_READS = 3

        /**
         * the order of the sources for this tap ([round]: taps with a car-like device so far): turned by one each time, so
         * that sources that could not be opened next to the others come first in later taps; the order of the link
         * changes every tap (300 ms before, at once)
         */
        fun <T> rotated(list: List<T>, round: Int): List<T> =
            if (list.isEmpty()) list else (list.indices).map { list[(it + round.mod(list.size)) % list.size] }
        fun waitFor(round: Int) = if (round % 2 == 0) WAIT_BEFORE_LINK_MS else 0L
    }
}
