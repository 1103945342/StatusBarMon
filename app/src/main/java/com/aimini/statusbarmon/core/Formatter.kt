package com.aimini.statusbarmon.core

import java.util.Locale

/**
 * 把采样结果按模板渲染成状态栏文本。
 * 模板占位符：{cpu} {freq} {mem} {temp}，未知占位符原样保留。
 */
object Formatter {

    const val DEFAULT_TEMPLATE = "CPU {cpu} · {freq} · RAM {mem} · {temp}"
    private const val SEPARATOR = " · "

    /** customTemplate 为空时按开关拼默认模板；全关则返回空串（不显示）。 */
    fun buildTemplate(
        showCpu: Boolean,
        showFreq: Boolean,
        showMem: Boolean,
        showTemp: Boolean,
        customTemplate: String,
    ): String {
        if (customTemplate.isNotBlank()) return customTemplate
        return buildList {
            if (showCpu) add("CPU {cpu}")
            if (showFreq) add("{freq}")
            if (showMem) add("RAM {mem}")
            if (showTemp) add("{temp}")
        }.joinToString(SEPARATOR)
    }

    fun format(snapshot: StatusSnapshot, template: String): String {
        var text = template
            .replace("{cpu}", cpu(snapshot.cpuPercent))
            .replace("{freq}", freq(snapshot.cpuFreqHz))
            .replace("{mem}", percent(snapshot.memPercent))
            .replace("{temp}", temp(snapshot.batteryTempC))
        // 指标缺失会留下多余空格，统一压缩并去掉首尾空白。
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    fun percent(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"

    fun freq(value: Double?): String = when {
        value == null -> "--"
        value >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fGHz", value / 1_000_000_000.0)
        value >= 1_000_000.0 -> String.format(Locale.US, "%.0fMHz", value / 1_000_000.0)
        else -> String.format(Locale.US, "%.0fKHz", value / 1_000.0)
    }

    fun temp(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f°C", it) } ?: "--"

    fun cpu(value: Double?): String = percent(value)

    /** 圆环中心用：频率数值，无单位。 */
    fun plainFreq(hz: Double?): String = when {
        hz == null -> "--"
        hz >= 1_000_000_000.0 -> "%.1f".format(Locale.US, hz / 1_000_000_000.0)
        else -> "%.0f".format(Locale.US, hz / 1_000_000.0)
    }

    /** 圆环中心用：温度数值，无单位。 */
    fun plainTemp(celsius: Double?): String =
        celsius?.let { "%.0f".format(Locale.US, it) } ?: "--"

    /** 顶部文字模式用：CPU:25% MEM:40% 35℃。 */
    fun formatInline(snapshot: StatusSnapshot): String {
        val cpu = percent(snapshot.cpuPercent)
        val mem = percent(snapshot.memPercent)
        val temp = snapshot.batteryTempC?.let { "%.0f℃".format(Locale.US, it) } ?: "--"
        return "CPU:$cpu MEM:$mem $temp"
    }
}
