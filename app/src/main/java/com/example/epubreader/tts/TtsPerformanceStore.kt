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
        cpuThreads = cpuThreads(),
        engineInitMillis = preferences.getLong(KEY_ENGINE_INIT_MS, 0),
        firstAudioMillis = preferences.getLong(KEY_FIRST_AUDIO_MS, 0),
        generationMillis = preferences.getLong(KEY_GENERATION_MS, 0),
        realTimeFactor = preferences.getFloat(KEY_RTF, 0f),
        prefetchHitRate = preferences.getFloat(KEY_PREFETCH_HIT_RATE, 0f),
        gapMillis = preferences.getLong(KEY_GAP_MS, 0),
    )

    fun saveMetrics(
        engineInitMillis: Long,
        firstAudioMillis: Long,
        generationMillis: Long,
        realTimeFactor: Float,
        prefetchHitRate: Float,
        gapMillis: Long,
    ) {
        preferences.edit()
            .putString(KEY_PROFILE, profileKey)
            .putInt(KEY_CPU_THREADS, cpuThreads())
            .putLong(KEY_ENGINE_INIT_MS, engineInitMillis)
            .putLong(KEY_FIRST_AUDIO_MS, firstAudioMillis)
            .putLong(KEY_GENERATION_MS, generationMillis)
            .putFloat(KEY_RTF, realTimeFactor)
            .putFloat(KEY_PREFETCH_HIT_RATE, prefetchHitRate)
            .putLong(KEY_GAP_MS, gapMillis)
            .apply()
    }

    private fun defaultCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS)

    companion object {
        private const val PREFERENCES_NAME = "tts_performance"
        private const val MODEL_REVISION = "75a59ed26f999226f412eb9e1dff31c86b42f082"
        private const val KEY_PROFILE = "profile"
        private const val KEY_CPU_THREADS = "cpu_threads"
        private const val KEY_ENGINE_INIT_MS = "engine_init_ms"
        private const val KEY_FIRST_AUDIO_MS = "first_audio_ms"
        private const val KEY_GENERATION_MS = "generation_ms"
        private const val KEY_RTF = "rtf"
        private const val KEY_PREFETCH_HIT_RATE = "prefetch_hit_rate"
        private const val KEY_GAP_MS = "gap_ms"
        private const val MIN_CPU_THREADS = TtsThreadPolicy.MIN_THREADS
        private const val MAX_CPU_THREADS = TtsThreadPolicy.MAX_THREADS
    }
}
