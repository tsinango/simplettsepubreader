package com.example.epubreader.tts.engine

/**
 * Encapsulates the small amount of mutable state shared by an
 * [EmbeddedTtsEngine] and its caller that drives the engine lifecycle: which
 * engine is currently instantiated, and whether that engine has been
 * successfully initialised for the active model identity.
 *
 * The point of pulling this out of the TTS service is to make the
 * "switch model → release old → create new → mark built" dance, and the race
 * protection around concurrent release, fully unit-testable without the
 * surrounding Android Service. The caller is still responsible for the
 * synthesis mutex and dispatcher; [candidate], [markBuilt] and [release] are
 * not safe to call concurrently with themselves and rely on that outer mutex.
 *
 * Identity is compared via [EmbeddedTtsEngine.id]: switching from one model to
 * another with the same engine kind (e.g. WNJ → MeloTTS) is treated the same
 * as switching engine families (e.g. Sherpa VITS → Kokoro) — the previous
 * instance is always released first so at most one native handle is alive.
 */
class EngineSwitchGate {

    @Volatile private var engine: EmbeddedTtsEngine? = null
    @Volatile private var builtEngineId: String? = null

    /** The currently held engine (may be uninitalised). */
    val active: EmbeddedTtsEngine? get() = engine

    /** The id of the engine currently in the built state, or null. */
    val builtId: String? get() = builtEngineId

    /**
     * Returns the engine the caller should drive for [desired]. If the
     * currently held engine already matches the desired identity it is
     * returned as-is; otherwise the held engine is released and replaced by
     * the fresh instance produced by [factory]. The returned engine may be
     * uninitialised — call [markBuilt] after [EmbeddedTtsEngine.initialize]
     * succeeds.
     */
    fun <D> candidate(
        desired: D,
        desiredId: String,
        factory: (D) -> EmbeddedTtsEngine,
    ): EmbeddedTtsEngine {
        val existing = engine
        if (existing != null && existing.id == desiredId) return existing
        existing?.runCatching { release() }
        val fresh = factory(desired)
        engine = fresh
        builtEngineId = null
        return fresh
    }

    /**
     * Records that [engine] is now initialised and ready for inference.
     * Only updates state if [engine] is still the active instance, so a
     * release that happened on another path cannot mark a stale engine as
     * built.
     */
    fun markBuilt(engine: EmbeddedTtsEngine) {
        if (this.engine === engine) builtEngineId = engine.id
    }

    /** Whether [engine] is the active instance and is currently built. */
    fun isBuilt(engine: EmbeddedTtsEngine): Boolean =
        this.engine === engine && builtEngineId == engine.id

    /**
     * Releases the active engine and clears both fields. Safe to call more
     * than once; safe to call after [candidate] already released inside its
     * own body.
     */
    fun release() {
        val current = engine
        current?.runCatching { release() }
        engine = null
        builtEngineId = null
    }

    /**
     * Releases the active engine only if its id matches [id]. Used so that
     * cancelling or deleting one model cannot release an engine that the user
     * has since switched away from.
     */
    fun releaseIfId(id: String) {
        if (engine?.id == id) release()
    }
}