package com.surendramaran.yolov9tflite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = Color.TRANSPARENT // Makes bounding box invisible
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        for (result in results) {
            val left = result.x1 * width
            val top = result.y1 * height
            val right = result.x2 * width
            val bottom = result.y2 * height

            // Set box color based on severity
            when (result.clsName) {
                "Manholes", "Road-cracks" -> boxPaint.color = Color.YELLOW
                "Uneven-terrain", "Speed-Bumps" -> boxPaint.color = Color.rgb(255, 165, 0) // Orange
                "Unfinished-pavements", "Potholes", "Puddle" -> boxPaint.color = Color.RED
                else -> boxPaint.color = Color.WHITE
            }

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label text
            val label = "${result.clsName} ${(result.cnf * 100).toInt()}%"
            textPaint.getTextBounds(label, 0, label.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            val labelLeft = left
            val labelTop = top - textHeight - 10

            canvas.drawRect(labelLeft, labelTop, labelLeft + textWidth + 16, labelTop + textHeight + 16, textBackgroundPaint)
            canvas.drawText(label, labelLeft + 8, labelTop + textHeight + 8, textPaint)
        }
    }


    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}