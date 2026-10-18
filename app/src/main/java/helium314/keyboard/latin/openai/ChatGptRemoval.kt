// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.features.AikbFeatures
import java.io.File

/**
 * Stage 5d migration (docs/AI.md, section 3): the ChatGPT sign-in is gone, so its leftovers are erased once, at the
 * first start of the new version: the encrypted tokens and the registration (noBackupFilesDir/chatgpt), their
 * Keystore key (without it the files could never be decrypted again anyway) and the settings that only the sign-in
 * used. The internet switch, the API key, voice settings, pause, ceilings, counters, balance estimate, proxy,
 * excluded apps and translation language stay. The consent for text requests is new and asked again.
 * Revoking at OpenAI is not possible any more (its address is not allowed): the user can disconnect AI Keyboard in
 * the ChatGPT settings. Before the first unlock nothing can be done; it is tried again at the next start.
 */
object ChatGptRemoval {
    const val PREF_DONE = "aikb_chatgpt_removed_5d"
    const val OLD_DIR = "chatgpt"
    const val OLD_KEYSTORE_ALIAS = "aikb_chatgpt_tokens_v1"
    /** the old feature card id: forgotten, so the new "ai" card is shown once */
    const val OLD_FEATURE_ID = "chatgpt"

    /** settings of the sign-in only; the old model list was in another format and its pause belonged to the plan */
    val OLD_PREFS = listOf(
        "aikb_chatgpt_privacy_accepted", "aikb_chatgpt_welcome_shown", "aikb_chatgpt_invite_snoozed_until",
        "aikb_ai_models", "aikb_ai_models_at", "aikb_ai_model", "aikb_ai_paused_until",
    )

    fun runOnce(context: Context) {
        val app = context.applicationContext
        val prefs = app.prefs()
        if (prefs.getBoolean(PREF_DONE, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && app.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        try {
            run(prefs, File(app.noBackupFilesDir, OLD_DIR)) { AndroidKeystoreKeys(OLD_KEYSTORE_ALIAS).delete() }
        } catch (e: Exception) {
            Log.w("ChatGptRemoval", "not finished: ${NetDiagnostic.exceptionName(e)}") // tried again next start
        }
    }

    /** the work, separate for tests; marks itself done only when the folder is really gone */
    fun run(prefs: SharedPreferences, oldDir: File, deleteKeystoreKey: () -> Unit): Boolean {
        val hadSomething = oldDir.exists() || OLD_PREFS.any { prefs.contains(it) }
        if (oldDir.exists()) {
            oldDir.walkBottomUp().forEach { f ->
                if (f.isFile) try { f.writeBytes(ByteArray(f.length().toInt().coerceAtMost(1 shl 20))) } catch (_: Exception) { }
                f.delete()
            }
        }
        deleteKeystoreKey()
        if (oldDir.exists()) return false
        val seen = prefs.getStringSet(AikbFeatures.PREF_SEEN, null)
        prefs.edit {
            OLD_PREFS.forEach { remove(it) }
            if (seen != null && OLD_FEATURE_ID in seen) putStringSet(AikbFeatures.PREF_SEEN, seen - OLD_FEATURE_ID)
            putBoolean(PREF_DONE, true)
        }
        Log.i("ChatGptRemoval", "done, old sign-in data " + if (hadSomething) "erased" else "not present")
        return true
    }
}
