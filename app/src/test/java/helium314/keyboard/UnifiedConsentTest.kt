// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ai.AiConsent
import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.settings.features.ConfirmConsentCard
import helium314.keyboard.settings.features.EnableAiPage
import helium314.keyboard.settings.features.UnifiedConsentDialog
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** stage 5zh, item 8: one consent for voice, ✨ and the cleanup; the wizard "Turn on AI"; the card for old installations */
@RunWith(RobolectricTestRunner::class)
class UnifiedConsentTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = app.prefs()
    private val day = 24 * 3600_000L

    /** the switch itself (AikbNetwork.internetAllowed is also false in this build without the AI part, on purpose) */
    private fun internetSwitch() = prefs.getBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, false)

    @BeforeTest fun setUp() {
        prefs.edit { clear() }
        OpenAiPrefs.inBuild = { true }
        VoiceApiPrefs.inBuild = { true }
    }

    @AfterTest fun tearDown() {
        OpenAiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
        VoiceApiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
    }

    @Test fun oneConsentTurnsOnVoiceSparkleAndCleanupWithDefaults() {
        assertFalse(AiConsent.hasAll(prefs))
        AiConsent.giveAll(prefs, internet = true)
        assertTrue(VoiceApiPrefs.consent(prefs) && AiPrefs.textConsent(prefs) && AiConsent.hasAll(prefs))
        assertEquals(SpeechEngineSetting.AUTO, VoiceApiPrefs.engine(prefs), "speech recognition: Auto")
        assertTrue(internetSwitch())
        assertEquals(helium314.keyboard.latin.ai.CleanupLevel.FULL, helium314.keyboard.latin.ai.CleanupLevel.read(prefs))
        assertEquals(helium314.keyboard.latin.ai.LongPressCommand.FIX, AiPrefs.longPressCommand(prefs))
        // the parts can still be withdrawn one by one
        AiPrefs.withdrawTextConsent(prefs)
        assertTrue(VoiceApiPrefs.consent(prefs) && !AiPrefs.textConsent(prefs))
    }

    @Test fun anEarlierVoiceChoiceIsKept() {
        VoiceApiPrefs.giveConsent(prefs)
        VoiceApiPrefs.setEngine(prefs, SpeechEngineSetting.OPENAI)
        AiConsent.giveAll(prefs)
        assertEquals(SpeechEngineSetting.OPENAI, VoiceApiPrefs.engine(prefs))
        assertFalse(internetSwitch(), "the card only confirms the consent, the switch stays as it was")
    }

    @Test fun withoutTheKeyTheIconsStayDimmedAndLeadToTheKey() {
        AiConsent.giveAll(prefs, internet = true)
        // AiAssist.isReady needs the key too: no key, no gold
        assertFalse(helium314.keyboard.latin.ai.AiAssist.isReady(app))
        assertEquals(AiGuard.Button.OPENS_SETTINGS, AiGuard.button(true, false, internetOn = true, ready = false))
        assertEquals(helium314.keyboard.latin.ai.AiError.NO_KEY, AiGuard.notReadyReason(true, false, false, true))
    }

    @Test fun cardOnlyForInstallationsThatUseAiWithoutTheTextConsent() {
        val now = 1_000_000_000L
        assertFalse(AiConsent.needsConfirmation(prefs, true, hasKey = false, now = now), "a new user gets the wizard, not the card")
        assertTrue(AiConsent.needsConfirmation(prefs, true, hasKey = true, now = now), "after the update of 5d: key, no text consent")
        assertFalse(AiConsent.needsConfirmation(prefs, false, hasKey = true, now = now), "never in the release variant")
        AiConsent.snooze(prefs, now)
        assertFalse(AiConsent.needsConfirmation(prefs, true, true, now + 7 * day - 1))
        assertTrue(AiConsent.needsConfirmation(prefs, true, true, now + 7 * day))
        AiConsent.giveAll(prefs)
        assertFalse(AiConsent.needsConfirmation(prefs, true, true, now + 8 * day), "gone after the consent")
        assertFalse(prefs.contains(AiConsent.PREF_CONFIRM_SNOOZED_UNTIL))
    }

    @Test fun cardOpensTheSingleConsentAndOnlyAcceptChangesSomething() {
        val s = ComposeScreen.show {
            var dialog by remember { mutableStateOf(false) }
            ConfirmConsentCard(onConfirm = { dialog = true }, onLater = { })
            if (dialog) UnifiedConsentDialog(onDismiss = { dialog = false }, onAccept = { AiConsent.giveAll(prefs); dialog = false })
        }
        assertTrue("Confirm the consent for ✨" in s.allText(), s.allText())
        s.click(text = "Confirm")
        assertTrue("Turn on AI: voice, ✨ and cleanup" in s.allText(), s.allText())
        assertFalse(AiConsent.hasAll(prefs), "showing the text changes nothing")
        s.click(tag = "consent_accept")
        assertTrue(AiConsent.hasAll(prefs))
    }

    @Config(qualifiers = "ru")
    @Test fun wizardOneButtonGivesConsentInternetAndAsksForTheMicrophone() {
        val s = ComposeScreen.show { EnableAiPage { } }
        val t = s.allText()
        assertTrue("Включить ИИ" in t && "по своему тарифу" in t && "Включить" in s.texts(), t)
        assertFalse(AiConsent.hasAll(prefs) || internetSwitch(), "nothing before the press")
        s.click(text = "Также имена из контактов для подсказок (остаются на телефоне, в OpenAI не уходят)")
        s.click(tag = "enable_ai")
        assertTrue(AiConsent.hasAll(prefs) && internetSwitch())
        val asked = shadowOf(s.activity).lastRequestedPermission
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS), asked.requestedPermissions.toSet())
    }

    @Config(qualifiers = "ru")
    @Test fun wizardNotNowChangesNothing() {
        var done = false
        val s = ComposeScreen.show { EnableAiPage { done = true } }
        s.click(text = "Не сейчас")
        assertTrue(done)
        assertFalse(AiConsent.hasAll(prefs) || internetSwitch())
        assertEquals(null, shadowOf(s.activity).lastRequestedPermission)
    }

    @Test fun deleteAllForgetsTheCardToo() {
        AiConsent.snooze(prefs)
        assertTrue(AiConsent.PREF_CONFIRM_SNOOZED_UNTIL in OpenAiPrefs.ALL_KEYS)
    }
}

