// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** one server-sent event: the "event:" name (if any) and the joined "data:" lines */
class SseEvent(val name: String?, val data: String) {
    override fun toString() = "SseEvent($name)" // never the data: it holds the answer text
}

/** Reads server-sent events (lines "event:" and "data:", an empty line ends an event; ":" lines are comments). */
class SseReader(input: InputStream) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    /** the next event, or null at the end of the stream */
    fun next(): SseEvent? {
        var name: String? = null
        val data = StringBuilder()
        var hasData = false
        var hasAny = false
        while (true) {
            val line = reader.readLine() ?: return if (hasAny) SseEvent(name, data.toString()) else null
            if (line.isEmpty()) {
                if (hasAny) return SseEvent(name, data.toString())
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            var value = if (':' in line) line.substringAfter(':') else ""
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> { name = value; hasAny = true }
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                    hasAny = true
                }
            }
        }
    }
}
