// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.CleanupLevel
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.ChatGptRemoval
import helium314.keyboard.latin.openai.OpenAiProxy
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.VoiceApiBalance
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.features.AiInviteCard
import helium314.keyboard.settings.features.AikbFeature
import helium314.keyboard.settings.features.AikbFeatures
import helium314.keyboard.settings.features.FeatureCards
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5d migration: the old ChatGPT sign-in is erased once, everything of the API key and voice stays. */
@RunWith(RobolectricTestRunner::class)
class ChatGptRemovalTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = app.prefs()

    @BeforeTest fun clean() { prefs.edit { clear() } }

    @Test fun oldSignInIsErasedAndTheRestStays() {
        val dir = Files.createTempDirectory("aikb-old").toFile()
        val old = File(dir, ChatGptRemoval.OLD_DIR).apply { mkdirs() }
        File(old, "credentials.bin").writeBytes(ByteArray(300) { 7 })
        File(old, "registration.json").writeText("""{"hostId":"urn:uuid:x"}""")
        File(old, "key-protection").writeText("plain")
        prefs.edit {
            ChatGptRemoval.OLD_PREFS.forEach { putString(it, "old") }
            putStringSet(AikbFeatures.PREF_SEEN, setOf("contacts", "voice", "chatgpt"))
            // what stays
            putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true)
            putBoolean(VoiceApiPrefs.PREF_CONSENT, true)
            putString(VoiceApiPrefs.PREF_ENGINE, "auto")
            putInt(VoiceApiPrefs.PREF_END_PAUSE_MS, 2000)
            putInt(AiUsage.PREF_CAP, 40)
            putInt(VoiceApiUsage.PREF_CAP_MINUTES, 120)
            putString(OpenAiProxy.PREF_TYPE, "socks5")
            putString(AiPrefs.PREF_TRANSLATE_TO, "de")
            putStringSet(AiPrefs.PREF_EXCLUDED_APPS, setOf("com.bank.app"))
        }
        var keystoreDeleted = 0
        assertTrue(ChatGptRemoval.run(prefs, old) { keystoreDeleted++ })
        assertFalse(old.exists(), "tokens, registration and key mode are gone")
        assertEquals(1, keystoreDeleted, "the Keystore key of the subscription is deleted")
        ChatGptRemoval.OLD_PREFS.forEach { assertFalse(prefs.contains(it), it) }
        assertEquals(setOf("contacts", "voice"), prefs.getStringSet(AikbFeatures.PREF_SEEN, null))
        assertEquals(listOf(AikbFeature.AI, AikbFeature.SCREENSHOT), AikbFeatures.unseen(prefs, true), "the new AI card (and the screenshot card) is shown once")
        assertTrue(prefs.getBoolean(ChatGptRemoval.PREF_DONE, false))
        // kept
        assertTrue(prefs.getBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, false))
        assertTrue(VoiceApiPrefs.consent(prefs))
        assertEquals("auto", prefs.getString(VoiceApiPrefs.PREF_ENGINE, null))
        assertEquals(2000, VoiceApiPrefs.endPauseMs(prefs))
        assertEquals(40, AiUsage(prefs).cap())
        assertEquals(120, prefs.getInt(VoiceApiUsage.PREF_CAP_MINUTES, 0))
        assertEquals(OpenAiProxy.Type.SOCKS5, OpenAiProxy.read(prefs).type)
        assertEquals("de", prefs.getString(AiPrefs.PREF_TRANSLATE_TO, null))
        assertEquals(setOf("com.bank.app"), AiPrefs.excludedApps(prefs))
        // the consent for text requests is new: asked again
        assertFalse(AiPrefs.textConsent(prefs))
        // the old pause of the plan is not taken for a pause of the key
        assertEquals(null, AiUsage(prefs).pausedError())
    }

    @Test fun onlyOnceAndFineOnAFreshInstall() {
        val dir = Files.createTempDirectory("aikb-new").toFile()
        var deleted = 0
        assertTrue(ChatGptRemoval.run(prefs, File(dir, ChatGptRemoval.OLD_DIR)) { deleted++ })
        assertTrue(prefs.getBoolean(ChatGptRemoval.PREF_DONE, false))
        prefs.edit { putBoolean(VoiceApiPrefs.PREF_CONSENT, true) }
        ChatGptRemoval.runOnce(app) // done already: nothing changes
        assertTrue(VoiceApiPrefs.consent(prefs))
        assertEquals(1, deleted)
    }

    @Test fun balanceEstimateStays() {
        VoiceApiBalance(prefs).topUp(5.0)
        ChatGptRemoval.run(prefs, File(Files.createTempDirectory("aikb-b").toFile(), "chatgpt")) { }
        assertEquals(5.0, VoiceApiBalance(prefs).remainingDollars()!!, 0.001)
    }
}

