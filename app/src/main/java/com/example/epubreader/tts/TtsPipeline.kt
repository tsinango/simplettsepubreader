package com.example.epubreader.tts

data class SynthesisChunk(
    val logicalSentenceKey: String,
    val index: Int,
    val text: String,
) {
    val key: String = "$logicalSentenceKey:$index"
}

object SynthesisChunker {
    const val TARGET_CHARS = 120
    const val MAX_CHARS = 160
    private const val MIN_BREAK_CHARS = 60
    private val preferredBreaks = charArrayOf('。', '！', '？', '；', '，', '.', '!', '?', ';', ',')

    fun split(logicalSentenceKey: String, text: String): List<SynthesisChunk> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        var remaining = normalized
        while (remaining.length > MAX_CHARS) {
            val boundary = findBoundary(remaining)
            parts += remaining.substring(0, boundary).trim()
            remaining = remaining.substring(boundary).trim()
        }
        if (remaining.isNotEmpty()) parts += remaining
        return parts.filter(String::isNotEmpty).mapIndexed { index, value ->
            SynthesisChunk(logicalSentenceKey, index, value)
        }
    }

    private fun findBoundary(value: String): Int {
        val preferredEnd = TARGET_CHARS.coerceAtMost(value.lastIndex)
        for (index in preferredEnd downTo MIN_BREAK_CHARS) {
            if (value[index] in preferredBreaks) return index + 1
        }
        for (index in (preferredEnd + 1)..MAX_CHARS.coerceAtMost(value.lastIndex)) {
            if (value[index] in preferredBreaks) return index + 1
        }
        return MAX_CHARS
    }
}

enum class TtsBackend { AUTO, QNN_HTP, CPU }

data class TtsPerformanceSnapshot(
    val requestedBackend: TtsBackend = TtsBackend.AUTO,
    val activeBackend: TtsBackend = TtsBackend.CPU,
    val cpuThreads: Int = 2,
    val generationMillis: Long = 0,
    val realTimeFactor: Float = 0f,
    val prefetchHitRate: Float = 0f,
    val gapMillis: Long = 0,
    val fallbackReason: String? = null,
)
