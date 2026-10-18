// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Some launchers / installers start the settings screen by themselves right after an update (docs/DESIGN.md, stage 3d).
 * The launcher settings screen started within [WINDOW_MS] after an update is closed at once, only one time per update.
 * The keyboard opens SettingsActivity2, which is never closed by this.
 *
 * Stage 5g-5 (the owner: "Open" in the installer right after an update "crashes"): that was this guard, the window
 * closed itself at once. Now it closes only when it is KNOWN who started it and it is not an installer: "Open" of the
 * installer (and any start whose source is unknown) always opens the settings.
 */
object UpdateRelaunchGuard {
    const val WINDOW_MS = 20_000L
    const val PREF_HANDLED_UPDATE = "aikb_update_relaunch_handled"

    fun shouldClose(
        now: Long,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        handledUpdateTime: Long,
        restored: Boolean,
        action: String?,
        /** the package that started the screen (Activity.getReferrer), null if unknown */
        starter: String? = null,
    ): Boolean {
        if (starter == null || isInstaller(starter)) return false // "Open" of the installer, or unknown: never closed
        if (restored) return false // recreated after rotation or process death: the user is already there
        if (action != null && action != Intent.ACTION_MAIN) return false // e.g. opening a dictionary file
        if (lastUpdateTime <= firstInstallTime) return false // fresh install, not an update
        if (handledUpdateTime == lastUpdateTime) return false // only once per update
        val sinceUpdate = now - lastUpdateTime
        return sinceUpdate in 0 until WINDOW_MS
    }

    /** the package installer of Android or of the phone maker, the Play store */
    fun isInstaller(pkg: String) = pkg.contains("packageinstaller", ignoreCase = true) || pkg.contains("installer", ignoreCase = true) ||
        pkg == "com.android.vending"

    /** @return true if the activity was finished */
    fun closeIfStartedByUpdate(activity: Activity, restored: Boolean): Boolean {
        val info = try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (e: Exception) {
            return false
        }
        val prefs = activity.prefs()
        val close = shouldClose(System.currentTimeMillis(), info.firstInstallTime, info.lastUpdateTime,
            prefs.getLong(PREF_HANDLED_UPDATE, 0L), restored, activity.intent?.action,
            try { activity.referrer?.host } catch (e: Exception) { null })
        if (!close) return false
        prefs.edit { putLong(PREF_HANDLED_UPDATE, info.lastUpdateTime) }
        Log.i("UpdateRelaunchGuard", "settings opened right after the update without user action, closing")
        activity.finish()
        if (Build.VERSION.SDK_INT >= 34) activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        return true
    }
}
