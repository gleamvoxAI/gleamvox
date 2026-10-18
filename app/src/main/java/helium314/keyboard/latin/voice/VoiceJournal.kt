// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Steps of one dictation reported by the recognizers for the voice journal (stage 5zh, item 6): numbers and names only,
 * never text or sound.
 */
sealed class VoiceTrace {
    /**
     * The microphone records, from [source] (MIC, VOICE_RECOGNITION, HEADSET or "system" for the Android recognizer);
     * [mic] (stage 5g): headset, phone, or headset→phone when the headset was wanted but the phone records
     */
    class RecordingStarted(val source: String, val mic: String? = null) : VoiceTrace()
    /** the microphone could not be opened: [reason] "busy" or "permission" */
    class RecordingFailed(val reason: String) : VoiceTrace()
    /** the recording ended: [end] pause, done, max, no_speech, failed; [peakDbfs] before any gain */
    class Recorded(val peakDbfs: Double, val speech: Boolean, val end: String, val ms: Long) : VoiceTrace()
    /** the phrase is being sent: about [bytes] */
    class Uploading(val bytes: Long) : VoiceTrace()
    /** the whole sound reached OpenAI; the answer is awaited */
    object Sent : VoiceTrace()
    /** the answer of OpenAI: HTTP [status] (null: no answer), time from sending, [problem] of a failure */
    class Answer(val status: Int?, val ms: Long, val problem: String?) : VoiceTrace()
    /** stage 5g-2: the first frame of the microphone came at [atMs] (wall clock) */
    class FirstFrame(val atMs: Long) : VoiceTrace()
    /** stage 5g-2: the headset channel came up ([up]) or was given up, [ms] after the start of the recording */
    class HeadsetTried(val up: Boolean, val ms: Long) : VoiceTrace()
    /** stage 5g-3: the headset gave silence where the phone heard sound: no headset */
    object HeadsetRejected : VoiceTrace()
    /** stage 5g-3: the headset heard the speaker: a mark "no sound" of it goes away */
    object HeadsetHeard : VoiceTrace()
    /** stage 5g-6: share of frames with speech (%), the noise level (dBFS), phrases dropped as noise */
    class Noise(val speechPercent: Int, val noiseDbfs: Double, val droppedPhrases: Int) : VoiceTrace()
    /** stage 5g-3: the phone's peak before the headset was asked for and after it was given back */
    class PhoneLevels(val beforeDbfs: Double, val afterDbfs: Double) : VoiceTrace()
    /** stage 5g-24: the phone's peak in 100 ms windows of the first 800 ms */
    class EarlyLevels(val dbfs: List<Int>) : VoiceTrace()
    /** stage 5g-7: the peaks of the headset (or car) and of the phone when the probation decided */
    class ProbationLevels(val headsetDbfs: Double, val phoneDbfs: Double, val afterUpMs: Long = 0, val speechFrames: Int = 0) : VoiceTrace()
    /** stage 5g-10: nobody spoke while the channel was tried: the phone goes on, no strike */
    object HeadsetUndecided : VoiceTrace()
    /** stage 5g-11: the phone's microphone was deaf after the rollback and was opened anew */
    class PhoneReopened(val times: Int) : VoiceTrace()
    /** stage 5g-12: frames with the very same peak in the headset and the phone on probation (the same stream) */
    class SameSound(val same: Int, val frames: Int) : VoiceTrace()
    /** stage 5g-12: how long the recording came from the phone and from the headset */
    class SourcesMs(val phoneMs: Long, val headsetMs: Long) : VoiceTrace()
    /** stage 5g-10: the phone heard nothing for [deafMs] of the [connectMs] the channel needed (peak in that time) */
    class Gap(val deafMs: Long, val connectMs: Long, val phonePeakDbfs: Double) : VoiceTrace()
    /** stage 5g-18: what every source of the phone heard while a car's link came up; [filledMs] of the gap filled */
    class Probe(val summary: String, val filledMs: Long) : VoiceTrace()
    /** stage 5g-20: a car-like device is asked for now, in a pause of a continuous dictation */
    object HeadsetAsked : VoiceTrace()
    /** stage 5g-19: the detector that decided ("silero", or "webrtc" and why), the probabilities of speech, the threshold */
    class Vad(val name: String, val probAvg: Float, val probMax: Float, val threshold: Float) : VoiceTrace()
}

