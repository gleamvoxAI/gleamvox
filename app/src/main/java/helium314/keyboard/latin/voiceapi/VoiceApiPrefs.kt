// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.BuildConfig

/** "Speech recognition" setting (docs/VOICE_API.md, section 3) */
enum class SpeechEngineSetting(val value: String) {
    /** OpenAI: "Smart recognition" on the screen (stage 5zh, before "OpenAI (paid)") */
    OPENAI("openai"),
    /** Android speech recognition, as in stage 3 */
    SYSTEM("system"),
    /** "Auto (recommended)": OpenAI when it can be used, else the system one, without a message */
    AUTO("auto");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/** Settings of voice through the OpenAI API. No key, no sound, no text: the key is in [ApiKeyStore]. */
object VoiceApiPrefs {
    const val PREF_ENGINE = "aikb_speech_engine"
    /** the consent screen was accepted: sound goes to OpenAI with your key and you pay per minute */
    const val PREF_CONSENT = "aikb_voice_api_consent"
    /** OpenAI answered 401: not used until the key is checked again or replaced */
    const val PREF_KEY_REJECTED = "aikb_voice_api_key_rejected"
    /** after a balance or rate limit error: no OpenAI until this time */
    const val PREF_PAUSED_UNTIL = "aikb_voice_api_paused_until"
    const val PREF_PAUSE_REASON = "aikb_voice_api_pause_reason"
    /** longest recording in seconds (default 120, 10..300) */
    const val PREF_MAX_SECONDS = "aikb_voice_api_max_seconds"
    const val DEFAULT_MAX_SECONDS = 120
    const val MIN_MAX_SECONDS = 10
    const val MAX_MAX_SECONDS = 300

    /**
     * "Pause before sending": this much silence after speech ends the phrase (0.8–2.5 s). Stored only when the user
     * moves the slider, so everybody who never changed it gets the new default of 1.5 s (owner, 2026-10-13; it was a
     * fixed 1.2 s before stage 5c).
     */
    const val PREF_END_PAUSE_MS = "aikb_voice_api_end_pause_ms"
    const val DEFAULT_END_PAUSE_MS = 1300 // stage 5g-19: 1.3 s (was 1.5 s); a pause set by hand stays
    const val MIN_END_PAUSE_MS = 800
    const val MAX_END_PAUSE_MS = 2500
    /** the slider moves in steps of 0.1 s */
    const val END_PAUSE_STEP_MS = 100

    fun endPauseMs(prefs: SharedPreferences): Int =
        prefs.getInt(PREF_END_PAUSE_MS, DEFAULT_END_PAUSE_MS).coerceIn(MIN_END_PAUSE_MS, MAX_END_PAUSE_MS)

    fun setEndPauseMs(prefs: SharedPreferences, ms: Int) = prefs.edit {
        val stepped = (ms + END_PAUSE_STEP_MS / 2) / END_PAUSE_STEP_MS * END_PAUSE_STEP_MS
        putInt(PREF_END_PAUSE_MS, stepped.coerceIn(MIN_END_PAUSE_MS, MAX_END_PAUSE_MS))
    }

    /** the microphone source that gave a signal last time (MIC by default, else VOICE_RECOGNITION) */
    const val PREF_AUDIO_SOURCE = "aikb_voice_audio_source"

    fun audioSource(prefs: SharedPreferences): Int =
        if (prefs.getInt(PREF_AUDIO_SOURCE, Loudness.SOURCE_MIC) == Loudness.SOURCE_VOICE_RECOGNITION) Loudness.SOURCE_VOICE_RECOGNITION
        else Loudness.SOURCE_MIC

    fun setAudioSource(prefs: SharedPreferences, source: Int) = prefs.edit { putInt(PREF_AUDIO_SOURCE, source) }

    /**
     * Continuous dictation (stage 5g, off by default): after a phrase is sent the recording goes on without a break, until
     * the circle is tapped, [PREF_CONTINUOUS_SILENCE_S] without speech, the session limit or the monthly minutes run out.
     * A long press on the microphone starts it once even when it is off.
     */
    const val PREF_CONTINUOUS = "aikb_voice_continuous"
    /** seconds without speech that end a continuous dictation: 10 by default (the owner), 5..60 */
    const val PREF_CONTINUOUS_SILENCE_S = "aikb_voice_continuous_silence_s"
    const val DEFAULT_CONTINUOUS_SILENCE_S = 10
    const val MIN_CONTINUOUS_SILENCE_S = 5
    const val MAX_CONTINUOUS_SILENCE_S = 60
    /** the longest continuous dictation in minutes: 5 by default, 1..10 */
    const val PREF_CONTINUOUS_LIMIT_MIN = "aikb_voice_continuous_limit_min"
    const val DEFAULT_CONTINUOUS_LIMIT_MIN = 5
    const val MIN_CONTINUOUS_LIMIT_MIN = 1
    const val MAX_CONTINUOUS_LIMIT_MIN = 10

    fun continuous(prefs: SharedPreferences) = prefs.getBoolean(PREF_CONTINUOUS, false)
    fun setContinuous(prefs: SharedPreferences, on: Boolean) = prefs.edit { putBoolean(PREF_CONTINUOUS, on) }

