// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * "Remaining (estimate)" of the OpenAI balance, only on this phone (docs/DESIGN.md, "Остаток баланса OpenAI"): the exact
 * balance can't be read with a normal API key, so the keyboard estimates it: the amounts the user entered ("Topped up",
 * "Set remaining") minus the minutes sent since then times the price per minute. Nothing of this leaves the phone, and
 * it is always shown as an estimate. Money is kept in millionths of a dollar, so sums don't drift.
 */
class VoiceApiBalance(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    enum class Event { NONE, LOW, EXHAUSTED }

    /** the user entered an amount at least once */
    fun isSet() = prefs.contains(PREF_ADDED_MICRO)

    fun pricePerMinute(): Double = pricePerMinute(prefs)

    /** dollars, or null without an entered amount */
    fun remainingDollars(): Double? {
        if (!isSet()) return null
        return (prefs.getLong(PREF_ADDED_MICRO, 0) - spentMicro()) / MICRO
    }

    private fun spentMicro(): Long = (prefs.getLong(PREF_USED_MS, 0) / 60_000.0 * pricePerMinute() * MICRO).roundToLong()

    /** OpenAI answered 402 or insufficient_quota, or the estimate reached 0: no OpenAI until the user enters money */
    fun exhausted(): Boolean = prefs.getBoolean(PREF_EXHAUSTED, false) || remainingDollars()?.let { it <= 0.0 } == true

    /** less than one dollar left (and not yet empty) */
    fun low(): Boolean = !exhausted() && remainingDollars()?.let { it < LOW_DOLLARS } == true

    /** time of the last "Topped up" or "Set remaining", or 0 */
    fun lastEntryMs(): Long = prefs.getLong(PREF_ENTRY_MS, 0)

    /** "Topped up by N dollars": may be pressed several times, every amount is added */
    fun topUp(dollars: Double) {
        val added = prefs.getLong(PREF_ADDED_MICRO, 0) + toMicro(dollars)
        prefs.edit {
            putLong(PREF_ADDED_MICRO, added)
            if (!prefs.contains(PREF_USED_MS)) putLong(PREF_USED_MS, 0)
            putLong(PREF_ENTRY_MS, now())
            remove(PREF_EXHAUSTED)
        }
        if (!low()) prefs.edit { remove(PREF_LOW_WARNED) }
    }

    /** "Set remaining": the estimate starts again from this amount */
    fun setRemaining(dollars: Double) {
        prefs.edit {
            putLong(PREF_ADDED_MICRO, toMicro(dollars))
            putLong(PREF_USED_MS, 0)
            putLong(PREF_ENTRY_MS, now())
            remove(PREF_EXHAUSTED)
            remove(PREF_LOW_WARNED)
        }
    }

    /** "Reset estimate": forget amounts, minutes and marks; the price stays */
    fun reset() = prefs.edit { (KEYS - PREF_PRICE_MICRO).forEach { remove(it) } }

    /**
     * Sent sound that may be paid. Returns [Event.LOW] the first time the estimate falls below one dollar and
     * [Event.EXHAUSTED] when it reaches 0 (both once).
     */
    @Synchronized
    fun record(ms: Long): Event {
        if (!isSet() || ms <= 0) return Event.NONE
        val wasExhausted = exhausted()
        prefs.edit { putLong(PREF_USED_MS, prefs.getLong(PREF_USED_MS, 0) + ms) }
        return when {
            exhausted() -> if (wasExhausted) Event.NONE else Event.EXHAUSTED
            low() && !prefs.getBoolean(PREF_LOW_WARNED, false) -> { prefs.edit { putBoolean(PREF_LOW_WARNED, true) }; Event.LOW }
            else -> Event.NONE
        }
    }

    /** OpenAI said the balance is empty (402, insufficient_quota, credit_balance_exhausted) */
    fun markExhausted(): Event {
        val was = exhausted()
        prefs.edit { putBoolean(PREF_EXHAUSTED, true) }
        return if (was) Event.NONE else Event.EXHAUSTED
    }

    companion object {
        const val PREF_ADDED_MICRO = "aikb_voice_balance_added_micro"
        const val PREF_USED_MS = "aikb_voice_balance_used_ms"
        const val PREF_ENTRY_MS = "aikb_voice_balance_entry_ms"
        const val PREF_EXHAUSTED = "aikb_voice_balance_exhausted"
        const val PREF_LOW_WARNED = "aikb_voice_balance_low_warned"
        const val PREF_PRICE_MICRO = "aikb_voice_price_micro"
        val KEYS = listOf(PREF_ADDED_MICRO, PREF_USED_MS, PREF_ENTRY_MS, PREF_EXHAUSTED, PREF_LOW_WARNED, PREF_PRICE_MICRO)

        /** "Low": below one dollar */
        const val LOW_DOLLARS = 1.0
        /** gpt-transcribe, dollars per minute of sound (pricing page, 2026-10-13); the price may change */
        const val DEFAULT_PRICE_PER_MINUTE = 0.0045
        const val MAX_PRICE_PER_MINUTE = 1.0
        const val MAX_AMOUNT = 100_000.0
        private const val MICRO = 1_000_000.0

        fun toMicro(dollars: Double): Long = (dollars * MICRO).roundToLong()

        fun pricePerMinute(prefs: SharedPreferences): Double =
            prefs.getLong(PREF_PRICE_MICRO, toMicro(DEFAULT_PRICE_PER_MINUTE)) / MICRO

        /** a price of 0 or above [MAX_PRICE_PER_MINUTE] is refused */
        fun setPricePerMinute(prefs: SharedPreferences, dollars: Double): Boolean {
            if (dollars <= 0.0 || dollars > MAX_PRICE_PER_MINUTE || dollars.isNaN()) return false
            prefs.edit { putLong(PREF_PRICE_MICRO, toMicro(dollars)) }
            return true
        }

        /** "10", "10.5", "10,5", "$ 10": dollars; null for anything else, negative or above [MAX_AMOUNT] */
        fun parseAmount(text: String, allowZero: Boolean = false, max: Double = MAX_AMOUNT): Double? {
            val t = text.trim().removePrefix("$").trim().replace(',', '.').replace(" ", "").replace(" ", "")
            if (t.isEmpty() || !t.matches(Regex("""\d{1,7}(\.\d{1,6})?"""))) return null
            val v = t.toDoubleOrNull() ?: return null
            if (v > max || v < 0 || (v == 0.0 && !allowZero)) return null
            return v
        }

        /** "12.34" with the decimal sign of [locale] */
        fun format(dollars: Double, locale: Locale): String = String.format(locale, "%.2f", dollars)
    }
}
