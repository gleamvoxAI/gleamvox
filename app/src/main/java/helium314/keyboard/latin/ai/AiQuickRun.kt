// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.R
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.utils.Log

/**
 * Short and long press of the ✨ button (docs/DESIGN.md, 5е-1): released before [timeoutMs] is a tap, held for
 * [timeoutMs] is a long press, a move further than [slopPx] from the first touch is neither. The clock is passed in,
 * so tests run without waiting.
 */
class LongPressDetector(
    private val timeoutMs: Long,
    private val slopPx: Float,
    private val schedule: (Long, () -> Unit) -> () -> Unit,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private enum class State { IDLE, PRESSED, FIRED, CANCELLED }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var cancelTimer: (() -> Unit)? = null

    fun down(x: Float, y: Float) {
        reset()
        state = State.PRESSED
        downX = x; downY = y
        cancelTimer = schedule(timeoutMs) {
            cancelTimer = null
            if (state == State.PRESSED) { state = State.FIRED; onLongPress() }
        }
    }

    fun move(x: Float, y: Float) {
        if (state != State.PRESSED) return
        val dx = x - downX; val dy = y - downY
        if (dx * dx + dy * dy > slopPx * slopPx) { reset(); state = State.CANCELLED }
    }

    fun up() {
        val wasPressed = state == State.PRESSED
        reset()
        if (wasPressed) onTap()
    }

    /** the system took the touch away (scroll, window change): nothing happens */
    fun cancel() = reset()

    private fun reset() {
        cancelTimer?.invoke()
        cancelTimer = null
        state = State.IDLE
    }

    companion object {
        /** the owner's threshold: about 450 ms */
        const val TIMEOUT_MS = 450L
        /** "a few millimeters" of movement cancel the press */
        const val SLOP_MM = 4f
    }
}

/** what the strip shows for a long press: the ✨ pulsing while the request runs, a short message, "Undo" */
interface QuickRunUi {
    fun setBusy(busy: Boolean)
    /** [onClick] null: only text; else the message opens something (the AI settings) */
    fun showMessage(res: Int, onClick: (() -> Unit)? = null)
    fun showUndo(onUndo: () -> Unit)
    fun hide()
}

/**
 * The long press of ✨ (docs/DESIGN.md, 5е-1): the chosen command runs at once on the selection (without a selection on
 * the whole field, up to 4000 characters) without a preview. The answer replaces exactly the fragment if it is still
 * unchanged, otherwise it is inserted next to it without touching other text; "Undo" stays for 8 seconds. A tap on ✨
 * while it runs cancels it. Errors are one short line in the strip. The text is never logged or stored.
 * All callbacks of [ui] and all field changes happen on the main thread ([post]); the request runs on [background].
 */
