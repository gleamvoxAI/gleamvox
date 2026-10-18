// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.openai.FakeKeys
import helium314.keyboard.openai.RefusingCipher
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.openai.KeyProtection
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.ApiKeyStore
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.settings.preferences.restoreSettings
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OpenAI API key: encrypted (Keystore, strict or plain mode), masked, never in logs, settings or the clipboard history. */
@RunWith(RobolectricTestRunner::class)
class ApiKeyStoreTest {
    private val dir: File = Files.createTempDirectory("aikb-key").toFile()
    private val keys = FakeKeys()
    private fun store(locked: Boolean = false, cipher: helium314.keyboard.latin.openai.KeyCipher = helium314.keyboard.latin.openai.KeyCipher()) =
        ApiKeyStore(dir, keys, { locked }, cipher)

    @Test fun roundTrip() {
        val s = store()
        assertIs<ApiKeyStore.Load.Empty>(s.load())
        assertFalse(s.hasKey())
        s.save(TEST_KEY)
        assertTrue(s.hasKey())
        assertEquals(TEST_KEY, (s.load() as ApiKeyStore.Load.Ok).key)
        // a new store object (app restarted) reads it too
        assertEquals(TEST_KEY, (store().load() as ApiKeyStore.Load.Ok).key)
        val file = File(dir, "api-key.bin").readBytes()
        assertFalse(String(file, Charsets.ISO_8859_1).contains("TESTKEY"), "the file is encrypted")
        s.delete()
        assertIs<ApiKeyStore.Load.Empty>(s.load())
        assertNull(keys.key, "the Keystore key is deleted too")
    }

    @Test fun changedFileIsDiscarded() {
        store().save(TEST_KEY)
        val f = File(dir, "api-key.bin")
        val bytes = f.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertIs<ApiKeyStore.Load.Discarded>(store().load())
        assertFalse(f.exists())
    }

    @Test fun lockedPhoneDeletesNothing() {
        store().save(TEST_KEY)
        keys.failGet = true
        assertIs<ApiKeyStore.Load.Unavailable>(store(locked = true).load())
        assertTrue(File(dir, "api-key.bin").exists())
    }

    @Test fun strictKeyRefusedFallsBackToPlain() {
        val s = store(cipher = RefusingCipher(keys))
        s.save(TEST_KEY)
        assertEquals(KeyProtection.PLAIN, s.protection())
        assertEquals(TEST_KEY, (s.load() as ApiKeyStore.Load.Ok).key)
    }

    /** moved here with the shared Keystore code from the removed token store (stage 5d) */
    @Test fun modeIsRememberedAfterRestartAndNeverRaised() {
        store(cipher = RefusingCipher(keys)).save(TEST_KEY)
        val again = store() // the app started again, the Keystore would accept a strict key now
        assertEquals(KeyProtection.PLAIN, again.protection())
        again.save(TEST_KEY)
        assertEquals(KeyProtection.PLAIN, again.protection())
        assertEquals(listOf(KeyProtection.STRICT, KeyProtection.PLAIN), keys.created)
    }

    @Test fun lockedPhoneWritesNothingAndKeepsStrict() {
        val s = store(locked = true, cipher = RefusingCipher(keys))
        kotlin.test.assertFailsWith<Exception> { s.save(TEST_KEY) }
        assertEquals(KeyProtection.STRICT, s.protection())
        assertFalse(s.hasKey())
    }

    @Test fun damagedModeFileMeansStrictAndDeleteForgetsTheMode() {
        dir.mkdirs()
        File(dir, "key-protection").writeText("something else")
        assertEquals(KeyProtection.STRICT, store().protection())
        val s = store(cipher = RefusingCipher(keys))
        s.save(TEST_KEY)
        assertEquals(KeyProtection.PLAIN, s.protection())
        s.delete()
        assertEquals(KeyProtection.STRICT, s.protection())
        assertNull(keys.key)
    }

    @Test fun maskAndShape() {
        assertEquals("••••XYZW", ApiKeyStore.mask(TEST_KEY))
        assertEquals(TEST_KEY, ApiKeyStore.normalize("  $TEST_KEY\n"))
        assertNull(ApiKeyStore.normalize("hello world"))
        assertNull(ApiKeyStore.normalize("sk-short"))
        assertNull(ApiKeyStore.normalize("sk-proj-abc def ghi jkl mno pqr"))
        assertTrue(ApiKeyStore.looksLikeKey(TEST_KEY))
        assertFalse(ApiKeyStore.looksLikeKey("Привет, это обычный текст"))
    }

