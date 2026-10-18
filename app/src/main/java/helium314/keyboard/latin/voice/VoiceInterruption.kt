// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** why a recording ended without the user (stage 5zh, item 5) */
enum class Interruption { SCREEN_OFF, HIDDEN }

/**
 * A recording interrupted by the screen going off, the keyboard closing or the app going away (docs/DESIGN.md, 5ж
 * item 5): what was recorded is not lost. A running recording is finished and sent; a phrase being recognized goes on.
 * The text is inserted at once if the field is still there, otherwise kept only in memory (never on disk, at most
 * [PENDING_MS]) until the keyboard comes back: in the same field it is inserted, in another one "Insert" is offered.
 * The line in the strip says what happened ("The screen went off, recording finished"). Never logs the text.
 */
class VoiceInterruption(private val now: () -> Long = System::currentTimeMillis) {
    enum class Action {
        /** stop recording now and send what was recorded */
        FINISH,
        /** the phrase is sent already: let it end */
        WAIT,
        /** nothing worth keeping (an error, the permission, idle) */
        CANCEL,
    }

    /** what the strip says when the keyboard is back */
    sealed class Show(val reason: Interruption) {
        class Inserted(reason: Interruption) : Show(reason)
        /** same field: insert [text] now, then say "inserted" */
        class InsertNow(reason: Interruption, val text: String) : Show(reason)
        /** another field: offer "Insert" */
        class Offer(reason: Interruption, val text: String) : Show(reason)
        class Failed(reason: Interruption) : Show(reason)
    }

    private enum class Outcome { INSERTED, PENDING, FAILED }

    /** the interrupted recording is still being finished or recognized */
    var reason: Interruption? = null
        private set
    private var outcomeReason: Interruption? = null
    private var outcome: Outcome? = null
    private var text: String? = null
    private var field: String? = null
    private var at = 0L

    val isRunning get() = reason != null
    val hasSomethingToSay get() = outcome != null

    fun onInterrupt(state: VoiceState, r: Interruption): Action {
        val action = when (state) {
            VoiceState.LISTENING -> Action.FINISH
            VoiceState.PROCESSING -> Action.WAIT
            else -> return Action.CANCEL
        }
        // the screen going off is the more useful reason when both come
        if (reason == null || r == Interruption.SCREEN_OFF) reason = r
        return action
    }

    /**
     * The recognized text arrived; true: insert it now ([here]: the field it was dictated in is shown). A continuous
     * dictation (stage 5g) brings several phrases: once one has to wait, the next ones wait behind it, joined with a space.
     */
    fun onText(text: String, here: Boolean, field: String?): Boolean {
        val r = reason ?: return true
        outcomeReason = r
        val waiting = this.text
        if (here && waiting == null) {
            outcome = Outcome.INSERTED
            return true
        }
        outcome = Outcome.PENDING
        this.text = if (waiting == null) text else "$waiting $text"
        if (waiting == null) this.field = field
        at = now()
        return false
    }

    /** nothing was recognized (an error, nothing heard) */
    fun onFailed() {
        val r = reason ?: return
        outcomeReason = r
        outcome = Outcome.FAILED
    }

    /** the dictation is over (idle again) */
    fun onEnded() { reason = null }

    /** the keyboard is shown in [currentField]: what to do and say; null while the phrase is still recognized */
    fun take(currentField: String?): Show? {
        if (reason != null) return null
        val o = outcome ?: return null
        val r = outcomeReason ?: return null
        val t = text?.takeIf { now() - at < PENDING_MS }
        val f = field
        clear()
        return when {
            o == Outcome.INSERTED -> Show.Inserted(r)
            o == Outcome.FAILED || t == null -> Show.Failed(r) // nothing recognized, or kept too long: dropped
            currentField == f -> Show.InsertNow(r, t)
            else -> Show.Offer(r, t)
        }
    }

    fun clear() {
        outcome = null; outcomeReason = null; text = null; field = null
    }

    companion object {
        /** the text of an interrupted recording waits this long for the keyboard to come back */
        const val PENDING_MS = 10 * 60_000L
    }
}
