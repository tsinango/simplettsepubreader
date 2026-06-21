package com.example.epubreader.tts

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbeddedPlaybackPipelineTest {
    private lateinit var factory: FakeAudioSinkFactory
    private lateinit var pipeline: EmbeddedPlaybackPipeline
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        factory = FakeAudioSinkFactory()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            streamWriteFrames = 4096,
            pollIntervalMs = 1L,
        )
    }

    @After
    fun tearDown() {
        pipeline.stopPlayback()
        scope.cancel()
    }

    @Test
    fun multiChunkSentencePlaysAllChunksInOrder() = runBlocking {
        val completed = mutableListOf<String>()
        var allComplete = false
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { allComplete = true }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", false)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", false)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { allComplete }

        assertEquals(listOf("s:0:0"), completed)
        assertTrue(allComplete)
        val sink = factory.lastSink!!
        assertEquals(3, sink.floatWrites.size)
        assertEquals(100, sink.floatWrites[0].size)
        assertEquals(100, sink.floatWrites[1].size)
        assertEquals(100, sink.floatWrites[2].size)
    }

    @Test
    fun singleChunkSentenceEndsAndAdvances() = runBlocking {
        val completed = mutableListOf<String>()
        var allComplete = false
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { allComplete = true }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(200), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { allComplete }

        assertEquals(listOf("s:0:0"), completed)
        assertTrue(allComplete)
    }

    @Test
    fun queueTemporarilyEmptyThenNewDataArrivesConsumerContinues() = runBlocking {
        val completed = mutableListOf<String>()
        var allComplete = false
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { allComplete = true }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", true)

        waitFor { completed.contains("s:0:0") }

        assertEquals(listOf("s:0:0"), completed)
        assertFalse(allComplete)
        assertTrue(pipeline.isPlaying)

        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:1", true)
        pipeline.closeChannel()

        waitFor { allComplete }

        assertEquals(listOf("s:0:0", "s:0:1"), completed)
        assertTrue(allComplete)
    }

    @Test
    fun channelCapacityLimitsToTwo() = runBlocking {
        val serial = 1
        pipeline.startPlayback(serial)

        val sendResults = mutableListOf<String>()
        val sendJob = scope.async {
            pipeline.enqueueAudio(floats(10), 22050, serial, "s:0:0", false)
            sendResults.add("first")
            pipeline.enqueueAudio(floats(10), 22050, serial, "s:0:0", false)
            sendResults.add("second")
            pipeline.enqueueAudio(floats(10), 22050, serial, "s:0:0", true)
            sendResults.add("third")
        }

        waitFor { sendResults.size == 3 }
        sendJob.join()

        assertEquals(listOf("first", "second", "third"), sendResults)
    }

    @Test
    fun playbackHeadNotAtBoundaryDoesNotAdvanceProgress() = runBlocking {
        factory = FakeAudioSinkFactory(advanceHeadOnWrite = false)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        val completed = mutableListOf<String>()
        pipeline.onSentenceComplete = { key -> completed.add(key) }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { factory.lastSink != null && factory.lastSink!!.floatWrites.isNotEmpty() }
        delay(50)

        assertTrue(completed.isEmpty())

        factory.lastSink!!.advanceHead(100)
        waitFor { completed.contains("s:0:0") }

        assertEquals(listOf("s:0:0"), completed)
    }

    @Test
    fun oldSerialDoesNotPlayAfterStop() = runBlocking {
        val completed = mutableListOf<String>()
        pipeline.onSentenceComplete = { key -> completed.add(key) }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)

        waitFor { completed.contains("s:0:0") }

        pipeline.stopPlayback()

        val oldSerial = serial
        val newSerial = pipeline.currentSerial
        assertFalse(pipeline.isCurrentSerial(oldSerial))
        assertTrue(newSerial != oldSerial)

        pipeline.startPlayback(newSerial)
        pipeline.enqueueAudio(floats(50), 22050, oldSerial, "s:0:1", true)
        pipeline.closeChannel()

        delay(100)

        assertEquals(listOf("s:0:0"), completed)
    }

    @Test
    fun floatPartialWriteLoopsUntilAllFramesWritten() = runBlocking {
        factory = FakeAudioSinkFactory(partialWriteSize = 30)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        val completed = mutableListOf<String>()
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { completed.contains("s:0:0") }

        assertEquals(listOf("s:0:0"), completed)
        val sink = factory.lastSink!!
        val totalWritten = sink.floatWrites.sumOf { it.size }
        assertEquals(100, totalWritten)
        assertTrue(sink.floatWrites.size > 1)
    }

    @Test
    fun floatInitFailureFallsBackToPcm16() = runBlocking {
        factory = FakeAudioSinkFactory(
            failCreateForEncoding = setOf(AudioFormat.ENCODING_PCM_FLOAT),
        )
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        var pcm16Fallback = false
        pipeline.onFallbackToPcm16 = { pcm16Fallback = true }
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { factory.createdSinks.any { it.pcm16Writes.isNotEmpty() } }

        assertFalse(pcm16Fallback)
        assertEquals(1, factory.createdSinks.size)
        assertNotNull(factory.lastSink)
        assertTrue(factory.lastSink!!.pcm16Writes.isNotEmpty())
    }

    @Test
    fun floatWriteFailureFallsBackToPcm16() = runBlocking {
        var pcm16Fallback = false
        val completed = mutableListOf<String>()
        factory = FakeAudioSinkFactory(failFloatWrite = true)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        pipeline.onFallbackToPcm16 = { pcm16Fallback = true }
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { pcm16Fallback }

        assertTrue(pcm16Fallback)
        assertEquals(1, factory.createdSinks.size)
        val firstSink = factory.sinks[0]
        assertTrue(firstSink.released)
        assertFalse(pipeline.isFloatSupported)
    }

    @Test
    fun pcm16InitFailureFallsBackToSystem() = runBlocking {
        var systemFallbackMessage: String? = null
        factory = FakeAudioSinkFactory(
            failMinBufferForEncoding = setOf(AudioFormat.ENCODING_PCM_FLOAT),
            failCreateForEncoding = setOf(AudioFormat.ENCODING_PCM_16BIT),
        )
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        pipeline.onFallbackToSystem = { msg -> systemFallbackMessage = msg }
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)

        waitFor { systemFallbackMessage != null }

        assertNotNull(systemFallbackMessage)
        assertTrue(systemFallbackMessage!!.contains("系统 TTS"))
    }

    @Test
    fun pcm16WriteFailureFallsBackToSystem() = runBlocking {
        var systemFallbackMessage: String? = null
        var pcm16Fallback = false
        factory = FakeAudioSinkFactory(failFloatWrite = true, failPcm16Write = true)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        pipeline.onFallbackToPcm16 = { pcm16Fallback = true }
        pipeline.onFallbackToSystem = { msg -> systemFallbackMessage = msg }
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial1 = 1
        pipeline.startPlayback(serial1)
        pipeline.enqueueAudio(floats(100), 22050, serial1, "s:0:0", true)

        waitFor { pcm16Fallback }

        pipeline.setFloatSupported(false)
        val serial2 = pipeline.currentSerial
        pipeline.startPlayback(serial2)
        pipeline.enqueueAudio(floats(100), 22050, serial2, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { systemFallbackMessage != null }

        assertNotNull(systemFallbackMessage)
        assertTrue(systemFallbackMessage!!.contains("系统 TTS"))
    }

    @Test
    fun multipleSentencesPlayInOrder() = runBlocking {
        val completed = mutableListOf<String>()
        var allComplete = false
        pipeline.onSentenceComplete = { key -> completed.add(key) }
        pipeline.onAllPlaybackComplete = { allComplete = true }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", true)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:1", true)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:2", true)
        pipeline.closeChannel()

        waitFor { allComplete }

        assertEquals(listOf("s:0:0", "s:0:1", "s:0:2"), completed)
        assertTrue(allComplete)
    }

    @Test
    fun stopReleasesSink() = runBlocking {
        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", true)

        waitFor { factory.lastSink != null && factory.lastSink!!.floatWrites.isNotEmpty() }

        val sink = factory.lastSink!!
        assertFalse(sink.released)

        pipeline.stopPlayback()

        waitFor { sink.released }

        assertTrue(sink.released)
    }

    @Test
    fun floatToShortClipsToRange() = runBlocking {
        factory = FakeAudioSinkFactory(
            failCreateForEncoding = setOf(AudioFormat.ENCODING_PCM_FLOAT),
        )
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = factory,
            scope = scope,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 1L,
        )
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        val samples = floatArrayOf(2.0f, 1.5f, 1.0f, 0.5f, 0f, -0.5f, -1.0f, -1.5f, -2.0f)
        pipeline.enqueueAudio(samples, 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { factory.lastSink != null && factory.lastSink!!.pcm16Writes.isNotEmpty() }

        val pcm16Data = factory.lastSink!!.pcm16Writes.flatMap { it.toList() }
        assertEquals(9, pcm16Data.size)
        assertEquals(Short.MAX_VALUE, pcm16Data[0])
        assertEquals(Short.MAX_VALUE, pcm16Data[1])
        assertEquals(Short.MAX_VALUE, pcm16Data[2])
        assertEquals((-Short.MAX_VALUE).toShort(), pcm16Data[7])
        assertEquals((-Short.MAX_VALUE).toShort(), pcm16Data[8])
    }

    @Test
    fun sameSampleRateReusesSink() = runBlocking {
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", false)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { factory.lastSink != null && factory.lastSink!!.floatWrites.size == 2 }

        assertEquals(1, factory.createdSinks.size)
    }

    @Test
    fun differentSampleRateRecreatesSink() = runBlocking {
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", true)
        pipeline.enqueueAudio(floats(50), 44100, serial, "s:0:1", true)
        pipeline.closeChannel()

        waitFor { factory.createdSinks.size == 2 }

        assertEquals(2, factory.createdSinks.size)
        assertTrue(factory.sinks[0].released)
    }

    @Test
    fun floatSupportedFlagPersistsAcrossStarts() = runBlocking {
        pipeline.setFloatSupported(false)
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(50), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { factory.lastSink != null && factory.lastSink!!.pcm16Writes.isNotEmpty() }

        assertEquals(1, factory.createdSinks.size)
        assertTrue(factory.lastSink!!.pcm16Writes.isNotEmpty())
        assertTrue(factory.lastSink!!.floatWrites.isEmpty())
    }

    @Test
    fun totalFramesWrittenMatchesEnqueued() = runBlocking {
        pipeline.onSentenceComplete = { }
        pipeline.onAllPlaybackComplete = { }

        val serial = 1
        pipeline.startPlayback(serial)
        pipeline.enqueueAudio(floats(100), 22050, serial, "s:0:0", false)
        pipeline.enqueueAudio(floats(200), 22050, serial, "s:0:0", true)
        pipeline.closeChannel()

        waitFor { pipeline.totalWritten == 300L }

        assertEquals(300L, pipeline.totalWritten)
        assertEquals(300L, pipeline.totalEnqueued)
    }

    private fun floats(n: Int): FloatArray = FloatArray(n) { it.toFloat() * 0.01f }

    private suspend fun waitFor(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }
}
