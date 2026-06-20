package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisChunkerTest {
    @Test
    fun shortSentenceRemainsOneChunk() {
        val chunks = SynthesisChunker.split("chapter:0:0", "这是一句短句。")

        assertEquals(listOf("这是一句短句。"), chunks.map { it.text })
        assertEquals("chapter:0:0:0", chunks.single().key)
    }

    @Test
    fun longSentenceUsesPunctuationAndPreservesText() {
        val text = buildString {
            repeat(30) { append("第一部分内容，第二部分内容；") }
        }

        val chunks = SynthesisChunker.split("sentence", text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= SynthesisChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun longTextWithoutPunctuationUsesHardLimit() {
        val text = "字".repeat(401)

        val chunks = SynthesisChunker.split("sentence", text)

        assertEquals(listOf(160, 160, 81), chunks.map { it.text.length })
        assertEquals(text, chunks.joinToString(""))
    }
}
