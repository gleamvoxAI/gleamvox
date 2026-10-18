// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colors of the result banners, light and dark: green for success like "All connected", red for a failure */
object ResultBannerColors {
    class Palette(val container: Long, val content: Long)

    fun of(success: Boolean, dark: Boolean): Palette = when {
        success && !dark -> Palette(0xFF2E7D32, 0xFFFFFFFF) // the green of "All connected"
        success -> Palette(0xFF1E5A2B, 0xFFE3F5E5)
        !dark -> Palette(0xFFC62828, 0xFFFFFFFF)
        else -> Palette(0xFF8C1D18, 0xFFFFDAD6)
    }

    /** WCAG contrast ratio of two opaque colors (0xAARRGGBB) */
    fun contrast(a: Long, b: Long): Double {
        fun channel(c: Long, shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        fun lum(c: Long) = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)
        val la = lum(a); val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}

/**
 * A result that must catch the eye (owner's request, stage 5b): a green banner with a check mark or a red one with
 * "!", a large title, a small detail line and optionally "Copy details". It is announced by screen readers
 * ([spoken], polite live region) and stays until the caller removes it.
 */
@Composable
fun ResultBanner(success: Boolean, title: String, detail: String?, spoken: String, copyText: String? = null) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = ResultBannerColors.of(success, dark)
    val content = Color(palette.content)
    ElevatedCard(Modifier.fillMaxWidth().testTag(if (success) "result_ok" else "result_failed"),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(palette.container), contentColor = content)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (success) "✓  " else "!  ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (copyText != null) {
                val ctx = LocalContext.current
                TextButton(onClick = {
                    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AI Keyboard", copyText))
                }) { Text(stringResource(R.string.openai_copy_details), color = content) }
            }
        }
    }
}
