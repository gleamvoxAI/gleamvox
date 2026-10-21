// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceInputView
import helium314.keyboard.latin.voice.VoiceState
import helium314.keyboard.latin.voice.VoiceError
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Looks at the real keyboard the way the phone does: builds the keyboard view with the theme context of the app,
 * measures the keys and draws the view into a picture (app/build/shots), then checks pixels.
 * Phone: width 1148 px, density 3.0 (480 dpi), dark mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class], qualifiers = "w383dp-h880dp-port-night-480dpi")
class AikbLookTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val darkBackground = 0xFF121318.toInt()
    private val darkKey = 0xFF292A2F.toInt()

    private class Shot(val bitmap: Bitmap, val keys: List<Key>, val keyboardLeft: Int, val keyboardTop: Int, val mic: IntArray)

    private fun offsetIn(root: View, v: View): IntArray {
        var x = 0; var y = 0; var cur: View = v
        while (cur !== root) { x += cur.left; y += cur.top; cur = cur.parent as View }
        return intArrayOf(x, y)
    }

    /** like the keyboard window: full width, height as big as the content but at most the screen (2492 px) */
    private fun forceLayoutAll(v: View) { v.forceLayout(); if (v is ViewGroup) for (i in 0 until v.childCount) forceLayoutAll(v.getChildAt(i)) }

    private fun measureAndLayout(view: View) {
        forceLayoutAll(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1148, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2492, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private var lastPinned: ViewGroup? = null

    @kotlin.test.AfterTest fun resetAi() {
        helium314.keyboard.latin.ai.AiAssist.inBuild = { helium314.keyboard.latin.openai.OpenAiPrefs.isAvailable() }
        helium314.keyboard.latin.suggestions.StripGlowDrawable.frozenSeconds = null
        durationScale(1f)
    }

    /** like "Remove animations" in the system settings (0) or the normal speed (1) */
    private fun durationScale(f: Float) {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, f)
    }

    /** the glow of the strip buttons pulses with time: pictures and pixels at t = 0 (alpha 0.25) */
    @kotlin.test.BeforeTest fun freezeGlow() {
        helium314.keyboard.latin.suggestions.StripGlowDrawable.frozenSeconds = 0f
        durationScale(0f) // pictures show the panels at rest; panelsFollowRemoveAnimations switches animations on
    }

    private fun shoot(style: String?, name: String, colors: String? = null, voicePanel: ((ViewGroup, VoiceInputView) -> Unit)? = null,
                      ai: Boolean = false, aiPanel: ((helium314.keyboard.latin.ai.AiPanelView) -> Unit)? = null,
                      aiState: helium314.keyboard.latin.ai.AiGuard.Button = helium314.keyboard.latin.ai.AiGuard.Button.READY,
                      micGold: Boolean? = null, strip: ((helium314.keyboard.latin.suggestions.SuggestionStripView) -> Unit)? = null): Shot {
        helium314.keyboard.latin.ai.AiAssist.inBuild = { ai } // the ✨ button exists only in the personal variant
        latinIME.prefs().edit {
            clear() // the preferences object is shared between tests
            putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true)
            if (style != null) putString(Settings.PREF_THEME_STYLE, style)
            if (colors != null) putString(Settings.PREF_THEME_COLORS, colors)
        }
        Settings.getInstance().loadSettings(latinIME)
        RichInputMethodManager.init(latinIME)
        val ks = KeyboardSwitcher.getInstance()
        val view = ks.onCreateInputView(latinIME, false)
        ks.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)
        view.findViewById<View>(R.id.strip_container).visibility = View.VISIBLE
        view.findViewById<View>(R.id.suggestion_strip_view).visibility = View.VISIBLE
        lastPinned = view.findViewById<ViewGroup>(R.id.pinned_keys)
        val voice = lastPinned!!.findViewWithTag<View>(ToolbarKey.VOICE)
        lastPinned!!.findViewWithTag<View>(ToolbarKey.CLIPBOARD)?.visibility = View.VISIBLE
        voice.visibility = View.VISIBLE // there is no voice input in the test environment
        if (ai) view.findViewById<helium314.keyboard.latin.suggestions.SuggestionStripView>(R.id.suggestion_strip_view)
            .setAiKeyState(aiState)
        if (micGold != null) view.findViewById<helium314.keyboard.latin.suggestions.SuggestionStripView>(R.id.suggestion_strip_view).setMicState(micGold)
        strip?.invoke(view.findViewById(R.id.suggestion_strip_view))
        measureAndLayout(view)
        if (aiPanel != null) {
            val wrapper = view.findViewById<ViewGroup>(R.id.keyboard_view_wrapper)
            val panel = helium314.keyboard.latin.ai.AiPanelView(wrapper.context, Settings.getValues().mColors, object : helium314.keyboard.latin.ai.AiPanelView.Actions {
                override fun onCommand(command: helium314.keyboard.latin.ai.AiCommand, language: String?) = Unit
                override fun onReplace() = Unit
                override fun onInsert() = Unit
                override fun onCancel() = Unit
                override fun onOpenBalance() = Unit
                override fun onOpenSettings() = Unit
            })
            // same as AiAssist.ensurePanel: explicit height of the keys area
            wrapper.addView(panel, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, view.findViewById<View>(R.id.keyboard_view).height))
            aiPanel(panel)
        }
        if (voicePanel != null) {
            val wrapper = view.findViewById<ViewGroup>(R.id.keyboard_view_wrapper)
            val panel = VoiceInputView(wrapper.context, Settings.getValues().mColors, object : VoiceInputView.Actions {
                override fun onMic() = Unit
                override fun onDone() = Unit
                override fun onCancel() = Unit
            })
            // same as VoiceInput.ensurePanel: explicit height of the keys area
            wrapper.addView(panel, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, view.findViewById<View>(R.id.keyboard_view).height))
            voicePanel(wrapper, panel)
        }
        measureAndLayout(view)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File("build/shots").mkdirs()
        File("build/shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val kv = view.findViewById<View>(R.id.keyboard_view)
        val off = offsetIn(view, kv)
        val micOff = offsetIn(view, voice)
        val micPos = intArrayOf(micOff[0], micOff[1], voice.width, voice.height)
        val keys = ks.keyboard!!.sortedKeys
        println("LOOK $name view=${view.measuredWidth}x${view.measuredHeight} keyboardAt=${off[0]},${off[1]} mic=${micPos.toList()}")
        return Shot(bmp, keys, off[0] + kv.paddingLeft, off[1] + kv.paddingTop, micPos)
    }

    /** (height of key, gap below it, distance between two rows) of the letter rows */
    private fun rowGeometry(keys: List<Key>): Triple<Int, Int, Int> {
        val q = keys.first { it.label == "q" }
        val a = keys.first { it.label == "a" }
        return Triple(q.height, q.verticalGap, a.y - q.y)
    }

    private fun digitPixel(s: Shot): Int {
        val k = s.keys.first { it.label == "1" }
        return s.bitmap.getPixel(s.keyboardLeft + k.x + k.width / 2, s.keyboardTop + k.y + 10)
    }

    private fun micPixel(s: Shot) = s.bitmap.getPixel(s.mic[0] + s.mic[2] / 2, s.mic[1] + (s.mic[3] * 0.15f).toInt())

    /** stage 5g-26: the quiet call pauses the car's music: a car badge instead of the Bluetooth one (the phone records) */
    @Test fun carPausedBadge() {
        var panel: VoiceInputView? = null
        shoot(null, "voice_car_paused", "aikb_dark", voicePanel = { _, p ->
            p.freezeTime(0f)
            p.setOpenAi(true)
            p.setMic(false, false, false)
            p.update(VoiceState.LISTENING, "", null)
            p.setCarPaused(true)
            panel = p
        })
        val p = panel!!
        assertTrue(p.carPausedShown(), "the car badge")
        assertFalse(p.bluetoothShown(), "no Bluetooth badge: the car's microphone does not record")
        assertTrue(p.micDescription().contains(latinIME.getString(R.string.voice_car_music_paused)), p.micDescription())
        p.setCarPaused(false)
        assertFalse(p.carPausedShown())
        assertFalse(p.micDescription().contains(latinIME.getString(R.string.voice_car_music_paused)))
    }

    /** ux-11 (the owner: "I can't tap it"): the car mode button lies inside the panel, at least 48 dp high, a tap switches */
    @Test fun carModeButtonCanBeTapped() {
        var panel: VoiceInputView? = null
        var taps = 0
        shoot(null, "voice_car_mode", "aikb_dark", voicePanel = { _, p ->
            p.freezeTime(0f)
            p.update(VoiceState.LISTENING, "", null)
            p.setCarMode("🚗 Через телефон")
            panel = p
        })
        val p = panel!!
        val b = p.carModeButton()
        val density = latinIME.resources.displayMetrics.density
        assertTrue(b.visibility == View.VISIBLE && b.height >= (48 * density).toInt() - 1, "height ${b.height}")
        assertTrue(b.left >= 0 && b.top >= 0 && b.right <= p.width && b.bottom <= p.height, "inside the panel: ${b.left},${b.top},${b.right},${b.bottom} of ${p.width}x${p.height}")
        b.setOnClickListener { taps++ }
        assertTrue(b.performClick())
        assertEquals(1, taps)
    }

    @Test fun aikbStyleIsTheDefault() {
        assertEquals(KeyboardTheme.STYLE_AIKB, Defaults.PREF_THEME_STYLE)
    }

    @Test fun aikbGeometry() {
        val s = shoot(null, "alpha_dark_aikb")
        val (key, gap, pitch) = rowGeometry(s.keys)
        println("LOOK aikb key=$key gap=$gap pitch=$pitch")
        assertTrue(key in 128..132, "key height $key")
        assertTrue(gap in 22..24, "gap $gap")
        assertTrue(pitch in 151..155, "row distance $pitch")
    }

    @Test fun aikbDigitsHaveNoKeyBackground() {
        val s = shoot(null, "alpha_dark_aikb")
        assertEquals(darkBackground, digitPixel(s), "digit key area is not the keyboard background")
    }

    @Test fun aikbMicrophoneIsRound() {
        val s = shoot(null, "alpha_dark_aikb")
        assertEquals(darkKey, micPixel(s), "no circle behind the microphone")
    }

    /** What the owner saw on the phone (stored old style): digits on keys, no circle, bigger gaps. Explains the mismatch. */
    @Test fun oldStyleReproducesPhoneScreenshot() {
        val s = shoot(KeyboardTheme.STYLE_ROUNDED, "alpha_dark_rounded")
        val (key, gap, pitch) = rowGeometry(s.keys)
        println("LOOK rounded key=$key gap=$gap pitch=$pitch")
        assertEquals(darkKey, digitPixel(s))
        assertEquals(darkBackground, micPixel(s))
        // phone measurement: key 129, gap 32, row distance 161
        assertTrue(key in 126..134 && gap in 27..35 && pitch in 157..165, "key $key gap $gap pitch $pitch")
    }

    @Test fun upgradeSwitchesOldStoredStyleOnce() {
        val prefs = latinIME.prefs()
        prefs.edit {
            clear()
            putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_ROUNDED)
            putInt(Settings.PREF_VERSION_CODE, BuildConfig.VERSION_CODE - 1)
        }
        checkVersionUpgrade(latinIME)
        assertEquals(KeyboardTheme.STYLE_AIKB, prefs.getString(Settings.PREF_THEME_STYLE, null))
        // a later choice of the user stays
        prefs.edit {
            putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_MATERIAL)
            putInt(Settings.PREF_VERSION_CODE, BuildConfig.VERSION_CODE - 1)
        }
        checkVersionUpgrade(latinIME)
        assertEquals(KeyboardTheme.STYLE_MATERIAL, prefs.getString(Settings.PREF_THEME_STYLE, null))
    }


    private fun voiceShot(name: String, colors: String, state: VoiceState, error: VoiceError? = null, openAi: Boolean = false): Pair<Shot, VoiceInputView> {
        var panel: VoiceInputView? = null
        val shot = shoot(null, name, colors, voicePanel = { _, p ->
            p.setOpenAi(openAi)
            p.update(state, if (state == VoiceState.LISTENING) "Привет, это проверка голосового ввода" else "", error)
            p.setLevel(0.6f)
            panel = p
        })
        return shot to panel!!
    }

    private fun pixelIn(s: Shot, panel: View, v: View, fx: Float, fy: Float): Int {
        var x = 0; var y = 0; var cur: View = v
        while (cur !== panel) { x += cur.left; y += cur.top; cur = cur.parent as View }
        x += panel.left; y += panel.top
        var p = panel.parent as View
        while (p.parent is View) { x += p.left; y += p.top; p = p.parent as View }
        x += p.left; y += p.top
        return s.bitmap.getPixel(x + (v.width * fx).toInt(), y + (v.height * fy).toInt())
    }

    private fun findText(root: View, text: String): View? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun voicePanelDark() {
        val (s, panel) = voiceShot("voice_dark", "aikb_dark", VoiceState.LISTENING)
        assertEquals(darkBackground, pixelIn(s, panel, panel, 0.02f, 0.5f), "panel background")
        val cancel = findText(panel, latinIME.getString(R.string.voice_cancel))!!
        assertEquals(darkKey, pixelIn(s, panel, cancel, 0.15f, 0.5f), "Cancel button has the key color of the design")
        // build 4351: no "Done"; one "Cancel" over the whole width, as in 5e; the grey hint under the circle
        assertEquals(null, findText(panel, "Done"))
        val hint = panel.finishHint()!!
        assertEquals(latinIME.getString(R.string.voice_finish_hint), hint.text.toString())
        assertEquals(helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors).grey, hint.currentTextColor)
        assertTrue(cancel.left < panel.width * 0.1f && cancel.right > panel.width * 0.9f, "Cancel ${cancel.left}..${cancel.right} of ${panel.width}")
        assertTrue(hint.top > panel.height / 2 && hint.bottom <= cancel.top, "hint ${hint.top}..${hint.bottom}, Cancel at ${cancel.top}")
        assertTrue(panel.micDescription().startsWith(latinIME.getString(R.string.voice_finish_desc)))
    }

    @Test fun voicePanelLight() {
        val (s, panel) = voiceShot("voice_light", "aikb_light", VoiceState.LISTENING)
        assertEquals(0xFFEBECF0.toInt(), pixelIn(s, panel, panel, 0.02f, 0.5f), "panel background")
        val cancel = findText(panel, latinIME.getString(R.string.voice_cancel))!!
        assertEquals(0xFFFFFFFF.toInt(), pixelIn(s, panel, cancel, 0.15f, 0.5f), "Cancel button has the light key color")
    }

    /**
     * Stage 5g, continuous dictation: "Listening · continuous · OpenAI · headset" in gold, the counters, the golden
     * "Phrase not recognized · Retry", no "Done", the hint under the circle, the screen kept on for the whole session.
     */
    @Test fun voicePanelContinuous() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            var panel: VoiceInputView? = null
            val s = shoot(null, "voice_continuous_$colors", colors, voicePanel = { _, p ->
                p.freezeTime(0f)
                p.setOpenAi(true)
                p.setContinuous(true)
                p.setMic(true, false, false)
                p.update(VoiceState.LISTENING, "", null)
                p.setProgress(3, 1, 1)
                p.setNetwork(true, true) // stage 5g-20: 2 min without the network
                panel = p
            })
            val p = panel!!
            val pal = helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors)
            assertEquals(latinIME.getString(R.string.voice_listening_continuous), p.statusText(), "no word: the badge says headset")
            assertTrue(p.bluetoothShown())
            assertEquals(pal.goldText, p.statusColor())
            assertEquals(latinIME.getString(R.string.voice_continuous_progress, 3, 1), p.infoText())
            val retry = p.retryPhraseLine()!!
            assertEquals(latinIME.getString(R.string.voice_network_retry), retry.text.toString())
            assertEquals(latinIME.getString(R.string.voice_waiting_network), p.noticeLine())
            assertEquals(pal.goldText, retry.currentTextColor)
            assertEquals(null, findText(p, "Done"))
            assertTrue(p.finishHint() != null && p.keepScreenOn)
            assertTrue(p.height <= s.bitmap.height)
            p.update(VoiceState.PROCESSING, "", null) // the last phrases are recognized
            assertTrue(p.keepScreenOn, "the whole session, also while the last phrases are recognized")
            assertEquals(latinIME.getString(R.string.voice_network_retry), p.retryPhraseLine()?.text?.toString(), "also while recognizing")
            p.setNetwork(false, false)
            assertEquals(null, p.retryPhraseLine())
            p.setContinuous(false)
            p.update(VoiceState.PROCESSING, "", null)
            assertTrue(!p.keepScreenOn, "one phrase: released after the recording")
        }
    }

    /**
     * stage 5g-2: the small blue Bluetooth badge at the circle only while the headset really records; with the phone
     * nothing (no word either); it appears and goes at once when the source changes; TalkBack says it
     */
    @Test fun bluetoothBadgeOnlyWhileTheHeadsetRecords() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            for (headset in listOf(true, false)) {
                var panel: VoiceInputView? = null
                val s = shoot(null, "voice_bluetooth_${if (headset) "headset" else "phone"}_$colors", colors, voicePanel = { _, p ->
                    p.freezeTime(0f)
                    p.setOpenAi(true)
                    p.setMic(headset, false, false)
                    p.update(VoiceState.LISTENING, "", null)
                    panel = p
                })
                val p = panel!!
                val (bx, by) = p.bluetoothBadgeCenter()
                val badgeColor = if (colors == "aikb_dark") VoiceInputView.BT_BLUE_DARK else VoiceInputView.BT_BLUE_LIGHT
                // a point of the blue circle left of the white sign
                val px = pix(p.rootView, p, (bx - helium314.keyboard.latin.ai.DesignScale(p.width, p.height).size(24f)).toInt(), by.toInt(), s)
                val bt = latinIME.getString(R.string.voice_mic_bluetooth_desc)
                assertEquals(latinIME.getString(R.string.voice_listening_openai, "English"), p.statusText(), "no headset or phone word")
                if (headset) {
                    assertTrue(p.bluetoothShown())
                    assertEquals(badgeColor, px, "$colors: the badge is blue")
                    assertTrue(p.micDescription().endsWith(bt), p.micDescription())
                    assertTrue(helium314.keyboard.latin.ai.AiTint.contrast(0xFFFFFFFF.toInt(), badgeColor) >= 4.5)
                } else {
                    assertFalse(p.bluetoothShown())
                    assertTrue(px != badgeColor, "$colors: no badge with the phone")
                    assertFalse(p.micDescription().contains(bt))
                }
                // the source changes in the middle of a session: the badge follows at once
                p.setMic(!headset, false, false)
                assertEquals(!headset, p.bluetoothShown())
                p.update(VoiceState.PROCESSING, "", null)
                assertFalse(p.bluetoothShown(), "only while recording")
            }
        }
        // the system recognizer picks its microphone itself: never a badge
        var sys: VoiceInputView? = null
        shoot(null, "voice_bluetooth_system_dark", "aikb_dark", voicePanel = { _, p -> p.setMic(true, false, false); p.update(VoiceState.LISTENING, "", null); sys = p })
        assertFalse(sys!!.bluetoothShown())
        sys!!.setOpenAi(true); sys!!.setMic(false, false, true)
        assertTrue(findText(sys!!, latinIME.getString(R.string.voice_mic_headset_missing)) != null, "\"Headset\" chosen and none there: said")
    }

    /** stage 5g-5: proven earbuds: a dimmed badge at the tap ("connecting"), bright with their sound, gone after a fallback */
    @Test fun bluetoothBadgeConnectingThenBrightThenGone() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            var panel: VoiceInputView? = null
            val s = shoot(null, "voice_bluetooth_connecting_$colors", colors, voicePanel = { _, p ->
                p.freezeTime(0f); p.setOpenAi(true); p.setMic(false, false, false); p.setBluetoothConnecting(true)
                p.update(VoiceState.LISTENING, "", null); panel = p
            })
            val p = panel!!
            val (bx, by) = p.bluetoothBadgeCenter()
            val blue = if (colors == "aikb_dark") VoiceInputView.BT_BLUE_DARK else VoiceInputView.BT_BLUE_LIGHT
            val bg = helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors).background
            val px = pix(p.rootView, p, (bx - helium314.keyboard.latin.ai.DesignScale(p.width, p.height).size(24f)).toInt(), by.toInt(), s)
            assertTrue(p.bluetoothShown() && p.bluetoothConnecting(), "shown at once, dimmed")
            assertTrue(near(helium314.keyboard.latin.ai.AikbMotion.blend(bg, blue, 0.45f), px, 4), "$colors: dimmed blue ${Integer.toHexString(px)}")
            assertFalse(p.micDescription().contains(latinIME.getString(R.string.voice_mic_bluetooth_desc)), "not said before it records")
            // stage 5g-10: a car is being connected: the status says so, and a tick comes when the channel is up
            p.setBluetoothConnecting(true, true); p.update(VoiceState.LISTENING, "", null)
            assertTrue(p.statusText().contains("car", true) || p.statusText().contains("машин", true), "status: ${p.statusText()}")
            p.setBluetoothConnecting(true, false); p.update(VoiceState.LISTENING, "", null)
            assertFalse(p.statusText().contains("car microphone", true) || p.statusText().contains("машин", true), "earbuds: no car text")
            val ticks = p.readyTicks; p.tickReady()
            assertEquals(ticks + 1, p.readyTicks)
            p.setMic(true, false, false); p.setBluetoothConnecting(false)
            assertTrue(p.bluetoothShown() && !p.bluetoothConnecting(), "bright: the earbuds give sound")
            assertTrue(p.micDescription().endsWith(latinIME.getString(R.string.voice_mic_bluetooth_desc)))
            p.setMic(false, true, false); p.setBluetoothConnecting(false)
            assertFalse(p.bluetoothShown(), "back to the phone: gone")
        }
    }

    /** stage 5g-13: every status of the voice panel is one whole line and never over the hint below, in both themes */
    @Test fun voiceStatusesFitOnOneLine() {
        val states = listOf<Pair<String, (VoiceInputView) -> Unit>>(
            "car connecting" to { p -> p.setBluetoothConnecting(true, true); p.update(VoiceState.LISTENING, "", null) },
            "car ready" to { p -> p.setBluetoothConnecting(true, true); p.tickReady(); p.update(VoiceState.LISTENING, "", null) },
            "earbuds connecting" to { p -> p.setBluetoothConnecting(true, false); p.update(VoiceState.LISTENING, "", null) },
            "listening" to { p -> p.update(VoiceState.LISTENING, "", null) },
            "only noise" to { p -> p.update(VoiceState.ERROR, "", VoiceError.ONLY_NOISE) },
            "no speech" to { p -> p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH) },
            "processing" to { p -> p.update(VoiceState.PROCESSING, "", null) },
        )
        for (colors in listOf("aikb_dark", "aikb_light")) for ((name, set) in states) {
            var panel: VoiceInputView? = null
            shoot(null, "voice_status_${name.replace(' ', '_')}_$colors", colors, voicePanel = { _, p ->
                p.setOpenAi(true); p.setMic(false, false, false); set(p); panel = p
            })
            val p = panel!!
            assertTrue(p.statusFits(), "$colors, $name: '${p.statusText()}' does not fit on one line")
            if (name.startsWith("car")) assertTrue(p.statusText().length <= 20, "short: ${p.statusText()}")
        }
    }

    /** stage 5g-15: a picture in the suggestion strip: the screenshot (inserted on a tap), and gray where the field takes none */
    @Test fun pictureSuggestionInTheStrip() {
        val red = 0xFFE53935.toInt()
        val picture = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }
        val ru = latinIME.createConfigurationContext(android.content.res.Configuration(latinIME.resources.configuration).apply { setLocale(java.util.Locale("ru")) })
        for (colors in listOf("aikb_dark", "aikb_light")) for (gray in listOf(false, true)) for (lang in listOf("en", "ru")) {
            var shown: View? = null
            var inserted = 0
            val label = (if (lang == "ru") ru else latinIME).getString(if (gray) R.string.aikb_image_not_accepted else R.string.aikb_screenshot_suggestion)
            val s = shoot(null, "strip_picture_${if (gray) "gray" else "screenshot"}_${colors}_$lang", colors, strip = { strip ->
                val v = latinIME.clipboardHistoryManager.pictureSuggestion(strip, picture, label, gray) { inserted++ }
                strip.setExternalSuggestionView(v, false)
                shown = v
            })
            val v = shown!!
            assertTrue(v.parent != null && v.visibility == View.VISIBLE && v.width > 0, "$colors: the suggestion is in the strip")
            val image = v.findViewById<View>(R.id.clipboard_suggestion_image)
            val at = offsetIn(v.rootView, image)
            val px = s.bitmap.getPixel(at[0] + image.width / 2, at[1] + image.height / 2)
            if (gray) assertTrue(px != red, "$colors: the picture is gray") else assertEquals(red, px, "$colors: the picture itself")
            val tv = v.findViewById<android.widget.TextView>(R.id.clipboard_suggestion_text)
            // the label is drawn: pixels inside the text that are not the background of the suggestion
            val bg = s.bitmap.getPixel(at[0] - 4, at[1] + image.height / 2)
            val tvAt = offsetIn(v.rootView, tv)
            var drawn = 0
            for (y in 0 until tv.height) for (x in tv.paddingLeft until tv.width - tv.paddingRight)
                if (s.bitmap.getPixel(tvAt[0] + x, tvAt[1] + y) != bg) drawn++
            assertTrue(drawn > 300, "$colors: the label is drawn ($drawn pixels)")
            assertEquals(0, tv.layout.getEllipsisCount(0), "$colors: '$label' fits")
            assertEquals(label, tv.text.toString())
            image.performClick()
            assertEquals(if (gray) 0 else 1, inserted, "$colors: a tap inserts only where the field takes pictures")
        }
    }

    @Test fun voicePanelError() {
        voiceShot("voice_error_dark", "aikb_dark", VoiceState.ERROR, VoiceError.LANGUAGE_NOT_SUPPORTED)
    }

    /** bounding box (left, top, right, bottom) of pixels that differ from the key color inside the key */
    private fun glyphBox(s: Shot, label: String): IntArray {
        val k = s.keys.first { it.label == label }
        val bg = s.bitmap.getPixel(s.keyboardLeft + k.x + k.width / 2, s.keyboardTop + k.y + 8)
        var l = k.width; var t = k.height; var r = -1; var b = -1
        for (y in 0 until k.height) for (x in 0 until k.width) {
            val p = s.bitmap.getPixel(s.keyboardLeft + k.x + x, s.keyboardTop + k.y + y)
            // only the middle part of the key (corners of the rounded shape are not key colored)
            if (x < 20 || x > k.width - 20 || y < 20 || y > k.height - 20) continue
            if (p != bg) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        }
        return intArrayOf(l, t, r, b)
    }

    @Test fun lettersAreBiggerAndFitTheKey() {
        val s = shoot(null, "alpha_dark_aikb")
        val a = glyphBox(s, "a")
        val xHeight = a[3] - a[1] + 1
        println("LOOK letter a box=${a.toList()} height=$xHeight")
        assertTrue(xHeight in 47..51, "x-height of a is $xHeight")
        val k = s.keys.first { it.label == "m" }
        for (label in listOf("m", "w")) {
            val g = glyphBox(s, label)
            val width = g[2] - g[0] + 1
            println("LOOK letter $label width=$width of key ${k.width}")
            assertTrue(width < k.width * 0.8f, "$label is $width wide in a key of ${k.width}")
        }
        // digits row is as big as the letters
        val one = glyphBox(s, "1")
        assertTrue(one[3] - one[1] + 1 > xHeight, "digit height")
    }

    @Test fun clipboardButtonIsLeftOfMicrophoneAndRound() {
        val s = shoot(null, "alpha_dark_aikb_clipboard")
        val pinned = lastPinned!!
        val order = (0 until pinned.childCount).map { pinned.getChildAt(it).tag }
        assertEquals(listOf<Any?>(ToolbarKey.CLIPBOARD, ToolbarKey.VOICE), order)
        val c = pinned.getChildAt(0); val m = pinned.getChildAt(1)
        assertTrue(c.left < m.left)
        assertEquals(c.width, m.width) // same round size
        assertEquals(c.height, m.height)
        // circle color behind the clipboard icon (top part of the circle, away from the icon)
        val off = offsetIn(pinned.rootView, c)
        assertEquals(darkKey, s.bitmap.getPixel(off[0] + c.width / 2, off[1] + (c.height * 0.15f).toInt()))
    }

    @Test fun voicePanelIsNotHigherThanTheKeyboard() {
        val plain = shoot(null, "alpha_dark_aikb_plain")
        val plainHeight = plain.bitmap.height
        val (withPanel, panel) = voiceShot("voice_dark_height", "aikb_dark", VoiceState.LISTENING)
        val keysHeight = plain.keys.maxOf { it.y + it.height + it.verticalGap } // keys area
        println("LOOK keyboard window=$plainHeight with panel=${withPanel.bitmap.height} panel=${panel.height} keys=$keysHeight")
        assertEquals(plainHeight, withPanel.bitmap.height, "window must not grow because of the panel")
        assertTrue(panel.height < 1200, "panel is ${panel.height} px, the screen is 2492")
        assertTrue(panel.height <= plainHeight)
        assertTrue(panel.height >= keysHeight - 40, "panel covers the keys")
    }

    // ---- ✨ (stage 4, part 2b) ----

    @Test fun aiButtonIsLeftOfClipboardAndRound() {
        val s = shoot(null, "alpha_dark_aikb_ai", ai = true)
        val pinned = lastPinned!!
        val ai = pinned.getChildAt(0)
        assertEquals(R.id.ai_key, ai.id)
        assertEquals(View.VISIBLE, ai.visibility)
        assertEquals(listOf<Any?>(ToolbarKey.CLIPBOARD, ToolbarKey.VOICE), (1 until pinned.childCount).map { pinned.getChildAt(it).tag })
        val c = pinned.getChildAt(1)
        assertTrue(ai.left < c.left)
        assertEquals(c.width, ai.width) // same round size as clipboard and microphone
        assertEquals(c.height, ai.height)
        val off = offsetIn(pinned.rootView, ai)
        // the circle behind ✨, slightly warm because a ready ✨ glows (stage 5e)
        val circle = s.bitmap.getPixel(off[0] + ai.width / 2, off[1] + (ai.height * 0.12f).toInt())
        assertTrue(near(helium314.keyboard.latin.ai.AikbMotion.blend(darkKey, 0xFF463A16.toInt(), 0.625f), circle, 2), "circle behind ✨")
        // the icon is drawn in the key color of the theme, inside the circle
        val center = s.bitmap.getPixel(off[0] + (ai.width * 0.42f).toInt(), off[1] + (ai.height * 0.54f).toInt())
        assertTrue(center != darkKey && center != darkBackground, "no ✨ icon")
    }

    @Test fun noAiButtonInRelease() { // release variant
        shoot(null, "alpha_dark_aikb_no_ai", ai = false)
        assertEquals(null, lastPinned!!.findViewById<View>(R.id.ai_key))
    }

    private fun aiShot(name: String, colors: String, setup: (helium314.keyboard.latin.ai.AiPanelView) -> Unit): Pair<Shot, helium314.keyboard.latin.ai.AiPanelView> {
        var panel: helium314.keyboard.latin.ai.AiPanelView? = null
        val shot = shoot(null, name, colors, ai = true, aiPanel = { p -> setup(p); panel = p })
        return shot to panel!!
    }

    private fun result(p: helium314.keyboard.latin.ai.AiPanelView) {
        p.showWorking(helium314.keyboard.latin.ai.AiCommand.FIX)
        p.setAnswer("Привет! Это исправленный текст, который пришёл от ИИ.")
        p.showResult("Привет! Это исправленный текст, который пришёл от ИИ.", canReplace = true)
    }

    @Test fun aiPanelDark() {
        val (s, panel) = aiShot("ai_result_dark", "aikb_dark", ::result)
        assertEquals(darkBackground, pixelIn(s, panel, panel, 0.01f, 0.5f), "panel background")
        val replace = findText(panel, "Replace")!!
        assertEquals(goldDark, pixelIn(s, panel, replace, 0.1f, 0.5f), "Replace is gold")
        val cancel = findText(panel, "Cancel")!!
        assertEquals(darkKey, pixelIn(s, panel, cancel, 0.1f, 0.5f), "Cancel has the key color of the design")
        assertTrue(findText(panel, "Insert next to") != null)
        // the source indicator stays visible: AI (OpenAI) with your key, link to the balance; no ChatGPT anywhere
        assertTrue(findText(panel, "Balance") != null)
        assertTrue(findText(panel, "AI (OpenAI), your API key ·") != null)
        shoot(null, "ai_commands_dark", "aikb_dark", ai = true, aiPanel = { it.showCommands("ru", "en") })
    }

    @Test fun aiPanelLight() {
        val (s, panel) = aiShot("ai_result_light", "aikb_light", ::result)
        assertEquals(0xFFEBECF0.toInt(), pixelIn(s, panel, panel, 0.01f, 0.5f), "panel background")
        assertEquals(panelGoldLight, pixelIn(s, panel, findText(panel, "Replace")!!, 0.1f, 0.5f), "Replace is the darker gold of the light theme")
        shoot(null, "ai_commands_light", "aikb_light", ai = true, aiPanel = { it.showCommands("ru", "en") })
    }

    @Test fun aiPanelChangedFieldOffersOnlyInsert() {
        val (_, panel) = aiShot("ai_changed_dark", "aikb_dark") { p ->
            p.showWorking(helium314.keyboard.latin.ai.AiCommand.REWRITE)
            p.showResult("Новый текст", canReplace = false)
        }
        assertEquals(listOf("Insert at cursor", "Cancel"), panel.buttonTexts())
        assertTrue("has changed" in panel.noteText())
    }

    @Test fun aiPanelIsNotHigherThanTheKeyboard() {
        val plain = shoot(null, "alpha_dark_aikb_plain_ai", ai = true)
        val (withPanel, panel) = aiShot("ai_height_dark", "aikb_dark", ::result)
        println("LOOK ai keyboard window=${plain.bitmap.height} with panel=${withPanel.bitmap.height} panel=${panel.height}")
        assertEquals(plain.bitmap.height, withPanel.bitmap.height, "window must not grow because of the panel")
        assertTrue(panel.height <= plain.bitmap.height && panel.height < 1200)
    }

    // ---- stage 5c: golden ✨ and microphone (docs/DESIGN.md) ----

    private val goldDark = 0xFFF6C945.toInt()
    private val goldLight = 0xFFB98300.toInt()
    /** the gold of the panels in the light theme (3:1 on the light panel too) */
    private val panelGoldLight = helium314.keyboard.latin.ai.PanelPalette.LIGHT_GOLD

    private fun near(a: Int, b: Int, tolerance: Int = 12) =
        listOf(16, 8, 0).all { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= tolerance }

    /** pixels of [color] (antialiasing aside) inside the view [v] of the strip */
    private fun countIn(s: Shot, v: View, color: Int): Int {
        val off = offsetIn(v.rootView, v)
        var n = 0
        for (y in 0 until v.height) for (x in 0 until v.width)
            if (near(s.bitmap.getPixel(off[0] + x, off[1] + y), color)) n++
        return n
    }

    private fun aiKeyOf() = lastPinned!!.findViewById<View>(R.id.ai_key)
    private fun micOf() = lastPinned!!.findViewWithTag<View>(ToolbarKey.VOICE)

    @Test fun sparkleIsGoldWhenReadyDark() {
        val s = shoot(null, "gold_ai_ready_dark", "aikb_dark", ai = true)
        val gold = countIn(s, aiKeyOf(), goldDark)
        println("LOOK gold pixels of ✨ dark: $gold")
        assertTrue(gold > 60, "✨ is not gold: $gold pixels")
        // the circle is the key color warmed by the glow (#463A16 at full glow; t = 0: alpha 0.25 of 0.4)
        val off = offsetIn(aiKeyOf().rootView, aiKeyOf())
        val warm = helium314.keyboard.latin.ai.AikbMotion.blend(darkKey, 0xFF463A16.toInt(), 0.25f / 0.4f)
        assertTrue(near(warm, s.bitmap.getPixel(off[0] + aiKeyOf().width / 2, off[1] + (aiKeyOf().height * 0.12f).toInt()), 2))
    }

    @Test fun sparkleIsGoldWhenReadyLight() {
        val s = shoot(null, "gold_ai_ready_light", "aikb_light", ai = true)
        val gold = countIn(s, aiKeyOf(), goldLight)
        println("LOOK gold pixels of ✨ light: $gold")
        assertTrue(gold > 60, "✨ is not gold: $gold pixels")
        val off = offsetIn(aiKeyOf().rootView, aiKeyOf())
        val circle = s.bitmap.getPixel(off[0] + aiKeyOf().width / 2, off[1] + (aiKeyOf().height * 0.12f).toInt())
        assertTrue(near(helium314.keyboard.latin.ai.AikbMotion.blend(0xFFFFFFFF.toInt(), helium314.keyboard.latin.ai.PanelPalette.LIGHT_WARM, 0.625f), circle, 2))
        assertTrue(helium314.keyboard.latin.ai.AiTint.contrast(goldLight, circle) >= 3.0)
        // even at full glow
        assertTrue(helium314.keyboard.latin.ai.AiTint.contrast(goldLight, helium314.keyboard.latin.ai.PanelPalette.LIGHT_WARM) >= 3.0)
    }

    @Test fun sparkleIsNotGoldWithoutKeyConsentOrInternet() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            val s = shoot(null, "gold_ai_dimmed_$colors", colors, ai = true, aiState = helium314.keyboard.latin.ai.AiGuard.Button.OPENS_SETTINGS)
            assertEquals(0, countIn(s, aiKeyOf(), if (colors == "aikb_dark") goldDark else goldLight), "dimmed ✨ must not be gold ($colors)")
            assertEquals(0.45f, aiKeyOf().alpha)
        }
    }

    @Test fun microphoneGoldOnlyWhenOpenAi() {
        for ((colors, gold) in listOf("aikb_dark" to goldDark, "aikb_light" to goldLight)) {
            val on = shoot(null, "gold_mic_on_$colors", colors, micGold = true)
            val goldPixels = countIn(on, micOf(), gold)
            println("LOOK gold pixels of the microphone $colors: $goldPixels")
            assertTrue(goldPixels > 60, "microphone is not gold ($colors): $goldPixels")
            assertEquals(latinIME.getString(R.string.mic_desc_ai), micOf().contentDescription)
            val off = shoot(null, "gold_mic_off_$colors", colors, micGold = false)
            assertEquals(0, countIn(off, micOf(), gold), "system microphone must keep the theme color ($colors)")
            assertEquals(latinIME.getString(R.string.mic_desc_system), micOf().contentDescription)
        }
        // nothing set (release, no connections): the theme color as before
        val plain = shoot(null, "gold_mic_plain", "aikb_dark")
        assertEquals(0, countIn(plain, micOf(), goldDark))
    }

    @Test fun microphoneTurnsGoldAndBackWithoutRestart() {
        shoot(null, "gold_mic_toggle", "aikb_dark", micGold = true)
        val strip = lastPinned!!.rootView.findViewById<helium314.keyboard.latin.suggestions.SuggestionStripView>(R.id.suggestion_strip_view)
        strip.setMicState(false)
        assertEquals(latinIME.getString(R.string.mic_desc_system), micOf().contentDescription)
        strip.setMicState(true)
        assertEquals(latinIME.getString(R.string.mic_desc_ai), micOf().contentDescription)
    }

    @Test fun voicePanelOpenAiIsGold() {
        for ((colors, gold) in listOf("aikb_dark" to goldDark, "aikb_light" to panelGoldLight)) {
            val (s, panel) = voiceShot("voice_openai_$colors", colors, VoiceState.LISTENING, openAi = true)
            assertEquals(gold, panel.micIconColor(), "gold microphone on the key-colored circle (design)")
            assertEquals(if (colors == "aikb_dark") darkKey else 0xFFFFFFFF.toInt(), panel.micCircleColor())
            assertTrue(panel.statusText().endsWith("OpenAI"), panel.statusText())
            val textGold = helium314.keyboard.latin.ai.AiTint.goldTextOn(if (colors == "aikb_dark") darkBackground else 0xFFEBECF0.toInt())
            assertEquals(textGold, panel.statusColor())
            assertTrue(panel.micDescription().endsWith(latinIME.getString(R.string.mic_desc_ai)), panel.micDescription())
            // the big circle is drawn in gold
            var n = 0
            for (y in 0 until s.bitmap.height step 2) for (x in 0 until s.bitmap.width step 2) if (near(s.bitmap.getPixel(x, y), gold, 4)) n++
            assertTrue(n > 500, "no gold on the panel ($colors): $n")
        }
    }

    @Test fun voicePanelSystemHasNoGold() {
        for ((colors, gold) in listOf("aikb_dark" to goldDark, "aikb_light" to panelGoldLight)) {
            val (s, panel) = voiceShot("voice_system_$colors", colors, VoiceState.LISTENING, openAi = false)
            assertTrue(panel.statusText().endsWith(latinIME.getString(R.string.voice_listening_system, "x").substringAfterLast("x")), panel.statusText())
            assertTrue(panel.micCircleColor() != gold)
            assertTrue(panel.micDescription().endsWith(latinIME.getString(R.string.mic_desc_system)))
            var n = 0
            for (y in 0 until s.bitmap.height step 2) for (x in 0 until s.bitmap.width step 2) if (near(s.bitmap.getPixel(x, y), gold, 4)) n++
            assertEquals(0, n, "gold on the system panel ($colors)")
        }
    }

    @Test fun aiPanelSparkleIsGold() {
        for ((colors, gold) in listOf("aikb_dark" to goldDark, "aikb_light" to panelGoldLight)) {
            val (s, panel) = aiShot("ai_gold_$colors", colors) { it.showWorking(helium314.keyboard.latin.ai.AiCommand.FIX) }
            val icon = panel.sparkleView()
            var n = 0
            val root = icon.rootView
            val off = offsetIn(root, icon)
            for (y in 0 until icon.height) for (x in 0 until icon.width) if (near(s.bitmap.getPixel(off[0] + x, off[1] + y), gold)) n++
            println("LOOK gold pixels of the panel ✨ $colors: $n")
            assertTrue(n > 30, "panel ✨ is not gold ($colors): $n")
        }
    }

    // stage 3e: capital letters smaller than the small letters relative to the key

    private class CapsShot(val bitmap: Bitmap, val keys: List<Key>, val left: Int, val top: Int)

    /** Russian layout in [element], keys [keyHeight] px high (the height of the keyboard is searched), drawn like the phone */
    private fun russian(element: helium314.keyboard.keyboard.KeyboardElement, keyHeight: Int, name: String): CapsShot {
        shoot(null, "caps_base")
        val ru = helium314.keyboard.latin.RichInputMethodSubtype.get(helium314.keyboard.latin.utils.SubtypeSettings
            .getResourceSubtypesForLocale(with(helium314.keyboard.latin.common.LocaleUtils) { "ru".constructLocale() }).first())
        fun build(h: Int) = helium314.keyboard.keyboard.KeyboardLayoutSet.Builder(latinIME, EditorInfo(), Settings.getValues())
            .setKeyboardGeometry(1148, h).setSubtype(ru).build().getKeyboard(element)
        fun letterHeight(k: helium314.keyboard.keyboard.Keyboard) = k.sortedKeys.first { it.label?.lowercase() == "ж" }.height
        var lo = 300; var hi = 2400
        while (lo < hi) { val mid = (lo + hi) / 2; if (letterHeight(build(mid)) < keyHeight) lo = mid + 1 else hi = mid }
        val kb = build(lo)
        assertEquals(keyHeight, letterHeight(kb), "key height")
        val kv = KeyboardSwitcher.getInstance().mainKeyboardView
        kv.setKeyboard(kb)
        kv.measure(View.MeasureSpec.makeMeasureSpec(1148, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        kv.layout(0, 0, kv.measuredWidth, kv.measuredHeight)
        val bmp = Bitmap.createBitmap(kv.measuredWidth, kv.measuredHeight, Bitmap.Config.ARGB_8888)
        kv.draw(Canvas(bmp))
        File("build/shots").mkdirs()
        File("build/shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return CapsShot(bmp, kb.sortedKeys, kv.paddingLeft, kv.paddingTop)
    }

    private fun luminance(c: Int) = (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000

    /**
     * box of the glyph in the whole key (dark theme): pixels clearly brighter than the key; the rounded corners show the
     * darker keyboard background and are never counted
     */
    private fun box(s: CapsShot, label: String): IntArray {
        val k = s.keys.first { it.label == label }
        val keyLum = luminance(s.bitmap.getPixel(s.left + k.x + k.width / 2, s.top + k.y + 3))
        var l = k.width; var t = k.height; var r = -1; var b = -1
        for (y in 0 until k.height) for (x in 0 until k.width) {
            if (luminance(s.bitmap.getPixel(s.left + k.x + x, s.top + k.y + y)) > keyLum + 40) {
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
            }
        }
        return intArrayOf(l, t, r, b, k.width, k.height)
    }

    private fun height(b: IntArray) = b[3] - b[1] + 1
    private fun width(b: IntArray) = b[2] - b[0] + 1

    @Test fun capitalsAreSmallerAtKey146() {
        val shifted = russian(helium314.keyboard.keyboard.KeyboardElement.ALPHABET_MANUAL_SHIFTED, 146, "caps_ru_146")
        val caps = listOf("В", "А", "П", "Р", "О", "Л").map { height(box(shifted, it)) }
        val zh = box(shifted, "Ж")
        println("LOOK caps 146: heights=$caps Ж width=${width(zh)} of ${zh[4]}")
        caps.forEach { assertTrue(it in 61..69, "capital height $it at key 146 (target about 65)") }
        assertTrue(width(zh) in 70..82, "Ж width ${width(zh)} (target about 77)")
        for (tail in listOf("Щ", "Ц")) {
            val b = box(shifted, tail)
            val gap = b[5] - 1 - b[3]
            println("LOOK caps 146: $tail bottom=${b[3]} of ${b[5]}, gap $gap")
            assertTrue(gap >= 8, "$tail tail $gap px from the bottom edge of the key")
        }
        // small letters stay as they were (66.5 %): the change of shift does not jump much
        val small = russian(helium314.keyboard.keyboard.KeyboardElement.ALPHABET, 146, "small_ru_146")
        val lower = height(box(small, "в"))
        val lowerWidth = width(box(small, "ж"))
        println("LOOK small 146: в height=$lower ж width=$lowerWidth")
        assertTrue(lower in 46..56, "small letter height $lower")
        assertTrue(caps[0] < lower * 1.35, "capital ${caps[0]} vs small $lower")
    }

    @Test fun capitalsAreSmallerAtKey133() {
        val shifted = russian(helium314.keyboard.keyboard.KeyboardElement.ALPHABET_MANUAL_SHIFTED, 133, "caps_ru_133")
        val caps = listOf("В", "А", "П", "Р", "О", "Л").map { height(box(shifted, it)) }
        println("LOOK caps 133: heights=$caps")
        caps.forEach { assertTrue(it in 56..62, "capital height $it at key 133 (target about 59)") }
    }

    @Test fun allShiftStatesUseTheSameCapitalSize() {
        val heights = listOf(helium314.keyboard.keyboard.KeyboardElement.ALPHABET_MANUAL_SHIFTED,
            helium314.keyboard.keyboard.KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED,
            helium314.keyboard.keyboard.KeyboardElement.ALPHABET_SHIFT_LOCKED)
            .map { e -> russian(e, 146, "caps_ru_${e.name.lowercase()}").let { s -> height(box(s, "В")) } }
        println("LOOK caps by shift state: $heights")
        assertEquals(1, heights.toSet().size, "$heights")
    }

    @Test fun functionalKeyLabelsAreNotChanged() {
        val shifted = russian(helium314.keyboard.keyboard.KeyboardElement.ALPHABET_MANUAL_SHIFTED, 146, "caps_ru_146_functional")
        val small = russian(helium314.keyboard.keyboard.KeyboardElement.ALPHABET, 146, "small_ru_146_functional")
        val label = shifted.keys.first { it.label == "?123" || it.label?.contains("123") == true }.label!!
        assertEquals(height(box(small, label)), height(box(shifted, label)), "label $label")
    }

    // ---- stage 5e: panels in the design of the promo video (docs/DESIGN.md, 5е-3) ----

    private fun pix(root: View, v: View, x: Int, y: Int, s: Shot): Int {
        val off = offsetIn(root, v)
        return s.bitmap.getPixel(off[0] + x, off[1] + y)
    }

    /** a shown text: hidden views of the other mode have the same labels */
    private fun findShown(root: View, text: String): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findShown(root.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun bounds(root: View, v: View): IntArray { val o = offsetIn(root, v); return intArrayOf(o[0], o[1], o[0] + v.width, o[1] + v.height) }

    private fun assertBox(expected: FloatArray, actual: IntArray, what: String, tolerance: Int = 2) {
        for (i in 0..3) assertTrue(abs(expected[i] - actual[i]) <= tolerance, "$what: expected ${expected.toList()}, is ${actual.toList()}")
    }

    private fun blend(a: Int, b: Int, alpha: Int) = helium314.keyboard.latin.ai.AikbMotion.blend(a, b, alpha / 255f)

    private fun palette(dark: Boolean) = Triple(if (dark) darkBackground else 0xFFEBECF0.toInt(), if (dark) darkKey else 0xFFFFFFFF.toInt(),
        if (dark) goldDark else panelGoldLight)

    @Test fun voicePanelFollowsTheDesign() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            var panel: VoiceInputView? = null
            val s = shoot(null, "design_voice_$colors", colors, voicePanel = { _, p ->
                p.freezeTime(0.2618f) // 6t = π/2: p = 1, the rings are furthest out
                p.setOpenAi(true)
                p.update(VoiceState.LISTENING, "", null)
                for (l in listOf(0.3f, 0.6f, 0.9f, 1f)) p.setLevel(l) // the real levels of the microphone
                panel = p
            })
            val p = panel!!
            val root = p.rootView
            val d = helium314.keyboard.latin.ai.DesignScale(p.width, p.height)
            val (bg, key, gold) = palette(colors == "aikb_dark")
            println("LOOK voice panel $colors ${p.width}x${p.height} k=${d.k}")
            val cx = p.width / 2; val cy = d.y(560f).toInt()
            assertEquals(key, pix(root, p, cx, cy - d.size(110f).toInt(), s), "circle of radius 120 in the key color")
            assertEquals(bg, pix(root, p, cx + d.size(230f).toInt(), cy - d.size(120f).toInt(), s), "nothing beyond the rings")
            // rings at p = 1: radius 120 + 18 + 26k, alpha 120, 85, 50, width 4
            for (k in 0..2) {
                val r = d.size(VoiceInputView.ringRadius(k, 1f))
                assertTrue(near(blend(bg, gold, VoiceInputView.RING_ALPHA[k]), pix(root, p, cx, (cy - r).toInt(), s), 10), "ring $k")
            }
            // bars: the newest level next to the circle, half height 14 + 54 × level
            assertEquals(listOf(68f, 62.6f, 46.4f, 30.2f), p.barHalfHeights().map { Math.round(it * 10) / 10f })
            for (i in 0..3) {
                val x = cx + d.size(VoiceInputView.barOffset(i)).toInt()
                val half = d.size(p.barHalfHeights()[i])
                assertTrue(near(blend(bg, gold, VoiceInputView.barAlpha(i)), pix(root, p, x, (cy - half + d.size(4f)).toInt(), s), 10), "bar $i inside")
                assertEquals(bg, pix(root, p, x, (cy - half - d.size(6f)).toInt(), s), "bar $i ends at its level")
                assertTrue(near(blend(bg, gold, VoiceInputView.barAlpha(i)), pix(root, p, p.width - x, cy, s), 10), "bar $i on the left too")
            }
            // the bottom row, height 120, margin 40: one "Cancel" over the whole width, as in 5e (build 4351: no "Done")
            val cancel = findText(p, latinIME.getString(R.string.voice_cancel))!!
            assertBox(floatArrayOf(d.x(40f), p.height - d.size(160f), p.width - d.x(40f), p.height - d.size(40f)), bounds(p, cancel), "Cancel")
            // the grey hint under the circle, below the outer ring and above "Cancel", centered
            val hint = p.finishHint()!!
            val hb = bounds(p, hint)
            assertTrue(hb[1] >= d.y(560f) + d.size(VoiceInputView.FINISH_HINT_FROM_CENTER) - 1 && hb[3] <= p.height - d.size(160f) + 1, "hint ${hb.toList()}")
            assertTrue(abs((hb[0] + hb[2]) / 2 - p.width / 2f) < 2, "hint is centered")
            // the status in bold gold around y 300 of the design
            val status = findText(p, p.statusText())!!
            val statusCenter = offsetIn(p, status)[1] + status.height / 2
            assertTrue(abs(statusCenter - d.y(297f)) < d.size(10f), "status at $statusCenter")
            assertEquals(android.graphics.Typeface.BOLD, (status as android.widget.TextView).typeface.style and android.graphics.Typeface.BOLD)
            assertTrue(p.height <= s.bitmap.height)
        }
    }

    @Test fun voiceBarsFollowTheRealLevelAndStayQuietOtherwise() {
        var panel: VoiceInputView? = null
        shoot(null, "design_voice_quiet_dark", "aikb_dark", voicePanel = { _, p ->
            p.freezeTime(0f)
            p.update(VoiceState.LISTENING, "", null)
            panel = p
        })
        val p = panel!!
        assertEquals(listOf(14f, 14f, 14f, 14f), p.barHalfHeights(), "silence: the bars are at their minimum, nothing invented")
        p.setLevel(0.5f)
        assertEquals(listOf(41f, 14f, 14f, 14f), p.barHalfHeights())
        p.update(VoiceState.PROCESSING, "", null)
        assertEquals(listOf(14f, 14f, 14f, 14f), p.barHalfHeights(), "no bars while recognizing")
        p.setLevel(1f)
        assertEquals(listOf(14f, 14f, 14f, 14f), p.barHalfHeights(), "levels after the recording are ignored")
        assertTrue(p.ringsPulse())
        p.update(VoiceState.ERROR, "", VoiceError.NO_SPEECH)
        assertTrue(!p.ringsPulse())
        // after an error: "Retry" next to "Cancel"
        assertTrue(findText(p, latinIME.getString(R.string.voice_retry)) != null)
        // the formula of the design
        assertEquals(0.5f, VoiceInputView.pulse(0f))
        assertEquals(120f + 18f * 0.4f, VoiceInputView.ringRadius(0, 0f))
        assertEquals(120f + 70f, VoiceInputView.ringRadius(2, 1f))
    }

    @Test fun voicePanelSystemRecognizerVoiceDesign() {
        voiceShot("design_voice_system_dark", "aikb_dark", VoiceState.LISTENING)
        voiceShot("design_voice_error_light", "aikb_light", VoiceState.ERROR, VoiceError.NO_SPEECH)
    }

    @Test fun commandsPanelFollowsTheDesign() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            val (s, panel) = aiShot("design_commands_$colors", colors) { it.showCommands("ru", "en") }
            val d = helium314.keyboard.latin.ai.DesignScale(panel.width, panel.height)
            val (_, key, _) = palette(colors == "aikb_dark")
            assertTrue(findText(panel, "What to do with the text?") != null)
            val chips = panel.commandChips()
            assertEquals(6, chips.size)
            chips.forEachIndexed { i, chip ->
                val col = i % 2; val row = i / 2
                val x0 = d.x(30f + 560f * col); val y0 = d.y(250f + 190f * row)
                assertBox(floatArrayOf(x0, y0, x0 + d.x(528f), y0 + d.size(150f)), bounds(panel, chip), "chip $i")
                assertEquals(key, pix(panel.rootView, chip, (chip.width * 0.05f).toInt(), chip.height / 2, s), "chip $i color")
            }
            // the chips fit into the keyboard
            assertTrue(chips.last().bottom <= panel.height)
        }
    }

    @Test fun pressedChipTurnsGoldWithDarkText() {
        durationScale(0f) // no animation: the result at once
        for (colors in listOf("aikb_dark", "aikb_light")) {
            var chip: android.widget.TextView? = null
            val (s, panel) = aiShot("design_commands_pressed_$colors", colors) {
                it.showCommands("ru", "en")
                chip = it.commandChips()[0] as android.widget.TextView
                chip!!.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 20f, 20f, 0))
            }
            val pal = helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors)
            assertEquals(pal.gold, pix(panel.rootView, chip!!, (chip!!.width * 0.05f).toInt(), chip!!.height / 2, s))
            assertEquals(pal.onGold, chip!!.currentTextColor)
            assertTrue(helium314.keyboard.latin.ai.AiTint.contrast(pal.onGold, pal.gold) >= 4.5, "text on gold readable ($colors)")
        }
    }

    @Test fun previewPanelFollowsTheDesign() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            val (s, panel) = aiShot("design_preview_$colors", colors, ::result)
            val d = helium314.keyboard.latin.ai.DesignScale(panel.width, panel.height)
            val (_, key, gold) = palette(colors == "aikb_dark")
            val root = panel.rootView
            val replace = findShown(panel, "Replace")!!; val insert = findShown(panel, "Insert next to")!!; val cancel = findShown(panel, "Cancel")!!
            assertBox(floatArrayOf(d.x(30f), d.y(700f), d.x(360f), d.y(840f)), bounds(panel, replace), "Replace")
            assertBox(floatArrayOf(d.x(378f), d.y(700f), d.x(828f), d.y(840f)), bounds(panel, insert), "Insert next to")
            assertBox(floatArrayOf(d.x(846f), d.y(700f), d.x(1118f), d.y(840f)), bounds(panel, cancel), "Cancel")
            assertEquals(gold, pix(root, replace, (replace.width * 0.08f).toInt(), replace.height / 2, s))
            assertEquals(key, pix(root, insert, (insert.width * 0.08f).toInt(), insert.height / 2, s))
            // the card of the answer: x 30–1118, y 240–650, key color
            val card = findText(panel, "Привет! Это исправленный текст, который пришёл от ИИ.")!!.parent as View
            assertBox(floatArrayOf(d.x(30f), d.y(240f), d.x(1118f), d.y(650f)), bounds(panel, card), "card")
            assertEquals(key, pix(root, card, card.width - (card.width * 0.02f).toInt(), card.height / 2, s))
            // the spark and the command in gold
            val heading = findShown(panel, "Fix")!! as android.widget.TextView
            val pal = helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors)
            assertEquals(pal.goldText, heading.currentTextColor)
            assertTrue(helium314.keyboard.latin.ai.AiTint.contrast(pal.goldText, pal.background) >= 4.5)
        }
    }

    @Test fun previewStreamsTheAnswer() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            val (_, panel) = aiShot("design_streaming_$colors", colors) {
                it.showWorking(helium314.keyboard.latin.ai.AiCommand.TRANSLATE, "en")
                it.setAnswer("Hello! This is the transl")
            }
            assertTrue(panel.cardText().startsWith("Hello! This is the transl") && panel.cardText().endsWith("▍"), panel.cardText())
            assertEquals(listOf("Cancel"), panel.buttonTexts(), "nothing to replace before the answer is complete")
            assertTrue(findShown(panel, "Translate → English") != null)
            panel.showResult("Hello! This is the translation.", true)
            assertEquals("Hello! This is the translation.", panel.cardText())
            assertEquals(listOf("Replace", "Insert next to", "Cancel"), panel.buttonTexts())
        }
    }

    @Test fun panelsFollowRemoveAnimations() {
        durationScale(0f)
        assertTrue(!helium314.keyboard.latin.ai.AikbMotion.enabled(latinIME))
        val (_, still) = aiShot("design_commands_still", "aikb_dark") { it.showCommands("ru", "en") }
        assertEquals(0f, still.translationY, "no slide with animations off")
        helium314.keyboard.latin.suggestions.StripGlowDrawable.frozenSeconds = null
        val glow = helium314.keyboard.latin.suggestions.StripGlowDrawable(latinIME, darkKey,
            helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors)).apply { glow = true }
        val a1 = glow.glowAlpha()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
        assertEquals(a1, glow.glowAlpha(), "the glow does not pulse")
        durationScale(1f)
        assertTrue(helium314.keyboard.latin.ai.AikbMotion.enabled(latinIME))
        val (_, moving) = aiShot("design_commands_slide", "aikb_dark") { it.showCommands("ru", "en") }
        assertTrue(moving.translationY > 0f || moving.alpha < 1f, "slides up from below with animations on")
        val b1 = glow.glowAlpha()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
        assertTrue(b1 != glow.glowAlpha(), "the glow pulses")
    }

    @Test fun stripButtonsGlowGoldWhenActive() {
        for ((colors, gold) in listOf("aikb_dark" to goldDark, "aikb_light" to helium314.keyboard.latin.ai.PanelPalette.LIGHT_GOLD)) {
            val s = shoot(null, "design_strip_glow_$colors", colors, ai = true, micGold = true)
            for (v in listOf(aiKeyOf(), micOf())) {
                val glow = v.background as helium314.keyboard.latin.suggestions.StripGlowDrawable
                assertTrue(glow.glow)
                // gold rings between the circle and the edge of the button
                val r = v.height / 2f * (1 - 2 * helium314.keyboard.latin.suggestions.StripGlowDrawable.INSET)
                var ring = 0
                val off = offsetIn(v.rootView, v)
                for (y in 0 until v.height) for (x in 0 until v.width) {
                    val dist = Math.hypot(x - v.width / 2.0, y - v.height / 2.0)
                    if (dist > r + 1 && dist < v.height / 2.0) {
                        val c = s.bitmap.getPixel(off[0] + x, off[1] + y)
                        val bg = if (colors == "aikb_dark") darkBackground else 0xFFEBECF0.toInt()
                        if (c != bg) ring++
                    }
                }
                println("LOOK glow ring pixels $colors: $ring")
                assertTrue(ring > 40, "no glow rings ($colors): $ring")
            }
            assertTrue(gold != 0)
            val off = shoot(null, "design_strip_plain_$colors", colors, ai = true, micGold = false,
                aiState = helium314.keyboard.latin.ai.AiGuard.Button.OPENS_SETTINGS)
            assertTrue(!(aiKeyOf().background as helium314.keyboard.latin.suggestions.StripGlowDrawable).glow)
            assertTrue(!(micOf().background as helium314.keyboard.latin.suggestions.StripGlowDrawable).glow, "no glow without AI")
            assertTrue(off.bitmap.width > 0)
        }
    }

    @Test fun pressSpreadsARing() {
        durationScale(1f) // the ring is an animation
        helium314.keyboard.latin.suggestions.StripGlowDrawable.frozenSeconds = 0f
        shoot(null, "design_strip_ripple_dark", "aikb_dark", ai = true)
        val v = aiKeyOf()
        val glow = v.background as helium314.keyboard.latin.suggestions.StripGlowDrawable
        fun render(): Bitmap { val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); glow.draw(Canvas(b)); return b }
        val before = render()
        glow.startRipple(v.width * 0.3f, v.height * 0.5f)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(150))
        val during = render()
        var diff = 0
        for (y in 0 until v.height) for (x in 0 until v.width) if (before.getPixel(x, y) != during.getPixel(x, y)) diff++
        assertTrue(diff > 30, "the ring is drawn: $diff")
        File("build/shots/design_strip_ripple_frame.png").outputStream().use { during.compress(Bitmap.CompressFormat.PNG, 100, it) }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
        val after = render()
        var left = 0
        for (y in 0 until v.height) for (x in 0 until v.width) if (before.getPixel(x, y) != after.getPixel(x, y)) left++
        assertEquals(0, left, "the ring is gone after 0.45 s")
        durationScale(0f)
        glow.startRipple(v.width * 0.3f, v.height * 0.5f)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(150))
        val still = render()
        var moved = 0
        for (y in 0 until v.height) for (x in 0 until v.width) if (before.getPixel(x, y) != still.getPixel(x, y)) moved++
        assertEquals(0, moved, "no ring with animations off")
    }

    @Test fun lightThemeGoldKeepsContrast() {
        for (colors in listOf("aikb_dark", "aikb_light")) {
            shoot(null, "design_palette_$colors", colors)
            val p = helium314.keyboard.latin.ai.PanelPalette.from(Settings.getValues().mColors)
            val c = helium314.keyboard.latin.ai.AiTint::contrast
            assertTrue(c(p.gold, p.background) >= 3.0, "gold on the panel ($colors): ${c(p.gold, p.background)}")
            assertTrue(c(p.gold, p.key) >= 3.0, "gold on a key ($colors): ${c(p.gold, p.key)}")
            assertTrue(c(p.goldText, p.background) >= 4.5, "gold text ($colors)")
            assertTrue(c(p.onGold, p.gold) >= 4.5, "text on gold ($colors)")
            assertTrue(c(p.text, p.key) >= 4.5 && c(p.grey, p.background) >= 4.5, "texts ($colors)")
            assertTrue(c(p.red, p.background) >= 3.0, "red ($colors)")
        }
    }

    @Test fun sparkleHasTheShapeOfTheLogo() {
        val d = androidx.core.content.ContextCompat.getDrawable(latinIME, R.drawable.ic_aikb_sparkle)!!
        val size = 240
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b))
        File("build/shots/design_sparkle.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun filled(x: Float, y: Float) = (b.getPixel((x / 24 * size).toInt(), (y / 24 * size).toInt()) ushr 24) > 128
        assertTrue(filled(11.08f, 12.92f), "big spark")
        assertTrue(filled(19.18f, 4.82f), "small spark")
        assertTrue(!filled(16f, 8f), "two separate sparks")
        // concave sides: the middle of a side is far inside the straight line between two tips
        assertTrue(!filled(6.5f, 8.5f) && !filled(15.5f, 17.5f))
    }

    @Test fun sparklePulsesWhileALongPressRequestRuns() {
        shoot(null, "design_strip_busy_dark", "aikb_dark", ai = true)
        val strip = lastPinned!!.rootView.findViewById<helium314.keyboard.latin.suggestions.SuggestionStripView>(R.id.suggestion_strip_view)
        strip.setAiBusy(true)
        assertTrue(strip.isAiBusy)
        assertEquals(0.6f, aiKeyOf().alpha, "with animations off the ✨ is only dimmed")
        strip.setAiBusy(false)
        assertTrue(!strip.isAiBusy)
        assertEquals(1f, aiKeyOf().alpha)
        durationScale(1f)
        strip.setAiBusy(true)
        assertTrue(strip.isAiBusy)
        strip.setAiBusy(false)
        assertEquals(1f, aiKeyOf().alpha, "back to normal after the answer")
        // the button is long-clickable (TalkBack) and keeps its click
        assertTrue(aiKeyOf().isLongClickable && aiKeyOf().hasOnClickListeners())
    }
}
