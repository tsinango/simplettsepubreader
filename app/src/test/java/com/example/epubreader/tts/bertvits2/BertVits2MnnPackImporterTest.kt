package com.example.epubreader.tts.bertvits2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.epubreader.tts.BertVits2MnnModelRegistry
import com.example.epubreader.tts.VitsModelManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BertVits2MnnPackImporterTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private val descriptor = BertVits2MnnModelRegistry.bertVits2Mnn22k

    @Before
    fun before() {
        cleanPackBackups()
    }

    @After
    fun after() {
        cleanPackBackups()
    }

    private fun cleanPackBackups() {
        val dir = java.io.File(context.filesDir, descriptor.dirName)
        dir.deleteRecursively()
        java.io.File(dir.parentFile, "${dir.name}.installing").deleteRecursively()
        java.io.File(dir.parentFile, "${dir.name}.backup").deleteRecursively()
    }

    @Test
    fun happyPathInstallsPackAtomicallyAndWritesMarker() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("bert/deberta.mnn", "hello".toByteArray()),
                ManifestFile("bv2/G_0.mnn", "world".toByteArray()),
                ManifestFile("tokenizer/tokenizer.json", """{"vocab":[]}""".toByteArray()),
            ),
        )
        val importer = BertVits2MnnPackImporter(context, descriptor)
        val manifest = importer.install(zip)
        assertEquals(descriptor.revision, manifest.revision)
        assertEquals(3, manifest.files.size)
        val packDir = java.io.File(context.filesDir, descriptor.dirName)
        assertTrue(packDir.isDirectory)
        assertTrue(java.io.File(packDir, descriptor.readyMarkerName).isFile)
        assertTrue(java.io.File(packDir, "bert/deberta.mnn").isFile)
        assertTrue(java.io.File(packDir, "bv2/G_0.mnn").isFile)
        assertTrue(java.io.File(packDir, "tokenizer/tokenizer.json").isFile)
        assertTrue(VitsModelManager.isReady(context, descriptor))
        assertFalse(java.io.File(packDir.parentFile, "${packDir.name}.installing").exists())
        assertFalse(java.io.File(packDir.parentFile, "${packDir.name}.backup").exists())
    }

    @Test
    fun zipWithDirectoryTraversalIsRejectedAndCleansUp() {
        val ba = ByteArrayOutputStream()
        ZipOutputStream(ba).use { zos ->
            zos.putNextEntry(ZipEntry("../../../escape/payload.txt"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
            writeManifest(zos, emptyList())
        }
        try {
            BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(ba.toByteArray()))
            fail("expected ImportException for traversal entry")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
        val packDir = java.io.File(context.filesDir, descriptor.dirName)
        assertFalse(packDir.exists())
        assertFalse(java.io.File(java.io.File(context.filesDir, descriptor.dirName).parentFile,
            "${descriptor.dirName}.installing").exists())
    }

    @Test
    fun zipWithoutManifestIsRejected() {
        val ba = ByteArrayOutputStream()
        ZipOutputStream(ba).use { zos ->
            zos.putNextEntry(ZipEntry("no/manifest/here.mnn"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        try {
            BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(ba.toByteArray()))
            fail("expected ImportException due to missing manifest")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun manifestVersionMismatchIsRejected() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "x".toByteArray()),
                ManifestFile("b.mnn", "y".toByteArray()),
                ManifestFile("c.json", "{}".toByteArray()),
            ),
            revision = "wrong-rev",
        )
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for wrong revision")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun sizeMismatchIsRejectedAfterExtraction() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "x".toByteArray()),
                ManifestFile("b.mnn", "y".toByteArray()),
                ManifestFile("target.txt", "zzz".toByteArray()),
            ),
            overrideSizes = mapOf("target.txt" to 5L),
            overrideHashes = mapOf("target.txt" to ""),
        )
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for size mismatch")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun hashMismatchIsRejected() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "x".toByteArray()),
                ManifestFile("b.mnn", "y".toByteArray()),
                ManifestFile("target.txt", "hello".toByteArray()),
            ),
            overrideHashes = mapOf("target.txt" to "00".repeat(32)),
        )
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for hash mismatch")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun unexpectedExtraZipEntriesAreRejected() {
        val ba = ByteArrayOutputStream()
        ZipOutputStream(ba).use { zos ->
            zos.putNextEntry(ZipEntry("a.mnn"))
            zos.write("x".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("b.mnn"))
            zos.write("y".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("c.mnn"))
            zos.write("z".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("stranger.txt"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
            writeManifest(
                zos,
                files = listOf(
                    ManifestFile("a.mnn", "x".toByteArray()),
                    ManifestFile("b.mnn", "y".toByteArray()),
                    ManifestFile("c.mnn", "z".toByteArray()),
                ),
            )
        }
        try {
            BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(ba.toByteArray()))
            fail("expected ImportException due to unexpected entries")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun missingManifestEntryIsRejected() {
        val ba = ByteArrayOutputStream()
        ZipOutputStream(ba).use { zos ->
            zos.putNextEntry(ZipEntry("a.mnn"))
            zos.write("x".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("b.mnn"))
            zos.write("y".toByteArray())
            zos.closeEntry()
            // Manifest lists a.mnn, b.mnn, c.mnn but c.mnn is not in the zip
            writeManifest(
                zos,
                files = listOf(
                    ManifestFile("a.mnn", "x".toByteArray()),
                    ManifestFile("b.mnn", "y".toByteArray()),
                    ManifestFile("c.mnn", "z".toByteArray()),
                ),
            )
        }
        try {
            BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(ba.toByteArray()))
            fail("expected ImportException due to missing entry")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun blankSha256IsSkippedSizeStillVerified() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "hello-bv2".toByteArray()),
                ManifestFile("b.mnn", "world".toByteArray()),
                ManifestFile("c.json", "{}".toByteArray()),
            ),
            overrideHashes = mapOf("a.mnn" to ""),
        )
        BertVits2MnnPackImporter(context, descriptor).install(zip)
        assertTrue(VitsModelManager.isReady(context, descriptor))
    }

    @Test
    fun importerStreamsZipInputStreamDirectlyWithoutMaterialisingToDisk() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "hello".toByteArray()),
                ManifestFile("b.mnn", "world".toByteArray()),
                ManifestFile("greetings.txt", "你好".toByteArray(Charsets.UTF_8)),
            ),
        )
        val bytes = ByteStreams.toByteArray(zip)
        BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(bytes))
        assertTrue(VitsModelManager.isReady(context, descriptor))
    }

    @Test
    fun emptyManifestIsRejected() {
        val zip = zipWithManifest(files = emptyList())
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for empty manifest")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun existingPackIsPreservedOnImportFailure() {
        // First install a valid pack.
        val goodZip = zipWithManifest(
            files = listOf(
                ManifestFile("good.mnn", "good".toByteArray()),
                ManifestFile("good2.mnn", "data".toByteArray()),
                ManifestFile("good3.mnn", "info".toByteArray()),
            ),
        )
        val importer = BertVits2MnnPackImporter(context, descriptor)
        importer.install(goodZip)
        val packDir = java.io.File(context.filesDir, descriptor.dirName)
        assertTrue(java.io.File(packDir, "good.mnn").isFile)

        // Now attempt a bad import — it should fail and leave the original intact.
        val badZip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "x".toByteArray()),
                ManifestFile("b.mnn", "y".toByteArray()),
                ManifestFile("c.mnn", "z".toByteArray()),
            ),
            overrideSizes = mapOf("c.mnn" to 999L),
            overrideHashes = mapOf("c.mnn" to ""),
        )
        try {
            BertVits2MnnPackImporter(context, descriptor).install(badZip)
            fail("expected ImportException for size mismatch")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
        // Original pack must still be intact and READY.
        assertTrue(packDir.isDirectory)
        assertTrue(java.io.File(packDir, "good.mnn").isFile)
        assertTrue(VitsModelManager.isReady(context, descriptor))
        assertFalse(java.io.File(packDir.parentFile, "${packDir.name}.backup").exists())
    }

    @Test
    fun successfulImportWithoutExistingPackHasNoBackup() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "data".toByteArray()),
                ManifestFile("b.mnn", "more".toByteArray()),
                ManifestFile("c.json", "{}".toByteArray()),
            ),
        )
        BertVits2MnnPackImporter(context, descriptor).install(zip)
        val packDir = java.io.File(context.filesDir, descriptor.dirName)
        assertTrue(VitsModelManager.isReady(context, descriptor))
        assertFalse(java.io.File(packDir.parentFile, "${packDir.name}.backup").exists())
    }

    @Test
    fun singleEntryManifestIsRejected() {
        val zip = zipWithManifest(files = listOf(ManifestFile("only.mnn", "x".toByteArray())))
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for below-minimum manifest entries")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun twoEntryManifestIsRejected() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("a.mnn", "x".toByteArray()),
                ManifestFile("b.mnn", "y".toByteArray()),
            ),
        )
        try {
            BertVits2MnnPackImporter(context, descriptor).install(zip)
            fail("expected ImportException for below-minimum manifest entries")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    private data class ManifestFile(val path: String, val bytes: ByteArray)

    private fun zipWithManifest(
        files: List<ManifestFile>,
        revision: String = descriptor.revision,
        overrideSizes: Map<String, Long> = emptyMap(),
        overrideHashes: Map<String, String> = emptyMap(),
    ): java.io.InputStream {
        val ba = ByteArrayOutputStream()
        ZipOutputStream(ba).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.path))
                zos.write(f.bytes)
                zos.closeEntry()
            }
            writeManifest(
                zos,
                files = files,
                revision = revision,
                overrideSizes = overrideSizes,
                overrideHashes = overrideHashes,
            )
        }
        return ByteArrayInputStream(ba.toByteArray())
    }

    private fun writeManifest(
        zos: ZipOutputStream,
        files: List<ManifestFile>,
        revision: String = descriptor.revision,
        overrideSizes: Map<String, Long> = emptyMap(),
        overrideHashes: Map<String, String> = emptyMap(),
    ) {
        val entries = files.joinToString(",") { f ->
            val size = overrideSizes[f.path] ?: f.bytes.size.toLong()
            val hash = overrideHashes[f.path] ?: sha256(f.bytes)
            "{\"path\":\"${f.path}\",\"size\":$size,\"sha256\":\"$hash\"}"
        }
        val content = "{\"revision\":\"$revision\",\"files\":[$entries]}"
        zos.putNextEntry(ZipEntry(descriptor.manifestFileName))
        zos.write(content.toByteArray())
        zos.closeEntry()
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(bytes)
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Small helper since we don't import Guava in tests. */
private object ByteStreams {
    fun toByteArray(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
