// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import java.util.WeakHashMap

/**
 * swipe-1: glide typing without the closed library of Google (it must not be in the store version). Used when that
 * library is not there, or when the owner chose it in the personal version (to compare). The words come from the
 * main dictionary of the keyboard (read once in the background), the keys from the keyboard that is shown.
 */
object OpenGlide {
    private const val TAG = "OpenGlide"
    const val PREF_OPEN_GLIDE = "aikb_open_glide"

    /** the keyboard shown now (set before every search of words for a glide, like the native code gets its proximity info) */
    @Volatile var keyboard: Keyboard? = null

    /** true when glides are handled here and not by the closed library */
    @JvmStatic fun active(): Boolean = !JniUtils.sHaveGestureLib || forced

    @Volatile private var forced = false
    /** personal version only: the owner can switch to this engine to compare it with the library of Google */
    @JvmStatic fun setForced(on: Boolean) { forced = on }

    private class State(val name: String) {
        @Volatile var index: GlideIndex? = null; @Volatile var building = false
        /** swipe-3: the index of the personal build (the matcher of swipe-2, unchanged) */
        @Volatile var legacy: LegacyGlideIndex? = null
        @Volatile var buildMs = -1L; @Volatile var error: String? = null
    }
    private val states = WeakHashMap<Dictionary, State>()
    private fun stateOf(dict: Dictionary) = synchronized(states) { states.getOrPut(dict) { State(dict.mLocale?.toString() ?: "?") } }

    /** swipe-5: how often a later update of the strip was kept from replacing the result of a glide (both engines) */
    object StripLog {
        @Volatile var keptCount = 0; private set
        @Volatile var lastKeptAt = 0L; private set
        @JvmStatic fun kept() { keptCount++; lastKeptAt = System.currentTimeMillis() }
        fun summary() = "результат свайпа сохранён от пересчёта полосы: $keptCount раз"
    }

    /** swipe-3: how long the search of words took for the last glides (and updates during a glide), in ms */
    /**
     * swipe-3 (owner's rule 2026-10-19): the faster matcher, the timing and the merged updates are for the play build
     * only; the personal build keeps the matcher of swipe-2 behind its "Open glide engine" switch
     */
    @JvmStatic val play = helium314.keyboard.latin.BuildConfig.BUILD_TYPE == "play"

    object Timing {
        private val last = ArrayDeque<Long>()
        @Synchronized fun add(ms: Long) { last.addLast(ms); while (last.size > 100) last.removeFirst() }
        @Synchronized fun summary(): String {
            if (last.isEmpty()) return "поиск слов ещё не запускался"
            val s = last.sorted()
            return "поиск слов: медиана ${s[(s.size - 1) / 2]} мс, максимум ${s.last()} мс (${s.size} раз)"
        }
    }

    /** swipe-2: for "Проверка словарей": every dictionary of this process with its index (words, time) or why not */
    fun status(): String = statusOfIndexes() + (if (play) "; " + Timing.summary() else "") + "; " + StripLog.summary()

    private fun statusOfIndexes(): String = synchronized(states) {
        if (!active()) return "закрытая библиотека Google (открытый движок не используется)"
        if (states.isEmpty()) return "словари для свайпа ещё не читались (клавиатура не открывалась после запуска)"
        states.values.joinToString("; ") { s ->
            s.name + ": " + when {
                s.index != null -> "${s.index!!.size} слов за ${s.buildMs} мс"
                s.legacy != null -> "${s.legacy!!.size} слов за ${s.buildMs} мс"
                s.error != null -> "ошибка ${s.error}"
                s.building -> "читается"
                else -> "не читался"
            }
        }
    }

    /** reads all words of [dict] in the background (once); [read] gives them one after another */
    @JvmStatic fun warmUp(dict: ReadOnlyBinaryDictionary) {
        val state = stateOf(dict)
        if (state.index != null || state.legacy != null || state.building) return
        state.building = true
        Thread({
            try {
                val t = System.currentTimeMillis()
                val words = ArrayList<String>(); val probs = ArrayList<Byte>()
                dict.forEachWord { word, probability ->
                    if (word.length in 2..24) { words.add(word); probs.add(probability.coerceIn(0, 255).toByte()) }
                }
                if (play) state.index = GlideIndex(words.toTypedArray(), probs.toByteArray())
                else state.legacy = LegacyGlideIndex(words.toTypedArray(), probs.toByteArray())
                state.buildMs = System.currentTimeMillis() - t
                Log.i(TAG, "index of ${words.size} words in ${state.buildMs} ms")
            } catch (e: Throwable) {
                state.error = e.javaClass.simpleName
                Log.w(TAG, "could not read the words", e)
            } finally { state.building = false }
        }, "OpenGlideIndex").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    /** suggestions of one dictionary for the glide in [composedData]; empty while the words are still being read */
    @JvmStatic fun suggest(dict: ReadOnlyBinaryDictionary, composedData: ComposedData): ArrayList<SuggestedWordInfo> {
        val result = ArrayList<SuggestedWordInfo>()
        val state = stateOf(dict)
        val pointers = composedData.mInputPointers
        val n = pointers.pointerSize
        if (!play) { // personal: the matcher of swipe-2, as it was
            val legacy = state.legacy ?: run { warmUp(dict); return result }
            val keys = centersOf(keyboard ?: return result)?.let { (c, w, h) -> LegacyGlideKeys(c, w, h) } ?: return result
            for (r in LegacyGlideMatcher.match(pointers.xCoordinates, pointers.yCoordinates, n, keys, legacy))
                result.add(SuggestedWordInfo(r.word, "", r.score, SuggestedWordInfo.KIND_CORRECTION, dict,
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
            return result
        }
        val index = state.index
        if (index == null) { warmUp(dict); return result }
        val keys = keysOf(keyboard ?: return result) ?: return result
        val t0 = System.nanoTime()
        val found = GlideMatcher.match(pointers.xCoordinates, pointers.yCoordinates, n, keys, index)
        Timing.add((System.nanoTime() - t0) / 1_000_000)
        for (r in found) {
            result.add(SuggestedWordInfo(r.word, "", r.score, SuggestedWordInfo.KIND_CORRECTION, dict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        }
        return result
    }

    private var cachedKeyboard: Keyboard? = null
    private var cachedKeys: GlideKeys? = null

    /** centres of the letter keys of [kb] (cached for the keyboard that is shown) */
    @Synchronized internal fun keysOf(kb: Keyboard): GlideKeys? {
        if (kb === cachedKeyboard) return cachedKeys
        cachedKeyboard = kb
        cachedKeys = centersOf(kb)?.let { (c, w, h) -> GlideKeys(c, w, h) }
        return cachedKeys
    }

    /** the centres of the letter keys and the average key size; null: not a keyboard of letters */
    private fun centersOf(kb: Keyboard): Triple<Map<Int, FloatArray>, Float, Float>? {
        val centers = HashMap<Int, FloatArray>()
        var widthSum = 0f; var heightSum = 0f; var count = 0
        for (key in kb.sortedKeys) {
            val code = key.code
            if (code <= 0 || !Character.isLetter(code) || key.isSpacer) continue
            val folded = GlideMatcher.fold(String(Character.toChars(code)))
            if (folded.size != 1 || folded[0] != Character.toLowerCase(code)) continue
            centers.putIfAbsent(folded[0], floatArrayOf(key.x + key.width / 2f, key.y + key.height / 2f))
            widthSum += key.width; heightSum += key.height; count++
        }
        return if (count < 10) null else Triple(centers, widthSum / count, heightSum / count)
    }
}
