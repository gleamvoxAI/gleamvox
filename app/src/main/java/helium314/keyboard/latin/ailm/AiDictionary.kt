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

    /**
     * the text before the word being typed (set by InputLogic right before suggestions are requested). ai-dict-3: a new
     * context (the first letter of a new word, or the keyboard shown in a field) is computed by the model at once in the
     * background, so the letters of the word only need the candidates scored
     */
    @Volatile var textBefore: String = ""
        set(value) { val changed = field != value; field = value; if (changed) prepare() }
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

    private class Engine(val close: () -> Unit, val words: WordModel, val ranker: LmRanker, val setLanguage: (String) -> Unit) {
        /** ai-dict-3: the first answer after loading is "cold" (the memory of the model is touched the first time) */
        @Volatile var answered = false
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-dict").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }
    @Volatile private var engine: Engine? = null
    @Volatile private var loading: Future<*>? = null
    @Volatile var loadError: String? = null; private set
    @Volatile var loadMs: Long = -1; private set

    private val cache = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > 64
    }
    private val pending = HashMap<String, Future<*>>()
    /** ai-dict-3: the newest waiting request of each kind ("r" rerank, "p" predict, "c" context): an older one is dropped */
    private val newest = HashMap<Char, Pair<String, Future<*>>>()

    /** runs [job] and notes how long the model counted (every answer, also the late ones) */
    private fun <T> timed(e: Engine, job: (Engine) -> T): T {
        val t0 = System.nanoTime()
        val r = job(e)
        Journal.computed((System.nanoTime() - t0) / 1_000_000, cold = !e.answered)
        e.answered = true
        return r
    }

    /** a new request of a kind drops the older one of that kind that has not started yet (the typed word changed) */
    private fun replaceNewest(key: String, f: Future<*>) {
        val kind = key[0]
        val old = newest.put(kind, key to f)
        if (old != null && old.first != key && old.second.cancel(false)) { pending.remove(old.first); Journal.dropped() }
    }

    /** ai-dict-3: the state of the context ahead of the letters (no waiting; dropped if the word changes first) */
    private fun prepare() {
        val e = engine ?: return
        val before = textBefore
        val lang = language
        if (held) return
        val key = "c\u0000$lang\u0000$before"
        synchronized(pending) {
            if (pending[key] != null) return
            val f = executor.submit { try { e.setLanguage(lang); e.words.prepare(before) } catch (t: Throwable) { Log.w(TAG, "prepare failed", t) } finally { synchronized(pending) { pending.remove(key) } } }
            pending[key] = f
            replaceNewest(key, f)
        }
    }

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
                    // ai-dict-3: the memory of the keyboard's process with the model in it (PSS, as the system counts it)
                    Journal.loaded(loadMs, try { android.os.Debug.getPss() / 1024 } catch (_: Throwable) { -1L })
                    Log.i(TAG, "model loaded in $loadMs ms")
                    prepare() // the context of the field is computed right away
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
                                val r = timed(e, job)
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
                    timed(e, job).also { r ->
                        synchronized(cache) { cache[key] = r }
                        // the suggestion thread did not wait for it: ask the keyboard to show the suggestions again
                        if (gaveUp.get()) onLateResult?.invoke()
                    }
                } finally { synchronized(pending) { pending.remove(key) } }
            }.also { pending[key] = it; replaceNewest(key, it) }
        }
        val t0 = System.nanoTime()
        return try {
            (f.get(BUDGET_MS, TimeUnit.MILLISECONDS) as T).also { Journal.inTime((System.nanoTime() - t0) / 1_000_000) }
        } catch (_: TimeoutException) {
            gaveUp.set(true)
            Journal.late(); null
        } catch (_: java.util.concurrent.CancellationException) {
            null // dropped: a newer request of the same kind came
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
        /** ai-dict-3: requests dropped because a newer one of the same kind came before they started */
        @Volatile var dropped = 0; private set
        /** ai-dict-3: how long the model counted for every answer (also the late ones), the last [KEEP] warm ones */
        private val warm = ArrayDeque<Long>()
        @Volatile var coldMs = -1L; private set
        @Volatile var lastLoadMs = -1L; private set
        @Volatile var memoryMb = -1L; private set
        private var dirty = 0
        private const val KEEP = 200

        @Synchronized fun inTime(ms: Long) { calls++; totalMs += ms; if (ms > maxMs) maxMs = ms; dirty++ }
        @Synchronized fun late() { calls++; late++; dirty++ }
        @Synchronized fun cached() { cachedHits++ }
        @Synchronized fun failed() { calls++; failed++ }
        @Synchronized fun dropped() { dropped++; dirty++ }
        /** the model's choice (autocorrection or first suggestion) differs from the dictionary's */
        @Synchronized fun changedChoice() { changed++; dirty++ }
        @Synchronized fun computed(ms: Long, cold: Boolean) {
            if (cold) coldMs = ms else { warm.addLast(ms); while (warm.size > KEEP) warm.removeFirst() }
            dirty++
        }
        @Synchronized fun loaded(ms: Long, mb: Long) { lastLoadMs = ms; memoryMb = mb; dirty++ }

        /** median, 90th percentile and maximum of the warm answers (null: none yet) */
        @Synchronized fun warmStats(): Triple<Long, Long, Long>? {
            if (warm.isEmpty()) return null
            val s = warm.sorted()
            return Triple(s[(s.size - 1) / 2], s[((s.size - 1) * 9 + 9) / 10].coerceAtMost(s.last()), s.last())
        }

        fun summary(): String {
            val inTime = calls - late - failed
            val avg = if (inTime > 0) totalMs / inTime else 0
            val w = warmStats()
            return "ответов модели: $calls (в срок $inTime, поздно $late, ошибок $failed, из памяти $cachedHits, сняты как устаревшие $dropped); " +
                "ожидание в срок: среднее $avg мс, максимум $maxMs мс; " +
                "счёт модели (все ответы, и поздние): " + (if (w == null) "ещё нет" else "медиана ${w.first} мс, 90% ${w.second} мс, максимум ${w.third} мс (${warm.size} отв.)") +
                "; первый ответ после загрузки: " + (if (coldMs >= 0) "$coldMs мс" else "ещё нет") +
                "; загрузка модели: " + (if (lastLoadMs >= 0) "$lastLoadMs мс" else "не было") +
                ", память клавиатуры после загрузки: " + (if (memoryMb >= 0) "$memoryMb МБ" else "?") +
                "; модель изменила выбор словаря: $changed раз"
        }

        fun save(prefs: SharedPreferences) {
            if (dirty == 0) return
            val times = synchronized(this) { warm.joinToString(";") }
            prefs.edit { putString(PREF_JOURNAL, "$calls,$late,$cachedHits,$failed,$changed,$totalMs,$maxMs,$dropped,$coldMs,$lastLoadMs,$memoryMb,$times") }
            dirty = 0
        }

        fun load(prefs: SharedPreferences) {
            val raw = prefs.getString(PREF_JOURNAL, null)?.split(',') ?: return
            val p = raw.take(11).mapNotNull { it.toLongOrNull() }
            if (p.size < 7) return
            calls = p[0].toInt(); late = p[1].toInt(); cachedHits = p[2].toInt(); failed = p[3].toInt(); changed = p[4].toInt()
            totalMs = p[5]; maxMs = p[6]
            if (p.size >= 11) { dropped = p[7].toInt(); coldMs = p[8]; lastLoadMs = p[9]; memoryMb = p[10] }
            synchronized(this) { warm.clear(); raw.getOrNull(11)?.split(';')?.mapNotNull { it.toLongOrNull() }?.let { warm.addAll(it.takeLast(KEEP)) } }
        }

        @Synchronized fun reset(prefs: SharedPreferences) {
            calls = 0; late = 0; cachedHits = 0; failed = 0; changed = 0; totalMs = 0; maxMs = 0
            dropped = 0; coldMs = -1; lastLoadMs = -1; memoryMb = -1; warm.clear()
            prefs.edit { remove(PREF_JOURNAL) }
        }
    }
}
