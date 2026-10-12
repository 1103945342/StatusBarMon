package com.aimini.statusbarmon.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File

data class StatusSnapshot(
    val cpuPercent: Double?,
    val cpuFreqHz: Double?,
    val memPercent: Double?,
    val batteryTempC: Double?,
    val coreCount: Int,
)

/**
 * 直接读取内核暴露的 /proc、/sys 与电池 sticky intent。
 *
 * 注意：部分 ROM（如 MIUI/HyperOS）在 Enforcing 下会把 /proc/stat 标为
 * proc_stat 并对应用域拒绝读取，因此 CPU 占用率提供 cpuidle 兜底。
 * 所有读取都会吞掉异常并返回 null，绝不能因读失败而崩掉宿主进程
 * （本模块的宿主是 SystemUI）。
 */
object MetricsProvider {

    private const val CPU_ROOT = "/sys/devices/system/cpu"
    private val CPU_LINE = Regex("^cpu\\s+.*")
    private val WHITESPACE = Regex("\\s+")
    private val CORE_NAME = Regex("cpu\\d+")

    // /proc/stat 差分采样状态
    private var statTotal = 0L
    private var statIdle = 0L
    private var statReady = false

    // cpuidle 差分采样状态
    private var idleTimeUs = -1L
    private var idleWallMs = -1L

    fun read(context: Context?): StatusSnapshot {
        val freq = readCpuFreq()
        return StatusSnapshot(
            cpuPercent = readCpuPercent(),
            cpuFreqHz = freq?.first,
            memPercent = readMemPercent(),
            batteryTempC = context?.let { readBatteryTemp(it) },
            coreCount = freq?.second ?: 0,
        )
    }

    /** 优先 /proc/stat，不可读时退回 cpuidle 空闲时间。 */
    @Synchronized
    fun readCpuPercent(): Double? = readCpuFromProcStat() ?: readCpuFromCpuidle()

    private fun readCpuFromProcStat(): Double? {
        val line = try {
            File("/proc/stat").useLines { seq ->
                seq.firstOrNull { CPU_LINE.matches(it) }
            }
        } catch (t: Throwable) {
            return null
        } ?: return null

        val values = line.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return null

        val idle = values[3] + values[4]
        val total = values.sum()

        if (!statReady) {
            statTotal = total
            statIdle = idle
            statReady = true
            return null
        }

        val totalDelta = total - statTotal
        val idleDelta = idle - statIdle
        statTotal = total
        statIdle = idle

        if (totalDelta <= 0L) return null
        return ((totalDelta - idleDelta).toDouble() * 100.0 / totalDelta).coerceIn(0.0, 100.0)
    }

    private fun readCpuFromCpuidle(): Double? {
        val sum = sumCpuidleTimeUs() ?: return null
        val (idle, cores) = sum
        val nowMs = SystemClock.uptimeMillis()

        if (idleTimeUs < 0L || idleWallMs < 0L) {
            idleTimeUs = idle
            idleWallMs = nowMs
            return null
        }

        val idleDelta = idle - idleTimeUs
        val wallDeltaUs = (nowMs - idleWallMs) * 1000L
        idleTimeUs = idle
        idleWallMs = nowMs

        if (wallDeltaUs <= 0L || cores <= 0) return null
        val busy = (cores * wallDeltaUs - idleDelta).toDouble() * 100.0 / (cores * wallDeltaUs)
        return busy.coerceIn(0.0, 100.0)
    }

    /** 汇总所有在线核心的 cpuidle 空闲时间，返回 (总空闲微秒, 参与统计的核心数)。 */
    private fun sumCpuidleTimeUs(): Pair<Long, Int>? {
        val cores = try {
            File(CPU_ROOT).listFiles { f -> CORE_NAME.matches(f.name) }
        } catch (t: Throwable) {
            null
        } ?: return null

        var total = 0L
        var counted = 0
        for (core in cores) {
            var coreIdle = 0L
            var readable = false
            for (state in 0..4) {
                val file = File(core, "cpuidle/state$state/time")
                val value = try {
                    if (file.exists()) file.readText().trim().toLongOrNull() else null
                } catch (t: Throwable) {
                    null
                }
                if (value != null) {
                    coreIdle += value
                    readable = true
                }
            }
            if (readable) {
                total += coreIdle
                counted++
            }
        }
        if (counted == 0) return null
        return total to counted
    }

    /** 对在线核心的 scaling_cur_freq 取平均，返回 (平均频率Hz, 统计核心数)。 */
    fun readCpuFreq(): Pair<Double, Int>? {
        val cores = try {
            File(CPU_ROOT).listFiles { f -> CORE_NAME.matches(f.name) }
        } catch (t: Throwable) {
            null
        } ?: return null

        val freqs = cores.mapNotNull { core ->
            val value = try {
                val file = File(core, "cpufreq/scaling_cur_freq")
                if (file.exists()) file.readText().trim().toLongOrNull() else null
            } catch (t: Throwable) {
                null
            }
            // cpufreq 节点以 KHz 为单位，统一换算成 Hz。
            value?.takeIf { it > 0L }?.times(1000L)
        }
        if (freqs.isEmpty()) return null
        return freqs.average() to freqs.size
    }

    fun readMemPercent(): Double? {
        val entries = try {
            File("/proc/meminfo").useLines { seq ->
                seq.mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val key = line.substring(0, idx)
                    val value = line.substring(idx + 1).trim().split(WHITESPACE).firstOrNull()
                        ?.toLongOrNull() ?: return@mapNotNull null
                    key to value
                }.toMap()
            }
        } catch (t: Throwable) {
            return null
        }
        val total = entries["MemTotal"] ?: return null
        val available = entries["MemAvailable"] ?: return null
        if (total <= 0L) return null
        return ((total - available).toDouble() * 100.0 / total).coerceIn(0.0, 100.0)
    }

    fun readBatteryTemp(context: Context): Double? = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        if (raw == Int.MIN_VALUE) null else raw / 10.0
    } catch (t: Throwable) {
        null
    }
}
