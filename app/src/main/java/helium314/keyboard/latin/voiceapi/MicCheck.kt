// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

/**
 * "Check microphone" (stage 5b): a short recording with each microphone source, only to measure its peak. The samples
 * are measured and overwritten at once; nothing is kept, saved or sent anywhere.
 */
object MicCheck {
    enum class Verdict { GOOD, QUIET, SILENT, UNAVAILABLE }

    /** [noiseDbfs] (stage 5g-6): the quietest 20 ms, the level of the background */
    class SourceResult(val source: Int, val peakDbfs: Double?, val noiseDbfs: Double? = null) {
        val verdict get() = verdict(peakDbfs)
    }

    /** 5 s in all: 2.5 s with each source */
    const val MS_PER_SOURCE = 2500L
    val SOURCES = listOf(Loudness.SOURCE_MIC, Loudness.SOURCE_VOICE_RECOGNITION)

    /** the headset channel: asked for and awaited up to 1.5 s; null if it does not come up (then it is given back) */
    private fun openHeadset(headset: HeadsetMic, sleep: (Long) -> Unit): PcmSource? {
        if (!headset.connect()) return null
        var waited = 0L
        while (!headset.isUp() && waited < Loudness.HEADSET_CHECK_MS) { sleep(50); waited += 50 }
        return if (headset.isUp()) headset.source() else null
    }

    fun verdict(peakDbfs: Double?): Verdict = when {
        peakDbfs == null -> Verdict.UNAVAILABLE
        peakDbfs >= -35.0 -> Verdict.GOOD
        peakDbfs >= Loudness.EMPTY_SOURCE_DBFS -> Verdict.QUIET
        else -> Verdict.SILENT
    }

    /** the phone source with the loudest peak if it is not silent: used for voice from then on (never the headset) */
    fun best(results: List<SourceResult>): Int? = results
        .filter { it.source >= 0 && (it.verdict == Verdict.GOOD || it.verdict == Verdict.QUIET) } // never the headset or the effects variant
        .maxByOrNull { it.peakDbfs ?: -200.0 }?.source

    /**
     * Blocking, on a background thread. [onProgress] gets the source, the level 0..1 of the last 20 ms and the time
     * left with this source. With a connected [headset] (stage 5g) its microphone is checked last, as
     * [Loudness.SOURCE_HEADSET]: its channel is asked for, awaited up to 1.5 s and always given back.
     */
    fun run(
        sourceFactory: (Int) -> PcmSource,
        sources: List<Int> = SOURCES,
        msPerSource: Long = MS_PER_SOURCE,
        onProgress: (source: Int, level: Float, msLeft: Long) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
        headset: HeadsetMic? = null,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): List<SourceResult> {
        val frame = ShortArray(PcmBuffer.SAMPLE_RATE * SpeechEndDetector.FRAME_MS / 1000)
        val results = mutableListOf<SourceResult>()
        val all = if (headset != null) sources + Loudness.SOURCE_HEADSET else sources
        try {
            for (audioSource in all) {
                if (cancelled()) break
                val s = if (audioSource == Loudness.SOURCE_HEADSET) openHeadset(headset!!, sleep) else sourceFactory(audioSource)
                if (s == null || !s.start()) { headset?.release(); results.add(SourceResult(audioSource, null)); continue }
                var peak = 0
                var quietest = Double.MAX_VALUE
                var samples = 0L
                val total = msPerSource * PcmBuffer.SAMPLE_RATE / 1000
                try {
                    while (samples < total && !cancelled()) {
                        val n = s.read(frame)
                        if (n < 0) break
                        if (n == 0) continue
                        samples += n
                        peak = maxOf(peak, Loudness.peak(frame, n))
                        val rms = SpeechEndDetector.rms(frame, n)
                        if (n >= frame.size) quietest = minOf(quietest, rms)
                        val level = SpeechEndDetector.level(rms)
                        frame.fill(0)
                        onProgress(audioSource, level, (total - samples) * 1000 / PcmBuffer.SAMPLE_RATE)
                    }
                } finally {
                    s.stop()
                    if (audioSource == Loudness.SOURCE_HEADSET) headset?.release()
                }
                results.add(SourceResult(audioSource, if (samples > 0) Loudness.dbfs(peak.toDouble()) else null,
                    quietest.takeIf { it != Double.MAX_VALUE }?.let { Loudness.dbfs(it) }))
            }
        } finally {
            frame.fill(0)
        }
        return results
    }
}
