// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import helium314.keyboard.latin.openai.KeyCipher
import helium314.keyboard.latin.openai.KeyProtection
import helium314.keyboard.latin.openai.SecretKeyProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keystore is replaced by a plain AES key (Robolectric has no Android Keystore); the file format and checks are the real ones. */
class FakeKeys : SecretKeyProvider {
    var key: SecretKey? = null
    var failGet = false
    /** protection of the current key, and every key created */
    var keyProtection: KeyProtection? = null
    val created = mutableListOf<KeyProtection>()
    override fun get(): SecretKey? { if (failGet) throw java.security.KeyStoreException("locked"); return key }
    override fun getOrCreate(protection: KeyProtection): SecretKey = key
        ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; keyProtection = protection; created.add(protection) }
    override fun delete() { key = null; keyProtection = null }
}

/** like the Keystore of HONOR with Android 16: a strict key refuses to work although the phone is unlocked */
class RefusingCipher(private val keys: FakeKeys) : KeyCipher() {
    var refuseStrict = true
    private fun check(key: SecretKey) {
        if (refuseStrict && key === keys.key && keys.keyProtection == KeyProtection.STRICT)
            throw android.security.keystore.UserNotAuthenticatedException()
    }
    override fun encrypt(key: SecretKey, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> { check(key); return super.encrypt(key, aad, plain) }
    override fun decrypt(key: SecretKey, aad: ByteArray, iv: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        check(key); return super.decrypt(key, aad, iv, data, offset, length)
    }
}
