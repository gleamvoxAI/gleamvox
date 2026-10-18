// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Intent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.CrashLog
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5g-5: "Open" after an update, and our own collector of crashes */
@RunWith(RobolectricTestRunner::class)
class CrashAndUpgradeTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** the settings of 4351, 4365 and 4367 are left as they were; "Open" of the installer right after the update opens */
    @Test fun openAfterAnUpdateOverOldSettings() {
        for (old in listOf("4351", "4365", "4367")) {
            app.prefs().edit {
                clear()
                putString("aikb_speech_engine", "auto"); putBoolean("aikb_voice_api_consent", true)
                putBoolean("aikb_text_consent", true); putBoolean("aikb_internet_allowed", true)
                putInt(helium314.keyboard.latin.settings.Settings.PREF_VERSION_CODE, old.toInt())
                if (old != "4351") { putString("aikb_voice_mic", "headset"); putString("aikb_voice_headset_failed_device", "7:Buds:12"); putInt("aikb_voice_headset_failures", 2) }
                if (old == "4367") putLong("aikb_update_relaunch_handled", 1L)
            }
            val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
            info.firstInstallTime = System.currentTimeMillis() - 100_000
            info.lastUpdateTime = System.currentTimeMillis() - 2_000
            val a = Robolectric.buildActivity(SettingsActivity::class.java, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://com.android.packageinstaller")))
                .create().start().resume()
            assertFalse(a.get().isFinishing, "after $old: the settings stay open")
            a.pause().stop().destroy()
        }
    }

    @Test fun crashesAreWrittenWithoutMessagesAndTheStandardHandlerGoesOn() {
        val file = File("build/tmp/crash-log/crash-log.txt").apply { parentFile?.mkdirs(); delete() }
        val log = CrashLog(file, "4.2 build 4371", now = { 0L })
        var passedOn = 0
        val handler = CrashLog.Handler(log) { _, _ -> passedOn++ }
        for (i in 1..7) handler.uncaughtException(Thread.currentThread(),
            IllegalStateException("secret text the user typed $i", NullPointerException("the key sk-abc")))
        assertEquals(7, passedOn, "the standard handler of Android goes on every time")
        val entries = CrashLog(file, "x").entries()
        assertEquals(CrashLog.MAX, entries.size, "the last 5")
        val all = entries.joinToString()
        assertTrue("java.lang.IllegalStateException" in all && "caused by java.lang.NullPointerException" in all && "4.2 build 4371" in all, all)
        assertTrue("  at " in all, "the stack frames")
        assertFalse("secret" in all || "sk-abc" in all, "never the messages")
        CrashLog(file, "x").clear()
        assertTrue(CrashLog(file, "x").entries().isEmpty())
    }
}
