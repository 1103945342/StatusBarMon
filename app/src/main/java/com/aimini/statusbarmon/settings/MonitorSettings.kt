package com.aimini.statusbarmon.settings

import android.content.Context
import android.content.SharedPreferences

/** 监控文本的内容与外观配置，设置页与 Hook 进程共用。 */
data class MonitorSettings(
    val enabled: Boolean = true,
    val showCpu: Boolean = true,
    val showFreq: Boolean = true,
    val showMem: Boolean = true,
    val showTemp: Boolean = true,
    val twoLine: Boolean = true,
    val intervalMs: Int = 2000,
    val position: Position = Position.RIGHT,
    val displayMode: DisplayMode = DisplayMode.RING,
    val template: String = "",
    val textSizeSp: Float = 8f,
    val textColor: Int = COLOR_AUTO,
    val letterSpacing: Float = 0f,
    val outline: Boolean = true,
) {
    /** 显示方案：圆环内联 或 顶部居中文字，二选一。 */
    enum class DisplayMode(val raw: String) {
        RING("ring"),
        TEXT_TOP("text_top");

        companion object {
            fun from(raw: String?): DisplayMode =
                entries.firstOrNull { it.raw == raw } ?: RING
        }
    }

    enum class Position(val raw: String) {
        LEFT("left"),
        RIGHT("right");

        companion object {
            fun from(raw: String?): Position =
                entries.firstOrNull { it.raw == raw } ?: RIGHT
        }
    }

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SHOW_CPU = "show_cpu"
        const val KEY_SHOW_FREQ = "show_freq"
        const val KEY_SHOW_MEM = "show_mem"
        const val KEY_SHOW_TEMP = "show_temp"
        const val KEY_TWO_LINE = "two_line"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_POSITION = "position"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_TEMPLATE = "template"
        const val KEY_TEXT_SIZE = "text_size_sp"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_LETTER_SPACING = "letter_spacing"
        const val KEY_OUTLINE = "outline"

        const val MIN_INTERVAL_MS = 500
        const val MAX_INTERVAL_MS = 10_000

        /** 颜色哨兵值：表示跟随状态栏时间颜色（auto），非真实颜色。 */
        const val COLOR_AUTO = Int.MIN_VALUE

        fun from(prefs: SharedPreferences): MonitorSettings = MonitorSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            showCpu = prefs.getBoolean(KEY_SHOW_CPU, true),
            showFreq = prefs.getBoolean(KEY_SHOW_FREQ, true),
            showMem = prefs.getBoolean(KEY_SHOW_MEM, true),
            showTemp = prefs.getBoolean(KEY_SHOW_TEMP, true),
            twoLine = prefs.getBoolean(KEY_TWO_LINE, true),
            intervalMs = prefs.getInt(KEY_INTERVAL, 2000)
                .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            position = Position.from(prefs.getString(KEY_POSITION, Position.RIGHT.raw)),
            displayMode = DisplayMode.from(prefs.getString(KEY_DISPLAY_MODE, DisplayMode.RING.raw)),
            template = prefs.getString(KEY_TEMPLATE, "") ?: "",
            textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 8f),
            textColor = prefs.getInt(KEY_TEXT_COLOR, COLOR_AUTO),
            letterSpacing = prefs.getFloat(KEY_LETTER_SPACING, 0f),
            outline = prefs.getBoolean(KEY_OUTLINE, true),
        )

        fun write(prefs: SharedPreferences, settings: MonitorSettings) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putBoolean(KEY_SHOW_CPU, settings.showCpu)
                .putBoolean(KEY_SHOW_FREQ, settings.showFreq)
                .putBoolean(KEY_SHOW_MEM, settings.showMem)
                .putBoolean(KEY_SHOW_TEMP, settings.showTemp)
                .putBoolean(KEY_TWO_LINE, settings.twoLine)
                .putInt(KEY_INTERVAL, settings.intervalMs)
                .putString(KEY_POSITION, settings.position.raw)
                .putString(KEY_DISPLAY_MODE, settings.displayMode.raw)
                .putString(KEY_TEMPLATE, settings.template)
                .putFloat(KEY_TEXT_SIZE, settings.textSizeSp)
                .putInt(KEY_TEXT_COLOR, settings.textColor)
                .putFloat(KEY_LETTER_SPACING, settings.letterSpacing)
                .putBoolean(KEY_OUTLINE, settings.outline)
                // 必须同步落盘，随后的 makeReadable 才能在文件被重写之后生效；
                // 用 apply() 会在后台线程晚一步重写文件并把权限刷回私有。
                .commit()
        }
    }
}

/**
 * 设置存储。使用 LSPosed 的 New XSharedPreferences 机制：
 * 应用侧以 MODE_WORLD_READABLE 打开偏好，LSPosed 会把偏好目录重定向到
 * 可跨进程读取的 misc 目录；Hook 侧用 XSharedPreferences 读同一份文件。
 * 需在 AndroidManifest 声明 xposedsharedprefs / xposedminversion>=93。
 */
object SettingsStore {

    const val PACKAGE = "com.aimini.statusbarmon"
    const val PREFS_NAME = "statusbar_mon"

    /** 设置页读写。未通过 LSPosed 加载时 MODE_WORLD_READABLE 会抛 SecurityException。 */
    fun prefs(context: Context): SharedPreferences = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Hook 进程内读取配置；文件不可读或异常时退回默认值。 */
    @Suppress("DEPRECATION")
    fun readForHook(): MonitorSettings = try {
        val prefs = de.robv.android.xposed.XSharedPreferences(PACKAGE, PREFS_NAME)
        prefs.reload()
        if (prefs.file?.canRead() == true) MonitorSettings.from(prefs) else MonitorSettings()
    } catch (t: Throwable) {
        MonitorSettings()
    }
}
