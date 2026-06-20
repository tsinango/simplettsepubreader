package com.example.epubreader.tts

import android.media.AudioTrack

class FakeAudioSink(
    override val state: Int = AudioTrack.STATE_INITIALIZED,
    private val partialWriteSize: Int = 0,
    private val failFloatWrite: Boolean = false,
    private val failPcm16Write: Boolean = false,
    private val advanceHeadOnWrite: Boolean = true,
) : AudioSink {
    @Volatile
    var playbackHeadPositionValue = 0
    val floatWrites = mutableListOf<FloatArray>()
    val pcm16Writes = mutableListOf<ShortArray>()
    var played = false
        private set
    var paused = false
        private set
    var flushed = false
        private set
    var released = false
        private set

    override val playbackHeadPosition: Int get() = playbackHeadPositionValue

    override fun write(
        samples: FloatArray,
        offsetInFloats: Int,
        sizeInFloats: Int,
        writeMode: Int,
    ): Int {
        if (failFloatWrite) return -1
        val toWrite = if (partialWriteSize > 0) minOf(partialWriteSize, sizeInFloats) else sizeInFloats
        floatWrites.add(samples.copyOfRange(offsetInFloats, offsetInFloats + toWrite))
        if (advanceHeadOnWrite) playbackHeadPositionValue += toWrite
        return toWrite
    }

    override fun write(
        samples: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        writeMode: Int,
    ): Int {
        if (failPcm16Write) return -1
        val toWrite = if (partialWriteSize > 0) minOf(partialWriteSize, sizeInShorts) else sizeInShorts
        pcm16Writes.add(samples.copyOfRange(offsetInShorts, offsetInShorts + toWrite))
        if (advanceHeadOnWrite) playbackHeadPositionValue += toWrite
        return toWrite
    }

    override fun play() { played = true }
    override fun pause() { paused = true }
    override fun flush() { flushed = true }
    override fun release() { released = true }

    fun advanceHead(frames: Int) {
        playbackHeadPositionValue += frames
    }
}

class FakeAudioSinkFactory(
    private val minBufferSize: Int = 4096,
    private val failMinBufferForEncoding: Set<Int> = emptySet(),
    private val failCreateForEncoding: Set<Int> = emptySet(),
    private val partialWriteSize: Int = 0,
    private val failFloatWrite: Boolean = false,
    private val failPcm16Write: Boolean = false,
    private val advanceHeadOnWrite: Boolean = true,
) : AudioSinkFactory {
    val createdSinks = mutableListOf<FakeAudioSink>()

    override fun getMinBufferSize(sampleRate: Int, channelConfig: Int, encoding: Int): Int {
        return if (encoding in failMinBufferForEncoding) -1 else minBufferSize
    }

    override fun create(sampleRate: Int, encoding: Int, bufferBytes: Int): AudioSink? {
        if (encoding in failCreateForEncoding) return null
        val sink = FakeAudioSink(
            partialWriteSize = partialWriteSize,
            failFloatWrite = failFloatWrite,
            failPcm16Write = failPcm16Write,
            advanceHeadOnWrite = advanceHeadOnWrite,
        )
        createdSinks.add(sink)
        return sink
    }

    val lastSink: FakeAudioSink?
        get() = createdSinks.lastOrNull()

    val sinks: List<FakeAudioSink>
        get() = createdSinks.toList()
}