/** One dictation in the journal. Everything optional is "not reached". Never text, never sound, never the field. */
class VoiceAttempt(val at: Long) {
    /** openai, system, openai→system */
    var engine: String? = null
    var started: Boolean? = null
    var source: String? = null
    /** why recording did not start: busy, permission, screen_off, no_panel, permission_asked */
    var notStarted: String? = null
    var peakDb: Int? = null
    var speech: Boolean? = null
    var recordMs: Long? = null
    /** pause, done, max, no_speech, noise, failed, screen_off, hidden, cancel; continuous: silence, limit, cap, noise */
    var stop: String? = null
    var uploadStatus: Int? = null
    var uploadMs: Long? = null
    var uploadKb: Long? = null
    var words: Int? = null
    /** yes, or pending: kept in memory until the keyboard comes back in the field (stage 5zh, item 5) */
    var inserted: String? = null
    /** VoiceError name, with the OpenAI problem after a colon */
    var error: String? = null
    /** where it went wrong: start, record, upload, answer, system */
    var step: String? = null
    /** stage 5g-17: "mid-sentence, case lowered yes/no, final period removed yes/no" or "start, ..." (no text) */
    var place: String? = null
    /** stage 5g: "continuous" for a continuous dictation */
    var mode: String? = null
    /** continuous dictation: phrases inserted and phrases not recognized */
    var phrases: Int? = null
    var failedPhrases: Int? = null
    /** stage 5g: phone, phone→headset, headset→phone, "phone, headset ready", "phone, headset given up" */
    var mic: String? = null
    /** stage 5g-2: ms from the tap to the first recorded frame, and ms spent on the headset (until it came up or was given up) */
    var firstFrameMs: Long? = null
    var headsetMs: Long? = null
    /** stage 5g-24: how the start went (touch, opening the microphone by steps, the process and Silero) */
    var startInfo: String? = null
    /** stage 5g-24: the phone's peak in 100 ms windows of the first 800 ms (dBFS) */
    var earlyLevels: String? = null
    /** stage 5g-3: the Bluetooth devices at the tap (types, inputs and outputs, last 2 characters of the address) and why the source */
    var btDevices: String? = null
    var micReason: String? = null
    /** stage 5g-3: the phone's peak before the headset was asked for and after its channel was given back (dBFS) */
    var phoneBeforeDb: Int? = null
    var phoneAfterDb: Int? = null
    /** stage 5g-5: something played sound at the tap, and the audio focus was taken (only then) */
    var musicPlaying: Boolean? = null
    var focusTaken: Boolean? = null
    /** stage 5g-6: after the dictation: "player resumed itself", "sent play" or "not needed" */
    var resume: String? = null
    /** stage 5g-7: the peaks of the headset (or car) and of the phone on probation, dBFS */
    var probeHeadsetDb: Int? = null
    var probePhoneDb: Int? = null
    /** stage 5g-10: from the channel up to the decision (ms), the frames with speech compared, the failed check (1/2) */
    var probeMs: Long? = null
    var probeFrames: Int? = null
    var strike: String? = null
    /** stage 5g-10: ms the phone heard digital silence while the channel came up, of the ms it needed, and the phone's peak then */
    var gapMs: Long? = null
    var gapOfMs: Long? = null
    var gapPeakDb: Int? = null
    /** stage 5g-18: the sources of the phone while a car's link came up ([helium314.keyboard.latin.voiceapi.PhoneProbe.summary]) and ms of the gap filled */
    var probe: String? = null
    var filledMs: Long? = null
    /** stage 5g-19: "silero" or "webrtc (silero: why)", the probabilities of speech avg and max and the threshold (in %) */
    var vad: String? = null
    /** stage 5g-23: numbers written in digits by the post-processing of the text (a count, no text) */
    var numbers: Int? = null
    var probAvg: Int? = null
    var probMax: Int? = null
    var vadThreshold: Int? = null
    /** stage 5g-20: "phone first", "car joined in pause after N ms", "single phrase phone only" */
    var car: String? = null
    /** stage 5g-20: started players at the tap, ms from the tap to the focus */
    var playbackConfigs: Int? = null
    var focusAtMs: Long? = null
    /** stage 5g-20: pieces sent, sent again, sound kept for a retry, waited for the network */
    var chunks: Int? = null
    var retried: Int? = null
    var keptForRetry: Boolean? = null
    /** stage 5g-21: the probe "mute the radio with a call": its summary and what the system did (names and numbers) */
    var carMute: String? = null
    var carMuteSystem: String? = null
    /** stage 5g-12: on probation the same peak in both microphones on [sameFrames] of [bothFrames] frames */
    var sameFrames: Int? = null
    var bothFrames: Int? = null
    /** stage 5g-12: ms of the recording from the phone and from the headset */
    var phoneMs: Long? = null
    var headsetRecMs: Long? = null
    /** stage 5g-6: speech frames (%), noise level (dBFS), phrases dropped as noise, the system effects (ns, agc) */
    var speechPercent: Int? = null
    var noiseDb: Int? = null
    var noisePhrases: Int? = null
    var effects: String? = null

