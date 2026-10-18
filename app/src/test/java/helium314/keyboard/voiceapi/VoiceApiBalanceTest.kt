// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voiceapi

import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiBalance
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.latin.voiceapi.VoiceEngineChoice
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 5c: "Remaining (estimate)" of the OpenAI balance, only on the phone (docs/DESIGN.md) */
class VoiceApiBalanceTest {
    private var now = 1_760_000_000_000L
    private fun balance(prefs: MemoryPrefs) = VoiceApiBalance(prefs, { now })
    private fun minutes(m: Double) = (m * 60_000).toLong()

    @Test fun notSetWithoutAnAmount() {
        val b = balance(MemoryPrefs())
        assertFalse(b.isSet())
        assertNull(b.remainingDollars())
        assertFalse(b.low())
        assertFalse(b.exhausted())
        assertEquals(VoiceApiBalance.Event.NONE, b.record(minutes(10.0)), "nothing is counted without an amount")
        assertNull(b.remainingDollars())
    }

    @Test fun amountsMinusMinutesTimesPrice() {
        val prefs = MemoryPrefs()
        val b = balance(prefs)
        b.topUp(5.0)
        b.topUp(5.0) // may be pressed several times
        assertEquals(10.0, b.remainingDollars()!!, 1e-9)
        assertEquals(now, b.lastEntryMs())
        b.record(minutes(100.0)) // 100 min * 0.0045 = 0.45
        assertEquals(9.55, b.remainingDollars()!!, 1e-9)
        // another price
        assertTrue(VoiceApiBalance.setPricePerMinute(prefs, 0.006))
        assertEquals(10.0 - 0.6, b.remainingDollars()!!, 1e-9)
        assertFalse(VoiceApiBalance.setPricePerMinute(prefs, 0.0))
        assertFalse(VoiceApiBalance.setPricePerMinute(prefs, 5.0))
        // set remaining starts the estimate again from this amount
        b.setRemaining(3.0)
        assertEquals(3.0, b.remainingDollars()!!, 1e-9)
        // reset forgets amounts and minutes, the price stays
        b.reset()
        assertFalse(b.isSet())
        assertEquals(0.006, b.pricePerMinute(), 1e-12)
    }

    @Test fun defaultPriceIsTheCheckedOne() {
        assertEquals(0.0045, VoiceApiBalance.pricePerMinute(MemoryPrefs()), 1e-12)
        assertEquals(VoiceApiBalance.DEFAULT_PRICE_PER_MINUTE, VoiceApiUsage.PRICE_PER_MINUTE)
    }

    @Test fun lowBelowOneDollarRemindsOnce() {
        val b = balance(MemoryPrefs())
        b.setRemaining(1.5)
        assertFalse(b.low())
        // 0.0045 per minute: 1.5 dollars are 333 minutes; 120 minutes leave 0.96
        assertEquals(VoiceApiBalance.Event.LOW, b.record(minutes(120.0)))
        assertTrue(b.low())
        assertEquals(VoiceApiBalance.Event.NONE, b.record(minutes(1.0)), "only one reminder")
        assertTrue(b.low())
        // a top-up above the threshold arms the reminder again
        b.topUp(10.0)
        assertFalse(b.low())
        b.setRemaining(1.01)
        assertEquals(VoiceApiBalance.Event.LOW, b.record(minutes(5.0)))
    }

    @Test fun zeroMeansExhaustedAndSystemRecognition() {
        val prefs = MemoryPrefs()
        val b = balance(prefs)
        b.setRemaining(0.01) // about 2.2 minutes
        assertEquals(VoiceApiBalance.Event.EXHAUSTED, b.record(minutes(3.0)))
        assertTrue(b.exhausted())
        assertFalse(b.low(), "exhausted is not 'low'")
        assertEquals(VoiceApiBalance.Event.NONE, b.record(minutes(1.0)), "said once")
        assertFalse(choice(b.exhausted()).openAi)
        assertEquals(VoiceApiProblem.NO_BALANCE, choice(b.exhausted()).notice)
        // set to 0 directly: exhausted too
        b.setRemaining(0.0)
        assertTrue(b.exhausted())
        b.topUp(5.0)
        assertFalse(b.exhausted())
        assertTrue(choice(b.exhausted()).openAi)
    }

