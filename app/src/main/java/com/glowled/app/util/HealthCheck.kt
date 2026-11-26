package com.glowled.app.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.glowled.app.core.BatteryStats
import com.glowled.app.core.DebugLog
import com.glowled.app.core.LedController
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService

data class HealthItem(
    val title: String,
    val isOk: Boolean,
    val detail: String,
    val fixAction: ((Context) -> Unit)? = null,
)

object HealthCheck {

    fun runAll(context: Context): List<HealthItem> {
        val list = mutableListOf<HealthItem>()

        // 1. Notification Listener
        val notifAccess = NotifyListenerService.isEnabled(context)
        val notifConnected = NotifyListenerService.instance != null
        list.add(HealthItem(
            title = "Notification Access",
            isOk = notifAccess && notifConnected,
            detail = when {
                !notifAccess -> "Permission not granted"
                !notifConnected -> "Granted but disconnected by system"
                else -> "Active and connected"
            },
            fixAction = { c ->
                try {
                    c.startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        ))

        // 2. Display over other apps (SYSTEM_ALERT_WINDOW)
        val canDraw = Settings.canDrawOverlays(context)
        list.add(HealthItem(
            title = "Display over other apps",
            isOk = canDraw,
            detail = if (canDraw) "Granted" else "Required for screen-on indicator and overlay",
            fixAction = { c ->
                try {
                    c.startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${c.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        ))

        // 3. Battery optimization
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        list.add(HealthItem(
            title = "Battery optimization",
            isOk = ignoringBattery,
            detail = if (ignoringBattery) "Unrestricted" else "System may kill background services",
            fixAction = { c ->
                try {
                    c.startActivity(android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        ))

        // 4. Accessibility service
        val a11y = LedAccessibilityService.instance != null
        list.add(HealthItem(
            title = "Accessibility service",
            isOk = a11y,
            detail = if (a11y) "Active (prevents screen flashes on lock screen)" else "Optional but recommended for Xiaomi/OLED",
            fixAction = { c ->
                try {
                    c.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        ))

        // 5. Post notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotifs = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            list.add(HealthItem(
                title = "Post notifications",
                isOk = postNotifs,
                detail = if (postNotifs) "Granted" else "Needed for update alerts",
                fixAction = null
            ))
        }

        // 6. Full screen intents (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val canFsi = nm?.canUseFullScreenIntent() == true
            list.add(HealthItem(
                title = "Full-screen alerts",
                isOk = canFsi,
                detail = if (canFsi) "Allowed" else "Needed to wake screen for lock-screen AOD window",
                fixAction = { c ->
                    try {
                        c.startActivity(android.content.Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${c.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            ))
        }

        // 7. Lock-screen window blocked state
        val blocked = LedController.prefs.deviceSp.getBoolean(LedController.PREF_WINDOW_BLOCKED, false)
        list.add(HealthItem(
            title = "Lock-screen window status",
            isOk = !blocked,
            detail = if (blocked) "Blocked by system (using Accessibility overlay fallback)" else "Operational",
            fixAction = { _ ->
                LedController.prefs.deviceSp.edit().remove(LedController.PREF_WINDOW_BLOCKED).apply()
            }
        ))

        // 8. CPU Monitor source
        if (LedController.prefs.cpuIndicator) {
            val src = com.glowled.app.core.CpuStats.activeSource
            val usage = com.glowled.app.core.CpuStats.cachedUsage
            list.add(HealthItem(
                title = "CPU Monitor",
                isOk = src != "unavailable",
                detail = if (src == "unavailable") "Blocked by system SELinux" else "Source: $src (${if (usage >= 0) "$usage%" else "sampling…"})",
                fixAction = null,
            ))
        }

        return list
    }

    /**
     * Generates a clean diagnostic text report excluding any personal data or notification content.
     */
    fun generateReport(context: Context): String {
        val b = StringBuilder()
        b.appendLine("=== Glow LED Diagnostics ===")
        b.appendLine("App Version: ${com.glowled.app.BuildConfig.VERSION_NAME} (${com.glowled.app.BuildConfig.VERSION_CODE})")
        b.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        b.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        b.appendLine("Fingerprint: ${Build.FINGERPRINT}")
        b.appendLine("OEM Xiaomi/HyperOS: ${OemHelper.isXiaomi}")
        b.appendLine()
        b.appendLine("--- Health Checks ---")
        runAll(context).forEach {
            b.appendLine("[${if (it.isOk) "PASS" else "WARN"}] ${it.title}: ${it.detail}")
        }
        b.appendLine()
        b.appendLine("--- Battery Info ---")
        try {
            val bi = BatteryStats.getInfo(context)
            b.appendLine("Level: ${bi.level}%, Voltage: ${bi.voltageMv} mV, Current: ${bi.currentMa} mA, Charging: ${bi.isCharging}")
        } catch (e: Exception) {
            b.appendLine("BatteryStats error: ${e.message}")
        }
        b.appendLine()
        b.appendLine("--- AOD Telemetry (Last 24h) ---")
        try {
            val aod = BatteryStats.getAodTelemetry(context)
            b.appendLine("AOD Wakeups: ${aod.sessionCount}, Animated: ${aod.animDurationMs / 1000}s, Static: ${aod.staticDurationMs / 1000}s")
        } catch (e: Exception) {
            b.appendLine("AodTelemetry error: ${e.message}")
        }
        b.appendLine()
        b.appendLine("--- Event Log (Sanitized) ---")
        b.appendLine(DebugLog.text())
        return b.toString()
    }
}