    fun toJson(): JsonObject = buildJsonObject {
        val fields: List<Pair<String, Any?>> = listOf("at" to at, "engine" to engine, "started" to started, "source" to source,
            "notStarted" to notStarted, "peakDb" to peakDb, "speech" to speech, "recordMs" to recordMs, "stop" to stop,
            "uploadStatus" to uploadStatus, "uploadMs" to uploadMs, "uploadKb" to uploadKb, "words" to words,
            "inserted" to inserted, "place" to place, "error" to error, "step" to step, "mode" to mode, "phrases" to phrases,
            "failedPhrases" to failedPhrases, "mic" to mic, "firstFrameMs" to firstFrameMs, "headsetMs" to headsetMs,
            "btDevices" to btDevices, "micReason" to micReason, "phoneBeforeDb" to phoneBeforeDb, "phoneAfterDb" to phoneAfterDb,
            "musicPlaying" to musicPlaying, "focusTaken" to focusTaken, "resume" to resume, "probeHeadsetDb" to probeHeadsetDb, "probePhoneDb" to probePhoneDb,
            "probeMs" to probeMs, "probeFrames" to probeFrames, "strike" to strike, "gapMs" to gapMs, "gapOfMs" to gapOfMs, "gapPeakDb" to gapPeakDb,
            "probe" to probe, "filledMs" to filledMs, "numbers" to numbers, "vad" to vad, "probAvg" to probAvg, "probMax" to probMax, "vadThreshold" to vadThreshold,
            "car" to car, "playbackConfigs" to playbackConfigs, "focusAtMs" to focusAtMs, "chunks" to chunks, "retried" to retried,
            "keptForRetry" to keptForRetry, "carMute" to carMute, "carMuteSystem" to carMuteSystem,
            "sameFrames" to sameFrames, "bothFrames" to bothFrames, "phoneMs" to phoneMs, "headsetRecMs" to headsetRecMs, "speechPercent" to speechPercent, "noiseDb" to noiseDb,
            "noisePhrases" to noisePhrases, "effects" to effects, "startInfo" to startInfo, "earlyLevels" to earlyLevels)
        for ((k, v) in fields) when (v) {
            is String -> put(k, JsonPrimitive(v))
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            else -> Unit
        }
    }

