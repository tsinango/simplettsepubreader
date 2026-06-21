package com.example.epubreader.tts

import android.content.Context
import android.os.Build

/**
 * Persists per-device CPU thread counts and per-model TTS performance metrics.
 *
 * Metrics are keyed by the active model's revision so that WNJ and MeloTTS
 * real-time factors are never mixed. CPU thread counts are device-level and
 * shared across models.
 */
class TtsPerformanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun cpuThreads(): Int {
        val savedProfile = preferences.getString(KEY_PROFILE, null)
        if (savedProfile == deviceProfile) return preferences.getInt(KEY_CPU_THREADS, defaultCpuThreads())
        return defaultCpuThreads()
    }

    fun snapshot(modelRevision: String, modelId: String): TtsPerformanceSnapshot = TtsPerformanceSnapshot(
        modelId = modelId,
        cpuThreads = cpuThreads(),
        engineInitMillis = preferences.getLong(metricKey(KEY_ENGINE_INIT_MS, modelRevision), 0),
        firstAudioMillis = preferences.getLong(metricKey(KEY_FIRST_AUDIO_MS, modelRevision), 0),
        generationMillis = preferences.getLong(metricKey(KEY_GENERATION_MS, modelRevision), 0),
        realTimeFactor = preferences.getFloat(metricKey(KEY_RTF, modelRevision), 0f),
        prefetchHitRate = preferences.getFloat(metricKey(KEY_PREFETCH_HIT_RATE, modelRevision), 0f),
        gapMillis = preferences.getLong(metricKey(KEY_GAP_MS, modelRevision), 0),
    )

    fun saveMetrics(
        modelRevision: String,
        engineInitMillis: Long,
        firstAudioMillis: Long,
        generationMillis: Long,
        realTimeFactor: Float,
        prefetchHitRate: Float,
        gapMillis: Long,
    ) {
        preferences.edit()
            .putString(KEY_PROFILE, deviceProfile)
            .putInt(KEY_CPU_THREADS, cpuThreads())
            .putLong(metricKey(KEY_ENGINE_INIT_MS, modelRevision), engineInitMillis)
            .putLong(metricKey(KEY_FIRST_AUDIO_MS, modelRevision), firstAudioMillis)
            .putLong(metricKey(KEY_GENERATION_MS, modelRevision), generationMillis)
            .putFloat(metricKey(KEY_RTF, modelRevision), realTimeFactor)
            .putFloat(metricKey(KEY_PREFETCH_HIT_RATE, modelRevision), prefetchHitRate)
            .putLong(metricKey(KEY_GAP_MS, modelRevision), gapMillis)
            .apply()
    }

    private fun metricKey(base: String, revision: String) = "$base:$revision"

    private val deviceProfile: String
        get() = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.VERSION.SDK_INT}"

    private fun defaultCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS)

    companion object {
        private const val PREFERENCES_NAME = "tts_performance"
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
