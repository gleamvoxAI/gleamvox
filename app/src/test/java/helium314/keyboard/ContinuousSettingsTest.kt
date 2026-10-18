// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiProvider
import helium314.keyboard.latin.ai.AiProviderKind
import helium314.keyboard.latin.ai.AiRequest
import helium314.keyboard.latin.ai.AiResult
import helium314.keyboard.latin.ai.CleanupLevel
import helium314.keyboard.latin.ai.CleanupUi
import helium314.keyboard.latin.ai.DictationCleaner
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.RecordingFocus
import helium314.keyboard.latin.voice.VoiceInput
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.latin.voiceapi.MicChoice
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.openai.FakeField
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5g on Android: settings of continuous dictation and the microphone, the audio focus, a cleanup per phrase */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class ContinuousSettingsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val prefs = latinIME.prefs()

    @BeforeTest fun clean() { prefs.edit { clear() } }

    @Test fun defaultsAndRanges() {
        assertFalse(VoiceApiPrefs.continuous(prefs), "off by default")
        assertEquals(10, VoiceApiPrefs.continuousSilenceS(prefs), "the owner: 10 s")
        assertEquals(5, VoiceApiPrefs.continuousLimitMin(prefs))
        assertEquals(MicChoice.AUTO, MicChoice.read(prefs))
        VoiceApiPrefs.setContinuousSilenceS(prefs, 2); assertEquals(5, VoiceApiPrefs.continuousSilenceS(prefs))
        VoiceApiPrefs.setContinuousSilenceS(prefs, 90); assertEquals(60, VoiceApiPrefs.continuousSilenceS(prefs))
        VoiceApiPrefs.setContinuousLimitMin(prefs, 30); assertEquals(10, VoiceApiPrefs.continuousLimitMin(prefs), "at most 10 minutes")
        VoiceApiPrefs.setContinuousLimitMin(prefs, 0); assertEquals(1, VoiceApiPrefs.continuousLimitMin(prefs))
        VoiceApiPrefs.setContinuous(prefs, true)
        MicChoice.save(prefs, MicChoice.HEADSET)
        assertTrue(VoiceApiPrefs.continuous(prefs))
        assertEquals(MicChoice.HEADSET, MicChoice.read(prefs))
        VoiceApiPrefs.eraseSettings(prefs) // "Delete all"
        assertFalse(VoiceApiPrefs.continuous(prefs))
        assertEquals(MicChoice.AUTO, MicChoice.read(prefs))
    }

    private class Focus : RecordingFocus.Api {
        val calls = mutableListOf<String>()
        override fun request(): Boolean { calls.add("request"); return true }
        override fun abandon() { calls.add("abandon") }
        override fun playing() = true // music plays at every tap of this test (and goes on by itself: no PLAY)
        override fun play() { calls.add("play") }
    }

    /** players pause only while the microphone records: after the phrase, a cancel, an error the focus is given back */
    @Test fun audioFocusOnlyWhileRecording() {
        val focus = Focus()
        val voice = VoiceInput(latinIME, focus) { true }
        voice.onStateChanged(VoiceState.LISTENING, "", null)
        assertTrue(voice.focus.held)
        voice.onStateChanged(VoiceState.LISTENING, "", null) // a continuous dictation stays in it: asked once
        voice.onStateChanged(VoiceState.PROCESSING, "", null)
        assertFalse(voice.focus.held, "the phrase is recorded: music goes on")
        voice.onStateChanged(VoiceState.LISTENING, "", null)
        voice.onStateChanged(VoiceState.IDLE, "", null) // cancel
        voice.onStateChanged(VoiceState.LISTENING, "", null)
        voice.onStateChanged(VoiceState.ERROR, "", helium314.keyboard.latin.voice.VoiceError.MIC_BUSY)
        voice.onStateChanged(VoiceState.LISTENING, "", null)
        voice.destroy()
        assertEquals(List(4) { listOf("request", "abandon") }.flatten(), focus.calls)
    }

    private class Ai(val answers: Map<String, String>) : AiProvider {
        override val kind = AiProviderKind.OWN_KEY
        override fun models(refresh: Boolean) = AiModels.Ok(emptyList())
        override fun respond(request: AiRequest, cancel: CancelSignal, onText: (String) -> Unit) =
            AiResult.Success(answers[request.text] ?: request.text)
    }

    private class Ui : CleanupUi {
        var undo: (() -> Unit)? = null
        override fun showUndo(onUndo: () -> Unit) { undo = onUndo }
        override fun showNote() = Unit
        override fun hide() { undo = null }
    }

    /**
     * Continuous dictation: every phrase is cleaned; an earlier cleanup is not cancelled by the next phrase, the later
     * phrase moves with it, and the cursor stays at the end (where the next phrase goes).
     */
    @Test fun everyPhraseIsCleanedAndTheCursorStays() {
        val raw1 = "ну э-э первая фраза"
        val raw2 = "типа вторая фраза"
        val field = FakeField("Привет. $raw1")
        val pending = mutableListOf<Runnable>()
        val ui = Ui()
        val cleaner = DictationCleaner({ Ai(mapOf(raw1 to "Первая фраза", raw2 to "вторая фраза")) }, field, { true }, { CleanupLevel.FULL },
            ui, post = { it.run() }, postDelayed = { _, _ -> }, background = { pending.add(it) })
        cleaner.onInserted(raw1, 8, continuous = true)
        field.text += " $raw2"; field.selStart = field.text.length; field.selEnd = field.selStart // the next phrase
        cleaner.onInserted(raw2, 8 + raw1.length + 1, continuous = true)
        assertEquals(2, pending.size, "the first cleanup is still on its way")
        pending[0].run()
        assertEquals("Привет. Первая фраза $raw2", field.text)
        assertEquals(field.text.length, field.selStart, "the cursor stays after the second phrase")
        pending[1].run()
        assertEquals("Привет. Первая фраза вторая фраза", field.text, "the second phrase moved with the first one")
        assertEquals(field.text.length, field.selStart)
        ui.undo!!.invoke() // "Undo" of the newest phrase
        assertEquals("Привет. Первая фраза $raw2", field.text)
    }

    /** one phrase as before: a new dictation cancels the cleanup of the old one */
    @Test fun aNewDictationStillCancelsTheOldCleanup() {
        val field = FakeField("один два три четыре")
        val pending = mutableListOf<Runnable>()
        val cleaner = DictationCleaner({ Ai(mapOf("один два три четыре" to "Один, два, три, четыре")) }, field, { true }, { CleanupLevel.FULL },
            Ui(), post = { it.run() }, postDelayed = { _, _ -> }, background = { pending.add(it) })
        cleaner.onInserted("один два три четыре", 0)
        cleaner.onInserted("пять шесть", 100)
        pending[0].run()
        assertEquals("один два три четыре", field.text)
    }
}

