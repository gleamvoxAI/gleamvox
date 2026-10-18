// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Long press on the round clipboard button: paste the current system clipboard as text. Read only at that moment, never logged. */
object ClipboardPaste {
    /** first item of [clip] as text, null if there is none, it is not text, or it is empty */
    @JvmStatic
    fun textOf(clip: ClipData?, context: Context): String? {
        if (clip == null || clip.itemCount == 0) return null
        if (clip.description?.hasMimeType("text/*") != true) return null
        return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun currentText(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return try { textOf(manager.primaryClip, context) } catch (_: Exception) { null }
    }
}
