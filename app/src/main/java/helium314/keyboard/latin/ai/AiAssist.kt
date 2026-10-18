// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceInputView
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsActivity2
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The ✨ button and its panel (docs/AI.md): connects the strip button, the panel over the keys, the field and the AI
 * provider (the user's own OpenAI API key). Nothing is sent without a tap on a command; the field is checked again right before
 * sending; text and answer live only in memory while the panel is open. Only the personal variant has it.
 */
class AiAssist @JvmOverloads constructor(
    private val ime: LatinIME,
    private val provider: () -> AiProvider? = { if (inBuild()) helium314.keyboard.latin.openai.OpenAiTextProvider.get(ime) else null },
    private val field: FieldAccess = ImeFieldAccess(ime),
    private val internetOn: () -> Boolean = { AikbNetwork.internetAllowed(ime) },
    /** a key that OpenAI did not reject, and the consent for text requests */
    private val ready: () -> Boolean = { isReady(ime) },
    /** where requests run; replaced in tests */
    background: ((Runnable) -> Unit)? = null,
    /** why ✨ is dimmed now: internet, key, rejected key or consent (stage 5zh) */
    private val notReady: () -> AiError = {
        val p = ime.prefs()
        AiGuard.notReadyReason(internetOn(), VoiceApi.hasKey(ime), VoiceApiPrefs.keyRejected(p), AiPrefs.textConsent(p))
    },
) : AiPanelView.Actions {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "aikb-ai").apply { isDaemon = true } }
    private val runInBackground: (Runnable) -> Unit = background ?: { executor.execute(it) }

    /** the long press of ✨: runs the chosen command at once, "Undo" and short messages in the strip (stage 5e) */
    private val quick = AiQuickRunner(
        provider = provider,
        field = field,
        ui = StripQuickUi(),
        post = { handler.post(it) },
        postDelayed = { r, ms -> handler.postDelayed(r, ms) },
        background = runInBackground,
    ).apply { setSettingsOpener { openAiSettings(AiGuard.settingsTarget(notReady())) } }
    private var panel: AiPanelView? = null
    private var cancel: CancelSignal? = null
    /** later events of an older request are dropped */
    private var session = 0
    private var editorInfo: EditorInfo? = null
    private var fragment: Fragment? = null
    private var answer: String? = null

    val isPanelShown get() = panel != null

    private fun blocked() = AiGuard.blocked(editorInfo, AiPrefs.excludedApps(ime.prefs()), Settings.getValues().mIncognitoModeEnabled)

    fun buttonState(): AiGuard.Button = AiGuard.button(inBuild(), blocked(), internetOn(), ready())

    fun updateButton() = ime.setAiKeyState(buttonState())

    /** a field is shown: the old panel is gone, the button follows the new field */
    fun onInputViewStarted(info: EditorInfo?) {
        stop()
        // the phone may have been locked when the app started (direct boot): erase the old sign-in now (stage 5d)
        helium314.keyboard.latin.openai.ChatGptRemoval.runOnce(ime)
        editorInfo = info
        updateButton()
    }

    /** the ✨ button: the commands; while a long-press request runs, a tap cancels it */
    fun onAiKey() {
        if (quick.isBusy) { quick.cancelRequest(); return }
        when (buttonState()) {
            AiGuard.Button.READY -> if (panel != null) closePanel() else showCommands()
            AiGuard.Button.OPENS_SETTINGS -> {
                // the reason in plain words in the strip, and right to what is missing (only the consent: its window)
                val reason = notReady()
                Log.i(TAG, "dimmed ✨ tapped: $reason")
                quick.message(AiQuickRunner.shortRes(reason), settings = true)
                openAiSettings(AiGuard.settingsTarget(reason))
            }
            AiGuard.Button.HIDDEN -> Unit
        }
    }

    /**
     * Long press on ✨ (docs/DESIGN.md, 5е-1): the command chosen in the settings ("Fix" by default) runs at once and its
     * answer replaces the fragment without a preview, unless "Show the preview after a long press" is on. The same
     * conditions as every AI function; a missing key, consent or internet is a short line in the strip.
     */
    fun onAiLongPress() {
        if (quick.isBusy) { quick.cancelRequest(); return }
        when (buttonState()) {
            AiGuard.Button.HIDDEN -> return
            AiGuard.Button.OPENS_SETTINGS -> {
                quick.message(AiQuickRunner.shortRes(notReady()), settings = true)
                return
            }
            AiGuard.Button.READY -> Unit
        }
        closePanel()
        // every condition again, right before sending
        if (!AiGuard.maySend(editorInfo, AiPrefs.excludedApps(ime.prefs()), Settings.getValues().mIncognitoModeEnabled, internetOn(), ready())) {
            updateButton(); return
        }
        val prefs = ime.prefs()
        val choice = AiPrefs.longPressCommand(prefs)
        val layout = layoutLanguage()
        val language = choice.language(layout, AiCommands.secondLanguage(prefs.getString(AiPrefs.PREF_TRANSLATE_TO, null), layout))
        ime.finishComposingForAi()
        if (AiPrefs.longPressPreview(prefs)) {
            showCommands()
            onCommand(choice.command, language)
        } else {
            quick.run(choice.command, language)
        }
    }

    /** for tests: a long-press request runs */
    val isQuickBusy get() = quick.isBusy

    private fun layoutLanguage(): String = currentLocale().language

    private fun currentLocale(): Locale = RichInputMethodManager.getInstance().currentSubtype.locale

    private fun showCommands() {
        quick.stop()
        val p = ensurePanel() ?: return
        p.setLabelLocale(currentLocale())
        val layout = layoutLanguage()
        p.showCommands(layout, AiCommands.secondLanguage(ime.prefs().getString(AiPrefs.PREF_TRANSLATE_TO, null), layout))
    }

    override fun onCommand(command: AiCommand, language: String?) {
        val p = panel ?: return
        // every condition again, right before sending
        if (!AiGuard.maySend(editorInfo, AiPrefs.excludedApps(ime.prefs()), Settings.getValues().mIncognitoModeEnabled, internetOn(), ready())) {
            closePanel(); updateButton(); return
        }
        ime.finishComposingForAi()
        when (val c = FieldFragment.capture(field)) {
            Capture.TooLong -> p.showMessage(R.string.ai_too_long, FieldFragment.MAX_CHARS)
            Capture.Empty -> p.showMessage(R.string.ai_empty)
            Capture.Unknown -> p.showMessage(R.string.ai_unknown_field)
            is Capture.Ok -> send(p, command, language, c.fragment)
        }
    }

    private fun send(p: AiPanelView, command: AiCommand, language: String?, f: Fragment) {
        val ai = provider() ?: return
        fragment = f
        answer = null
        val id = ++session
        val signal = CancelSignal()
        cancel = signal
        p.showWorking(command, language)
        val request = AiCommands.request(command, f.text, language)
        Log.i(TAG, "request: $command, $f") // never the text
        runInBackground(Runnable {
            val result = ai.respond(request, signal) { text -> handler.post { if (id == session) panel?.setAnswer(text) } }
            handler.post { if (id == session) onResult(result) }
        })
    }

    private fun onResult(result: AiResult) {
        cancel = null
        val p = panel ?: return
        when (result) {
            is AiResult.Success -> {
                if (result.text.isBlank()) { p.showMessage(R.string.ai_error_incomplete); return }
                answer = result.text
                p.showResult(result.text, fragment?.let { FieldFragment.isUnchanged(field, it) } == true)
            }
            is AiResult.Failure -> showFailure(p, result)
        }
    }

    private fun showFailure(p: AiPanelView, f: AiResult.Failure) {
        when (f.error) {
            AiError.NO_BALANCE -> p.showMessage(R.string.ai_error_no_balance, balance = true)
            AiError.DAILY_CAP -> p.showMessage(R.string.ai_error_daily_cap, AiUsage(ime.prefs()).cap(), settings = true)
            AiError.INTERNET_OFF -> p.showMessage(R.string.ai_error_internet_off, settings = true)
            AiError.NO_KEY, AiError.KEY_INVALID, AiError.NO_CONSENT -> {
                updateButton()
                p.showMessage(errorRes(f.error), settings = true)
            }
            AiError.CANCELLED -> closePanel()
            else -> p.showMessage(errorRes(f.error))
        }
    }

    override fun onReplace() {
        val f = fragment ?: return
        val a = answer ?: return
        if (FieldFragment.replace(field, f, a)) closePanel()
        else panel?.showResult(a, false) // changed meanwhile: only "insert at the cursor"
    }

    override fun onInsert() {
        val f = fragment ?: return
        val a = answer ?: return
        FieldFragment.insertNextTo(field, f, a)
        closePanel()
    }

    /** "Cancel": the request ends at once, the panel closes, nothing is inserted */
    override fun onCancel() = closePanel()

    /** the exact balance is only on the OpenAI site */
    override fun onOpenBalance() { OpenAiSetup.openInBrowser(ime, OpenAi.BILLING_URL) }

    override fun onOpenSettings() {
        stop()
        openAiSettings(AiGuard.settingsTarget(notReady()))
    }

    private fun openAiSettings(target: AiGuard.SettingsTarget = AiGuard.SettingsTarget.NONE) {
        ime.requestHideSelf(0)
        try {
            ime.startActivity(Intent(ime, SettingsActivity2::class.java).setAction(SettingsActivity.ACTION_OPEN_AI)
                .putExtra(SettingsActivity.EXTRA_AI_TARGET, target.value)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "can't open the AI settings")
        }
    }

    /** keyboard hidden, other field or app, new view: cancel the requests, forget text and answer, close the panel */
    fun stop() {
        quick.stop()
        closePanel()
    }

    /** the panel only: its request ends, text and answer are forgotten */
    private fun closePanel() {
        session++
        cancel?.cancel()
        cancel = null
        fragment = null
        answer = null
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
    }

    fun onLayoutChanged() { panel?.setLabelLocale(currentLocale()) }

    fun destroy() {
        stop()
        executor.shutdownNow()
    }

    private fun ensurePanel(): AiPanelView? {
        val wrapper = ime.keyboardWrapperForVoice ?: return null
        panel?.let { if (it.parent === wrapper) return it }
        val keys = KeyboardSwitcher.getInstance().visibleKeyboardView
        // explicit height of the keys area, like the voice panel: never higher than the keyboard
        val height = keys?.height?.takeIf { it > 0 } ?: keys?.measuredHeight ?: 0
        if (height <= 0) return null
        val p = AiPanelView(wrapper.context, Settings.getValues().mColors, this)
        wrapper.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
        panel = p
        return p
    }

    /** "Undo" and the short messages of the long press in the strip, in the language of the layout; the pulsing ✨ */
    private inner class StripQuickUi : QuickRunUi {
        private val chip = AiStripChip(ime)
        override fun setBusy(busy: Boolean) = ime.setAiKeyBusy(busy)
        override fun showMessage(res: Int, onClick: (() -> Unit)?) =
            chip.show(VoiceInputView.labelContextFor(ime, currentLocale()).getString(res), onClick == null, onClick)
        override fun showUndo(onUndo: () -> Unit) =
            chip.show(VoiceInputView.labelContextFor(ime, currentLocale()).getString(R.string.ai_quick_undo), false, onUndo)
        override fun hide() = chip.hide()
    }

    companion object {
        private const val TAG = "AiAssist"

        /** only the personal variant has AI functions; replaced in tests */
        @JvmField
        var inBuild: () -> Boolean = { OpenAiPrefs.isAvailable() }

        /** ✨ can send: a key that OpenAI did not reject and the consent for text requests (the internet switch apart) */
        fun isReady(context: android.content.Context): Boolean {
            val prefs = context.prefs()
            return AiPrefs.textConsent(prefs) && !VoiceApiPrefs.keyRejected(prefs) && VoiceApi.hasKey(context)
        }

        fun errorRes(error: AiError) = when (error) {
            AiError.INTERNET_OFF -> R.string.ai_error_internet_off
            AiError.NO_KEY -> R.string.ai_error_no_key
            AiError.KEY_INVALID -> R.string.ai_error_key_invalid
            AiError.NO_CONSENT -> R.string.ai_error_no_consent
            AiError.PHONE_LOCKED -> R.string.ai_error_locked
            AiError.NO_BALANCE -> R.string.ai_error_no_balance
            AiError.RATE_LIMIT -> R.string.ai_error_rate
            AiError.DAILY_CAP -> R.string.ai_error_daily_cap
            AiError.UNAVAILABLE -> R.string.ai_error_unavailable
            AiError.REGION_BLOCKED -> R.string.ai_error_region
            AiError.NETWORK -> R.string.ai_error_network
            AiError.INCOMPLETE -> R.string.ai_error_incomplete
            AiError.TOO_LONG -> R.string.ai_error_too_long
            AiError.TIMEOUT -> R.string.ai_error_timeout
            AiError.NO_MODEL -> R.string.ai_error_no_model
            AiError.CANCELLED, AiError.FAILED -> R.string.ai_error_failed
        }
    }
}

