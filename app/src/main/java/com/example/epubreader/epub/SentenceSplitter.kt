package com.example.epubreader.epub

import java.text.BreakIterator
import java.util.Locale

object SentenceSplitter {
    private const val MAX_CHARS = 3500

    fun split(text: String): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(normalized)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            chunk(normalized.substring(start, end).trim(), result)
            start = end
            end = iterator.next()
        }
        if (result.isEmpty()) chunk(normalized, result)
        return result
    }

    private fun chunk(value: String, output: MutableList<String>) {
        var rest = value
        while (rest.length > MAX_CHARS) {
            val boundary = rest.lastIndexOfAny(charArrayOf('，', ',', '；', ';', ' '), MAX_CHARS)
                .takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
            output += rest.substring(0, boundary + 1).trim()
            rest = rest.substring(boundary + 1).trim()
        }
        if (rest.isNotEmpty()) output += rest
    }
}
