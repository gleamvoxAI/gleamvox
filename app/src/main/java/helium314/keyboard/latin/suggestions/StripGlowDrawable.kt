// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import helium314.keyboard.latin.ai.AikbMotion
import helium314.keyboard.latin.ai.PanelPalette
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The round button of the strip in the AI Keyboard style (same circle as before: 20 of 24.8 of the button). For ✨ and the
 * microphone when they work through AI (docs/DESIGN.md, 5е-3): a soft gold glow, three thin rings around the circle whose
 * alpha pulses 0.25 + 0.15 sin(3t), and a circle that gets slightly warm (#463A16 at full glow). A press starts a ring
 * that spreads from the touch point in 0.45 s. No pulsing with "Remove animations"; the ring is then not shown either.
 */
class StripGlowDrawable(private val context: Context, private val circle: Int, private val palette: PanelPalette) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    var glow = false
        set(value) {
            if (field == value) return
            field = value
            scheduleTick()
            invalidateSelf()
        }
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleStart = -1L
    private var ticking = false

    private val tick = Runnable {
        ticking = false
        invalidateSelf()
        scheduleTick()
    }

    /** the circle with the color it has at this moment (warm while it glows) */
    fun circleColor(): Int = if (glow) AikbMotion.blend(circle, palette.warm, glowAlpha() / MAX_ALPHA) else circle

    private fun seconds() = frozenSeconds ?: ((SystemClock.uptimeMillis() - start) / 1000f)

    /** 0.25 + 0.15 sin(3t); a calm 0.3 with animations off */
    fun glowAlpha(): Float = if (!AikbMotion.enabled(context) && frozenSeconds == null) 0.3f else 0.25f + 0.15f * sin(3f * seconds())

    /** a press at ([x], [y]) inside the button */
    fun startRipple(x: Float, y: Float) {
        if (!AikbMotion.enabled(context)) return
        rippleX = x; rippleY = y
        rippleStart = SystemClock.uptimeMillis()
        scheduleTick()
        invalidateSelf()
    }

    private fun rippleRunning() = rippleStart >= 0 && SystemClock.uptimeMillis() - rippleStart < RIPPLE_MS

    private fun scheduleTick() {
        if (ticking || !isVisible || frozenSeconds != null) return
        if (!(glow && AikbMotion.enabled(context)) && !rippleRunning()) return
        ticking = true
        scheduleSelf(tick, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) { unscheduleSelf(tick); ticking = false } else scheduleTick()
        return changed
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val size = min(b.width(), b.height()).toFloat()
        val r = size / 2f * (1f - 2 * INSET)
        val margin = size / 2f - r
        paint.style = Paint.Style.FILL
        paint.color = circleColor()
        canvas.drawCircle(cx, cy, r, paint)
        if (glow) {
            val a = glowAlpha()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, margin * 0.14f)
            for ((i, f) in RING_FADE.withIndex()) {
                paint.color = AikbMotion.withAlpha(palette.gold, (255 * a * f).toInt())
                canvas.drawCircle(cx, cy, r + margin * (0.22f + 0.3f * i), paint)
            }
        }
        if (rippleRunning()) {
            val f = (SystemClock.uptimeMillis() - rippleStart) / RIPPLE_MS.toFloat()
            val maxR = maxOf(hypot(rippleX - b.left, rippleY - b.top), hypot(rippleX - b.right, rippleY - b.top),
                hypot(rippleX - b.left, rippleY - b.bottom), hypot(rippleX - b.right, rippleY - b.bottom))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, margin * 0.35f)
            paint.color = AikbMotion.withAlpha(if (glow) palette.gold else palette.grey, (170 * (1f - f)).toInt())
            canvas.save()
            canvas.clipRect(b)
            canvas.drawCircle(rippleX, rippleY, maxR * f, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** the circle of the round strip buttons: 20 of 24.8, as the expand key */
        const val INSET = 0.0806f
        const val MAX_ALPHA = 0.4f
        const val RIPPLE_MS = 450L
        private const val FRAME_MS = 50L
        /** the outer rings fade */
        private val RING_FADE = floatArrayOf(1f, 0.7f, 0.45f)

        /** for tests and pictures: every glow is drawn at this moment and does not tick */
        @JvmField
        var frozenSeconds: Float? = null
    }
}
