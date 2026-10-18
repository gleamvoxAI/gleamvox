// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bottom row of the letters keyboard: ?123 , language space ? . action; no ? key in other keyboards. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class BottomRowTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    private fun keyboard(inputType: Int, element: KeyboardElement): Keyboard {
        RichInputMethodManager.init(latinIME)
        val ei = EditorInfo().apply { this.inputType = inputType }
        val set = KeyboardLayoutSet.Builder(latinIME, ei, Settings.getValues())
            .setKeyboardGeometry(1080, 700)
            .setSubtype(RichInputMethodManager.getInstance().currentSubtype)
            .build()
        return set.getKeyboard(element)
    }

    private fun bottomRow(k: Keyboard): List<String> {
        val bottom = k.sortedKeys.maxOf { it.y }
        return k.sortedKeys.filter { it.y == bottom }.sortedBy { it.x }.map { it.label ?: "<${it.code}>" }
    }

    @Test fun textRow() {
        val row = bottomRow(keyboard(InputType.TYPE_CLASS_TEXT, KeyboardElement.ALPHABET))
        val space = row.indexOfFirst { it == "<32>" }
        assertTrue(space >= 0, "no space in $row")
        assertEquals(listOf("?", "."), row.subList(space + 1, space + 3), "row: $row")
    }

    @Test fun emailAndUriKeepSpecialKeys() {
        val email = bottomRow(keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, KeyboardElement.ALPHABET))
        assertTrue("@" in email && "?" in email, "email: $email")
        val uri = bottomRow(keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, KeyboardElement.ALPHABET))
        assertTrue("/" in uri && "?" in uri, "uri: $uri")
    }

    @Test fun symbolsHaveNoExtraQuestionKey() {
        val row = bottomRow(keyboard(InputType.TYPE_CLASS_TEXT, KeyboardElement.SYMBOLS))
        val space = row.indexOfFirst { it == "<32>" }
        assertTrue(row.subList(space + 1, row.size).none { it == "?" }, "symbols: $row")
    }
}
