package com.glowled.app.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Reads overall device CPU usage percentage (0..100%).
 * Uses /proc/stat if accessible, falling back to top -n 1 or sysfs frequencies.
 * All sampling runs off the UI thread via [updateAsync].
 */
object CpuStats {
    private const val TAG = "CpuStats"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CpuStatsWorker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastTotal: Long = 0L
    private var lastIdle: Long = 0L
    @Volatile var cachedUsage: Int = -1
        private set

    /** Submits a background sample and returns the percentage to [callback] on the main thread. */
    fun updateAsync(callback: (Int) -> Unit) {
        executor.execute {
            val usage = sample()
            cachedUsage = usage
            mainHandler.post { callback(usage) }
        }
    }

    /** Samples total CPU usage percentage synchronously (call off main thread). */
    fun sample(): Int {
        val fromProc = readProcStat()
        if (fromProc in 0..100) return fromProc

        val fromTop = readTop()
        if (fromTop in 0..100) return fromTop

        val fromFreq = readFreqRatio()
        if (fromFreq in 0..100) return fromFreq

        return if (cachedUsage >= 0) cachedUsage else 0
    }

    /** Reads /proc/stat if available to the process. */
    private fun readProcStat(): Int = try {
        val file = File("/proc/stat")
        if (!file.canRead()) -1
        else {
            val line = BufferedReader(FileReader(file)).use { it.readLine() } ?: return -1
            parseProcStatLine(line)
        }
    } catch (_: Throwable) {
        -1
    }

    /** Parses the first line of /proc/stat: "cpu user nice sys idle iowait irq softirq steal..." */
    internal fun parseProcStatLine(line: String): Int {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || parts[0] != "cpu") return -1
        val user = parts[1].toLongOrNull() ?: return -1
        val nice = parts[2].toLongOrNull() ?: return -1
        val system = parts[3].toLongOrNull() ?: return -1
        val idle = parts[4].toLongOrNull() ?: return -1
        val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
        val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
        val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
        val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

        val totalIdle = idle + iowait
        val totalActive = user + nice + system + irq + softirq + steal
        val total = totalIdle + totalActive

        val diffTotal = total - lastTotal
        val diffIdle = totalIdle - lastIdle
        lastTotal = total
        lastIdle = totalIdle

        return if (diffTotal > 0) {
            (((diffTotal - diffIdle) * 100) / diffTotal).toInt().coerceIn(0, 100)
        } else {
            -1
        }
    }

    /** Runs top -n 1 and parses the global CPU load line. */
    private fun readTop(): Int = try {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "top -n 1"))
        val line = BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            var matched: String? = null
            var l: String?
            while (reader.readLine().also { l = it } != null) {
                val str = l!!
                if (str.contains("%cpu") || str.contains("%idle") || (str.contains("User") && str.contains("%"))) {
                    matched = str
                    break
                }
            }
            matched
        }
        proc.destroy()
        if (line != null) parseTopLine(line) else -1
    } catch (e: Throwable) {
        Log.d(TAG, "readTop failed: ${e.message}")
        -1
    }

    /**
     * Parses top output line.
     * Format 1 (modern toybox): "800%cpu 125%user 0%nice 68%sys 600%idle ..."
     * Format 2: "User 25%, System 10%, IOW 0%, IRQ 0%"
     */
    internal fun parseTopLine(line: String): Int {
        // Modern toybox: "800%cpu ... 600%idle"
        val match = Regex("""(\d+)%cpu.*?(\d+)%idle""").find(line)
        if (match != null) {
            val total = match.groupValues[1].toIntOrNull() ?: 0
            val idle = match.groupValues[2].toIntOrNull() ?: 0
            if (total > 0) {
                return (((total - idle) * 100) / total).coerceIn(0, 100)
            }
        }
        // Legacy: "User 25%, System 10%..."
        val user = Regex("""User\s+(\d+)%""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sys = Regex("""System\s+(\d+)%""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val irq = Regex("""IRQ\s+(\d+)%""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sum = user + sys + irq
        if (sum > 0) return sum.coerceIn(0, 100)
        return -1
    }

    /** Fallback: estimates load from current scaling frequency vs maximum frequency across cores. */
    private fun readFreqRatio(): Int = try {
        var count = 0
        var sumRatio = 0f
        for (i in 0 until 16) {
            val curFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val maxFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (!curFile.exists() || !maxFile.exists()) {
                if (i > 0 && !File("/sys/devices/system/cpu/cpu$i").exists()) break
                continue
            }
            val cur = curFile.readText().trim().toFloatOrNull() ?: continue
            val max = maxFile.readText().trim().toFloatOrNull() ?: continue
            if (max > 0f) {
                sumRatio += (cur / max).coerceIn(0f, 1f)
                count++
            }
        }
        if (count > 0) ((sumRatio / count) * 100).toInt().coerceIn(0, 100) else -1
    } catch (_: Throwable) {
        -1
    }
}
