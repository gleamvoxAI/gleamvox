// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.Manifest
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.ai.AiInvite
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.features.AikbFeature
import helium314.keyboard.settings.features.AikbFeatures
import helium314.keyboard.settings.features.AiInviteCard
import helium314.keyboard.settings.features.FeaturesPage
import helium314.keyboard.settings.features.WizardSteps
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Connect AI" card, the first start wizard and the "AI Keyboard features" screen. */
@RunWith(RobolectricTestRunner::class)
class FeaturesAndInviteTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = app.prefs()
    private val day = 24 * 60 * 60 * 1000L

    @BeforeTest fun clean() { prefs.edit { clear() } } // the preferences object is shared between tests

    // ---- invitation card ----

    @Test fun inviteShownOnlyWithoutKey() {
        assertTrue(AiInvite.shouldShow(true, hasKey = false, snoozedUntil = 0, now = 1000))
        assertFalse(AiInvite.shouldShow(true, hasKey = true, snoozedUntil = 0, now = 1000)) // a key is saved: never
    }

    @Test fun inviteNeverInRelease() {
        assertFalse(AiInvite.shouldShow(false, hasKey = false, snoozedUntil = 0, now = 1000))
        // unit tests use the build without AI functions, like the release variant
        assertFalse(OpenAiPrefs.isAvailable())
    }

    @Test fun laterHidesForSevenDays() {
        val now = 1_000_000_000L
        AiInvite.snooze(prefs, now)
        val until = prefs.getLong(AiInvite.PREF_SNOOZED_UNTIL, 0)
        assertEquals(now + 7 * day, until)
        assertFalse(AiInvite.shouldShow(true, false, until, now))
        assertFalse(AiInvite.shouldShow(true, false, until, now + 7 * day - 1))
        assertTrue(AiInvite.shouldShow(true, false, until, now + 7 * day)) // back after 7 days
        assertFalse(AiInvite.shouldShow(true, true, until, now + 8 * day))
    }

    // ---- wizard ----

    @Test fun wizardOrder() {
        assertEquals(listOf(WizardSteps.WELCOME, WizardSteps.ENABLE, WizardSteps.SELECT, WizardSteps.FEATURES, WizardSteps.AI_DICT, WizardSteps.DONE), WizardSteps.ORDER)
        assertEquals(WizardSteps.ORDER.sorted(), WizardSteps.ORDER)
        assertEquals(WizardSteps.WELCOME, WizardSteps.determine(enabled = false, current = false, featuresSeen = false))
        assertEquals(WizardSteps.SELECT, WizardSteps.determine(enabled = true, current = false, featuresSeen = false))
        // the features come only after the keyboard is enabled and selected
        assertEquals(WizardSteps.FEATURES, WizardSteps.determine(enabled = true, current = true, featuresSeen = false))
        assertEquals(WizardSteps.DONE, WizardSteps.determine(enabled = true, current = true, featuresSeen = true))
        assertEquals(WizardSteps.SELECT, WizardSteps.determine(enabled = true, current = false, featuresSeen = true))
    }

    // ---- features ----

    @Test fun releaseHasNoInternetOrAiCard() {
        assertEquals(listOf(AikbFeature.CONTACTS, AikbFeature.VOICE, AikbFeature.SCREENSHOT), AikbFeatures.available(ai = false))
        assertEquals(listOf(AikbFeature.CONTACTS, AikbFeature.VOICE, AikbFeature.AI, AikbFeature.SCREENSHOT), AikbFeatures.available(ai = true))
        assertEquals(OpenAiPrefs.isAvailable(), AikbFeature.AI in AikbFeatures.available())
        assertEquals(listOf("contacts", "voice", "ai", "screenshot"), AikbFeature.entries.map { it.id }) // the ChatGPT card is gone
    }

    @Test fun newFeatureShownOnce() {
        assertEquals(AikbFeatures.available(true), AikbFeatures.unseen(prefs, true))
        AikbFeatures.markSeen(prefs, listOf(AikbFeature.CONTACTS, AikbFeature.VOICE)) // an older version showed these
        assertEquals(listOf(AikbFeature.AI, AikbFeature.SCREENSHOT), AikbFeatures.unseen(prefs, true)) // new in this version
        AikbFeatures.markSeen(prefs, AikbFeatures.unseen(prefs, true))
        assertTrue(AikbFeatures.unseen(prefs, true).isEmpty())
    }

    @Test fun everythingOffByDefault() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
        AikbFeatures.available(true).forEach { assertFalse(AikbFeatures.isOn(app, prefs, it), it.id) }
        assertFalse(AikbNetwork.internetAllowed(app))
        // the voice switch alone (default of stage 3) is not "on" without the microphone permission
        assertTrue(prefs.getBoolean(Settings.PREF_VOICE_INPUT_INTERNAL, true))
        assertFalse(AikbFeatures.isOn(app, prefs, AikbFeature.VOICE))
    }

    @Test fun markSeenAndSnoozeChangeNothingElse() {
        AikbFeatures.markSeen(prefs, AikbFeatures.available(true))
        AiInvite.snooze(prefs)
        // only our keys: the app itself may write emoji_max_sdk in the background at any moment
        assertEquals(setOf(AikbFeatures.PREF_SEEN, AiInvite.PREF_SNOOZED_UNTIL), prefs.all.keys.filter { it.startsWith("aikb_") }.toSet())
    }

    /** the screens really drawn: showing the cards and the invitation switches nothing on and starts no activity */
    @Test fun showingTheCardsSwitchesNothingOn() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var done = false
        activity.setContent {
            helium314.keyboard.latin.utils.Theme {
                FeaturesPage(AikbFeatures.available(true), "Done") { done = true }
                AiInviteCard(onConnect = { }, onLater = { })
            }
        }
        ShadowLooper.idleMainLooper()
        assertTrue(activity.window.decorView.rootView.isAttachedToWindow)
        assertFalse(done)
        assertTrue((prefs.all.keys - helium314.keyboard.latin.settings.Settings.PREF_EMOJI_MAX_SDK).isEmpty(), prefs.all.toString())
        val started: Intent? = shadowOf(app).nextStartedActivity
        assertEquals(null, started)
    }
}
