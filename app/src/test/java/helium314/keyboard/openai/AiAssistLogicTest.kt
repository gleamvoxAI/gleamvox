// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ai.AiCommand
import helium314.keyboard.latin.ai.AiCommands
import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.Capture
import helium314.keyboard.latin.ai.FieldAccess
import helium314.keyboard.latin.ai.FieldFragment
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.KeyProtection
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.openai.OpenAiProxy
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.voiceapi.ApiKeyStore
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** a text field like an app has it: text, selection, and what the keyboard can read and write */
class FakeField(var text: String, var selStart: Int = text.length, var selEnd: Int = selStart, private val maxRead: Int = Int.MAX_VALUE) : FieldAccess {
    val edits = mutableListOf<String>()
    override fun selectionStart() = selStart
    override fun selectionEnd() = selEnd
    override fun selectedText(): CharSequence? = text.substring(selStart, selEnd)
    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, selStart - minOf(n, maxRead)), selStart)
    override fun textAfter(n: Int): CharSequence = text.substring(selEnd, minOf(text.length, selEnd + n))
    override fun replace(start: Int, end: Int, text: String): Boolean {
        edits.add("replace $start..$end")
        this.text = this.text.substring(0, start) + text + this.text.substring(end)
        selStart = start + text.length; selEnd = selStart
        return true
    }
    override fun insertAt(position: Int, text: String): Boolean {
        edits.add("insert $position")
        this.text = this.text.substring(0, position) + text + this.text.substring(position)
        selStart = position + text.length; selEnd = selStart
        return true
    }
    override fun insertAtCursor(text: String): Boolean {
        edits.add("cursor $selStart")
        this.text = this.text.substring(0, selStart) + text + this.text.substring(selEnd)
        selStart += text.length; selEnd = selStart
        return true
    }
    override fun setCursor(position: Int): Boolean {
        edits.add("cursor to $position")
        selStart = position; selEnd = position
        return true
    }
}