    private fun choice(exhausted: Boolean) = VoiceEngineChoice.decide(VoiceEngineChoice.Inputs(
        inBuild = true, setting = SpeechEngineSetting.OPENAI, consent = true, hasKey = true, keyRejected = false,
        internetOn = true, locked = false, fieldBlocked = false, balanceExhausted = exhausted))

    @Test fun answer402OrInsufficientQuotaMeansExhausted() {
        for (failure in listOf(TranscriptionResult.Failed(VoiceApiProblem.NO_BALANCE, 402),
            TranscriptionResult.Failed(VoiceApiProblem.NO_BALANCE, 429))) {
            val prefs = MemoryPrefs()
            // even without an entered amount: "Balance used up" and the system recognizer
            val outcome = VoiceApi.applyResultFully(prefs, failure, 3000, now)
            assertEquals(VoiceApiBalance.Event.EXHAUSTED, outcome.balance)
            assertTrue(balance(prefs).exhausted())
            assertEquals(0L, VoiceApiUsage(prefs, { now }).usedMs(), "a refused request is not counted")
            assertFalse(choice(balance(prefs).exhausted()).openAi)
            // until the user enters money
            balance(prefs).topUp(10.0)
            assertFalse(balance(prefs).exhausted())
        }
        // in Auto mode the system recognizer is used without a line
        val auto = VoiceEngineChoice.decide(VoiceEngineChoice.Inputs(true, SpeechEngineSetting.AUTO, true, true, false, true,
            false, false, balanceExhausted = true))
        assertFalse(auto.openAi)
        assertNull(auto.notice)
    }

    @Test fun transcriptionsReduceTheEstimate() {
        val prefs = MemoryPrefs()
        balance(prefs).setRemaining(2.0)
        VoiceApi.applyResultFully(prefs, TranscriptionResult.Ok("x", 600.0), 600_000, now) // 10 minutes
        assertEquals(2.0 - 10 * 0.0045, balance(prefs).remainingDollars()!!, 1e-9)
        // a refused request costs nothing
        VoiceApi.applyResultFully(prefs, TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401), 600_000, now)
        assertEquals(2.0 - 10 * 0.0045, balance(prefs).remainingDollars()!!, 1e-9)
    }

    @Test fun parsingAmounts() {
        assertEquals(10.0, VoiceApiBalance.parseAmount("10"))
        assertEquals(10.5, VoiceApiBalance.parseAmount("10,5"))
        assertEquals(10.5, VoiceApiBalance.parseAmount(" \$10.50 "))
        assertNull(VoiceApiBalance.parseAmount("0"))
        assertEquals(0.0, VoiceApiBalance.parseAmount("0", allowZero = true))
        assertNull(VoiceApiBalance.parseAmount("-5"))
        assertNull(VoiceApiBalance.parseAmount("abc"))
        assertNull(VoiceApiBalance.parseAmount("1e5"))
        assertNull(VoiceApiBalance.parseAmount("200000"))
        assertEquals("12,35", VoiceApiBalance.format(12.346, Locale.forLanguageTag("ru")).replace('.', ','))
    }

    @Test fun eraseEverythingForgetsTheEstimate() {
        val prefs = MemoryPrefs()
        balance(prefs).topUp(3.0)
        VoiceApiBalance.setPricePerMinute(prefs, 0.01)
        balance(prefs).markExhausted()
        VoiceApiPrefs.eraseSettings(prefs)
        VoiceApiBalance.KEYS.forEach { assertFalse(prefs.contains(it), it) }
    }
}
