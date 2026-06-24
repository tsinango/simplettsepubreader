package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSynthesisPolicyTest {
    @Test
    fun modelProfilesDoNotApplyWnjCalibrationToMelo() {
        assertEquals(0.85f, TtsSynthesisProfiles.forModel(VitsModelId.FANCHEN_WNJ).baseSpeed)
        assertEquals(1f, TtsSynthesisProfiles.forModel(VitsModelId.MELO_TTS_ZH_EN).baseSpeed)
    }

    @Test
    fun normalizerCleansArtifactsAndSeparatesMixedLanguage() {
        assertEquals("你好 Chapter 1……开始，继续", TtsTextNormalizer.normalize("你好Chapter 1...开始，，继续"))
    }

    @Test
    fun replacementsUseLongestKeyFirst() {
        val result = TtsTextNormalizer.normalize("重庆银行", mapOf("重庆" to "重 庆", "重庆银行" to "重 庆 银 行"))
        assertEquals("重 庆 银 行", result)
    }

    @Test
    fun tailSilenceOnlyAddsMissingPause() {
        val rate = 1_000
        val audio = FloatArray(200) { if (it < 100) 0.5f else 0f }
        assertEquals(100, TailSilenceCompensator.requiredPaddingSamples(audio, rate, 200))
        assertTrue(TailSilenceCompensator.requiredPaddingSamples(audio, rate, 50) == 0)
    }
}