/** the current field through the input connection; writes go through HeliBoard's connection so its caches stay right */
class ImeFieldAccess(private val ime: LatinIME) : FieldAccess {
    private fun ic() = ime.currentInputConnection

    private fun selection(): Pair<Int, Int> {
        val et = try { ic()?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0) } catch (e: Exception) { null }
        if (et != null && et.selectionStart >= 0) return (et.startOffset + et.selectionStart) to (et.startOffset + et.selectionEnd)
        val rc = ime.aiConnection
        return rc.expectedSelectionStart to rc.expectedSelectionEnd
    }

    override fun selectionStart() = selection().let { minOf(it.first, it.second) }
    override fun selectionEnd() = selection().let { maxOf(it.first, it.second) }
    override fun selectedText(): CharSequence? = ic()?.getSelectedText(0)
    override fun textBefore(n: Int): CharSequence? = ic()?.getTextBeforeCursor(n, 0)
    override fun textAfter(n: Int): CharSequence? = ic()?.getTextAfterCursor(n, 0)

    private fun edit(block: (helium314.keyboard.latin.RichInputConnection) -> Boolean): Boolean {
        val rc = ime.aiConnection
        rc.beginBatchEdit()
        return try { block(rc) } finally { rc.endBatchEdit() }
    }

    override fun replace(start: Int, end: Int, text: String) = edit { rc ->
        rc.setSelection(start, end) && run { rc.commitText(text, 1); true }
    }

    override fun insertAt(position: Int, text: String) = edit { rc ->
        rc.setSelection(position, position) && run { rc.commitText(text, 1); true }
    }

    override fun insertAtCursor(text: String) = edit { rc -> rc.commitText(text, 1); true }

    override fun setCursor(position: Int) = edit { rc -> rc.setSelection(position, position) }
}
