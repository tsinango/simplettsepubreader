package com.example.epubreader.tts

/**
 * [TtsModelPackDescriptor] for the Kokoro-82M Chinese pack distributed at
 * HuggingFace `csukuangfj/kokoro-multi-lang-v1_1`.
 *
 * Upstream source (verified, see links below):
 *   - Repository:  https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_1
 *   - Pinned revision: 914313412b607d95400bcd12446233fbd1248801
 *     (`main` head at the time of writing; immutable commit hash)
 *   - Sherpa-onnx PR #1942 (Add Kokoro v1.1-zh):
 *     https://github.com/k2-fsa/sherpa-onnx/pull/1942
 *   - Speaker breakdown per upstream PR description:
 *       0-2   English female (3 speakers)
 *       3-57  Chinese female (55 speakers)
 *       58-102 Chinese male (45 speakers)
 *     total 103 speakers; sample rate fixed at 24000 Hz.
 *   - LICENSE: Apache-2.0 (delegated to upstream repo LICENSE file)
 *
 * File hashes:
 *   The seven LFS-tracked files (`model.onnx`, `voices.bin`, `lexicon-zh.txt`,
 *   `lexicon-us-en.txt`, `lexicon-gb-en.txt`, `espeak-ng-data/cmn_dict`,
 *   `espeak-ng-data/ru_dict`) carry a verifiable SHA-256 reported by HF's LFS
 *   pointer (the `oid` field). Every other file lacks an LFS pointer and the
 *   upstream docs page does not list a SHA-256, so its `sha256` field is left
 *   blank: the download worker validates size and skips the hash step. These
 *   blank fields are an explicit TODO for a contributor with first-hand bytes
 *   rather than a guessed value.
 *
 * Storage:
 *  - Model files live under `filesDir/models/kokoro-multi-lang-v1_1`.
 *  - The ready marker is `.ready-9143134-v1`.
 *  - The `dict/` folder is NOT fetched: the sherpa-onnx Kotlin bindings mark
 *    `OfflineTtsKokoroModelConfig.dictDir` as unused, so the 14.6 MB of jieba
 *    dictionaries is dead weight at runtime and is excluded here.
 *
 * Non-commercial notice: the upstream model is the Apache-2.0 licensed Kokoro
 * export; the PR distributes only SHA-defined ONNX/voices/lexicon files, no
 * additional usage restriction beyond the upstream LICENSE applies. Display the
 * upstream LICENSE link in the model card UI.
 */
data class KokoroModelDescriptor(
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
    override val specs: List<ModelFileSpec>,
) : TtsModelPackDescriptor {
    override fun assetUrl(spec: ModelFileSpec): String =
        "https://huggingface.co/$huggingFaceRepo/resolve/$revision/${spec.name}"
}

object KokoroModelRegistry {
    /**
     * Suggested default Sid for the "试听女声" entry point. Pinned to the first
     * Chinese female documented by upstream PR #1942 (`zf_001`, id=3).
     */
    const val DEFAULT_CHINESE_FEMALE_SID = 3

    /**
     * Suggested default Sid for the "试听男声" entry point. Pinned to the first
     * Chinese male documented by upstream PR #1942 (`zm_xxx`, id=58).
     */
    const val DEFAULT_CHINESE_MALE_SID = 58

    /**
     * Default user speech rate for the Kokoro pack. Per the task spec ("速度
     * 默认 1.0，不使用当前 WNJ 的 0.85 校准") we keep Kokoro's
     * lengthScale at a clean 1.0 baseline; the user speech-rate slider then
     * multiplies on top via [TtsRatePolicy.userRate].
     */
    const val DEFAULT_USER_RATE = 1f

