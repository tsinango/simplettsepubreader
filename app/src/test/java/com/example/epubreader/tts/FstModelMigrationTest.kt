package com.example.epubreader.tts

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FstModelMigrationTest {
    private lateinit var tempDir: File
    private lateinit var modelDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fst-migration-test").toFile()
        modelDir = File(tempDir, "models/vits-zh-hf-fanchen-wnj").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeFile(name: String, size: Long, byteValue: Byte = 0) {
        File(modelDir, name).writeBytes(ByteArray(size.toInt()) { byteValue })
    }

    private fun fstSpecs() =
        VitsModelManager.MODEL_SPECS.filter { it.name in listOf("phone.fst", "date.fst", "number.fst") }

    @Test
    fun missingFstFilesAreDetected() {
        val onnxSpec = VitsModelManager.MODEL_SPECS[0]
        val tokensSpec = VitsModelManager.MODEL_SPECS[1]
        val lexiconSpec = VitsModelManager.MODEL_SPECS[2]
        writeFile(onnxSpec.name, onnxSpec.size)
        writeFile(tokensSpec.name, tokensSpec.size)
        writeFile(lexiconSpec.name, lexiconSpec.size)

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val fstStatuses = statuses.filter { it.spec.name in listOf("phone.fst", "date.fst", "number.fst") }
        assertEquals(3, fstStatuses.size)
        assertTrue(fstStatuses.all { !it.valid })
    }

    @Test
    fun sameSizeCorruptFstIsDetected() {
        VitsModelManager.MODEL_SPECS.forEach { spec ->
            writeFile(spec.name, spec.size)
        }

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val phoneStatus = statuses.first { it.spec.name == "phone.fst" }
        assertEquals(phoneStatus.spec.size, phoneStatus.file.length())
        assertFalse("same-size corrupt phone.fst must be detected", phoneStatus.valid)
    }

    @Test
    fun onlyCorruptFilesAreFlaggedForReDownload() {
        VitsModelManager.MODEL_SPECS.forEach { spec ->
            writeFile(spec.name, spec.size)
        }

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val corrupt = statuses.filter { !it.valid }
        assertEquals(6, corrupt.size)

        val phoneCorrupt = corrupt.first { it.spec.name == "phone.fst" }
        assertEquals("phone.fst", phoneCorrupt.spec.name)
        val requiredBytes = corrupt.sumOf { it.spec.size }
        assertTrue(requiredBytes <= VitsModelManager.MODEL_SIZE_BYTES)
    }

    @Test
    fun validatedFilesAreNotRedownloaded() {
        VitsModelManager.MODEL_SPECS.forEach { spec ->
            writeFile(spec.name, spec.size)
        }

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val invalid = statuses.filter { !it.valid }

        assertTrue(
            "Files with matching size but wrong SHA-256 should be invalid",
            invalid.size == 6,
        )
    }

    @Test
    fun spaceCalculationUsesOnlyMissingFilesWhenSomeValid() {
        val largeSpec = VitsModelManager.MODEL_SPECS[0]
        writeFile(largeSpec.name, largeSpec.size)

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val missing = statuses.filter { !it.valid }
        val requiredBytes = missing.sumOf { it.spec.size }
        val fullSize = VitsModelManager.MODEL_SIZE_BYTES

        assertTrue(
            "Required bytes ($requiredBytes) must equal sum of invalid files, not full size",
            requiredBytes == missing.sumOf { it.spec.size },
        )
        assertEquals(fullSize, VitsModelManager.MODEL_SPECS.sumOf { it.size })
    }

    @Test
    fun spaceCalculationForOnlyFstMissingIsLessThanFullModel() {
        val largeSpec = VitsModelManager.MODEL_SPECS[0]
        val tokensSpec = VitsModelManager.MODEL_SPECS[1]
        val lexiconSpec = VitsModelManager.MODEL_SPECS[2]
        writeFile(largeSpec.name, largeSpec.size)
        writeFile(tokensSpec.name, tokensSpec.size)
        writeFile(lexiconSpec.name, lexiconSpec.size)

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val fstInvalid = statuses.filter {
            !it.valid && it.spec.name in listOf("phone.fst", "date.fst", "number.fst")
        }
        val nonFstInvalid = statuses.filter {
            !it.valid && it.spec.name !in listOf("phone.fst", "date.fst", "number.fst")
        }
        val fstRequired = fstInvalid.sumOf { it.spec.size }
        val totalRequired = statuses.filter { !it.valid }.sumOf { it.spec.size }

        assertTrue(fstRequired > 0)
        assertTrue(fstRequired < VitsModelManager.MODEL_SIZE_BYTES)
        assertTrue(nonFstInvalid.isNotEmpty())
        assertTrue(totalRequired >= fstRequired)
    }

    @Test
    fun oldVersionMigrationPreservesLargeModelFiles() {
        val largeSpec = VitsModelManager.MODEL_SPECS[0]
        val tokensSpec = VitsModelManager.MODEL_SPECS[1]
        val lexiconSpec = VitsModelManager.MODEL_SPECS[2]
        writeFile(largeSpec.name, largeSpec.size)
        writeFile(tokensSpec.name, tokensSpec.size)
        writeFile(lexiconSpec.name, lexiconSpec.size)

        val beforeLargeFile = File(modelDir, largeSpec.name)
        val beforeLargeLength = beforeLargeFile.length()

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val missingFsts = statuses.filter {
            !it.valid && it.spec.name in listOf("phone.fst", "date.fst", "number.fst")
        }
        assertEquals(3, missingFsts.size)
        assertEquals(beforeLargeLength, beforeLargeFile.length())
    }

    @Test
    fun allFstFilesMustBeVerified() {
        val specs = fstSpecs()
        assertEquals(3, specs.size)
        val phoneSpec = specs.first { it.name == "phone.fst" }
        assertEquals(88_630L, phoneSpec.size)
        val dateSpec = specs.first { it.name == "date.fst" }
        assertEquals(59_154L, dateSpec.size)
        val numberSpec = specs.first { it.name == "number.fst" }
        assertEquals(64_482L, numberSpec.size)
    }

    @Test
    fun readyMarkerOnlyWrittenAfterAllFilesValid() {
        val readyFile = File(modelDir, ".ready-75a59ed-v2")
        readyFile.writeText("test")

        VitsModelManager.MODEL_SPECS.forEach { spec ->
            writeFile(spec.name, spec.size)
        }

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val allValid = statuses.all { it.valid }
        assertFalse("Ready marker should not be valid when files are corrupt", allValid)
    }

    @Test
    fun fstSpecsHaveSha256Hashes() {
        val specs = fstSpecs()
        specs.forEach { spec ->
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun differentSizeFstIsDetected() {
        val phoneSpec = VitsModelManager.MODEL_SPECS.first { it.name == "phone.fst" }
        File(modelDir, phoneSpec.name).writeBytes(ByteArray(10))

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val phoneStatus = statuses.first { it.spec.name == "phone.fst" }
        assertFalse(phoneStatus.valid)
    }

    @Test
    fun missingAllFilesAreAllInvalid() {
        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        assertEquals(6, statuses.size)
        assertTrue(statuses.all { !it.valid })
    }

    @Test
    fun fstMigrationRequiresFstSpaceNotFullModelSpace() {
        val largeSpec = VitsModelManager.MODEL_SPECS[0]
        val tokensSpec = VitsModelManager.MODEL_SPECS[1]
        val lexiconSpec = VitsModelManager.MODEL_SPECS[2]
        writeFile(largeSpec.name, largeSpec.size)
        writeFile(tokensSpec.name, tokensSpec.size)
        writeFile(lexiconSpec.name, lexiconSpec.size)

        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        val fstMissing = statuses.filter {
            !it.valid && it.spec.name in listOf("phone.fst", "date.fst", "number.fst")
        }
        val fstTotal = fstMissing.sumOf { it.spec.size }
        assertEquals(88_630L + 59_154L + 64_482L, fstTotal)
        assertTrue(fstTotal < VitsModelManager.MODEL_SIZE_BYTES)
    }

    @Test
    fun corruptFstWithSameSizeHasWrongSha256() {
        val phoneSpec = VitsModelManager.MODEL_SPECS.first { it.name == "phone.fst" }
        writeFile(phoneSpec.name, phoneSpec.size, 0)
        val file = File(modelDir, phoneSpec.name)
        val actualSha = VitsModelManager.sha256(file)

        assertEquals(phoneSpec.size, file.length())
        assertFalse("Corrupt content with same size must have different SHA-256", actualSha == phoneSpec.sha256)
    }

    @Test
    fun modelSpecsIncludeAllSixFiles() {
        assertEquals(6, VitsModelManager.MODEL_SPECS.size)
        val names = VitsModelManager.MODEL_SPECS.map { it.name }
        assertTrue("vits-zh-hf-fanchen-wnj.onnx" in names)
        assertTrue("tokens.txt" in names)
        assertTrue("lexicon.txt" in names)
        assertTrue("phone.fst" in names)
        assertTrue("date.fst" in names)
        assertTrue("number.fst" in names)
    }

    @Test
    fun verifyReportsFileAndSpecForEachEntry() {
        val statuses = VitsModelManager.verifyModelFilesInDir(modelDir)
        statuses.forEach { status ->
            assertEquals(status.spec.name, status.file.name)
            assertFalse(status.valid)
        }
    }
}
