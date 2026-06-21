package com.example.epubreader.tts.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.epubreader.tts.BertVits2MnnModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bert-VITS2-MNN adapter skeleton safety net. The actual native AAR cannot
 * be produced in CI; see `docs/bert-vits2-mnn-integration.md` for the
 * production AAR build instructions to drop into `app/libs/`. These tests
 * lock down the contract that lets the app fall back cleanly when the AAR
 * is absent.
 */
@RunWith(RobolectricTestRunner::class)
class BertVits2MnnEngineTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun metadataExposesBv2StableId() {
        val engine = BertVits2MnnEngine(BertVits2MnnModelRegistry.bertVits2Mnn22k)
        assertEquals("BERT_VITS2_MNN_22K", engine.id)
        assertEquals("Bert-VITS2 MNN (22K 中文)", engine.displayName)
    }

    @Test
    fun releaseBeforeInitializeIsSafe() {
        val engine = BertVits2MnnEngine(BertVits2MnnModelRegistry.bertVits2Mnn22k)
        engine.release()
        engine.release()
    }

    @Test
    fun synthesizeBeforeInitializeThrows() {
        val engine = BertVits2MnnEngine(BertVits2MnnModelRegistry.bertVits2Mnn22k)
        try {
            engine.synthesize("hello", sid = 0, speed = 1f)
            fail("Expected IllegalStateException before initialize")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun isAvailableReturnsFalseWithoutMarker() {
        val engine = BertVits2MnnEngine(BertVits2MnnModelRegistry.bertVits2Mnn22k)
        assertFalse(engine.isAvailable(context))
    }

    @Test
    fun initializeWithoutMarkerThrowsReadableIllegalStateNotLinkError() {
        // The marker is absent → initialize should refuse with a clear
        // IllegalStateException (so the TTS service can fall back to WNJ /
        // system TTS without an UnsatisfiedLinkError). In CI the AAR would
        // also be absent; we only assert the marker path here because the
        // AAR-missing path is exercised under the no-marker branch above.
        val engine = BertVits2MnnEngine(BertVits2MnnModelRegistry.bertVits2Mnn22k)
        try {
            engine.initialize(context, numThreads = 2)
            fail("Expected IllegalStateException when marker is missing")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}