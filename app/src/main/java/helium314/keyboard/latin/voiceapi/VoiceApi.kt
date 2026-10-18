// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceapi

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiModel
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.AndroidKeystoreKeys
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors

/** Voice through the OpenAI API with the user's own key (docs/VOICE_API.md): the key and its state for the settings. */
object VoiceApi {
    private const val TAG = "VoiceApi"
    private var store: ApiKeyStore? = null
    val worker = Executors.newSingleThreadExecutor { Thread(it, "aikb-voice-api").apply { isDaemon = true } }
    /** continuous dictation (stage 5g): at most two phrases are recognized at the same time */
    val phraseWorkers = Executors.newFixedThreadPool(2) { Thread(it, "aikb-voice-phrase").apply { isDaemon = true } }

    /** null before the first unlock after a restart (no-backup folder and Keystore not ready) */
    @Synchronized
    fun keyStore(context: Context): ApiKeyStore? {
        if (!VoiceApiPrefs.isAvailable()) return null
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && app.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return null
        store?.let { return it }
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        return ApiKeyStore(ApiKeyStore.storageDir(app), AndroidKeystoreKeys(ApiKeyStore.KEYSTORE_ALIAS), { keyguard?.isDeviceLocked == true })
            .also { store = it }
    }

    fun hasKey(context: Context) = keyStore(context)?.hasKey() == true

    enum class SaveResult { SAVED, NOT_A_KEY, NOT_SAVED }

    /**
     * Saves a pasted key: only something shaped like an OpenAI key; a new key is not "rejected" any more. The clipboard
     * is cleared if it holds this key, and the keyboard's clipboard history forgets it.
     */
    fun saveKey(context: Context, text: String): SaveResult {
        val key = ApiKeyStore.normalize(text) ?: return SaveResult.NOT_A_KEY
        val s = keyStore(context) ?: return SaveResult.NOT_SAVED
        try {
            s.save(key)
        } catch (e: Exception) {
            Log.w(TAG, "key not saved: ${helium314.keyboard.latin.openai.NetDiagnostic.exceptionName(e)}")
            return SaveResult.NOT_SAVED
        }
        VoiceApiPrefs.setKeyRejected(context.prefs(), false)
        forgetInClipboard(context, key)
        Log.i(TAG, "key saved")
        return SaveResult.SAVED
    }

    /** the clipboard text, for "Paste from clipboard"; null if it is not text */
    fun clipboardText(context: Context): String? = try {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) { null }

