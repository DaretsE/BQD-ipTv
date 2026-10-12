package ru.bqd.iptv

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ScrollView

/** A persistent scrollbar set inside the panel, clear of the content and rounded edge. */
class InsetScrollbarScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3363D4E2 }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isVerticalScrollBarEnabled = false
        setWillNotDraw(false)
    }

    override fun draw(canvas: Canvas) {
        val saveCount = canvas.save()
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        if (range <= extent || extent <= 0) return

        val density = resources.displayMetrics.density
        val inset = 16f * density
        val barWidth = 6f * density
        val radius = barWidth / 2f
        val top = inset
        val bottom = height - inset
        val trackHeight = bottom - top
        val thumbHeight = (trackHeight * extent / range.toFloat())
            .coerceAtLeast(28f * density).coerceAtMost(trackHeight)
        val progress = (scrollY.toFloat() / (range - extent)).coerceIn(0f, 1f)
        val thumbTop = top + (trackHeight - thumbHeight) * progress
        val left = width - inset - barWidth

        // ScrollView leaves the drawing canvas in content coordinates here.
        // Cancel that offset so the indicator stays fixed within the panel.
        val barSaveCount = canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        canvas.drawRoundRect(left, top, left + barWidth, bottom, radius, radius, trackPaint)
        thumbPaint.shader = LinearGradient(left, thumbTop, left, thumbTop + thumbHeight,
            0xFF63D4E2.toInt(), 0xFF2E8B98.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(left, thumbTop, left + barWidth, thumbTop + thumbHeight,
            radius, radius, thumbPaint)
        canvas.restoreToCount(barSaveCount)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()
    }
}
