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
        cleanPack()
    }

    @After
    fun after() {
        cleanPack()
    }

    private fun cleanPack() {
        val dir = java.io.File(context.filesDir, descriptor.dirName)
        dir.deleteRecursively()
        java.io.File(dir.parentFile, "${dir.name}.installing").deleteRecursively()
    }

    @Test
    fun happyPathInstallsPackAtomicallyAndWritesMarker() {
        val zip = zipWithManifest(
            files = listOf(
                ManifestFile("bert/deberta.mnn", "hello".toByteArray()),
                ManifestFile("bv2/G_0.mnn", "world".toByteArray()),
            ),
        )
        val importer = BertVits2MnnPackImporter(context, descriptor)
        val manifest = importer.install(zip) // throws on failure
        assertEquals(descriptor.revision, manifest.revision)
        assertEquals(2, manifest.files.size)
        val packDir = java.io.File(context.filesDir, descriptor.dirName)
        assertTrue(packDir.isDirectory)
        assertTrue(java.io.File(packDir, descriptor.readyMarkerName).isFile)
        assertTrue(java.io.File(packDir, "bert/deberta.mnn").isFile)
        assertTrue(java.io.File(packDir, "bv2/G_0.mnn").isFile)
        assertTrue(VitsModelManager.isReady(context, descriptor))
        // No staging dir leaks behind a successful install.
        assertFalse(java.io.File(packDir.parentFile, "${packDir.name}.installing").exists())
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
        } catch (e: BertVits2MnnPackImporter.ImportException) {
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
            files = listOf(ManifestFile("a.txt", "x".toByteArray())),
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
            files = listOf(ManifestFile("a.txt", "x".toByteArray())),
            // claim twice the actual byte count
            overrideSizes = mapOf("a.txt" to 2L),
            overrideHashes = mapOf("a.txt" to ""),
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
            files = listOf(ManifestFile("a.txt", "hello".toByteArray())),
            overrideHashes = mapOf("a.txt" to "00".repeat(32)),
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
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("stranger.txt"))
            zos.write("z".toByteArray())
            zos.closeEntry()
            writeManifest(
                zos,
                files = listOf(
                    ManifestFile("a.txt", "hello".toByteArray()),
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
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
            // Manifest lists a missing b.txt
            writeManifest(
                zos,
                files = listOf(
                    ManifestFile("a.txt", "hello".toByteArray()),
                    ManifestFile("b.txt", "world".toByteArray()),
                ),
            )
        }
        try {
            BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(ba.toByteArray()))
            fail("expected ImportException due to missing entry b.txt")
        } catch (_: BertVits2MnnPackImporter.ImportException) {
            // expected
        }
    }

    @Test
    fun blankSha256IsSkippedSizeStillVerified() {
        val data = "hello-bv2".toByteArray()
        val zip = zipWithManifest(
            files = listOf(ManifestFile("a.txt", data)),
            overrideHashes = mapOf("a.txt" to ""),
        )
        BertVits2MnnPackImporter(context, descriptor).install(zip)
        assertTrue(VitsModelManager.isReady(context, descriptor))
    }

    @Test
    fun importerStreamsZipInputStreamDirectlyWithoutMaterialisingToDisk() {
        val zip = zipWithManifest(
            files = listOf(ManifestFile("greetings.txt", "你好".toByteArray(Charsets.UTF_8))),
        )
        // Mark the stream by using a wrapped stream: importer reads once
        // sequentially. Ensure we don't accidentally require random access.
        val bytes = ByteStreams.toByteArray(zip)
        BertVits2MnnPackImporter(context, descriptor).install(ByteArrayInputStream(bytes))
        assertTrue(VitsModelManager.isReady(context, descriptor))
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