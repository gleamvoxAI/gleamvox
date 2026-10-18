// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.SensitiveInput
import helium314.keyboard.latin.voice.VoiceInput
import helium314.keyboard.latin.voice.VoicePermissionActivity
import helium314.keyboard.latin.voice.VoiceState
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice input inside the keyboard: sensitive fields, fallback to the old behaviour, permission, manifest. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class VoiceInputIntegrationTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    private fun editorInfo(inputType: Int, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
        packageName = "com.example"
    }

    private val text = InputType.TYPE_CLASS_TEXT
    private val sensitiveTypes = mapOf(
        "password" to (text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        "visible password" to (text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        "web password" to (text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        "number password" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        "e-mail" to (text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        "web e-mail" to (text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        "number" to InputType.TYPE_CLASS_NUMBER,
        "phone" to InputType.TYPE_CLASS_PHONE,
        "date" to InputType.TYPE_CLASS_DATETIME,
    )
    private val normalTypes = mapOf(
        "text" to text,
        "multi-line" to (text or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        "message" to (text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        "web text" to (text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
        // stage 5g-8, the owner: the address and search bar of Chrome and Edge
        "address bar" to (text or InputType.TYPE_TEXT_VARIATION_URI),
    )

    private fun makeRecognizerAvailable() {
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = "com.example.speech"; name = "Recognizer" } }
        shadowOf(latinIME.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), info)
    }

    private fun setVoicePref(enabled: Boolean) {
        latinIME.prefs().edit {
            clear() // the preferences object is shared between tests
            putBoolean(Settings.PREF_VOICE_INPUT_INTERNAL, enabled)
        }
        Settings.getInstance().loadSettings(latinIME)
    }

    @Test fun sensitiveFields() {
        sensitiveTypes.forEach { (name, type) -> assertTrue(SensitiveInput.isSensitive(editorInfo(type)), name) }
        normalTypes.forEach { (name, type) -> assertFalse(SensitiveInput.isSensitive(editorInfo(type)), name) }
        assertTrue(SensitiveInput.isSensitive(editorInfo(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)), "do not remember")
        // an incognito tab: the address bar with "do not remember" stays closed (stage 5g-8)
        assertTrue(SensitiveInput.isSensitive(editorInfo(text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)), "incognito address bar")
        assertTrue(SensitiveInput.isSensitive(null))
    }

    @Test fun noMicrophoneInSensitiveFields() {
        RichInputMethodManager.init(latinIME)
        sensitiveTypes.forEach { (name, type) ->
            val attrs = InputAttributes(editorInfo(type), false, latinIME.packageName)
            assertFalse(attrs.mMayShowVoiceInputKey, name)
            assertFalse(attrs.mShouldShowVoiceInputKey, name)
        }
        val noLearning = InputAttributes(editorInfo(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false, latinIME.packageName)
        assertFalse(noLearning.mMayShowVoiceInputKey, "do not remember")
        normalTypes.forEach { (name, type) ->
            assertTrue(InputAttributes(editorInfo(type), false, latinIME.packageName).mMayShowVoiceInputKey, name)
        }
    }

    @Test fun micInSensitiveFieldDoesNothing() {
        makeRecognizerAvailable()
        setVoicePref(true)
        val voice = VoiceInput(latinIME) { true }
        assertTrue(voice.onMicKey(editorInfo(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))) // handled: no fallback either
        assertNull(shadowOf(latinIME.application).nextStartedActivity)
        assertEquals(VoiceState.IDLE, voice.controller.state)
    }

    @Test fun switchedOffUsesOldBehaviour() {
        makeRecognizerAvailable()
        setVoicePref(false)
        assertFalse(VoiceInput(latinIME) { true }.onMicKey(editorInfo(text)))
    }

    @Test fun noRecognizerUsesOldBehaviour() {
        setVoicePref(true)
        assertFalse(VoiceInput(latinIME) { false }.onMicKey(editorInfo(text)))
    }

    @Test fun permissionIsAskedOnceThenOldBehaviour() {
        makeRecognizerAvailable()
        setVoicePref(true)
        shadowOf(latinIME.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val voice = VoiceInput(latinIME) { true }
        assertTrue(voice.onMicKey(editorInfo(text)))
        val started = shadowOf(latinIME.application).nextStartedActivity
        assertEquals(VoicePermissionActivity::class.java.name, started?.component?.className)
        assertEquals(VoiceState.IDLE, voice.controller.state) // listening never starts by itself
        assertEquals("permission_asked", helium314.keyboard.latin.voice.VoiceJournal.get(latinIME).entries().first().notStarted)
        // denied: next time the old behaviour, no second dialog
        assertFalse(voice.onMicKey(editorInfo(text)))
        assertNull(shadowOf(latinIME.application).nextStartedActivity)
    }

    @Test fun withPermissionButNoVisibleKeyboardNothingIsRecorded() {
        makeRecognizerAvailable()
        setVoicePref(true)
        shadowOf(latinIME.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val journal = helium314.keyboard.latin.voice.VoiceJournal.get(latinIME).apply { clear() }
        val voice = VoiceInput(latinIME) { true }
        assertTrue(voice.onMicKey(editorInfo(text)))
        assertEquals(VoiceState.IDLE, voice.controller.state) // the panel can't be shown, so no listening
        // stage 5zh: never silently: a message, and the journal says why
        assertEquals(latinIME.getString(helium314.keyboard.latin.R.string.voice_error_no_panel), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("no_panel", journal.entries().first().notStarted)
    }

    @Test fun manifestHasOnlyRecordAudioAddedAndNoInternet() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val androidNs = "http://schemas.android.com/apk/res/android"
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
        val permissions = elements("uses-permission").map { it.getAttributeNS(androidNs, "name") }.toSet()
        assertEquals(setOf(
            "android.permission.READ_USER_DICTIONARY",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.VIBRATE",
            "android.permission.WRITE_USER_DICTIONARY",
            "android.permission.READ_CONTACTS",
            "android.permission.RECORD_AUDIO",
            // stage 5g-15 (the owner, 2026-10-16): "Offer a fresh screenshot", off by default, asked for only when it is turned on
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        ), permissions)
        assertFalse("android.permission.INTERNET" in permissions)
        val queryActions = elements("queries").flatMap { q ->
            q.getElementsByTagName("action").let { list -> (0 until list.length).map { (list.item(it) as Element).getAttributeNS(androidNs, "name") } }
        }
        assertTrue(RecognitionService.SERVICE_INTERFACE in queryActions)
        val activity = elements("activity").single { it.getAttributeNS(androidNs, "name").endsWith("VoicePermissionActivity") }
        assertEquals("false", activity.getAttributeNS(androidNs, "exported"))
        assertEquals("", activity.getAttributeNS(androidNs, "taskAffinity"), "own task: back to the app where the user typed")
        assertTrue(activity.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("true", activity.getAttributeNS(androidNs, "excludeFromRecents"))
        assertEquals("true", activity.getAttributeNS(androidNs, "noHistory"))
    }
}
