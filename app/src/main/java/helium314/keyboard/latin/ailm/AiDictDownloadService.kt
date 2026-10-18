// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsActivity2
import java.util.concurrent.Executors

/**
 * ai-dict-2: the download of the AI dictionary as a foreground service (type dataSync), so that it is not cut off when
 * the screen is closed. The notification shows the progress "N of M MB" and a "Stop" button; at the end "AI dictionary is
 * ready" or "Download interrupted — continue" (the button starts the download again from where it stopped). Only the
 * personal variant declares it (src/aikbPersonal/AndroidManifest.xml).
 */
class AiDictDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-dict-download").apply { isDaemon = true } }
    @Volatile private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AiDictDownloads.cancel()
            if (!working) stopSelf(startId) // an old notification: nothing runs
            return START_NOT_STICKY
        }
        val total = AiDictModels.current.totalBytes
        // within a few seconds after startForegroundService
        startForeground(NOTIF_ID, progress(this, 0, total), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (working) return START_NOT_STICKY // already running: the notification is only shown again
        working = true
        AiDictDownloads.state.value = AiDictDownloads.State.Running(0, total)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(RESULT_ID)
        executor.execute {
            var lastMs = 0L
            val ok = AiDictDownloads.run(applicationContext) { done, all ->
                val now = System.currentTimeMillis()
                if (now - lastMs >= 700) { lastMs = now; nm.notify(NOTIF_ID, progress(this, done, all)) } // not faster than 1.5 times a second
            }
            working = false
            val cancelled = (AiDictDownloads.state.value as? AiDictDownloads.State.Failed)?.message?.isEmpty() == true
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(RESULT_ID, result(this, ok, cancelled))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** Android 15: the system limits dataSync to 6 hours a day and calls this at the end; the part stays for "continue" */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AiDictDownloads.cancel()
        stopSelf(startId)
    }

    companion object {
        const val ACTION_START = "helium314.keyboard.action.AI_DICT_DOWNLOAD"
        const val ACTION_STOP = "helium314.keyboard.action.AI_DICT_STOP"
        const val CHANNEL = "ai_dict_download"
        const val NOTIF_ID = 4412
        const val RESULT_ID = 4413

        private fun mb(bytes: Long) = ((bytes + 500_000) / 1_000_000).toInt()

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_dict_notif_channel), NotificationManager.IMPORTANCE_LOW))
        }

        private fun openSettings(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, SettingsActivity2::class.java).setAction(SettingsActivity.ACTION_OPEN_AI_DICT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(context, code,
            Intent(context, AiDictDownloadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun builder(context: Context): NotificationCompat.Builder {
            channel(context)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_aikb_sparkle)
                .setContentTitle(context.getString(R.string.ai_dict_title)).setContentIntent(openSettings(context)).setOnlyAlertOnce(true)
        }

        /** the ongoing notification: "N of M MB", a bar, a "Stop" button */
        fun progress(context: Context, done: Long, total: Long): Notification = builder(context)
            .setContentText(context.getString(R.string.ai_dict_notif_progress, mb(done), mb(total)))
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.ai_dict_stop), serviceIntent(context, ACTION_STOP, 1))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

        /** the end: ready, or interrupted / stopped with a button to continue */
        fun result(context: Context, ok: Boolean, stopped: Boolean): Notification {
            val b = builder(context).setAutoCancel(true).setOngoing(false).setCategory(NotificationCompat.CATEGORY_STATUS)
            return if (ok) b.setContentText(context.getString(R.string.ai_dict_notif_done)).build()
            else b.setContentText(context.getString(if (stopped) R.string.ai_dict_notif_stopped else R.string.ai_dict_notif_failed))
                .addAction(0, context.getString(R.string.ai_dict_notif_resume), serviceIntent(context, ACTION_START, 2)).build()
        }
    }
}
