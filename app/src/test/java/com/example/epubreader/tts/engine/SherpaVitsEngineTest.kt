package com.example.epubreader.tts.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.epubreader.tts.VitsModelRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the safety invariants of [SherpaVitsEngine] that do not require
 * loading the sherpa-onnx native library:
 *
 * - Constructing an engine never touches JNI (so the settings UI can hold one
 *   purely to ask [EmbeddedTtsEngine.isAvailable]).
 * - [EmbeddedTtsEngine.release] is safe before [EmbeddedTtsEngine.initialize];
 *   a not-yet-built engine must not crash when the TTS service tears it down.
 * - [EmbeddedTtsEngine.synthesize] before [EmbeddedTtsEngine.initialize]
 *   throws `IllegalStateException` so pipelines surface the bug instead of
 *   silently emitting a zero-length PCM buffer.
 *
 * Any test that actually calls [SherpaVitsEngine.initialize] would attempt to
 * `System.loadLibrary("sherpa-onnx-jni")`, which has no implementation in the
 * JVM unit-test classpath. Those scenarios are covered by the device tests in
 * `app/src/androidTest` instead and are explicitly NOT exercised here.
 */
@RunWith(RobolectricTestRunner::class)
class SherpaVitsEngineTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun isAvailableReturnsFalseWhenModelFilesAreMissing() {
        val engine = SherpaVitsEngine(VitsModelRegistry.WNJ)
        // No files have been laid down in Robolectric's filesDir.
        assertFalse(engine.isAvailable(context))
    }

    @Test
    fun releaseBeforeInitializeIsSafe() {
        val engine = SherpaVitsEngine(VitsModelRegistry.WNJ)
        // Must not throw.
        engine.release()
        engine.release()
    }

    @Test
    fun synthesizeBeforeInitializeThrows() {
        val engine = SherpaVitsEngine(VitsModelRegistry.WNJ)
        try {
            engine.synthesize("hello", sid = 0, speed = 1f)
            fail("Expected IllegalStateException before initialize")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun metadataExposesDescriptorValues() {
        val engine = SherpaVitsEngine(VitsModelRegistry.WNJ)
        assertTrue(engine.id == VitsModelRegistry.WNJ.id.stableValue)
        assertTrue(engine.displayName == VitsModelRegistry.WNJ.id.displayName)
    }
}