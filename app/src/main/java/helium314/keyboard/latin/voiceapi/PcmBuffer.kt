// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

/**
 * Recorded sound (PCM16, mono, little endian), only in memory, in pieces of one second so it never has to be copied
 * while growing. [wipe] overwrites everything with zeros (docs/VOICE_API.md, section 4).
 */
class PcmBuffer(val sampleRate: Int = SAMPLE_RATE) {
    private val chunkBytes = sampleRate * 2
    private val chunks = mutableListOf<ByteArray>()
    var byteCount = 0
        private set

    val durationMs: Long get() = byteCount * 1000L / (sampleRate * 2)

    @Synchronized
    fun append(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val pos = byteCount % chunkBytes
            if (pos == 0 && byteCount / chunkBytes == chunks.size) chunks.add(ByteArray(chunkBytes))
            val chunk = chunks[byteCount / chunkBytes]
            val s = samples[i].toInt()
            chunk[pos] = (s and 0xFF).toByte()
            chunk[pos + 1] = (s shr 8 and 0xFF).toByte()
            byteCount += 2
        }
    }

    @Synchronized
    fun copyTo(dest: ByteArray, offset: Int) {
        var left = byteCount
        var at = offset
        for (chunk in chunks) {
            if (left <= 0) break
            val n = minOf(left, chunk.size)
            System.arraycopy(chunk, 0, dest, at, n)
            at += n
            left -= n
        }
    }

    /** multiplies every sample by [factor] (clipped to the PCM16 range); in place, nothing is copied */
    @Synchronized
    fun applyGain(factor: Double) {
        if (factor == 1.0) return
        var left = byteCount
        for (chunk in chunks) {
            var i = 0
            while (i + 1 < chunk.size && left > 0) {
                val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
                val v = (s * factor).toInt().coerceIn(-32768, 32767)
                chunk[i] = (v and 0xFF).toByte()
                chunk[i + 1] = (v shr 8 and 0xFF).toByte()
                i += 2
                left -= 2
            }
        }
    }

    /** the loudest sample */
    @Synchronized
    fun peak(): Int {
        var p = 0
        var left = byteCount
        for (chunk in chunks) {
            var i = 0
            while (i + 1 < chunk.size && left > 0) {
                val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
                if (kotlin.math.abs(s) > p) p = kotlin.math.abs(s)
                i += 2
                left -= 2
            }
        }
        return p
    }

    /** moves everything into a new buffer (nothing is copied); this one is empty afterwards (stage 5g: one phrase) */
    @Synchronized
    fun takeAll(): PcmBuffer {
        val b = PcmBuffer(sampleRate)
        b.chunks.addAll(chunks)
        b.byteCount = byteCount
        chunks.clear()
        byteCount = 0
        return b
    }

    @Synchronized
    fun wipe() {
        chunks.forEach { it.fill(0) }
        chunks.clear()
        byteCount = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** The request body of /v1/audio/transcriptions: form fields and the sound as a WAV file, built in one array. */
object WavMultipart {
    const val WAV_HEADER_BYTES = 44

    /** the standard 44 byte header of PCM16 mono WAV */
    fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val h = ByteArray(WAV_HEADER_BYTES)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
        fun int32(at: Int, v: Int) { for (i in 0..3) h[at + i] = (v shr (8 * i) and 0xFF).toByte() }
        fun int16(at: Int, v: Int) { for (i in 0..1) h[at + i] = (v shr (8 * i) and 0xFF).toByte() }
        ascii(0, "RIFF"); int32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1); int32(24, sampleRate); int32(28, sampleRate * 2)
        int16(32, 2); int16(34, 16)
        ascii(36, "data"); int32(40, dataBytes)
        return h
    }

    /** [fields] are sent in this order before the file; names and values must not contain line breaks */
    fun build(boundary: String, fields: List<Pair<String, String>>, audio: PcmBuffer, fileName: String = "speech.wav"): ByteArray {
        val head = StringBuilder()
        for ((name, value) in fields) {
            require(!name.contains('\r') && !name.contains('\n') && !name.contains('"'))
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value.replace("\r", " ").replace("\n", " ")).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
            .append("Content-Type: audio/wav\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val data = audio.byteCount
        val body = ByteArray(headBytes.size + WAV_HEADER_BYTES + data + tailBytes.size)
        System.arraycopy(headBytes, 0, body, 0, headBytes.size)
        System.arraycopy(wavHeader(data, audio.sampleRate), 0, body, headBytes.size, WAV_HEADER_BYTES)
        audio.copyTo(body, headBytes.size + WAV_HEADER_BYTES)
        System.arraycopy(tailBytes, 0, body, body.size - tailBytes.size, tailBytes.size)
        return body
    }
}