    /**
     * Kokoro `kokoro-multi-lang-v1_1` pack. Sizes come from HuggingFace's
     * `/api/models/.../tree/main?recursive=true`; hashes come from LFS
     * pointers where available, otherwise the field is blank.
     */
    val kokoroMultiLangV1_1 = KokoroModelDescriptor(
        id = VitsModelId.KOKORO_MULTI_ZH,
        engineKind = TtsEngineKind.SHERPA_KOKORO,
        displayName = VitsModelId.KOKORO_MULTI_ZH.displayName,
        sizeLabel = "约 394 MB",
        totalSizeBytes = 412_070_517L,
        dirName = "models/kokoro-multi-lang-v1_1",
        revision = "914313412b607d95400bcd12446233fbd1248801",
        huggingFaceRepo = "csukuangfj/kokoro-multi-lang-v1_1",
        readyMarkerName = ".ready-9143134-v1",
        workName = "download-kokoro-multi-lang-v1_1",
        description = "Kokoro-82M 中文版（非量化），24 kHz / 103 说话人 " +
            "(中文女声 55 / 男声 45 / 英文女声 3)。Apache-2.0。约 394 MB。",
        license = "Apache-2.0",
        sampleRate = 24_000,
        speakerMetadata = kokoroSpeakerManifest(),
        specs = listOf(
            ModelFileSpec(name = "date-zh.fst", size = 59154L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/af_dict", size = 121473L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/am_dict", size = 63878L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/an_dict", size = 6691L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ar_dict", size = 478165L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/as_dict", size = 5005L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/az_dict", size = 43773L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ba_dict", size = 2098L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/be_dict", size = 2652L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/bg_dict", size = 87051L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/bn_dict", size = 89979L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/bpy_dict", size = 5226L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/bs_dict", size = 47068L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ca_dict", size = 45566L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/chr_dict", size = 2859L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/cmn_dict", size = 1566335L, sha256 = "109aaa7708d3727382acb3ae41d8e2094a7e2bb9f651a81835be22a6f08071fe"),
            ModelFileSpec(name = "espeak-ng-data/cs_dict", size = 49645L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/cv_dict", size = 1344L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/cy_dict", size = 43130L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/da_dict", size = 245287L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/de_dict", size = 68276L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/el_dict", size = 72841L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/en_dict", size = 166944L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/eo_dict", size = 4666L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/es_dict", size = 49252L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/et_dict", size = 44263L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/eu_dict", size = 48841L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/fa_dict", size = 292423L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/fi_dict", size = 43928L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/fr_dict", size = 63727L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ga_dict", size = 52673L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/gd_dict", size = 49121L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/gn_dict", size = 3248L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/grc_dict", size = 3433L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/gu_dict", size = 82480L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/hak_dict", size = 3335L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/haw_dict", size = 2443L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/he_dict", size = 6963L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/hi_dict", size = 92143L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/hr_dict", size = 49388L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ht_dict", size = 1803L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/hu_dict", size = 153785L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/hy_dict", size = 62263L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ia_dict", size = 331275L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/id_dict", size = 43458L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/intonations", size = 2040L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/io_dict", size = 2165L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/is_dict", size = 44354L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/it_dict", size = 152889L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ja_dict", size = 47652L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/jbo_dict", size = 2243L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ka_dict", size = 87775L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/kk_dict", size = 1859L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/kl_dict", size = 2838L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/kn_dict", size = 87828L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ko_dict", size = 47523L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/kok_dict", size = 6394L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ku_dict", size = 2265L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ky_dict", size = 64977L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/la_dict", size = 3806L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/aav/vi", size = 111L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/aav/vi-VN-x-central", size = 143L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/aav/vi-VN-x-south", size = 142L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/eo", size = 41L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/ia", size = 29L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/io", size = 50L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/jbo", size = 69L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/lfn", size = 135L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/piqd", size = 56L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/py", size = 140L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/qdb", size = 57L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/qya", size = 173L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/art/sjn", size = 175L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/azc/nci", size = 114L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/bat/lt", size = 28L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/bat/ltg", size = 312L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/bat/lv", size = 229L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/bnt/sw", size = 41L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/bnt/tn", size = 42L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ccs/ka", size = 124L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/cel/cy", size = 37L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/cel/ga", size = 66L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/cel/gd", size = 51L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/cus/om", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/dra/kn", size = 55L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/dra/ml", size = 57L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/dra/ta", size = 51L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/dra/te", size = 70L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/esx/kl", size = 30L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/eu", size = 54L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmq/da", size = 43L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmq/is", size = 27L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmq/nb", size = 87L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmq/sv", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/af", size = 123L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/de", size = 42L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en", size = 140L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-029", size = 335L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-GB-scotland", size = 295L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-GB-x-gbclan", size = 238L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-GB-x-gbcwmd", size = 188L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-GB-x-rp", size = 249L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-US", size = 257L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/en-US-nyc", size = 271L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/lb", size = 31L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/gmw/nl", size = 23L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/grk/el", size = 23L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/grk/grc", size = 99L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/as", size = 42L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/bn", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/bpy", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/gu", size = 42L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/hi", size = 23L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/kok", size = 26L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/mr", size = 41L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/ne", size = 37L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/or", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/pa", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/sd", size = 66L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/si", size = 55L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/inc/ur", size = 94L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ine/hy", size = 61L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ine/hyw", size = 365L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ine/sq", size = 103L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ira/fa", size = 90L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ira/fa-Latn", size = 269L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ira/ku", size = 40L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/iro/chr", size = 569L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/itc/la", size = 297L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/jpx/ja", size = 52L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/ko", size = 51L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/map/haw", size = 42L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/miz/mto", size = 183L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/myn/quc", size = 210L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/poz/id", size = 134L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/poz/mi", size = 367L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/poz/ms", size = 430L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/qu", size = 88L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/an", size = 27L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/ca", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/es", size = 63L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/es-419", size = 167L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/fr", size = 79L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/fr-BE", size = 84L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/fr-CH", size = 86L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/ht", size = 140L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/it", size = 109L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/pap", size = 62L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/pt", size = 95L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/pt-BR", size = 109L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/roa/ro", size = 26L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sai/gn", size = 47L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sem/am", size = 41L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sem/ar", size = 50L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sem/he", size = 40L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sem/mt", size = 41L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/cmn", size = 686L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/cmn-Latn-pinyin", size = 161L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/hak", size = 128L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/my", size = 56L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/yue", size = 194L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/sit/yue-Latn-jyutping", size = 213L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/tai/shn", size = 92L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/tai/th", size = 37L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/az", size = 45L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/ba", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/cv", size = 40L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/kk", size = 40L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/ky", size = 43L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/nog", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/tk", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/tr", size = 25L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/tt", size = 23L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/ug", size = 24L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/trk/uz", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/urj/et", size = 237L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/urj/fi", size = 237L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/urj/hu", size = 73L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/urj/smj", size = 45L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zle/be", size = 52L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zle/ru", size = 57L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zle/ru-cl", size = 91L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zle/ru-LV", size = 280L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zle/uk", size = 97L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/bg", size = 111L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/bs", size = 230L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/hr", size = 262L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/mk", size = 28L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/sl", size = 43L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zls/sr", size = 250L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zlw/cs", size = 23L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zlw/pl", size = 38L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lang/zlw/sk", size = 24L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lb_dict", size = 687931L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lfn_dict", size = 2793L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lt_dict", size = 49890L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/lv_dict", size = 66337L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/mi_dict", size = 1346L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/mk_dict", size = 63859L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ml_dict", size = 92345L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/mr_dict", size = 87391L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ms_dict", size = 53541L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/mt_dict", size = 4384L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/mto_dict", size = 3960L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/my_dict", size = 95948L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/nci_dict", size = 1534L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ne_dict", size = 95377L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/nl_dict", size = 65979L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/no_dict", size = 4178L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/nog_dict", size = 3294L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/om_dict", size = 2302L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/or_dict", size = 89246L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/pa_dict", size = 79953L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/pap_dict", size = 2128L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/phondata", size = 550424L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/phondata-manifest", size = 21821L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/phonindex", size = 39074L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/phontab", size = 55796L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/piqd_dict", size = 1710L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/pl_dict", size = 76730L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/pt_dict", size = 67817L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/py_dict", size = 2409L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/qdb_dict", size = 3028L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/qu_dict", size = 1919L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/quc_dict", size = 1450L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/qya_dict", size = 1939L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ro_dict", size = 68538L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ru_dict", size = 8532392L, sha256 = "f0f6181bbbf9e53cd1e8f9d26bde8fc62119c4f78181948f961cb29866e5e585"),
            ModelFileSpec(name = "espeak-ng-data/sd_dict", size = 59928L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/shn_dict", size = 88172L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/si_dict", size = 85384L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sjn_dict", size = 1783L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sk_dict", size = 50002L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sl_dict", size = 45047L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/smj_dict", size = 35095L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sq_dict", size = 45003L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sr_dict", size = 46832L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sv_dict", size = 47836L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/sw_dict", size = 47804L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ta_dict", size = 209553L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/te_dict", size = 94837L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/th_dict", size = 2301L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/tk_dict", size = 20868L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/tn_dict", size = 3072L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/tr_dict", size = 46793L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/tt_dict", size = 2121L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ug_dict", size = 2070L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/uk_dict", size = 3492L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/ur_dict", size = 133556L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/uz_dict", size = 2540L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/vi_dict", size = 52608L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/adam", size = 75L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Alex", size = 128L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Alicia", size = 474L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Andrea", size = 357L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Andy", size = 320L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/anika", size = 493L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/anikaRobot", size = 512L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Annie", size = 315L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/announcer", size = 300L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/antonio", size = 381L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/AnxiousAndy", size = 361L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/aunty", size = 358L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/belinda", size = 340L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/benjamin", size = 201L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/boris", size = 224L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/caleb", size = 57L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/croak", size = 93L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/david", size = 112L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Demonic", size = 3858L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Denis", size = 305L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Diogo", size = 379L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/ed", size = 287L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/edward", size = 151L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/edward2", size = 152L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/f1", size = 324L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/f2", size = 357L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/f3", size = 375L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/f4", size = 350L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/f5", size = 432L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/fast", size = 149L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Gene", size = 281L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Gene2", size = 283L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/grandma", size = 263L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/grandpa", size = 256L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/gustave", size = 253L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Henrique", size = 381L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Hugo", size = 378L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/ian", size = 3168L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/iven", size = 261L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/iven2", size = 279L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/iven3", size = 262L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/iven4", size = 261L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Jacky", size = 267L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/john", size = 3186L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/kaukovalta", size = 361L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt", size = 38L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt2", size = 38L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt3", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt4", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt5", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/klatt6", size = 39L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Lee", size = 338L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/linda", size = 350L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m1", size = 335L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m2", size = 264L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m3", size = 300L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m4", size = 290L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m5", size = 262L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m6", size = 188L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m7", size = 254L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/m8", size = 284L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/marcelo", size = 251L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Marco", size = 467L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Mario", size = 270L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/max", size = 225L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Michael", size = 270L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/michel", size = 404L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/miguel", size = 382L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Mike", size = 112L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/mike2", size = 188L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Mr serious", size = 3193L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Nguyen", size = 280L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/norbert", size = 3189L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/pablo", size = 3142L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/paul", size = 284L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/pedro", size = 352L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/quincy", size = 354L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Reed", size = 202L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/RicishayMax", size = 233L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/RicishayMax2", size = 435L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/RicishayMax3", size = 435L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/rob", size = 265L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robert", size = 274L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft", size = 451L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft2", size = 454L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft3", size = 455L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft4", size = 447L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft5", size = 445L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft6", size = 287L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft7", size = 410L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/robosoft8", size = 243L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/sandro", size = 530L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/shelby", size = 280L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/steph", size = 364L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/steph2", size = 367L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/steph3", size = 377L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Storm", size = 420L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/travis", size = 383L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/Tweaky", size = 3189L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/UniRobot", size = 417L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/victor", size = 253L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/whisper", size = 186L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/whisperf", size = 392L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/voices/!v/zac", size = 275L, sha256 = ""),
            ModelFileSpec(name = "espeak-ng-data/yue_dict", size = 563571L, sha256 = ""),
            ModelFileSpec(name = "lexicon-gb-en.txt", size = 6366635L, sha256 = "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"),
            ModelFileSpec(name = "lexicon-us-en.txt", size = 5956885L, sha256 = "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
            ModelFileSpec(name = "lexicon-zh.txt", size = 2119465L, sha256 = "11111d8cd695fba2ace1367a1d0a708b586e6ef5c1f9be91da5d7eef129b651c"),
            ModelFileSpec(name = "model.onnx", size = 325631784L, sha256 = "acc4adc175b9d9986106cd20060329673ad5a2e12ef3c557d2d3745b694f8b38"),
            ModelFileSpec(name = "number-zh.fst", size = 64482L, sha256 = ""),
            ModelFileSpec(name = "phone-zh.fst", size = 88630L, sha256 = ""),
            ModelFileSpec(name = "tokens.txt", size = 1111L, sha256 = ""),
            ModelFileSpec(name = "voices.bin", size = 53790720L, sha256 = "e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c"),

        ),
    )

    /**
     * Returns the official Kokoro v1.1-zh 103-speaker manifest as documented in
     * https://github.com/k2-fsa/sherpa-onnx/pull/1942:
     *
     *   0    af_maple   English female
     *   1    af_sol     English female
     *   2    bf_vale    English female
     *   3..57  (55)    Chinese female (zf_NNN); name pattern confirmed by PR sample id=3 `zf_001`.
     *   58..102 (45)   Chinese male  (zm_NNN); pattern follows the same convention.
     *
     * For ids beyond the three named in the PR (`af_maple`, `af_sol`, `bf_vale`)
     * we cannot verify individual voice names from the primary source, so the
     * per-id label uses the canonical prefix + zero-padded index of the id
     * inside its language/gender block (`zf_001` for id=3 etc.). The genders
     * themselves — which is the information the UI relies on for the default
     * female / male test entries — are taken verbatim from PR #1942's table,
     * never guessed.
     */
    fun kokoroSpeakerManifest(): List<SpeakerEntry> = buildList {
        add(SpeakerEntry(0, "af_maple", "EN", SpeakerGender.FEMALE))
        add(SpeakerEntry(1, "af_sol", "EN", SpeakerGender.FEMALE))
        add(SpeakerEntry(2, "bf_vale", "EN", SpeakerGender.FEMALE))
        for (sid in 3..57) {
            val n = (sid - 2).toString().padStart(3, '0')
            add(SpeakerEntry(sid, "zf_$n", "ZH", SpeakerGender.FEMALE))
        }
        for (sid in 58..102) {
            val n = (sid - 57).toString().padStart(3, '0')
            add(SpeakerEntry(sid, "zm_$n", "ZH", SpeakerGender.MALE))
        }
    }
}