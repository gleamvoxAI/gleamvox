// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The yellow star logo is the icon of the app and of the keyboard (docs/DESIGN.md, 5е-2). Pictures in app/build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val gold = 0xFFF6C945.toInt()
    private val background = 0xFF121318.toInt()
    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))
    private fun Document.elements(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test fun manifestUsesTheLauncherIcons() {
        val doc = parse("src/main/AndroidManifest.xml")
        val app = doc.elements("application").single()
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(androidNs, "icon"))
        assertEquals("@mipmap/ic_launcher_round", app.getAttributeNS(androidNs, "roundIcon"))
        // the keyboard in the list of input methods
        val ime = doc.elements("service").single { it.getAttributeNS(androidNs, "name") == "LatinIME" }
        assertEquals("@mipmap/ic_launcher", ime.getAttributeNS(androidNs, "icon"))
        assertEquals("@mipmap/ic_launcher_round", ime.getAttributeNS(androidNs, "roundIcon"))
        assertEquals("@string/english_ime_name", ime.getAttributeNS(androidNs, "label"))
    }

    @Test fun adaptiveIconHasBackgroundGoldStarAndMonochrome() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = parse("src/main/res/mipmap-anydpi-v26/$name.xml")
            assertEquals("@color/ic_launcher_background", icon.elements("background").single().getAttributeNS(androidNs, "drawable"))
            assertEquals("@drawable/ic_launcher_foreground", icon.elements("foreground").single().getAttributeNS(androidNs, "drawable"))
            assertEquals("@drawable/ic_launcher_monochrome", icon.elements("monochrome").single().getAttributeNS(androidNs, "drawable"))
        }
        assertTrue("#121318" in File("src/main/res/values/ic_launcher_background.xml").readText())
        val fg = parse("src/main/res/drawable/ic_launcher_foreground.xml").elements("path").map { it.getAttributeNS(androidNs, "fillColor") }
        assertEquals(listOf("#F6C945", "#F6C945"), fg, "two gold sparks")
        val mono = parse("src/main/res/drawable/ic_launcher_monochrome.xml").elements("path").map { it.getAttributeNS(androidNs, "fillColor") }
        assertEquals(2, mono.size)
        // the same shapes as the logo sources
        val logo = parse("../docs/design/logo/android/ic_launcher_foreground.xml").elements("path").map { it.getAttributeNS(androidNs, "pathData") }
        assertEquals(logo, parse("src/main/res/drawable/ic_launcher_foreground.xml").elements("path").map { it.getAttributeNS(androidNs, "pathData") })
    }

    @Test fun legacyPngsInEveryDensity() {
        for ((dpi, px) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192))
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val f = File("src/main/res/mipmap-$dpi/$name.png")
                val bytes = f.readBytes()
                val w = java.nio.ByteBuffer.wrap(bytes, 16, 8).int
                val h = java.nio.ByteBuffer.wrap(bytes, 20, 4).int
                assertEquals(px to px, w to h, f.path)
                val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
                // dark background on the left below the big spark, gold in the middle of the big spark
                val c = bmp.getPixel(px / 2, px / 2)
                assertTrue(near(bmp.getPixel((px * 0.42).toInt(), (px * 0.53).toInt()), gold), "gold in ${f.path}: ${Integer.toHexString(c)}")
                assertTrue(near(bmp.getPixel((px * 0.2).toInt(), (px * 0.25).toInt()), background), "background in ${f.path}")
            }
    }

    private fun near(a: Int, b: Int, t: Int = 10) = listOf(16, 8, 0).all { kotlin.math.abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= t }

    @Test @Config(qualifiers = "night") fun adaptiveIconDrawnDark() = drawIcon("app_icon_dark")

    @Test @Config(qualifiers = "notnight") fun adaptiveIconDrawnLight() = drawIcon("app_icon_light")

    /** the icon does not depend on the theme; the themed (monochrome) icon is drawn on the colors of the system */
    private fun drawIcon(name: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val icon = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
        assertIs<AdaptiveIconDrawable>(icon)
        val size = 432
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(b))
        File("build/shots").mkdirs()
        File("build/shots/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // the layers are 108 × 108 of which the middle 72 are shown: design v is at (v − 18) / 72 of the icon
        fun at(v: Double) = ((v - 18) / 72 * size).toInt()
        // the big spark sits a little left of and below the center, the small one at the top right
        assertTrue(near(b.getPixel(at(50.0), at(58.0)), gold), "big spark")
        assertTrue(near(b.getPixel(at(72.0), at(36.0)), gold), "small spark")
        assertTrue(near(b.getPixel(at(70.0), at(70.0)), background), "background")
        // themed icon (Android 13+): the system tints the monochrome layer
        val mono = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_monochrome)!!.mutate()
        val night = name.endsWith("dark")
        val themed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(themed)
        c.drawColor(if (night) 0xFF2D3138.toInt() else 0xFFDCE3F0.toInt())
        mono.setTint(if (night) 0xFFC5D6F5.toInt() else 0xFF1E3A66.toInt())
        mono.setBounds(0, 0, size, size)
        mono.draw(c)
        File("build/shots/${name}_themed.png").outputStream().use { themed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(near(themed.getPixel((size * 50 / 108.0).toInt(), (size * 58 / 108.0).toInt()), if (night) 0xFFC5D6F5.toInt() else 0xFF1E3A66.toInt()))
    }
}
