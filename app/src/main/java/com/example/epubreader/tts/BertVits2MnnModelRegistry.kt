package com.example.epubreader.tts

/**
 * [TtsModelPackDescriptor] for Bert-VITS2-MNN. The upstream project lives at
 * https://github.com/Voine/Bert-VITS2-MNN; we pin tag `v2.0.0` / commit
 * `346bc84` for the 22 kHz Chinese example pack documented in `base_model_22k`.
 *
 * Unlike Kokoro the BV2 packs are NOT downloaded over HTTP: upstream commits
 * binary `.mnn` models to Git LFS inside the demo repository and does not
 * publish a stable redistributable download URL on GitHub Releases. Per the
 * task spec ("如果上游没有稳定可再分发的下载 URL，则实现本地 ZIP 导入和 manifest
 * 校验，不得编造 URL 或把模型提交到仓库"), this pack only becomes READY
 * after the user picks a local BV2 ZIP via the settings UI; see
 * [com.example.epubreader.tts.bertvits2.BertVits2MnnImportWorker].
 *
 * `specs` therefore starts empty — the ZIP manifest itself records which
 * files (MNN modules, distilled BERT, tokenizer, cppjieba dictionary and
 * BV2 pack-specific speaker files) the imported pack must contain, including
 * their per-file size and SHA-256. The fast readiness check verifies the
 * marker revision and a sentinel MANIFEST file written atomically by the
 * importer after every file is hash-verified.
 *
 * Non-commercial notice: the 22 kHz base model is documented in the upstream
 * README as "仅供学习交流使用，禁止用于商业用途"; the model card UI surfaces
 * this disclaimer before the import action and we never expose it as a
 * generally available TTS option. The original Bert-VITS2 implementation
 * (the upstream demo's base model and the inference runtime itself) carries
 * Apache-2.0; the model data carries the additional non-commercial notice.
 */
data class BertVits2MnnPackDescriptor(
    override val id: VitsModelId,
    override val engineKind: TtsEngineKind,
    override val displayName: String,
    override val sizeLabel: String,
    override val totalSizeBytes: Long,
    override val dirName: String,
    override val revision: String,
    override val huggingFaceRepo: String,
    override val readyMarkerName: String,
    override val workName: String,
    override val description: String,
    override val license: String,
    override val sampleRate: Int,
    override val speakerMetadata: List<SpeakerEntry>?,
    override val specs: List<ModelFileSpec> = emptyList(),
    /**
     * Stable name of the manifest file the importer writes into the pack
     * directory after successful verification. Used by the fast readiness
     * check.
     */
    val manifestFileName: String,
) : TtsModelPackDescriptor {
    override fun assetUrl(spec: ModelFileSpec): String =
        error("Bert-VITS2-MNN packs are imported via local ZIP; no HTTP URL is defined")
}

object BertVits2MnnModelRegistry {
    /**
     * 22 kHz Chinese example pack based on `base_model_22k` directory of
     * Bert-VITS2-MNN tag v2.0.0 (commit 346bc84). The pack is loaded at
     * runtime via the upstream `bertvits2-infer-wrapper` AAR built from
     * `PUBLISHING.md`; when the AAR is absent on the device the BV2 engine
     * `initialize` throws a clear `IllegalStateException` (instead of
     * `UnsatisfiedLinkError`) and the TTS service falls back to WNJ /
     * system TTS without crashing.
     */
    val bertVits2Mnn22k = BertVits2MnnPackDescriptor(
        id = VitsModelId.BERT_VITS2_MNN_22K,
        engineKind = TtsEngineKind.BERT_VITS2_MNN,
        displayName = VitsModelId.BERT_VITS2_MNN_22K.displayName,
        sizeLabel = "约 30 MB (MNN int8)",
        totalSizeBytes = 0L,
        dirName = "models/bertvits2-mnn-22k-zh",
        revision = "346bc84",
        huggingFaceRepo = "Voine/Bert-VITS2-MNN",
        readyMarkerName = ".ready-bv2-22k-v1",
        workName = "install-bertvits2-mnn-22k-zh",
        description = "Bert-VITS2 MNN 22k 中文示例包；通过本地 ZIP 导入；仅学习交流，严禁商用。",
        license = "Apache-2.0 (代码) / 非商业 (示例模型)",
        sampleRate = 22_050,
        speakerMetadata = emptyList(),
        manifestFileName = "bv2-pack-manifest.json",
    )
}