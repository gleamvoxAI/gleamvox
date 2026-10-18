// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Minutes of sound sent to OpenAI this month, only on this phone (docs/VOICE_API.md, section 2), and the monthly
 * ceiling (default 300 minutes, 0..3000; 0: no OpenAI). Only numbers are stored. It is an estimate: the real usage is
 * in the OpenAI dashboard, where the owner also sets a monthly spending limit.
 */
class VoiceApiUsage(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private fun month(): String = YearMonth.from(Instant.ofEpochMilli(now()).atZone(zone())).toString()

    fun usedMs(): Long = if (prefs.getString(PREF_MONTH, null) == month()) prefs.getLong(PREF_USED_MS, 0) else 0

    fun capMinutes(): Int = prefs.getInt(PREF_CAP_MINUTES, DEFAULT_CAP_MINUTES).coerceIn(0, MAX_CAP_MINUTES)

    fun setCapMinutes(value: Int) = prefs.edit { putInt(PREF_CAP_MINUTES, value.coerceIn(0, MAX_CAP_MINUTES)) }

    fun remainingMs(): Long = (capMinutes() * 60_000L - usedMs()).coerceAtLeast(0)

    /** less than a second left: no OpenAI until next month or a higher ceiling */
    fun capReached(): Boolean = remainingMs() < MIN_RECORDING_MS

    fun nearCap(): Boolean = capMinutes() > 0 && usedMs() >= capMinutes() * 60_000L * WARN_SHARE

    fun usedMinutes(): Double = usedMs() / 60_000.0

    /** at the price of the settings (0.0045 checked on 2026-10-13); the price may change */
    fun estimatedDollars(): Double = usedMinutes() * VoiceApiBalance.pricePerMinute(prefs)

    /** adds sent sound; true the first time this month the usage passes 80 % of the ceiling (warn once) */
    @Synchronized
    fun record(ms: Long): Boolean {
        val m = month()
        val before = usedMs()
        val after = before + ms.coerceAtLeast(0)
        val limit = capMinutes() * 60_000L * WARN_SHARE
        val warn = capMinutes() > 0 && before < limit && after >= limit && prefs.getString(PREF_WARNED_MONTH, null) != m
        prefs.edit {
            putString(PREF_MONTH, m)
            putLong(PREF_USED_MS, after)
            if (warn) putString(PREF_WARNED_MONTH, m)
        }
        return warn
    }

    companion object {
        const val PREF_MONTH = "aikb_voice_api_month"
        const val PREF_USED_MS = "aikb_voice_api_used_ms"
        const val PREF_CAP_MINUTES = "aikb_voice_api_cap_minutes"
        const val PREF_WARNED_MONTH = "aikb_voice_api_warned_month"
        val KEYS = listOf(PREF_MONTH, PREF_USED_MS, PREF_CAP_MINUTES, PREF_WARNED_MONTH)
        const val DEFAULT_CAP_MINUTES = 300
        const val MAX_CAP_MINUTES = 3000
        const val WARN_SHARE = 0.8
        const val MIN_RECORDING_MS = 1000L
        /** gpt-transcribe, dollars per minute of sound (pricing page, 2026-10-13); the setting may change it */
        const val PRICE_PER_MINUTE = VoiceApiBalance.DEFAULT_PRICE_PER_MINUTE

        /**
         * Which answers count: a transcription (OpenAI's own "usage" seconds if it sent them) and a connection that
         * broke after sending (it may have been paid). Refused requests (4xx) don't count.
         */
        fun billedMs(r: TranscriptionResult, sentMs: Long): Long = when (r) {
            is TranscriptionResult.Ok -> r.billedSeconds?.let { (it * 1000).toLong() } ?: sentMs
            is TranscriptionResult.Failed -> if (r.problem == VoiceApiProblem.NETWORK || r.problem == VoiceApiProblem.SERVER) sentMs else 0
        }
    }
}
