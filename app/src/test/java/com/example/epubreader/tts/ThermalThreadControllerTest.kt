package com.example.epubreader.tts

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalThreadControllerTest {
    private class FakeClock(
        initialThermal: Int = PowerManager.THERMAL_STATUS_NONE,
    ) : Clock {
        var currentThermal: Int = initialThermal
        var elapsedRealtime: Long = 0L

        override fun elapsedRealtimeMillis(): Long = elapsedRealtime
        override fun currentThermalStatus(): Int = currentThermal
    }

    @Test
    fun initializesFromCurrentThermalStatus() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_MODERATE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        assertEquals(3, controller.engineThreads)
        assertEquals(3, controller.targetEngineThreads)
    }

    @Test
    fun noneAndLightUseFourThreads() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        assertEquals(4, controller.engineThreads)

        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_LIGHT)
        assertEquals(4, controller.targetEngineThreads)
        assertTrue(action is ThermalAction.NoChange)
    }

    @Test
    fun moderateUsesThreeThreads() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(3, controller.targetEngineThreads)
        assertTrue(action is ThermalAction.Downgrade)
        val downgrade = action as ThermalAction.Downgrade
        assertEquals(4, downgrade.from)
        assertEquals(3, downgrade.to)
    }

    @Test
    fun severeAndAboveUseTwoThreads() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(2, controller.targetEngineThreads)
        assertTrue(action is ThermalAction.Downgrade)
    }

    @Test
    fun thermalDowngrade4To3To2ImmediatelyDowngrades() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        var action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE)
        assertTrue(action is ThermalAction.Downgrade)
        controller.commitEngineThreads(3)

        action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(action is ThermalAction.Downgrade)
        assertEquals(2, controller.targetEngineThreads)
    }

    @Test
    fun improvementStartsRecoveryButDoesNotUpgradeImmediately() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_MODERATE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        controller.commitEngineThreads(2)

        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        assertTrue(action is ThermalAction.StartRecovery)
        assertEquals(2, controller.engineThreads)
    }

    @Test
    fun recoveryDelaysUpgradeBySixtySeconds() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_SEVERE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        clock.elapsedRealtime = 30_000L
        var action = controller.tickRecovery()
        assertTrue(action is ThermalAction.StillRecovering)
        assertEquals(2, controller.engineThreads)

        clock.elapsedRealtime = 59_999L
        action = controller.tickRecovery()
        assertTrue(action is ThermalAction.StillRecovering)
        assertEquals(2, controller.engineThreads)
    }

    @Test
    fun recoveryUpgradesAfterSixtySecondsWhenStable() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_SEVERE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        clock.currentThermal = PowerManager.THERMAL_STATUS_NONE
        clock.elapsedRealtime = 60_001L
        val action = controller.tickRecovery()
        assertTrue(action is ThermalAction.Upgrade)
        assertEquals(4, (action as ThermalAction.Upgrade).toThreads)
    }

    @Test
    fun thermalChangeDuringRecoveryCancelsAndReschedulesRecovery() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_SEVERE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        val firstAction = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        assertTrue(firstAction is ThermalAction.StartRecovery)
        val deadline1 = (firstAction as ThermalAction.StartRecovery).deadlineAt

        clock.elapsedRealtime = 20_000L
        val secondAction = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_LIGHT)
        assertTrue(secondAction is ThermalAction.StartRecovery)
        val deadline2 = (secondAction as ThermalAction.StartRecovery).deadlineAt

        assertTrue(deadline2 > deadline1)
    }

    @Test
    fun reheatDuringRecoveryDowngradesImmediately() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE)
        controller.commitEngineThreads(3)
        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        clock.elapsedRealtime = 30_000L

        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(action is ThermalAction.Downgrade)
        assertEquals(2, controller.targetEngineThreads)
    }

    @Test
    fun recoveryReReadsThermalStatusBeforeUpgrading() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_SEVERE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())

        controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        clock.elapsedRealtime = 60_001L
        clock.currentThermal = PowerManager.THERMAL_STATUS_MODERATE

        val action = controller.tickRecovery()
        assertTrue(action is ThermalAction.Upgrade)
        assertEquals(3, (action as ThermalAction.Upgrade).toThreads)
    }

    @Test
    fun noRecoveryWhenAlreadyAtTarget() {
        val clock = FakeClock(initialThermal = PowerManager.THERMAL_STATUS_NONE)
        val controller = ThermalThreadController(clock)
        controller.initialize(clock.currentThermalStatus())
        val action = controller.onThermalStatusChanged(PowerManager.THERMAL_STATUS_LIGHT)
        assertTrue(action is ThermalAction.NoChange)
    }
}
