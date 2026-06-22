package com.example.epubreader.tts.engine

import android.content.Context
import com.example.bertvits2.BertVITS2JNI
import com.example.bertvits2_infer_wrapper.impl.BertVITS2SimpleInferImpl
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.tts.BertVits2MnnPackDescriptor
import com.example.epubreader.tts.VitsModelManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile

class BertVits2MnnEngine(
    private val descriptor: BertVits2MnnPackDescriptor,
) : EmbeddedTtsEngine {
    override val id: String = descriptor.id.stableValue
    override val displayName: String = descriptor.id.displayName

    private var inferImpl: BertVITS2SimpleInferImpl? = null
    private var speakerNames: List<String> = emptyList()

    // ---- Phase 2: backend / CPU thread configuration ----------------------------
    // Must be set BEFORE initialize() so the BV2 executor is constructed on the
    // right backend.  Defaults: backend = AUTO, threads = 6 (matches C++).
    //
    // The wrapper exposes both the raw native impl (BertVITS2JNI interface) and
    // a Kotlin enum (BertVits2Backend) -- the latter is what callers should use.
    @Volatile
    private var requestedBackend: BertVits2Backend = BertVits2Backend.AUTO

    @Volatile
    private var cpuThreads: Int = 6

    /**
     * Configure the BV2 backend BEFORE [initialize].  Idempotent; calling after
     * init has no effect on the live engine (caller must [release] and re-init
     * to switch backends).
     */
    fun configureBackend(backend: BertVits2Backend, cpuThreads: Int = this.cpuThreads) {
        require(cpuThreads in 1..16) { "cpuThreads must be 1..16, got $cpuThreads" }
        this.requestedBackend = backend
        this.cpuThreads = cpuThreads
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "backend_configured model=${descriptor.id.stableValue} backend=$backend threads=$cpuThreads",
        )
    }

    /**
     * Read the backend the C++ engine actually activated (after AUTO/fallback).
     *
     * On Phase 1 (CPU-only) builds that don't export `getActiveBackend` this
     * returns the requested backend -- callers should treat it as "best
     * available information" and not rely on it for correctness.
     */
    fun activeBackend(): BertVits2Backend {
        val native = inferImpl ?: return requestedBackend
        // BertVITS2JNI is a stateless JNI class; its external methods all
        // operate on global native state, so a fresh instance reads the same
        // backend config that BertVITS2FullInferImpl set via its by-delegation
        // holder.  This avoids reflectively poking private lazy fields.
        val activeNativeId = try {
            BertVITS2JNI().getActiveBackend()
        } catch (e: UnsatisfiedLinkError) {
            // Phase 1 CPU-only libbertvits2.so -- no getActiveBackend symbol
            requestedBackend.nativeId
        }
        return runCatching { BertVits2Backend.fromNativeId(activeNativeId) }
            .getOrDefault(requestedBackend)
    }

    override fun isAvailable(context: Context): Boolean =
        VitsModelManager.isReady(context, descriptor)

    override fun initialize(context: Context, numThreads: Int) {
        check(inferImpl == null) { "BertVits2MnnEngine(${descriptor.id.stableValue}) already initialised" }
        val modelDir = VitsModelManager.modelDir(context, descriptor)
        if (!modelDir.exists()) {
            throw IllegalStateException("Bert-VITS2-MNN 模型目录不存在，请先下载模型包。")
        }

        // Extract ZIP if needed
        val extractedMarker = File(modelDir, ".extracted")
        if (!extractedMarker.isFile) {
            val zipFile = descriptor.specs.firstOrNull()?.let { spec ->
                File(modelDir, spec.name).takeIf { it.isFile }
            } ?: throw IllegalStateException("Bert-VITS2-MNN 模型ZIP文件未找到，请重新下载。")

            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val target = File(modelDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            zipFile.delete()
            extractedMarker.writeText("ok")
            DiagnosticLogger.event("VITS_ENGINE", "extracted_bv2_zip size=${zipFile.length()}")
        }

        // ---- Phase 2: push backend config into native BEFORE init() ------------
        // init() in BertVITS2SimpleInferImpl calls initBertVITS2Loader() which
        // constructs the BV2 Executor -- so setBackend/setCpuThreads must run
        // first.  BertVITS2JNI is stateless: its external methods all touch
        // the same global native state used by BertVITS2FullInferImpl, so a
        // fresh instance is enough.  UnsatisfiedLinkError on a Phase 1 CPU
        // build (no setBackend/setCpuThreads symbols) is caught and treated
        // as "no backend control -- use original CPU behaviour".
        var openclAvail = false
        try {
            val jni = BertVITS2JNI()
            jni.setCpuThreads(cpuThreads)
            jni.setBackend(requestedBackend.nativeId)
            openclAvail = jni.openclAvailable()
        } catch (e: UnsatisfiedLinkError) {
            DiagnosticLogger.event(
                "VITS_ENGINE",
                "backend_select_skipped model=${descriptor.id.stableValue} reason='${e.javaClass.simpleName}: ${e.message}' using_cpu_default",
            )
        }

        val impl = BertVITS2SimpleInferImpl(context, modelDir.absolutePath)
        runBlocking {
            val ok = impl.init()
            if (!ok) {
                throw IllegalStateException("Bert-VITS2-MNN 模型初始化失败，请尝试重新下载。")
            }
        }
        speakerNames = impl.getSpkNameList()
        inferImpl = impl

        val activeBackend = activeBackend()
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created model=${descriptor.id.stableValue} kind=bv2_mnn threads=$numThreads speakers=${speakerNames.size} " +
                "requestedBackend=$requestedBackend activeBackend=$activeBackend openClAvailable=$openclAvail",
        )
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        val infer = inferImpl ?: error("BertVits2MnnEngine(${descriptor.id.stableValue}) not initialised")
        val spkName = speakerNames.getOrNull(sid) ?: speakerNames.firstOrNull()
            ?: error("No speakers loaded for BV2 pack")
        infer.setAudioLengthScale(speed)
        val pair = runBlocking {
            infer.infer(text, spkName)
        } ?: error("Bert-VITS2-MNN infer returned null")
        val samples = pair.first ?: error("Bert-VITS2-MNN infer returned null samples")
        return SynthesizedAudio(samples, pair.second)
    }

    override fun release() {
        inferImpl?.let { impl ->
            runCatching { impl.release() }
            DiagnosticLogger.event("VITS_ENGINE", "released model=${descriptor.id.stableValue}")
        }
        inferImpl = null
        speakerNames = emptyList()
    }
}
