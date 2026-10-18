// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.VoiceAttempt
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceInputView
import helium314.keyboard.latin.voice.VoiceJournal
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.settings.screens.AiAdvancedSection
import helium314.keyboard.settings.screens.VoiceDiagnostics
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5g-4: "Advanced" that is clear when it opens, and a voice journal that is easy to copy (narrow screen, both themes) */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UxClarityTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** the engineering items are behind the developer mode: not there without it, there with it, gone after switching it off */
    @Test fun developerModeShowsAndHidesTheEngineeringRow() {
        val prefs = app.prefs()
        helium314.keyboard.latin.ai.DeveloperMode.set(prefs, false)
        val off = ComposeScreen.show(false) { Column { AiAdvancedSection() } }
        assertFalse(off.has("ai_advanced"), "no row for an ordinary user")
        assertFalse(app.getString(R.string.developer_title) in off.texts())
        helium314.keyboard.latin.ai.DeveloperMode.set(prefs, true)
        for (dark in listOf(false, true)) {
            val row = ComposeScreen.show(dark) { Column { AiAdvancedSection() } }
            assertTrue(row.has("ai_advanced") && "›" in row.texts() && app.getString(R.string.developer_title) in row.texts())
            row.shot("developer_row_" + if (dark) "dark" else "light")
            val screen = ComposeScreen.show(dark) { Column(Modifier.verticalScroll(rememberScrollState())) { helium314.keyboard.settings.screens.AiAdvancedContent() } }
            val t = screen.allText()
            val order = listOf(app.getString(R.string.developer_switch), app.getString(R.string.voice_diag_title),
                app.getString(R.string.voice_mic_check), app.getString(R.string.ai_about_title),
                app.getString(R.string.ai_details), app.getString(R.string.ai_section_connection), app.getString(R.string.openai_check))
            val positions = order.map { t.indexOf(it) }
            assertTrue(positions.all { it >= 0 }, "missing: ${order.filterIndexed { i, _ -> positions[i] < 0 }}\n$t")
            assertEquals(positions.sorted(), positions)
            screen.shot("developer_screen_" + if (dark) "dark" else "light")
        }
        // switching it off on that screen hides the row again
        var left = 0
        val s = ComposeScreen.show(false) { Column { helium314.keyboard.settings.screens.AiAdvancedContent(onSwitchedOff = { left++ }) } }
        s.click(tag = "developer_switch")
        assertEquals(1, left)
        assertFalse(helium314.keyboard.latin.ai.DeveloperMode.on(prefs))
        assertFalse(ComposeScreen.show(false) { Column { AiAdvancedSection() } }.has("ai_advanced"))
    }

    /** 7 taps on the version switch it on, with "N more taps" from the third; off by default; a backup does not bring it back */
    @Test fun sevenTapsSwitchItOn() {
        val prefs = app.prefs()
        helium314.keyboard.latin.ai.DeveloperMode.set(prefs, false)
        assertFalse(helium314.keyboard.latin.ai.DeveloperMode.on(prefs), "off by default")
        val taps = (1..7).map { helium314.keyboard.latin.ai.DeveloperMode.tap(prefs, it) }
        assertTrue(taps[0] is helium314.keyboard.latin.ai.DeveloperMode.Tap.Silent && taps[1] is helium314.keyboard.latin.ai.DeveloperMode.Tap.Silent)
        assertEquals(listOf(4, 3, 2, 1), taps.subList(2, 6).map { (it as helium314.keyboard.latin.ai.DeveloperMode.Tap.More).left })
        assertTrue(taps[6] is helium314.keyboard.latin.ai.DeveloperMode.Tap.Enabled)
        assertTrue(helium314.keyboard.latin.ai.DeveloperMode.on(prefs))
        assertTrue(helium314.keyboard.latin.ai.DeveloperMode.tap(prefs, 8) is helium314.keyboard.latin.ai.DeveloperMode.Tap.Already)
        helium314.keyboard.latin.openai.OpenAiPrefs.resetAfterRestore(prefs)
        assertFalse(helium314.keyboard.latin.ai.DeveloperMode.on(prefs), "not from a backup")
    }

    /** the AI screen of an ordinary user is short: no engineering items, "Report a problem" leads to the hint */
    @Test fun aiScreenOfAnOrdinaryUser() {
        helium314.keyboard.latin.ai.DeveloperMode.set(app.prefs(), false)
        helium314.keyboard.latin.openai.OpenAiPrefs.inBuild = { true }
        helium314.keyboard.latin.voiceapi.VoiceApiPrefs.inBuild = { true }
        val s = ComposeScreen.show(false) { helium314.keyboard.settings.screens.AiScreen(onClickBack = {}) }
        val t = s.allText()
        for (hidden in listOf(R.string.openai_check, R.string.voice_mic_check, R.string.openai_proxy_title, R.string.voice_diag_title,
                R.string.ai_details, R.string.developer_title))
            assertFalse(app.getString(hidden) in t, "\"${app.getString(hidden)}\" only in the developer mode")
        assertTrue(app.getString(R.string.ai_quality_title) in t && app.getString(R.string.report_problem_title) in t)
        // the owner (5g-5): the manual choice of the microphone is for everybody; the dollars only for developers
        assertTrue(app.getString(R.string.voice_mic_choice_title) in t, "the microphone choice for everybody")
        assertFalse(app.getString(R.string.ai_own_key_title) in t, "dollars only in the developer mode")
        assertTrue(t.indexOf(app.getString(R.string.report_problem_title)) < t.indexOf(app.getString(R.string.ai_erase)), "Delete all last")
        s.click(tag = "report_problem")
        assertTrue(app.getString(R.string.report_problem_hint) in s.texts(), "the hint about the developer mode")
    }

    /** the owner (5g-5): the proxy and the check for everybody, but only while OpenAI does not answer */
    @Test fun proxyAndCheckOnlyWhenOpenAiDoesNotAnswer() {
        val prefs = app.prefs()
        helium314.keyboard.latin.ai.DeveloperMode.set(prefs, false)
        helium314.keyboard.latin.openai.OpenAiReach.ok(prefs)
        assertFalse(ComposeScreen.show(false) { Column { helium314.keyboard.settings.screens.OpenAiUnreachableCard() } }.has("openai_unreachable"))
        // a dictation could not reach OpenAI
        helium314.keyboard.latin.voiceapi.VoiceApi.applyResultFully(prefs,
            helium314.keyboard.latin.voiceapi.TranscriptionResult.Failed(helium314.keyboard.latin.voiceapi.VoiceApiProblem.NETWORK), 1000, 5000)
        assertTrue(helium314.keyboard.latin.openai.OpenAiReach.unreachable(prefs))
        for (dark in listOf(false, true)) {
            val s = ComposeScreen.show(dark) { Column(Modifier.verticalScroll(rememberScrollState())) { helium314.keyboard.settings.screens.OpenAiUnreachableCard() } }
            val t = s.allText()
            assertTrue(s.has("openai_unreachable") && app.getString(R.string.openai_check) in t && app.getString(R.string.openai_proxy_title) in t, t)
            s.shot("openai_unreachable_" + if (dark) "dark" else "light")
        }
        // any answer of OpenAI (even an error with a status) hides it again
        helium314.keyboard.latin.voiceapi.VoiceApi.applyResultFully(prefs,
            helium314.keyboard.latin.voiceapi.TranscriptionResult.Failed(helium314.keyboard.latin.voiceapi.VoiceApiProblem.RATE_LIMIT, 429), 0, 6000)
        assertFalse(helium314.keyboard.latin.openai.OpenAiReach.unreachable(prefs))
        assertFalse(ComposeScreen.show(false) { Column { helium314.keyboard.settings.screens.OpenAiUnreachableCard() } }.has("openai_unreachable"))
    }

    /** an error "OpenAI could not be reached" on the panel links to the connection settings, for everybody */
    @Test fun networkErrorOnThePanelLinksToTheConnection() {
        Settings.getInstance().loadSettings(app)
        var connection = 0; var journal = 0
        val p = VoiceInputView(app, Settings.getValues().mColors, object : VoiceInputView.Actions {
            override fun onMic() = Unit
            override fun onDone() = Unit
            override fun onCancel() = Unit
            override fun onOpenJournal() { journal++ }
            override fun onOpenConnection() { connection++ }
        })
        p.setNotice(helium314.keyboard.latin.voiceapi.VoiceApiProblem.NETWORK, false)
        p.update(VoiceState.ERROR, "", VoiceError.OPENAI)
        val link = p.journalLink()!!
        assertEquals(app.getString(R.string.voice_connection_link), link.text.toString())
        link.performClick()
        assertEquals(1 to 0, connection to journal)
    }

    private fun journalWithEntry(): VoiceJournal {
        val j = VoiceJournal.get(app)
        j.clear()
        j.add(VoiceAttempt(System.currentTimeMillis()).apply { engine = "openai"; started = true; source = "MIC"; stop = "pause"; words = 5; inserted = "yes" })
        return j
    }

    @Test fun copyPutsTheJournalInTheClipboardAndSaysSo() {
        val j = journalWithEntry()
        for (dark in listOf(false, true)) {
            val s = ComposeScreen.show(dark) { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceDiagnostics() } }
            val texts = s.texts()
            assertTrue(app.getString(R.string.voice_diag_hint) in texts, "the hint under the title")
            // the main button is first and says what it does
            assertEquals(app.getString(R.string.voice_diag_copy), texts.first { it == app.getString(R.string.voice_diag_copy) })
            s.shot("voice_diag_" + if (dark) "dark" else "light")
            val before = s.buttonBounds(app.getString(R.string.voice_diag_copy))
            assertTrue(before.top < s.buttonBounds(app.getString(R.string.voice_diag_clear)).top, "Copy above Clear")
            s.click(tag = "copy_journal")
            val clip = app.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(clip.startsWith("AI Keyboard: voice journal") && "version:" in clip && "words 5" in clip, clip)
            assertEquals(app.getString(R.string.voice_diag_copied_toast), ShadowToast.getTextOfLatestToast())
            assertTrue(app.getString(R.string.voice_diag_copied_button) in s.texts(), "the button says Copied for 2 s")
        }
        j.clear()
    }

    @Test fun clearAsksFirst() {
        val j = journalWithEntry()
        val s = ComposeScreen.show(false) { Column { VoiceDiagnostics() } }
        s.click(text = app.getString(R.string.voice_diag_clear))
        assertTrue(app.getString(R.string.voice_diag_clear_confirm) in s.texts(), "the question")
        assertEquals(1, j.entries().size, "nothing is cleared before the answer")
        s.click(text = app.getString(android.R.string.cancel))
        assertEquals(1, j.entries().size)
        s.click(text = app.getString(R.string.voice_diag_clear))
        s.click(tag = "confirm_clear")
        s.idleFor(300)
        assertEquals(0, j.entries().size)
    }

    /** the short way: the "Journal" link under an error, and a long press on the error text */
    @Test fun errorOnThePanelLinksToTheJournal() {
        var opened = 0
        Settings.getInstance().loadSettings(app)
        val p = VoiceInputView(app, helium314.keyboard.latin.settings.Settings.getValues().mColors, object : VoiceInputView.Actions {
            override fun onMic() = Unit
            override fun onDone() = Unit
            override fun onCancel() = Unit
            override fun onOpenJournal() { opened++ }
        })
        p.update(VoiceState.LISTENING, "", null)
        assertEquals(null, p.journalLink(), "no link while recording")
        p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH)
        assertEquals(null, p.journalLink(), "no link without the developer mode")
        p.statusView().performLongClick()
        assertEquals(0, opened, "nor the long press")
        p.setJournalAllowed(true)
        val link = p.journalLink()!!
        assertEquals(app.getString(R.string.voice_journal_link), link.text.toString())
        link.performClick()
        assertEquals(1, opened)
        p.statusView().performLongClick()
        assertEquals(2, opened)
        p.update(VoiceState.PROCESSING, "", null)
        assertEquals(null, p.journalLink())
    }
}
