package com.example.epubreader.tts.engine

import android.content.Context
import android.os.SystemClock
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.VitsModelDescriptor
import com.example.epubreader.tts.VitsModelManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * [EmbeddedTtsEngine] adapter around sherpa-onnx [OfflineTts] for the existing
 * single-speaker Chinese VITS family (WNJ and MeloTTS-zh_en).
 *
 * Construction is cheap and never touches JNI: the sherpa-onnx `OfflineTts`
 * static initialiser only loads `sherpa-onnx-jni` when the class is first
 * referenced, which happens lazily inside [initialize]. This lets the UI build
 * an instance purely to call [isAvailable] without risk of an
 * `UnsatisfiedLinkError` on devices that never actually run inference.
 *
 * The configuration mirrors the historical setup that lived inline in
 * `ReaderTtsService.embeddedTts()` so behaviour — silenceScale 0.2,
 * maxNumSentences 1, sid 0, the exact file paths and the diagnostic log line —
 * is preserved verbatim. Only the JNI entry point moves home.
 */
class SherpaVitsEngine(
    private val descriptor: VitsModelDescriptor,
) : EmbeddedTtsEngine {

    override val id: String = descriptor.id.stableValue
    override val displayName: String = descriptor.id.displayName

    private var offlineTts: OfflineTts? = null
    private var initStartedAt: Long = 0L

    override fun isAvailable(context: Context): Boolean =
        VitsModelManager.isReady(context, descriptor)

    override fun initialize(context: Context, numThreads: Int) {
        check(offlineTts == null) { "SherpaVitsEngine(${descriptor.id.stableValue}) already initialised" }
        initStartedAt = SystemClock.elapsedRealtime()
        val modelDir = VitsModelManager.modelDir(context, descriptor)
        val fstPaths = descriptor.ruleFstFileNames.joinToString(",") { name ->
            File(modelDir, name).absolutePath
        }
        val modelPath = File(modelDir, descriptor.onnxFileName).absolutePath
        val tokensPath = File(modelDir, descriptor.tokensFileName).absolutePath
        val lexiconPath = File(modelDir, descriptor.lexiconFileName).absolutePath
        val created: OfflineTts
        try {
            created = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = modelPath,
                            tokens = tokensPath,
                            lexicon = lexiconPath,
                        ),
                        numThreads = numThreads,
                    ),
                    ruleFsts = fstPaths,
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                ),
            )
        } catch (t: Throwable) {
            // Make sure a half-constructed handle never lingers.
            runCatching { offlineTts?.release() }
            offlineTts = null
            throw t
        }
        offlineTts = created
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created model=${descriptor.id.stableValue} initMs=" +
                "${(SystemClock.elapsedRealtime() - initStartedAt).coerceAtLeast(1)} " +
                "threads=$numThreads ruleFsts=${descriptor.ruleFstFileNames.joinToString(",")} " +
                "maxNumSentences=1 silenceScale=0.2 " +
                "modelReady=${VitsModelManager.isReady(context, descriptor)} " +
                "modelBytes=${File(modelDir, descriptor.onnxFileName).length()} " +
                "tokensBytes=${File(modelDir, descriptor.tokensFileName).length()} " +
                "lexiconBytes=${File(modelDir, descriptor.lexiconFileName).length()}",
        )
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        val engine = offlineTts ?: error("SherpaVitsEngine(${descriptor.id.stableValue}) not initialised")
        val generated = engine.generateWithConfig(
            text,
            GenerationConfig(silenceScale = 0.2f, speed = speed, sid = sid),
        )
        return SynthesizedAudio(generated.samples, generated.sampleRate)
    }

    override fun release() {
        val engine = offlineTts ?: return
        runCatching { engine.release() }
            .onSuccess { DiagnosticLogger.event("VITS_ENGINE", "released model=${descriptor.id.stableValue}") }
            .onFailure { DiagnosticLogger.error("VITS_ENGINE", "release_failed model=${descriptor.id.stableValue}", it) }
        offlineTts = null
    }
}