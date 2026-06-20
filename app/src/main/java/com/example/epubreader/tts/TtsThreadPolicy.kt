package com.example.epubreader.tts

import android.os.PowerManager
import android.os.SystemClock

object TtsThreadPolicy {
    const val MIN_THREADS = 2
    const val MAX_THREADS = 4
    const val STABLE_DURATION_MS = 60_000L

    private var lastDowngradedAt = 0L

    fun threadsForThermal(thermalStatus: Int): Int = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> 4
        PowerManager.THERMAL_STATUS_MODERATE -> 3
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY -> 2
        else -> 4
    }

    fun shouldRecreate(currentThreads: Int, targetThreads: Int): Boolean {
        if (currentThreads == targetThreads) return false
        if (targetThreads < currentThreads) {
            lastDowngradedAt = SystemClock.elapsedRealtime()
            return true
        }
        val elapsed = SystemClock.elapsedRealtime() - lastDowngradedAt
        return elapsed >= STABLE_DURATION_MS
    }

    fun reason(currentThreads: Int, targetThreads: Int): String = when {
        targetThreads < currentThreads -> "thermal_downgrade ${currentThreads}t->${targetThreads}t"
        targetThreads > currentThreads -> "thermal_upgrade ${currentThreads}t->${targetThreads}t"
        else -> "no_change ${currentThreads}t"
    }

    /** Reset state for testing */
    fun reset() { lastDowngradedAt = 0L }
}
