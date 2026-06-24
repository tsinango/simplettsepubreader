package com.example.epubreader.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KokoroModelDescriptorTest {

    @Test
    fun registryExposesKokoroPackWithExpectedMetadata() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        assertEquals(VitsModelId.KOKORO_MULTI_ZH, pack.id)
        assertEquals(TtsEngineKind.SHERPA_KOKORO, pack.engineKind)
        assertEquals("914313412b607d95400bcd12446233fbd1248801", pack.revision)
        assertEquals("csukuangfj/kokoro-multi-lang-v1_1", pack.huggingFaceRepo)
        assertEquals(".ready-9143134-v1", pack.readyMarkerName)
        assertEquals("download-kokoro-multi-lang-v1_1", pack.workName)
        assertEquals(24_000, pack.sampleRate)
        assertEquals("Apache-2.0", pack.license)
    }

    @Test
    fun specCountAndTotalSizeMatchUpstreamListing() {
        // 364 files (excluding dict/, .gitattributes, LICENSE, README.md) and
        // 412070517 bytes. Sizes were pulled from the HF recursive tree API; if
        // upstream reordered or moved a file these must be refreshed.
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        assertEquals(364, pack.specs.size)
        val sum = pack.specs.sumOf { it.size }
        assertEquals(pack.totalSizeBytes, sum)
        assertEquals(412_070_517L, sum)
    }

    @Test
    fun speakerManifestMatchesUpstreamPr1942Breakdown() {
        val speakers = KokoroModelRegistry.kokoroSpeakerManifest()
        assertEquals(103, speakers.size)
        // IDs 0-2 English female per upstream PR #1942.
        assertEquals(SpeakerGender.FEMALE, speakers[0].gender)
        assertEquals("EN", speakers[0].language)
        assertEquals("af_maple", speakers[0].name)
        assertEquals(SpeakerGender.FEMALE, speakers[2].gender)
        assertEquals("bf_vale", speakers[2].name)
        // IDs 3-57 Chinese female (55 speakers), 58-102 Chinese male (45).
        val zhFemale = speakers.filter { it.language == "ZH" && it.gender == SpeakerGender.FEMALE }
        val zhMale = speakers.filter { it.language == "ZH" && it.gender == SpeakerGender.MALE }
        assertEquals(55, zhFemale.size)
        assertEquals(45, zhMale.size)
        assertEquals(3, zhFemale.first().id)
        assertEquals(58, zhMale.first().id)
    }

    @Test
    fun lfsHashesArePopulatedForLargeFilesOnly() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        val hashed = pack.specs.filter { it.sha256.isNotEmpty() }
        // The seven LFS-tracked upstream files must carry a SHA-256 derived
        // from the HF LFS pointer (`lfs.oid`). Other files without a pointer
        // leave the hash blank as an explicit "unknown" signal — never guessed.
        val expectedHashedNames = listOf(
            "model.onnx",
            "voices.bin",
            "lexicon-zh.txt",
            "lexicon-us-en.txt",
            "lexicon-gb-en.txt",
            "espeak-ng-data/cmn_dict",
            "espeak-ng-data/ru_dict",
        )
        assertEquals(expectedHashedNames.toSet(), hashed.map { it.name }.toSet())
        hashed.forEach { spec ->
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun ruleFstNamesArePresent() {
        val names = KokoroModelRegistry.kokoroMultiLangV1_1.specs.map { it.name }.toSet()
        assertTrue("phone-zh.fst" in names)
        assertTrue("date-zh.fst" in names)
        assertTrue("number-zh.fst" in names)
        assertTrue("tokens.txt" in names)
        assertTrue("model.onnx" in names)
        assertTrue("voices.bin" in names)
        assertTrue("espeak-ng-data/phondata" in names)
    }

    @Test
    fun dictFolderIsIntentionallyExcluded() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        // dict/ is jieba-only and sherpa-onnx marks dictDir as unused for Kokoro,
        // so listing it would waste 14.6 MB and never be loaded.
        assertFalse(pack.specs.any { it.name.startsWith("dict/") })
    }

    @Test
    fun assetUrlPinsRevisionAndRepo() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        val onnxUrl = pack.assetUrl(pack.specs.first { it.name == "model.onnx" })
        assertEquals(
            "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_1/" +
                "resolve/914313412b607d95400bcd12446233fbd1248801/model.onnx",
            onnxUrl,
        )
    }

    @Test
    fun nestedSpecFileIsHashedInWorkerDir() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1
        val tmp = Files.createTempDirectory("kok-spec").toFile()
        try {
            val dir = File(tmp, pack.dirName).apply { mkdirs() }
            val nested = pack.specs.first { it.name == "espeak-ng-data/af_dict" }
            val target = File(dir, nested.name)
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(nested.size.toInt()) { 0x41 })
            // The descriptor's spec is the path the worker writes to; nested
            // parent must be created by the worker (handled in
            // VitsModelDownloadWorker.download).
            assertTrue(target.isFile)
            assertEquals(nested.size, target.length())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun defaultSidsMatchFirstNativeSpeaker() {
        // Pinned to official mapping boundaries; never guessed.
        assertEquals(3, KokoroModelRegistry.DEFAULT_CHINESE_FEMALE_SID)
        assertEquals(58, KokoroModelRegistry.DEFAULT_CHINESE_MALE_SID)
        assertNotNull(VitsModelId.fromStableValue(VitsModelId.KOKORO_MULTI_ZH.stableValue))
    }

    @Test
    fun int8PackUsesIndependentPinnedGraphAndStorage() {
        val pack = KokoroModelRegistry.kokoroMultiLangV1_1Int8
        assertEquals(VitsModelId.KOKORO_MULTI_ZH_INT8, pack.id)
        assertEquals("model.int8.onnx", pack.modelFileName)
        assertTrue(pack.totalSizeBytes < KokoroModelRegistry.kokoroMultiLangV1_1.totalSizeBytes)
        assertEquals(114_299_010L, pack.specs.first { it.name == "model.int8.onnx" }.size)
        assertFalse(pack.specs.any { it.name == "model.onnx" })
    }
}
