package com.example.epubreader.tts

import java.io.File

/**
 * One downloadable file belonging to a [VitsModelDescriptor]. The [sha256] is
 * the SHA-256 of the file content and is verified after download.
 */
data class ModelFileSpec(
    val name: String,
    val size: Long,
    val sha256: String,
)

/**
 * The result of verifying a [ModelFileSpec] against a file on disk.
 */
data class ModelFileStatus(
    val spec: ModelFileSpec,
    val file: File,
    val valid: Boolean,
)

/**
 * Selectable embedded VITS models offered by the offline TTS pipeline.
 *
 * Each model is downloaded on demand from a pinned Hugging Face revision and
 * stored under its own directory so multiple models can coexist without
 * overwriting each other. The [stableValue] is persisted in user settings and
 * must therefore remain constant for the lifetime of a model.
 */
enum class VitsModelId(val stableValue: String, val displayName: String) {
    FANCHEN_WNJ("FANCHEN_WNJ", "内置 VITS（WNJ）"),
    MELO_TTS_ZH_EN("MELO_TTS_ZH_EN", "MeloTTS 中英双语"),
    KOKORO_MULTI_ZH("KOKORO_MULTI_ZH", "Kokoro-82M 中文"),
    ;

    companion object {
        fun fromStableValue(value: String?): VitsModelId? =
            values().firstOrNull { it.stableValue == value }
    }
}

/**
 * Describes a single downloadable VITS model and the files it needs at runtime.
 *
 * The [revision] pins the exact Hugging Face commit so downloads, size checks
 * and SHA-256 verification always reference the same bytes. [dirName] is
 * relative to the app's filesDir and must be unique per model to keep model
 * files isolated.
 */
data class VitsModelDescriptor(
    override val id: VitsModelId,
    override val sizeLabel: String,
    override val totalSizeBytes: Long,
    override val dirName: String,
    override val revision: String,
    override val huggingFaceRepo: String,
    override val readyMarkerName: String,
    override val workName: String,
    val onnxFileName: String,
    val tokensFileName: String,
    val lexiconFileName: String,
    val ruleFstFileNames: List<String>,
    override val specs: List<ModelFileSpec>,
    override val description: String,
    override val engineKind: TtsEngineKind = TtsEngineKind.SHERPA_VITS,
    override val license: String = "Apache-2.0",
    override val sampleRate: Int = 0,
    override val speakerMetadata: List<SpeakerEntry>? = null,
) : TtsModelPackDescriptor {
    override val displayName: String
        get() = id.displayName

    val baseUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo/resolve/$revision"

    override fun assetUrl(spec: ModelFileSpec): String = "$baseUrl/${spec.name}"
}

object VitsModelRegistry {
    /**
     * WNJ Chinese single-speaker male voice. The directory, ready marker and
     * work name intentionally match the pre-multi-model implementation so that
     * existing installations keep reporting ready without a re-download.
     */
    val WNJ = VitsModelDescriptor(
        id = VitsModelId.FANCHEN_WNJ,
        sizeLabel = "约 124 MB",
        totalSizeBytes = 123_746_625L,
        dirName = "models/vits-zh-hf-fanchen-wnj",
        revision = "75a59ed26f999226f412eb9e1dff31c86b42f082",
        huggingFaceRepo = "csukuangfj/vits-zh-hf-fanchen-wnj",
        readyMarkerName = ".ready-75a59ed-v2",
        workName = "download-vits-fanchen-wnj",
        onnxFileName = "vits-zh-hf-fanchen-wnj.onnx",
        tokensFileName = "tokens.txt",
        lexiconFileName = "lexicon.txt",
        ruleFstFileNames = listOf("phone.fst", "date.fst", "number.fst"),
        specs = listOf(
            ModelFileSpec("vits-zh-hf-fanchen-wnj.onnx", 121_076_185L, "ccd592a5f6fa3f7e8840405c3422ffed9eba58db253d4abd82c75280db98c644"),
            ModelFileSpec("tokens.txt", 331L, "34b035b9aeb070df6188b022f29c00e0e142c7ade9f25611ced65db5e9cc8402"),
            ModelFileSpec("lexicon.txt", 2_457_843L, "9af2824e49e731bf615927c768fdc36bbbe894cac57d8e0088d9c94331b07320"),
            ModelFileSpec("phone.fst", 88_630L, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
            ModelFileSpec("date.fst", 59_154L, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
            ModelFileSpec("number.fst", 64_482L, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
        ),
        description = "约 124 MB，中文男声，单说话人；仅支持中文。",
    )

    /**
     * MeloTTS Chinese+English single-speaker female voice converted by the
     * sherpa-onnx team from MyShell.ai's MeloTTS. English coverage is limited to
     * words present in the bundled lexicon.
     */
    val MELO = VitsModelDescriptor(
        id = VitsModelId.MELO_TTS_ZH_EN,
        sizeLabel = "约 170 MB",
        totalSizeBytes = 177_502_116L,
        dirName = "models/vits-melo-tts-zh_en",
        revision = "a0d5c6a264c0ef92d70d8661d8cc502d79627cd6",
        huggingFaceRepo = "csukuangfj/vits-melo-tts-zh_en",
        readyMarkerName = ".ready-a0d5c6a-v1",
        workName = "download-vits-melo-tts-zh_en",
        onnxFileName = "model.onnx",
        tokensFileName = "tokens.txt",
        lexiconFileName = "lexicon.txt",
        ruleFstFileNames = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst"),
        specs = listOf(
            ModelFileSpec("model.onnx", 170_429_550L, "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d"),
            ModelFileSpec("tokens.txt", 655L, "d18664a7e12bd7ea1022ddaf951e534e136815016c5a809d6b64156bffb4369d"),
            ModelFileSpec("lexicon.txt", 6_837_671L, "7236884b02435ac5d10cf69b4be40a61b45aa676b5300f0e412f185748fee528"),
            ModelFileSpec("phone.fst", 88_630L, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
            ModelFileSpec("date.fst", 59_154L, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
            ModelFileSpec("number.fst", 64_482L, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
            ModelFileSpec("new_heteronym.fst", 21_974L, "ca14b2127e27baa571664e4bb791e143e7425f56a6bc29db08d74f97e6aa4e29"),
        ),
        description = "约 170 MB，单女声，中英双语；英文仅保证词典中已有词汇。",
    )

    val all: List<VitsModelDescriptor> = listOf(WNJ, MELO)

    fun byId(id: VitsModelId): VitsModelDescriptor =
        all.first { it.id == id }

    fun byStableValue(value: String?): VitsModelDescriptor? =
        VitsModelId.fromStableValue(value)?.let { byId(it) }
}
