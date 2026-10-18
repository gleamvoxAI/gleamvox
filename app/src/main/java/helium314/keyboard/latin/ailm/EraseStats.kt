// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ailm

/**
 * ux-4: how long the keyboard needs for Backspace (the owner: "Backspace got slow"). Times only, never text, kept in memory
 * since the process start: the last [CAPACITY] presses of each kind. Shown in the AI dictionary window.
 *  - key: from the key event to the changed text (the work of the main thread);
 *  - stripErase / stripType: the whole update of the suggestion strip after an erase / after other typing (the main thread
 *    waits for it).
 */
object EraseStats {
    const val CAPACITY = 200

    class Series {
        private val ring = LongArray(CAPACITY)
        private var n = 0
        @Synchronized fun add(nanos: Long) { ring[n % CAPACITY] = nanos; n++ }
        @Synchronized fun count() = n
        @Synchronized fun summary(): String {
            if (n == 0) return "нет данных"
            val v = ring.copyOf(minOf(n, CAPACITY)).also { it.sort() }
            fun ms(x: Long) = "%.1f".format(java.util.Locale.ROOT, x / 1e6)
            return "медиана ${ms(v[v.size / 2])}, 90 % ${ms(v[(v.size * 9 / 10).coerceAtMost(v.size - 1)])}, максимум ${ms(v.last())} мс ($n шт.)"
        }
        @Synchronized fun median(): Long = if (n == 0) 0 else ring.copyOf(minOf(n, CAPACITY)).also { it.sort() }.let { it[it.size / 2] }
    }

    @JvmField val key = Series()
    @JvmField val stripErase = Series()
    @JvmField val stripType = Series()

    @JvmStatic fun recordStrip(nanos: Long, erasing: Boolean) = (if (erasing) stripErase else stripType).add(nanos)

    fun summary() = "Стирание (Backspace): нажатие обработано — ${key.summary()}; полоса подсказок после стирания — " +
        "${stripErase.summary()}; после набора — ${stripType.summary()}"
}
