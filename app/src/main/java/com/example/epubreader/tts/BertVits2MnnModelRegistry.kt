package com.example.epubreader.tts

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
    override val minManifestEntryCount: Int = 1,
) : TtsModelPackDescriptor {
    override fun assetUrl(spec: ModelFileSpec): String = DOWNLOAD_URL

    companion object {
        private const val DOWNLOAD_URL = "https://github.com/tsinango/simplettsepubreader/releases/download/v1.3/bertvits2-zh-22k-v1.zip"
    }
}

object BertVits2MnnModelRegistry {
    private const val REVISION = "v1"
    private const val ZIP_NAME = "bertvits2-zh-22k-v1.zip"
    private const val ZIP_SIZE = 126959959L
    private const val ZIP_SHA256 = "648997532dee10a09576cb7ffad443528a14f71ea0d241168e705c086ac270ae"
    private const val DOWNLOAD_URL = "https://github.com/tsinango/simplettsepubreader/releases/download/v1.3/bertvits2-zh-22k-v1.zip"

    val bertVits2Mnn22k = BertVits2MnnPackDescriptor(
        id = VitsModelId.BERT_VITS2_MNN_22K,
        engineKind = TtsEngineKind.BERT_VITS2_MNN,
        displayName = VitsModelId.BERT_VITS2_MNN_22K.displayName,
        sizeLabel = "约 127 MB",
        totalSizeBytes = ZIP_SIZE,
        dirName = "models/bertvits2-mnn-22k-zh",
        revision = REVISION,
        huggingFaceRepo = "",
        readyMarkerName = ".ready-bv2-22k-v1",
        workName = "install-bertvits2-mnn-22k-zh",
        description = "Bert-VITS2 MNN 22k 中文模型包；基于上游 Voine/Bert-VITS2-MNN v2.0.0 的 base_model_22k；" +
            "仅学习交流，严禁商用。",
        license = "Apache-2.0 (代码) / 非商业 (模型数据)",
        sampleRate = 22_050,
        speakerMetadata = emptyList(),
        specs = listOf(
            ModelFileSpec(ZIP_NAME, ZIP_SIZE, ZIP_SHA256),
        ),
    )
}
