package com.example.epubreader.tts.bertvits2

import android.content.Context
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.BertVits2MnnPackDescriptor
import com.example.epubreader.tts.VitsModelManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Import / verify / atomically-install a Bert-VITS2-MNN pack ZIP.
 *
 * The reader cannot download BV2 packs from a fixed URL (upstream commits the
 * bytes to Git LFS inside the demo repository, with no redistributable
 * GitHub Releases URL; see `BertVits2MnnPackDescriptor`). Instead the user
 * picks a local ZIP via the settings UI; this importer is the only path that
 * makes a BV2 pack READY.
 *
 * Hardening guarantees:
 *
 *  - Path traversal: every entry is normalised and rejected if it escapes the
 *    destination directory. `..` segments and absolute paths are forbidden.
 *  - Manifest: the importer reads `bv2-pack-manifest.json` from the zip and
 *    applies it as the authoritative file list — file path, exact size, and
 *    SHA-256 (where non-blank). Files listed in the manifest but missing from
 *    the zip, or files present in the zip but absent from the manifest, both
 *    fail the import.
 *  - Atomicity: extraction happens into a sibling `.installing/` directory;
 *    only after every listed file has been laid down, sized and hash-verified
 *    does the importer rename it onto the pack root and write the `.ready`
 *    marker. Any failure removes the partial install so no half-written BV2
 *    pack can ever be READY.
 */
class BertVits2MnnPackImporter(
    private val context: Context,
    private val descriptor: BertVits2MnnPackDescriptor,
) {
    data class ManifestEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    data class Manifest(
        val revision: String,
        val files: List<ManifestEntry>,
    )

    class ImportException(message: String) : Exception(message)

    /** Reads [zipStream] and installs the pack, returning the manifest it used. */
    fun install(zipStream: InputStream): Manifest = runBlockingImport {
        val packRoot = VitsModelManager.modelDir(context, descriptor)
        // Save an existing pack so we can restore it if the import fails.
        val backup = File(packRoot.parentFile, "${packRoot.name}.backup")
        if (packRoot.isDirectory) {
            backup.deleteRecursively()
            check(packRoot.renameTo(backup)) { "backup existing pack failed" }
        }
        val staging = File(packRoot.parentFile, "${packRoot.name}.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val manifest = extractAndReadManifest(zipStream, staging)
            if (!manifest.revision.equals(descriptor.revision, ignoreCase = true)) {
                throw ImportException(
                    "manifest revision ${manifest.revision} != pack ${descriptor.revision}",
                )
            }
            if (manifest.files.size < descriptor.minManifestEntryCount) {
                throw ImportException(
                    "manifest declares ${manifest.files.size} files, " +
                        "minimum is ${descriptor.minManifestEntryCount}",
                )
            }
            val stagedFiles = staging.walk()
                .filter { it.isFile }
                .associateBy { it.relativeTo(staging).path.replace(File.separatorChar, '/') }
            val issues = mutableListOf<String>()
            manifest.files.forEach { entry ->
                val file = stagedFiles[entry.path]
                if (file == null) {
                    issues += "missing ${entry.path}"
                    return@forEach
                }
                if (file.length() != entry.size) {
                    issues += "size mismatch ${entry.path}: ${file.length()} != ${entry.size}"
                    return@forEach
                }
                if (entry.sha256.isNotEmpty()) {
                    val actual = sha256(file)
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        issues += "sha256 mismatch ${entry.path}"
                    }
                }
            }
            // Files present in the zip but not in the manifest are untrusted. The
            // manifest file itself is allowed — it is always present in the
            // staged directory so the importer can re-read it after
            // extraction, but it is not part of the model asset list.
            val expectedKeys = manifest.files.map { it.path }.toSet() + descriptor.manifestFileName
            val extras = stagedFiles.keys - expectedKeys
            if (extras.isNotEmpty()) {
                issues += "unexpected entries: ${extras.joinToString()}"
            }
            if (issues.isNotEmpty()) {
                throw ImportException("manifest validation failed: ${issues.joinToString("; ")}")
            }
            check(staging.renameTo(packRoot)) { "rename staging into pack root failed" }
            val marker = File(packRoot, descriptor.readyMarkerName)
            val markerPart = File(packRoot, "${descriptor.readyMarkerName}.part")
            markerPart.writeText(descriptor.revision)
            check(markerPart.renameTo(marker)) { "writing ready marker failed" }
            // Commit succeeded — discard the backup.
            backup.deleteRecursively()
            DiagnosticLogger.event(
                "BV2_IMPORT",
                "ok model=${descriptor.id.stableValue} " +
                    "files=${manifest.files.size} revision=${manifest.revision}",
            )
            manifest
        } catch (e: Throwable) {
            // Atomically: discard the failed staging, restore backup if one
            // exists, then throw. Never leave a half-written pack or no pack
            // when a valid pack was present before the import.
            staging.deleteRecursively()
            packRoot.deleteRecursively()
            if (backup.isDirectory) {
                check(backup.renameTo(packRoot)) { "restore backup after failed import" }
            }
            throw e
        }
    }

    private fun extractAndReadManifest(zipStream: InputStream, staging: File): Manifest {
        var manifest: Manifest? = null
        ZipInputStream(zipStream).use { zin ->
            while (true) {
                val entry: ZipEntry = zin.nextEntry ?: break
                try {
                    if (entry.isDirectory) {
                        zin.closeEntry()
                        continue
                    }
                    val safePath = sanitiseEntryName(entry.name)
                        ?: throw ImportException("forbidden entry path: ${entry.name}")
                    val target = File(staging, safePath)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator) &&
                        target.canonicalPath != staging.canonicalPath
                    ) {
                        throw ImportException("path traversal detected: ${entry.name}")
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zin.copyTo(out) }
                    if (safePath == descriptor.manifestFileName) {
                        manifest = parseManifest(target)
                    }
                } finally {
                    zin.closeEntry()
                }
            }
        }
        return manifest ?: throw ImportException("missing manifest ${descriptor.manifestFileName}")
    }

    /**
     * Rejects absolute paths, drive letters (Windows-style), parent
     * traversal, and control characters. Returns null on rejection. Leading
     * slashes are stripped because the staged directory already provides the
     * root; this also stops `/etc/passwd` style entries.
     */
    internal fun sanitiseEntryName(name: String): String? {
        if (name.isEmpty()) return null
        if (name.contains('\u0000')) return null
        if (name.contains(":") && (name.indexOf(":") in 1..2)) return null // drive letters
        val normalised = name.replace('\\', '/')
        if (normalised.startsWith("/")) return null
        if (normalised.startsWith("..") || normalised.startsWith("./..") ||
            normalised.contains("/../") || normalised == ".." || normalised.endsWith("/..")
        ) return null
        return normalised
    }

    private fun parseManifest(file: File): Manifest {
        val text = file.readText()
        // Minimal JSON parser for our specific shape; avoids an extra
        // dependency. The manifest file is only ever written by the user /
        // upstream import tool, not the reader, so a permissive decoder is
        // acceptable.
        val revision = Regex("\"revision\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: throw ImportException("manifest.revision missing in ${file.name}")
        val fileBlocks = Regex(
            "\\{\\s*\"path\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)\\s*,\\s*\"sha256\"\\s*:\\s*\"([^\"]*)\"\\s*\\}",
        ).findAll(text).toList()
        val entries = fileBlocks.map { m ->
            ManifestEntry(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3])
        }
        return Manifest(revision, entries)
    }

    private inline fun <T> runBlockingImport(block: () -> T): T = block()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}