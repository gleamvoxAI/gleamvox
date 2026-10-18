// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ai-dict-1: the AI dictionary on the phone (layer 1). The ordinary dictionary gives candidates, the language model scores
 * them in the context of the sentence and suggests next words. The model runs on its own thread; the suggestion thread
 * waits at most [BUDGET_MS]: a late answer is kept and used at the next key press of the same word, until then the
 * dictionary's choice stands. Nothing leaves the phone; the journal holds only counts and times, never text.
 */
object AiDictionary {
    private const val TAG = "AiDictionary"
    const val PREF_ON = "aikb_ai_dict_on"
    /** "" never offered, "declined" ("Не сейчас": never asked again), "accepted" */
    const val PREF_OFFER = "aikb_ai_dict_offer"
    const val PREF_JOURNAL = "aikb_ai_dict_journal"
    const val BUDGET_MS = 150L
    const val THREADS = 4

    /** ONNX Runtime is only in the personal variant (as for Silero); the store version would need it and an asset pack */
    fun inBuild() = BuildConfig.AIKB_OPENAI

    @Volatile private var downloaded: Boolean? = null
    fun isDownloaded(context: Context) = downloaded ?: AiDictModels.current.isComplete(context).also { downloaded = it }
    /** after a download or a deletion */
    fun invalidate() { downloaded = null; release() }
    fun isOn(prefs: SharedPreferences) = prefs.getBoolean(PREF_ON, true)
    fun active(context: Context) = forceActiveForTest || inBuild() && isOn(context.prefs()) && isDownloaded(context)

    /** tests only: a fake model instead of the downloaded one */
    @Volatile private var forceActiveForTest = false
    internal fun installForTest(model: WordModel?) {
        forceActiveForTest = model != null
        engine = model?.let { Engine({}, it, LmRanker(it), {}) }
        synchronized(cache) { cache.clear() }
        erasing = false; held = false
    }

    /** set by LatinIME.onCreate */
    @Volatile var appContext: Context? = null
    /** suggestions the model added (not from the dictionary) */
    @JvmField val SOURCE = helium314.keyboard.latin.dictionary.Dictionary.PhonyDictionary("ai_dict")

    /** the text before the word being typed (set by InputLogic right before suggestions are requested) */
    @Volatile var textBefore: String = ""
    /** the language of the keyboard (set by Suggest from the dictionary's locale): tells the model the language */
    @Volatile var language: String = ""
        set(value) { field = when (value) { "in" -> "id"; "iw" -> "he"; else -> value } } // old Java codes of Android
    /** set by LatinIME: shows the suggestions again when the model answered after the budget */
    @Volatile var onLateResult: (() -> Unit)? = null

