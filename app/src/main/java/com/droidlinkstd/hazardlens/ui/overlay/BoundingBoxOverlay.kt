package com.droidlinkstd.hazardlens.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.droidlinkstd.hazardlens.data.Detection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom overlay canvas that visualizes road hazard bounding boxes, corner indicators,
 * and high-contrast label badges over camera, image, or video viewports.
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var detections: List<Detection> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    // Paint for main bounding box outline (4dp bright red-orange stroke)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.parseColor("#FF3D00") // High-contrast safety orange-red
    }

    // Paint for corner accent brackets (HUD effect)
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFD600") // Vibrant warning amber
    }

    // Paint for badge background
    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9181818") // Semi-transparent dark surface
    }

    // Paint for badge stroke/border
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#FF3D00")
    }

    // Paint for badge text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val textBounds = Rect()
    private val badgeRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0 || detections.isEmpty()) {
            return
        }

        for (detection in detections) {
            // Map normalized 0.0-1.0 coordinates to view dimensions
            val left = detection.boundingBox.left * viewWidth
            val top = detection.boundingBox.top * viewHeight
            val right = detection.boundingBox.right * viewWidth
            val bottom = detection.boundingBox.bottom * viewHeight

            val rect = RectF(
                min(left, right),
                min(top, bottom),
                max(left, right),
                max(top, bottom)
            )

            // 1. Draw Bounding Rectangle
            canvas.drawRect(rect, boxPaint)

            // 2. Draw Corner Indicators
            drawCornerIndicators(canvas, rect)

            // 3. Draw Rounded Label Badge (e.g. "Pothole 89%")
            drawLabelBadge(canvas, rect, detection)
        }
    }

    private fun drawCornerIndicators(canvas: Canvas, rect: RectF) {
        val cornerLength = min(20f * density, min(rect.width(), rect.height()) / 3f)

        // Top-Left
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, cornerPaint)

        // Top-Right
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, cornerPaint)

        // Bottom-Left
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, cornerPaint)

        // Bottom-Right
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, cornerPaint)
    }

    private fun drawLabelBadge(canvas: Canvas, rect: RectF, detection: Detection) {
        val confidencePercent = (detection.confidence * 100).roundToInt()
        val text = "${detection.label} $confidencePercent%"

        textPaint.getTextBounds(text, 0, text.length, textBounds)

        val paddingH = 8f * density
        val paddingV = 4f * density
        val badgeHeight = textBounds.height() + (paddingV * 2)
        val badgeWidth = textBounds.width() + (paddingH * 2)

        // Place badge directly above bounding box if space permits, otherwise just inside top
        val badgeTop = if (rect.top - badgeHeight - (4f * density) >= 0) {
            rect.top - badgeHeight - (4f * density)
        } else {
            rect.top + (4f * density)
        }

        val badgeBottom = badgeTop + badgeHeight
        val badgeLeft = rect.left
        val badgeRight = min(badgeLeft + badgeWidth, width.toFloat() - (4f * density))

        badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeBottom)
        val cornerRadius = 6f * density

        // Draw Badge Background & Border
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, badgeBackgroundPaint)
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, badgeBorderPaint)

        // Draw Text inside Badge
        val textX = badgeRect.left + paddingH
        val textY = badgeRect.centerY() + (textBounds.height() / 2f) - textBounds.bottom
        canvas.drawText(text, textX, textY, textPaint)
    }
}
