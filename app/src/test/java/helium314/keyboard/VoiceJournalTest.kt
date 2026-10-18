// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.voice.VoiceAttempt
import helium314.keyboard.latin.voice.VoiceJournal
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** stage 5zh, item 6: the voice journal keeps the last 30 dictations on the phone, without text and sound */
class VoiceJournalTest {
    private val dir = Files.createTempDirectory("aikb-journal").toFile()
    private val file = File(dir, VoiceJournal.FILE_NAME)
    private fun journal() = VoiceJournal(file) { it.run() }

    private fun okAttempt(at: Long) = VoiceAttempt(at).apply {
        engine = "openai"; started = true; source = "MIC"; peakDb = -23; speech = true; recordMs = 3400; stop = "pause"
        uploadKb = 107; uploadStatus = 200; uploadMs = 1840; words = 12; inserted = "yes"
    }

    @Test fun keepsTheLastThirtyNewestFirstAndSurvivesARestart() {
        val j = journal()
        for (i in 1..35) j.add(okAttempt(i * 1000L))
        val e = j.entries()
        assertEquals(30, e.size)
        assertEquals(35_000L, e.first().at)
        assertEquals(6_000L, e.last().at)
        val again = journal().entries() // a new process reads the file
        assertEquals(e.map { it.at }, again.map { it.at })
        assertEquals(12, again.first().words)
        assertEquals("yes", again.first().inserted)
    }

    @Test fun onlyStepsCodesAndNumbersNeverText() {
        val j = journal()
        j.add(okAttempt(1))
        val keys = Regex("\"(\\w+)\":").findAll(file.readText()).map { it.groupValues[1] }.toSet()
        val allowed = setOf("at", "engine", "started", "source", "notStarted", "peakDb", "speech", "recordMs", "stop",
            "uploadStatus", "uploadMs", "uploadKb", "words", "inserted", "error", "step",
            // stage 5g, 5g-2: names and numbers too
            "mode", "phrases", "failedPhrases", "mic", "firstFrameMs", "headsetMs", "btDevices", "micReason", "phoneBeforeDb", "phoneAfterDb", "musicPlaying", "focusTaken", "resume", "probeHeadsetDb", "probePhoneDb", "probeMs", "probeFrames", "strike", "gapMs", "gapOfMs", "gapPeakDb", "sameFrames", "bothFrames", "phoneMs", "headsetRecMs",
            // stage 5g-6: the noise, as numbers
            "speechPercent", "noiseDb", "noisePhrases", "effects", "place",
            // stage 5g-18: source names and levels
            "probe", "filledMs",
            // stage 5g-19: the speech detector, numbers
            "vad", "probAvg", "probMax", "vadThreshold", "numbers",
            // stage 5g-20: the car, the playback, the pieces and retries, as names and numbers
            "car", "playbackConfigs", "focusAtMs", "chunks", "retried", "keptForRetry",
            // stage 5g-21: the probe of the car mute call
            "carMute", "carMuteSystem")
        assertTrue(allowed.containsAll(keys), keys.toString())
        // the class has no field that could hold text or sound
        val fields = VoiceAttempt::class.java.declaredFields.map { it.name }.filter { it != "Companion" }
        assertTrue(fields.none { it.contains("text", true) || it.contains("audio", true) || it.contains("sound", true) }, fields.toString())
    }

    @Test fun linesAndResultsInPlainTerms() {
        val ok = okAttempt(0)
        val line = ok.line()
        for (part in listOf("openai", "recording MIC", "peak -23 dBFS", "speech", "stop: pause", "upload 107 KB", "HTTP 200, 1840 ms",
                "words 12", "inserted: yes")) assertTrue(part in line, "$part in $line")
        assertEquals(VoiceAttempt.Result.INSERTED, ok.result())
        assertEquals(VoiceAttempt.Result.NOT_STARTED, VoiceAttempt(0).apply { notStarted = "busy" }.result())
        assertEquals(VoiceAttempt.Result.WAITING, VoiceAttempt(0).apply { inserted = "pending" }.result())
        assertEquals(VoiceAttempt.Result.CANCELLED, VoiceAttempt(0).apply { stop = "cancel" }.result())
        val failed = VoiceAttempt(0).apply { engine = "openai"; uploadKb = 50; uploadMs = 30_000; error = "openai:network"; step = "upload" }
        assertEquals(VoiceAttempt.Result.FAILED, failed.result())
        assertTrue("no answer, 30000 ms" in failed.line() && "error openai:network at upload" in failed.line(), failed.line())
    }

    @Test fun clearAndBrokenFile() {
        val j = journal()
        j.add(okAttempt(1))
        j.clear()
        assertTrue(j.entries().isEmpty())
        assertFalse(file.exists())
        file.writeText("not json")
        assertTrue(journal().entries().isEmpty(), "a broken file is ignored")
    }

    @Test fun wordsAreCountedNotKept() {
        assertEquals(4, VoiceJournal.words("Привет, как твои дела?"))
        assertEquals(0, VoiceJournal.words("  … "))
        assertEquals(2, VoiceJournal.words("ok — 5"))
    }
}
