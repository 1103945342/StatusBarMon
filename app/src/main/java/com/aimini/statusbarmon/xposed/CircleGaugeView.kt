package com.aimini.statusbarmon.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 单个圆环指示器：外圈背景环 + 按比例的进度弧 + 居中文字。
 * 进度弧颜色按占用程度分级（低=绿、中=黄、高=红），中心文字为数值。
 */
class CircleGaugeView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x33FFFFFF
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        color = Color.WHITE
    }

    private val arcRect = RectF()
    private var progress = 0f
    private var label = ""
    private var hasShadow = false

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    fun setLabel(value: String) {
        if (value != label) {
            label = value
            invalidate()
        }
    }

    fun setTextColor(color: Int) {
        if (textPaint.color != color) {
            textPaint.color = color
            invalidate()
        }
    }

    fun setTextSizePx(sizePx: Float) {
        if (textPaint.textSize != sizePx) {
            textPaint.textSize = sizePx
            invalidate()
        }
    }

    fun setLetterSpacing(spacing: Float) {
        // Paint 无直接字间距接口，用等效的 letterSpacing 通过 TextPaint 设置。
        if (textPaint.letterSpacing != spacing) {
            textPaint.letterSpacing = spacing
            invalidate()
        }
    }

    /** 描边/阴影：浅色背景下靠黑影保证可读。 */
    fun setTextShadow(enabled: Boolean) {
        if (hasShadow == enabled) return
        hasShadow = enabled
        val radius = if (enabled) 2f else 0f
        textPaint.setShadowLayer(radius, 0f, 0f, 0xFF000000.toInt())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val stroke = (size * 0.16f).coerceAtLeast(2f)
        ringPaint.strokeWidth = stroke
        bgPaint.strokeWidth = stroke

        // 始终以中心为基准画正圆，不随视图宽高比变形。
        val radius = (size - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arcRect, 0f, 360f, false, bgPaint)
        ringPaint.color = colorForProgress(progress)
        canvas.drawArc(arcRect, -90f, 360f * progress, false, ringPaint)

        if (label.isNotEmpty()) {
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    private fun colorForProgress(p: Float): Int = when {
        p < 0.5f -> 0xFF4CAF50.toInt()
        p < 0.8f -> 0xFFFFC107.toInt()
        else -> 0xFFF44336.toInt()
    }
}
