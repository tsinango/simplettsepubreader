package com.example.epubreader.tts.engine

/**
 * BV2 MNN backend selection (Phase 2).
 *
 * Integer [nativeId] values MUST stay in sync with the C++ enum
 * `MNN_BERT_VITS2::Backend` in `bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.hpp`.
 *
 * CPU=0, OPENCL_ALL=1, OPENCL_DECODER=2, NNAPI=3, AUTO=4.
 *
 * Call [BertVits2MnnEngine.configureBackend] BEFORE [BertVits2MnnEngine.initialize]
 * so the BV2 executor constructed by `init_vits_loader()` lives on the right
 * backend. If OpenCL is requested but unavailable on the device, the C++ side
 * auto-falls back to CPU (see `fall_back_to_cpu_and_reload`) -- [ACTIVE] after
 * init may differ from the requested value.
 */
enum class BertVits2Backend(val nativeId: Int, val displayName: String) {
    CPU(0, "CPU (MNN_FORWARD_CPU, Precision_Low_BF16, Memory_Low)"),
    OPENCL_ALL(1, "OpenCL FP16 (full pipeline on GPU)"),
    OPENCL_DECODER(2, "OpenCL Decoder-only (BERT/Enc/DP/Flow on CPU)"),
    NNAPI(3, "NNAPI (MNN_FORWARD_NN)"),
    AUTO(4, "Auto (OpenCL when supported, else best CPU)");

    companion object {
        /** @throws IllegalArgumentException if [nativeId] is not a known backend. */
        fun fromNativeId(nativeId: Int): BertVits2Backend =
            entries.firstOrNull { it.nativeId == nativeId }
                ?: throw IllegalArgumentException("Unknown BV2 backend id: $nativeId")
    }
}