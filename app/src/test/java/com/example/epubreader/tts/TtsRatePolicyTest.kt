package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRatePolicyTest {
    @Test
    fun vitsSpeedIncreasesWithUserRate() {
        val slow = TtsRatePolicy.vitsSpeed(0.5f)
        val normal = TtsRatePolicy.vitsSpeed(1f)
        val fast = TtsRatePolicy.vitsSpeed(2f)

        assertTrue(slow < normal)
        assertTrue(normal < fast)
        assertEquals(normal * 0.5f, slow, 0.0001f)
        assertTrue(fast <= 1.5f)
    }

    @Test
    fun userRateIsLimitedToUiRange() {
        assertEquals(0.5f, TtsRatePolicy.userRate(0.1f), 0f)
        assertEquals(2f, TtsRatePolicy.userRate(10f), 0f)
    }
}
