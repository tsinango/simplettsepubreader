package com.example.epubreader.tts.engine

import android.content.Context
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.BertVits2MnnPackDescriptor
import com.example.epubreader.tts.VitsModelManager
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * [EmbeddedTtsEngine] adapter for Bert-VITS2-MNN packs.
 *
 * At runtime, [initialize] reflectively loads the upstream entry class
 * `com.example.bertvits2_infer_wrapper.impl.BertVITS2SimpleInferImpl`.
 * This matches the source at tag v2.0.0 / commit 346bc84:
 *
 * ```kotlin
 * class BertVITS2SimpleInferImpl(context: Context) {
 *     suspend fun init()
 *     fun setAudioLengthScale(scale: Float)
 *     suspend fun infer(text: String, spkName: String): Pair<FloatArray?, Int>?
 *     fun release()
 * }
 * ```
 *
 * When the AAR is absent, [initialize] throws a clear
 * [IllegalStateException] (instead of `UnsatisfiedLinkError`) so the TTS
 * service falls back to WNJ / system TTS without crashing.
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
        val constructor = try {
            cls.getConstructor(Context::class.java)
        } catch (_: Throwable) {
            throw IllegalStateException(
                "Bert-VITS2-MNN AAR 入口签名不匹配。预期 $BERT_VITS2_INFER_IMPL_FQN" +
                    "(Context)。请确认使用上游 v2.0.0 的 bertvits2-infer-wrapper。",
            )
        }
        inferImpl = try {
            constructor.newInstance(context)
        } catch (t: Throwable) {
            throw IllegalStateException("Bert-VITS2-MNN 初始化失败：${t.message ?: t}", t)
        }
        runBlocking(Dispatchers.Default) {
            val initMethod = requireNotNull(inferImpl).javaClass.getMethod(
                "init", Continuation::class.java,
            )
            val deferred = CompletableDeferred<Unit>()
            val cont = object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    result.fold({ deferred.complete(Unit) }, { deferred.completeExceptionally(it) })
                }
            }
            val ret = initMethod.invoke(inferImpl, cont)
            if (ret !== COROUTINE_SUSPENDED) deferred.complete(Unit)
            deferred.await()
        }
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created model=${descriptor.id.stableValue} kind=bv2_mnn threads=$numThreads",
        )
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        val infer = inferImpl ?: error("BertVits2MnnEngine(${descriptor.id.stableValue}) not initialised")
        val pair = runBlocking(Dispatchers.Default) {
            try {
                infer.javaClass.getMethod(
                    "setAudioLengthScale", Float::class.javaPrimitiveType,
                ).invoke(infer, speed)
            } catch (_: NoSuchMethodException) { }
            val inferMethod = infer.javaClass.getMethod(
                "infer",
                String::class.java,
                String::class.java,
                Continuation::class.java,
            )
            @Suppress("UNCHECKED_CAST")
            val deferred = CompletableDeferred<Pair<FloatArray?, Int>?>()
            val cont = object : Continuation<Pair<FloatArray?, Int>?> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Pair<FloatArray?, Int>?>) {
                    result.fold({ deferred.complete(it) }, { deferred.completeExceptionally(it) })
                }
            }
            val ret = inferMethod.invoke(infer, text, sid.toString(), cont)
            if (ret !== COROUTINE_SUSPENDED) {
                deferred.complete(ret as? Pair<FloatArray?, Int>?)
            }
            deferred.await()
        } ?: error("Bert-VITS2-MNN infer returned null")
        val samples = pair.first ?: error("Bert-VITS2-MNN infer returned null samples")
        val rate = pair.second
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
        const val BERT_VITS2_INFER_IMPL_FQN =
            "com.example.bertvits2_infer_wrapper.impl.BertVITS2SimpleInferImpl"
    }
}
