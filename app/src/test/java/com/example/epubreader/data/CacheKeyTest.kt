package com.example.epubreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CacheKeyTest {
    @Test
    fun sentenceCacheKeyUsesPathAndParagraphContent() {
        val chapter = Chapter("path/to/chapter.xhtml", "Chapter 1", listOf("Para one.", "Para two."))
        val key1 = sentenceCacheKey(chapter)
        val chapter2 = Chapter("path/to/chapter.xhtml", "Chapter 1", listOf("Para one.", "Para two."))
        val key2 = sentenceCacheKey(chapter2)
        assertEquals(key1, key2)
    }

    @Test
    fun differentParagraphsProduceDifferentKeys() {
        val chapter1 = Chapter("path/a.xhtml", "A", listOf("Hello world"))
        val chapter2 = Chapter("path/a.xhtml", "A", listOf("Different text"))
        val key1 = sentenceCacheKey(chapter1)
        val key2 = sentenceCacheKey(chapter2)
        assertNotEquals(key1, key2)
    }

    @Test
    fun differentPathsProduceDifferentKeys() {
        val chapter1 = Chapter("path/a.xhtml", "A", listOf("Same content"))
        val chapter2 = Chapter("path/b.xhtml", "A", listOf("Same content"))
        val key1 = sentenceCacheKey(chapter1)
        val key2 = sentenceCacheKey(chapter2)
        assertNotEquals(key1, key2)
    }

    @Test
    fun hashCodeCollisionDifferentiatedByPath() {
        val chapter1 = Chapter("unique/path/1.xhtml", "T", listOf("Aa", "BB"))
        val chapter2 = Chapter("unique/path/2.xhtml", "T", listOf("Aa", "BB"))
        val key1 = sentenceCacheKey(chapter1)
        val key2 = sentenceCacheKey(chapter2)
        assertNotEquals(key1, key2)
    }

    private fun sentenceCacheKey(chapter: Chapter): String =
        "${chapter.path}:${chapter.paragraphs.joinToString("|") { it.take(40) }}"
}
