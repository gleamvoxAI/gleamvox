// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId

/**
 * Local usage of AI text requests, only on this phone (never sent anywhere): requests sent today (✨ and cleanup of
 * dictation together) with the daily ceiling (default 100, 0..500; 0 = no AI text requests), the cleanups of today
 * as their own line, and the pause after "no balance" or "too many requests". Only numbers and dates, never text.
 */
class AiUsage(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private fun today(): String = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()

    fun todayCount(): Int = if (prefs.getString(PREF_DAY, null) == today()) prefs.getInt(PREF_COUNT, 0) else 0

    /** "Cleanup: N today" */
    fun todayCleanups(): Int = if (prefs.getString(PREF_DAY, null) == today()) prefs.getInt(PREF_CLEANUP_COUNT, 0) else 0

    fun cap(): Int = prefs.getInt(PREF_CAP, DEFAULT_CAP).coerceIn(0, MAX_CAP)

    fun setCap(value: Int) = prefs.edit { putInt(PREF_CAP, value.coerceIn(0, MAX_CAP)) }

    fun capReached(): Boolean = todayCount() >= cap()

    /** one request was sent (also a repeated one: it may be billed as well); [cleanup] counts it on its own line too */
    @Synchronized
    fun recordSent(cleanup: Boolean = false) {
        val day = today()
        val same = prefs.getString(PREF_DAY, null) == day
        val count = if (same) prefs.getInt(PREF_COUNT, 0) else 0
        val cleanups = if (same) prefs.getInt(PREF_CLEANUP_COUNT, 0) else 0
        prefs.edit {
            putString(PREF_DAY, day)
            putInt(PREF_COUNT, count + 1)
            putInt(PREF_CLEANUP_COUNT, cleanups + if (cleanup) 1 else 0)
        }
    }

    /** after "no balance" or "too many requests": no new text request for [ms] */
    fun pause(reason: AiError, ms: Long) = prefs.edit { putLong(PREF_PAUSED_UNTIL, now() + ms); putString(PREF_PAUSE_REASON, reason.name) }

    fun isPaused(): Boolean = now() < prefs.getLong(PREF_PAUSED_UNTIL, 0)

    /** the error of a running pause, or null */
    fun pausedError(): AiError? {
        if (!isPaused()) return null
        return if (prefs.getString(PREF_PAUSE_REASON, null) == AiError.NO_BALANCE.name) AiError.NO_BALANCE else AiError.RATE_LIMIT
    }

    fun clear() = prefs.edit { KEYS.forEach { remove(it) } }

    companion object {
        const val PREF_DAY = "aikb_ai_usage_day"
        const val PREF_COUNT = "aikb_ai_usage_count"
        const val PREF_CLEANUP_COUNT = "aikb_ai_cleanup_count"
        const val PREF_CAP = "aikb_ai_daily_cap"
        const val PREF_PAUSED_UNTIL = "aikb_ai_paused_until"
        const val PREF_PAUSE_REASON = "aikb_ai_pause_reason"
        val KEYS = listOf(PREF_DAY, PREF_COUNT, PREF_CLEANUP_COUNT, PREF_CAP, PREF_PAUSED_UNTIL, PREF_PAUSE_REASON)
        const val DEFAULT_CAP = 100
        const val MAX_CAP = 500
    }
}
