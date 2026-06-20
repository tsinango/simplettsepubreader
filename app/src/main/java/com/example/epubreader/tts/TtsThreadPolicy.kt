package com.example.epubreader.tts

import android.os.PowerManager

interface Clock {
    fun elapsedRealtimeMillis(): Long
    fun currentThermalStatus(): Int
}

object DefaultClock : Clock {
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    override fun currentThermalStatus(): Int =
        // Resolved lazily by the service via injected PowerManager; this default
        // is only used when no explicit clock is provided.
        PowerManager.THERMAL_STATUS_NONE
}

object TtsThreadPolicy {
    const val MIN_THREADS = 2
    const val MAX_THREADS = 4
    const val STABLE_DURATION_MS = 60_000L

    fun threadsForThermal(thermalStatus: Int): Int = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> 4
        PowerManager.THERMAL_STATUS_MODERATE -> 3
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY -> 2
        else -> 4
    }

    fun reason(currentThreads: Int, targetThreads: Int): String = when {
        targetThreads < currentThreads -> "thermal_downgrade ${currentThreads}t->${targetThreads}t"
        targetThreads > currentThreads -> "thermal_upgrade ${currentThreads}t->${targetThreads}t"
        else -> "no_change ${currentThreads}t"
    }
}

class ThermalThreadController(private val clock: Clock) {
    @Volatile
    var engineThreads: Int = TtsThreadPolicy.threadsForThermal(clock.currentThermalStatus())
        private set

    @Volatile
    var targetEngineThreads: Int = engineThreads
        private set

    @Volatile
    private var lastDowngradedAt: Long = 0L
    @Volatile
    private var recoveryDeadline: Long = 0L

    fun initialize(thermalStatus: Int) {
        engineThreads = TtsThreadPolicy.threadsForThermal(thermalStatus)
        targetEngineThreads = engineThreads
        lastDowngradedAt = 0L
        recoveryDeadline = 0L
    }

    fun onThermalStatusChanged(status: Int): ThermalAction {
        targetEngineThreads = TtsThreadPolicy.threadsForThermal(status)
        if (targetEngineThreads < engineThreads) {
            lastDowngradedAt = clock.elapsedRealtimeMillis()
            recoveryDeadline = 0L
            return ThermalAction.Downgrade(engineThreads, targetEngineThreads)
        }
        if (targetEngineThreads > engineThreads) {
            recoveryDeadline = clock.elapsedRealtimeMillis() + TtsThreadPolicy.STABLE_DURATION_MS
            return ThermalAction.StartRecovery(recoveryDeadline)
        }
        recoveryDeadline = 0L
        return ThermalAction.NoChange
    }

    fun tickRecovery(): ThermalAction {
        if (recoveryDeadline == 0L) return ThermalAction.NoChange
        val now = clock.elapsedRealtimeMillis()
        if (now < recoveryDeadline) return ThermalAction.StillRecovering(recoveryDeadline - now)
        val currentStatus = clock.currentThermalStatus()
        val currentTarget = TtsThreadPolicy.threadsForThermal(currentStatus)
        if (currentTarget > engineThreads) {
            engineThreads = currentTarget
            targetEngineThreads = currentTarget
            recoveryDeadline = 0L
            return ThermalAction.Upgrade(engineThreads)
        }
        recoveryDeadline = 0L
        return ThermalAction.NoChange
    }

    fun commitEngineThreads(threads: Int) {
        engineThreads = threads
    }
}

sealed class ThermalAction {
    data object NoChange : ThermalAction()
    data class Downgrade(val from: Int, val to: Int) : ThermalAction()
    data class StartRecovery(val deadlineAt: Long) : ThermalAction()
    data class StillRecovering(val remainingMs: Long) : ThermalAction()
    data class Upgrade(val toThreads: Int) : ThermalAction()
}
