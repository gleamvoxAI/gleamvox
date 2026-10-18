// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.voice.Interruption
import helium314.keyboard.latin.voice.VoiceInterruption
import helium314.keyboard.latin.voice.VoiceInterruption.Action
import helium314.keyboard.latin.voice.VoiceInterruption.Show
import helium314.keyboard.latin.voice.VoiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** stage 5zh, item 5: the screen goes off or the keyboard closes while dictating: what was said is not lost */
class VoiceInterruptionTest {
    private var now = 1_000_000L
    private val i = VoiceInterruption { now }

    @Test fun recordingIsFinishedRecognitionGoesOnRestIsCancelled() {
        assertEquals(Action.FINISH, i.onInterrupt(VoiceState.LISTENING, Interruption.SCREEN_OFF))
        assertEquals(Action.WAIT, VoiceInterruption().onInterrupt(VoiceState.PROCESSING, Interruption.HIDDEN))
        for (st in listOf(VoiceState.IDLE, VoiceState.ERROR, VoiceState.REQUESTING_PERMISSION, VoiceState.DONE))
            assertEquals(Action.CANCEL, VoiceInterruption().onInterrupt(st, Interruption.HIDDEN), st.name)
    }

    @Test fun screenOffWinsOverHidden() {
        i.onInterrupt(VoiceState.LISTENING, Interruption.HIDDEN)
        i.onInterrupt(VoiceState.PROCESSING, Interruption.SCREEN_OFF)
        i.onInterrupt(VoiceState.PROCESSING, Interruption.HIDDEN)
        assertEquals(Interruption.SCREEN_OFF, i.reason)
    }

    @Test fun screenOffTextInsertedInTheSameFieldWhenTheKeyboardIsBack() {
        i.onInterrupt(VoiceState.LISTENING, Interruption.SCREEN_OFF)
        assertFalse(i.onText("Встреча в пять.", here = false, field = "chat:1"), "the field is not there: kept")
        assertNull(i.take("chat:1"), "nothing to say while it is still running")
        i.onEnded()
        now += 60_000
        val show = i.take("chat:1")
        assertIs<Show.InsertNow>(show)
        assertEquals("Встреча в пять.", show.text)
        assertEquals(Interruption.SCREEN_OFF, show.reason)
        assertNull(i.take("chat:1"), "said once")
    }

    @Test fun anotherFieldOnlyOffersInsert() {
        i.onInterrupt(VoiceState.LISTENING, Interruption.HIDDEN)
        i.onText("hello", here = false, field = "chat:1")
        i.onEnded()
        assertIs<Show.Offer>(i.take("mail:7"))
    }

    @Test fun fieldStillThereInsertsAtOnce() {
        i.onInterrupt(VoiceState.PROCESSING, Interruption.HIDDEN)
        assertTrue(i.onText("hello", here = true, field = "chat:1"))
        i.onEnded()
        assertIs<Show.Inserted>(i.take("chat:1"))
    }

    @Test fun keptAtMostTenMinutesOnlyInMemory() {
        i.onInterrupt(VoiceState.LISTENING, Interruption.SCREEN_OFF)
        i.onText("secret", here = false, field = "chat:1")
        i.onEnded()
        now += VoiceInterruption.PENDING_MS
        assertIs<Show.Failed>(i.take("chat:1"), "too old: dropped, never inserted")
    }

    @Test fun nothingRecognizedIsToldToo() {
        i.onInterrupt(VoiceState.LISTENING, Interruption.SCREEN_OFF)
        i.onFailed()
        i.onEnded()
        assertIs<Show.Failed>(i.take("chat:1"))
    }

    @Test fun withoutInterruptionNothingChanges() {
        assertTrue(i.onText("x", here = false, field = "a"), "a normal dictation inserts at once")
        assertFalse(i.hasSomethingToSay)
    }
}
