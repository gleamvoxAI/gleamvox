// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Our own collector of crashes (stage 5g-5, a step towards the reports of 5i): no internet, no permission. Installed
 * first thing when the process starts (keyboard and settings alike), it writes the last [MAX] crashes to
 * noBackupFilesDir/crash-log.txt: time, version, thread, the classes of the exception and its causes, and the stack
 * frames. Never the messages of the exceptions (they may hold text the user typed), never keys. Then the standard
 * handler of Android goes on as before. Shown under "Last crashes" on the voice diagnostics screen and in its copy.
 */
class CrashLog(private val file: File, private val version: String, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** one crash as text: no message of the exception, only classes and frames */
    fun describe(thread: String, e: Throwable): String = buildString {
        append(SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date(now()))).append(" · ").append(version)
            .append(" · thread ").append(thread.take(40)).append('\n')
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            append(if (depth == 0) "" else "caused by ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(FRAMES).forEach { append("  at ").append(it.toString()).append('\n') }
            t = t.cause.takeIf { it !== t }
            depth++
        }
    }

    fun record(thread: String, e: Throwable) {
        try {
            synchronized(lock) {
                val entries = entries().toMutableList()
                entries.add(0, describe(thread, e).trimEnd())
                file.parentFile?.mkdirs()
                file.writeText(entries.take(MAX).joinToString(SEPARATOR))
            }
        } catch (_: Throwable) { } // a crash while writing a crash must not hide the first one
    }

    /** newest first */
    fun entries(): List<String> = try {
        if (!file.exists()) emptyList() else file.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    fun clear() { file.delete() }

    companion object {
        const val MAX = 5
        const val FILE_NAME = "crash-log.txt"
        private const val FRAMES = 30
        private const val SEPARATOR = "\n\n----\n\n"
        @Volatile private var instance: CrashLog? = null

        fun get(context: Context): CrashLog = instance ?: synchronized(this) {
            instance ?: CrashLog(File(context.applicationContext.noBackupFilesDir, FILE_NAME), BuildConfig.VERSION_NAME).also { instance = it }
        }

        /** wraps the default handler of the process: write, then the standard handling (the app closes as before) */
        fun install(context: Context) {
            val log = get(context)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is Handler) return
            Thread.setDefaultUncaughtExceptionHandler(Handler(log, previous))
        }
    }

    class Handler(private val log: CrashLog, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            log.record(t.name, e)
            previous?.uncaughtException(t, e)
        }
    }
}
