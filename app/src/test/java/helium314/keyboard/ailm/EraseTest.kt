// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ailm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ailm.AiDictionary
import helium314.keyboard.latin.ailm.EraseStats
import helium314.keyboard.latin.ailm.WordModel
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ux-4: while Backspace is pressed the keyboard never waits for the model (the strip update is waited for by the key) */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EraseTest {
    private val calls = AtomicInteger()
    private val lateAnswers = AtomicInteger()

    /** a slow phone: every answer takes 400 ms */
    private val slow = object : WordModel {
        override fun wordLogProbs(context: String, words: List<String>): DoubleArray {
            calls.incrementAndGet(); Thread.sleep(400)
            return DoubleArray(words.size) { -10.0 - it }
        }
        override fun nextWords(context: String, count: Int) = emptyList<Pair<String, Double>>()
    }

    @Before fun setUp() {
        AiDictionary.appContext = ApplicationProvider.getApplicationContext<Context>()
        AiDictionary.onLateResult = { lateAnswers.incrementAndGet() }
        AiDictionary.textBefore = "Hello"
        AiDictionary.installForTest(slow)
    }

    @After fun tearDown() { AiDictionary.installForTest(null); AiDictionary.onLateResult = null }

    private fun rerank(word: String) = AiDictionary.rerank(word, listOf(word + "a", word + "b"), false)

    private fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun typingWaitsForTheBudgetButErasingNeverWaits() {
        AiDictionary.onTyped()
        val typing = ms { assertNull(rerank("tyx")) }                 // the slow model: the key waits the whole budget
        assertTrue(typing >= AiDictionary.BUDGET_MS - 20, "typing waited $typing ms")
        AiDictionary.onErase(false)
        val erasing = ms { assertNull(rerank("eraz")) }
        assertTrue(erasing < 60, "erasing waited $erasing ms")         // no waiting at all
    }

    @Test fun oneRequestAfterThePauseAndItsAnswerIsShownLater() {
        AiDictionary.onErase(false)
        // five presses within the pause: the model is started once, only for the last word
        for (w in listOf("abcdef", "abcde", "abcd", "abc", "abz")) { assertNull(rerank(w)); Thread.sleep(30) }
        assertEquals(0, calls.get(), "no model run before the pause is over")
        Thread.sleep(AiDictionary.ERASE_PAUSE_MS + 700)
        assertEquals(1, calls.get())
        assertTrue(lateAnswers.get() >= 1, "the strip is asked to show the answer")
        assertNotNull(rerank("abz"))                                   // the answer is used at once from memory
    }

    @Test fun nothingRunsWhileTheKeyIsHeld() {
        for (i in 1..10) { AiDictionary.onErase(true); assertNull(rerank("held$i")); Thread.sleep(40) }
        assertEquals(0, calls.get())
        Thread.sleep(AiDictionary.ERASE_PAUSE_MS + 100)                 // released
        assertTrue(!AiDictionary.isHeld())
        assertTrue(lateAnswers.get() >= 1, "after the hold the strip is updated once")
        assertEquals(0, calls.get())                                    // the model itself starts with that update
    }

    @Test fun anotherKeyCancelsThePausedRequest() {
        AiDictionary.onErase(false)
        assertNull(rerank("cancel"))
        AiDictionary.onTyped()
        Thread.sleep(AiDictionary.ERASE_PAUSE_MS + 100)
        assertEquals(0, calls.get())
    }

    @Test fun statsShowMedianNinetiethAndMaximum() {
        val s = EraseStats.Series()
        assertEquals("нет данных", s.summary())
        for (i in 1..10) s.add(i * 1_000_000L)
        assertEquals("медиана 6.0, 90 % 10.0, максимум 10.0 мс (10 шт.)", s.summary())
    }
}
