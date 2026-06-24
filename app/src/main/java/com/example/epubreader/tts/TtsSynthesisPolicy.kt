package com.example.epubreader.tts

import kotlin.math.abs

data class TtsSynthesisProfile(
    val baseSpeed: Float,
    val maxChunkChars: Int,
    val targetBufferMs: Long,
    val silenceScale: Float,
)

object TtsSynthesisProfiles {
    fun forModel(id: VitsModelId): TtsSynthesisProfile = when (id) {
        VitsModelId.FANCHEN_WNJ -> TtsSynthesisProfile(0.85f, 80, 2_000, 0.2f)
        VitsModelId.MELO_TTS_ZH_EN -> TtsSynthesisProfile(1f, 110, 2_500, 0.2f)
        VitsModelId.KOKORO_MULTI_ZH,
        VitsModelId.KOKORO_MULTI_ZH_INT8 -> TtsSynthesisProfile(1f, 140, 3_000, 0.2f)
        VitsModelId.BERT_VITS2_MNN_22K -> TtsSynthesisProfile(1f, 150, 4_000, 0.2f)
    }
}

/** Text-only cleanup. It never changes the EPUB text or persisted locator. */
object TtsTextNormalizer {
    private val whitespace = Regex("[\\t\\u00a0\\u3000 ]+")
    private val repeatedComma = Regex("[，,]{2,}")
    private val repeatedStops = Regex("[。]{2,}")
    private val asciiWord = Regex("[A-Za-z0-9]")

    fun normalize(text: String, replacements: Map<String, String> = emptyMap()): String {
        var value = text
            .replace("...", "……")
            .replace(repeatedStops, "……")
            .replace(repeatedComma, "，")
            .replace(whitespace, " ")
            .trim()
        replacements.entries
            .asSequence()
            .filter { it.key.isNotEmpty() }
            .sortedByDescending { it.key.length }
            .forEach { (source, spoken) -> value = value.replace(source, spoken) }
        return addMixedLanguageBoundaries(value)
    }

    private fun addMixedLanguageBoundaries(text: String): String {
        if (text.length < 2) return text
        val out = StringBuilder(text.length + 8)
        text.forEachIndexed { index, ch ->
            if (index > 0) {
                val previous = text[index - 1]
                val boundary = (previous.isCjk() && asciiWord.matches(ch.toString())) ||
                    (ch.isCjk() && asciiWord.matches(previous.toString()))
                if (boundary && previous != ' ' && ch != ' ') out.append(' ')
            }
            out.append(ch)
        }
        return out.toString()
    }

    private fun Char.isCjk(): Boolean = code in 0x3400..0x9fff
}

object TailSilenceCompensator {
    private const val SILENCE_THRESHOLD = 0.005f
    private const val WINDOW_MS = 10

    fun requiredPaddingSamples(samples: FloatArray, sampleRate: Int, targetPauseMs: Int): Int {
        if (sampleRate <= 0 || targetPauseMs <= 0) return 0
        val target = targetPauseMs * sampleRate / 1000
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        var silent = 0
        var end = samples.size
        while (end > 0) {
            val start = (end - window).coerceAtLeast(0)
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, abs(samples[i]))
            if (peak > SILENCE_THRESHOLD) break
            silent += end - start
            end = start
        }
        return (target - silent).coerceAtLeast(0)
    }
}
