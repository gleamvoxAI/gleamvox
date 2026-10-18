// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * After the microphone permission dialog the keyboard is shown again and listening should start by itself, once.
 * "Expected start" is remembered for the field where the microphone was pressed and dropped on a timeout,
 * another field or app, and screen off. Plain Kotlin, no Android, so it can be tested on the JVM.
 */
class PermissionAutoStart(private val timeoutMs: Long = TIMEOUT_MS) {
    enum class Action { NONE, START, SHOW_DENIED }

    private var requestedAt = 0L
    private var field: String? = null
    val isPending get() = field != null

    /** [fieldKey] identifies the app and the field (see [fieldKey]) */
    fun expectStart(now: Long, fieldKey: String) {
        requestedAt = now
        field = fieldKey
    }

    fun reset() { field = null }

    /** the keyboard is shown for a field. One decision only: afterwards nothing is pending. */
    fun onInputViewStarted(now: Long, fieldKey: String, hasPermission: Boolean, sensitive: Boolean): Action {
        val expected = field ?: return Action.NONE
        field = null // never more than one automatic start
        if (now - requestedAt > timeoutMs || expected != fieldKey || sensitive) return Action.NONE
        return if (hasPermission) Action.START else Action.SHOW_DENIED
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        fun fieldKey(packageName: String?, fieldId: Int) = "${packageName ?: ""}/$fieldId"
    }
}
