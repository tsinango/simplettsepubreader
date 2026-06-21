package com.example.epubreader.tts.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.epubreader.tts.KokoroModelRegistry
import com.example.epubreader.tts.VitsModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Safety net for the Kokoro adapter without touching JNI; actual Kokoro
 * inference is exercised only on a device (see the per-device RTF report).
 */
@RunWith(RobolectricTestRunner::class)
class KokoroSherpaEngineTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun metadataExposesKokoroStableValues() {
        val engine = KokoroSherpaEngine(KokoroModelRegistry.kokoroMultiLangV1_1)
        assertEquals(VitsModelId.KOKORO_MULTI_ZH.stableValue, engine.id)
        assertEquals(VitsModelId.KOKORO_MULTI_ZH.displayName, engine.displayName)
    }

    @Test
    fun isAvailableReturnsFalseWhenModelFilesAreMissing() {
        val engine = KokoroSherpaEngine(KokoroModelRegistry.kokoroMultiLangV1_1)
        assertFalse(engine.isAvailable(context))
    }

    @Test
    fun releaseBeforeInitializeIsSafe() {
        val engine = KokoroSherpaEngine(KokoroModelRegistry.kokoroMultiLangV1_1)
        engine.release()
        engine.release()
    }

    @Test
    fun synthesizeBeforeInitializeThrows() {
        val engine = KokoroSherpaEngine(KokoroModelRegistry.kokoroMultiLangV1_1)
        try {
            engine.synthesize("你好", sid = 3, speed = 1f)
            fail("Expected IllegalStateException before initialize")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}