    /** one technical line for "Copy details" and the diagnostics screen */
    fun line(): String = listOfNotNull(
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date(at)),
        engine,
        mode,
        notStarted?.let { "not started: $it" },
        started?.takeIf { it }?.let { "recording" + (source?.let { s -> " $s" } ?: "") + (mic?.let { m -> " ($m)" } ?: "") },
        firstFrameMs?.let { "first sound after $it ms" },
        startInfo?.let { "start: $it" },
        earlyLevels?.let { "levels 0–800 ms: $it" },
        headsetMs?.let { "headset tried $it ms" },
        btDevices?.let { "bluetooth: $it" },
        micReason?.let { "microphone: $it" },
        speechPercent?.let { "speech $it % of frames" + (noiseDb?.let { n -> ", noise $n dBFS" } ?: "") +
            (noisePhrases?.takeIf { d -> d > 0 }?.let { d -> ", dropped as noise $d" } ?: "") },
        vad?.let { v -> "vad: $v" + (if (v == "silero") ", speech prob avg ${pct(probAvg)}/max ${pct(probMax)}, threshold ${pct(vadThreshold)}" else "") +
            (noiseDb?.let { n -> ", noise $n dBFS" } ?: "") + ", phrases dropped as noise ${noisePhrases ?: 0}" },
        effects?.let { "effects: " + it.ifEmpty { "none" } },
        musicPlaying?.let { "playback before: ${if (it) "yes" else "no"}" + (playbackConfigs?.let { c -> " (configs $c)" } ?: "") +
            ", focus " + (if (focusTaken == true) "taken" + (focusAtMs?.let { f -> " at $f ms" } ?: "") else "not taken") },
        car?.let { "car: $it" },
        carMute?.let { "car mute: $it" },
        carMuteSystem?.let { "car mute system: $it" },
        chunks?.let { "chunks $it, retried ${retried ?: 0}, kept for retry ${if (keptForRetry == true) "yes" else "no"}" },
        resume?.let { "resume: $it" },
        probeHeadsetDb?.let { h -> "on probation: headset $h dBFS, phone ${probePhoneDb ?: "?"} dBFS" +
            (probeMs?.let { ", decided $it ms after the channel was up on ${probeFrames ?: 0} frames with speech" } ?: "") },
        strike?.let { "failed check $it" },
        sameFrames?.takeIf { (bothFrames ?: 0) > 0 }?.let { "same sound in headset and phone on $it of $bothFrames frames" },
        phoneMs?.let { "recorded: phone $it ms, headset ${headsetRecMs ?: 0} ms" },
        gapMs?.let { "gap: $it ms without sound of ${gapOfMs ?: "?"} ms while the channel came up (phone peak ${gapPeakDb ?: "?"} dBFS)" +
            (filledMs?.let { f -> ", filled $f ms from another source" } ?: "") },
        probe?.let { "probe: $it" },
        phoneBeforeDb?.let { b -> "phone ${b} dBFS before the headset, ${phoneAfterDb ?: "?"} dBFS after" },
        peakDb?.let { "peak $it dBFS" },
        speech?.let { if (it) "speech" else "no speech" },
        recordMs?.let { "${it} ms" },
        stop?.let { "stop: $it" },
        uploadKb?.let { "upload ${it} KB" },
        (uploadStatus?.let { "HTTP $it" } ?: if (uploadMs != null) "no answer" else null)?.let { a -> uploadMs?.let { "$a, $it ms" } ?: a },
        phrases?.let { "phrases $it" + (failedPhrases?.takeIf { f -> f > 0 }?.let { f -> ", failed $f" } ?: "") },
        words?.let { "words $it" },
        inserted?.let { "inserted: $it" },
        place?.let { "context: $it" },
        numbers?.let { "numbers: $it converted" },
        error?.let { "error $it" + (step?.let { s -> " at $s" } ?: "") },
    ).joinToString(" · ")

    private fun pct(v: Int?) = v?.let { String.format(Locale.ROOT, "%.2f", it / 100.0) } ?: "?"

    /** what the user sees first: inserted, waiting, cancelled, failed */
    enum class Result { INSERTED, WAITING, CANCELLED, FAILED, NOT_STARTED }

    fun result(): Result = when {
        notStarted != null -> Result.NOT_STARTED
        inserted == "yes" || inserted == "later" -> Result.INSERTED
        inserted == "pending" -> Result.WAITING
        stop == "cancel" && error == null -> Result.CANCELLED
        else -> Result.FAILED
    }

    companion object {
        fun fromJson(o: JsonObject): VoiceAttempt? {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            val at = l("at") ?: return null
            return VoiceAttempt(at).apply {
                engine = s("engine"); started = b("started"); source = s("source"); notStarted = s("notStarted")
                peakDb = i("peakDb"); speech = b("speech"); recordMs = l("recordMs"); stop = s("stop")
                uploadStatus = i("uploadStatus"); uploadMs = l("uploadMs"); uploadKb = l("uploadKb")
                words = i("words"); inserted = s("inserted"); place = s("place"); error = s("error"); step = s("step")
                mode = s("mode"); phrases = i("phrases"); failedPhrases = i("failedPhrases"); mic = s("mic")
                firstFrameMs = l("firstFrameMs"); headsetMs = l("headsetMs"); startInfo = s("startInfo"); earlyLevels = s("earlyLevels"); btDevices = s("btDevices"); micReason = s("micReason")
                phoneBeforeDb = i("phoneBeforeDb"); phoneAfterDb = i("phoneAfterDb")
                musicPlaying = b("musicPlaying"); focusTaken = b("focusTaken"); resume = s("resume"); probeHeadsetDb = i("probeHeadsetDb"); probePhoneDb = i("probePhoneDb")
                probeMs = l("probeMs"); probeFrames = i("probeFrames"); strike = s("strike")
                gapMs = l("gapMs"); gapOfMs = l("gapOfMs"); gapPeakDb = i("gapPeakDb")
                probe = s("probe"); filledMs = l("filledMs")
                numbers = i("numbers"); vad = s("vad"); probAvg = i("probAvg"); probMax = i("probMax"); vadThreshold = i("vadThreshold")
                car = s("car"); playbackConfigs = i("playbackConfigs"); focusAtMs = l("focusAtMs"); chunks = i("chunks")
                retried = i("retried"); keptForRetry = b("keptForRetry"); carMute = s("carMute"); carMuteSystem = s("carMuteSystem")
                sameFrames = i("sameFrames"); bothFrames = i("bothFrames"); phoneMs = l("phoneMs"); headsetRecMs = l("headsetRecMs")
                speechPercent = i("speechPercent"); noiseDb = i("noiseDb"); noisePhrases = i("noisePhrases"); effects = s("effects")
            }
        }
    }
}

