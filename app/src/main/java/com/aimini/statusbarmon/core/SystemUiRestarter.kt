package com.aimini.statusbarmon.core

import android.content.Context
import android.widget.Toast

/**
 * 重启 SystemUI 进程。应用自身无权限结束 SystemUI，因此优先借 root 执行；
 * 未授予 root 时会失败，此时提示改用 Shizuku/Root 手动重启。
 */
object SystemUiRestarter {

    private const val TARGET = "com.android.systemui"

    fun restart(context: Context) {
        val ok = runShell("su -c 'killall $TARGET'") ||
            runShell("killall $TARGET") ||
            runShell("pkill -f $TARGET")

        Toast.makeText(
            context,
            if (ok) "已发送重启 SystemUI 指令" else "重启失败：请授予 root，或用 Shizuku/Root 重启",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun runShell(command: String): Boolean = try {
        ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    } catch (t: Throwable) {
        false
    }
}
