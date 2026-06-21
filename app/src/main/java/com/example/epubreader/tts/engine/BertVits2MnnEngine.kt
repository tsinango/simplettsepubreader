package com.example.epubreader.tts.engine

import android.content.Context
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.BertVits2MnnPackDescriptor
import com.example.epubreader.tts.VitsModelManager
import java.io.File

/**
 * [EmbeddedTtsEngine] adapter for Bert-VITS2-MNN packs.
 *
 * Why this is a *skeleton*: the upstream project
 * https://github.com/Voine/Bert-VITS2-MNN publishes the inference runtime as a
 * multi-AAR set built with MNN native libraries (see `PUBLISHING.md` in the
 * upstream repo). Those AARs cannot be produced inside this CI workflow — the
 * build requires MNN, a working NDK + cppjieba + tokenizers-cpp + openjtalk
 * submodules. Per the agreed scope this commit therefore lands only the
 * Kotlin adapter and the build wiring that lets a downstream contributor drop
 * the locally-built `bertvits2-infer-wrapper.aar` into `app/libs/` and
 * re-build. See `docs/bert-vits2-mnn-integration.md`.
 *
 * The engine intentionally avoids any compile-time reference to the BV2 AAR
 * classes. At runtime, [initialize] reflectively loads the upstream entry
 * class and, if absent, throws an [IllegalStateException] with a precise
 * instruction string instead of letting the JVM raise an
 * `UnsatisfiedLinkError`. This keeps BV2 selectable on the UI model card
 * without crashing devices where the AAR is not packaged.
 *
 * File-path adapter: upstream `BertVITS2SimpleInferImpl` historically expects
 * an `AssetManager` for its MNN modules, distilled BERT, tokenizer and
 * cppjieba dictionary. The driver therefore persists model bytes under the
 * app's `filesDir` models/<pack-dir> and wraps the AAR's `AssetManager.open`
 * path through the minimal [FileAssetManagerAdapter] shipped in this repo. The
 * adapter is purposefully thin and the only purpose it serves is turning a
 * downloaded directory into something that looks like an `AssetManager.open`
 * call from the AAR's perspective; whether the upstream AAR actually accepts
 * this adapter end-to-end is left to the device verification step described
 * in the report (RESIDUAL RISK).
 */
class BertVits2MnnEngine(
    private val descriptor: BertVits2MnnPackDescriptor,
) : EmbeddedTtsEngine {
    override val id: String = descriptor.id.stableValue
    override val displayName: String = descriptor.id.displayName

    private var inferImpl: Any? = null
    private var sampleRateReported: Int = descriptor.sampleRate

    override fun isAvailable(context: Context): Boolean =
        VitsModelManager.isReady(context, descriptor)

    override fun initialize(context: Context, numThreads: Int) {
        check(inferImpl == null) { "BertVits2MnnEngine(${descriptor.id.stableValue}) already initialised" }
        val markerReady = VitsModelManager.isReady(context, descriptor)
        if (!markerReady) {
            throw IllegalStateException(
                "Bert-VITS2-MNN 尚未导入模型包，请先在设置中选择本地 ZIP 导入。",
            )
        }
        val cls = try {
            Class.forName(BERT_VITS2_INFER_IMPL_FQN)
        } catch (_: Throwable) {
            val msg = "未检测到 Bert-VITS2-MNN 推理 AAR (bertvits2-infer-wrapper)。请按 " +
                "docs/bert-vits2-mnn-integration.md 构建并把它放入 app/libs/，再重新打包安装。" +
                " 已自动回退到 WNJ/系统 TTS，本句不会重复初始化失败模型。"
            DiagnosticLogger.event("BV2_ENGINE", "aar_missing class=$BERT_VITS2_INFER_IMPL_FQN")
            throw IllegalStateException(msg)
        }
        val packRoot = VitsModelManager.modelDir(context, descriptor)
        val packDir = packRoot.absolutePath
        val assetManager = FileAssetManagerFactory.create(packRoot)
            ?: throw IllegalStateException(
                "无法把 BV2 包目录暴露给 AssetManager（hidden API addAssetPath 失败）。请反馈详情到诊断日志。",
            )
        val constructor = try {
            cls.getConstructor(
                Class.forName("android.content.res.AssetManager"),
                String::class.java, // packRootPath
                Int::class.javaPrimitiveType, // numThreads
            )
        } catch (_: Throwable) {
            throw IllegalStateException(
                "Bert-VITS2-MNN AAR 入口签名不匹配（请确认使用上游 v2.0.0 的 " +
                    "bertvits2-infer-wrapper）。预期 $BERT_VITS2_INFER_IMPL_FQN" +
                    "(AssetManager, String, int)。",
            )
        }
        inferImpl = try {
            constructor.newInstance(assetManager, packDir, numThreads)
        } catch (t: Throwable) {
            throw IllegalStateException("Bert-VITS2-MNN 初始化失败：${t.message ?: t}", t)
        }
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created model=${descriptor.id.stableValue} kind=bv2_mnn threads=$numThreads",
        )
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        val infer = inferImpl ?: error("BertVits2MnnEngine(${descriptor.id.stableValue}) not initialised")
        val generateMethod = infer.javaClass.getMethod(
            "infer",
            String::class.java, // text
            Int::class.javaPrimitiveType, // sid
            Float::class.javaPrimitiveType, // lengthScale / speed
        )
        val audioResult = generateMethod.invoke(infer, text, sid, speed)
            ?: error("Bert-VITS2-MNN infer returned null")
        val samplesField = audioResult.javaClass.getField("samples")
        val sampleRateField = audioResult.javaClass.getField("sampleRate")
        @Suppress("UNCHECKED_CAST")
        val samples = samplesField.get(audioResult) as FloatArray
        val rate = sampleRateField.get(audioResult) as Int
        sampleRateReported = rate
        return SynthesizedAudio(samples, rate)
    }

    override fun release() {
        inferImpl?.let { impl ->
            runCatching { impl.javaClass.getMethod("release").invoke(impl) }
            DiagnosticLogger.event("VITS_ENGINE", "released model=${descriptor.id.stableValue}")
        }
        inferImpl = null
    }

    private companion object {
        /**
         * Reflective target class. The upstream `BertVITS2SimpleInferImpl.kt`
         * lives at the package root `com.example.bertvits2mnn` inside the
         * `bertvits2-infer-wrapper` AAR. We intentionally do not hold a
         * compile-time reference so the project still builds and the model
         * selection UI does not crash when the AAR is not yet packaged (see
         * `docs/bert-vits2-mnn-integration.md`).
         */
        const val BERT_VITS2_INFER_IMPL_FQN = "com.example.bertvits2mnn.BertVITS2SimpleInferImpl"
    }
}