/**
 * The voice journal (stage 5zh, item 6): the last [MAX] dictations, only on this phone, in the no-backup folder, never
 * sent anywhere; no text, no sound, no field contents. Shown in Advanced → Voice diagnostics with "Copy details".
 * Writes go to [io] (one background thread on the phone).
 */
class VoiceJournal(private val file: File, private val io: Executor = writer) {
    private val lock = Any()
    private var cache: MutableList<VoiceAttempt>? = null

    private fun loaded(): MutableList<VoiceAttempt> = cache ?: read().toMutableList().also { cache = it }

    private fun read(): List<VoiceAttempt> = try {
        if (!file.exists()) emptyList()
        else Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { (it as? JsonObject)?.let(VoiceAttempt::fromJson) }
    } catch (_: Exception) { emptyList() }

    /** newest first */
    fun entries(): List<VoiceAttempt> = synchronized(lock) { loaded().reversed() }

    fun add(a: VoiceAttempt) {
        val json = synchronized(lock) {
            val list = loaded()
            list.add(a)
            while (list.size > MAX) list.removeAt(0)
            buildJsonArray { list.forEach { add(it.toJson()) } }.toString()
        }
        io.execute { write(json) }
    }

    /** an entry already added was changed afterwards (stage 5g-6: the resume of the music comes 0.8 s after the end) */
    fun changed() {
        val json = synchronized(lock) { buildJsonArray { loaded().forEach { add(it.toJson()) } }.toString() }
        io.execute { write(json) }
    }

    fun clear() {
        synchronized(lock) { cache = mutableListOf() }
        io.execute { file.delete() }
    }

    private fun write(json: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    /** all entries as text for "Copy details", newest first */
    fun text(header: String): String = buildString {
        appendLine(header)
        entries().forEachIndexed { i, a -> appendLine("${i + 1}. ${a.line()}") }
    }

    companion object {
        const val MAX = 30
        const val FILE_NAME = "voice-journal.json"
        private val writer: Executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "aikb-voice-journal").apply { isDaemon = true } } }
        @Volatile private var instance: VoiceJournal? = null

        /** in the no-backup folder: never in a backup or a data transfer */
        fun get(context: Context): VoiceJournal = instance ?: synchronized(this) {
            instance ?: VoiceJournal(File(context.applicationContext.noBackupFilesDir, FILE_NAME)).also { instance = it }
        }

        /** "Delete all" of the AI screen */
        fun erase(context: Context) = get(context).clear()

        /** number of words, for "Done: N words" and the journal (the text itself is not kept) */
        fun words(text: String) = text.trim().split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
    }
}
