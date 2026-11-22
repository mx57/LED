package com.glowled.app.core

data class SignalSnapshot(
    val enabled: Boolean,
    val aodEnabled: Boolean,
    val overlayEnabled: Boolean,
    val isInteractive: Boolean,
    val isLocked: Boolean,
    val hasUnreadNotifs: Boolean,
    val overlayLockOnly: Boolean,
    val transferFraction: Float? = null, // null if no transfer, < 0 if indeterminate, >= 0 if progress
    val isPlugged: Boolean = false,
    val chargeLed: Boolean = false,
    val chargeAod: Boolean = false,
    val chargeScreenOn: Boolean = false,
    val isChargeAodActive: Boolean = false,
    val isChargeScreenOnActive: Boolean = false,
    val isChargeFlashActive: Boolean = false,
    val batteryIndicator: Boolean = false,
    val cpuIndicator: Boolean = false,
    val blockReason: String? = null,
)

sealed class TargetState {
    object Idle : TargetState()
    data class Blocked(val reason: String) : TargetState()
    data class Aod(
        val kind: AodKind,
        val timeoutSec: Int,
    ) : TargetState()
    data class Overlay(
        val showNotifRing: Boolean,
        val showMeter: Boolean,
        val showBattery: Boolean,
        val showCpu: Boolean,
    ) : TargetState()
}

enum class AodKind {
    NOTIFICATION,
    TRANSFER,
    CHARGING,
}

object LedStateMachine {

    fun hasAodContent(
        hasUnreadNotifs: Boolean,
        hasTransferProgress: Boolean,
        chargeLed: Boolean,
        isPlugged: Boolean,
        chargeAod: Boolean,
        isChargeAodActive: Boolean,
        isChargeFlashActive: Boolean,
    ): Boolean {
        if (hasUnreadNotifs) return true
        if (hasTransferProgress) return true
        if (chargeLed && isPlugged && chargeAod && isChargeAodActive) return true
        if (chargeLed && isPlugged && isChargeFlashActive) return true
        return false
    }

    fun computeTarget(
        signal: SignalSnapshot,
        defaultAodTimeoutSec: Int,
        chargeTimeoutAodSec: Int,
        progressLed: Boolean,
    ): TargetState {
        if (!signal.enabled) return TargetState.Idle

        if (signal.isInteractive) {
            // Screen ON: Overlay mode
            val notifRing = signal.overlayEnabled && signal.hasUnreadNotifs && (!signal.overlayLockOnly || signal.isLocked)
            val hasTransfer = progressLed && signal.transferFraction != null
            val chargingActive = (signal.chargeLed && signal.isPlugged && signal.chargeScreenOn && signal.isChargeScreenOnActive) ||
                (signal.chargeLed && signal.isPlugged && signal.isChargeFlashActive)
            val showMeter = hasTransfer || (chargingActive && (!notifRing || signal.isChargeFlashActive))
            val show = notifRing || showMeter || signal.batteryIndicator || signal.cpuIndicator
            return if (show) {
                TargetState.Overlay(
                    showNotifRing = notifRing,
                    showMeter = showMeter,
                    showBattery = signal.batteryIndicator,
                    showCpu = signal.cpuIndicator,
                )
            } else {
                TargetState.Idle
            }
        } else {
            // Screen OFF: AOD mode
            if (!signal.aodEnabled) return TargetState.Idle
            if (signal.blockReason != null) return TargetState.Blocked(signal.blockReason)

            val hasTransfer = progressLed && signal.transferFraction != null && signal.transferFraction >= 0f
            val hasAod = hasAodContent(
                hasUnreadNotifs = signal.hasUnreadNotifs,
                hasTransferProgress = hasTransfer,
                chargeLed = signal.chargeLed,
                isPlugged = signal.isPlugged,
                chargeAod = signal.chargeAod,
                isChargeAodActive = signal.isChargeAodActive,
                isChargeFlashActive = signal.isChargeFlashActive,
            )
            if (!hasAod) return TargetState.Idle

            val kind = when {
                signal.hasUnreadNotifs -> AodKind.NOTIFICATION
                hasTransfer -> AodKind.TRANSFER
                else -> AodKind.CHARGING
            }
            val timeout = when (kind) {
                AodKind.CHARGING -> chargeTimeoutAodSec
                else -> defaultAodTimeoutSec
            }
            return TargetState.Aod(kind, timeout)
        }
    }
}
