// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AikbMotion
import helium314.keyboard.latin.ai.DesignScale
import helium314.keyboard.latin.ai.PanelPalette
import helium314.keyboard.latin.common.Colors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Voice panel over the keys (same size, so nothing jumps; never higher than the keyboard), in the design of the promo
 * video (docs/DESIGN.md, 5е-3): "Listening · language · OpenAI" in bold gold, the round microphone with three pulsing gold
 * rings, four level bars on each side that follow the real microphone level. A tap on the circle finishes the recording
 * and sends it; under the circle a small grey hint says so. At the bottom one "Cancel" over the whole width, as in 5e
 * (the owner on build 4351: no "Done" button). After an error the golden "Retry" is next to "Cancel". The system
 * recognizer has no gold.
 * Animations stop with "Remove animations" in the system settings.
 */
@SuppressLint("ViewConstructor")
class VoiceInputView(context: Context, colors: Colors, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun onMic()
        /** the circle while recording: finish and send */
        fun onDone()
        fun onCancel()
        /** continuous dictation: "Phrase not recognized · Retry" (stage 5g) */
        fun onRetryPhrase() {}
        /** the "Journal" link under an error (stage 5g-4): opens the voice diagnostics */
        fun onOpenJournal() {}
        /** stage 5g-5: OpenAI could not be reached: the link opens the AI screen with the proxy and the check */
        fun onOpenConnection() {}
        /** stage 5g-27: the line of the mode of muting the car's radio (developer mode): the next mode */
        fun onCarMode() {}
    }

    private val palette = PanelPalette.from(colors)
    private val art = MicArt(context)
    private val status = TextView(context)
    /** partial text or the hint, the reason line about OpenAI and the signal level after "didn't catch that" */
    private val info = LinearLayout(context)
    private val partialText = TextView(context)
    private val noticeText = TextView(context)
    private val signalText = TextView(context)
    /** stage 5g-27: "🚗 Mode: call via the car" (developer mode, a car there): a tap switches to the next mode */
    private val carModeText = TextView(context)
    /** the link and the long press open the journal only in the developer mode (stage 5g-4) */
    private var journalAllowed = false
    /** "Journal" under an error: a long press on the error text does the same */
    private val journalLink = TextView(context)
    /** continuous dictation: "Phrase not recognized · Retry", golden, a tap sends the failed phrases again */
    private val retryPhraseText = TextView(context)
    /** "Tap the microphone to finish", small and grey under the circle while recording */
    private val finishHint = TextView(context)
    private val cancelButton = TextView(context)
    private val retryButton = TextView(context)
    private var labelContext: Context = labelContextFor(context, Locale.ENGLISH)
    private var labelLocale: Locale = Locale.ENGLISH
    private var lastError: VoiceError? = null
    private var lastState: VoiceState? = null
    private var lastPartial = ""
    private var openAi = false
    private var notice: helium314.keyboard.latin.voiceapi.VoiceApiProblem? = null
    private var switched = false
    private var signalPeak: Double? = null
    private var signalSource: String? = null
    private var phase: Phase? = null
    /** stage 5g: continuous dictation and its counters */
    private var continuous = false
    private var phrases = 0
    private var recognizing = 0
    private var failedPhrases = 0
    /** a long press asked for continuous dictation, but it works only with OpenAI */
    private var continuousUnavailable = false
    /** stage 5g: the headset or the phone records (null: unknown, the system recognizer) */
    private var micHeadset: Boolean? = null
    private var micFellBack = false
    private var micMissing = false

    /** what happens while the phrase is processed (stage 5zh: a clear status on every step) */
    sealed class Phase {
        class Sending(val kb: Long) : Phase()
        object Recognizing : Phase()
    }

    init {
        id = R.id.voice_input_panel
        isClickable = true // touches must not reach the keys below
        setBackgroundColor(palette.background)

        addView(art)
        status.setTypeface(status.typeface, Typeface.BOLD)
        status.gravity = Gravity.CENTER
        status.maxLines = 1 // stage 5g-13: one line, shrunk to fit (fitStatus), never over the hint below
        status.ellipsize = TextUtils.TruncateAt.END
        status.includeFontPadding = false
        addView(status)

        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER_HORIZONTAL
        for (t in listOf(partialText, noticeText, signalText, retryPhraseText)) {
            t.gravity = Gravity.CENTER
            t.setTextColor(palette.grey)
            t.includeFontPadding = false
            info.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        partialText.setTextColor(palette.text)
        partialText.setHintTextColor(palette.grey)
        partialText.maxLines = 1
        partialText.ellipsize = TextUtils.TruncateAt.START // the newest words stay visible
        noticeText.maxLines = 2
        noticeText.visibility = View.GONE
        signalText.visibility = View.GONE
        retryPhraseText.setTextColor(palette.goldText)
        retryPhraseText.visibility = View.GONE
        retryPhraseText.setOnClickListener { actions.onRetryPhrase() }
        // ux-11 (the owner: "I can't tap it"): the line lay in the box of the info lines and ran out of it, where no touch
        // reaches; now a button of its own at the top of the panel, at least 48 dp high
        setupButton(carModeText, palette.key, palette.goldText)
        carModeText.visibility = View.GONE
        carModeText.minHeight = (48 * resources.displayMetrics.density).toInt()
        carModeText.setOnClickListener { actions.onCarMode() }
        journalLink.gravity = Gravity.CENTER
        journalLink.setTextColor(palette.goldText)
        journalLink.paintFlags = journalLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        journalLink.minHeight = (48 * resources.displayMetrics.density).toInt() // a finger needs 48 dp
        journalLink.visibility = View.GONE
        journalLink.setOnClickListener { if (networkLink()) actions.onOpenConnection() else actions.onOpenJournal() }
        status.setOnLongClickListener { if (lastState == VoiceState.ERROR && journalAllowed) { actions.onOpenJournal(); true } else false }
        addView(info)
        addView(journalLink)

        finishHint.gravity = Gravity.CENTER
        finishHint.setTextColor(palette.grey)
        finishHint.includeFontPadding = false
        finishHint.maxLines = 1
        finishHint.ellipsize = TextUtils.TruncateAt.END
        finishHint.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the circle itself says "Finish recording"
        addView(finishHint)
        setupButton(cancelButton, palette.key, palette.text)
        setupButton(retryButton, palette.gold, palette.onGold)
        cancelButton.setOnClickListener { actions.onCancel() }
        retryButton.setOnClickListener { actions.onMic() }
        retryButton.visibility = View.GONE
        addView(cancelButton)
        addView(retryButton)
        addView(carModeText)
        art.setOnClickListener {
            when (lastState) {
                VoiceState.LISTENING -> actions.onDone() // finish and send
                VoiceState.PROCESSING -> Unit // the phrase is on its way already
                else -> actions.onMic() // "Retry"
            }
        }
        applyLabels()
    }

    private fun setupButton(button: TextView, background: Int, foreground: Int) {
        button.gravity = Gravity.CENTER
        button.setTextColor(foreground)
        button.isClickable = true
        button.maxLines = 1
        button.ellipsize = TextUtils.TruncateAt.END
        button.background = GradientDrawable().apply { setColor(background) }
    }

    // ---- layout in the coordinates of the design ----

    private fun scale() = DesignScale(width.coerceAtLeast(1), height.coerceAtLeast(1))

    private fun place(v: View, l: Float, t: Float, r: Float, b: Float) {
        val w = (r - l).toInt().coerceAtLeast(0); val h = (b - t).toInt().coerceAtLeast(0)
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        v.layout(l.toInt(), t.toInt(), l.toInt() + w, t.toInt() + h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    /** stage 5g-13: the status is one line; a text wider than [width] gets a smaller font, down to 12 sp, then "…" */
    private fun fitStatus(width: Float) {
        val text = status.text?.toString().orEmpty()
        if (text.isEmpty() || width <= 0f) return
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val paint = android.text.TextPaint(status.paint)
        var size = status.textSize
        paint.textSize = size
        while (size > min && paint.measureText(text) > width) { size -= 1f; paint.textSize = size }
        if (size != status.textSize) status.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val s = DesignScale(right - left, bottom - top)
        val w = (right - left).toFloat(); val h = (bottom - top).toFloat()
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(50f))
        fitStatus(w - 2 * s.x(40f))
        for (t in listOf(partialText, noticeText, signalText, retryPhraseText)) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(32f))
        finishHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(26f))
        for (b in listOf(cancelButton, retryButton)) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(52f))
            (b.background as GradientDrawable).cornerRadius = s.size(36f)
        }
        place(art, 0f, 0f, w, h)
        // ux-11: the car mode button at the top (above the status; the rings of the circle never reach it)
        carModeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(36f))
        (carModeText.background as GradientDrawable).cornerRadius = s.size(36f)
        val carH = maxOf(s.size(110f), carModeText.minHeight.toFloat())
        // design y counts from the top of the strip (120 above the panel): the panel starts at 0 here
        val carTop = 0f
        place(carModeText, s.x(200f), carTop, w - s.x(200f), carTop + carH)
        // status around y 300 of the design, the info lines between it and the circle
        place(status, s.x(40f), s.y(250f), w - s.x(40f), s.y(345f))
        place(info, s.x(40f), s.y(345f), w - s.x(40f), s.y(430f))
        // the bottom row, height 120 with a margin of 40: "Cancel" over the whole width (5e); after an error the golden
        // "Retry" on the left (60 %) and "Cancel" on the right
        val bottomY = h - s.size(40f)
        val topY = bottomY - s.size(120f)
        val left = s.x(40f); val right = w - s.x(40f); val gap = s.x(20f)
        val split = left + (right - left - gap) * GOLD_SHARE
        if (retryButton.visibility == View.VISIBLE) {
            place(retryButton, left, topY, split, bottomY)
            place(cancelButton, split + gap, topY, right, bottomY)
        } else {
            place(cancelButton, left, topY, right, bottomY)
            place(retryButton, 0f, 0f, 0f, 0f)
        }
        // the hint right above "Cancel", under the rings (the outer one reaches 190 only at the top of its pulse)
        val hintBottom = topY - s.size(2f)
        val hintTop = maxOf(hintBottom - s.size(36f), s.y(560f) + s.size(FINISH_HINT_FROM_CENTER))
        // under the error: a small link to the journal, above "Cancel"
        place(journalLink, s.x(40f), topY - s.size(70f), w - s.x(40f), topY - s.size(4f))
        place(finishHint, s.x(40f), hintTop, w - s.x(40f), maxOf(hintBottom, hintTop + s.size(30f)))
    }

    // ---- labels and state ----

    /** all labels of the panel are in the language of the active layout, not of the phone; no translation: English */
    fun setLabelLocale(locale: Locale) {
        if (locale == labelLocale) return
        labelLocale = locale
        labelContext = labelContextFor(context, locale)
        applyLabels()
    }

    private fun applyLabels() {
        finishHint.text = labelContext.getString(R.string.voice_finish_hint)
        cancelButton.text = labelContext.getString(R.string.voice_cancel)
        retryButton.text = labelContext.getString(R.string.voice_retry)
        if (lastState != null) update(lastState!!, lastPartial, lastError) else updateDescription()
    }

    /** voice through OpenAI is used for this dictation (the status says so) */
    fun setOpenAi(on: Boolean) {
        openAi = on
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /** [problem] null: no line; [switchedToSystem]: OpenAI failed and the system recognizer listens now */
    fun setNotice(problem: helium314.keyboard.latin.voiceapi.VoiceApiProblem?, switchedToSystem: Boolean) {
        notice = problem
        switched = switchedToSystem
        if (switchedToSystem) openAi = false
        lastState?.let { update(it, lastPartial, lastError) } ?: applyNotice()
    }

    /** continuous dictation (stage 5g) is used for this dictation: the status and the counters say so */
    fun setContinuous(on: Boolean) {
        continuous = on
        if (!on) { phrases = 0; recognizing = 0; failedPhrases = 0 }
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /** a long press asked for continuous dictation, but this dictation goes through the system recognizer */
    fun setContinuousUnavailable(on: Boolean) {
        continuousUnavailable = on
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /**
     * The microphone of the recording (stage 5g): "headset" or "phone" after the status; [fellBack]: the headset did not
     * answer, [missing]: "Headset" was chosen but none is connected (a line says so)
     */
    fun setMic(headset: Boolean?, fellBack: Boolean, missing: Boolean) {
        micHeadset = headset; micFellBack = fellBack; micMissing = missing
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /** continuous dictation: [phrases] inserted, [recognizing] on their way, [failed] not recognized */
    fun setProgress(phrases: Int, recognizing: Int, failed: Int) {
        this.phrases = phrases; this.recognizing = recognizing; failedPhrases = failed
        lastState?.let { update(it, lastPartial, lastError) }
    }

    private fun applyNotice() {
        val p = notice
        val note = when {
            p != null || lastState == VoiceState.ERROR -> null
            continuousUnavailable -> R.string.voice_continuous_only_openai
            // stage 5g-2: no line when the headset did not answer, only the word "phone" (the journal knows why)
            micMissing && openAi -> R.string.voice_mic_headset_missing
            netWaiting -> R.string.voice_waiting_network // stage 5g-20
            else -> null
        }
        if (note != null) {
            noticeText.text = labelContext.getString(note)
            noticeText.visibility = View.VISIBLE
            return
        }
        if (p == null || lastState == VoiceState.ERROR) { noticeText.visibility = View.GONE; return }
        val reason = helium314.keyboard.latin.voiceapi.VoiceApi.problemText(labelContext, p)
        noticeText.text = labelContext.getString(if (switched) R.string.voice_api_notice_switched else R.string.voice_api_notice_system, reason)
        noticeText.visibility = View.VISIBLE
    }

    /** stage 5g-20: pieces wait for the network: a quiet line; [askRetry]: "No connection · Retry" (after 2 min) */
    private var netWaiting = false
    private var netAskRetry = false
    fun setNetwork(waiting: Boolean, askRetry: Boolean) {
        netWaiting = waiting; netAskRetry = waiting && askRetry
        lastState?.let { update(it, lastPartial, lastError) } ?: applyNotice()
    }
    /** for tests: the quiet line under the status (null while hidden) */
    fun noticeLine(): String? = noticeText.takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    /** "Sending… N KB", then "Recognizing…" */
    fun setPhase(p: Phase?) {
        phase = p
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /** level of the last recording (OpenAI) for "didn't catch that"; null: unknown */
    fun setSignal(peakDbfs: Double?, source: String?) {
        signalPeak = peakDbfs
        signalSource = source
        lastState?.let { update(it, lastPartial, lastError) }
    }

    private fun applySignal(state: VoiceState, error: VoiceError?) {
        if (state != VoiceState.ERROR || error != VoiceError.NO_SPEECH) { signalText.visibility = View.GONE; return }
        val peak = signalPeak
        signalText.text = if (peak != null && signalSource != null)
            labelContext.getString(R.string.voice_signal_level, formatDbfs(peak), signalSource)
        else labelContext.getString(R.string.voice_signal_system)
        signalText.visibility = View.VISIBLE
    }

    /** [partial] is only shown, never stored */
    fun update(state: VoiceState, partial: String, error: VoiceError?) {
        lastState = state; lastPartial = partial; lastError = error
        if (state != VoiceState.PROCESSING) phase = null
        val languageName = labelLocale.getDisplayLanguage(labelLocale)
        status.setTextColor(when {
            state == VoiceState.ERROR -> palette.red
            openAi -> palette.goldText
            else -> palette.text
        })
        carStatus = btCar && btConnecting && micHeadset != true || carReady
        status.text = when (state) {
            // the engine is always named next to "Listening" (stage 5b)
            // stage 5g-2: the headset is shown by the blue Bluetooth badge at the circle, the phone by nothing (the default)
            VoiceState.LISTENING -> if (carReady) labelContext.getString(R.string.voice_car_ready)
                else if (btCar && btConnecting && micHeadset != true) labelContext.getString(R.string.voice_car_connecting)
                else if (continuous && openAi) labelContext.getString(R.string.voice_listening_continuous)
                else labelContext.getString(if (openAi) R.string.voice_listening_openai else R.string.voice_listening_system, languageName)
            VoiceState.PROCESSING -> when (val ph = phase) {
                is Phase.Sending -> labelContext.getString(R.string.voice_status_sending, ph.kb)
                else -> labelContext.getString(if (openAi) R.string.voice_processing_openai else R.string.voice_processing)
            }
            VoiceState.ERROR -> errorText(error, languageName)
            else -> ""
        }
        if (state == VoiceState.ERROR) {
            partialText.text = ""
            partialText.hint = labelContext.getString(R.string.voice_retry_hint)
        } else if (continuous && openAi) {
            // "Phrases: 3 · recognizing: 1" instead of the hint (stage 5g)
            partialText.text = labelContext.getString(R.string.voice_continuous_progress, phrases, recognizing)
        } else {
            partialText.text = partial
            // stage 5g-13: one line of status at a time: while the car connects the hint below is hidden
            partialText.hint = if (carStatus && state == VoiceState.LISTENING) "" else labelContext.getString(R.string.voice_hint)
        }
        // stage 5g-20: the network did not come back for 2 min: "No connection · Retry" (the sound is kept)
        val netRetry = netAskRetry && openAi && (state == VoiceState.LISTENING || state == VoiceState.PROCESSING)
        // a phrase dropped for another reason has no sound left to send again: counted at the end, no "Retry"
        val showRetry = netRetry
        retryPhraseText.text = if (netRetry) labelContext.getString(R.string.voice_network_retry) else ""
        retryPhraseText.visibility = if (showRetry) View.VISIBLE else View.GONE
        // no network to OpenAI: a link to the connection settings for everybody (5g-5); else the journal for developers
        journalLink.text = labelContext.getString(if (state == VoiceState.ERROR && networkLink()) R.string.voice_connection_link else R.string.voice_journal_link)
        journalLink.visibility = if (state == VoiceState.ERROR && (journalAllowed || networkLink())) View.VISIBLE else View.GONE
        val retry = if (state == VoiceState.ERROR) View.VISIBLE else View.GONE
        if (retryButton.visibility != retry) { retryButton.visibility = retry; requestLayout() }
        // only while recording: the hint stays in its place (invisible) otherwise, so nothing jumps
        finishHint.visibility = if (state == VoiceState.LISTENING) View.VISIBLE else View.INVISIBLE
        art.update(state, openAi)
        // the blue Bluetooth badge: only while the headset really records (its channel is up and gives sound)
        art.bluetooth = state == VoiceState.LISTENING && openAi && (micHeadset == true || btConnecting)
        art.bluetoothDim = btConnecting && micHeadset != true
        // stage 5zh: no screen timeout while recording (the user speaks and does not touch the screen); released as soon
        // as the recording ends. A flag of the keyboard window, no permission needed.
        // continuous dictation (stage 5g): the whole session, also while its last phrases are recognized
        keepScreenOn = state == VoiceState.LISTENING || (continuous && state == VoiceState.PROCESSING)
        updateDescription()
        applyNotice()
        applySignal(state, error)
    }

    private fun updateDescription() {
        art.contentDescription = labelContext.getString(if (lastState == VoiceState.ERROR) R.string.voice_retry else R.string.voice_finish_desc) +
            ". " + labelContext.getString(if (openAi) R.string.mic_desc_ai else R.string.mic_desc_system) +
            (if (art.bluetooth && !art.bluetoothDim) ". " + labelContext.getString(R.string.voice_mic_bluetooth_desc) else "") +
            (if (art.carPaused && !art.bluetooth) ". " + labelContext.getString(R.string.voice_car_music_paused) else "")
    }

    /** the real sound level of the microphone, 0..1 */
    fun setLevel(level: Float) = art.setLevel(level)

    /** for tests: the circle and icon colors of the big microphone, the status line */
    fun micCircleColor() = art.circleColor
    fun micIconColor() = art.iconColor
    fun statusText(): String = status.text.toString()
    /** for tests: the second line (counters of a continuous dictation, the hint, the partial text) */
    fun infoText(): String = partialText.text.toString()
    /** for tests: "Phrase not recognized · Retry" (null while hidden) */
    fun retryPhraseLine(): TextView? = retryPhraseText.takeIf { it.visibility == View.VISIBLE }
    fun statusColor() = status.currentTextColor
    fun micDescription(): String = art.contentDescription?.toString() ?: ""
    /** for tests: the blue Bluetooth badge is drawn (stage 5g-2) */
    fun bluetoothShown() = art.bluetooth
    /** for tests: the badge is dimmed, "connecting" (stage 5g-5) */
    fun bluetoothConnecting() = art.bluetooth && art.bluetoothDim

    /** stage 5g-27: the line of the mode of muting the car (null: hidden) */
    fun setCarMode(label: String?) {
        carModeText.text = label ?: ""
        carModeText.visibility = if (label == null) View.GONE else View.VISIBLE
    }
    /** for tests */
    fun carModeLine(): String? = carModeText.takeIf { it.visibility == View.VISIBLE }?.text?.toString()
    /** for tests (ux-11): the car mode button: its place in the panel, and a tap on it */
    fun carModeButton(): View = carModeText

    /**
     * stage 5g-26: the "quiet call" holds the car's music paused: a small car badge at the lower left of the circle
     * instead of the Bluetooth one (that one means the car's microphone records, which it does not now)
     */
    fun setCarPaused(on: Boolean) {
        if (art.carPaused == on) return
        art.carPaused = on
        updateDescription()
    }
    /** for tests */
    fun carPausedShown() = art.carPaused && !art.bluetooth

    /** stage 5g-5: proven earbuds are being asked for: a dimmed badge at once, bright when they give sound */
    private var btConnecting = false
    /** stage 5g-10: the device being connected is a car (not proven earbuds): the status says so */
    private var btCar = false
    fun setBluetoothConnecting(on: Boolean, car: Boolean = false) {
        btConnecting = on
        btCar = on && car
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /**
     * stage 5g-10: the channel of a car is up: a short tick, "speak" (the phone does not hear the first words while the
     * channel comes up). A haptic feedback of the view: no permission, and nothing if the system has it off.
     */
    fun tickReady() {
        try { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) } catch (_: Exception) { }
        readyTicks++
        // "Speak" for a moment (the tick), then the usual status
        carReady = true
        lastState?.let { update(it, lastPartial, lastError) }
        postDelayed({ carReady = false; lastState?.let { update(it, lastPartial, lastError) } }, 1500)
    }
    /** for tests (5g-13): the status is on one line, whole, and does not reach the texts below it */
    fun statusFits(): Boolean {
        val l = status.layout ?: return true
        return l.lineCount <= 1 && l.getEllipsisCount(0) == 0 && status.bottom <= info.top + 1 && status.width > 0
    }
    private var carReady = false
    private var carStatus = false
    /** for tests */
    var readyTicks = 0
        private set
    /** for tests: the center of the badge in px of the panel */
    fun bluetoothBadgeCenter(): Pair<Float, Float> = scale().let { s -> width / 2f + s.size(BT_BADGE_OFFSET) to s.y(560f) + s.size(BT_BADGE_OFFSET) }
    /** for tests: the big microphone (a tap finishes the recording) */
    fun micCircle(): View = art
    /** the error is that OpenAI could not be reached */
    private fun networkLink() = lastError == VoiceError.OPENAI && notice == helium314.keyboard.latin.voiceapi.VoiceApiProblem.NETWORK

    /** the developer mode is on: the "Journal" link may show */
    fun setJournalAllowed(on: Boolean) {
        journalAllowed = on
        lastState?.let { update(it, lastPartial, lastError) }
    }

    /** for tests: the "Journal" link under an error (null while hidden) */
    fun journalLink(): TextView? = journalLink.takeIf { it.visibility == View.VISIBLE }
    /** for tests: the status text, long-pressed after an error it opens the journal */
    fun statusView(): TextView = status
    /** for tests: the grey hint under the circle (null while it is not shown) */
    fun finishHint(): TextView? = finishHint.takeIf { it.visibility == View.VISIBLE }
    /** for tests: the bars and rings as they are drawn now */
    fun barHalfHeights(): List<Float> = art.barHalfHeights()
    fun ringsPulse() = art.isPulsing
    /** for tests and pictures: draw the animation at this moment, without the clock */
    fun freezeTime(seconds: Float) = art.freeze(seconds)

    private fun errorText(error: VoiceError?, languageName: String) = when (error) {
        VoiceError.LANGUAGE_NOT_SUPPORTED -> labelContext.getString(R.string.voice_error_language, languageName)
        VoiceError.NETWORK -> labelContext.getString(R.string.voice_error_network)
        VoiceError.AUDIO -> labelContext.getString(R.string.voice_error_audio)
        VoiceError.BUSY -> labelContext.getString(R.string.voice_error_busy)
        VoiceError.PERMISSION -> labelContext.getString(R.string.voice_error_permission)
        VoiceError.MIC_BUSY -> labelContext.getString(R.string.voice_error_mic_busy)
        VoiceError.MIC_SILENCED -> labelContext.getString(R.string.voice_error_mic_silenced)
        VoiceError.NO_SPEECH -> labelContext.getString(R.string.voice_not_heard)
        VoiceError.ONLY_NOISE -> labelContext.getString(R.string.voice_only_noise)
        VoiceError.OPENAI -> labelContext.getString(R.string.voice_api_error,
            notice?.let { helium314.keyboard.latin.voiceapi.VoiceApi.problemText(labelContext, it) } ?: labelContext.getString(R.string.voice_error_other))
        else -> labelContext.getString(R.string.voice_error_other)
    }

    companion object {
        /** "−45" with a real minus sign */
        fun formatDbfs(dbfs: Double): String = String.format(Locale.ROOT, "%.0f", dbfs).replace('-', '−')

        /** resources in [locale]; strings without a translation come from the default (English) resources */
        fun labelContextFor(base: Context, locale: Locale): Context {
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }

        /** design: ring k (0..2) at the pulse p: radius 120 + (18 + 26k)(0.4 + 0.6p), alpha 120, 85, 50 */
        fun ringRadius(k: Int, p: Float) = 120f + (18f + 26f * k) * (0.4f + 0.6f * p)
        val RING_ALPHA = intArrayOf(120, 85, 50)
        /** design: bar i (0..3) at 190 + 34i from the center, 16 wide, half height 14 + 54 × level, alpha 200 − 35i */
        fun barOffset(i: Int) = 190f + 34f * i
        fun barHalfHeight(level: Float) = 14f + 54f * level.coerceIn(0f, 1f)
        fun barAlpha(i: Int) = 200 - 35 * i
        /** p = 0.5 + 0.5 sin(6t) */
        fun pulse(t: Float) = 0.5f + 0.5f * sin(6f * t)
        /** the hint under the circle starts this far below its center (the outer ring reaches 190 at most) */
        const val FINISH_HINT_FROM_CENTER = 186f
        /** the Bluetooth badge (stage 5g-2): its center this far right of and below the center of the circle, radius 34 */
        const val BT_BADGE_OFFSET = 86f
        const val BT_BADGE_RADIUS = 34f
        /** blue of the badge: the white sign on it has a contrast of at least 4.5 : 1; a ring in the panel color separates it from the circle */
        const val BT_BLUE_DARK = 0xFF2F6FE0.toInt()
        const val BT_BLUE_LIGHT = 0xFF1E5BC8.toInt()
        /** after an error the golden "Retry" takes this share of the bottom row, "Cancel" the rest */
        const val GOLD_SHARE = 0.6f
        /** about 30 frames per second while the rings pulse */
        private const val FRAME_MS = 33L
    }

    /**
     * The circle with the microphone, the rings and the level bars, drawn over the whole panel. The bars show the last
     * levels of the microphone, the newest next to the circle, so the sound runs outwards; nothing is invented.
     */
    private inner class MicArt(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_voice_rounded)?.mutate()
        private val levels = FloatArray(4)
        private var state: VoiceState? = null
        private var gold = false
        private val start = SystemClock.uptimeMillis()
        private var frozen: Float? = null
        private val rect = RectF()
        var circleColor = palette.key
            private set
        var iconColor = palette.text
            private set
        /** stage 5g-2: the headset records: a small blue Bluetooth badge at the lower right of the circle */
        var bluetooth = false
            set(value) { if (field != value) { field = value; invalidate() } }
        /** "connecting": the badge at 45 % */
        var bluetoothDim = false
            set(value) { if (field != value) { field = value; invalidate() } }
        private val btPath = android.graphics.Path()
        /** stage 5g-26: the car's music is paused by the quiet call */
        var carPaused = false
            set(value) { if (field != value) { field = value; invalidate() } }

        /** a small round badge with a car in the panel color ring, at the lower left of the circle */
        private fun drawCar(canvas: Canvas, x: Float, y: Float, r: Float) {
            paint.style = Paint.Style.FILL
            paint.color = palette.background
            canvas.drawCircle(x, y, r + max(1f, r * 0.15f), paint)
            paint.color = palette.key
            canvas.drawCircle(x, y, r, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = r * 1.15f
            paint.color = palette.text
            val fm = paint.fontMetrics
            canvas.drawText("\uD83D\uDE97", x, y - (fm.ascent + fm.descent) / 2, paint)
        }
        val isPulsing get() = state == VoiceState.LISTENING || state == VoiceState.PROCESSING
        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                if (isAttachedToWindow && isPulsing && frozen == null && AikbMotion.enabled(context)) postDelayed(this, FRAME_MS)
            }
        }

        init {
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fun update(state: VoiceState, gold: Boolean) {
            val wasPulsing = isPulsing
            this.state = state
            this.gold = gold
            circleColor = palette.key
            iconColor = if (gold) palette.gold else palette.text
            icon?.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            if (state != VoiceState.LISTENING) levels.fill(0f)
            if (isPulsing && !wasPulsing) { removeCallbacks(tick); post(tick) }
            invalidate()
        }

        fun setLevel(level: Float) {
            if (state != VoiceState.LISTENING) return
            for (i in levels.size - 1 downTo 1) levels[i] = levels[i - 1]
            levels[0] = level.coerceIn(0f, 1f)
            invalidate()
        }

        fun barHalfHeights() = levels.map { barHalfHeight(it) }

        fun freeze(seconds: Float) { frozen = seconds; invalidate() }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (isPulsing) { removeCallbacks(tick); post(tick) }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        private fun center(s: DesignScale) = (width / 2f) to s.y(560f)

        /**
         * Our own drawing of the Bluetooth sign (a vertical stroke with two arrow heads to the right and the two crossing
         * strokes to the left) in white on a blue circle, with a ring in the panel color around it.
         */
        private fun drawBluetooth(canvas: Canvas, x: Float, y: Float, r: Float) {
            paint.style = Paint.Style.FILL
            paint.color = palette.background
            canvas.drawCircle(x, y, r + max(1f, r * 0.15f), paint)
            paint.color = if (palette.isDark) BT_BLUE_DARK else BT_BLUE_LIGHT
            if (bluetoothDim) paint.color = AikbMotion.blend(palette.background, paint.color, 0.45f)
            canvas.drawCircle(x, y, r, paint)
            val h = r * 0.55f // half height of the sign
            val w = r * 0.30f // reach of the arrow heads
            btPath.reset()
            btPath.moveTo(x - w, y - h * 0.5f)
            btPath.lineTo(x + w, y + h * 0.5f)
            btPath.lineTo(x, y + h)
            btPath.lineTo(x, y - h)
            btPath.lineTo(x + w, y - h * 0.5f)
            btPath.lineTo(x - w, y + h * 0.5f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1.5f, r * 0.13f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawPath(btPath, paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val s = scale()
            val (cx, cy) = center(s)
            // only the circle (and a little around it) is the button; elsewhere the panel takes the touch
            if (event.actionMasked == MotionEvent.ACTION_DOWN && hypot(event.x - cx, event.y - cy) > s.size(150f)) return false
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val s = scale()
            val (cx, cy) = center(s)
            val accent = if (gold) palette.gold else palette.grey
            if (isPulsing) {
                val t = frozen ?: if (AikbMotion.enabled(context)) (SystemClock.uptimeMillis() - start) / 1000f else (PI / 12).toFloat()
                val p = pulse(t)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, s.size(4f))
                for (k in 0..2) {
                    paint.color = AikbMotion.withAlpha(accent, RING_ALPHA[k])
                    canvas.drawCircle(cx, cy, s.size(ringRadius(k, p)), paint)
                }
            }
            if (state == VoiceState.LISTENING) {
                paint.style = Paint.Style.FILL
                val halfW = s.size(8f)
                for (i in 0..3) {
                    paint.color = AikbMotion.withAlpha(accent, barAlpha(i))
                    val half = s.size(barHalfHeight(levels[i]))
                    for (side in intArrayOf(-1, 1)) {
                        val x = cx + side * s.size(barOffset(i))
                        rect.set(x - halfW, cy - half, x + halfW, cy + half)
                        canvas.drawRoundRect(rect, halfW, halfW, paint)
                    }
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = circleColor
            canvas.drawCircle(cx, cy, s.size(120f), paint)
            if (bluetooth) drawBluetooth(canvas, cx + s.size(BT_BADGE_OFFSET), cy + s.size(BT_BADGE_OFFSET), s.size(BT_BADGE_RADIUS))
            else if (carPaused && state == VoiceState.LISTENING) drawCar(canvas, cx - s.size(BT_BADGE_OFFSET), cy + s.size(BT_BADGE_OFFSET), s.size(BT_BADGE_RADIUS))
            icon?.let {
                val h = s.size(120f)
                val w = h * it.intrinsicWidth / max(1, it.intrinsicHeight)
                it.setBounds((cx - w / 2).toInt(), (cy - h / 2).toInt(), (cx + w / 2).toInt(), (cy + h / 2).toInt())
                it.draw(canvas)
            }
        }
    }

}
