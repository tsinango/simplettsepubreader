package com.example.epubreader.tts

/**
 * Enumerates which concrete offline TTS engine implementation drives a given
 * [TtsModelPackDescriptor]. The TTS service uses this to pick the matching
 * [com.example.epubreader.tts.engine.EmbeddedTtsEngine] adapter.
 */
enum class TtsEngineKind {
    /** sherpa-onnx OfflineTtsVitsModelConfig — single/double-speaker Chinese VITS family. */
    SHERPA_VITS,

    /** sherpa-onnx OfflineTtsKokoroModelConfig — Kokoro-82M 103-speaker multi-lang model. */
    SHERPA_KOKORO,

    /** Bert-VITS2 + MNN JNI wrapper — Bert-VITS2-MNN packs. */
    BERT_VITS2_MNN,
}

/**
 * Gender tag attached to a [SpeakerEntry]. Used by the UI to provide a default
 * Chinese female / Chinese male "试听" target and to organise the speaker list.
 */
enum class SpeakerGender { MALE, FEMALE }

/**
 * One selectable voice in a multi-speaker engine. `id` is the integer passed
 * to `OfflineTts.generate(sid = id)` (or, for Bert-VITS2-MNN, the pack-local
 * speaker id). `name` is the upstream label when available, otherwise a
 * stable synthetic label that the UI renders verbatim.
 */
data class SpeakerEntry(
    val id: Int,
    val name: String,
    val language: String,
    val gender: SpeakerGender,
)

/**
 * Unified, engine-agnostic description of one downloadable offline TTS pack.
 *
 * The existing [VitsModelDescriptor] implements this interface verbatim so the
 * historical WNJ and MeloTTS descriptors keep their directory layout, ready
 * markers and migration paths while the new Kokoro and Bert-VITS2 model kinds
 * get their own parallel implementations. All concrete descriptor types honour
 * the same on-disk layout (one directory per pack, one ready marker, one
 * pinned revision, a list of file specs with size and SHA-256) so the same
 * [VitsModelManager] / `WorkManager` download worker can fetch, verify and
 * install every pack.
 *
 * `sha256` entries left empty (`""`) signal "no upstream reference hash" — the
 * download worker still validates size and writes the marker only after the
 * download completes cleanly, but skips the SHA-256 check. This keeps the
 * pipeline honest: a blank hash is an explicit TODO rather than a guessed
 * value and is therefore documented as a residual risk in the per-pack
 * metadata (see [license] / [description]).
 */
interface TtsModelPackDescriptor {
    /** Stable identifier persisted in settings; must be unique app-wide. */
    val id: VitsModelId

    /** Which engine adapter handles this pack at runtime. */
    val engineKind: TtsEngineKind

    /** Human-readable name shown in the settings UI. */
    val displayName: String

    /** Approximate size label shown in the UI, e.g. "约 394 MB". */
    val sizeLabel: String

    /** Exact total bytes of all [specs] combined; used by the worker. */
    val totalSizeBytes: Long

    /** Unique directory name relative to the app's filesDir. */
    val dirName: String

    /** Pinned upstream revision (commit hash) the [specs] are downloaded from. */
    val revision: String

    /** Upstream repository prefix, e.g. "csukuangfj/kokoro-multi-lang-v1_1". */
    val huggingFaceRepo: String

    /** Marker file written atomically only after every spec is hash-verified. */
    val readyMarkerName: String

    /** Unique WorkManager unique work name; see [VitsModelManager.download]. */
    val workName: String

    /** Free text shown to the user above the download button. */
    val description: String

    /** Upstream license short string, e.g. "Apache-2.0" / "MIT". */
    val license: String

    /** Sample rate of the audio produced by this pack; 0 means engine-reported. */
    val sampleRate: Int

    /** Speakers exposed by the pack, or null for single-speaker models. */
    val speakerMetadata: List<SpeakerEntry>?

    /** Minimum number of entries the manifest (or specs list) must contain. */
    val minManifestEntryCount: Int get() = 1

    /** All files that must exist on disk for the pack to be ready. */
    val specs: List<ModelFileSpec>

    /** Absolute URL the worker should fetch [spec] from. */
    fun assetUrl(spec: ModelFileSpec): String
}