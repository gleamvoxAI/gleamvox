// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** the smallest height of a button on the AI screens (a finger needs 48 dp) */
val MIN_BUTTON_HEIGHT = 48.dp

/**
 * Buttons under each other over the whole width (stage 5g-2: on the HONOR "Clear journal" was squeezed next to "Copy
 * details" and its label ran down two letters a line). A long label wraps inside its own button, never into a column.
 */
@Composable
fun StackedButtons(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** one button of [StackedButtons]: whole width, at least 48 dp high */
@Composable
fun WideButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Color.Unspecified, tag: String = text) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_BUTTON_HEIGHT).testTag(tag)) {
        Text(text, color = color, textAlign = TextAlign.Center)
    }
}

/**
 * "Later" and "Enable" at the end of a card: in a row while they fit, the second goes to the next line when they don't
 * (instead of squeezing one of them); at least 48 dp high.
 */
@Composable
fun EndButtons(content: @Composable FlowRowScope.() -> Unit) =
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)

/** for the buttons inside [EndButtons] */
val endButton = Modifier.heightIn(min = MIN_BUTTON_HEIGHT)
