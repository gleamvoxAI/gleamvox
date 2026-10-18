// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ai-dict-1 / ai-dict-2: the state of the download of the AI dictionary (shown on the settings screen and in the
 * notification) and the entry points. The download itself runs in [AiDictDownloadService], a foreground service of type
 * dataSync with a notification (progress, "Stop"), so it goes on when the screen is closed. It resumes after a break
 * (HTTP Range) and the files are checked by SHA-256 (AiDictDownloader).
 */
object AiDictDownloads {
    sealed class State {
        object Idle : State()
        class Running(val done: Long, val total: Long) : State()
        class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    @Volatile private var downloader: AiDictDownloader? = null

    fun isRunning() = state.value is State.Running

    /** starts (or resumes) the download; the service shows the notification */
    fun start(context: Context) {
        if (isRunning()) return
        val app = context.applicationContext
        state.value = State.Running(0, AiDictModels.current.totalBytes)
        try {
            ContextCompat.startForegroundService(app, Intent(app, AiDictDownloadService::class.java).setAction(AiDictDownloadService.ACTION_START))
        } catch (e: Exception) { // e.g. the system refuses a foreground service
            Log.w("AiDictDownloads", "cannot start the download service", e)
            state.value = State.Failed(e.message ?: e.toString())
        }
    }

    /** runs in the service thread; throws nothing, the result goes to [state] */
    internal fun run(app: Context, onProgress: (Long, Long) -> Unit): Boolean {
        val model = AiDictModels.current
        val d = AiDictDownloader(app).also { downloader = it }
        return try {
            d.download(model) { p -> state.value = State.Running(p.done, p.total); onProgress(p.done, p.total) }
            AiDictionary.invalidate()
            app.prefs().edit { putBoolean(AiDictionary.PREF_ON, true) }
            AiDictionary.warmUp(app)
            state.value = State.Idle
            true
        } catch (e: Exception) {
            Log.w("AiDictDownloads", "download failed", e)
            state.value = State.Failed(if (d.cancelled) "" else e.message ?: e.toString())
            false
        } finally {
            downloader = null
        }
    }

    /** stops at once; the part already downloaded stays, the next start goes on from it */
    fun cancel() { downloader?.cancel() }

    fun delete(context: Context) {
        cancel()
        AiDictDownloader.delete(context, AiDictModels.current)
        AiDictionary.invalidate()
        state.value = State.Idle
    }
}
