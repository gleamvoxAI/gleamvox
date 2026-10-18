// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.ClipboardPaste
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.pinClipboardBeforeVoice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round clipboard button: long press logic and the order of the pinned buttons (clipboard left of microphone). */
@RunWith(RobolectricTestRunner::class)
class ClipboardButtonTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun enabled(pref: String) = pref.split(Separators.ENTRY).filter { it.endsWith("true") }.map { it.substringBefore(Separators.KV) }

    @Test fun textIsPasted() {
        assertEquals("привет", ClipboardPaste.textOf(ClipData.newPlainText("", "привет"), ctx))
    }

    @Test fun onlyFirstItemIsUsed() {
        val clip = ClipData.newPlainText("", "первый")
        clip.addItem(ClipData.Item("второй"))
        assertEquals("первый", ClipboardPaste.textOf(clip, ctx))
    }

    @Test fun emptyOrMissingClipboardPastesNothing() {
        assertNull(ClipboardPaste.textOf(null, ctx))
        assertNull(ClipboardPaste.textOf(ClipData.newPlainText("", ""), ctx))
    }

    @Test fun nonTextPastesNothing() {
        val clip = ClipData(ClipDescription("", arrayOf("image/png")), ClipData.Item(Uri.parse("content://x/1")))
        assertNull(ClipboardPaste.textOf(clip, ctx))
    }

    @Test fun defaultPinnedOrderIsClipboardThenMicrophone() {
        assertEquals(listOf(ToolbarKey.CLIPBOARD.name, ToolbarKey.VOICE.name), enabled(defaultPinnedToolbarPref))
    }

    @Test fun upgradePinsClipboardBeforeMicrophoneAndKeepsOthers() {
        val old = ToolbarKey.entries.filter { it != ToolbarKey.CLOSE_HISTORY }.joinToString(Separators.ENTRY) {
            it.name + Separators.KV + (it == ToolbarKey.VOICE || it == ToolbarKey.UNDO)
        }
        val result = pinClipboardBeforeVoice(old)
        val on = enabled(result)
        assertEquals(listOf("CLIPBOARD", "VOICE", "UNDO").sorted(), on.sorted())
        assertEquals(true, on.indexOf("CLIPBOARD") < on.indexOf("VOICE"))
        assertEquals(1, result.split(Separators.ENTRY).count { it.startsWith("CLIPBOARD" + Separators.KV) })
        assertEquals(result, pinClipboardBeforeVoice(result)) // idempotent
        assertEquals(defaultPinnedToolbarPref, pinClipboardBeforeVoice(null))
    }
}
