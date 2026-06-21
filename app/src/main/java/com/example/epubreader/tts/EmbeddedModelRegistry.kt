package com.example.epubreader.tts

/**
 * Unified lookup over every downloadable TTS pack offered by the reader,
 * regardless of engine kind (sherpa VITS, sherpa Kokoro, Bert-VITS2-MNN).
 *
 * Existing settings persist a [VitsModelId.stableValue] string in the
 * `vitsModelId` column; this registry is the single place that turns that
 * stable string into the appropriate [TtsModelPackDescriptor] implementation
 * (a [VitsModelDescriptor] or a [KokoroModelDescriptor]). The historical
 * [VitsModelRegistry] is retained and indexed here too so existing users keep
 * their pinned WNJ / MeloTTS data without re-downloading.
 */
object EmbeddedModelRegistry {
    val all: List<TtsModelPackDescriptor> = listOf(
        VitsModelRegistry.WNJ,
        VitsModelRegistry.MELO,
        KokoroModelRegistry.kokoroMultiLangV1_1,
        BertVits2MnnModelRegistry.bertVits2Mnn22k,
    )

    fun byId(id: VitsModelId): TtsModelPackDescriptor =
        all.first { it.id == id }

    fun byStableValue(stableValue: String?): TtsModelPackDescriptor? =
        stableValue?.let { sv -> all.firstOrNull { it.id.stableValue == sv } }
}