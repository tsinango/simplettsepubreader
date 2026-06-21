package com.example.epubreader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsPerformanceStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs by lazy {
        context.getSharedPreferences("tts_performance", Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        prefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().apply()
    }

    @Test
    fun snapshotCarriesModelIdMatchingDescriptor() {
        val store = TtsPerformanceStore(context)
        val wnjSnap = store.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue)
        val meloSnap = store.snapshot(VitsModelRegistry.MELO.revision, VitsModelRegistry.MELO.id.stableValue)
        assertEquals(VitsModelRegistry.WNJ.id.stableValue, wnjSnap.modelId)
        assertEquals(VitsModelRegistry.MELO.id.stableValue, meloSnap.modelId)
        assertNotEquals(wnjSnap.modelId, meloSnap.modelId)
    }

    @Test
    fun metricsAreStoredPerModelRevision() {
        val store = TtsPerformanceStore(context)
        store.saveMetrics(
            modelRevision = VitsModelRegistry.WNJ.revision,
            engineInitMillis = 111L,
            firstAudioMillis = 222L,
            generationMillis = 333L,
            realTimeFactor = 0.55f,
            prefetchHitRate = 0.1f,
            gapMillis = 10L,
        )
        store.saveMetrics(
            modelRevision = VitsModelRegistry.MELO.revision,
            engineInitMillis = 999L,
            firstAudioMillis = 888L,
            generationMillis = 777L,
            realTimeFactor = 0.88f,
            prefetchHitRate = 0.2f,
            gapMillis = 20L,
        )
        val wnj = store.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue)
        val melo = store.snapshot(VitsModelRegistry.MELO.revision, VitsModelRegistry.MELO.id.stableValue)
        assertEquals(0.55f, wnj.realTimeFactor, 0.0001f)
        assertEquals(0.88f, melo.realTimeFactor, 0.0001f)
        assertEquals(111L, wnj.engineInitMillis)
        assertEquals(999L, melo.engineInitMillis)
    }

    @Test
    fun legacyUnsuffixedMetricsMigrateToWnjRevisionKeys() {
        // Pre-multi-model versions stored a single WNJ profile under bare keys.
        prefs.edit()
            .putFloat("rtf", 0.42f)
            .putLong("engine_init_ms", 100L)
            .putLong("first_audio_ms", 200L)
            .putLong("generation_ms", 300L)
            .putFloat("prefetch_hit_rate", 0.3f)
            .putLong("gap_ms", 15L)
            .apply()
        val store = TtsPerformanceStore(context)
        val wnj = store.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue)
        assertEquals(0.42f, wnj.realTimeFactor, 0.0001f)
        assertEquals(100L, wnj.engineInitMillis)
        assertEquals(200L, wnj.firstAudioMillis)
        // Legacy bare keys must be removed so they do not leak into other models.
        assertNull(prefs.all["rtf"])
        assertNull(prefs.all["engine_init_ms"])
    }

    @Test
    fun legacyMigrationDoesNotOverwriteExistingRevisionKeyedMetrics() {
        prefs.edit()
            .putFloat("rtf", 0.42f)
            .putFloat("rtf:${VitsModelRegistry.WNJ.revision}", 0.99f)
            .apply()
        TtsPerformanceStore(context)
        val store = TtsPerformanceStore(context)
        val wnj = store.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue)
        assertEquals(0.99f, wnj.realTimeFactor, 0.0001f)
    }

    @Test
    fun wnjMetricsDoNotAppearUnderMeloRevision() {
        val store = TtsPerformanceStore(context)
        store.saveMetrics(
            modelRevision = VitsModelRegistry.WNJ.revision,
            engineInitMillis = 111L,
            firstAudioMillis = 222L,
            generationMillis = 333L,
            realTimeFactor = 0.55f,
            prefetchHitRate = 0.1f,
            gapMillis = 10L,
        )
        val melo = store.snapshot(VitsModelRegistry.MELO.revision, VitsModelRegistry.MELO.id.stableValue)
        assertEquals(0L, melo.engineInitMillis)
        assertEquals(0f, melo.realTimeFactor, 0.0001f)
    }

    @Test
    fun snapshotModelIdGuardDistinguishesModels() {
        // Encodes the guard used by the UI: a WNJ snapshot must not be shown
        // under a Melo descriptor (and vice versa).
        val store = TtsPerformanceStore(context)
        store.saveMetrics(
            modelRevision = VitsModelRegistry.WNJ.revision,
            engineInitMillis = 111L,
            firstAudioMillis = 222L,
            generationMillis = 333L,
            realTimeFactor = 0.55f,
            prefetchHitRate = 0.1f,
            gapMillis = 10L,
        )
        val perf = store.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue)
        assertTrue(perf.modelId == VitsModelRegistry.WNJ.id.stableValue)
        assertTrue(perf.modelId != VitsModelRegistry.MELO.id.stableValue)
    }
}
