package com.example.epubreader.tts

import android.content.Context
import android.os.Build

class TtsPerformanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val profileKey = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.VERSION.SDK_INT}:$MODEL_REVISION"

    fun cpuThreads(): Int {
        val savedProfile = preferences.getString(KEY_PROFILE, null)
        if (savedProfile == profileKey) return preferences.getInt(KEY_CPU_THREADS, defaultCpuThreads())
        return defaultCpuThreads()
    }

    fun snapshot(): TtsPerformanceSnapshot = TtsPerformanceSnapshot(
        requestedBackend = requestedBackend(),
        activeBackend = TtsBackend.CPU,
        cpuThreads = cpuThreads(),
        generationMillis = preferences.getLong(KEY_GENERATION_MS, 0),
        realTimeFactor = preferences.getFloat(KEY_RTF, 0f),
        prefetchHitRate = preferences.getFloat(KEY_PREFETCH_HIT_RATE, 0f),
        gapMillis = preferences.getLong(KEY_GAP_MS, 0),
        fallbackReason = qnnFallbackReason(),
    )

    fun requestedBackend(): TtsBackend = runCatching {
        TtsBackend.valueOf(preferences.getString(KEY_BACKEND, TtsBackend.AUTO.name).orEmpty())
    }.getOrDefault(TtsBackend.AUTO)

    fun setRequestedBackend(backend: TtsBackend) {
        preferences.edit().putString(KEY_BACKEND, backend.name).apply()
    }

    fun saveMetrics(
        generationMillis: Long,
        realTimeFactor: Float,
        prefetchHitRate: Float,
        gapMillis: Long,
    ) {
        preferences.edit()
            .putString(KEY_PROFILE, profileKey)
            .putInt(KEY_CPU_THREADS, cpuThreads())
            .putLong(KEY_GENERATION_MS, generationMillis)
            .putFloat(KEY_RTF, realTimeFactor)
            .putFloat(KEY_PREFETCH_HIT_RATE, prefetchHitRate)
            .putLong(KEY_GAP_MS, gapMillis)
            .apply()
    }

    private fun qnnFallbackReason(): String? = when (requestedBackend()) {
        TtsBackend.CPU -> null
        TtsBackend.AUTO, TtsBackend.QNN_HTP ->
            "当前安装包未包含 QNN HTP runtime，已使用 CPU 整图回退"
    }

    private fun defaultCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS)

    companion object {
        private const val PREFERENCES_NAME = "tts_performance"
        private const val MODEL_REVISION = "75a59ed26f999226f412eb9e1dff31c86b42f082"
        private const val KEY_PROFILE = "profile"
        private const val KEY_BACKEND = "backend"
        private const val KEY_CPU_THREADS = "cpu_threads"
        private const val KEY_GENERATION_MS = "generation_ms"
        private const val KEY_RTF = "rtf"
        private const val KEY_PREFETCH_HIT_RATE = "prefetch_hit_rate"
        private const val KEY_GAP_MS = "gap_ms"
        private const val MIN_CPU_THREADS = 2
        private const val MAX_CPU_THREADS = 4
    }
}
