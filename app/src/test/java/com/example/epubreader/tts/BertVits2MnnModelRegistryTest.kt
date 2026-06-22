package com.example.epubreader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("v1", pack.revision)
        assertEquals(".ready-bv2-22k-v1", pack.readyMarkerName)
        assertEquals(22_050, pack.sampleRate)
        assertEquals(1, pack.minManifestEntryCount)
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
    fun assetUrlReturnsDownloadUrl() {
        val pack = BertVits2MnnModelRegistry.bertVits2Mnn22k
        val url = pack.assetUrl(pack.specs.first())
        assertTrue(url.startsWith("https://github.com/"))
        assertTrue(url.endsWith(".zip"))
    }
}