package com.example.epubreader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.epubreader.tts.bertvits2.BertVits2MnnPackImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BertVits2MnnModelRegistryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun before() {
        val dir = java.io.File(context.filesDir, BertVits2MnnModelRegistry.bertVits2Mnn22k.dirName)
        dir.deleteRecursively()
    }

    @Test
    fun registryExposesBv2Pack() {
        val pack = BertVits2MnnModelRegistry.bertVits2Mnn22k
        assertEquals(VitsModelId.BERT_VITS2_MNN_22K, pack.id)
        assertEquals(TtsEngineKind.BERT_VITS2_MNN, pack.engineKind)
        assertEquals("346bc84", pack.revision)
        assertEquals("Voine/Bert-VITS2-MNN", pack.huggingFaceRepo)
        assertEquals(".ready-bv2-22k-v1", pack.readyMarkerName)
        assertEquals(22_050, pack.sampleRate)
        assertEquals("bv2-pack-manifest.json", pack.manifestFileName)
        assertTrue(
            "license mentions Apache and non-commercial combination",
            pack.license.contains("Apache"),
        )
        assertTrue(pack.license.contains("非商业") || pack.license.contains("non-commercial", ignoreCase = true))
    }

    @Test
    fun packNotAvailableWhenMarkerMissing() {
        val pack = BertVits2MnnModelRegistry.bertVits2Mnn22k
        val dir = java.io.File(context.filesDir, pack.dirName).apply { mkdirs() }
        assertTrue(dir.isDirectory)
        assertFalse(VitsModelManager.isReady(context, pack))
    }

    @Test
    fun assetUrlThrowsForBv2() {
        val pack = BertVits2MnnModelRegistry.bertVits2Mnn22k
        try {
            pack.assetUrl(ModelFileSpec("anything", 0L, ""))
            error("expected assetUrl to throw for BV2")
        } catch (_: IllegalStateException) {
            // expected: BV2 packs are import-only; no HTTP URL is defined
        }
    }

    @Test
    fun importerRejectsTraversalEntries() {
        val pack = BertVits2MnnModelRegistry.bertVits2Mnn22k
        val importer = BertVits2MnnPackImporter(context, pack)
        assertNull(importer.sanitiseEntryName("/etc/passwd"))
        assertNull(importer.sanitiseEntryName("../../escape"))
        assertNull(importer.sanitiseEntryName("ok/../escape"))
        assertNull(importer.sanitiseEntryName("a/b/../../escape"))
        assertNull(importer.sanitiseEntryName("C:\\windows\\evil"))
        assertNull(importer.sanitiseEntryName("evil\u0000"))
        assertEquals("safe/path.bin", importer.sanitiseEntryName("safe/path.bin"))
        assertEquals("nested/dir/file.mnn", importer.sanitiseEntryName("nested/dir/file.mnn"))
        assertEquals("nested/win.bin", importer.sanitiseEntryName("nested\\win.bin"))
    }
}