/**
 * Stage 5g-2: on the HONOR "Clear journal" was squeezed next to "Copy details" (two letters a line). The buttons are
 * now under each other over the whole width, at least 48 dp high; checked on a narrow screen in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WideButtonsTest {
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    private val density get() = app.resources.displayMetrics.density

    private fun checkWide(screen: ComposeScreen, labels: List<String>) {
        val width = app.resources.displayMetrics.widthPixels
        var lastBottom = -1f
        for (label in labels) {
            val b = screen.buttonBounds(label)
            assertTrue(b.height >= 48 * density - 1, "$label: ${b.height} px high, at least 48 dp")
            assertTrue(b.width >= width * 0.6f, "$label: ${b.width} of $width px wide")
            assertTrue(b.top >= lastBottom, "$label is under the button before it")
            lastBottom = b.bottom
        }
    }

    @Test fun voiceDiagnosticsButtonsAreWide() {
        for (dark in listOf(false, true)) {
            val s = ComposeScreen.show(dark) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                    helium314.keyboard.settings.screens.VoiceDiagnostics()
                }
            }
            checkWide(s, listOf(app.getString(helium314.keyboard.latin.R.string.voice_diag_copy),
                app.getString(helium314.keyboard.latin.R.string.voice_diag_clear)))
            s.shot("voice_diagnostics_buttons_" + if (dark) "dark" else "light")
        }
    }

    @Test fun balanceButtonsAreWide() {
        val s = ComposeScreen.show(true) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { helium314.keyboard.settings.screens.BalanceBlock() }
        }
        checkWide(s, listOf(app.getString(helium314.keyboard.latin.R.string.voice_balance_top_up),
            app.getString(helium314.keyboard.latin.R.string.voice_balance_set)))
        s.shot("balance_buttons_dark")
    }

    /** "Later" and "Enable" of a card: never squeezed, the second goes to the next line when they don't fit */
    @Test fun cardButtonsAreNotSqueezed() {
        val long = "Включить голосовой ввод в клавиатуре и умное распознавание"
        val s = ComposeScreen.show(false) {
            helium314.keyboard.settings.features.FeatureCard("Голос", "Текст карточки", null, true, onEnable = {}, onLater = {}, enableText = long)
        }
        val later = s.buttonBounds(app.getString(helium314.keyboard.latin.R.string.ai_later))
        val enable = s.buttonBounds(long)
        assertTrue(later.height >= 48 * density - 1 && enable.height >= 48 * density - 1)
        assertTrue(later.width >= 48 * density, "\"Later\" keeps its width: ${later.width}")
        s.shot("feature_card_buttons_light")
    }
}
