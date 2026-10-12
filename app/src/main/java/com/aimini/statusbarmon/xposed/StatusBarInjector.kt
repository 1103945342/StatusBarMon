package com.aimini.statusbarmon.xposed

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.aimini.statusbarmon.settings.MonitorSettings
import com.aimini.statusbarmon.settings.SettingsStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 在 SystemUI 状态栏注入监控视图（内联，不用覆盖层）。
 *
 * 两种落点，随显示模式切换（运行时热切换，无需重启 SystemUI）：
 *  - RING：插到时钟视图正后方，圆环紧贴时间右侧。
 *  - TEXT_TOP：贴到状态栏根布局，水平居中、垂直贴顶。
 *
 * 承载类名各版本不一致，按候选顺序尝试安装 hook，命中即注入；失败只记录日志。
 */
object StatusBarInjector {

    private const val CONTENT_ID = "status_bar_contents"
    private const val CLOCK_ID = "clock"
    private const val VIEW_TAG = "statusbar_mon_view"

    private val CANDIDATE_CLASSES = listOf(
        "com.android.systemui.statusbar.phone.PhoneStatusBarView",
        "com.android.statusbarmon.statusbar.phone.PhoneStatusBarView",
        "com.android.systemui.statusbar.phone.CentralSurroundingStatusBarView",
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
    )

    private val INFLATE_METHODS = listOf("onFinishInflate", "onAttachedToWindow")

    // 记录当前落点，避免每次刷新都重建视图。
    private var placedMode: MonitorSettings.DisplayMode? = null
    private var placedParent: ViewGroup? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        var hooked = false
        for (name in CANDIDATE_CLASSES) {
            if (tryHook(name, lpparam.classLoader)) hooked = true
        }
        if (!hooked) {
            XposedBridge.log("[StatusBarMon] no candidate status bar class found")
        }
    }

    private fun tryHook(className: String, classLoader: ClassLoader): Boolean {
        val clazz = try {
            XposedHelpers.findClassIfExists(className, classLoader)
        } catch (t: Throwable) {
            null
        } ?: return false

        var installed = false
        for (methodName in INFLATE_METHODS) {
            val method: Method = clazz.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: continue

            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val host = param.thisObject as? View ?: return
                        inject(host)
                    }
                })
                installed = true
            } catch (t: Throwable) {
                XposedBridge.log("[StatusBarMon] hook $className#$methodName failed: $t")
            }
        }
        return installed
    }

    private fun inject(host: View) {
        val root = host.rootView
        if (root.findViewWithTag<View>(VIEW_TAG) != null) return

        val context = host.context ?: return
        val monitor = try {
            StatusBarMonitorView(context)
        } catch (t: Throwable) {
            XposedBridge.log("[StatusBarMon] create view failed: $t")
            return
        }
        monitor.tag = VIEW_TAG

        // 内容/尺寸变化时重新评估落点（仅模式变化时才真正搬动视图）。
        monitor.setOnChanged { place(monitor, host) }
        // auto 颜色：跟随状态栏时间的当前文字颜色。
        monitor.clockColorProvider = { currentClockColor(host) }
        monitor.start()

        place(monitor, host)
    }

    private fun place(monitor: StatusBarMonitorView, host: View) {
        try {
            val settings = SettingsStore.readForHook()
            if (placedParent != null && placedMode == settings.displayMode) {
                // 落点未变，仅让视图保持当前父级即可。
                return
            }

            // 先脱离旧父级，避免同一视图挂在两处。
            (monitor.parent as? ViewGroup)?.removeView(monitor)

            val ok = when (settings.displayMode) {
                MonitorSettings.DisplayMode.TEXT_TOP -> placeTop(monitor, host)
                MonitorSettings.DisplayMode.RING -> placeAfterClock(monitor, host)
            }
            if (ok) {
                placedMode = settings.displayMode
            } else {
                XposedBridge.log("[StatusBarMon] place failed for mode=${settings.displayMode}")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[StatusBarMon] place error: ${android.util.Log.getStackTraceString(t)}")
        }
    }

    /** 圆环方案：插到时钟正后方。 */
    private fun placeAfterClock(monitor: StatusBarMonitorView, host: View): Boolean {
        val clock = findClock(host) ?: run {
            XposedBridge.log("[StatusBarMon] clock not found")
            return false
        }
        val parent = clock.parent as? ViewGroup ?: return false

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_VERTICAL }

        return try {
            parent.addView(monitor, parent.indexOfChild(clock) + 1, params)
            placedParent = parent
            XposedBridge.log("[StatusBarMon] ring placed after clock in ${parent.javaClass.simpleName}")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[StatusBarMon] ring place failed: $t")
            false
        }
    }

    /** 文字方案：贴状态栏根布局，水平居中、垂直贴顶。 */
    private fun placeTop(monitor: StatusBarMonitorView, host: View): Boolean {
        val parent = host as? ViewGroup ?: return false
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

        return try {
            parent.addView(monitor, params)
            placedParent = parent
            XposedBridge.log("[StatusBarMon] text placed top-center in ${parent.javaClass.simpleName}")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[StatusBarMon] text place failed: $t")
            false
        }
    }

    private fun findClock(host: View): View? {
        val id = resolveId(host, CLOCK_ID)
        if (id != 0) {
            host.findViewById<View>(id)?.let { return it }
        }
        return deepFindByClassName(host as? ViewGroup, "Clock")
    }

    /** 读取时间控件的当前文字颜色；拿不到则退回子视图的默认色。 */
    private fun currentClockColor(host: View): Int {
        val clock = findClock(host)
        if (clock is android.widget.TextView) {
            val color = clock.currentTextColor
            if (color != 0) return color
        }
        return 0xFFFFFFFF.toInt()
    }

    private fun deepFindByClassName(group: ViewGroup?, simpleName: String): View? {
        if (group == null) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.simpleName == simpleName) return child
            if (child is ViewGroup) {
                deepFindByClassName(child, simpleName)?.let { return it }
            }
        }
        return null
    }

    private fun resolveId(anchor: View, name: String): Int =
        anchor.resources.getIdentifier(name, "id", "com.android.systemui")
}
