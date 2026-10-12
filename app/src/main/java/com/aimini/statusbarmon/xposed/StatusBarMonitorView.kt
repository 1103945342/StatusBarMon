package com.aimini.statusbarmon.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aimini.statusbarmon.core.Formatter
import com.aimini.statusbarmon.core.MetricsProvider
import com.aimini.statusbarmon.settings.MonitorSettings
import com.aimini.statusbarmon.settings.SettingsStore
import de.robv.android.xposed.XposedBridge

/**
 * 状态栏监控视图，两种方案二选一：
 *  - RING：CPU / MEM 两个圆环内联在时钟之后。
 *  - TEXT_TOP：单行文字（如 CPU:25% MEM:40% 35C）居中贴在状态栏最顶部。
 * 运行在 SystemUI 进程主线程，周期性读取设置与指标并热切换模式。
 */
@SuppressLint("ViewConstructor")
class StatusBarMonitorView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val ringGroup = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val cpuRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val memRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val cpuLabel = createPrefix()
    private val memLabel = createPrefix()
    private val cpuGauge = CircleGaugeView(context)
    private val memGauge = CircleGaugeView(context)

    private val textView = TextView(context).apply {
        isSingleLine = true
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private var intervalMs = 2000L
    private var started = false
    private var loggedSettings = false
    private var lastMode: MonitorSettings.DisplayMode? = null

    private companion object {
        const val DEFAULT_COLOR = 0xFFFFFFFFL.toInt()
    }

    /** 读取状态栏时间的当前颜色，用于 auto（跟随时间颜色）。 */
    var clockColorProvider: (() -> Int)? = null

    private val loop = object : Runnable {
        override fun run() {
            tick()
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        cpuLabel.text = "CPU:"
        memLabel.text = "MEM:"
        cpuRow.addView(cpuLabel)
        cpuRow.addView(cpuGauge)
        memRow.addView(memLabel)
        memRow.addView(memGauge)
        ringGroup.addView(cpuRow)
        ringGroup.addView(memRow)
    }

    private fun createPrefix(): TextView = TextView(context).apply {
        isSingleLine = true
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
    }

    fun start() {
        if (started) return
        started = true
        mainHandler.post(loop)
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacks(loop)
    }

    private fun tick() {
        try {
            val settings = SettingsStore.readForHook()
            intervalMs = settings.intervalMs.toLong()
            if (!loggedSettings) {
                loggedSettings = true
                XposedBridge.log(
                    "[StatusBarMon] settings color=#${Integer.toHexString(settings.textColor)} " +
                        "size=${settings.textSizeSp} spacing=${settings.letterSpacing} " +
                        "twoLine=${settings.twoLine} mode=${settings.displayMode}"
                )
            }

            if (!settings.enabled) {
                visibility = View.GONE
                return
            }
            visibility = View.VISIBLE

            applyAppearance(settings)
            switchModeIfNeeded(settings)

            val snapshot = MetricsProvider.read(context)
            if (settings.displayMode == MonitorSettings.DisplayMode.TEXT_TOP) {
                textView.text = Formatter.formatInline(snapshot)
            } else {
                cpuGauge.setProgress(((snapshot.cpuPercent ?: 0.0) / 100.0).toFloat())
                cpuGauge.setLabel(Formatter.plainFreq(snapshot.cpuFreqHz))
                memGauge.setProgress(((snapshot.memPercent ?: 0.0) / 100.0).toFloat())
                memGauge.setLabel(Formatter.plainTemp(snapshot.batteryTempC))
                applyRingGeometry(settings)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[StatusBarMon] tick failed: ${android.util.Log.getStackTraceString(t)}")
        }
    }

    /** 模式切换：直接换掉子视图并重新上报几何尺寸，无需重启 SystemUI。 */
    private fun switchModeIfNeeded(settings: MonitorSettings) {
        if (lastMode == settings.displayMode) return
        lastMode = settings.displayMode
        removeAllViews()
        if (settings.displayMode == MonitorSettings.DisplayMode.TEXT_TOP) {
            orientation = HORIZONTAL
            addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        } else {
            ringGroup.orientation = if (settings.twoLine) VERTICAL else HORIZONTAL
            addView(ringGroup, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        requestLayout()
        notifyChanged()
    }

    /** 依据设置解析实际用颜色：auto 时取时间颜色。 */
    private fun resolveColor(settings: MonitorSettings): Int =
        if (settings.textColor == MonitorSettings.COLOR_AUTO) {
            try {
                clockColorProvider?.invoke() ?: DEFAULT_COLOR
            } catch (t: Throwable) {
                DEFAULT_COLOR
            }
        } else {
            settings.textColor
        }

    private fun applyAppearance(settings: MonitorSettings) {
        val px = settings.textSizeSp * resources.displayMetrics.scaledDensity
        val color = resolveColor(settings)
        for (label in arrayOf(cpuLabel, memLabel)) {
            label.setTextColor(color)
            label.textSize = settings.textSizeSp * 0.85f
            label.letterSpacing = settings.letterSpacing
        }
        for (gauge in arrayOf(cpuGauge, memGauge)) {
            gauge.setTextColor(color)
            gauge.setTextSizePx(px)
            gauge.setLetterSpacing(settings.letterSpacing)
        }
        textView.setTextColor(color)
        textView.textSize = settings.textSizeSp
        textView.letterSpacing = settings.letterSpacing
        applyOutline(settings.outline)
    }

    /** 给文字加黑色描边，保证浅色背景下仍可辨认（状态栏文字本色无法预知）。 */
    private fun applyOutline(enabled: Boolean) {
        val shadow = if (enabled) 1f else 0f
        for (view in arrayOf<TextView>(cpuLabel, memLabel, textView)) {
            view.setShadowLayer(shadow, 0f, 0f, 0xFF000000.toInt())
        }
        for (gauge in arrayOf(cpuGauge, memGauge)) {
            gauge.setTextShadow(enabled)
        }
    }

    /** 圆环直径与子视图尺寸（固定正方形，避免椭圆）。 */
    private fun applyRingGeometry(settings: MonitorSettings) {
        val density = resources.displayMetrics.density
        val diameter = (settings.textSizeSp * 2.6f * density).toInt().coerceAtLeast(1)
        val gap = (density * 1f).toInt().coerceAtLeast(0)
        val prefixWidth = (cpuLabel.paint.measureText("MEM:") + gap).toInt()

        ringGroup.orientation = if (settings.twoLine) VERTICAL else HORIZONTAL
        for (row in arrayOf(cpuRow, memRow)) {
            row.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        if (settings.twoLine) {
            cpuRow.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            }
        }
        for ((label, gauge) in arrayOf(cpuLabel to cpuGauge, memLabel to memGauge)) {
            label.layoutParams = LayoutParams(prefixWidth, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap
            }
            gauge.layoutParams = LayoutParams(diameter, diameter)
        }
        requestLayout()
        notifyChanged()
    }

    private var onChanged: (() -> Unit)? = null

    /** 内容或尺寸变化后回调，供注入器重算让位宽度。父视图类型不同时也可能需要重建。 */
    fun setOnChanged(listener: () -> Unit) {
        onChanged = listener
    }

    private fun notifyChanged() {
        post { onChanged?.invoke() }
    }
}
