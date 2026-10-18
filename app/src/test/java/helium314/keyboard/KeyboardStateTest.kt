// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.KeyboardState
import helium314.keyboard.keyboard.internal.ShiftMode
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression test for the layout switching order of AI Keyboard: ?123 -> numpad 3x3 -> !?# symbols, ABC -> letters. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class KeyboardStateTest {
    private val shown = mutableListOf<String>()
    private val actions = object : KeyboardState.SwitchActions {
        override fun setAlphabetKeyboard(shiftMode: ShiftMode) { shown.add("ALPHABET") }
        override fun setEmojiKeyboard() { shown.add("EMOJI") }
        override fun setClipboardKeyboard() { shown.add("CLIPBOARD") }
        override fun setNumpadKeyboard() { shown.add("NUMPAD") }
        override fun setDpadKeyboard() { shown.add("DPAD") }
        override fun setSymbolsKeyboard() { shown.add("SYMBOLS") }
        override fun setSymbolsShiftedKeyboard() { shown.add("SYMBOLS_SHIFTED") }
        override fun startDoubleTapShiftKeyTimer() {}
        override fun popDoubleTapShiftKeyTimer() = false
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun setOneHandedModeEnabled(enabled: Boolean) {}
        override fun switchOneHandedMode() {}
        override fun setFloatingKeyboardEnabled(enabled: Boolean) {}
    }
    private lateinit var state: KeyboardState

    @BeforeTest fun setUp() {
        Robolectric.setupService(LatinIME::class.java) // initializes Settings
        shown.clear()
        state = KeyboardState(actions)
        state.onLoadKeyboard(0, null, false)
        shown.clear()
    }

    /** same calls and order as PointerTracker for a normal tap: press, key event (code input), release */
    private fun tap(code: Int) {
        state.onPressKey(code, 1, 0, null)
        state.onEvent(Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), 0, null)
        state.onReleaseKey(code, false, 0, null)
    }

    /**
     * What really happened on the phone: the layout changed while the finger was down, PointerTracker looked up the key
     * under the finger in the new layout and sent that one on release (ABC of the number panel sits where ?123 is).
     */
    private fun tapWhereKeyUnderFingerChangesWithLayout(pressed: Int, keyAfterLayoutChange: Int) {
        state.onPressKey(pressed, 1, 0, null)
        val code = if (shown.isNotEmpty()) keyAfterLayoutChange else pressed // layout already changed on press?
        state.onEvent(Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), 0, null)
        state.onReleaseKey(code, false, 0, null)
    }

    @Test fun layoutDoesNotChangeOnPressOfSymbolKey() {
        state.onPressKey(KeyCode.SYMBOL_ALPHA, 1, 0, null)
        assertEquals(emptyList(), shown) // otherwise the key under the finger would be replaced (the bug)
    }

    @Test fun numpadSurvivesKeyUnderFingerBeingAbc() {
        tapWhereKeyUnderFingerChangesWithLayout(KeyCode.SYMBOL_ALPHA, KeyCode.ALPHA)
        assertEquals("NUMPAD", shown.last())
    }

    @Test fun symbolKeyFromLettersOpensNumpad() {
        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals("NUMPAD", shown.last())
        assertEquals(listOf("NUMPAD"), shown)
    }

    @Test fun symbolKeyWithFinishSlidingStillNumpadWhenNotSliding() {
        tap(KeyCode.SYMBOL_ALPHA)
        state.onFinishSlidingInput(0, null) // PointerTracker calls this after every touch sequence
        assertEquals("NUMPAD", shown.last())
    }

    @Test fun numpadSymbolsKeyOpensSymbols() {
        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SYMBOL)
        assertEquals("SYMBOLS", shown.last())
    }

    @Test fun abcReturnsToLettersFromNumpadAndSymbols() {
        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.ALPHA)
        assertEquals("ALPHABET", shown.last())
        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SYMBOL)
        tap(KeyCode.SYMBOL_ALPHA) // "ABC" in symbols is the same key as ?123 in letters
        assertEquals("ALPHABET", shown.last())
    }

    @Test fun numpadKeyInSymbolsReturnsToNumpad() {
        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SYMBOL)
        tap(KeyCode.NUMPAD)
        assertEquals("NUMPAD", shown.last())
    }

    @Test fun longPressOnLettersKeepsNumpad() {
        state.onPressKey(KeyCode.SYMBOL_ALPHA, 1, 0, null)
        state.onLongPressAlphaSymbolForNumpad()
        state.onReleaseKey(KeyCode.SYMBOL_ALPHA, false, 0, null)
        assertEquals("NUMPAD", shown.last())
    }
}