class AiQuickRunner(
    private val provider: () -> AiProvider?,
    private val field: FieldAccess,
    private val ui: QuickRunUi,
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val background: (Runnable) -> Unit,
    private val log: (String) -> Unit = { Log.i("AiQuickRun", it) },
) {
    private var session = 0
    private var cancel: CancelSignal? = null

    /** a request runs now: a tap on ✨ cancels it */
    var isBusy = false
        private set

    /**
     * Runs [command] now. The caller has checked field, excluded apps, incognito, internet, key and consent right before;
     * the daily ceiling and pauses are checked by the provider.
     */
    fun run(command: AiCommand, language: String?) {
        stop()
        val f = when (val c = FieldFragment.capture(field)) {
            Capture.TooLong -> { message(R.string.ai_quick_too_long); return }
            Capture.Empty -> { message(R.string.ai_quick_empty); return }
            Capture.Unknown -> { message(R.string.ai_quick_unknown_field); return }
            is Capture.Ok -> c.fragment
        }
        val ai = provider() ?: return
        val id = session
        val signal = CancelSignal()
        cancel = signal
        isBusy = true
        ui.setBusy(true)
        log("long press: $command, $f") // never the text
        background(Runnable {
            val result = ai.respond(AiCommands.request(command, f.text, language), signal) { }
            post(Runnable { if (id == session) onResult(command, f, result) })
        })
    }

    private fun onResult(command: AiCommand, f: Fragment, result: AiResult) {
        cancel = null
        isBusy = false
        ui.setBusy(false)
        when (result) {
            is AiResult.Success -> apply(command, f, result.text.trim())
            is AiResult.Failure -> when (result.error) {
                AiError.CANCELLED -> log("long press: cancelled")
                else -> { log("long press: ${result.error}"); message(shortRes(result.error), settings = needsSettings(result.error)) }
            }
        }
    }

    private fun apply(command: AiCommand, f: Fragment, answer: String) {
        if (answer.isEmpty()) { message(R.string.ai_quick_failed); return }
        if (command != AiCommand.CONTINUE && FieldFragment.isUnchanged(field, f)) {
            if (answer == f.text) { log("long press: nothing to change"); message(R.string.ai_quick_unchanged); return }
            if (!field.replace(f.start, f.end, answer)) { message(R.string.ai_quick_failed); return }
            log("long press: replaced")
            offerUndo(f.start, answer, f.text)
            return
        }
        // "Continue", or the fragment changed meanwhile: next to it (or at the cursor), never over other text
        val unchanged = FieldFragment.isUnchanged(field, f)
        val at = if (unchanged) f.end else field.selectionEnd()
        if (at < 0) { message(R.string.ai_quick_failed); return }
        val before = if (unchanged) f.text else field.textBefore(1)?.toString() ?: ""
        val inserted = FieldFragment.joiner(before, answer) + answer
        if (!field.insertAt(at, inserted)) { message(R.string.ai_quick_failed); return }
        log(if (unchanged) "long press: inserted after the fragment" else "long press: the field changed, inserted at the cursor")
        offerUndo(at, inserted, "")
    }

    /** "Undo" for 8 s: [now] at [start] goes back to [before], only if it is still exactly there */
    private fun offerUndo(start: Int, now: String, before: String) {
        val id = ++session
        ui.showUndo {
            if (id != session) return@showUndo
            session++
            ui.hide()
            if (DictationCleanup.isStillThere(field, start, now)) {
                field.replace(start, start + now.length, before)
                log("long press: undone")
            } else log("long press: the field changed, not undone")
        }
        postDelayed(Runnable { if (id == session) { session++; ui.hide() } }, UNDO_MS)
    }

    private var settingsOpener: (() -> Unit)? = null

    /** what a message about a missing key, consent or internet opens */
    fun setSettingsOpener(open: () -> Unit) { settingsOpener = open }

    /** a short line for [MESSAGE_MS]; [settings]: a tap opens the AI settings */
    fun message(res: Int, settings: Boolean = false) {
        val id = ++session
        ui.showMessage(res, if (settings) settingsOpener else null)
        postDelayed(Runnable { if (id == session) { session++; ui.hide() } }, MESSAGE_MS)
    }

    /** a tap on ✨ while busy: the request ends at once, nothing is inserted */
    fun cancelRequest() {
        if (!isBusy) return
        stop()
        log("long press: cancelled by a tap")
    }

    /** keyboard hidden, other field, the panel opens: the request ends, "Undo" and messages go away */
    fun stop() {
        session++
        cancel?.cancel()
        cancel = null
        if (isBusy) { isBusy = false; ui.setBusy(false) }
        ui.hide()
    }

    companion object {
        /** "Undo" stays this long (the same as after the cleanup of dictation) */
        const val UNDO_MS = DictationCleanup.UNDO_MS
        const val MESSAGE_MS = 3_500L

        fun needsSettings(error: AiError) = error == AiError.INTERNET_OFF || error == AiError.NO_KEY ||
            error == AiError.KEY_INVALID || error == AiError.NO_CONSENT || error == AiError.DAILY_CAP

        /** one short line for the strip */
        fun shortRes(error: AiError) = when (error) {
            AiError.INTERNET_OFF -> R.string.ai_quick_internet_off
            AiError.NO_KEY -> R.string.ai_quick_no_key
            AiError.KEY_INVALID -> R.string.ai_quick_key_invalid
            AiError.NO_CONSENT -> R.string.ai_quick_no_consent
            AiError.PHONE_LOCKED -> R.string.ai_quick_locked
            AiError.NO_BALANCE -> R.string.ai_quick_no_balance
            AiError.RATE_LIMIT -> R.string.ai_quick_rate
            AiError.DAILY_CAP -> R.string.ai_quick_daily_cap
            AiError.NETWORK, AiError.UNAVAILABLE, AiError.REGION_BLOCKED -> R.string.ai_quick_network
            AiError.TIMEOUT -> R.string.ai_quick_timeout
            AiError.NO_MODEL -> R.string.ai_quick_no_model
            AiError.INCOMPLETE, AiError.TOO_LONG, AiError.CANCELLED, AiError.FAILED -> R.string.ai_quick_failed
        }
    }
}
