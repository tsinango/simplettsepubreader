package com.example.epubreader.tts

import android.content.Context

/**
 * Per-model persistence of the user-selected speaker id and (where the
 * engine supports it per model) user rate. Stored in a single
 * SharedPreferences file keyed by `${modelId.stableValue}:sid` and
 * `${modelId.stableValue}:rate` so values never cross models; switching
 * engine kind cannot leak another model's speaker or rate into the active one.
 *
 * Defaults: WNJ/MeloTTS are single-speaker, so their default sid is 0 and the
 * rate column is intentionally unused. Kokoro defaults to the first Chinese
 * female sid (see [KokoroModelRegistry.DEFAULT_CHINESE_FEMALE_SID]) and uses
 * its own rate as the speed slider is independent of the system TTS rate.
 */
class EmbeddedModelSelectionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun speakerId(modelStableId: String, default: Int): Int =
        prefs.getInt(sidKey(modelStableId), default)

    fun setSpeakerId(modelStableId: String, sid: Int) {
        prefs.edit().putInt(sidKey(modelStableId), sid).apply()
    }

    fun rate(modelStableId: String, default: Float): Float =
        prefs.getFloat(rateKey(modelStableId), default)

    fun setRate(modelStableId: String, rate: Float) {
        prefs.edit().putFloat(rateKey(modelStableId), rate).apply()
    }

    /** Clears all selections; primarily used by tests. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun sidKey(modelStableId: String) = "$modelStableId:sid"
    private fun rateKey(modelStableId: String) = "$modelStableId:rate"

    companion object {
        private const val PREFERENCES_NAME = "embedded_model_selection"
    }
}