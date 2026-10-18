// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.voice.PermissionAutoStart
import helium314.keyboard.latin.voice.PermissionAutoStart.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Automatic listening after the microphone permission dialog: once, same field only, never in sensitive fields. */
class PermissionAutoStartTest {
    private val field = PermissionAutoStart.fieldKey("com.chat", 7)

    private fun pending(): PermissionAutoStart = PermissionAutoStart().also { it.expectStart(1_000, field) }

    @Test fun permissionGrantedStartsOnce() {
        val a = pending()
        assertEquals(Action.START, a.onInputViewStarted(2_000, field, hasPermission = true, sensitive = false))
        assertFalse(a.isPending)
        assertEquals(Action.NONE, a.onInputViewStarted(3_000, field, true, false)) // never a second automatic start
    }

    @Test fun permissionDeniedShowsMessageOnce() {
        val a = pending()
        assertEquals(Action.SHOW_DENIED, a.onInputViewStarted(2_000, field, hasPermission = false, sensitive = false))
        assertEquals(Action.NONE, a.onInputViewStarted(3_000, field, false, false)) // no loop
    }

    @Test fun otherFieldOrAppResetsTheFlag() {
        val a = pending()
        assertEquals(Action.NONE, a.onInputViewStarted(2_000, PermissionAutoStart.fieldKey("com.chat", 8), true, false))
        assertFalse(a.isPending)
        val b = pending()
        assertEquals(Action.NONE, b.onInputViewStarted(2_000, PermissionAutoStart.fieldKey("com.other", 7), true, false))
    }

    @Test fun timeoutResetsTheFlag() {
        val a = pending()
        assertEquals(Action.NONE, a.onInputViewStarted(1_000 + PermissionAutoStart.TIMEOUT_MS + 1, field, true, false))
        assertFalse(a.isPending)
    }

    @Test fun sensitiveFieldNeverStarts() {
        val a = pending()
        assertEquals(Action.NONE, a.onInputViewStarted(2_000, field, true, sensitive = true))
        assertFalse(a.isPending)
    }

    @Test fun explicitResetDropsTheFlag() { // screen off
        val a = pending()
        a.reset()
        assertEquals(Action.NONE, a.onInputViewStarted(2_000, field, true, false))
    }

    @Test fun nothingExpectedNothingHappens() {
        assertEquals(Action.NONE, PermissionAutoStart().onInputViewStarted(2_000, field, true, false))
    }
}
