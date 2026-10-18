// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.voice.VoiceInputView
import java.util.Locale

/**
 * The ✨ panel over the keys (same place and size as the voice panel, never higher than the keyboard), in the design of
 * the promo video (docs/DESIGN.md, 5е-3). Commands: "What to do with the text?" and a grid of 2 × 3 chips that turn gold
 * when pressed; the panel slides up. Preview: the gold spark with the command, a card where the answer streams in, then
 * "Replace" (gold), "Insert next to" and "Cancel". "AI (OpenAI), your API key · Balance" stays visible at the bottom.
 * Labels in the language of the active layout (English if there is no translation). The answer is only shown here,
 * never stored. Animations stop with "Remove animations" in the system settings.
 */
@SuppressLint("ViewConstructor")
class AiPanelView(context: Context, colors: Colors, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun onCommand(command: AiCommand, language: String?)
        fun onReplace()
        fun onInsert()
        fun onCancel()
        fun onOpenBalance()
        fun onOpenSettings()
    }

    enum class Mode { COMMANDS, WORKING, RESULT, MESSAGE }

    private val palette = PanelPalette.from(colors)

    private var labelLocale: Locale = Locale.ENGLISH
    private var labels: Context = VoiceInputView.labelContextFor(context, labelLocale)

    // commands
    private val title = TextView(context)
    private val commandButtons = linkedMapOf<Pair<AiCommand, String?>, TextView>()
    // preview
    private val sparkle = ImageView(context)
    private val heading = TextView(context)
    private val card = ScrollView(context)
    private val answer = TextView(context)
    private val note = TextView(context)
    private val replaceButton = TextView(context)
    private val insertButton = TextView(context)
    private val cancelButton = TextView(context)
    private val manageButton = TextView(context)
    private val settingsButton = TextView(context)
    // footer: "AI (OpenAI), your API key · Balance" and "Close"
    private val plan = TextView(context)
    private val manage = TextView(context)
    private val close = TextView(context)

    var mode = Mode.COMMANDS
        private set
    private var layoutLanguage = "en"
    private var secondLanguage = "ru"
    private var command: AiCommand? = null
    private var commandLanguage: String? = null
    private var canReplace = false
    private var messageRes = 0
    private var messageArg: Any? = null
    private var answerText = ""

    init {
        id = R.id.ai_panel
        isClickable = true // touches must not reach the keys below
        setBackgroundColor(palette.background)

        title.setTextColor(palette.text)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        addView(title)

        val entries = listOf(AiCommand.FIX to null, AiCommand.REWRITE to null, AiCommand.SHORTEN to null,
            AiCommand.CONTINUE to null, AiCommand.TRANSLATE to "layout", AiCommand.TRANSLATE to "second")
        for ((cmd, which) in entries) {
            val b = TextView(context)
            setupButton(b, palette.key, palette.text)
            setupChipPress(b)
            b.setOnClickListener { actions.onCommand(cmd, when (which) { "layout" -> layoutLanguage; "second" -> secondLanguage; else -> null }) }
            commandButtons[cmd to which] = b
            addView(b)
        }

        // the same golden spark (the logo) as the button in the strip
        sparkle.setImageResource(R.drawable.ic_aikb_sparkle)
        sparkle.colorFilter = PorterDuffColorFilter(palette.gold, PorterDuff.Mode.SRC_IN)
        sparkle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(sparkle)
        heading.setTextColor(palette.goldText)
        heading.setTypeface(heading.typeface, Typeface.BOLD)
        heading.gravity = Gravity.CENTER_VERTICAL
        heading.maxLines = 1
        heading.ellipsize = TextUtils.TruncateAt.END
        addView(heading)

        // the answer, scrollable, as plain text (never a link, never executed)
        answer.setTextColor(palette.text)
        answer.setTextIsSelectable(false)
        answer.linksClickable = false
        answer.autoLinkMask = 0
        card.addView(answer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.isVerticalScrollBarEnabled = true
        card.background = GradientDrawable().apply { setColor(palette.key) }
        addView(card)

        note.setTextColor(palette.red)
        note.gravity = Gravity.CENTER_VERTICAL
        note.maxLines = 2
        note.ellipsize = TextUtils.TruncateAt.END
        addView(note)

        setupButton(replaceButton, palette.gold, palette.onGold)
        setupButton(manageButton, palette.gold, palette.onGold)
        setupButton(settingsButton, palette.gold, palette.onGold)
        setupButton(insertButton, palette.key, palette.text)
        setupButton(cancelButton, palette.key, palette.text)
        cancelButton.setOnClickListener { actions.onCancel() }
        insertButton.setOnClickListener { actions.onInsert() }
        replaceButton.setOnClickListener { actions.onReplace() }
        manageButton.setOnClickListener { actions.onOpenBalance() }
        settingsButton.setOnClickListener { actions.onOpenSettings() }
        for (b in listOf(replaceButton, manageButton, settingsButton, insertButton, cancelButton)) addView(b)

        plan.setTextColor(palette.grey)
        plan.maxLines = 1
        plan.ellipsize = TextUtils.TruncateAt.END
        plan.gravity = Gravity.CENTER_VERTICAL
        addView(plan)
        for (link in listOf(manage, close)) {
            link.setTextColor(palette.text)
            link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            link.gravity = Gravity.CENTER_VERTICAL
            link.maxLines = 1
            addView(link)
        }
        manage.setOnClickListener { actions.onOpenBalance() }
        close.setOnClickListener { actions.onCancel() }

        applyLabels()
        showCommands(layoutLanguage, secondLanguage)
    }

    private fun setupButton(button: TextView, background: Int, foreground: Int) {
        button.gravity = Gravity.CENTER
        button.setTextColor(foreground)
        button.maxLines = 1
        button.ellipsize = TextUtils.TruncateAt.END
        button.isClickable = true
        button.background = GradientDrawable().apply { setColor(background) }
    }

    /** design: when pressed the fill turns gold smoothly, the text dark */
    private fun setupChipPress(chip: TextView) {
        var animator: ValueAnimator? = null
        fun to(goldness: Float) {
            animator?.cancel()
            val bg = chip.background as GradientDrawable
            val from = (chip.tag as? Float) ?: 0f
            fun apply(f: Float) {
                chip.tag = f
                bg.setColor(AikbMotion.blend(palette.key, palette.gold, f))
                chip.setTextColor(AikbMotion.blend(palette.text, palette.onGold, f))
            }
            if (!AikbMotion.enabled(context)) { apply(goldness); return }
            animator = ValueAnimator.ofFloat(from, goldness).apply {
                duration = PRESS_MS
                addUpdateListener { apply(it.animatedValue as Float) }
                start()
            }
        }
        chip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> to(1f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.postDelayed({ to(0f) }, if (e.actionMasked == MotionEvent.ACTION_UP) PRESS_MS else 0)
            }
            false // the click itself is handled by the view
        }
    }

    // ---- layout in the coordinates of the design ----

    private fun place(v: View, l: Float, t: Float, r: Float, b: Float) {
        val w = (r - l).toInt().coerceAtLeast(0); val h = (b - t).toInt().coerceAtLeast(0)
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        v.layout(l.toInt(), t.toInt(), l.toInt() + w, t.toInt() + h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private fun autoSize(t: TextView, maxPx: Float) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, (maxPx * 0.55f).toInt().coerceAtLeast(1),
            maxPx.toInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val s = DesignScale(right - left, bottom - top)
        val w = (right - left).toFloat()
        // commands: the title around y 190, chips 528 × 150 at x 30 + 560 col, y 250 + 190 row, rounded 40, label 52
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(48f))
        place(title, s.x(30f), s.y(150f), w - s.x(30f), s.y(232f))
        commandButtons.values.forEachIndexed { i, b ->
            val col = i % 2; val row = i / 2
            (b.background as GradientDrawable).cornerRadius = s.size(40f)
            autoSize(b, s.size(52f))
            b.setPadding(s.x(16f).toInt(), 0, s.x(16f).toInt(), 0)
            val x0 = s.x(30f + 560f * col); val y0 = s.y(250f + 190f * row)
            place(b, x0, y0, x0 + s.x(528f), y0 + s.size(150f))
        }
        // preview: spark and command name in gold around y 186; card x 30–1118, y 240–650, rounded 36, text 46 / line 64
        val iconSize = s.size(56f)
        place(sparkle, s.x(30f), s.y(186f) - iconSize / 2, s.x(30f) + iconSize, s.y(186f) + iconSize / 2)
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(46f))
        place(heading, s.x(30f) + iconSize + s.x(16f), s.y(150f), w - s.x(30f), s.y(222f))
        (card.background as GradientDrawable).cornerRadius = s.size(36f)
        answer.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(46f))
        TextViewCompat.setLineHeight(answer, s.size(64f).toInt().coerceAtLeast(1))
        val pad = s.size(30f).toInt()
        answer.setPadding(pad, pad, pad, pad)
        place(card, s.x(30f), s.y(240f), s.x(1118f), s.y(650f))
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(30f))
        place(note, s.x(40f), s.y(652f), w - s.x(40f), s.y(698f))
        // buttons y 700–840: Replace x 30–360 (gold), Insert next to 378–828, Cancel 846–1118; rounded 40, label 42
        for (b in listOf(replaceButton, manageButton, settingsButton, insertButton, cancelButton)) {
            (b.background as GradientDrawable).cornerRadius = s.size(40f)
            autoSize(b, s.size(42f))
            b.setPadding(s.x(10f).toInt(), 0, s.x(10f).toInt(), 0)
        }
        val by0 = s.y(700f); val by1 = s.y(840f)
        for (b in listOf(replaceButton, manageButton, settingsButton)) place(b, s.x(30f), by0, s.x(360f), by1)
        place(insertButton, s.x(378f), by0, s.x(828f), by1)
        place(cancelButton, s.x(846f), by0, s.x(1118f), by1)
        // footer under both layouts
        val fy0 = s.y(858f); val fy1 = s.y(922f)
        for (t in listOf(plan, manage, close)) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.size(32f))
        close.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        manage.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val closeL = w - s.x(30f) - close.measuredWidth
        place(close, closeL, fy0, w - s.x(30f), fy1)
        val linksEnd = if (close.visibility == View.VISIBLE) closeL - s.x(24f) else w - s.x(30f)
        val planR = (linksEnd - manage.measuredWidth - s.x(8f)).coerceAtLeast(s.x(30f))
        place(plan, s.x(30f), fy0, planR, fy1)
        place(manage, planR + s.x(8f), fy0, planR + s.x(8f) + manage.measuredWidth, fy1)
    }

    // ---- content ----

    fun setLabelLocale(locale: Locale) {
        if (locale == labelLocale) return
        labelLocale = locale
        labels = VoiceInputView.labelContextFor(context, locale)
        applyLabels()
    }

    private fun languageLabel(tag: String) = Locale.forLanguageTag(tag).getDisplayLanguage(labelLocale).replaceFirstChar { it.titlecase(labelLocale) }

    private fun commandLabel(c: AiCommand, language: String?) = when (c) {
        AiCommand.FIX -> labels.getString(R.string.ai_cmd_fix)
        AiCommand.REWRITE -> labels.getString(R.string.ai_cmd_rewrite)
        AiCommand.SHORTEN -> labels.getString(R.string.ai_cmd_shorten)
        AiCommand.CONTINUE -> labels.getString(R.string.ai_cmd_continue)
        AiCommand.TRANSLATE -> labels.getString(R.string.ai_cmd_translate_to, languageLabel(language ?: layoutLanguage))
    }

    private fun applyLabels() {
        title.text = labels.getString(R.string.ai_commands_title)
        plan.text = labels.getString(R.string.ai_panel_source) + " ·"
        manage.text = labels.getString(R.string.ai_balance)
        close.text = labels.getString(R.string.ai_close)
        commandButtons[AiCommand.FIX to null]?.text = commandLabel(AiCommand.FIX, null)
        commandButtons[AiCommand.REWRITE to null]?.text = commandLabel(AiCommand.REWRITE, null)
        commandButtons[AiCommand.SHORTEN to null]?.text = commandLabel(AiCommand.SHORTEN, null)
        commandButtons[AiCommand.CONTINUE to null]?.text = commandLabel(AiCommand.CONTINUE, null)
        commandButtons[AiCommand.TRANSLATE to "layout"]?.text = commandLabel(AiCommand.TRANSLATE, layoutLanguage)
        commandButtons[AiCommand.TRANSLATE to "second"]?.text = commandLabel(AiCommand.TRANSLATE, secondLanguage)
        replaceButton.text = labels.getString(R.string.ai_replace)
        insertButton.text = labels.getString(if (canReplace || mode != Mode.RESULT) R.string.ai_insert_next else R.string.ai_insert_cursor)
        cancelButton.text = labels.getString(if (mode == Mode.WORKING || mode == Mode.RESULT) R.string.ai_cancel else R.string.ai_close)
        manageButton.text = labels.getString(R.string.ai_balance_open)
        settingsButton.text = labels.getString(R.string.ai_open_settings)
        heading.text = when (mode) {
            Mode.WORKING, Mode.RESULT -> command?.let { commandLabel(it, commandLanguage) } ?: ""
            else -> labels.getString(R.string.ai_panel_title)
        }
        when (mode) {
            Mode.COMMANDS -> note.text = ""
            Mode.WORKING -> { note.text = ""; showAnswer() }
            Mode.RESULT -> { note.text = if (canReplace) "" else labels.getString(R.string.ai_changed); showAnswer() }
            Mode.MESSAGE -> {
                val text = messageArg?.let { labels.getString(messageRes, it) } ?: if (messageRes != 0) labels.getString(messageRes) else ""
                note.text = ""
                answer.text = text
                answer.setTextColor(palette.red)
            }
        }
    }

    /** the answer so far; while it streams in, a gold cursor follows it */
    private fun showAnswer() {
        answer.setTextColor(palette.text)
        if (mode != Mode.WORKING) { answer.text = answerText; return }
        val sb = SpannableStringBuilder(answerText).append(CURSOR)
        sb.setSpan(ForegroundColorSpan(palette.gold), sb.length - CURSOR.length, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        answer.text = sb
    }

    private val commandViews get() = listOf<View>(title, close) + commandButtons.values
    private val previewViews get() = listOf<View>(sparkle, heading, card, note, replaceButton, manageButton, settingsButton, insertButton, cancelButton)

    private fun show(vararg visible: View) {
        for (v in commandViews + previewViews) v.visibility = if (v in visible) View.VISIBLE else View.GONE
        applyLabels()
    }

    private fun enable(b: View, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else DISABLED_ALPHA
    }

    fun showCommands(layoutLanguage: String, secondLanguage: String) {
        this.layoutLanguage = layoutLanguage
        this.secondLanguage = secondLanguage
        mode = Mode.COMMANDS
        answerText = ""
        show(*commandViews.toTypedArray())
        commandButtons[AiCommand.TRANSLATE to "second"]?.visibility = if (secondLanguage == layoutLanguage) View.INVISIBLE else View.VISIBLE
        slideIn()
    }

    /** design: the panel comes up from 120 px below in 0.4 s */
    private fun slideIn() {
        if (!AikbMotion.enabled(context)) { translationY = 0f; alpha = 1f; return }
        val s = DesignScale(resources.displayMetrics.widthPixels, Int.MAX_VALUE / 2)
        translationY = s.size(120f)
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    fun showWorking(command: AiCommand, language: String? = null) {
        this.command = command
        commandLanguage = language
        mode = Mode.WORKING
        answerText = ""
        show(sparkle, heading, card, note, replaceButton, insertButton, cancelButton)
        enable(replaceButton, false)
        enable(insertButton, false)
    }

    /** the answer so far, as plain text */
    fun setAnswer(text: String) {
        answerText = text
        showAnswer()
        card.post { card.fullScroll(View.FOCUS_DOWN) }
    }

    /** [canReplace]: the fragment is unchanged; "Continue" only inserts */
    fun showResult(text: String, canReplace: Boolean) {
        this.canReplace = canReplace
        mode = Mode.RESULT
        answerText = text
        val replace = canReplace && command != AiCommand.CONTINUE
        if (replace) show(sparkle, heading, card, note, replaceButton, insertButton, cancelButton)
        else show(sparkle, heading, card, note, insertButton, cancelButton)
        enable(replaceButton, true)
        enable(insertButton, true)
        note.visibility = if (canReplace) View.GONE else View.VISIBLE
    }

    /** a message; [balance]: "Open balance" button (no money left), [settings]: "AI settings" button */
    fun showMessage(res: Int, arg: Any? = null, balance: Boolean = false, settings: Boolean = false) {
        messageRes = res
        messageArg = arg
        mode = Mode.MESSAGE
        val extra = listOfNotNull(sparkle, heading, card, cancelButton, manageButton.takeIf { balance }, settingsButton.takeIf { settings && !balance })
        show(*extra.toTypedArray())
    }

    /** for tests and accessibility: what the panel offers now */
    fun buttonTexts(): List<String> = listOf(replaceButton, manageButton, settingsButton, insertButton, cancelButton)
        .filter { it.visibility == View.VISIBLE && it.isEnabled }.map { it.text.toString() }

    /** for tests: the ✨ icon of the header */
    fun sparkleView(): View = sparkle

    /** for tests: the six command chips */
    fun commandChips(): List<View> = commandButtons.values.toList()

    fun noteText(): String = if (note.visibility == View.VISIBLE) note.text.toString() else ""

    /** for tests: the text in the card (answer or message) */
    fun cardText(): String = answer.text.toString()

    companion object {
        private const val CURSOR = " ▍"
        private const val PRESS_MS = 150L
        private const val SLIDE_MS = 400L
        private const val DISABLED_ALPHA = 0.35f
    }
}
