// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ailm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ailm.AiDictionary
import helium314.keyboard.latin.ailm.WordModel
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ai-dict-3 (4411 on the phone: 4 of 5 answers late): old requests are dropped, the context is computed ahead, every answer is timed */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiDictLatencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val runs = AtomicInteger()
    private val prepared = AtomicInteger()
    private val late = AtomicInteger()

    /** a slow phone: every scoring takes 1 s */
    private val slow = object : WordModel {
        override fun wordLogProbs(context: String, words: List<String>): DoubleArray {
            runs.incrementAndGet(); Thread.sleep(1000)
            return DoubleArray(words.size) { -10.0 - it }
        }
        override fun nextWords(context: String, count: Int) = emptyList<Pair<String, Double>>()
        override fun prepare(context: String) { prepared.incrementAndGet() }
    }

    @Before fun setUp() {
        AiDictionary.appContext = context
        AiDictionary.onLateResult = { late.incrementAndGet() }
        AiDictionary.Journal.reset(context.prefs())
        AiDictionary.installForTest(slow)
        AiDictionary.onTyped()
    }

    @After fun tearDown() { AiDictionary.installForTest(null); AiDictionary.onLateResult = null }

    private fun rerank(word: String) = AiDictionary.rerank(word, listOf(word + "a", word + "b"), false)

    @Test fun olderRequestsOfTheWordAreDroppedAndTheNewestComesLate() {
        AiDictionary.textBefore = "Hi "
        for (w in listOf("pr", "pri", "priv", "prive")) rerank(w) // each waits the budget (150 ms), the model needs 1 s
        assertTrue(AiDictionary.Journal.dropped >= 2, "the requests in between are dropped: ${AiDictionary.Journal.dropped}")
        Thread.sleep(2300)
        assertEquals(2, runs.get(), "only the first (already running) and the newest are counted")
        assertTrue(late.get() >= 1, "the late answer of the newest word updates the strip")
        assertNotNull(rerank("prive"), "and is used from memory at the next look")
    }

    @Test fun everyAnswerIsTimedAlsoTheLateOnes() {
        AiDictionary.textBefore = "Ok "
        rerank("he"); Thread.sleep(1200)
        rerank("hel"); Thread.sleep(1200)
        rerank("help"); Thread.sleep(1200)
        val j = AiDictionary.Journal
        assertTrue(j.coldMs >= 900, "the first answer after loading: ${j.coldMs}")
        val w = assertNotNull(j.warmStats())
        assertTrue(w.first >= 900 && w.third >= w.second && w.second >= w.first, "median, 90 %, maximum: $w")
        val s = j.summary()
        assertTrue("медиана" in s && "90%" in s && "первый ответ после загрузки" in s && "поздно 3" in s, s)
    }

    @Test fun theContextIsComputedAheadOncePerWord() {
        AiDictionary.textBefore = "Как дела "
        AiDictionary.textBefore = "Как дела " // the next letters of the same word: the same context
        Thread.sleep(200)
        assertEquals(1, prepared.get())
        AiDictionary.textBefore = "Как дела у "
        Thread.sleep(200)
        assertEquals(2, prepared.get())
    }

    @Test fun theJournalIsKeptAcrossRestarts() {
        AiDictionary.textBefore = "x "
        rerank("ab"); Thread.sleep(1200); rerank("abc"); Thread.sleep(1200)
        val before = AiDictionary.Journal.summary()
        AiDictionary.Journal.save(context.prefs())
        AiDictionary.Journal.load(context.prefs())
        assertEquals(before, AiDictionary.Journal.summary())
    }
}