    @Test fun clipboardHistorySkipsKeysAndSensitiveClips() {
        assertTrue(ClipboardHistoryManager.isSecretClip(null, TEST_KEY))
        assertFalse(ClipboardHistoryManager.isSecretClip(null, "обычный текст"))
        val sensitive = ClipDescription("x", arrayOf("text/plain")).apply {
            extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        assertTrue(ClipboardHistoryManager.isSecretClip(sensitive, "123456"))
    }

    @Test fun logHasNoKey() {
        val before = Log.getLog().size
        val s = store()
        s.save(TEST_KEY)
        s.load()
        File(dir, "api-key.bin").writeBytes(byteArrayOf(1, 2, 3))
        s.load()
        val lines = Log.getLog().drop(before).joinToString("\n") { it.message }
        assertFalse(lines.contains("TESTKEY") || lines.contains("sk-"), lines)
    }
}

/** consent, choice, reset on restore, "Delete all", the release build (no voice through OpenAI at all) */
@RunWith(RobolectricTestRunner::class)
class VoiceApiPrefsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = ctx.prefs()

    @Test fun openAiOnlyWithConsent() {
        prefs.edit { clear() }
        assertFalse(VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.OPENAI))
        assertFalse(VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.AUTO))
        assertNull(prefs.getString(VoiceApiPrefs.PREF_ENGINE, null))
        assertTrue(VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.SYSTEM))
        VoiceApiPrefs.giveConsent(prefs)
        assertTrue(VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.OPENAI))
        assertEquals("openai", prefs.getString(VoiceApiPrefs.PREF_ENGINE, null))
        VoiceApiPrefs.withdrawConsent(prefs)
        assertFalse(VoiceApiPrefs.consent(prefs))
        assertEquals("system", prefs.getString(VoiceApiPrefs.PREF_ENGINE, null))
        prefs.edit { clear() }
    }

    @Test fun defaultIsSystem() {
        assertEquals(SpeechEngineSetting.SYSTEM, SpeechEngineSetting.of(null))
        assertEquals(SpeechEngineSetting.SYSTEM, SpeechEngineSetting.of("garbage"))
        assertEquals(VoiceApiPrefs.DEFAULT_MAX_SECONDS, VoiceApiPrefs.maxSeconds(prefs.also { it.edit { clear() } }))
        VoiceApiPrefs.setMaxSeconds(prefs, 9999)
        assertEquals(300, VoiceApiPrefs.maxSeconds(prefs))
        assertEquals(300, VoiceApiUsage(prefs).capMinutes())
        prefs.edit { clear() }
    }

    @Test fun restoredBackupNeverSwitchesVoiceThroughOpenAiOn() {
        prefs.edit {
            clear()
            putBoolean(VoiceApiPrefs.PREF_CONSENT, true)
            putString(VoiceApiPrefs.PREF_ENGINE, "openai")
            putBoolean("some_other_setting", true)
        }
        val out = ByteArrayOutputStream()
        settingsToJsonStream(prefs.all, out)
        prefs.edit { clear() }
        restoreSettings(out.toString().split("\n"), prefs)
        assertTrue(prefs.getBoolean("some_other_setting", false))
        assertFalse(prefs.contains(VoiceApiPrefs.PREF_CONSENT))
        assertFalse(prefs.contains(VoiceApiPrefs.PREF_ENGINE))
        prefs.edit { clear() }
    }

    @Test fun deleteAllRemovesKeyAndVoiceSettings() {
        val dir = Files.createTempDirectory("aikb-key").toFile()
        val keyStore = ApiKeyStore(dir, FakeKeys())
        keyStore.save(TEST_KEY)
        prefs.edit {
            putBoolean(VoiceApiPrefs.PREF_CONSENT, true)
            putString(VoiceApiPrefs.PREF_ENGINE, "auto")
            putLong(VoiceApiUsage.PREF_USED_MS, 1000)
        }
        OpenAiSetup.eraseEverything(prefs, keyStore)
        assertFalse(keyStore.hasKey())
        assertTrue(VoiceApiPrefs.KEYS.none { prefs.contains(it) })
        assertTrue(OpenAiPrefs.ALL_KEYS.containsAll(VoiceApiPrefs.KEYS))
    }

    @Test fun keyIsNeverInSettings() {
        prefs.edit { clear() }
        VoiceApiPrefs.giveConsent(prefs)
        assertTrue(prefs.all.values.none { it.toString().contains("sk-") })
        prefs.edit { clear() }
    }

    /** unit tests are built like the release variant: no INTERNET, no voice through OpenAI */
    @Test fun releaseBuildHasNoVoiceThroughOpenAi() {
        if (BuildConfig.AIKB_OPENAI) return
        prefs.edit {
            clear()
            putBoolean(VoiceApiPrefs.PREF_CONSENT, true)
            putString(VoiceApiPrefs.PREF_ENGINE, "openai")
            putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true)
        }
        assertFalse(VoiceApiPrefs.isAvailable())
        assertEquals(SpeechEngineSetting.SYSTEM, VoiceApiPrefs.engine(prefs))
        assertFalse(VoiceApiPrefs.modeOn(prefs))
        assertNull(VoiceApi.keyStore(ctx))
        assertFalse(AikbNetwork.internetAllowed(ctx))
        prefs.edit { clear() }
    }
}
