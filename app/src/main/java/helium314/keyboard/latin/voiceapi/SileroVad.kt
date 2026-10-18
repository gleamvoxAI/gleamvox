// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** the probability of speech in one chunk of [SileroVad.CHUNK] samples at 16 kHz (-1..1), for one stream of sound */
interface SpeechProbability {
    fun probability(chunk: FloatArray): Float
}

/**
 * Stage 5g-19 (the owner: in the car on a bad road, at speed, the dictation never stops; noise taken for a voice): the
 * Silero VAD (MIT, model v6.2.3, assets/silero_vad.onnx, only in the personal variant) with ONNX Runtime (MIT), all on
 * the phone, without the network. One model per process, loaded once in the background; each recording (and each
 * microphone on probation) gets its own [SpeechProbability] with its own state. When the model can't be loaded (not in
 * this build, Android before 7, an error) [stream] gives null and the speech detector uses WebRTC VAD as before; why is
 * in [failure] (for the journal).
 */
object SileroVad {
    const val ASSET = "silero_vad.onnx"
    /** 32 ms at 16 kHz, and the 64 samples before it the model sees too (as the Silero code does) */
    const val CHUNK = 512
    const val CONTEXT = 64
    const val SAMPLE_RATE = 16000L

    @Volatile var failure: String? = null
        private set
    @Volatile private var model: Model? = null
    private var loading: Future<Model?>? = null
    private val loader by lazy { Executors.newSingleThreadExecutor { Thread(it, "aikb-silero").apply { isDaemon = true } } }

    /** starts loading the model in the background (when the voice panel opens), if not loaded yet */
    @Synchronized
    fun preload(context: Context) {
        if (model != null || loading != null) return
        val app = context.applicationContext
        loading = loader.submit<Model?> { load { app.assets.open(ASSET).use { it.readBytes() } } }
    }

    /** a new stream for one recording: waits for the model at most [waitMs] (on the recording thread, never the main one) */
    fun stream(waitMs: Long = 3000): SpeechProbability? {
        model?.let { return it.stream() }
        val f = synchronized(this) { loading } ?: run { if (failure == null) failure = "not loaded"; return null }
        return try { f.get(waitMs, TimeUnit.MILLISECONDS)?.stream() } catch (e: java.util.concurrent.TimeoutException) {
            failure = "still loading"; null
        } catch (e: Exception) { failure = "load: ${e.javaClass.simpleName}"; null }
    }

    /** loads the model from [bytes] (the tests: from the file); null and [failure] when it can't */
    fun load(bytes: () -> ByteArray): Model? = try {
        // 0 in the tests on the computer (no Android there)
        val sdk = try { android.os.Build.VERSION.SDK_INT } catch (e: Throwable) { 0 }
        if (sdk in 1 until 24) { failure = "Android before 7"; null }
        else {
            val b = try { bytes() } catch (e: java.io.IOException) { failure = "model not in this build"; null }
            b?.let { Model.create(it) }?.also { model = it; failure = null }
        }
    } catch (e: Throwable) { // NoClassDefFoundError: ONNX Runtime not in this build; UnsatisfiedLinkError: no native code
        failure = "load: ${e.javaClass.simpleName}"
        null
    }

    /** the loaded model, for the tests */
    fun loaded(): Model? = model

    /** the ONNX session; only this class touches ONNX Runtime */
    class Model private constructor(private val env: ai.onnxruntime.OrtEnvironment, private val session: ai.onnxruntime.OrtSession) {
        fun stream(): SpeechProbability = Stream()

        private inner class Stream : SpeechProbability {
            /** the hidden state of the model, [2, 1, 128], and the last 64 samples of the chunk before */
            private var state = Array(2) { Array(1) { FloatArray(128) } }
            private val input = FloatArray(CONTEXT + CHUNK)

            override fun probability(chunk: FloatArray): Float {
                System.arraycopy(chunk, 0, input, CONTEXT, CHUNK)
                val x = ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + CHUNK).toLong()))
                val s = ai.onnxruntime.OnnxTensor.createTensor(env, state)
                val sr = ai.onnxruntime.OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf())
                try {
                    session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (r[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        state = r[1].value as Array<Array<FloatArray>>
                        System.arraycopy(input, CHUNK, input, 0, CONTEXT) // the end of this chunk is the context of the next
                        return p
                    }
                } finally { x.close(); s.close(); sr.close() }
            }
        }

        companion object {
            fun create(bytes: ByteArray): Model {
                val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                try { env.setTelemetry(false) } catch (_: Throwable) { } // no telemetry (CLAUDE.md)
                val options = ai.onnxruntime.OrtSession.SessionOptions().apply {
                    // one thread: a frame of 32 ms takes well under a millisecond, and the keyboard shares the phone
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return Model(env, env.createSession(bytes, options))
            }
        }
    }
}
