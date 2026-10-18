// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * How the Keystore key is protected (docs/VOICE_API.md, section 6):
 * [STRICT] usable only while the phone is unlocked (setUnlockedDeviceRequired), in StrongBox if the phone has it;
 * [PLAIN] a normal Android Keystore key (still can't leave the phone, usable only by this app), used when the strict
 * key is refused although the phone is unlocked (seen on HONOR with Android 16). Never raised back automatically.
 */
enum class KeyProtection { STRICT, PLAIN }

/** the AES key that encrypts a saved secret; Android Keystore on the phone, a plain key in tests */
interface SecretKeyProvider {
    fun get(): SecretKey?
    fun getOrCreate(protection: KeyProtection): SecretKey
    fun delete()
}

/**
 * AES-256-GCM key in Android Keystore: can't leave the phone; with [KeyProtection.STRICT] usable only while the phone
 * is unlocked (Android 9+), in StrongBox if the phone has it.
 */
class AndroidKeystoreKeys(private val alias: String) : SecretKeyProvider {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun get(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    override fun getOrCreate(protection: KeyProtection): SecretKey = get() ?: when (protection) {
        KeyProtection.PLAIN -> generate(strict = false, strongBox = false)
        KeyProtection.STRICT -> try {
            generate(strict = true, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e is StrongBoxUnavailableException) generate(strict = true, strongBox = false) else throw e
        }
    }

    private fun generate(strict: Boolean, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (strict && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                    if (strongBox) setIsStrongBoxBacked(true)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
    }

    override fun delete() { try { keyStore().deleteEntry(alias) } catch (_: Exception) { } }
}

/** AES-GCM of a saved secret; separate so tests can make a key refuse to work like a real Keystore does */
open class KeyCipher {
    /** returns (IV, encrypted bytes); the key makes a fresh random IV */
    open fun encrypt(key: SecretKey, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plain)
        return cipher.iv to encrypted
    }

    open fun decrypt(key: SecretKey, aad: ByteArray, iv: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(data, offset, length)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

object SecureKeys {
    /**
     * The Keystore refused to use the key: UserNotAuthenticatedException, KeyStoreException (java.security or
     * android.security), ProviderException (e.g. with StrongBox), anywhere in the chain of causes.
     */
    fun isKeyRefusal(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 10) {
            if (t is UserNotAuthenticatedException || t is java.security.KeyStoreException || t is java.security.ProviderException ||
                t.javaClass.simpleName == "KeyStoreException" || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && t is StrongBoxUnavailableException))
                return true
            t = t.cause.takeIf { it !== t }
        }
        return false
    }
}
