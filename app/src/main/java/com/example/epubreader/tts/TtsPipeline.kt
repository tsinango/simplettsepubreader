package com.example.epubreader.tts

data class SynthesisChunk(
    val logicalSentenceKey: String,
    val index: Int,
    val text: String,
    val pauseMs: Int = 0,
) {
    val key: String = "$logicalSentenceKey:$index"
}

data class PauseConfig(
    val strongMs: Int = 350,
    val semicolonMs: Int = 220,
    val commaMs: Int = 130,
    val ideographicCommaMs: Int = 80,
    val defaultMs: Int = 40,
)

object SynthesisChunker {
    const val MAX_CHARS = 80
    private const val MIN_BREAK_CHARS = 15

    private val strongPunctuation = setOf('。', '！', '？', '!', '?')
    private val weakPunctuation = setOf('；', ';', '：', ':', '，', ',', '、')
    private val closingChars = setOf('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '」', '』', '）', '】', '》', '〉', '〕')

    fun pauseMsFor(ch: Char, config: PauseConfig = PauseConfig()): Int = when (ch) {
        '。', '！', '？', '!', '?' -> config.strongMs
        '；', ';', '：', ':' -> config.semicolonMs
        '，', ',' -> config.commaMs
        '、' -> config.ideographicCommaMs
        else -> config.defaultMs
    }

    fun split(logicalSentenceKey: String, text: String, config: PauseConfig = PauseConfig()): List<SynthesisChunk> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()

        val result = mutableListOf<SynthesisChunk>()
        val len = normalized.length
        var start = 0
        var i = 0

        while (i < len) {
            val ch = normalized[i]
            val charsSinceBreak = i - start

            when {
                ch in strongPunctuation -> {
                    val end = if (i + 1 < len && normalized[i + 1] in closingChars) i + 2 else i + 1
                    result.add(SynthesisChunk(logicalSentenceKey, result.size, normalized.substring(start, end), pauseMsFor(ch, config)))
                    start = end
                    i = end
                }
                ch in weakPunctuation && charsSinceBreak >= MIN_BREAK_CHARS -> {
                    result.add(SynthesisChunk(logicalSentenceKey, result.size, normalized.substring(start, i + 1), pauseMsFor(ch, config)))
                    start = i + 1
                    i = start
                }
                charsSinceBreak >= MAX_CHARS -> {
                    val searchStart = start + MIN_BREAK_CHARS
                    val breakPos = if (searchStart < i) findLastPunct(normalized, searchStart, i) else -1
                    if (breakPos >= searchStart) {
                        result.add(SynthesisChunk(logicalSentenceKey, result.size, normalized.substring(start, breakPos + 1), pauseMsFor(normalized[breakPos], config)))
                        start = breakPos + 1
                    } else {
                        result.add(SynthesisChunk(logicalSentenceKey, result.size, normalized.substring(start, i), config.defaultMs))
                        start = i
                    }
                    i = start
                }
                else -> {
                    i++
                }
            }
        }

        if (start < len) {
            val trailing = normalized.substring(start)
            result.add(SynthesisChunk(logicalSentenceKey, result.size, trailing, pauseMsFor(trailing.last(), config)))
        }

        return result
    }

    private fun findLastPunct(text: String, searchStart: Int, searchEnd: Int): Int {
        for (pos in (searchEnd - 1) downTo searchStart) {
            if (text[pos] in strongPunctuation || text[pos] in weakPunctuation) return pos
        }
        return -1
    }
}

object TtsRatePolicy {
    // The model's speed argument is a multiplier: larger values are faster. A fixed
    // calibration of 0.85 maps this voice's native pace to normal Chinese narration.
    const val CALIBRATED_NORMAL_SPEED = 0.85f

    fun userRate(value: Float): Float = value.coerceIn(0.5f, 2f)

    fun vitsSpeed(value: Float): Float =
        (CALIBRATED_NORMAL_SPEED * userRate(value)).coerceIn(0.25f, 1.5f)
}

data class TtsPerformanceSnapshot(
    val modelId: String = "",
    val cpuThreads: Int = 2,
    val engineInitMillis: Long = 0,
    val firstAudioMillis: Long = 0,
    val generationMillis: Long = 0,
    val realTimeFactor: Float = 0f,
    val prefetchHitRate: Float = 0f,
    val gapMillis: Long = 0,
)
