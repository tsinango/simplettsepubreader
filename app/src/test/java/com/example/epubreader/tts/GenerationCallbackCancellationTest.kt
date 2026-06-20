package com.example.epubreader.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenerationCallbackCancellationTest {
    private data class CallbackState(
        @Volatile var playing: Boolean = true,
        @Volatile var activeEngineVits: Boolean = true,
        @Volatile var currentSerial: Int = 1,
    )

    private lateinit var scope: CoroutineScope
    private lateinit var state: CallbackState

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        state = CallbackState()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun isGenerationCurrent(serial: Int): Boolean =
        state.playing && state.activeEngineVits && serial == state.currentSerial

    private suspend fun simulateGenerate(
        serial: Int,
        onCallback: (Int) -> Unit = {},
    ): Int {
        val job = coroutineContext[Job]
        var lastCallbackResult = 1
        repeat(2000) {
            lastCallbackResult = if (job?.isActive == true && isGenerationCurrent(serial)) 1 else 0
            onCallback(lastCallbackResult)
            if (lastCallbackResult == 0) return lastCallbackResult
            delay(2)
        }
        return lastCallbackResult
    }

    @Test
    fun callbackReturnsOneWhileJobActiveAndSerialCurrent() = runBlocking {
        val deferred = scope.async { simulateGenerate(serial = 1) }
        val result = deferred.await()
        assertEquals(1, result)
    }

    @Test
    fun callbackReturnsZeroAfterDeferredCancelled() = runBlocking {
        val latch = CompletableDeferred<Unit>()
        val deferred = scope.async {
            latch.complete(Unit)
            simulateGenerate(serial = 1)
        }
        latch.await()
        delay(20)
        deferred.cancel()
        val cancelled = try {
            deferred.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(cancelled)
    }

    @Test
    fun callbackReturnsZeroWhenPlayingStops() = runBlocking {
        val latch = CompletableDeferred<Unit>()
        val deferred = scope.async {
            latch.complete(Unit)
            simulateGenerate(serial = 1)
        }
        latch.await()
        delay(20)
        state.playing = false
        val result = deferred.await()
        assertEquals(0, result)
    }

    @Test
    fun callbackReturnsZeroWhenSerialChanges() = runBlocking {
        val latch = CompletableDeferred<Unit>()
        val deferred = scope.async {
            latch.complete(Unit)
            simulateGenerate(serial = 1)
        }
        latch.await()
        delay(20)
        state.currentSerial = 2
        val result = deferred.await()
        assertEquals(0, result)
    }

    @Test
    fun callbackReturnsZeroWhenEngineSwitchedAwayFromVits() = runBlocking {
        val latch = CompletableDeferred<Unit>()
        val deferred = scope.async {
            latch.complete(Unit)
            simulateGenerate(serial = 1)
        }
        latch.await()
        delay(20)
        state.activeEngineVits = false
        val result = deferred.await()
        assertEquals(0, result)
    }
}
