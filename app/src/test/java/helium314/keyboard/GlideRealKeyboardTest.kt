// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.glide.GlideIndex
import helium314.keyboard.latin.glide.GlideKeys
import helium314.keyboard.latin.glide.GlideMatcher
import helium314.keyboard.latin.glide.OpenGlide
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * swipe-2 (the owner on Gleamvox 4436: the trail is drawn, no words): the open glide typing with the REAL keyboards of
 * the app (Russian and English, 1084 px wide like the HONOR phone): the keys are taken by the same code as on the phone
 * ([OpenGlide.keysOf]), a finger path is drawn through the centres of the keys with a shaky hand, and the matcher looks
 * the word up in the real word list of the dictionary (from the tools folder; skipped where it is not).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class], qualifiers = "w361dp-h804dp-port-night-480dpi")
class GlideRealKeyboardTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    private fun keys(lang: String): GlideKeys {
        latinIME.prefs().edit { clear(); putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true) }
        Settings.getInstance().loadSettings(latinIME)
        RichInputMethodManager.init(latinIME)
        val ks = KeyboardSwitcher.getInstance()
        val view = ks.onCreateInputView(latinIME, false)
        ks.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)
        view.measure(View.MeasureSpec.makeMeasureSpec(1084, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2412, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val height = view.findViewById<View>(R.id.keyboard_view).height
        val subtype = RichInputMethodSubtype.get(SubtypeSettings.getResourceSubtypesForLocale(lang.constructLocale()).first())
        val kb = KeyboardLayoutSet.Builder(latinIME, EditorInfo(), Settings.getValues())
            .setKeyboardGeometry(1084, height).setSubtype(subtype).build().getKeyboard(KeyboardElement.ALPHABET)
        return OpenGlide.keysOf(kb)!!
    }

    private fun gauss(rnd: Random) = sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())

    /** a hand drawn path through the letters: missed by up to ~[shake] key, corners cut, a point every ~10 px */
    private fun path(word: String, keys: GlideKeys, rnd: Random, shake: Double = 0.2): Pair<IntArray, IntArray> {
        val way = GlideMatcher.fold(word).mapIndexed { i, l ->
            val c = keys.centers[l]!!
            val j = if (i == 0) 0.4 else 1.0
            floatArrayOf((c[0] + gauss(rnd) * shake * keys.keyWidth * j).toFloat(), (c[1] + gauss(rnd) * shake * keys.keyHeight * j).toFloat())
        }
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (i in 0 until way.size - 1) {
            val p0 = way[maxOf(0, i - 1)]; val p1 = way[i]; val p2 = way[i + 1]; val p3 = way[minOf(way.size - 1, i + 2)]
            val steps = maxOf(2, (hypot((p2[0] - p1[0]).toDouble(), (p2[1] - p1[1]).toDouble()) / 10).toInt())
            for (s in 0 until steps) {
                val t = s / steps.toDouble()
                fun cr(a: Float, b: Float, c: Float, d: Float) = 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t)
                xs.add(cr(p0[0], p1[0], p2[0], p3[0]).toInt()); ys.add(cr(p0[1], p1[1], p2[1], p3[1]).toInt())
            }
        }
        xs.add(way.last()[0].toInt()); ys.add(way.last()[1].toInt())
        return xs.toIntArray() to ys.toIntArray()
    }

    private fun index(file: String): GlideIndex {
        val f = File(System.getProperty("user.home") + "/.local/ai-keyboard-tools/models/wordlists/" + file)
        Assume.assumeTrue("no word list $f", f.isFile)
        val e = f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
            .filter { it.first.length in 2..24 && it.second > 0 }.distinctBy { it.first }
        return GlideIndex(e.map { it.first }.toTypedArray(), ByteArray(e.size) { e[it].second.coerceIn(0, 255).toByte() })
    }

    private fun check(lang: String, file: String, words: List<String>) {
        val keys = keys(lang)
        val alphabet = words.flatMap { GlideMatcher.fold(it).toList() }.toSet()
        assertTrue(alphabet.all { it in keys.centers }, "$lang: every letter has a key: ${alphabet.filter { it !in keys.centers }.map { it.toChar() }}")
        val idx = index(file)
        val rnd = Random(11)
        var top1 = 0; var top3 = 0; var n = 0
        val missed = ArrayList<String>()
        repeat(3) { // three tries of every word
            for (w in words) {
                val (xs, ys) = path(w, keys, rnd)
                val r = GlideMatcher.match(xs, ys, xs.size, keys, idx).map { it.word.lowercase() }
                n++
                if (r.firstOrNull() == w) top1++ else missed.add("$w -> ${r.take(3)}")
                if (w in r.take(3)) top3++
            }
        }
        println("GLIDE real $lang: keys ${keys.centers.size}, key ${keys.keyWidth.toInt()}x${keys.keyHeight.toInt()} px, first ${100 * top1 / n}%, in three ${100 * top3 / n}% of $n; missed: ${missed.take(12)}")
        assertTrue(top1 * 100 >= 70 * n, "$lang first ${100 * top1 / n}%: $missed")
    }

    @Test fun russian() = check("ru", "ru_old.tsv", listOf("привет", "спасибо", "хорошо", "сегодня", "когда", "может", "сейчас", "только",
        "потом", "человек", "работа", "время", "дома", "вчера", "завтра", "пожалуйста", "конечно", "давай", "сделать", "понял", "встреча", "деньги", "машина"))

    @Test fun english() = check("en_US", "en_old.tsv", listOf("hello", "thanks", "keyboard", "that", "with", "have", "this", "from", "they",
        "would", "about", "there", "people", "today", "please", "tomorrow", "meeting", "really", "should", "great", "think", "where", "because"))
}