    private fun forgetInClipboard(context: Context, key: String) {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && clipboardText(context)?.trim() == key) cm?.clearPrimaryClip()
        } catch (e: Exception) { }
        try { ClipboardDao.getInstance(context)?.deleteTextsMatching(ApiKeyStore::looksLikeKey) } catch (e: Exception) { }
    }

    /** "••••abcd", or null without a readable key */
    fun maskedKey(context: Context): String? = (keyStore(context)?.load() as? ApiKeyStore.Load.Ok)?.key?.let { ApiKeyStore.mask(it) }

    /** the key for a request; null if there is none or it can't be read now (phone locked) */
    fun key(context: Context): String? = (keyStore(context)?.load() as? ApiKeyStore.Load.Ok)?.key

    fun client(context: Context): TranscriptionClient {
        val app = context.applicationContext
        // stage 5g-20: pieces of at most 30 s; a short connect (5 s) and 30 s of silence at most, so a failing network
        // goes over to the retry quickly (the sound is kept, ChunkSender)
        return TranscriptionClient(AikbNetwork.get(app, readTimeoutMs = 30_000, connectTimeoutMs = 5_000), { key(app) })
    }

    /** what "Check key" found: the answer, whether gpt-transcribe (voice) is there and how many text models (✨) */
    class KeyCheck(val result: TranscriptionResult, val ms: Long, val voiceModel: Boolean, val textModels: Int)

    /**
     * "Check key" on a background thread: GET /v1/models, nothing is transcribed or generated. The text models of the
     * key are kept for ✨ (cheapest first). [onDone] gets the result and the time of the answer in ms.
     */
    fun checkKey(context: Context, onDone: (KeyCheck) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val start = System.nanoTime()
            var voiceModel = false
            var textModels = 0
            val r = client(app).checkKey { ids ->
                voiceModel = OpenAi.TRANSCRIBE_MODEL in ids
                val text = TextModels.sort(ids.filter { TextModels.isTextModel(it) }).map { AiModel(it, it) }
                textModels = text.size
                TextModels(app.prefs()).save(text)
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            when {
                r is TranscriptionResult.Ok -> VoiceApiPrefs.setKeyRejected(app.prefs(), false)
                r is TranscriptionResult.Failed && r.problem == VoiceApiProblem.KEY_INVALID -> VoiceApiPrefs.setKeyRejected(app.prefs(), true)
            }
            onDone(KeyCheck(r, ms, voiceModel, textModels))
        }
    }

    /** the large line of a failed key check, in plain words */
    fun keyCheckTitle(context: Context, p: VoiceApiProblem): String = when (p) {
        VoiceApiProblem.KEY_INVALID, VoiceApiProblem.KEY_REJECTED -> context.getString(R.string.voice_api_check_wrong_key)
        VoiceApiProblem.NO_BALANCE, VoiceApiProblem.PAUSED_BALANCE -> context.getString(R.string.voice_api_check_no_balance)
        VoiceApiProblem.RATE_LIMIT, VoiceApiProblem.PAUSED_RATE -> context.getString(R.string.voice_api_check_rate)
        VoiceApiProblem.NETWORK, VoiceApiProblem.SERVER -> context.getString(R.string.voice_api_check_no_connection)
        VoiceApiProblem.MODEL_NOT_AVAILABLE, VoiceApiProblem.FORBIDDEN -> context.getString(R.string.voice_api_check_no_model)
        VoiceApiProblem.INTERNET_OFF -> context.getString(R.string.voice_api_check_internet_off)
        VoiceApiProblem.MODE_OFF -> context.getString(R.string.voice_api_check_mode_off)
        else -> problemText(context, p).replaceFirstChar { it.uppercase() }
    }

    /** "Copy details" of a key check: codes, time, switches; never the key, not even its last characters */
    fun keyCheckDetails(context: Context, r: TranscriptionResult, ms: Long): String = listOfNotNull(
        "AI Keyboard: OpenAI API key check",
        "result: " + when (r) { is TranscriptionResult.Ok -> "ok"; is TranscriptionResult.Failed -> r.details() },
        "time: $ms ms",
        "internet switch: " + if (AikbNetwork.internetAllowed(context)) "on" else "off",
        "voice through OpenAI: " + if (VoiceApiPrefs.modeOn(context.prefs())) "on" else "off",
        "proxy for OpenAI: " + helium314.keyboard.latin.openai.OpenAiProxy.read(context.prefs()),
        keyStore(context)?.protection()?.let { "key protection: " + it.name.lowercase() },
    ).joinToString("\n")

    /** a short reason in plain words, in the language of [context] */
    fun problemText(context: Context, p: VoiceApiProblem): String = context.getString(when (p) {
        VoiceApiProblem.INTERNET_OFF -> R.string.voice_api_problem_internet_off
        VoiceApiProblem.NO_KEY -> R.string.voice_api_problem_no_key
        VoiceApiProblem.KEY_REJECTED, VoiceApiProblem.KEY_INVALID -> R.string.voice_api_problem_key_invalid
        VoiceApiProblem.CAP_REACHED -> R.string.voice_api_problem_cap
        VoiceApiProblem.PAUSED_BALANCE, VoiceApiProblem.NO_BALANCE -> R.string.voice_api_problem_balance
        VoiceApiProblem.PAUSED_RATE, VoiceApiProblem.RATE_LIMIT -> R.string.voice_api_problem_rate
        VoiceApiProblem.LOCKED -> R.string.voice_api_problem_locked
        VoiceApiProblem.TOO_LARGE -> R.string.voice_api_problem_too_large
        VoiceApiProblem.BAD_REQUEST -> R.string.voice_api_problem_bad_request
        VoiceApiProblem.FORBIDDEN -> R.string.voice_api_problem_forbidden
        VoiceApiProblem.MODEL_NOT_AVAILABLE -> R.string.voice_api_problem_model
        VoiceApiProblem.SERVER -> R.string.voice_api_problem_server
        VoiceApiProblem.NETWORK -> R.string.voice_api_problem_network
        VoiceApiProblem.MODE_OFF -> R.string.voice_api_problem_mode_off
        VoiceApiProblem.BAD_ANSWER -> R.string.voice_api_problem_bad_answer
        VoiceApiProblem.CANCELLED -> R.string.voice_api_problem_cancelled
    })

    /** pause after "no balance" (docs/VOICE_API.md, section 5; our choice) */
    const val BALANCE_PAUSE_MS = 30 * 60_000L
    /** pause after "too many requests" */
    const val RATE_PAUSE_MS = 60_000L

    /**
     * What an answer means for the next dictations: minutes used, key rejected, pauses. Returns true when the usage
     * passed 80 % of the monthly ceiling for the first time this month (the keyboard warns once).
     */
    fun applyResult(prefs: android.content.SharedPreferences, r: TranscriptionResult, sentMs: Long, now: Long): Boolean =
        applyResultFully(prefs, r, sentMs, now).usageWarning

    /** what the keyboard says once after a dictation: 80 % of the ceiling, little or no money left (estimate) */
    class Outcome(val usageWarning: Boolean, val balance: VoiceApiBalance.Event)

    fun applyResultFully(prefs: android.content.SharedPreferences, r: TranscriptionResult, sentMs: Long, now: Long): Outcome {
        val billed = VoiceApiUsage.billedMs(r, sentMs)
        val warn = if (billed > 0) VoiceApiUsage(prefs, { now }).record(billed) else false
        // stage 5g-5: no network to OpenAI shows the proxy and the check to everybody; any answer hides them
        if (r is TranscriptionResult.Failed && r.problem == VoiceApiProblem.NETWORK) helium314.keyboard.latin.openai.OpenAiReach.problem(prefs, now)
        else if (r !is TranscriptionResult.Failed || r.status != null) helium314.keyboard.latin.openai.OpenAiReach.ok(prefs)
        val balance = VoiceApiBalance(prefs, { now })
        var event = balance.record(billed)
        if (r is TranscriptionResult.Failed) when (r.problem) {
            VoiceApiProblem.KEY_INVALID -> VoiceApiPrefs.setKeyRejected(prefs, true)
            VoiceApiProblem.NO_BALANCE -> {
                VoiceApiPrefs.pause(prefs, now + BALANCE_PAUSE_MS, "balance")
                // "Balance used up" until the user enters money; meanwhile the system recognizer
                event = balance.markExhausted()
            }
            VoiceApiProblem.RATE_LIMIT -> VoiceApiPrefs.pause(prefs, now + RATE_PAUSE_MS, "rate")
            else -> Unit
        }
        return Outcome(warn, event)
    }

    /** a pause that is still running, or null */
    fun pausedProblem(prefs: android.content.SharedPreferences, now: Long): VoiceApiProblem? = when {
        now >= VoiceApiPrefs.pausedUntil(prefs) -> null
        VoiceApiPrefs.pauseReason(prefs) == "balance" -> VoiceApiProblem.PAUSED_BALANCE
        else -> VoiceApiProblem.PAUSED_RATE
    }

    fun deleteKey(context: Context) {
        keyStore(context)?.delete()
        VoiceApiPrefs.setKeyRejected(context.prefs(), false)
        Log.i(TAG, "key deleted")
    }
}
