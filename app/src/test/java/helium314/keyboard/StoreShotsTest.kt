// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/**
 * play-4: the pictures for the Google Play page (1080 x 2400, portrait). The keyboard in the pictures is the real
 * keyboard view of the app (the same layouts, theme, strip and keys as on the phone), drawn on the JVM; only the plain
 * "note" screen around it and the caption are drawn here. Runs only from tools/aikb/build.sh shots
 * (as the "play" build type: the names and strings of the store version), result in docs/design/store/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class], qualifiers = "w360dp-h800dp-port-night-480dpi")
class StoreShotsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val W = 1080
    private val H = 2400
    private val out = File("../docs/design/store/screenshots").apply { mkdirs() }

    private fun enabled() = Assume.assumeTrue(System.getProperty("aikb.storeShots").orEmpty().isNotEmpty())

    private fun info(word: String, kind: Int = SuggestedWordInfo.KIND_CORRECTION) =
        SuggestedWordInfo(word, "", 100, kind, Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun forceLayoutAll(v: View) { v.forceLayout(); if (v is ViewGroup) for (i in 0 until v.childCount) forceLayoutAll(v.getChildAt(i)) }

    private fun layoutAll(view: View) {
        forceLayoutAll(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun subtype(lang: String) = RichInputMethodSubtype.get(SubtypeSettings.getResourceSubtypesForLocale(lang.constructLocale()).first())

    /** the real input view of the app: letters of [lang] (or the symbols / emoji panel), words in the strip */
    private fun keyboard(lang: String, panel: String = "letters", words: List<SuggestedWordInfo>? = null, typed: SuggestedWordInfo? = null,
                         autoCorrect: Boolean = false): Bitmap {
        latinIME.prefs().edit { clear(); putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true) }
        Settings.getInstance().loadSettings(latinIME)
        RichInputMethodManager.init(latinIME)
        helium314.keyboard.latin.ai.AiAssist.inBuild = { false }
        helium314.keyboard.latin.suggestions.StripGlowDrawable.frozenSeconds = 0f
        // pictures show the strip at rest, not in the middle of its fade-in
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        val ks = KeyboardSwitcher.getInstance()
        val view = ks.onCreateInputView(latinIME, false)
        ks.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)
        view.findViewById<View>(R.id.strip_container).visibility = View.VISIBLE
        val strip = view.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        strip.visibility = View.VISIBLE
        layoutAll(view)
        val kv = ks.mainKeyboardView
        val element = when (panel) { "symbols" -> KeyboardElement.SYMBOLS; else -> KeyboardElement.ALPHABET }
        val height = view.findViewById<View>(R.id.keyboard_view).height
        kv.setKeyboard(KeyboardLayoutSet.Builder(latinIME, EditorInfo(), Settings.getValues())
            .setKeyboardGeometry(W, height).setSubtype(subtype(lang)).build().getKeyboard(element))
        if (panel == "emoji") {
            ks.setEmojiKeyboard()
            layoutAll(view)
            // the font of the JVM is older than the one of the phone: a category without the newest emoji (a square instead of a picture)
            fun tab(v: View): View? {
                if (v.tag == helium314.keyboard.keyboard.emoji.EmojiCategory.Category.ACTIVITIES) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) tab(v.getChildAt(i))?.let { return it }
                return null
            }
            tab(view)!!.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            layoutAll(view)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val pager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.emoji_pager)
            pager.setCurrentItem(6, false)
            val rv = pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
            rv.scrollToPosition(6)
            for (i in 0..3) { layoutAll(view); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
            println("PAGER first=${(rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()} rvchildren=${rv.childCount}")
            println("PAGER item=${pager.currentItem} count=${pager.adapter?.itemCount} size=${pager.width}x${pager.height} tabsFood=${tab(view)}")
        }
        if (words != null) {
            val t = typed
            strip.setSuggestions(SuggestedWords(ArrayList(words), null, t, false, autoCorrect, false,
                if (t == null) SuggestedWords.INPUT_STYLE_PREDICTION else SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER), false)
        }
        layoutAll(view)
        // the words keep the very wide text layout of their first measure (the view is not on a screen): lay them out again
        fun relayout(v: View) {
            if (v is android.widget.TextView && v.parent === strip.findViewById<ViewGroup>(R.id.suggestions_strip)) { v.isSingleLine = false; v.maxLines = 1; v.text = v.text.toString() }
            if (v is ViewGroup) for (i in 0 until v.childCount) relayout(v.getChildAt(i))
        }
        relayout(strip)
        layoutAll(view)
        view.findViewById<View>(R.id.backgroundGatheringIndicator)?.visibility = View.GONE // the app hides it too (LatinIME)
        fun dump(v: View) {
            if (v is android.widget.TextView && v.text.isNotEmpty()) {
                val b = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888)
                v.draw(Canvas(b))
                var n = 0; for (y in 0 until b.height) for (x in 0 until b.width) if ((b.getPixel(x, y) ushr 24) > 0) n++
                println("DUMP ${v.text} at ${v.left},${v.top} ${v.width}x${v.height} alpha=${v.alpha} shown=${v.isShown} pixels=$n layout=${v.layout?.text} lines=${v.lineCount} scaleX=${v.textScaleX} paintAlpha=${v.paint.alpha} color=${Integer.toHexString(v.paint.color)} size=${v.paint.textSize} lw=${v.layout?.width} tf=${v.typeface} tl=${v.textLocale} ems=${v.paint.measureText(v.text.toString())} gravity=${v.gravity} align=${v.layout?.alignment} lineLeft=${v.layout?.getLineLeft(0)} lineRight=${v.layout?.getLineRight(0)} pad=${v.paddingLeft},${v.paddingRight} ellipsize=${v.ellipsize} maxW=${v.maxWidth} singleLine=${v.maxLines} bg=${v.background} fontScale=${v.resources.configuration.fontScale} clip=${v.clipBounds}")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i))
        }
        dump(strip)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, center: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }
        c.drawText(s, x, y, p)
    }

    /** caption on top, plain note screen with [typedText] in the middle, [content] (keyboard or settings) at the bottom */
    private fun frame(name: String, caption: String, typedText: String?, content: Bitmap, dark: Boolean = true) {
        val bg = if (dark) 0xFF121318.toInt() else 0xFFF3F4F8.toInt()
        val ink = if (dark) 0xFFE6E7EE.toInt() else 0xFF1B1C22.toInt()
        val sub = if (dark) 0xFF9A9CA8.toInt() else 0xFF6A6C78.toInt()
        val card = if (dark) 0xFF1D1E24.toInt() else 0xFFFFFFFF.toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        var size = 74f
        val probe = Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        while (size > 40f && run { probe.textSize = size; probe.measureText(caption) } > W - 120f) size -= 2f
        text(c, caption, W / 2f, 170f, size, ink, bold = true, center = true)
        if (typedText != null) {
            val top = 290f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = card
            c.drawRoundRect(RectF(60f, top, W - 60f, top + 360f), 48f, 48f, p)
            text(c, "New note", 110f, top + 90f, 40f, sub)
            text(c, typedText, 110f, top + 210f, 62f, ink)
            p.color = 0xFFF5C542.toInt() // the cursor in the accent colour of the theme
            val w = Paint().apply { textSize = 62f; typeface = Typeface.DEFAULT }.measureText(typedText)
            c.drawRoundRect(RectF(114f + w, top + 150f, 120f + w, top + 232f), 3f, 3f, p)
        }
        val keyTop = H - content.height - 64
        c.drawBitmap(content, 0f, keyTop.toFloat(), null)
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sub }
        c.drawRoundRect(RectF(W / 2f - 130f, H - 36f, W / 2f + 130f, H - 28f), 4f, 4f, pill)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        collage()
        println("SHOT $name ${bmp.width}x${bmp.height} keyboard ${content.width}x${content.height}")
    }

    private val names = listOf("01_russian", "02_english", "03_typo", "04_emoji", "05_symbols", "06_settings")

    /** all six pictures on one sheet for the owner (rebuilt by every test; the last one sees all six) */
    private fun collage() {
        val files = names.map { File(out, "$it.png") }
        if (files.any { !it.exists() }) return
        val cw = 432; val ch = 960; val gap = 36
        val sheet = Bitmap.createBitmap(3 * cw + 4 * gap, 2 * ch + 3 * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF2A2B33.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        files.forEachIndexed { i, f ->
            val b = android.graphics.BitmapFactory.decodeFile(f.path)
            val x = gap + (i % 3) * (cw + gap); val y = gap + (i / 3) * (ch + gap)
            c.drawBitmap(b, null, RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), paint)
        }
        File(out, "collage.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shot1Russian() {
        enabled()
        val kb = keyboard("ru", words = listOf(info("Всё"), info("Я"), info("Что")))
        frame("01_russian", "Russian, English and more", "Привет! Как дела?", kb)
    }

    @Config(qualifiers = "w360dp-h800dp-port-notnight-480dpi")
    @Test fun shot2English() {
        enabled()
        val kb = keyboard("en_US", words = listOf(info("I"), info("Just"), info("Thanks")))
        frame("02_english", "Smart suggestions, on your device", "Hi! How are you today?", kb, dark = false)
    }

    @Test fun shot3Typo() {
        enabled()
        val kb = keyboard("en_US", words = listOf(info("hapy", SuggestedWordInfo.KIND_TYPED), info("happy"), info("hapy", SuggestedWordInfo.KIND_TYPED), info("happen")),
            typed = info("hapy", SuggestedWordInfo.KIND_TYPED), autoCorrect = true)
        frame("03_typo", "Fixes typos as you type", "I'm so hapy", kb)
    }

    @Test fun shot4Emoji() {
        enabled()
        val kb = keyboard("en_US", panel = "emoji")
        frame("04_emoji", "Emoji and symbols", "Have a great day!", kb)
    }

    @Test fun shot5Symbols() {
        enabled()
        val kb = keyboard("en_US", panel = "symbols")
        frame("05_symbols", "Numbers and symbols", "Call me at 555-0123", kb)
    }

    @Config(qualifiers = "w360dp-h690dp-port-night-480dpi")
    @Test fun shot6Settings() {
        enabled()
        // the cards of the new features were shown already
        latinIME.prefs().edit { clear(); putStringSet(helium314.keyboard.settings.features.AikbFeatures.PREF_SEEN, setOf("contacts", "voice", "ai", "screenshot")) }
        // the keyboard is on and chosen: no first-start wizard, the main screen of the settings
        val imm = latinIME.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val info = imm.inputMethodList.first()
        org.robolectric.Shadows.shadowOf(imm).setEnabledInputMethodInfoList(listOf(info))
        android.provider.Settings.Secure.putString(latinIME.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, info.id)
        val a = Robolectric.buildActivity(helium314.keyboard.settings.SettingsActivity::class.java,
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)).setup().get()
        for (i in 0..5) { org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2070, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, W, 2070)
        for (i in 0..5) { org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val bmp = Bitmap.createBitmap(W, 2070, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        frame("06_settings", "Private: no internet access", null, bmp)
    }
}
