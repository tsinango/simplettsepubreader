package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedModelRegistryTest {

    @Test
    fun registryCoversAllEmbeddedModels() {
        val all = EmbeddedModelRegistry.all
        // WNJ, MeloTTS, KokoroMultiLangv1_1
        assertEquals(3, all.size)
        assertEquals(
            setOf(
                VitsModelId.FANCHEN_WNJ,
                VitsModelId.MELO_TTS_ZH_EN,
                VitsModelId.KOKORO_MULTI_ZH,
            ),
            all.map { it.id }.toSet(),
        )
    }

    @Test
    fun registryGroupsByEngineKind() {
        // VITS class includes WNJ + MeloTTS; Kokoro has its own engine kind.
        EmbeddedModelRegistry.all.forEach { d ->
            when (d.id) {
                VitsModelId.FANCHEN_WNJ, VitsModelId.MELO_TTS_ZH_EN ->
                    assertEquals(TtsEngineKind.SHERPA_VITS, d.engineKind)
                VitsModelId.KOKORO_MULTI_ZH ->
                    assertEquals(TtsEngineKind.SHERPA_KOKORO, d.engineKind)
            }
        }
    }

    @Test
    fun byStableValueResolvesEachEntryByStableString() {
        EmbeddedModelRegistry.all.forEach { d ->
            assertEquals(d, EmbeddedModelRegistry.byStableValue(d.id.stableValue))
        }
    }

    @Test
    fun byStableValueReturnsNullForUnknown() {
        assertNull(EmbeddedModelRegistry.byStableValue(null))
        assertNull(EmbeddedModelRegistry.byStableValue("non-existent-stable-id"))
    }

    @Test
    fun eachDescriptorHasUniqueDirWorkNameAndMarker() {
        val dirs = EmbeddedModelRegistry.all.map { it.dirName }
        val works = EmbeddedModelRegistry.all.map { it.workName }
        val markers = EmbeddedModelRegistry.all.map { it.readyMarkerName }
        assertEquals(dirs.size, dirs.toSet().size)
        assertEquals(works.size, works.toSet().size)
        assertEquals(markers.size, markers.toSet().size)
    }

    @Test
    fun backCompatResolverKeepsLegacyWnjMeloLookups() {
        // VitsModelRegistry is retained for callers that still rely on it.
        assertNotNull(VitsModelRegistry.byId(VitsModelId.FANCHEN_WNJ))
        assertNotNull(VitsModelRegistry.byId(VitsModelId.MELO_TTS_ZH_EN))
        assertTrue(EmbeddedModelRegistry.byId(VitsModelId.FANCHEN_WNJ) is VitsModelDescriptor)
        assertTrue(EmbeddedModelRegistry.byId(VitsModelId.MELO_TTS_ZH_EN) is VitsModelDescriptor)
        assertTrue(EmbeddedModelRegistry.byId(VitsModelId.KOKORO_MULTI_ZH) is KokoroModelDescriptor)
    }
}