package com.glowled.app.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads overall device CPU usage percentage (0..100%).
 * Uses /proc/stat if accessible, falling back to top -b -n 1 -m 1 or sysfs frequencies
 * (both cpu* and cpufreq/policy*). All sampling runs off the UI thread via [updateAsync].
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

    @Volatile var activeSource: String = "pending"
        private set

    /** Submits a background sample and returns the percentage (or -1 if unavailable) to [callback] on main thread. */
    fun updateAsync(callback: (Int) -> Unit) {
        executor.execute {
            val usage = sample()
            if (usage >= 0) cachedUsage = usage
            val result = if (usage >= 0) usage else cachedUsage
            mainHandler.post { callback(result) }
        }
    }

    /** Samples total CPU usage percentage synchronously (call off main thread). */
    fun sample(): Int {
        val fromProc = readProcStat()
        if (fromProc in 0..100) {
            activeSource = "proc_stat"
            return fromProc
        }

        val fromTop = readTop()
        if (fromTop in 0..100) {
            activeSource = "top"
            return fromTop
        }

        val fromPolicy = readPolicyFreqRatio()
        if (fromPolicy in 0..100) {
            activeSource = "cpufreq_policy"
            return fromPolicy
        }

        val fromFreq = readFreqRatio()
        if (fromFreq in 0..100) {
            activeSource = "cpufreq_cpu"
            return fromFreq
        }

        activeSource = "unavailable"
        return -1
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

    /**
     * Runs top in batch mode (-b) with 1 iteration (-n 1) and max 1 process (-m 1).
     * Does not require an interactive TTY, finishes in ~10-20ms, and outputs clean stats.
     */
    private fun readTop(): Int {
        var proc: Process? = null
        return try {
            val cmd = if (File("/system/bin/top").exists()) {
                arrayOf("/system/bin/top", "-b", "-n", "1", "-m", "1")
            } else {
                arrayOf("top", "-b", "-n", "1", "-m", "1")
            }
            proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            var matchedLine: String? = null
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var l: String?
                var linesRead = 0
                while (reader.readLine().also { l = it } != null && linesRead < 15) {
                    linesRead++
                    val str = l ?: continue
                    if (str.contains("%cpu", ignoreCase = true) || str.contains("%idle", ignoreCase = true) ||
                        (str.contains("User", ignoreCase = true) && str.contains("%"))
                    ) {
                        matchedLine = str
                        break
                    }
                }
            }
            proc.waitFor(800, TimeUnit.MILLISECONDS)
            if (matchedLine != null) parseTopLine(matchedLine!!) else -1
        } catch (e: Throwable) {
            Log.d(TAG, "readTop failed: ${e.message}")
            -1
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {}
        }
    }

    private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")

    /**
     * Parses top output line.
     * Format 1 (modern toybox): "800%cpu 125%user 0%nice 68%sys 600%idle ..."
     * Format 2: "User 25%, System 10%, IOW 0%, IRQ 0%"
     */
    internal fun parseTopLine(rawLine: String): Int {
        val line = rawLine.replace(ANSI_REGEX, "")
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
        val user = Regex("""User\s+(\d+)%""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sys = Regex("""System\s+(\d+)%""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val irq = Regex("""IRQ\s+(\d+)%""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sum = user + sys + irq
        if (sum > 0) return sum.coerceIn(0, 100)
        return -1
    }

    /** Computes normalized load ratio from (cur - min) / (max - min). */
    internal fun freqRatio(cur: Float, min: Float, max: Float): Float {
        if (max <= 0f) return -1f
        val effectiveMin = if (min >= 0f && min < max) min else 0f
        val span = max - effectiveMin
        return if (span > 0f) ((cur - effectiveMin) / span).coerceIn(0f, 1f) else (cur / max).coerceIn(0f, 1f)
    }

    private fun readDirFreq(dir: File): Float {
        val curFile = File(dir, "scaling_cur_freq")
        if (!curFile.canRead()) return -1f
        val maxFile = File(dir, "cpuinfo_max_freq").takeIf { it.canRead() }
            ?: File(dir, "scaling_max_freq").takeIf { it.canRead() }
            ?: return -1f
        val minFile = File(dir, "cpuinfo_min_freq").takeIf { it.canRead() }
            ?: File(dir, "scaling_min_freq").takeIf { it.canRead() }
        val cur = curFile.readText().trim().toFloatOrNull() ?: return -1f
        val max = maxFile.readText().trim().toFloatOrNull() ?: return -1f
        val min = minFile?.readText()?.trim()?.toFloatOrNull() ?: 0f
        return freqRatio(cur, min, max)
    }

    /** Reads /sys/devices/system/cpu/cpufreq/policy* directories (standard on modern GKI/Snapdragon/Dimensity). */
    private fun readPolicyFreqRatio(): Int = try {
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val dirs = policyRoot.listFiles { f -> f.isDirectory && f.name.startsWith("policy") } ?: return -1
        var count = 0
        var sumRatio = 0f
        for (dir in dirs) {
            val r = readDirFreq(dir)
            if (r >= 0f) {
                sumRatio += r
                count++
            }
        }
        if (count > 0) (sumRatio / count * 100f).toInt().coerceIn(0, 100) else -1
    } catch (_: Throwable) {
        -1
    }

    /** Fallback: estimates load from current scaling frequency vs min/max across cpu0..cpu15. */
    private fun readFreqRatio(): Int = try {
        var count = 0
        var sumRatio = 0f
        for (i in 0 until 16) {
            val cpuDir = File("/sys/devices/system/cpu/cpu$i")
            if (i > 0 && !cpuDir.exists()) break
            val r = readDirFreq(File(cpuDir, "cpufreq"))
            if (r >= 0f) {
                sumRatio += r
                count++
            }
        }
        if (count > 0) (sumRatio / count * 100f).toInt().coerceIn(0, 100) else -1
    } catch (_: Throwable) {
        -1
    }
}
