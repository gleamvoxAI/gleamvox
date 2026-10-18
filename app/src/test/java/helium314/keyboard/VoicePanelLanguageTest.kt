// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.VoiceError
import helium314.keyboard.latin.voice.VoiceInputView
import helium314.keyboard.latin.voice.VoiceState
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Labels of the voice panel follow the language of the active layout, not the language of the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class VoicePanelLanguageTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val noActions = object : VoiceInputView.Actions {
        override fun onMic() = Unit
        override fun onDone() = Unit
        override fun onCancel() = Unit
    }

    private fun texts(root: View): List<String> =
        (if (root is TextView) listOf(root.text.toString(), root.hint?.toString().orEmpty()) else emptyList()) +
            ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())

    private fun panel(phone: String, layout: Locale, state: VoiceState = VoiceState.LISTENING, error: VoiceError? = null): VoiceInputView {
        Settings.getInstance().loadSettings(latinIME)
        val config = Configuration(latinIME.resources.configuration).apply { setLocale(Locale.forLanguageTag(phone)) }
        val phoneContext = latinIME.createConfigurationContext(config) // the phone language
        return VoiceInputView(phoneContext, Settings.getValues().mColors, noActions).also {
            it.setLabelLocale(layout)
            it.update(state, "", error)
        }
    }

    @Test fun englishLayoutOnRussianPhone() {
        val p = panel("ru", Locale.ENGLISH)
        val t = texts(p)
        assertTrue("Listening · English · system" in t, t.toString())
        assertTrue("Speak, then pause" in t && "Tap the microphone to finish" in t, t.toString())
        assertTrue("Cancel" in t && "Done" !in t && p.micDescription().startsWith("Finish recording"), "${p.micDescription()} $t")
        assertTrue(t.none { it.contains("Слушаю") || it == "Отмена" || it == "Готово" }, t.toString())
    }

    @Test fun russianLayoutOnEnglishPhone() {
        val p = panel("en", Locale.forLanguageTag("ru"))
        val t = texts(p)
        assertTrue("Слушаю · русский · системное" in t, t.toString())
        assertTrue("Говорите, потом пауза" in t && "Коснитесь микрофона, чтобы закончить" in t, t.toString())
        assertTrue("Отмена" in t && "Готово" !in t && p.micDescription().startsWith("Закончить запись"), "${p.micDescription()} $t")
        assertTrue(t.none { it.contains("Listening") || it == "Cancel" || it == "Done" }, t.toString())
    }

    @Test fun layoutWithoutTranslationFallsBackToEnglishNotPhoneLanguage() {
        val p = panel("ru", Locale.GERMAN)
        val t = texts(p)
        assertTrue("Listening · Deutsch · system" in t, t.toString())
        assertTrue("Cancel" in t && p.micDescription().startsWith("Finish recording"))
        assertTrue(t.none { it == "Отмена" || it == "Готово" }, t.toString())
    }

    @Test fun errorTextsFollowLayout() {
        assertTrue("Микрофон недоступен" in texts(panel("en", Locale.forLanguageTag("ru"), VoiceState.ERROR, VoiceError.AUDIO)))
        assertTrue("The microphone is not available" in texts(panel("ru", Locale.ENGLISH, VoiceState.ERROR, VoiceError.AUDIO)))
        assertTrue("Press the microphone to try again" in texts(panel("ru", Locale.ENGLISH, VoiceState.ERROR, VoiceError.AUDIO)))
    }

    @Test fun labelsChangeWhileOpen() {
        val p = panel("ru", Locale.ENGLISH)
        p.setLabelLocale(Locale.forLanguageTag("ru"))
        val t = texts(p)
        assertTrue("Слушаю · русский · системное" in t && "Отмена" in t, t.toString())
        assertEquals(false, "Cancel" in t)
    }
}

/**
 * Build 4351, the owner: no "Done" button. One "Cancel" in the middle, a grey "Tap the microphone to finish" under the
 * circle, and a tap on the circle finishes and sends (stage 5g, step 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class VoiceDoneButtonTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @Test fun noDoneButtonTheCircleFinishes() {
        Settings.getInstance().loadSettings(latinIME)
        var done = 0; var mic = 0; var cancel = 0
        val p = VoiceInputView(latinIME, Settings.getValues().mColors, object : VoiceInputView.Actions {
            override fun onMic() { mic++ }
            override fun onDone() { done++ }
            override fun onCancel() { cancel++ }
        })
        p.setLabelLocale(Locale.forLanguageTag("ru"))
        p.update(VoiceState.LISTENING, "", null)
        val t = texts(p)
        assertTrue("Готово" !in t && "Done" !in t, t.toString())
        assertEquals("Коснитесь микрофона, чтобы закончить", p.finishHint()?.text?.toString())
        assertTrue(p.keepScreenOn, "stage 5zh: the screen stays on while recording")
        assertTrue(p.micDescription().startsWith("Закончить запись"))
        assertTrue(p.micCircle().performClick())
        assertEquals(1, done, "the circle finishes and sends")
        p.update(VoiceState.PROCESSING, "", null)
        assertEquals(null, p.finishHint(), "nothing to finish while the phrase is sent")
        p.micCircle().performClick()
        assertEquals(1, done)
        assertFalse(p.keepScreenOn, "released as soon as the recording ends")
        p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH)
        assertEquals(null, p.finishHint())
        p.micCircle().performClick()
        assertEquals(1, mic, "after an error the circle is \"Retry\"")
        assertFalse(p.keepScreenOn)
        assertEquals(0, cancel)
    }

    private fun texts(root: View): List<String> =
        (if (root is TextView) listOf(root.text.toString()) else emptyList()) +
            ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())
}
