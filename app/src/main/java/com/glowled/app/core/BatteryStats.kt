package com.glowled.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

data class BatteryInfo(
    val level: Int,
    val voltageMv: Int,
    val currentMa: Int,
    val powerWatts: Float,
    val isCharging: Boolean,
) {
    fun formatString(
        showPercent: Boolean,
        showVoltage: Boolean,
        showCurrent: Boolean,
        showPower: Boolean,
    ): String? {
        val row1 = mutableListOf<String>()
        val row2 = mutableListOf<String>()

        if (showPercent && level in 0..100) {
            row1.add("$level%")
        }
        if (showVoltage && voltageMv > 0) {
            val vVolts = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()
            row1.add(String.format(Locale.US, "%.2fV", vVolts))
        }
        if (showCurrent && abs(currentMa) > 0) {
            row2.add(String.format(Locale.US, "%d mA", abs(currentMa)))
        }
        if (showPower && powerWatts > 0.05f) {
            row2.add(String.format(Locale.US, "%.1fW", powerWatts))
        }

        return when {
            row1.isNotEmpty() && row2.isNotEmpty() -> "${row1.joinToString(" · ")}\n${row2.joinToString(" · ")}"
            row1.isNotEmpty() -> row1.joinToString(" · ")
            row2.isNotEmpty() -> row2.joinToString(" · ")
            else -> null
        }
    }
}

object BatteryStats {
    fun getInfo(context: Context): BatteryInfo {
        val bm = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100)
                    else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100).coerceIn(0, 100)

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: 0
        val isCharging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || bm?.isCharging == true

        var rawCurrent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        if (rawCurrent == Int.MIN_VALUE || rawCurrent == Int.MAX_VALUE) rawCurrent = 0
        var currentMa = when {
            abs(rawCurrent) > 10_000 -> rawCurrent / 1000
            else -> rawCurrent
        }

        if (currentMa == 0 && isCharging) {
            val avg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
            if (avg != Int.MIN_VALUE && avg != Int.MAX_VALUE && avg != 0) {
                currentMa = if (abs(avg) > 10_000) avg / 1000 else avg
            }
        }

        val effectiveCurrentMa = if (isCharging) abs(currentMa) else -abs(currentMa)

        val vVolts = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()
        val cAmps = abs(effectiveCurrentMa) / 1000f
        val powerWatts = if (vVolts > 0f && cAmps > 0f) vVolts * cAmps else 0f

        return BatteryInfo(
            level = level,
            voltageMv = voltageMv,
            currentMa = effectiveCurrentMa,
            powerWatts = powerWatts,
            isCharging = isCharging,
        )
    }

    data class AodTelemetry(
        val sessionCount: Int,
        val animDurationMs: Long,
        val staticDurationMs: Long,
    )

    private const val KEY_AOD_SESSIONS_COUNT = "aod_sessions_count"
    private const val KEY_AOD_ANIM_MS = "aod_anim_ms"
    private const val KEY_AOD_STATIC_MS = "aod_static_ms"
    private const val KEY_AOD_RESET_TIME = "aod_stats_reset"

    /** Records duration of one completed AOD session (zero I/O on draw frames). */
    fun recordAodSession(context: Context, animDurationMs: Long, staticDurationMs: Long) {
        try {
            val sp = context.applicationContext.getSharedPreferences("device_state", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastReset = sp.getLong(KEY_AOD_RESET_TIME, 0L)
            val e = sp.edit()
            if (lastReset == 0L || now - lastReset > 24 * 3600 * 1000L) {
                e.putLong(KEY_AOD_RESET_TIME, now)
                e.putInt(KEY_AOD_SESSIONS_COUNT, 1)
                e.putLong(KEY_AOD_ANIM_MS, animDurationMs.coerceAtLeast(0L))
                e.putLong(KEY_AOD_STATIC_MS, staticDurationMs.coerceAtLeast(0L))
            } else {
                val count = sp.getInt(KEY_AOD_SESSIONS_COUNT, 0) + 1
                val anim = sp.getLong(KEY_AOD_ANIM_MS, 0L) + animDurationMs.coerceAtLeast(0L)
                val st = sp.getLong(KEY_AOD_STATIC_MS, 0L) + staticDurationMs.coerceAtLeast(0L)
                e.putInt(KEY_AOD_SESSIONS_COUNT, count)
                e.putLong(KEY_AOD_ANIM_MS, anim)
                e.putLong(KEY_AOD_STATIC_MS, st)
            }
            e.apply()
        } catch (_: Exception) {}
    }

    fun getAodTelemetry(context: Context): AodTelemetry {
        return try {
            val sp = context.applicationContext.getSharedPreferences("device_state", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastReset = sp.getLong(KEY_AOD_RESET_TIME, 0L)
            if (lastReset != 0L && now - lastReset > 24 * 3600 * 1000L) {
                AodTelemetry(0, 0L, 0L)
            } else {
                AodTelemetry(
                    sessionCount = sp.getInt(KEY_AOD_SESSIONS_COUNT, 0),
                    animDurationMs = sp.getLong(KEY_AOD_ANIM_MS, 0L),
                    staticDurationMs = sp.getLong(KEY_AOD_STATIC_MS, 0L),
                )
            }
        } catch (_: Exception) {
            AodTelemetry(0, 0L, 0L)
        }
    }
}

