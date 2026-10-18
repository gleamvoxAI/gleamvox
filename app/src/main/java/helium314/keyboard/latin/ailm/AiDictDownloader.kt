// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.NetworkBlockedException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest

/**
 * ai-dict-1: downloads the AI dictionary on the owner's request. Resumes after a break (HTTP Range into a ".part" file),
 * checks the SHA-256 of every file, deletes a file with a wrong checksum. The connections go through AikbNetwork (the
 * only network code of the app): only the fixed catalogue addresses and the storage redirect of HuggingFace, only with
 * "Allow internet access" on (the personal variant); nothing about the user is sent.
 */
class AiDictDownloader(
    private val context: Context,
    private val net: AikbNetwork = AikbNetwork.forAiDict(context),
) {
    data class Progress(val done: Long, val total: Long, val file: String)

    @Volatile var cancelled = false
        private set
    @Volatile private var current: CancelSignal? = null

    /** stops at once (the connection is closed); the part already downloaded stays for the next try */
    fun cancel() { cancelled = true; current?.cancel() }

    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** downloads what is missing of [model]; throws on errors (the partial files stay for the next try) */
    fun download(model: AiDictModel, onProgress: (Progress) -> Unit) {
        if (!net.isOn()) throw NetworkBlockedException(NetworkBlockedException.Reason.INTERNET_OFF)
        val dir = model.dir(context).apply { mkdirs() }
        File(dir, AiDictModel.VERIFIED).delete()
        var before = 0L
        for (f in model.files) {
            val target = File(dir, f.name)
            if (target.length() != f.size || !verify(target, f.sha256)) {
                target.delete()
                fetch(f, File(dir, f.name + ".part")) { onProgress(Progress(before + it, model.totalBytes, f.name)) }
                val part = File(dir, f.name + ".part")
                if (!verify(part, f.sha256)) { part.delete(); throw IOException("checksum of ${f.name} does not match, file deleted") }
                if (!part.renameTo(target)) throw IOException("cannot store ${f.name}")
            }
            before += f.size
            onProgress(Progress(before, model.totalBytes, f.name))
        }
        File(dir, AiDictModel.VERIFIED).writeText(model.files.joinToString("\n") { "${it.sha256}  ${it.name}" })
    }

    private fun fetch(f: AiDictFile, part: File, onBytes: (Long) -> Unit) {
        var have = part.length().coerceAtMost(f.size)
        if (have == f.size) return
        var url = f.url
        var redirected = false
        var hops = 0
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val signal = CancelSignal().also { current = it }
            val r = net.downloadFile(url, have, redirected, signal)
            try {
                val code = r.status
                if (code in 300..399) {
                    val next = r.headers["location"]?.let { try { URI(url).resolve(it).toString() } catch (e: Exception) { null } }
                    if (next == null || ++hops > MAX_REDIRECTS)
                        throw NetworkBlockedException(NetworkBlockedException.Reason.REDIRECT, AikbNetwork.hostAndPath(next ?: url), code)
                    url = next; redirected = true // checked again by AikbNetwork: only the storage of HuggingFace
                    continue
                }
                if (code == 416 && have > 0) { part.delete(); have = 0; continue } // the server has another file: start again
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                if (code == 200 && have > 0) have = 0 // no Range support: from the beginning
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = r.body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have > f.size) throw IOException("${f.name} is larger than expected")
                        if (have - last >= (1 shl 20)) { last = have; onBytes(have) }
                    }
                }
            } catch (e: IOException) {
                if (cancelled) throw IOException("cancelled")
                throw e
            } finally {
                r.close()
                current = null
            }
            onBytes(have)
            if (have != f.size) throw IOException("${f.name}: connection ended at $have of ${f.size} bytes")
            return
        }
    }

    companion object {
        const val MAX_REDIRECTS = 3

        fun verify(file: File, sha256: String): Boolean {
            if (!file.isFile) return false
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) } == sha256
        }

        fun delete(context: Context, model: AiDictModel) { model.dir(context).deleteRecursively() }
    }
}
