package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PronunciationStoreTest {
    @Test
    fun parserIgnoresMalformedRulesAndUsesFirstEqualsAsSeparator() {
        val result = PronunciationStore.parse("重庆=重 庆\ninvalid\nA=B=C\n=empty")
        assertEquals(mapOf("重庆" to "重 庆", "A" to "B=C"), result)
    }
}
