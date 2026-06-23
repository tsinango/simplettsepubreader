package com.example.bertvits2

interface IBertVITS2JNI {

    fun initBertVITS2Loader()

    fun setBertVITS2ModelPath(
        enc_model_path: String,
        dec_model_path: String,
        sdp_model_path: String,
        dp_model_path: String,
        emb_model_path: String,
        flow_model_path: String,
        bert_model_path: String,
    )

    fun destroyBertVITS2Loader()

    fun startAudioInfer(
        input_seq: IntArray,
        input_t: IntArray,
        input_language: IntArray,
        input_ids: IntArray,
        input_word2ph: IntArray,
        attention_mask: IntArray,
        spkid: Int,
    ): FloatArray?

    fun setAudioLengthScale(length_scale: Float)

    // ---- Phase 2: backend selection --------------------------------------------
    // Backend ids MUST stay in sync with the C++ enum `MNN_BERT_VITS2::Backend`
    // (bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.hpp).
    // CPU=0, OPENCL_ALL=1, OPENCL_DECODER=2, NNAPI=3, AUTO=4
    //
    // Call setBackend BEFORE initBertVITS2Loader() so the executor that
    // gets constructed matches the requested backend.
    fun setBackend(backend: Int)

    fun getActiveBackend(): Int

    fun getRequestedBackend(): Int

    fun openclAvailable(): Boolean

    fun setCpuThreads(threads: Int)

    // ---- Phase 3: benchmark harness (P0d) ------------------------------------
    // Runs start_audio_infer in a warmup + bench C++ loop.
    // Same input arrays as startAudioInfer; returns timing via logcat only.
    fun bv2RunBenchmark(
        backend: Int,
        cpuThreads: Int,
        warmupIters: Int,
        benchIters: Int,
        input_seq: IntArray,
        input_t: IntArray,
        input_language: IntArray,
        input_ids: IntArray,
        input_word2ph: IntArray,
        attention_mask: IntArray,
        spkid: Int,
    )
}

// Concrete implementation that keeps the native bindings.
// Keep the class name `BertVITS2JNI` so existing JNI C++ functions match the JVM name.
class BertVITS2JNI : IBertVITS2JNI {

    external override fun initBertVITS2Loader()

    external override fun setBertVITS2ModelPath(
        enc_model_path: String,
        dec_model_path: String,
        sdp_model_path: String,
        dp_model_path: String,
        emb_model_path: String,
        flow_model_path: String,
        bert_model_path: String,
    )

    external override fun destroyBertVITS2Loader()

    external override fun startAudioInfer(
        input_seq: IntArray,
        input_t: IntArray,
        input_language: IntArray,
        input_ids: IntArray,
        input_word2ph: IntArray,
        attention_mask: IntArray,
        spkid: Int
    ): FloatArray?

    external override fun setAudioLengthScale(length_scale: Float)

    // ---- Phase 2: backend selection --------------------------------------------
    external override fun setBackend(backend: Int)

    external override fun getActiveBackend(): Int

    external override fun getRequestedBackend(): Int

    external override fun openclAvailable(): Boolean

    external override fun setCpuThreads(threads: Int)

    external override fun bv2RunBenchmark(
        backend: Int,
        cpuThreads: Int,
        warmupIters: Int,
        benchIters: Int,
        input_seq: IntArray,
        input_t: IntArray,
        input_language: IntArray,
        input_ids: IntArray,
        input_word2ph: IntArray,
        attention_mask: IntArray,
        spkid: Int,
    )

    companion object {
        // Used to load the 'bertvits2' library on application startup.
        //
        // Phase 2 (OpenCL): with MNN_SEP_BUILD=OFF the Express symbols are
        // baked into libMNN.so and there is no separate libMNN_Express.so on
        // disk -- the optional load below is tolerated via try/catch.
        // The Phase 1 (CPU) build still ships libMNN_Express.so, in which case
        // the load succeeds and no symbols are duplicated (Express symbols are
        // weak in libMNN.so and resolved to libMNN_Express.so first).
        init {
            try {
                System.loadLibrary("MNN_Express")
            } catch (_: UnsatisfiedLinkError) {
                // Phase 2 combined build: Express is in libMNN.so
            }
            System.loadLibrary("MNN")
            System.loadLibrary("bertvits2")
        }
    }
}