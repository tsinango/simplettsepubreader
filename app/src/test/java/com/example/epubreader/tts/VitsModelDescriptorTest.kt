package com.example.epubreader.tts

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VitsModelDescriptorTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vits-descriptor-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun registryExposesBothModels() {
        assertEquals(2, VitsModelRegistry.all.size)
        assertNotNull(VitsModelRegistry.byId(VitsModelId.FANCHEN_WNJ))
        assertNotNull(VitsModelRegistry.byId(VitsModelId.MELO_TTS_ZH_EN))
    }

    @Test
    fun modelIdRoundTripsThroughStableValue() {
        VitsModelId.values().forEach { id ->
            assertEquals(id, VitsModelId.fromStableValue(id.stableValue))
        }
        assertNull(VitsModelId.fromStableValue(null))
        assertNull(VitsModelId.fromStableValue("does-not-exist"))
    }

    @Test
    fun registryResolvesStableValue() {
        assertEquals(
            VitsModelId.FANCHEN_WNJ,
            VitsModelRegistry.byStableValue("FANCHEN_WNJ")?.id,
        )
        assertEquals(
            VitsModelId.MELO_TTS_ZH_EN,
            VitsModelRegistry.byStableValue("MELO_TTS_ZH_EN")?.id,
        )
        assertNull(VitsModelRegistry.byStableValue(null))
    }

    @Test
    fun modelsUseDistinctDirectoriesAndRevisions() {
        val wnj = VitsModelRegistry.WNJ
        val melo = VitsModelRegistry.MELO
        assertNotEquals(wnj.dirName, melo.dirName)
        assertNotEquals(wnj.revision, melo.revision)
        assertNotEquals(wnj.readyMarkerName, melo.readyMarkerName)
        assertNotEquals(wnj.workName, melo.workName)
        assertNotEquals(wnj.onnxFileName, melo.onnxFileName)
    }

    @Test
    fun wnjDescriptorMatchesLegacyStatics() {
        assertEquals(VitsModelManager.MODEL_SIZE_BYTES, VitsModelRegistry.WNJ.totalSizeBytes)
        assertEquals(VitsModelManager.MODEL_SIZE_LABEL, VitsModelRegistry.WNJ.sizeLabel)
        assertEquals("约 124 MB", VitsModelRegistry.WNJ.sizeLabel)
        assertEquals(VitsModelManager.MODEL_SPECS, VitsModelRegistry.WNJ.specs)
        assertEquals(6, VitsModelRegistry.WNJ.specs.size)
        assertEquals(".ready-75a59ed-v2", VitsModelRegistry.WNJ.readyMarkerName)
        assertEquals("models/vits-zh-hf-fanchen-wnj", VitsModelRegistry.WNJ.dirName)
        assertEquals("download-vits-fanchen-wnj", VitsModelRegistry.WNJ.workName)
    }

    @Test
    fun wnjTotalSizeMatchesSpecSum() {
        val sum = VitsModelRegistry.WNJ.specs.sumOf { it.size }
        assertEquals(VitsModelRegistry.WNJ.totalSizeBytes, sum)
    }

    @Test
    fun meloTotalSizeMatchesSpecSum() {
        val sum = VitsModelRegistry.MELO.specs.sumOf { it.size }
        assertEquals(VitsModelRegistry.MELO.totalSizeBytes, sum)
    }

    @Test
    fun meloSpecsIncludeAllSevenFiles() {
        val names = VitsModelRegistry.MELO.specs.map { it.name }
        assertEquals(7, names.size)
        assertTrue("model.onnx" in names)
        assertTrue("tokens.txt" in names)
        assertTrue("lexicon.txt" in names)
        assertTrue("phone.fst" in names)
        assertTrue("date.fst" in names)
        assertTrue("number.fst" in names)
        assertTrue("new_heteronym.fst" in names)
    }

    @Test
    fun meloRuleFstsIncludeNewHeteronym() {
        val ruleFsts = VitsModelRegistry.MELO.ruleFstFileNames
        assertEquals(listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst"), ruleFsts)
    }

    @Test
    fun meloSpecsHaveExactSizesAndHexSha256() {
        VitsModelRegistry.MELO.specs.forEach { spec ->
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.sha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(spec.size > 0)
        }
    }

    @Test
    fun meloModelOnnxSizeAndHashMatchPinnedRevision() {
        val onnx = VitsModelRegistry.MELO.specs.first { it.name == "model.onnx" }
        assertEquals(170_429_550L, onnx.size)
        assertEquals(
            "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d",
            onnx.sha256,
        )
    }

    @Test
    fun meloBaseUrlPinsRevisionAndRepo() {
        val melo = VitsModelRegistry.MELO
        assertTrue(melo.baseUrl.startsWith("https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/resolve/"))
        assertTrue(melo.baseUrl.contains(melo.revision))
        assertEquals("a0d5c6a264c0ef92d70d8661d8cc502d79627cd6", melo.revision)
    }

    @Test
    fun meloDescriptionMentionsBilingualAndDictionaryLimit() {
        val desc = VitsModelRegistry.MELO.description
        assertTrue(desc.contains("170"))
        assertTrue(desc.contains("双语"))
        assertTrue(desc.contains("词典"))
    }

    @Test
    fun sharedRuleFstsHaveIdenticalHashesAcrossModels() {
        val wnjBy = VitsModelRegistry.WNJ.specs.associateBy { it.name }
        val meloBy = VitsModelRegistry.MELO.specs.associateBy { it.name }
        listOf("phone.fst", "date.fst", "number.fst").forEach { name ->
            assertEquals(
                "$name must share SHA-256 across models",
                wnjBy[name]?.sha256,
                meloBy[name]?.sha256,
            )
            assertEquals(
                "$name must share size across models",
                wnjBy[name]?.size,
                meloBy[name]?.size,
            )
        }
    }

    @Test
    fun emptyMeloDirIsNotReady() {
        val dir = File(tempDir, VitsModelRegistry.MELO.dirName).apply { mkdirs() }
        val statuses = VitsModelManager.verifyFilesInDir(dir, VitsModelRegistry.MELO.specs)
        assertEquals(VitsModelRegistry.MELO.specs.size, statuses.size)
        assertTrue(statuses.all { !it.valid })
    }

    @Test
    fun meloSameSizeCorruptFileIsInvalid() {
        val dir = File(tempDir, VitsModelRegistry.MELO.dirName).apply { mkdirs() }
        VitsModelRegistry.MELO.specs.forEach { spec ->
            File(dir, spec.name).writeBytes(ByteArray(spec.size.toInt()))
        }
        val statuses = VitsModelManager.verifyFilesInDir(dir, VitsModelRegistry.MELO.specs)
        assertTrue(statuses.all { !it.valid })
    }

    @Test
    fun meloDifferentSizeFileIsInvalid() {
        val dir = File(tempDir, VitsModelRegistry.MELO.dirName).apply { mkdirs() }
        File(dir, "model.onnx").writeBytes(ByteArray(10))
        val onnx = VitsModelManager.verifyFilesInDir(dir, VitsModelRegistry.MELO.specs)
            .first { it.spec.name == "model.onnx" }
        assertFalse(onnx.valid)
    }

    @Test
    fun readyDetectionIsolatesModelsByDirectory() {
        val wnjDir = File(tempDir, VitsModelRegistry.WNJ.dirName).apply { mkdirs() }
        val meloDir = File(tempDir, VitsModelRegistry.MELO.dirName).apply { mkdirs() }
        VitsModelRegistry.WNJ.specs.forEach { spec ->
            File(wnjDir, spec.name).writeBytes(ByteArray(spec.size.toInt()))
        }
        File(wnjDir, VitsModelRegistry.WNJ.readyMarkerName).writeText("ready")

        val meloStatuses = VitsModelManager.verifyFilesInDir(meloDir, VitsModelRegistry.MELO.specs)
        assertTrue(meloStatuses.all { !it.valid })
        assertFalse(File(meloDir, VitsModelRegistry.MELO.readyMarkerName).isFile)
    }

    @Test
    fun verifyFilesInDirReportsSpecAndFileForEachEntry() {
        val dir = File(tempDir, VitsModelRegistry.MELO.dirName).apply { mkdirs() }
        val statuses = VitsModelManager.verifyFilesInDir(dir, VitsModelRegistry.MELO.specs)
        statuses.forEach { status ->
            assertEquals(status.spec.name, status.file.name)
            assertFalse(status.valid)
        }
    }
}
