package com.example.earthwallet.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.max

/**
 * Simple custom pie chart view for governance allocation display
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val rectF = RectF()
    private var slices: List<PieSlice> = emptyList()

    data class PieSlice(
        @JvmField val name: String,
        @JvmField val percentage: Float,
        @JvmField val color: Int
    )

    fun setData(slices: List<PieSlice>) {
        this.slices = slices
        invalidate() // Trigger a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (slices.isEmpty()) {
            return
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val radius = min(width, height) * 0.4f // 40% of the smaller dimension (bigger pie)

        val centerX = width / 2
        val centerY = height / 2

        // Set up the rectangle for drawing arcs
        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        var startAngle = 0f

        // Draw each slice
        for (slice in slices) {
            paint.color = slice.color

            val sweepAngle = (slice.percentage / 100f) * 360f

            // Draw the pie slice
            canvas.drawArc(rectF, startAngle, sweepAngle, true, paint)

            startAngle += sweepAngle
        }

        // Draw center circle for donut effect (optional)
        paint.color = 0xFFFFFFFF.toInt() // White center
        val innerRadius = radius * 0.4f
        canvas.drawCircle(centerX, centerY, innerRadius, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Make it square
        var size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )

        // Minimum size (bigger minimum)
        size = max(size, 500)

        setMeasuredDimension(size, size)
    }
}