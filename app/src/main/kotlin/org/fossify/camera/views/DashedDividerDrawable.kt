package org.fossify.camera.views

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Draws a horizontal dashed line. Used instead of an XML "line" shape, which does not render
 * dashes reliably as a View background.
 */
class DashedDividerDrawable(
    color: Int,
    strokeWidthPx: Float,
    dashWidthPx: Float,
    dashGapPx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        pathEffect = DashPathEffect(floatArrayOf(dashWidthPx, dashGapPx), 0f)
    }

    override fun draw(canvas: Canvas) {
        val y = bounds.exactCenterY()
        canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
