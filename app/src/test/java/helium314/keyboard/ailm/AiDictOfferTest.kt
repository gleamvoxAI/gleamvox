// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ailm

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ailm.AiDictDownloadService
import helium314.keyboard.latin.ailm.AiDictOffer
import helium314.keyboard.latin.ailm.AiDictionary
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.features.WizardSteps
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ai-dict-2: where the AI dictionary is offered: the conditions of the offer in the strip and in the wizard, the notification */
@RunWith(RobolectricTestRunner::class)
class AiDictOfferTest {
    private val ok = AiDictOffer.StripState(inBuild = true, downloaded = false, unanswered = true, internetAllowed = true,
        stripShownBefore = false, downloading = false, daysUsed = 3, words = 300, wifi = true, fieldHidden = false)

    @Test fun stripOfferWhenAllConditionsHold() {
        assertTrue(AiDictOffer.showInStrip(ok))
    }

    @Test fun stripOfferNeedsRealUse() {
        assertFalse(AiDictOffer.showInStrip(ok.copy(daysUsed = 2)))   // 3 days
        assertFalse(AiDictOffer.showInStrip(ok.copy(words = 299)))    // 300 words
        assertTrue(AiDictOffer.showInStrip(ok.copy(daysUsed = 40, words = 5000)))
    }

    @Test fun stripOfferOnlyOnWifiAndOutsideSensitiveFields() {
        assertFalse(AiDictOffer.showInStrip(ok.copy(wifi = false)))
        assertFalse(AiDictOffer.showInStrip(ok.copy(fieldHidden = true))) // password, e-mail, number, "do not remember", incognito
    }

    @Test fun stripOfferOnlyOnceAndNeverAfterAnAnswer() {
        assertFalse(AiDictOffer.showInStrip(ok.copy(stripShownBefore = true)))
        assertFalse(AiDictOffer.showInStrip(ok.copy(unanswered = false))) // "Not now" or "Download" anywhere
    }

    @Test fun nothingToOfferWhenNotPossible() {
        assertFalse(AiDictOffer.showInStrip(ok.copy(inBuild = false)))        // the store version has no model
        assertFalse(AiDictOffer.showInStrip(ok.copy(downloaded = true)))
        assertFalse(AiDictOffer.showInStrip(ok.copy(downloading = true)))
        assertFalse(AiDictOffer.showInStrip(ok.copy(internetAllowed = false))) // it could not be downloaded
    }

    @Test fun wizardStepOnlyWhenItCanBeAnswered() {
        assertTrue(AiDictOffer.wizardStep(inBuild = true, downloaded = false, unanswered = true, internetAllowed = true))
        assertFalse(AiDictOffer.wizardStep(inBuild = false, downloaded = false, unanswered = true, internetAllowed = true))
        assertFalse(AiDictOffer.wizardStep(inBuild = true, downloaded = true, unanswered = true, internetAllowed = true))
        assertFalse(AiDictOffer.wizardStep(inBuild = true, downloaded = false, unanswered = false, internetAllowed = true))
        assertFalse(AiDictOffer.wizardStep(inBuild = true, downloaded = false, unanswered = true, internetAllowed = false))
        // the step comes after the features and before the end; a finished wizard never gets it
        assertEquals(WizardSteps.AI_DICT, WizardSteps.determine(enabled = true, current = true, featuresSeen = true, aiDictOffer = true))
        assertEquals(WizardSteps.DONE, WizardSteps.determine(enabled = true, current = true, featuresSeen = true, aiDictOffer = false))
        assertEquals(WizardSteps.FEATURES, WizardSteps.determine(enabled = true, current = true, featuresSeen = false, aiDictOffer = true))
        assertEquals(WizardSteps.AI_DICT, WizardSteps.afterFeatures(true))
        assertEquals(WizardSteps.DONE, WizardSteps.afterFeatures(false))
    }

    @Test fun notNowAnywhereMeansNeverAgain() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.prefs()
        prefs.edit { remove(AiDictionary.PREF_OFFER) }
        assertTrue(AiDictOffer.unanswered(prefs))
        AiDictOffer.decline(prefs) // from the wizard ("Later"), the strip ("×", "Not now") or the dialog
        assertFalse(AiDictOffer.unanswered(prefs))
        assertFalse(AiDictOffer.showInStrip(ok.copy(unanswered = AiDictOffer.unanswered(prefs))))
        assertFalse(AiDictOffer.wizardStep(true, false, AiDictOffer.unanswered(prefs), true))
        prefs.edit { remove(AiDictionary.PREF_OFFER) }
    }

    @Test fun usageCountsWordsAndDaysWithoutText() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.prefs()
        prefs.edit { remove(AiDictOffer.PREF_WORDS); remove(AiDictOffer.PREF_FIRST_DAY) }
        assertEquals(0L, AiDictOffer.daysUsed(prefs, 20000))
        // outside the personal variant nothing is counted
        if (!AiDictionary.inBuild()) { AiDictOffer.onWord(ctx, 20000); assertEquals(0, AiDictOffer.words(prefs)); return }
        repeat(25) { AiDictOffer.onWord(ctx, 20000) }
        assertTrue(AiDictOffer.words(prefs) in 20..25) // saved every 10 words
        assertEquals(0L, AiDictOffer.daysUsed(prefs, 20000))
        assertEquals(3L, AiDictOffer.daysUsed(prefs, 20003))
        assertEquals(setOf(AiDictOffer.PREF_WORDS, AiDictOffer.PREF_FIRST_DAY),
            prefs.all.keys.filter { it.startsWith("aikb_ai_dict_words") || it.startsWith("aikb_ai_dict_first") }.toSet()) // numbers only
    }

    @Test fun sessionAndFirstKey() {
        AiDictOffer.onStartInput()
        assertFalse(AiDictOffer.keyPressed)
        AiDictOffer.onKeyPressed()
        assertTrue(AiDictOffer.keyPressed)   // the strip offer is gone for this field
        AiDictOffer.onStartInput()
        assertFalse(AiDictOffer.keyPressed)  // a new field: the offer would have been used up already
    }

    @Test fun notificationShowsProgressStopAndContinue() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val n = AiDictDownloadService.progress(ctx, 150_000_000, 490_000_000)
        assertEquals("150 of 490 MB", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals("AI dictionary", n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals(1, n.actions.size)
        assertEquals("Stop", n.actions[0].title.toString())
        assertEquals(306, n.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val done = AiDictDownloadService.result(ctx, ok = true, stopped = false)
        assertEquals("AI dictionary is ready", done.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(done.actions == null || done.actions.isEmpty())
        val broken = AiDictDownloadService.result(ctx, ok = false, stopped = false)
        assertEquals("Download interrupted — continue", broken.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals("Continue", broken.actions[0].title.toString())
    }
}
