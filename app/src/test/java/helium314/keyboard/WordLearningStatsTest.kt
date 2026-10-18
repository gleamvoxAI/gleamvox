// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.WordLearning
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** ux-3: counts (no words) that the owner reads on the AI dictionary screen */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WordLearningStatsTest {
    @Test fun countsAutomaticTapAndSkippedWords() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("ux3test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals("Свои слова: 0 (само 0, нажатием 0); не запомнено как опечатка 0, как обычное слово с большой буквы 0",
            WordLearning.summary(prefs, 0))
        WordLearning.count(prefs, WordLearning.Stat.AUTO)
        WordLearning.count(prefs, WordLearning.Stat.AUTO)
        WordLearning.count(prefs, WordLearning.Stat.TAP)
        WordLearning.count(prefs, WordLearning.Stat.SKIPPED_TYPO)
        assertEquals("Свои слова: 3 (само 2, нажатием 1); не запомнено как опечатка 1, как обычное слово с большой буквы 0",
            WordLearning.summary(prefs, 3))
    }
}
