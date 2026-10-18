// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.utils.prefs

/**
 * ai-dict-2 (owner's decision 2026-10-17): where the AI dictionary is offered. The offer is never forced:
 *  1. the first start wizard, one step (only inside the wizard, a finished wizard is not shown again);
 *  2. once in the suggestion strip, when the person really uses the keyboard (3 days, 300 words) on Wi-Fi, not in a
 *     password / e-mail / number / "do not remember" field and not in incognito;
 *  3. a dialog in "Text correction" (when opened for the first time), and the permanent item there.
 * An answer "Not now" anywhere ([PREF_OFFER] = declined) means: never offered again by itself.
 * Only counts and a day are stored, never the typed text.
 */
object AiDictOffer {
    const val PREF_WORDS = "aikb_ai_dict_words"
    const val PREF_FIRST_DAY = "aikb_ai_dict_first_day"
    /** the strip offer was shown once (shown = used up, whatever the person did) */
    const val PREF_STRIP_SHOWN = "aikb_ai_dict_strip_shown"
    const val MIN_DAYS = 3
    const val MIN_WORDS = 300

    /** the person has not answered any offer yet ("" never answered; "declined" Not now; "accepted" download pressed) */
    fun unanswered(prefs: SharedPreferences) = prefs.getString(AiDictionary.PREF_OFFER, "").isNullOrEmpty()

    /** the wizard step: only for a model that exists in this build, not downloaded, not answered, and can be downloaded */
    fun wizardStep(inBuild: Boolean, downloaded: Boolean, unanswered: Boolean, internetAllowed: Boolean) =
        inBuild && !downloaded && unanswered && internetAllowed

    data class StripState(
        val inBuild: Boolean, val downloaded: Boolean, val unanswered: Boolean, val internetAllowed: Boolean,
        val stripShownBefore: Boolean, val downloading: Boolean,
        val daysUsed: Long, val words: Int,
        /** Wi-Fi or another unmetered network */
        val wifi: Boolean,
        /** password, e-mail, number, "do not remember" field, or incognito */
        val fieldHidden: Boolean,
    )

    fun showInStrip(s: StripState) = s.inBuild && !s.downloaded && s.unanswered && s.internetAllowed && !s.stripShownBefore &&
        !s.downloading && s.daysUsed >= MIN_DAYS && s.words >= MIN_WORDS && s.wifi && !s.fieldHidden

    // ---- usage: how many words were typed, since which day (no text) ----

    private var pending = 0
    private var cachedWords = -1

    /** a word was committed; the count is saved every 10 words (a keystroke must not wait for a disk write) */
    @Synchronized fun onWord(context: Context, epochDay: Long = System.currentTimeMillis() / DAY_MS) {
        if (!AiDictionary.inBuild()) return
        val prefs = context.prefs()
        if (cachedWords < 0) cachedWords = prefs.getInt(PREF_WORDS, 0)
        if (cachedWords >= MIN_WORDS * 4) return // enough, stop writing (the condition is "at least")
        cachedWords++
        pending++
        if (pending >= 10 || cachedWords == MIN_WORDS) {
            pending = 0
            prefs.edit {
                putInt(PREF_WORDS, cachedWords)
                if (!prefs.contains(PREF_FIRST_DAY)) putLong(PREF_FIRST_DAY, epochDay)
            }
        }
    }

    fun words(prefs: SharedPreferences) = prefs.getInt(PREF_WORDS, 0)

    fun daysUsed(prefs: SharedPreferences, epochDay: Long = System.currentTimeMillis() / DAY_MS): Long {
        val first = prefs.getLong(PREF_FIRST_DAY, -1L)
        return if (first < 0) 0 else (epochDay - first).coerceAtLeast(0)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Wi-Fi (or any network that is not paid by volume) */
    fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun stripState(context: Context, fieldHidden: Boolean): StripState {
        val prefs = context.prefs()
        return StripState(
            inBuild = AiDictionary.inBuild(), downloaded = AiDictionary.isDownloaded(context), unanswered = unanswered(prefs),
            internetAllowed = AikbNetwork.internetAllowed(context), stripShownBefore = prefs.getBoolean(PREF_STRIP_SHOWN, false),
            downloading = AiDictDownloads.isRunning(), daysUsed = daysUsed(prefs), words = words(prefs),
            wifi = onUnmeteredNetwork(context), fieldHidden = fieldHidden,
        )
    }

    /** "Not now" anywhere: never offered again by itself (the item in the settings stays) */
    // ---- one input session (a field the keyboard opened for) ----
    /** counts the starts of input; the strip offer belongs to one of them */
    @Volatile var session = 0
    @Volatile var sessionOffered = -1
    /** a key was pressed in this session: the offer is gone for it */
    @Volatile var keyPressed = false
    fun onStartInput() { session++; keyPressed = false }
    fun onKeyPressed() { keyPressed = true }

    fun decline(prefs: SharedPreferences) { prefs.edit { putString(AiDictionary.PREF_OFFER, "declined") } }

    fun markStripShown(prefs: SharedPreferences) { prefs.edit { putBoolean(PREF_STRIP_SHOWN, true) } }
}
