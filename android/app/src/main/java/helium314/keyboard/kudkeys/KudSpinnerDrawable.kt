// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** A small turning arc for the bar's Fix/Instruct slot while a call runs. Drawn in [tint] (tints don't apply). */
class KudSpinnerDrawable(private val sizePx: Int, strokePx: Float, tint: Int) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = tint
    }
    private val oval = RectF()
    private var running = false
    private val tick = Runnable { invalidateSelf(); if (running) scheduleNext() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = paint.strokeWidth
        oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val angle = (SystemClock.uptimeMillis() % 900L) * 360f / 900f
        canvas.drawArc(oval, angle, 270f, false, paint)
    }

    private fun scheduleNext() { scheduleSelf(tick, SystemClock.uptimeMillis() + 16) }

    override fun start() { if (!running) { running = true; scheduleNext() } }
    override fun stop() { running = false; unscheduleSelf(tick) }
    override fun isRunning() = running
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