    // ux-4 (Backspace was slow): the suggestion update runs on the thread that the keyboard waits for, so waiting for the
    // model there stalls the key. While the person erases, the model is never waited for: only an answer that is already
    // known is used; one request starts after a pause of [ERASE_PAUSE_MS] (a new key press cancels the old one), and
    // none at all while the key is held.
    const val ERASE_PAUSE_MS = 150L
    @Volatile private var erasing = false
    @Volatile private var held = false
    private val debounce = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "ai-dict-pause").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var debounced: ScheduledFuture<*>? = null

    /** Backspace was pressed ([repeat]: the key is held) */
    @JvmStatic fun onErase(repeat: Boolean) {
        erasing = true
        held = repeat
        synchronized(debounce) { debounced?.cancel(false); debounced = null }
        if (repeat) {
            // when the repeat stops, the model gets its turn after a pause (the strip is shown again then)
            synchronized(debounce) {
                debounced = debounce.schedule({ held = false; onLateResult?.invoke() }, ERASE_PAUSE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** any other key: the model is waited for again, within the budget (ai-dict-1) */
    @JvmStatic fun onTyped() {
        if (!erasing && !held) return
        erasing = false; held = false
        synchronized(debounce) { debounced?.cancel(false); debounced = null }
    }

    /** true while the model must not be waited for */
    @JvmStatic fun isErasing() = erasing
    /** true while Backspace is held: the model is not used and its context is not even read */
    @JvmStatic fun isHeld() = held

    private class Engine(val close: () -> Unit, val words: WordModel, val ranker: LmRanker, val setLanguage: (String) -> Unit)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-dict").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }
    @Volatile private var engine: Engine? = null
    @Volatile private var loading: Future<*>? = null
    @Volatile var loadError: String? = null; private set
    @Volatile var loadMs: Long = -1; private set

    private val cache = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > 64
    }
    private val pending = HashMap<String, Future<*>>()

    /** starts loading the model in the background (a few seconds); suggestions use the dictionary meanwhile */
    fun warmUp(context: Context) {
        if (engine != null || loading != null || !active(context)) return
        val app = context.applicationContext
        synchronized(this) {
            if (engine != null || loading != null) return
            loading = executor.submit {
                val t0 = System.nanoTime()
                try {
                    val m = AiDictModels.current
                    val tok = BpeTokenizer.load(m.file(app, m.tokenizerFile))
                    val lm = OnnxLm(m.file(app, m.modelFile), THREADS)
                    val words = WordLm(tok, lm)
                    engine = Engine({ lm.close() }, words, LmRanker(words), { words.language = it })
                    loadMs = (System.nanoTime() - t0) / 1_000_000
                    loadError = null
                    Log.i(TAG, "model loaded in $loadMs ms")
                } catch (t: Throwable) {
                    loadError = t.toString()
                    Log.w(TAG, "model not loaded", t)
                } finally {
                    loading = null
                }
            }
        }
    }

    /** frees the memory of the model (switched off, deleted) */
    fun release() {
        executor.submit { engine?.close?.invoke(); engine = null; synchronized(cache) { cache.clear() } }
    }

    /** waits up to the budget for [job]; late results land in the cache under [key] */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ask(context: Context, key: String, job: (Engine) -> T): T? {
        synchronized(cache) { cache[key]?.let { Journal.cached(); return it as T } }
        val e = engine ?: run { warmUp(context); return null }
        if (erasing) {
            if (!held) synchronized(debounce) {
                // one request after a pause; a new key press (this call again, or any key) cancels the waiting one
                debounced?.cancel(false)
                debounced = debounce.schedule(Runnable {
                    synchronized(pending) {
                        if (pending[key] == null) pending[key] = executor.submit {
                            try {
                                val r = job(e)
                                synchronized(cache) { cache[key] = r }
                                onLateResult?.invoke()
                            } finally { synchronized(pending) { pending.remove(key) } }
                        }
                    }
                }, ERASE_PAUSE_MS, TimeUnit.MILLISECONDS)
            }
            return null
        }
        val gaveUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val f = synchronized(pending) {
            pending[key] ?: executor.submit<T> {
                try {
                    job(e).also { r ->
                        synchronized(cache) { cache[key] = r }
                        // the suggestion thread did not wait for it: ask the keyboard to show the suggestions again
                        if (gaveUp.get()) onLateResult?.invoke()
                    }
                } finally { synchronized(pending) { pending.remove(key) } }
            }.also { pending[key] = it }
        }
        val t0 = System.nanoTime()
        return try {
            (f.get(BUDGET_MS, TimeUnit.MILLISECONDS) as T).also { Journal.inTime((System.nanoTime() - t0) / 1_000_000) }
        } catch (_: TimeoutException) {
            gaveUp.set(true)
            Journal.late(); null
        } catch (t: Throwable) {
            Log.w(TAG, "model failed", t); Journal.failed(); null
        }
    }

    /** scores the dictionary's candidates for [typed] in the context; null = use the dictionary as it is */
    fun rerank(typed: String, candidates: List<String>, typedIsWord: Boolean): LmRanker.Result? {
        val context = appContext ?: return null
        if (!active(context) || typed.length < 2 || held) return null
        val before = textBefore
        val lang = language
        val words = candidates.filter { it.isNotEmpty() && it.any(Char::isLetter) }.take(LmRanker.MAX_CANDIDATES)
        if (words.isEmpty()) return null
        return ask(context, "r\u0000$lang\u0000$before\u0000$typed\u0000${words.joinToString("\u0001")}") {
            it.setLanguage(lang)
            it.ranker.rank(before, typed, words, typedIsWord)
        }
    }

    /** next words after the text before the cursor, merged with the dictionary's predictions; best first */
    fun predict(dictionaryWords: List<String>): List<String>? {
        val context = appContext ?: return null
        if (!active(context) || held) return null
        val before = textBefore
        val lang = language
        val dict = dictionaryWords.filter { it.isNotEmpty() && it.any(Char::isLetter) }.take(PREDICT_DICT)
        return ask(context, "p\u0000$lang\u0000$before\u0000${dict.joinToString("\u0001")}") { e ->
            e.setLanguage(lang)
            val own = e.words.nextWords(before, PREDICT_OWN)
            val scored = HashMap<String, Double>()
            own.forEach { (w, lp) -> scored[w] = lp }
            val missing = dict.filter { it !in scored }
            if (missing.isNotEmpty()) e.words.wordLogProbs(before, missing).forEachIndexed { i, lp -> scored[missing[i]] = lp + DICT_BONUS }
            scored.entries.sortedByDescending { it.value }.map { it.key }
        }
    }

    private const val PREDICT_OWN = 4
    private const val PREDICT_DICT = 5
    /** a word the dictionary also predicts (from its pairs of words) gets a little more */
    private const val DICT_BONUS = 0.5

    /** counts and times only (no words): shown on the AI dictionary screen */
    object Journal {
        @Volatile var calls = 0; private set
        @Volatile var late = 0; private set
        @Volatile var cachedHits = 0; private set
        @Volatile var failed = 0; private set
        @Volatile var changed = 0; private set
        @Volatile var totalMs = 0L; private set
        @Volatile var maxMs = 0L; private set
        private var dirty = 0

        @Synchronized fun inTime(ms: Long) { calls++; totalMs += ms; if (ms > maxMs) maxMs = ms; dirty++ }
        @Synchronized fun late() { calls++; late++; dirty++ }
        @Synchronized fun cached() { cachedHits++ }
        @Synchronized fun failed() { calls++; failed++ }
        /** the model's choice (autocorrection or first suggestion) differs from the dictionary's */
        @Synchronized fun changedChoice() { changed++; dirty++ }

        fun summary(): String {
            val avg = if (calls - late - failed > 0) totalMs / (calls - late - failed) else 0
            return "ответов модели: $calls (в срок ${calls - late - failed}, поздно $late, ошибок $failed, из памяти $cachedHits); " +
                "среднее $avg мс, максимум $maxMs мс; модель изменила выбор словаря: $changed раз"
        }

        fun save(prefs: SharedPreferences) { if (dirty > 0) { prefs.edit { putString(PREF_JOURNAL, "$calls,$late,$cachedHits,$failed,$changed,$totalMs,$maxMs") }; dirty = 0 } }

        fun load(prefs: SharedPreferences) {
            val p = prefs.getString(PREF_JOURNAL, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
            if (p.size < 7) return
            calls = p[0].toInt(); late = p[1].toInt(); cachedHits = p[2].toInt(); failed = p[3].toInt(); changed = p[4].toInt()
            totalMs = p[5]; maxMs = p[6]
        }

        @Synchronized fun reset(prefs: SharedPreferences) {
            calls = 0; late = 0; cachedHits = 0; failed = 0; changed = 0; totalMs = 0; maxMs = 0
            prefs.edit { remove(PREF_JOURNAL) }
        }
    }
}
