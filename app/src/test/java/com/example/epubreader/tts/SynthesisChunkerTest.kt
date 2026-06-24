package com.example.epubreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val text = buildString { repeat(30) { append("第一部分内容，第二部分内容；") } }
        val chunks = SynthesisChunker.split("sentence", text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= SynthesisChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun longTextWithoutPunctuationUsesHardLimit() {
        val text = "字".repeat(401)
        val chunks = SynthesisChunker.split("sentence", text)
        assertEquals(listOf(80, 80, 80, 80, 80, 1), chunks.map { it.text.length })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun shortClausesStayTogetherForBetterProsody() {
        val text = "你好，今天的天气很好。我们出去走走吧！"
        val chunks = SynthesisChunker.split("key", text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single().text)
        assertEquals(350, chunks.single().pauseMs)
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun quotationNotSplitIntoSeparateChunk() {
        val text = "他说：“好的，我马上来。”"
        val chunks = SynthesisChunker.split("key", text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single().text)
    }

    @Test
    fun semicolonSplitsAfterMinimumLength() {
        val text = "第一，保持冷静；第二，检查问题；第三，解决问题。"
        val chunks = SynthesisChunker.split("key", text)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= SynthesisChunker.MAX_CHARS })
        assertTrue(chunks.none { it.text.isBlank() })
        chunks.forEach { chunk ->
            val last = chunk.text.last()
            if (last == '。') assertEquals(350, chunk.pauseMs)
        }
    }

    @Test
    fun pauseMsForStrongPunctuationIs350() {
        listOf('。', '！', '？', '!', '?').forEach { ch ->
            val pause = SynthesisChunker.pauseMsFor(ch)
            assertEquals(350, pause)
        }
    }

    @Test
    fun pauseMsForCommaIs130() {
        listOf('，', ',').forEach { ch ->
            assertEquals(130, SynthesisChunker.pauseMsFor(ch))
        }
    }

    @Test
    fun pauseMsForSemicolonIs220() {
        listOf('；', ';', '：', ':').forEach { ch ->
            assertEquals(220, SynthesisChunker.pauseMsFor(ch))
        }
    }

    @Test
    fun pauseMsForChineseCommaIs80() {
        assertEquals(80, SynthesisChunker.pauseMsFor('、'))
    }

    @Test
    fun pauseMsForNonPunctuationIs40() {
        assertEquals(40, SynthesisChunker.pauseMsFor('字'))
    }

    @Test
    fun noEmptyChunksProduced() {
        val texts = listOf(
            "你好，今天的天气很好。我们出去走走吧！",
            "他说：“好的，我马上来。”",
            "第一，保持冷静；第二，检查问题；第三，解决问题。",
            "这是一个没有标点而且非常长的文本……",
        )
        texts.forEach { text ->
            val chunks = SynthesisChunker.split("key", text)
            assertTrue("Empty chunk in \"$text\"", chunks.none { it.text.isBlank() })
        }
    }

    @Test
    fun preservesOriginalTextWhenSplitting() {
        val texts = listOf(
            "你好，今天的天气很好。我们出去走走吧！",
            "他说：“好的，我马上来。”",
            "第一，保持冷静；第二，检查问题；第三，解决问题。",
            "这是一个没有标点而且非常长的文本……",
            "short text",
            buildString { repeat(20) { append("测试句子。") } },
        )
        texts.forEach { text ->
            val chunks = SynthesisChunker.split("key", text)
            assertEquals("Text mismatch for: $text", text, chunks.joinToString("") { it.text })
        }
    }

    @Test
    fun noChunkExceedsMaxChars() {
        val text = buildString { repeat(50) { append("测试用长文本，需要分割成多块。") } }
        val chunks = SynthesisChunker.split("key", text)
        assertTrue(chunks.all { it.text.length <= SynthesisChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertTrue(SynthesisChunker.split("key", "").isEmpty())
        assertTrue(SynthesisChunker.split("key", "  ").isEmpty())
    }

    @Test
    fun noPunctuationLongTextHas40msPauseOnForcedChunks() {
        val text = "字".repeat(250)
        val chunks = SynthesisChunker.split("key", text)
        assertTrue(chunks.size >= 3)
        val forcedChunks = chunks.dropLast(1)
        forcedChunks.forEach { chunk ->
            assertEquals(40, chunk.pauseMs)
        }
    }
}
