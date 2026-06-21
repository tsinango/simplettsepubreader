package com.example.epubreader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedModelSelectionStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: EmbeddedModelSelectionStore

    @Before
    fun setUp() {
        store = EmbeddedModelSelectionStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun speakerIdReturnsDefaultBeforePersist() {
        assertEquals(
            KokoroModelRegistry.DEFAULT_CHINESE_FEMALE_SID,
            store.speakerId(VitsModelId.KOKORO_MULTI_ZH.stableValue, KokoroModelRegistry.DEFAULT_CHINESE_FEMALE_SID),
        )
        assertEquals(0, store.speakerId(VitsModelId.FANCHEN_WNJ.stableValue, 0))
    }

    @Test
    fun speakerIdPersistsPerModelAndDoesNotBleedAcrossEngines() {
        store.setSpeakerId(VitsModelId.KOKORO_MULTI_ZH.stableValue, sid = 88)
        store.setSpeakerId(VitsModelId.FANCHEN_WNJ.stableValue, sid = 0)
        // Kokoro selection must persist independently of WNJ/Melo defaults.
        assertEquals(88, store.speakerId(VitsModelId.KOKORO_MULTI_ZH.stableValue, default = -1))
        assertEquals(0, store.speakerId(VitsModelId.FANCHEN_WNJ.stableValue, default = -1))
        // MeloTTS default sid untouched.
        assertEquals(0, store.speakerId(VitsModelId.MELO_TTS_ZH_EN.stableValue, default = 0))
    }

    @Test
    fun ratePersistsPerModel() {
        store.setRate(VitsModelId.KOKORO_MULTI_ZH.stableValue, rate = 1.3f)
        store.setRate(VitsModelId.FANCHEN_WNJ.stableValue, rate = 0.85f)
        assertEquals(1.3f, store.rate(VitsModelId.KOKORO_MULTI_ZH.stableValue, default = 1f), 0.0001f)
        assertEquals(0.85f, store.rate(VitsModelId.FANCHEN_WNJ.stableValue, default = 1f), 0.0001f)
        assertNotEquals(
            store.rate(VitsModelId.KOKORO_MULTI_ZH.stableValue, default = 1f),
            store.rate(VitsModelId.FANCHEN_WNJ.stableValue, default = 1f),
        )
        assertTrue(store.rate(VitsModelId.MELO_TTS_ZH_EN.stableValue, default = 1f) == 1f)
    }

    @Test
    fun clearWipesEverything() {
        store.setSpeakerId(VitsModelId.KOKORO_MULTI_ZH.stableValue, sid = 5)
        store.clear()
        assertEquals(3, store.speakerId(VitsModelId.KOKORO_MULTI_ZH.stableValue, default = 3))
    }
}