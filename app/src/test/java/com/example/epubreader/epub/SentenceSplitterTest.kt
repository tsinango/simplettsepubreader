package com.example.epubreader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun splitsChineseAndEnglishSentences() {
        val result = SentenceSplitter.split("第一句。第二句！ Hello world. Last one?")
        assertTrue(result.size >= 4)
        assertEquals("第一句。", result.first())
    }

    @Test
    fun limitsVeryLongSegments() {
        val result = SentenceSplitter.split("字".repeat(8000))
        assertTrue(result.all { it.length <= 3501 })
        assertEquals(8000, result.sumOf(String::length))
    }

    @Test
    fun ignoresWhitespaceOnlyText() {
        assertTrue(SentenceSplitter.split(" \n\t ").isEmpty())
    }
}
