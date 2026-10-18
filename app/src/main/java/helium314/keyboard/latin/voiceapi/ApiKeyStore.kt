// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.AtomicFile
import helium314.keyboard.latin.openai.KeyProtection
import helium314.keyboard.latin.openai.NetDiagnostic
import helium314.keyboard.latin.openai.SecretKeyProvider
import helium314.keyboard.latin.openai.KeyCipher
import helium314.keyboard.latin.openai.SecureKeys
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.FileOutputStream
import javax.crypto.AEADBadTagException

/**
 * The user's own OpenAI API key for voice and text (docs/VOICE_API.md, section 6, docs/AI.md): AES-256-GCM with an
 * Android Keystore key, strict or plain mode ([KeyProtection]), its own Keystore key and its own no-backup folder.
 * Never in settings, never logged.
 */
class ApiKeyStore(
    private val dir: File,
    private val keys: SecretKeyProvider,
    private val isDeviceLocked: () -> Boolean = { false },
    private val crypto: KeyCipher = KeyCipher(),
) {
    sealed class Load {
        object Empty : Load()
        class Ok(val key: String) : Load()
        /** phone locked or Keystore busy: try later, nothing deleted */
        object Unavailable : Load()
        /** unreadable (changed, damaged, key gone): deleted, paste the key again */
        object Discarded : Load()
    }

    private val keyFile = File(dir, "api-key.bin")
    /** "plain" or missing (strict), the strict and plain modes of [KeyProtection] */
    private val protectionFile = File(dir, "key-protection")

    /** a key is saved (the file exists; it may still turn out unreadable) */
    fun hasKey(): Boolean = keyFile.isFile

    @Synchronized
    fun protection(): KeyProtection = try {
        if (protectionFile.readText().trim() == PLAIN) KeyProtection.PLAIN else KeyProtection.STRICT
    } catch (e: Exception) { KeyProtection.STRICT }

    private fun shouldFallBack(e: Throwable) = SecureKeys.isKeyRefusal(e) && protection() == KeyProtection.STRICT && !isDeviceLocked()

    private fun fallBackToPlain(e: Throwable) {
        Log.w(TAG, "strict key refused while unlocked (${NetDiagnostic.exceptionName(e)}), using a plain Keystore key")
        keys.delete()
        writeAtomically(protectionFile, PLAIN.toByteArray(Charsets.US_ASCII))
    }

    /** [key] must be checked with [normalize] before */
    @Synchronized
    fun save(key: String) {
        val plain = key.toByteArray(Charsets.UTF_8)
        try {
            val (iv, encrypted) = try {
                crypto.encrypt(keys.getOrCreate(protection()), AAD, plain)
            } catch (e: Exception) {
                if (!shouldFallBack(e)) throw e // phone locked: nothing is written
                fallBackToPlain(e)
                crypto.encrypt(keys.getOrCreate(KeyProtection.PLAIN), AAD, plain)
            }
            writeAtomically(keyFile, MAGIC + byteArrayOf(FORMAT, iv.size.toByte()) + iv + encrypted)
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun load(): Load {
        if (!keyFile.isFile) return Load.Empty
        val bytes = try { keyFile.readBytes() } catch (e: Exception) { return Load.Unavailable }
        val headerSize = MAGIC.size + 2
        if (bytes.size < headerSize || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[MAGIC.size] != FORMAT)
            return discard("unknown format")
        val ivSize = bytes[MAGIC.size + 1].toInt()
        if (ivSize !in 12..16 || bytes.size < headerSize + ivSize + 16) return discard("damaged")
        val iv = bytes.copyOfRange(headerSize, headerSize + ivSize)
        val key = try { keys.get() } catch (e: Exception) {
            if (isDeviceLocked()) return Load.Unavailable
            if (shouldFallBack(e)) { fallBackToPlain(e); return discard("strict key refused") }
            return discard("key not readable")
        } ?: return discard("key gone")
        val plain = try {
            crypto.decrypt(key, AAD, iv, bytes, headerSize + ivSize, bytes.size - headerSize - ivSize)
        } catch (e: AEADBadTagException) {
            return discard("changed or damaged")
        } catch (e: KeyPermanentlyInvalidatedException) {
            return discard("key invalidated")
        } catch (e: Exception) {
            if (isDeviceLocked()) return Load.Unavailable
            if (shouldFallBack(e)) { fallBackToPlain(e); return discard("strict key refused") }
            if (e is UserNotAuthenticatedException) return Load.Unavailable
            return discard("not readable")
        }
        val text = plain.toString(Charsets.UTF_8)
        plain.fill(0)
        return normalize(text)?.let { Load.Ok(it) } ?: discard("not a key")
    }

    private fun discard(why: String): Load {
        Log.w(TAG, "saved API key removed: $why")
        keyFile.delete()
        return Load.Discarded
    }

    /** "Delete key" and "Delete all": the file, its Keystore key and the protection mode */
    @Synchronized
    fun delete() {
        keyFile.delete()
        protectionFile.delete()
        keys.delete()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val atomic = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            throw e
        }
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PLAIN = "plain"
        private val MAGIC = "AIKK".toByteArray(Charsets.US_ASCII)
        private const val FORMAT: Byte = 1
        private val AAD = "aikb-openai-api-key-v1".toByteArray(Charsets.US_ASCII)
        const val KEYSTORE_ALIAS = "aikb_openai_api_key_v1"
        private val KEY_SHAPE = Regex("sk-[A-Za-z0-9_-]{16,500}")

        fun storageDir(context: Context): File = File(context.noBackupFilesDir, "openai-voice")

        /** the pasted text as a key (spaces and line breaks around it removed), or null if it doesn't look like one */
        fun normalize(text: String): String? = text.trim().takeIf { KEY_SHAPE.matches(it) }

        /** for the clipboard history: never keep something that looks like an OpenAI key */
        fun looksLikeKey(text: CharSequence): Boolean = text.length <= 600 && KEY_SHAPE.matches(text.trim())

        /** only the last 4 characters are ever shown */
        fun mask(key: String): String = "••••" + key.takeLast(4)
    }
}
