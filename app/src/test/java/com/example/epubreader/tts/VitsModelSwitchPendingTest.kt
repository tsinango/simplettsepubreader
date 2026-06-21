package com.example.epubreader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VitsModelSwitchPendingTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs by lazy {
        context.getSharedPreferences("vits_model", Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        prefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().apply()
    }

    private fun wnjManager() = VitsModelManager(context, VitsModelRegistry.WNJ)
    private fun meloManager() = VitsModelManager(context, VitsModelRegistry.MELO)

    @Test
    fun requestingSwitchOverwritesPreviousPendingTarget() {
        val wnj = wnjManager()
        val melo = meloManager()
        wnj.requestSwitchAfterDownload()
        assertEquals(VitsModelId.FANCHEN_WNJ, wnj.pendingSwitchTarget())
        melo.requestSwitchAfterDownload()
        // The most recent choice must win; WNJ's pending is superseded.
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, melo.pendingSwitchTarget())
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, wnj.pendingSwitchTarget())
    }

    @Test
    fun pendingTargetsSingleSharedIdAcrossManagers() {
        val wnj = wnjManager()
        val melo = meloManager()
        wnj.requestSwitchAfterDownload()
        assertEquals(VitsModelId.FANCHEN_WNJ, melo.pendingSwitchTarget())
        melo.requestSwitchAfterDownload()
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, wnj.pendingSwitchTarget())
    }

    @Test
    fun clearingPendingForOneModelDoesNotClobberAnother() {
        val wnj = wnjManager()
        val melo = meloManager()
        melo.requestSwitchAfterDownload()
        // Cancelling/deleting WNJ must not clear a pending target pointing at Melo.
        wnj.clearPendingSwitch(VitsModelId.FANCHEN_WNJ)
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, melo.pendingSwitchTarget())
    }

    @Test
    fun clearingPendingForMatchingModelRemovesIt() {
        val melo = meloManager()
        melo.requestSwitchAfterDownload()
        melo.clearPendingSwitch(VitsModelId.MELO_TTS_ZH_EN)
        assertNull(melo.pendingSwitchTarget())
    }

    @Test
    fun clearPendingUnconditionallyRemovesIt() {
        val melo = meloManager()
        melo.requestSwitchAfterDownload()
        melo.clearPendingSwitch()
        assertNull(melo.pendingSwitchTarget())
    }

    @Test
    fun pendingIsNullByDefault() {
        assertNull(wnjManager().pendingSwitchTarget())
    }

    // ---- Pending switch race: A completes must not override B (issue 2) ----

    @Test
    fun selectingBWhileAIsDownloadingKeepsBAsPendingWhenACompletes() {
        val wnj = wnjManager()
        val melo = meloManager()
        // User starts downloading WNJ and wants to switch to it.
        wnj.requestSwitchAfterDownload()
        // User then changes mind and selects Melo; latest choice overwrites.
        melo.requestSwitchAfterDownload()
        // WNJ finishes first. The pending target is Melo, so the collector must
        // NOT switch to WNJ. This test encodes the guard the MainViewModel
        // collector relies on: pending != the model that became READY.
        val pending = wnj.pendingSwitchTarget()
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, pending)
        // Only the pending model (Melo) becoming READY should trigger a switch.
        assert(pending != VitsModelId.FANCHEN_WNJ)
    }

    // ---- Legacy switch_after_download key migration (issue 2 compat) ----

    @Test
    fun legacyWnjSwitchBooleanMigratesToPendingId() {
        prefs.edit().putBoolean("switch_after_download", true).apply()
        // Constructing the manager triggers migration.
        val wnj = wnjManager()
        assertEquals(VitsModelId.FANCHEN_WNJ, wnj.pendingSwitchTarget())
        // Old key must be cleaned up.
        assertNull(prefs.all["switch_after_download"])
    }

    @Test
    fun legacyPerModelSwitchBooleanMigratesToPendingId() {
        prefs.edit().putBoolean("switch_after_download-MELO_TTS_ZH_EN", true).apply()
        val melo = meloManager()
        assertEquals(VitsModelId.MELO_TTS_ZH_EN, melo.pendingSwitchTarget())
        assertNull(prefs.all["switch_after_download-MELO_TTS_ZH_EN"])
    }

    @Test
    fun legacySwitchKeysAreRemovedEvenWhenNoPendingCanBeDerived() {
        prefs.edit().putBoolean("switch_after_download-UNKNOWN_MODEL", true).apply()
        wnjManager()
        assertNull(prefs.all["switch_after_download-UNKNOWN_MODEL"])
        assertNull(wnjManager().pendingSwitchTarget())
    }
}
