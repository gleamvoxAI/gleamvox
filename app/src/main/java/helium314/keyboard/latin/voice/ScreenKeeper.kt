// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Stage 5g-20 (the owner: the screen went dark right after the dictation, no time to read and correct the text): the
 * screen stays on (FLAG_KEEP_SCREEN_ON on the keyboard window, no permission) while recording, recognizing and inserting,
 * and [AFTER_INSERT_MS] after the last text went in; then, or at a touch of the keyboard, or when the keyboard closes,
 * the flag goes. Android holds that flag with a wake lock "on after release" (WindowManagerService: SCREEN_BRIGHT_WAKE_LOCK
 * | ON_AFTER_RELEASE), so taking it away counts as user activity: the normal screen timeout of Android starts from then,
 * the screen does not go dark at once.
 */
class ScreenKeeper(private val setFlag: (Boolean) -> Unit, private val later: (Long, Runnable) -> (() -> Unit)) {
    var on = false
        private set
    private var timer: (() -> Unit)? = null
    private var insertedNow = false

    /** recording, recognizing or inserting: on, and no timer */
    fun keep() {
        timer?.invoke(); timer = null
        if (!on) { on = true; setFlag(true) }
    }

    /** a text went in: the screen stays on, and [AFTER_INSERT_MS] after the dictation ends */
    fun inserted() { insertedNow = true; keep() }

    /** the dictation is over: [AFTER_INSERT_MS] more if a text went in, else off now */
    fun ended() {
        if (!insertedNow) { release(); return }
        insertedNow = false
        timer?.invoke()
        if (!on) { on = true; setFlag(true) }
        timer = later(AFTER_INSERT_MS, Runnable { timer = null; release() })
    }

    /** a touch of the keyboard, the keyboard closed: the normal timeout of Android from now */
    fun release() {
        timer?.invoke(); timer = null
        insertedNow = false
        if (on) { on = false; setFlag(false) }
    }

    companion object {
        const val AFTER_INSERT_MS = 5_000L
    }
}
