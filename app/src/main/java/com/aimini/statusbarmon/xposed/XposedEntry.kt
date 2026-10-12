package com.aimini.statusbarmon.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = "StatusBarMon"
        const val SYSTEM_UI = "com.android.systemui"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("[$TAG] zygote initialized, modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI) return

        try {
            StatusBarInjector.install(lpparam)
        } catch (t: Throwable) {
            // 任何异常都不能冒泡到 SystemUI，否则会拖垮状态栏。
            XposedBridge.log("[$TAG] inject failed: ${android.util.Log.getStackTraceString(t)}")
        }
    }
}