/** stage 5d: no "ChatGPT" in the interface; "AI (OpenAI)" instead; every AI text in English and Russian */
@RunWith(RobolectricTestRunner::class)
class AiTextsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun strings(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        .getElementsByTagName("string").let { l -> (0 until l.length).map { l.item(it) as Element } }
        .associate { it.getAttribute("name") to it.textContent }

    private fun ctx(lang: String) = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) })

    @Test fun noChatGptInAnyText() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml")) {
            val s = strings(path)
            assertEquals(emptyList(), s.filter { (n, t) -> "ChatGPT" in t || n.startsWith("chatgpt_") }.keys.toList(), path)
            assertFalse(s.values.any { "Manage usage" in it }, path)
        }
    }

    @Test fun aiTextsInBothLanguages() {
        fun ours(path: String) = strings(path).keys.filter { it.startsWith("ai_") || it.startsWith("openai_") || it.startsWith("feature_ai") }.toSet()
        val en = ours("src/main/res/values/strings.xml")
        assertTrue(en.size > 60, "${en.size}")
        assertEquals(en, ours("src/main/res/values-ru/strings.xml"))
    }

    @Test fun namesInTheInterface() {
        val ru = ctx("ru")
        assertEquals("ИИ (OpenAI)", ru.getString(R.string.ai_title))
        assertEquals("Подключите ИИ: вставьте ключ API OpenAI", ru.getString(R.string.ai_invite_title))
        assertEquals("ИИ-функции (ключ API OpenAI)", ru.getString(R.string.feature_ai_title))
        assertEquals("ИИ (OpenAI), ваш ключ API", ru.getString(R.string.ai_panel_source))
        assertEquals("Очистка: 3 сегодня", ru.getString(R.string.ai_cleanup_count, 3))
        assertEquals("↶ Вернуть как было", ru.getString(R.string.ai_cleanup_undo))
        assertEquals("Клавиатура ничего никуда не отправляет.", ru.getString(helium314.keyboard.settings.screens.internetSummaryRes(false)))
        assertEquals("Разрешён только api.openai.com (голос, ✨ и очистка по вашему ключу).", ru.getString(helium314.keyboard.settings.screens.internetSummaryRes(true)))
        val en = ctx("en")
        assertEquals("AI (OpenAI)", en.getString(R.string.ai_title))
        assertEquals("Connect AI: paste an OpenAI API key", en.getString(R.string.ai_invite_title))
        assertEquals("AI functions (OpenAI API key)", en.getString(R.string.feature_ai_title))
        assertEquals("ai", SettingsDestination.Ai)
        assertEquals(CleanupLevel.FULL, CleanupLevel.DEFAULT)
    }

    /** the consent screen says where the text goes and who pays, in both languages */
    @Test fun textConsentSaysWhatIsSentAndWhoPays() {
        for ((lang, words) in listOf("en" to listOf("OpenAI", "API key", "balance", "cleanup"), "ru" to listOf("OpenAI", "ключу API", "баланса", "очистк"))) {
            val c = ctx(lang)
            val t = c.getString(R.string.ai_text_consent_text) + " " + c.getString(R.string.ai_text_consent_what)
            words.forEach { assertTrue(it in t, "$lang: $it") }
        }
    }

    /** the cards really drawn: nothing switches on, nothing is started */
    @Test fun aiCardsDrawWithoutSwitchingAnythingOn() {
        val prefs = app.prefs()
        prefs.edit { clear() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var opened = 0
        activity.setContent {
            helium314.keyboard.latin.utils.Theme {
                androidx.compose.foundation.layout.Column {
                    AiInviteCard(onConnect = { opened++ }, onLater = { })
                    FeatureCards(listOf(AikbFeature.AI), onOpenAi = { opened++ })
                }
            }
        }
        ShadowLooper.idleMainLooper()
        assertEquals(0, opened)
        assertTrue((prefs.all.keys - helium314.keyboard.latin.settings.Settings.PREF_EMOJI_MAX_SDK).isEmpty(), prefs.all.toString()) // the app may write emoji_max_sdk in the background
    }
}
