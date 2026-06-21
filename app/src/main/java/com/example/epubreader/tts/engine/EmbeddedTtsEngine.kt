package com.example.epubreader.tts.engine

import android.content.Context

/**
 * Result of a single offline TTS synthesis call. The [samples] are normalised
 * to the range [-1, 1] so the playback pipeline can either forward them to an
 * `AudioTrack` configured for PCM_FLOAT or quantise them to PCM_16BIT. The
 * [sampleRate] is the rate reported by the underlying engine and must be used
 * verbatim when constructing the playback sink — engines are free to emit
 * different rates per model (WNJ 22050 Hz, MeloTTS 44100 Hz, Kokoro 24000 Hz,
 * Bert-VITS2 22050/44100 Hz).
 */
data class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SynthesizedAudio && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * sampleRate + samples.contentHashCode()
}

/**
 * Minimal pluggable interface for offline TTS engines that run entirely on the
 * device.
 *
 * The contract is intentionally narrow:
 *
 * - [isAvailable] is a fast, non-blocking presence check used by the playback
 *   service before launching generation. It must NOT load any native library
 *   and must NOT touch the inference engine. A typical implementation inspects
 *   the ready marker, pinned revision and expected file sizes — exactly like
 *   the existing [com.example.epubreader.tts.VitsModelManager] fast readiness
 *   check — so it can be called while the user is just looking at the settings
 *   UI.
 *
 * - [initialize] loads the native engine for the model that the descriptor
 *   associated with this instance points at. It is synchronous and blocking
 *   because callers always dispatch it on a background dispatcher (the TTS
 *   service runs it under `Dispatchers.Default` while holding the synthesis
 *   mutex). Implementations must be idempotent against double-initialisation
 *   within the same engine instance and must release any previously allocated
 *   native handle on a failure before rethrowing.
 *
 * - [synthesize] performs one inference call. [sid] selects a speaker for
 *   multi-speaker engines (single-speaker engines ignore it). [speed] is the
 *   engine-native speed multiplier; the caller is responsible for mapping the
 *   UI speech rate to the engine's per-model range.
 *
 * - [release] frees the native handle. It must be safe to call from a
 *   background scope and safe to call more than once.
 *
 * The service guarantees that at most one engine is initialised at any time.
 * Switching models calls [release] on the previous engine before initialising
 * the next, so engines are not required to be re-entrant or coexist.
 */
interface EmbeddedTtsEngine {
    /** Stable identifier persisted in settings; unique across engine kinds. */
    val id: String

    /** Human-readable name shown in the model selection UI. */
    val displayName: String

    /** Fast on-disk presence check; safe to call on the main thread. */
    fun isAvailable(context: Context): Boolean

    /**
     * Synchronously loads the native engine. Must run on a background
     * dispatcher. Throws on failure; the engine remains in the not-built state
     * afterwards so the caller can release and fall back.
     */
    fun initialize(context: Context, numThreads: Int)

    /**
     * Generates audio for [text]. Returns samples in [-1, 1] together with
     * the model's native sample rate. Throws on inference failure.
     */
    fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio

    /** Releases native resources. Safe to call more than once. */
    fun release()
}