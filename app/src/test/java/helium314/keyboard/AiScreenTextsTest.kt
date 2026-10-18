// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.compose.foundation.layout.Column
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiModel
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.settings.screens.AiAdvancedContent
import helium314.keyboard.settings.screens.AiAdvancedSection
import helium314.keyboard.settings.screens.AssistantSection
import helium314.keyboard.settings.screens.VoiceSection
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 5zh, items 1 and 2: the ordinary AI screen shows no model names, no prices, no "paid"; dollars, the balance
 * estimate and the models only in Advanced. Checked on the drawn screen, in Russian and English.
 */
@RunWith(RobolectricTestRunner::class)
class AiScreenTextsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = app.prefs()

    @BeforeTest fun setUp() {
        prefs.edit { clear() }
        OpenAiPrefs.inBuild = { true }
        VoiceApiPrefs.inBuild = { true }
        TextModels(prefs).save(listOf("gpt-4.1-nano", "gpt-6-luna").map { AiModel(it, it) })
    }

    @AfterTest fun tearDown() {
        OpenAiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
        VoiceApiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
    }

    private val forbidden = listOf("платно", "paid", "доллар", "dollar", "цена", "price", "$", "gpt-", "luna", "nano", "1000 запросов", "1000 requests")

    private fun screen() = ComposeScreen.show { Column { VoiceSection(); AssistantSection(); helium314.keyboard.settings.screens.AiQualityBlock(); AiAdvancedSection() } }
    /** the "Advanced" screen (stage 5g-4: a screen of its own, not a block that opens) */
    private fun advancedScreen() = ComposeScreen.show { Column { AiAdvancedContent() } }

    private fun assertOrdinary(s: ComposeScreen) {
        val text = s.allText()
        assertTrue(text.isNotBlank())
        for (f in forbidden) assertFalse(text.contains(f, ignoreCase = true), "\"$f\" on the ordinary screen:\n$text")
    }

    @Config(qualifiers = "ru")
    @Test fun russianOrdinaryScreenHasNoPricesAndNoModels() {
        val s = screen()
        assertOrdinary(s)
        val text = s.allText()
        assertTrue("Умное распознавание" in text, text)
        assertTrue("Использовано в этом месяце: 0,0 из 300 минут" in text, text)
        assertTrue("Сегодня: 0 из 100" in text, text) // requests are counted, not dollars
        // Advanced: AI quality, and the dollars only for the own key
        assertFalse("Для разработчика" in s.allText(), "no engineering row without the developer mode")
        val adv = advancedScreen()
        val advanced = adv.allText() + "\n" + s.allText() // the quality is on the AI screen, the rest on "For developer"
        assertTrue("Качество ИИ" in advanced && "Экономно (по умолчанию)" in advanced && "Лучше" in advanced, advanced)
        assertTrue("доллар" in advanced && "Открыть баланс на сайте OpenAI" in advanced, advanced)
        // item 7: read only, which models work; the list with prices only after "Details"
        assertTrue("Распознавание речи: gpt-transcribe (OpenAI)" in advanced, advanced)
        assertTrue("Текст (✨, очистка): gpt-6-luna, выбрана автоматически" in advanced, advanced)
        assertFalse("≈" in advanced || "gpt-4.1-nano" in advanced, "the candidates with prices only after \"Details\"")
        adv.click(text = "Подробности")
        assertTrue("text model now: gpt-6-luna (automatic)" in adv.allText(), adv.allText())
    }

    @Config(qualifiers = "ru")
    @Test fun aboutWithoutModelListSaysAutomatic() {
        prefs.edit { clear() }
        val s = advancedScreen()
        assertTrue("Текст (✨, очистка): модель выбирается автоматически после проверки ключа" in s.allText())
    }

    @Test fun englishOrdinaryScreenHasNoPricesAndNoModels() {
        val s = screen()
        assertOrdinary(s)
        assertTrue("Smart recognition" in s.allText())
        assertTrue("AI quality" in s.allText(), "the quality is for everybody")
        val adv = advancedScreen()
        assertTrue("dollars" in adv.allText() && "Voice diagnostics" in adv.allText())
    }

    @Config(qualifiers = "ru")
    @Test fun consentTextsNameOneNeutralPayment() {
        for (res in listOf(R.string.voice_api_consent_title, R.string.ai_text_consent_title, R.string.voice_api_engine_openai,
                R.string.ai_invite_text, R.string.feature_ai_text, R.string.ai_screen_intro, R.string.voice_api_summary))
            for (f in listOf("платно", "доллар", "цент", "0,0045")) assertFalse(f in app.getString(res), app.getString(res))
        assertEquals("Умное распознавание", app.getString(R.string.voice_api_engine_openai))
        assertTrue("по своему тарифу" in app.getString(R.string.voice_api_consent_text))
        assertTrue("по своему тарифу" in app.getString(R.string.ai_text_consent_text))
    }
}

/** Stage 5zh, item 3: "Само" became "Авто" (English Auto), and it comes first as the recommended choice */
@RunWith(RobolectricTestRunner::class)
class AutoNotSamoTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @AfterTest fun tearDown() { VoiceApiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI } }

    /** every string of ours in every language file: no "Само" left */
    @Test fun noSamoInAnyLanguage() {
        val res = java.io.File("src/main/res").takeIf { it.isDirectory } ?: java.io.File("app/src/main/res")
        val ours = Regex("<string name=\"(voice_|ai_|openai_|feature_|features_|mic_desc_)[^\"]*\">([^<]*)</string>")
        val found = res.listFiles()!!.filter { it.name.startsWith("values") }.mapNotNull { java.io.File(it, "strings.xml").takeIf { f -> f.exists() } }
            .flatMap { f -> ours.findAll(f.readText()).filter { "Само" in it.groupValues[2] }.map { "${f.parentFile.name}: ${it.value}" }.toList() }
        assertEquals(emptyList(), found)
    }

    @Config(qualifiers = "ru")
    @Test fun russianAutoRecommendedFirst() {
        assertTrue(app.getString(R.string.voice_api_engine_auto).startsWith("Авто (рекомендуется)"))
        assertTrue("«Авто»" in app.getString(R.string.voice_api_problem_mode_off) && "«Авто»" in app.getString(R.string.voice_api_check_mode_off))
        VoiceApiPrefs.inBuild = { true }
        val s = ComposeScreen.show { VoiceSection() }
        val t = s.texts()
        val auto = t.indexOfFirst { it.startsWith("Авто (рекомендуется)") }
        assertTrue(auto >= 0 && auto < t.indexOf("Умное распознавание") && auto < t.indexOf("Системное (Android)"), t.toString())
    }

    @Test fun englishAuto() {
        assertTrue(app.getString(R.string.voice_api_engine_auto).startsWith("Auto (recommended)"))
    }
}
