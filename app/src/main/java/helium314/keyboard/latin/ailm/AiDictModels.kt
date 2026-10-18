// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.content.Context
import java.io.File
import java.net.URI

/**
 * ai-dict-1: the AI dictionary that can be downloaded (owner's decision 2026-10-17: the ordinary dictionary by default, the
 * AI dictionary only on request). Every file has a fixed address (a fixed revision of the repository, never "main") and a
 * SHA-256; a file with another checksum is deleted, never used.
 */
data class AiDictFile(val name: String, val url: String, val size: Long, val sha256: String)

data class AiDictModel(val id: String, val title: String, val license: String, val source: String, val files: List<AiDictFile>,
                       val modelFile: String = files.first().name, val tokenizerFile: String = "tokenizer.json") {
    val totalBytes get() = files.sumOf { it.size }
    fun dir(context: Context) = File(context.noBackupFilesDir, "ai-dict/$id")
    fun file(context: Context, name: String) = File(dir(context), name)
    fun isComplete(context: Context) = files.all { file(context, it.name).length() == it.size } && File(dir(context), VERIFIED).isFile

    companion object { const val VERIFIED = "verified" }
}

object AiDictModels {
    private const val HF = "https://huggingface.co/"

    /** the model chosen by the comparison of ai-dict-1 (docs/DECISIONS.md, a proposal) */
    val QWEN25_05B = AiDictModel(
        // q4f16 (4-bit weights, half-precision tables): the int8 export of Qwen2.5 gives nonsense (checked in ai-dict-1)
        id = "qwen2.5-0.5b-q4f16",
        title = "Qwen2.5 0.5B",
        license = "Apache-2.0, © Alibaba Cloud (Qwen team); ONNX export: onnx-community",
        source = "huggingface.co/Qwen/Qwen2.5-0.5B, huggingface.co/onnx-community/Qwen2.5-0.5B",
        files = listOf(
            AiDictFile("model_q4f16.onnx", HF + "onnx-community/Qwen2.5-0.5B/resolve/bae5ceaee026f0d0592858b2bd27645a06f19c42/onnx/model_q4f16.onnx",
                483_003_582, "30a39f89fab8f30d0f99aa1e28d3e3be6fca66a3fab915f77584ac52a8361d25"),
            AiDictFile("tokenizer.json", HF + "onnx-community/Qwen2.5-0.5B/resolve/bae5ceaee026f0d0592858b2bd27645a06f19c42/tokenizer.json",
                7_031_673, "a8506e7111b80c6d8635951a02eab0f4e1a8e4e5772da83846579e97b16f61bf"),
        ),
    )

    val all = listOf(QWEN25_05B)
    val current get() = QWEN25_05B

    /** the first request goes only to one of the fixed addresses above */
    fun isCatalogueUrl(url: String) = all.any { m -> m.files.any { it.url == url } }

    /**
     * HuggingFace answers the fixed address with a redirect to its file storage (a signed, temporary address with a query).
     * Only these hosts, only https, and only as the redirect of a catalogue address; the content is checked by SHA-256.
     */
    fun isStorageRedirect(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        if (u.scheme != "https" || u.rawUserInfo != null || (u.port != -1 && u.port != 443)) return false
        val host = u.host ?: return false
        return host == "huggingface.co" || host == "cdn-lfs.huggingface.co" || host == "cdn-lfs-us-1.huggingface.co" ||
            host.endsWith(".hf.co") || host.endsWith(".xethub.hf.co")
    }
}
