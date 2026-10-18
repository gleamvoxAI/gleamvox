// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Intent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.UpdateRelaunchGuard
import helium314.keyboard.settings.UpdateRelaunchGuard.WINDOW_MS
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings screen opened by the system right after an update is closed once (docs/DESIGN.md, stage 3d). */
@RunWith(RobolectricTestRunner::class)
class UpdateRelaunchGuardTest {
    private val installed = 1_000_000L
    private val updated = 5_000_000L

    private fun close(now: Long, handled: Long = 0L, restored: Boolean = false, action: String? = Intent.ACTION_MAIN, first: Long = installed,
                      starter: String? = "com.hihonor.android.launcher") =
        UpdateRelaunchGuard.shouldClose(now, first, updated, handled, restored, action, starter)

    /** stage 5g-5, the owner: "Open" in the installer right after an update opens the settings, it is never closed */
    @Test fun openOfTheInstallerIsNeverClosed() {
        for (installer in listOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.hihonor.packageinstaller", "com.android.vending"))
            assertFalse(close(updated + 500, starter = installer), installer)
        assertFalse(close(updated + 500, starter = null), "unknown source: opened")
        assertTrue(close(updated + 500), "a launcher that opens it by itself right after the update: closed once")
    }

    @Test fun closedRightAfterUpdate() {
        assertTrue(close(updated + 500))
        assertTrue(close(updated + WINDOW_MS - 1))
        assertTrue(close(updated + 3000, action = null))
    }

    @Test fun manualStartLaterWorks() {
        assertFalse(close(updated + WINDOW_MS))
        assertFalse(close(updated + 60_000))
    }

    @Test fun onlyOncePerUpdate() {
        assertFalse(close(updated + 1000, handled = updated))
        assertTrue(close(updated + 1000, handled = updated - 100)) // an older update was handled, this is a new one
    }

    @Test fun notOnFreshInstallRestoreOrOtherIntents() {
        assertFalse(close(updated + 1000, first = updated)) // fresh install: firstInstallTime == lastUpdateTime
        assertFalse(close(updated + 1000, restored = true))
        assertFalse(close(updated + 1000, action = Intent.ACTION_VIEW)) // e.g. opening a dictionary file
        assertFalse(close(updated - 1000)) // clock went back
    }

    @Test fun activityClosesOnceThenOpensNormally() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.prefs().edit { clear() }
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.firstInstallTime = System.currentTimeMillis() - 100_000
        info.lastUpdateTime = System.currentTimeMillis() - 1_000
        val installerOpen = Robolectric.buildActivity(SettingsActivity::class.java, Intent(Intent.ACTION_MAIN)
            .putExtra(Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://com.android.packageinstaller"))).create()
        assertFalse(installerOpen.get().isFinishing, "\"Open\" of the installer: the settings open")
        val first = Robolectric.buildActivity(SettingsActivity::class.java, Intent(Intent.ACTION_MAIN)
            .putExtra(Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://com.hihonor.android.launcher"))).create()
        assertTrue(first.get().isFinishing)
        assertEquals(info.lastUpdateTime, app.prefs().getLong(UpdateRelaunchGuard.PREF_HANDLED_UPDATE, 0))
        // second start (the user taps the icon): not closed by the guard
        assertFalse(UpdateRelaunchGuard.shouldClose(System.currentTimeMillis(), info.firstInstallTime, info.lastUpdateTime,
            app.prefs().getLong(UpdateRelaunchGuard.PREF_HANDLED_UPDATE, 0), false, Intent.ACTION_MAIN))
    }
}
