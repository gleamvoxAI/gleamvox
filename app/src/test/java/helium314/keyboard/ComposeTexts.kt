// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/** what a Compose screen really shows, read from its semantics (no ui-test library needed) */
class ComposeScreen(val activity: ComponentActivity) {
    private fun roots(v: View = activity.window.decorView): List<RootForTest> = buildList {
        if (v is ViewRootForTest) add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) addAll(roots(v.getChildAt(i)))
    }

    private fun nodes(): List<SemanticsNode> {
        ShadowLooper.idleMainLooper()
        // dialogs are windows of their own
        val dialogs = org.robolectric.shadows.ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }
        return (roots() + dialogs.flatMap { roots(it) }).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
    }

    /** every visible text, also of open dialogs */
    fun texts(): List<String> = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    fun allText() = texts().joinToString("\n")

    /** taps the node with this test tag, or the first one showing exactly [text] */
    fun click(tag: String? = null, text: String? = null) {
        val node = nodes().firstOrNull { n ->
            (tag != null && n.config.getOrNull(SemanticsProperties.TestTag) == tag) ||
                (text != null && n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text })
        } ?: throw AssertionError("nothing to click: tag $tag, text $text\n" + allText())
        // a text inside a button has no click action itself: walk up to the clickable parent
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        (n?.config?.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("not clickable: $tag $text")).invoke()
        ShadowLooper.idleMainLooper()
    }

    /** where the button showing exactly [text] is, in px of the window (the clickable parent of the text) */
    fun buttonBounds(text: String): androidx.compose.ui.geometry.Rect {
        val node = nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
            ?: throw AssertionError("no \"$text\"\n" + allText())
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return (n ?: node).boundsInRoot
    }

    /** lets the main thread work for [ms] (animations, delays) */
    fun idleFor(ms: Long) { ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }

    /** top of the node with this tag in the window (px), after scrolling */
    fun topOf(tag: String): Float =
        (nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: throw AssertionError("no $tag")).positionInWindow.y

    /** the node with this tag exists */
    fun has(tag: String) = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /** the state description of the node with this tag ("collapsed", "expanded") */
    fun stateOf(tag: String): String? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** the window as a picture in build/shots/[name].png */
    fun shot(name: String) {
        ShadowLooper.idleMainLooper()
        val v = activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        java.io.File("build/shots").mkdirs()
        java.io.File("build/shots/$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        fun show(dark: Boolean? = null, content: @Composable () -> Unit): ComposeScreen {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            activity.setContent {
                if (dark == null) helium314.keyboard.latin.utils.Theme { content() }
                else helium314.keyboard.latin.utils.Theme(dark) { androidx.compose.material3.Surface { content() } }
            }
            ShadowLooper.idleMainLooper()
            return ComposeScreen(activity)
        }
    }
}
