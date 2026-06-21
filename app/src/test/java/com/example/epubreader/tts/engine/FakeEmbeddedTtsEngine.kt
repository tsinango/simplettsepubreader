package com.example.epubreader.tts.engine

import android.content.Context

/**
 * In-process [EmbeddedTtsEngine] used by unit tests to verify the engine
 * lifecycle owned by `EngineSwitchGate` and any caller of the engine interface
 * without touching JNI.
 *
 * The fake is intentionally verbose: it records every call in the order it
 * happened and lets tests pre-program both [isAvailable] answers and the
 * generated audio, while counting [release] invocations so race and switch
 * tests can assert that no double-release and no orphaned native handle is
 * left behind.
 */
class FakeEmbeddedTtsEngine(
    override val id: String,
    override val displayName: String = id,
    private val available: Boolean = true,
    private val audio: SynthesizedAudio = SynthesizedAudio(FloatArray(8) { 0f }, 22050),
    private val initThrows: Throwable? = null,
    private val synthThrows: Throwable? = null,
    private val onInit: (FakeEmbeddedTtsEngine, Int) -> Unit = { _, _ -> },
    private val onRelease: (FakeEmbeddedTtsEngine) -> Unit = { },
) : EmbeddedTtsEngine {

    val initCalls = mutableListOf<Int>()
    val synthCalls = mutableListOf<Triple<String, Int, Float>>()
    var releaseCalls = 0
    var initialized = false
    var released = false

    override fun isAvailable(context: Context): Boolean = available

    override fun initialize(context: Context, numThreads: Int) {
        if (initThrows != null) {
            released = false
            initialized = false
            throw initThrows
        }
        initCalls.add(numThreads)
        initialized = true
        released = false
        onInit(this, numThreads)
    }

    override fun synthesize(text: String, sid: Int, speed: Float): SynthesizedAudio {
        synthCalls.add(Triple(text, sid, speed))
        if (synthThrows != null) throw synthThrows
        check(initialized) { "FakeEmbeddedTtsEngine($id).synthesize called before initialize" }
        return audio
    }

    override fun release() {
        releaseCalls += 1
        initialized = false
        released = true
        onRelease(this)
    }

    companion object {
        /** Identity used by [EngineSwitchGateTest]; keeps the test factory call sites concise. */
        fun factory(
            id: String,
            available: Boolean = true,
            audio: SynthesizedAudio = SynthesizedAudio(FloatArray(8) { 0f }, 22050),
            initThrows: Throwable? = null,
            synthThrows: Throwable? = null,
            onInit: (FakeEmbeddedTtsEngine, Int) -> Unit = { _, _ -> },
            onRelease: (FakeEmbeddedTtsEngine) -> Unit = { },
        ): (String) -> EmbeddedTtsEngine = { desiredId ->
            FakeEmbeddedTtsEngine(
                id = desiredId,
                available = available,
                audio = audio,
                initThrows = initThrows,
                synthThrows = synthThrows,
                onInit = onInit,
                onRelease = onRelease,
            )
        }
    }
}