// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * ai-dict-1: a causal language model exported to ONNX (the "onnx-community" exports of Qwen2.5, Qwen3, SmolLM2: inputs
 * input_ids, attention_mask, position_ids (if present), past_key_values.N.key/value; outputs logits and present.N.*).
 * Keeps the key/value cache of the last context, so the next request with the same beginning only computes the new tokens.
 * Several continuations of one context run together as one batch (much faster on a phone than one after another).
 * Not thread safe: one caller at a time.
 */
class OnnxLm(modelFile: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.path, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val pastNames = session.inputNames.filter { it.startsWith("past_key_values.") }.sorted()
    private val presentNames = pastNames.map { "present." + it.removePrefix("past_key_values.") }
    private val hasPosition = "position_ids" in session.inputNames
    private val kvHeads: Int
    private val headDim: Int
    /** the key/value cache is float32 or float16 (Qwen3 q4f16); kept as raw bytes either way */
    private val kvType: OnnxJavaType
    private val kvBytes: Int

    init {
        val info = session.inputInfo[pastNames.first()]!!.info as ai.onnxruntime.TensorInfo
        kvHeads = info.shape[1].toInt()
        headDim = info.shape[3].toInt()
        kvType = if (info.type == OnnxJavaType.FLOAT16) OnnxJavaType.FLOAT16 else OnnxJavaType.FLOAT
        kvBytes = if (kvType == OnnxJavaType.FLOAT16) 2 else 4
    }

    /** the state after [tokens]: the key/value cache ([kv] per layer, one row) and the log-probabilities of the next token */
    class State internal constructor(val tokens: IntArray, internal val kv: Array<ByteArray>, val logProbs: FloatArray)

    private var cached: State? = null
    /** tokens computed so far, and model runs (for the journal and the comparison) */
    var steps = 0L; private set
    var runs = 0L; private set

    /** the state after [tokens]; reuses the cached beginning if the text only grew */
    fun state(tokens: IntArray): State {
        require(tokens.isNotEmpty())
        val c = cached
        if (c != null && c.tokens.contentEquals(tokens)) return c
        val base = if (c != null && tokens.size > c.tokens.size && c.tokens.contentEquals(tokens.copyOf(c.tokens.size))) c else null
        val more = if (base == null) tokens else tokens.copyOfRange(base.tokens.size, tokens.size)
        val out = run(listOf(base), arrayOf(more), base?.tokens?.size ?: 0, wantStates = true) { _, _, _ -> true }
        cached = out.states!![0]
        return cached!!
    }

    /**
     * Scores continuations of [from] in one batch: for each, the sum of log-probabilities of its tokens and [tail] of the
     * distribution after its last token (e.g. the probability that the word ends there).
     */
    fun score(from: State, conts: List<IntArray>, tail: (FloatArray) -> Double): DoubleArray {
        // the first token comes from the cached distribution; the rest and the tail need one batched run
        val res = DoubleArray(conts.size) { from.logProbs[conts[it][0]].toDouble() }
        val groups = if (!onePerLongRow) listOf(conts.indices.toList())
            else conts.indices.partition { conts[it].size == 1 }.let { (one, long) -> listOf(one) + long.map { listOf(it) } }.filter { it.isNotEmpty() }
        try {
            for (g in groups) run(g.map { from }, Array(g.size) { conts[g[it]] }, from.tokens.size, wantStates = false) { b, pos, row ->
                val c = conts[g[b]]
                if (pos < c.size - 1) res[g[b]] += row[c[pos + 1]].toDouble() else res[g[b]] += tail(row)
                false
            }
        } catch (e: OrtException) {
            // the fused attention of some exports (GroupQueryAttention, e.g. Qwen3) takes several new tokens only in a batch of one
            if (onePerLongRow || e.message?.contains("batch_size must be 1") != true) throw e
            onePerLongRow = true
            return score(from, conts, tail)
        }
        return res
    }

    /** set when the model refuses a batch of several rows with several new tokens each */
    @Volatile private var onePerLongRow = false

    /** one step for each row: [rows] states of the same length, each continued by one token */
    fun step(rows: List<State>, tokens: IntArray): List<State> =
        run(rows, Array(rows.size) { intArrayOf(tokens[it]) }, rows.first().tokens.size, wantStates = true) { _, _, _ -> true }.states!!

    private class Out(val states: List<State>?)

    /**
     * One batched run. [from] may contain null (no past: only allowed with [past] = 0). [onRow] gets the log-softmax of
     * every real position (batch row, position, row: reused, copy what you keep); true = keep the row of the last position
     * for the new state.
     */
    private fun run(from: List<State?>, more: Array<IntArray>, past: Int, wantStates: Boolean,
                    onRow: (Int, Int, FloatArray) -> Boolean): Out {
        val b = more.size
        val len = more.maxOf { it.size }
        val total = past + len
        val inputs = HashMap<String, OnnxTensor>()
        try {
            val ids = LongArray(b * len) { i -> more[i / len].getOrElse(i % len) { more[i / len][0] }.toLong() }
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(b.toLong(), len.toLong()))
            val mask = LongArray(b * total) { i -> val r = i / total; val p = i % total; if (p < past || p - past < more[r].size) 1 else 0 }
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(b.toLong(), total.toLong()))
            if (hasPosition)
                inputs["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(b * len) { (past + it % len).toLong() }), longArrayOf(b.toLong(), len.toLong()))
            val rowSize = kvHeads * past * headDim * kvBytes
            for (l in pastNames.indices) {
                val data = ByteBuffer.allocateDirect(maxOf(1, b * rowSize)).order(ByteOrder.nativeOrder())
                if (past > 0) for (r in 0 until b) data.put(from[r]!!.kv[l], 0, rowSize)
                data.rewind()
                if (b * rowSize == 0) data.limit(0)
                inputs[pastNames[l]] = OnnxTensor.createTensor(env, data, longArrayOf(b.toLong(), kvHeads.toLong(), past.toLong(), headDim.toLong()), kvType)
            }
            session.run(inputs).use { out ->
                runs++
                steps += more.sumOf { it.size }
                val logits = out.get("logits").get() as OnnxTensor
                val vocab = logits.info.shape[2].toInt()
                val buf = logits.floatBuffer
                val row = FloatArray(vocab)
                val lastRows = arrayOfNulls<FloatArray>(b)
                for (r in 0 until b) for (p in 0 until more[r].size) {
                    if (wantStates && p != more[r].size - 1) continue // a new state needs only its last distribution
                    buf.position((r * len + p) * vocab)
                    buf.get(row)
                    logSoftmax(row)
                    val keep = onRow(r, p, row)
                    if (keep && p == more[r].size - 1) lastRows[r] = row.copyOf()
                }
                if (!wantStates) return Out(null)
                val presents = presentNames.map { (out.get(it).get() as OnnxTensor).byteBuffer.order(ByteOrder.nativeOrder()) }
                val newRow = kvHeads * total * headDim * kvBytes
                val states = (0 until b).map { r ->
                    val kv = Array(presents.size) { l ->
                        val bb = presents[l]
                        bb.position(r * newRow)
                        ByteArray(newRow).also { bb.get(it) }
                    }
                    State((from[r]?.tokens ?: IntArray(0)) + more[r], kv, lastRows[r]!!)
                }
                return Out(states)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() { session.close() }

    companion object {
        fun logSoftmax(x: FloatArray): FloatArray {
            var max = Float.NEGATIVE_INFINITY
            for (v in x) if (v > max) max = v
            var sum = 0.0
            for (v in x) sum += Math.exp((v - max).toDouble())
            val lse = max + Math.log(sum).toFloat()
            for (i in x.indices) x[i] -= lse
            return x
        }
    }
}
