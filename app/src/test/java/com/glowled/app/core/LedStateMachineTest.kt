package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedStateMachineTest {

    @Test
    fun returnsIdleWhenAppDisabled() {
        val signal = SignalSnapshot(
            enabled = false,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = false,
            isLocked = true,
            hasUnreadNotifs = true,
            overlayLockOnly = false,
        )
        val target = LedStateMachine.computeTarget(signal, 120, 0, true)
        assertEquals(TargetState.Idle, target)
    }

    @Test
    fun aodPriorityNotificationsOverCharging() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = false,
            isLocked = true,
            hasUnreadNotifs = true,
            overlayLockOnly = false,
            isPlugged = true,
            chargeLed = true,
            chargeAod = true,
            isChargeAodActive = true,
        )
        val target = LedStateMachine.computeTarget(signal, 120, 60, true)
        assertTrue(target is TargetState.Aod)
        val aod = target as TargetState.Aod
        assertEquals(AodKind.NOTIFICATION, aod.kind)
        assertEquals(120, aod.timeoutSec)
    }

    @Test
    fun aodChargingAloneUsesChargeTimeout() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = false,
            isLocked = true,
            hasUnreadNotifs = false,
            overlayLockOnly = false,
            isPlugged = true,
            chargeLed = true,
            chargeAod = true,
            isChargeAodActive = true,
        )
        val target = LedStateMachine.computeTarget(signal, 120, 45, true)
        assertTrue(target is TargetState.Aod)
        val aod = target as TargetState.Aod
        assertEquals(AodKind.CHARGING, aod.kind)
        assertEquals(45, aod.timeoutSec)
    }

    @Test
    fun aodBlockedWhenPowerGuardReasonPresent() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = false,
            isLocked = true,
            hasUnreadNotifs = true,
            overlayLockOnly = false,
            blockReason = "Quiet hours",
        )
        val target = LedStateMachine.computeTarget(signal, 120, 0, true)
        assertTrue(target is TargetState.Blocked)
        assertEquals("Quiet hours", (target as TargetState.Blocked).reason)
    }

    @Test
    fun screenOnOverlayShowsCpuAndBatteryWhenEnabled() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = true,
            isLocked = false,
            hasUnreadNotifs = false,
            overlayLockOnly = false,
            cpuIndicator = true,
            batteryIndicator = true,
        )
        val target = LedStateMachine.computeTarget(signal, 120, 0, true)
        assertTrue(target is TargetState.Overlay)
        val overlay = target as TargetState.Overlay
        assertEquals(false, overlay.showNotifRing)
        assertEquals(false, overlay.showMeter)
        assertEquals(true, overlay.showBattery)
        assertEquals(true, overlay.showCpu)
    }

    @Test
    fun screenOnOverlayLockOnlyHidesRingWhenUnlocked() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = true,
            isLocked = false, // unlocked!
            hasUnreadNotifs = true,
            overlayLockOnly = true, // lock only!
        )
        val target = LedStateMachine.computeTarget(signal, 120, 0, true)
        assertEquals(TargetState.Idle, target)
    }

    @Test
    fun screenOnOverlayLockOnlyShowsRingWhenLocked() {
        val signal = SignalSnapshot(
            enabled = true,
            aodEnabled = true,
            overlayEnabled = true,
            isInteractive = true,
            isLocked = true, // locked!
            hasUnreadNotifs = true,
            overlayLockOnly = true, // lock only!
        )
        val target = LedStateMachine.computeTarget(signal, 120, 0, true)
        assertTrue(target is TargetState.Overlay)
        val overlay = target as TargetState.Overlay
        assertEquals(true, overlay.showNotifRing)
    }
}