    fun continuousSilenceS(prefs: SharedPreferences) =
        prefs.getInt(PREF_CONTINUOUS_SILENCE_S, DEFAULT_CONTINUOUS_SILENCE_S).coerceIn(MIN_CONTINUOUS_SILENCE_S, MAX_CONTINUOUS_SILENCE_S)
    fun setContinuousSilenceS(prefs: SharedPreferences, s: Int) =
        prefs.edit { putInt(PREF_CONTINUOUS_SILENCE_S, s.coerceIn(MIN_CONTINUOUS_SILENCE_S, MAX_CONTINUOUS_SILENCE_S)) }

    fun continuousLimitMin(prefs: SharedPreferences) =
        prefs.getInt(PREF_CONTINUOUS_LIMIT_MIN, DEFAULT_CONTINUOUS_LIMIT_MIN).coerceIn(MIN_CONTINUOUS_LIMIT_MIN, MAX_CONTINUOUS_LIMIT_MIN)
    fun setContinuousLimitMin(prefs: SharedPreferences, m: Int) =
        prefs.edit { putInt(PREF_CONTINUOUS_LIMIT_MIN, m.coerceIn(MIN_CONTINUOUS_LIMIT_MIN, MAX_CONTINUOUS_LIMIT_MIN)) }

    /** a restored backup must never switch sending sound on by itself */
    val RESET_ON_RESTORE = listOf(PREF_ENGINE, PREF_CONSENT)

    val KEYS = listOf(PREF_ENGINE, PREF_CONSENT, PREF_KEY_REJECTED, PREF_PAUSED_UNTIL, PREF_PAUSE_REASON, PREF_MAX_SECONDS, PREF_AUDIO_SOURCE,
        PREF_END_PAUSE_MS, PREF_CONTINUOUS, PREF_CONTINUOUS_SILENCE_S, PREF_CONTINUOUS_LIMIT_MIN, MicChoice.PREF) + VoiceApiUsage.KEYS +
        VoiceApiBalance.KEYS

    /** only the personal variant has the INTERNET permission; the Keystore key needs Android 9 */
    fun isAvailable() = inBuild() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** the personal variant; replaced in tests (the unit tests run without the AI part) */
    @JvmField
    var inBuild: () -> Boolean = { BuildConfig.AIKB_OPENAI }

    fun engine(prefs: SharedPreferences): SpeechEngineSetting =
        if (!isAvailable()) SpeechEngineSetting.SYSTEM else SpeechEngineSetting.of(prefs.getString(PREF_ENGINE, null))

    fun consent(prefs: SharedPreferences) = prefs.getBoolean(PREF_CONSENT, false)

    /** OpenAI or Auto may be chosen only with the consent; without it the setting stays "system" */
    fun setEngine(prefs: SharedPreferences, setting: SpeechEngineSetting): Boolean {
        if (setting != SpeechEngineSetting.SYSTEM && !consent(prefs)) return false
        prefs.edit { putString(PREF_ENGINE, setting.value) }
        return true
    }

    fun giveConsent(prefs: SharedPreferences) = prefs.edit { putBoolean(PREF_CONSENT, true) }

    /** the consent is withdrawn: back to the system recognizer */
    fun withdrawConsent(prefs: SharedPreferences) = prefs.edit { remove(PREF_CONSENT); putString(PREF_ENGINE, SpeechEngineSetting.SYSTEM.value) }

    /** "Voice through OpenAI" is switched on: consent and OpenAI or Auto chosen (the key is checked separately) */
    fun modeOn(prefs: SharedPreferences) = isAvailable() && consent(prefs) && engine(prefs) != SpeechEngineSetting.SYSTEM

    fun maxSeconds(prefs: SharedPreferences) = prefs.getInt(PREF_MAX_SECONDS, DEFAULT_MAX_SECONDS).coerceIn(MIN_MAX_SECONDS, MAX_MAX_SECONDS)

    fun setMaxSeconds(prefs: SharedPreferences, value: Int) = prefs.edit { putInt(PREF_MAX_SECONDS, value.coerceIn(MIN_MAX_SECONDS, MAX_MAX_SECONDS)) }

    fun keyRejected(prefs: SharedPreferences) = prefs.getBoolean(PREF_KEY_REJECTED, false)

    fun setKeyRejected(prefs: SharedPreferences, rejected: Boolean) = prefs.edit { putBoolean(PREF_KEY_REJECTED, rejected) }

    fun pause(prefs: SharedPreferences, untilMs: Long, reason: String) = prefs.edit { putLong(PREF_PAUSED_UNTIL, untilMs); putString(PREF_PAUSE_REASON, reason) }

    fun pausedUntil(prefs: SharedPreferences) = prefs.getLong(PREF_PAUSED_UNTIL, 0)

    fun pauseReason(prefs: SharedPreferences): String? = prefs.getString(PREF_PAUSE_REASON, null)

    fun eraseSettings(prefs: SharedPreferences) = prefs.edit { KEYS.forEach { remove(it) } }
}
