// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowProximityInfo
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiAssist
import helium314.keyboard.latin.ai.AiCommand
import helium314.keyboard.latin.ai.AiCommands
import helium314.keyboard.latin.ai.AiError
import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiProvider
import helium314.keyboard.latin.ai.AiProviderKind
import helium314.keyboard.latin.ai.AiQuickRunner
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.ai.LongPressCommand
import helium314.keyboard.latin.ai.LongPressDetector
import helium314.keyboard.latin.ai.QuickRunUi
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Long press of ✨ (docs/DESIGN.md, 5е-1): detection, default command, replacement with the check, "Undo", cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class AiLongPressTest {
    // ---- the press itself ----

    private class Clock {
        var now = 0L
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(ms: Long, action: () -> Unit): () -> Unit {
            val t = (now + ms) to action
            timers.add(t)
            return { timers.remove(t) }
        }
        fun advance(ms: Long) {
            now += ms
            timers.filter { it.first <= now }.forEach { timers.remove(it); it.second() }
        }
    }

    private class Presses(clock: Clock) {
        var taps = 0
        var longs = 0
        val d = LongPressDetector(LongPressDetector.TIMEOUT_MS, 20f, clock::schedule, { taps++ }, { longs++ })
    }

    @Test fun shortPressOpensTheCommands() {
        val c = Clock(); val p = Presses(c)
        p.d.down(10f, 10f); c.advance(200); p.d.up()
        assertEquals(1, p.taps); assertEquals(0, p.longs)
        c.advance(1000)
        assertEquals(0, p.longs, "the timer of a released press must not fire")
    }

    @Test fun longPressFiresAfterAbout450msAndUpIsNotATap() {
        val c = Clock(); val p = Presses(c)
        p.d.down(10f, 10f); c.advance(449)
        assertEquals(0, p.longs)
        c.advance(1)
        assertEquals(1, p.longs, "fires while the finger is still down")
        p.d.up()
        assertEquals(0, p.taps)
        assertEquals(450L, LongPressDetector.TIMEOUT_MS)
    }

    @Test fun movingAFewMillimetersCancelsBoth() {
        val c = Clock(); val p = Presses(c)
        p.d.down(10f, 10f); p.d.move(25f, 18f) // 17 px: within the slop of 20
        c.advance(500)
        assertEquals(1, p.longs, "a small tremble does not cancel")
        p.d.up()
        p.d.down(10f, 10f); p.d.move(10f, 40f); c.advance(500); p.d.up()
        assertEquals(1, p.longs); assertEquals(0, p.taps, "moved away: neither tap nor long press")
        p.d.down(10f, 10f); p.d.cancel(); c.advance(500)
        assertEquals(1, p.longs); assertEquals(0, p.taps)
    }

    // ---- settings ----

    private val ime: LatinIME by lazy { Robolectric.setupService(LatinIME::class.java) }

    @Test fun defaultCommandIsFixWithoutPreview() {
        val prefs = ime.prefs()
        prefs.edit { remove(AiPrefs.PREF_LONG_PRESS_COMMAND); remove(AiPrefs.PREF_LONG_PRESS_PREVIEW) }
        assertEquals(LongPressCommand.FIX, AiPrefs.longPressCommand(prefs))
        assertEquals(AiCommand.FIX, AiPrefs.longPressCommand(prefs).command)
        assertFalse(AiPrefs.longPressPreview(prefs))
        AiPrefs.setLongPressCommand(prefs, LongPressCommand.TRANSLATE_SECOND)
        AiPrefs.setLongPressPreview(prefs, true)
        assertEquals(LongPressCommand.TRANSLATE_SECOND, AiPrefs.longPressCommand(prefs))
        assertEquals("en", AiPrefs.longPressCommand(prefs).language("ru", "en"))
        assertEquals("ru", LongPressCommand.TRANSLATE_LAYOUT.language("ru", "en"))
        assertNull(LongPressCommand.SHORTEN.language("ru", "en"))
        assertTrue(AiPrefs.longPressPreview(prefs))
        prefs.edit { putString(AiPrefs.PREF_LONG_PRESS_COMMAND, "garbage") }
        assertEquals(LongPressCommand.FIX, AiPrefs.longPressCommand(prefs))
        // "Delete everything" removes them with the other AI settings
        assertTrue(AiPrefs.PREF_LONG_PRESS_COMMAND in AiPrefs.KEYS && AiPrefs.PREF_LONG_PRESS_PREVIEW in AiPrefs.KEYS)
    }

    // ---- the run ----

    private class FakeAi(var answer: String = "Привет, как дела?", var error: AiError? = null) : AiProvider {
        override val kind = AiProviderKind.OWN_KEY
        val asked = mutableListOf<AiRequest>()
        override fun models(refresh: Boolean) = AiModels.Ok(emptyList())
        override fun respond(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit): AiResult {
            asked.add(request)
            if (cancel.isCancelled) return AiResult.Failure(AiError.CANCELLED)
            return error?.let { AiResult.Failure(it) } ?: AiResult.Success(answer)
        }
    }

    private class Ui : QuickRunUi {
        var isBusyNow = false
        var busyChanges = 0
        var undo: (() -> Unit)? = null
        val messages = mutableListOf<Int>()
        var settingsLink = false
        override fun setBusy(busy: Boolean) { isBusyNow = busy; busyChanges++ }
        override fun showMessage(res: Int, onClick: (() -> Unit)?) { messages.add(res); settingsLink = onClick != null }
        override fun showUndo(onUndo: () -> Unit) { undo = onUndo }
        override fun hide() { undo = null }
    }

    private class Run(text: String, selStart: Int = text.length, selEnd: Int = selStart, val ai: FakeAi = FakeAi(), holdRequest: Boolean = false) {
        val field = FakeField(text, selStart, selEnd)
        val ui = Ui()
        val delayed = mutableListOf<Pair<Runnable, Long>>()
        /** a request that has not answered yet */
        var pending: Runnable? = null
        val runner = AiQuickRunner({ ai }, field, ui, { it.run() }, { r, ms -> delayed.add(r to ms) },
            { if (holdRequest) pending = it else it.run() }, log = { assertFalse("Привет" in it || "привет" in it, "text in the log: $it") })
            .apply { setSettingsOpener { } }
        fun timeout(ms: Long) { delayed.filter { it.second == ms }.forEach { it.first.run() } }
    }

    @Test fun replacesTheWholeFieldAndUndoBringsItBack() {
        val r = Run("привет как дила")
        r.runner.run(AiCommand.FIX, null)
        assertEquals(1, r.ai.asked.size)
        assertEquals(AiCommands.instructions(AiCommand.FIX), r.ai.asked[0].instructions)
        assertEquals("привет как дила", r.ai.asked[0].text)
        assertEquals("Привет, как дела?", r.field.text)
        assertEquals(listOf("replace 0..15"), r.field.edits)
        assertFalse(r.ui.isBusyNow, "the pulse stops with the answer")
        assertEquals(2, r.ui.busyChanges)
        r.ui.undo!!()
        assertEquals("привет как дила", r.field.text)
        assertNull(r.ui.undo)
    }

    @Test fun replacesOnlyTheSelection() {
        val r = Run("Мама: привет как дила. Пока", 6, 21)
        r.runner.run(AiCommand.FIX, null)
        assertEquals("привет как дила", r.ai.asked[0].text)
        assertEquals("Мама: Привет, как дела?. Пока", r.field.text)
    }

    @Test fun undoDisappearsAfter8Seconds() {
        val r = Run("привет как дила")
        r.runner.run(AiCommand.FIX, null)
        assertTrue(r.delayed.any { it.second == 8_000L })
        r.timeout(8_000L)
        assertNull(r.ui.undo)
    }

    @Test fun undoDoesNothingWhenTheTextChangedMeanwhile() {
        val r = Run("привет как дила")
        r.runner.run(AiCommand.FIX, null)
        val undo = r.ui.undo!!
        r.field.text = "Привет, как дела? Новое"; r.field.selStart = 5; r.field.selEnd = 5 // the user edited it
        undo()
        assertEquals("Привет, как дела? Новое", r.field.text)
    }

    @Test fun fieldChangedDuringTheRequestInsertsNextToItAndOverwritesNothing() {
        val r = Run("привет как дила", holdRequest = true)
        r.runner.run(AiCommand.FIX, null)
        assertTrue(r.ui.isBusyNow, "✨ pulses while the request runs")
        r.field.text = "привет как дила!!"; r.field.selStart = 17; r.field.selEnd = 17 // typed during the request
        r.pending!!.run()
        assertEquals("привет как дила!! Привет, как дела?", r.field.text, "the user's text stays untouched")
        assertEquals(listOf("insert 17"), r.field.edits)
        r.ui.undo!!()
        assertEquals("привет как дила!!", r.field.text)
    }

    @Test fun continueInsertsAfterTheFragment() {
        val r = Run("Я приду завтра", ai = FakeAi(answer = "Буду к обеду."))
        r.runner.run(AiCommand.CONTINUE, null)
        assertEquals("Я приду завтра Буду к обеду.", r.field.text)
    }

    @Test fun tapWhileRunningCancelsAndInsertsNothing() {
        val r = Run("привет как дила", holdRequest = true)
        r.runner.run(AiCommand.FIX, null)
        assertTrue(r.runner.isBusy)
        r.runner.cancelRequest()
        assertFalse(r.runner.isBusy); assertFalse(r.ui.isBusyNow)
        r.pending!!.run() // the answer arrives late
        assertEquals("привет как дила", r.field.text)
        assertTrue(r.field.edits.isEmpty())
        assertTrue(r.ui.messages.isEmpty())
    }

    @Test fun errorsAreAShortLine() {
        val r = Run("привет", ai = FakeAi(error = AiError.NO_BALANCE))
        r.runner.run(AiCommand.FIX, null)
        assertEquals(listOf(R.string.ai_quick_no_balance), r.ui.messages)
        assertEquals("привет", r.field.text)
        assertTrue(r.delayed.any { it.second == AiQuickRunner.MESSAGE_MS })
        val noKey = Run("привет", ai = FakeAi(error = AiError.NO_KEY))
        noKey.runner.run(AiCommand.FIX, null)
        assertEquals(listOf(R.string.ai_quick_no_key), noKey.ui.messages)
        assertTrue(noKey.ui.settingsLink, "a missing key opens the AI settings")
        val cap = Run("привет", ai = FakeAi(error = AiError.DAILY_CAP))
        cap.runner.run(AiCommand.FIX, null)
        assertEquals(listOf(R.string.ai_quick_daily_cap), cap.ui.messages)
        for (e in AiError.entries) AiQuickRunner.shortRes(e) // every error has a short line
    }

    @Test fun emptyOrTooLongFieldSendsNothing() {
        val empty = Run("   ")
        empty.runner.run(AiCommand.FIX, null)
        assertEquals(0, empty.ai.asked.size)
        assertEquals(listOf(R.string.ai_quick_empty), empty.ui.messages)
        val long = Run("а".repeat(4001))
        long.runner.run(AiCommand.FIX, null)
        assertEquals(0, long.ai.asked.size)
        assertEquals(listOf(R.string.ai_quick_too_long), long.ui.messages)
    }

    @Test fun sameTextIsNotReplaced() {
        val r = Run("Привет, как дела?")
        r.runner.run(AiCommand.FIX, null)
        assertTrue(r.field.edits.isEmpty())
        assertEquals(listOf(R.string.ai_quick_unchanged), r.ui.messages)
    }

    @Test fun notReadyReasons() {
        assertEquals(AiError.INTERNET_OFF, AiGuard.notReadyReason(false, true, false, true))
        assertEquals(AiError.NO_KEY, AiGuard.notReadyReason(true, false, false, true))
        assertEquals(AiError.KEY_INVALID, AiGuard.notReadyReason(true, true, true, true))
        assertEquals(AiError.NO_CONSENT, AiGuard.notReadyReason(true, true, false, false))
    }

    // ---- through AiAssist, as the button calls it ----

    @AfterTest fun reset() { AiAssist.inBuild = { helium314.keyboard.latin.openai.OpenAiPrefs.isAvailable() } }

    private fun assist(field: FakeField, ai: FakeAi, internet: Boolean = true, ready: Boolean = true): AiAssist {
        AiAssist.inBuild = { true }
        ime.prefs().edit { remove(AiPrefs.PREF_LONG_PRESS_COMMAND); remove(AiPrefs.PREF_LONG_PRESS_PREVIEW); remove(AiPrefs.PREF_EXCLUDED_APPS) }
        return AiAssist(ime, { ai }, field, { internet }, { ready }, { it.run() })
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun info(type: Int, pkg: String = "com.example.chat") = EditorInfo().apply { inputType = type; packageName = pkg }

    private val textField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun longPressRunsFixAtOnce() {
        val field = FakeField("привет как дила"); val ai = FakeAi()
        val a = assist(field, ai)
        a.onInputViewStarted(info(textField))
        a.onAiLongPress()
        idle()
        assertEquals(1, ai.asked.size)
        assertEquals(AiCommands.instructions(AiCommand.FIX), ai.asked[0].instructions)
        assertEquals("Привет, как дела?", field.text)
        assertFalse(a.isPanelShown, "no preview by default")
    }

    @Test fun longPressInSensitiveFieldsAndExcludedAppsSendsNothing() {
        val sensitive = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER,
        )
        for (t in sensitive) {
            val field = FakeField("секрет"); val ai = FakeAi()
            val a = assist(field, ai)
            a.onInputViewStarted(info(t))
            assertEquals(AiGuard.Button.HIDDEN, a.buttonState())
            a.onAiLongPress(); idle()
            assertEquals(0, ai.asked.size, "sent from input type $t")
            assertEquals("секрет", field.text)
        }
        val field = FakeField("текст"); val ai = FakeAi()
        val a = assist(field, ai)
        AiPrefs.addExcluded(ime.prefs(), "com.bank")
        a.onInputViewStarted(info(textField, "com.bank"))
        a.onAiLongPress(); idle()
        assertEquals(0, ai.asked.size, "sent from an excluded app")
    }

    @Test fun longPressWithoutInternetOrKeySendsNothing() {
        for ((internet, ready) in listOf(false to true, true to false)) {
            val field = FakeField("привет как дила"); val ai = FakeAi()
            val a = assist(field, ai, internet, ready)
            a.onInputViewStarted(info(textField))
            assertEquals(AiGuard.Button.OPENS_SETTINGS, a.buttonState())
            a.onAiLongPress(); idle()
            assertEquals(0, ai.asked.size)
            assertEquals("привет как дила", field.text)
        }
    }

    @Test fun tapOnTheIconCancelsTheRequest() {
        val field = FakeField("привет как дила"); val ai = FakeAi()
        var pending: Runnable? = null
        AiAssist.inBuild = { true }
        ime.prefs().edit { remove(AiPrefs.PREF_LONG_PRESS_COMMAND); remove(AiPrefs.PREF_LONG_PRESS_PREVIEW) }
        val a = AiAssist(ime, { ai }, field, { true }, { true }, { pending = it })
        a.onInputViewStarted(info(textField))
        a.onAiLongPress()
        assertTrue(a.isQuickBusy)
        a.onAiKey() // the tap
        assertFalse(a.isQuickBusy)
        assertFalse(a.isPanelShown, "the tap cancels, it does not open the commands")
        pending!!.run(); idle()
        assertEquals("привет как дила", field.text)
    }

    @Test fun longPressUsesTheChosenCommand() {
        val field = FakeField("Очень длинный и подробный текст"); val ai = FakeAi(answer = "Короткий текст")
        val a = assist(field, ai)
        AiPrefs.setLongPressCommand(ime.prefs(), LongPressCommand.SHORTEN)
        a.onInputViewStarted(info(textField))
        a.onAiLongPress(); idle()
        assertEquals(AiCommands.instructions(AiCommand.SHORTEN), ai.asked.single().instructions)
        assertEquals("Короткий текст", field.text)
    }

    // ---- stage 5zh, item 9: the dimmed ✨ opens what is missing, the reason in plain words ----

    @Test fun tapOnDimmedSparkleOpensWhatIsMissing() {
        val cases = mapOf(
            AiError.NO_CONSENT to AiGuard.SettingsTarget.CONSENT, // only the consent is missing: its window at once
            AiError.NO_KEY to AiGuard.SettingsTarget.KEY,
            AiError.KEY_INVALID to AiGuard.SettingsTarget.KEY,
            AiError.INTERNET_OFF to AiGuard.SettingsTarget.INTERNET,
        )
        for ((reason, target) in cases) {
            val field = FakeField("привет"); val ai = FakeAi()
            AiAssist.inBuild = { true }
            val a = AiAssist(ime, { ai }, field, { reason != AiError.INTERNET_OFF }, { false }, { it.run() }, notReady = { reason })
            a.onInputViewStarted(info(textField))
            assertEquals(AiGuard.Button.OPENS_SETTINGS, a.buttonState())
            a.onAiKey(); idle()
            val started = shadowOf(ime.application).nextStartedActivity
            assertEquals(helium314.keyboard.settings.SettingsActivity.ACTION_OPEN_AI, started?.action, reason.name)
            assertEquals(target.value, started?.getStringExtra(helium314.keyboard.settings.SettingsActivity.EXTRA_AI_TARGET), reason.name)
            assertEquals(0, ai.asked.size)
            assertFalse(a.isPanelShown)
        }
    }

    @Test fun theTargetIsTakenOnce() {
        helium314.keyboard.settings.screens.AiScreenTarget.set("consent")
        assertEquals(AiGuard.SettingsTarget.CONSENT, helium314.keyboard.settings.screens.AiScreenTarget.take())
        assertEquals(AiGuard.SettingsTarget.NONE, helium314.keyboard.settings.screens.AiScreenTarget.take())
        helium314.keyboard.settings.screens.AiScreenTarget.set("something else")
        assertEquals(AiGuard.SettingsTarget.NONE, helium314.keyboard.settings.screens.AiScreenTarget.take())
    }

    @Config(qualifiers = "ru")
    @Test fun reasonsInPlainWords() {
        val ctx = ime
        assertEquals("✨ Нужно согласие на ИИ-текст: нажмите здесь", ctx.getString(AiQuickRunner.shortRes(AiError.NO_CONSENT)))
        assertEquals("✨ Вставьте ключ OpenAI: нажмите здесь", ctx.getString(AiQuickRunner.shortRes(AiError.NO_KEY)))
        assertEquals("✨ Включите интернет: нажмите здесь", ctx.getString(AiQuickRunner.shortRes(AiError.INTERNET_OFF)))
    }
}
