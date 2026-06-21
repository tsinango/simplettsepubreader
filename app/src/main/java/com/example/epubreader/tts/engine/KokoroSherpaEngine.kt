package com.example.epubreader.tts.engine

import android.content.Context
import android.os.SystemClock
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.KokoroModelDescriptor
import com.example.epubreader.tts.KokoroModelRegistry
import com.example.epubreader.tts.VitsModelManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * [EmbeddedTtsEngine] adapter around sherpa-onnx
 * [OfflineTtsKokoroModelConfig] for the Kokoro-82M `kokoro-multi-lang-v1_1`
 * pack. The engine is multi-speaker: the caller selects a speaker id at
 * synthesis time, so [synthesize] accepts the [sid] verbatim and forwards it
 * to sherpa's [GenerationConfig].
 *
 * Configuration choices:
 *
 *  - `dataDir` points at the `espeak-ng-data` directory inside the unpacked
 *    pack, which is required for Kokoro's G2P. sherpa-onnx v1.13.3 docs (PR
 *    #1942 and the Kokoro docs page) describe this layout exactly.
 *  - `lexicon` is set to the comma-separated list `lexicon-zh.txt,
 *    lexicon-us-en.txt, lexicon-gb-en.txt` so both Chinese and English are
 *    decodeable. The pack is a multi-lang model; Chinese is the primary
 *    use case so the Chinese lexicon comes first.
 *  - `lang` is left blank and the multi-lang paths in `dataDir` decide the
 *    language model from the text. Per sherpa docs there is no `lang` value
 *    to hardcode for the multi-lang-v1_1 pack.
 *  - `lengthScale` defaults to 1.0 — Kokoro's native speed linearly scales
 *    with lengthScale and there is no WNJ-style 0.85 calibration; the user
 *    speech rate slider multiplies `lengthScale` from a clean 1.0 base.
 *
 * The Kokoro pack does not require `ruleFsts` to be enabled by default, but
 * the docs note that enabling `date-zh.fst` / `number-zh.fst` /
 * `phone-zh.fst` improves Chinese-natural text handling. These FSTs are part
 * of the pack and we wire them at [OfflineTtsConfig.ruleFsts] exactly as the
 * docs examples do.
 */
class KokoroSherpaEngine(
    private val descriptor: KokoroModelDescriptor,
) : EmbeddedTtsEngine {
    override val id: String = descriptor.id.stableValue
    override val displayName: String = descriptor.id.displayName

    private var offlineTts: OfflineTts? = null
    private var initStartedAt: Long = 0L

    override fun isAvailable(context: Context): Boolean =
        VitsModelManager.isReady(context, descriptor)

    override fun initialize(context: Context, numThreads: Int) {
        check(offlineTts == null) { "KokoroSherpaEngine(${descriptor.id.stableValue}) already initialised" }
        initStartedAt = SystemClock.elapsedRealtime()
        val modelDir = VitsModelManager.modelDir(context, descriptor)
        val modelPath = File(modelDir, MODEL_ONNX).absolutePath
        val voicesPath = File(modelDir, VOICES_BIN).absolutePath
        val tokensPath = File(modelDir, TOKENS_TXT).absolutePath
        val dataDir = File(modelDir, ESPEAK_NG_DATA).absolutePath
        val lexicon = listOf(LEXICON_ZH, LEXICON_US_EN, LEXICON_GB_EN)
            .joinToString(",") { File(modelDir, it).absolutePath }
        val ruleFsts = listOf(PHONE_ZH_FST, NUMBER_ZH_FST, DATE_ZH_FST)
            .joinToString(",") { File(modelDir, it).absolutePath }
        val created: OfflineTts
        try {
            created = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelPath,
                            voices = voicesPath,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            lexicon = lexicon,
                            lengthScale = 1.0f,
                        ),
                        numThreads = numThreads,
                    ),
                    ruleFsts = ruleFsts,
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                ),
            )
        } catch (t: Throwable) {
            runCatching { offlineTts?.release() }
            offlineTts = null
            throw t
        }
        offlineTts = created
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created model=${descriptor.id.stableValue} kind=kokoro initMs=" +
                "${(SystemClock.elapsedRealtime() - initStartedAt).coerceAtLeast(1)} " +
                "threads=$numThreads " +
                "sampleRate=${descriptor.sampleRate} numSpecs=${descriptor.specs.size}",
        )
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        val engine = offlineTts ?: error("KokoroSherpaEngine(${descriptor.id.stableValue}) not initialised")
        val generated = engine.generateWithConfig(
            text,
            GenerationConfig(silenceScale = 0.2f, speed = speed, sid = sid),
        )
        if (descriptor.sampleRate > 0) {
            check(generated.sampleRate == descriptor.sampleRate) {
                "Kokoro engine returned sampleRate=${generated.sampleRate}; expected ${descriptor.sampleRate}"
            }
        }
        return SynthesizedAudio(generated.samples, generated.sampleRate)
    }

    override fun release() {
        val engine = offlineTts ?: return
        runCatching { engine.release() }
            .onSuccess { DiagnosticLogger.event("VITS_ENGINE", "released model=${descriptor.id.stableValue}") }
            .onFailure { DiagnosticLogger.error("VITS_ENGINE", "release_failed model=${descriptor.id.stableValue}", it) }
        offlineTts = null
    }

    private companion object {
        const val MODEL_ONNX = "model.onnx"
        const val VOICES_BIN = "voices.bin"
        const val TOKENS_TXT = "tokens.txt"
        const val ESPEAK_NG_DATA = "espeak-ng-data"
        const val LEXICON_ZH = "lexicon-zh.txt"
        const val LEXICON_US_EN = "lexicon-us-en.txt"
        const val LEXICON_GB_EN = "lexicon-gb-en.txt"
        const val PHONE_ZH_FST = "phone-zh.fst"
        const val NUMBER_ZH_FST = "number-zh.fst"
        const val DATE_ZH_FST = "date-zh.fst"
    }
}