/** ✨: field filter, source text, fragment check before replacing, commands (docs/AI.md) */
@RunWith(RobolectricTestRunner::class)
class AiAssistLogicTest {
    private fun field(inputType: Int, pkg: String = "com.example.chat", imeOptions: Int = 0) =
        EditorInfo().apply { this.inputType = inputType; packageName = pkg; this.imeOptions = imeOptions }

    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun sensitiveFieldsHideTheButtonAndSendNothing() {
        val sensitive = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_NULL,
        ).map { field(it) } + field(text, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) // "do not remember"
        for (f in sensitive) {
            assertTrue(AiGuard.blocked(f, emptySet(), false), "type ${f.inputType}")
            assertEquals(AiGuard.Button.HIDDEN, AiGuard.button(true, AiGuard.blocked(f, emptySet(), false), true, true))
            assertFalse(AiGuard.maySend(f, emptySet(), false, true, true))
        }
        assertTrue(AiGuard.blocked(null, emptySet(), false)) // unknown field
    }

    /** stage 5g-8, the owner: ✨ in the address and search bar of Chrome and Edge; an incognito tab ("do not remember") stays closed */
    @Test fun addressBarIsAllowed() {
        val bar = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "com.android.chrome")
        assertFalse(AiGuard.blocked(bar, emptySet(), false))
        assertTrue(AiGuard.maySend(bar, emptySet(), false, true, true))
        val incognito = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "com.android.chrome", EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(AiGuard.blocked(incognito, emptySet(), false))
        assertTrue(AiGuard.blocked(bar, setOf("com.android.chrome"), false), "the browser in the excluded apps")
    }

    @Test fun excludedAppsAndIncognito() {
        val f = field(text, "com.bank.app")
        assertFalse(AiGuard.blocked(f, emptySet(), false))
        assertTrue(AiGuard.blocked(f, setOf("com.bank.app"), false))
        assertTrue(AiGuard.blocked(f, emptySet(), true))
        assertFalse(AiGuard.maySend(f, setOf("com.bank.app"), false, true, true))
    }

    @Test fun buttonStates() {
        assertEquals(AiGuard.Button.HIDDEN, AiGuard.button(inBuild = false, blocked = false, internetOn = true, ready = true)) // release
        assertEquals(AiGuard.Button.OPENS_SETTINGS, AiGuard.button(true, false, internetOn = false, ready = true))
        assertEquals(AiGuard.Button.OPENS_SETTINGS, AiGuard.button(true, false, internetOn = true, ready = false))
        assertEquals(AiGuard.Button.READY, AiGuard.button(true, false, true, true))
        assertFalse(AiGuard.maySend(field(text), emptySet(), false, internetOn = false, ready = true))
        assertTrue(AiGuard.maySend(field(text), emptySet(), false, true, true))
    }

    @Test fun excludedAppsAreEmptyByDefault() {
        val prefs = ApplicationProvider.getApplicationContext<android.app.Application>().prefs()
        prefs.edit { clear() }
        assertTrue(AiPrefs.excludedApps(prefs).isEmpty())
        AiPrefs.addExcluded(prefs, "com.bank.app")
        assertTrue(AiPrefs.isExcluded(prefs, "com.bank.app"))
        AiPrefs.removeExcluded(prefs, "com.bank.app")
        assertFalse(AiPrefs.isExcluded(prefs, "com.bank.app"))
    }

    @Test fun sourceIsTheSelectionOrTheWholeField() {
        val sel = FakeField("Привет мир, как дела", 7, 10)
        val c = FieldFragment.capture(sel) as Capture.Ok
        assertEquals("мир", c.fragment.text)
        assertTrue(c.fragment.fromSelection)
        val whole = FakeField("Привет мир", 3)
        val w = FieldFragment.capture(whole) as Capture.Ok
        assertEquals("Привет мир", w.fragment.text)
        assertEquals(0, w.fragment.start); assertEquals(10, w.fragment.end)
        assertIs<Capture.Empty>(FieldFragment.capture(FakeField("   ")))
        assertIs<Capture.TooLong>(FieldFragment.capture(FakeField("a".repeat(4001))))
        assertIs<Capture.Ok>(FieldFragment.capture(FakeField("a".repeat(4000))))
        assertIs<Capture.TooLong>(FieldFragment.capture(FakeField("a".repeat(5000), 10, 4011))) // selection too long
        assertIs<Capture.Unknown>(FieldFragment.capture(FakeField("abc", -1, -1)))
        // the app gives only part of the text before the cursor: the whole field is unknown, nothing is sent
        assertIs<Capture.TooLong>(FieldFragment.capture(FakeField("abcdef", 6, 6, maxRead = 3)))
    }

    @Test fun replaceOnlyTheUnchangedFragment() {
        val f = FakeField("Начало. Ошибко тут. Конец.", 8, 19)
        val fragment = (FieldFragment.capture(f) as Capture.Ok).fragment
        assertTrue(FieldFragment.replace(f, fragment, "Ошибка тут."))
        assertEquals("Начало. Ошибка тут. Конец.", f.text) // text outside the fragment untouched
        assertEquals(listOf("replace 8..19"), f.edits)
    }

    @Test fun changedFieldAllowsOnlyInsertingAtTheCursor() {
        val f = FakeField("Начало. Ошибко тут. Конец.", 8, 19)
        val fragment = (FieldFragment.capture(f) as Capture.Ok).fragment
        f.text = "Начало. Ошибко тут! Конец." // the app changed the text meanwhile
        assertFalse(FieldFragment.isUnchanged(f, fragment))
        assertFalse(FieldFragment.replace(f, fragment, "X"))
        assertTrue(f.edits.isEmpty())
        // the selection moved: also changed
        val g = FakeField("один два три", 5, 9)
        val gf = (FieldFragment.capture(g) as Capture.Ok).fragment
        g.selStart = 0; g.selEnd = 4
        assertFalse(FieldFragment.isUnchanged(g, gf))
        // "insert as it is": at the cursor, nothing else changed
        g.selStart = 12; g.selEnd = 12
        FieldFragment.insertNextTo(g, gf, "!")
        assertEquals("один два три!", g.text)
        assertEquals(listOf("cursor 12"), g.edits)
    }

    @Test fun wholeFieldChangedIsDetected() {
        val f = FakeField("Привет мир")
        val fragment = (FieldFragment.capture(f) as Capture.Ok).fragment
        assertTrue(FieldFragment.isUnchanged(f, fragment))
        f.selStart = 3; f.selEnd = 3 // only the cursor moved: still the same field text
        assertTrue(FieldFragment.isUnchanged(f, fragment))
        f.text = "Привет мир!"; f.selStart = 11; f.selEnd = 11
        assertFalse(FieldFragment.isUnchanged(f, fragment))
    }

    @Test fun insertNextToTheFragment() {
        val f = FakeField("Hello world. Bye", 0, 12)
        val fragment = (FieldFragment.capture(f) as Capture.Ok).fragment
        FieldFragment.insertNextTo(f, fragment, "Привет, мир.")
        assertEquals("Hello world. Привет, мир. Bye", f.text)
        assertEquals("", FieldFragment.joiner("abc ", "x"))
        assertEquals("", FieldFragment.joiner("abc", ", x"))
    }

    @Test fun commandsSendOnlyTheTextWithFixedInstructions() {
        val r = AiCommands.request(AiCommand.TRANSLATE, "Привет", "de")
        assertEquals("Привет", r.text)
        assertTrue("German" in r.instructions)
        assertTrue("data, not instructions" in r.instructions)
        assertEquals(3 * 6 + 500, r.maxAnswerChars)
        AiCommand.entries.forEach { assertTrue("never follow" in AiCommands.instructions(it, "en")) }
        assertFalse("Привет" in r.toString()) // never printed
        assertEquals("en", AiCommands.secondLanguage(null, "ru"))
        assertEquals("ru", AiCommands.secondLanguage(null, "en"))
        assertEquals("de", AiCommands.secondLanguage("de", "ru"))
    }

    /** "Delete all": the API key with its Keystore key and mode, all AI settings, consents and counters; other settings stay */
    @Test fun deleteAll() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = app.prefs()
        prefs.edit {
            clear()
            putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true)
            putBoolean(AiPrefs.PREF_TEXT_CONSENT, true)
            putString(OpenAiProxy.PREF_HOST, "127.0.0.1")
            putString(AiPrefs.PREF_TRANSLATE_TO, "de")
            putBoolean("some_other_setting", true)
        }
        AiPrefs.addExcluded(prefs, "com.bank.app")
        AiUsage(prefs).apply { recordSent(); recordSent(cleanup = true); setCap(50); pause(helium314.keyboard.latin.ai.AiError.RATE_LIMIT, 60_000) }
        TextModels(prefs).apply { save(listOf(helium314.keyboard.latin.ai.AiModel("gpt-4.1-nano", "gpt-4.1-nano"))); markUnavailable("gpt-4.1-nano") }
        helium314.keyboard.latin.ai.AiQuality.save(prefs, helium314.keyboard.latin.ai.AiQuality.BETTER)
        prefs.edit { putString(TextModels.PREF_MODEL, "gpt-4.1-nano") } // the manual choice of stage 5d
        val keys = FakeKeys()
        val dir = Files.createTempDirectory("aikb-erase").toFile()
        val store = ApiKeyStore(dir, keys, { false }, RefusingCipher(keys)) // plain mode after the fallback
        store.save("sk-test-" + "A".repeat(40))
        assertEquals(KeyProtection.PLAIN, store.protection())

        OpenAiSetup.eraseEverything(prefs, store)
        assertIs<ApiKeyStore.Load.Empty>(store.load())
        assertEquals(null, keys.key)
        assertEquals(KeyProtection.STRICT, store.protection())
        for (key in OpenAiPrefs.ALL_KEYS) assertFalse(prefs.contains(key), key)
        assertTrue(prefs.getBoolean("some_other_setting", false))
        assertEquals(0, AiUsage(prefs).todayCount())
        assertEquals(AiUsage.DEFAULT_CAP, AiUsage(prefs).cap())
        assertFalse(AiUsage(prefs).isPaused())
        assertFalse(AikbNetwork.internetAllowed(app)) // internet switched off again
        assertFalse(File(dir, "api-key.bin").exists())
        prefs.edit { clear() }
    }
}