/** stage 5zh, item 9: the AI screen opened by the dimmed ✨ shows at once what is missing */
@RunWith(RobolectricTestRunner::class)
class AiScreenTargetTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @BeforeTest fun setUp() { app.prefs().edit { clear() }; OpenAiPrefs.inBuild = { true }; VoiceApiPrefs.inBuild = { true } }

    @AfterTest fun tearDown() {
        OpenAiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
        VoiceApiPrefs.inBuild = { helium314.keyboard.latin.BuildConfig.AIKB_OPENAI }
    }

    @Test fun onlyTheConsentMissingOpensTheConsentWindow() {
        helium314.keyboard.settings.screens.AiScreenTarget.set("consent")
        val s = ComposeScreen.show { helium314.keyboard.settings.screens.AiScreen { } }
        assertTrue("Turn on AI: voice, ✨ and cleanup" in s.allText(), s.allText())
        s.click(tag = "consent_accept")
        assertTrue(AiConsent.hasAll(app.prefs()))
    }

    @Test fun missingKeyAndInternetAreNamed() {
        helium314.keyboard.settings.screens.AiScreenTarget.set("key")
        assertTrue("✨ is grey: paste your OpenAI API key below" in ComposeScreen.show { helium314.keyboard.settings.screens.AiScreen { } }.allText())
        helium314.keyboard.settings.screens.AiScreenTarget.set("internet")
        assertTrue("✨ is grey: switch on internet access below" in ComposeScreen.show { helium314.keyboard.settings.screens.AiScreen { } }.allText())
        // opened in the ordinary way: no hint, no dialog
        val plain = ComposeScreen.show { helium314.keyboard.settings.screens.AiScreen { } }.allText()
        assertFalse("✨ is grey" in plain || "Turn on AI" in plain, plain)
    }
}
