package com.example.epubreader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

interface AudioSink {
    val state: Int
    val playbackHeadPosition: Int
    fun write(samples: FloatArray, offsetInFloats: Int, sizeInFloats: Int, writeMode: Int): Int
    fun write(samples: ShortArray, offsetInShorts: Int, sizeInShorts: Int, writeMode: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
}

interface AudioSinkFactory {
    fun getMinBufferSize(sampleRate: Int, channelConfig: Int, encoding: Int): Int
    fun create(sampleRate: Int, encoding: Int, bufferBytes: Int): AudioSink?
}

class DefaultAudioSinkFactory : AudioSinkFactory {
    override fun getMinBufferSize(sampleRate: Int, channelConfig: Int, encoding: Int): Int =
        AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

    override fun create(sampleRate: Int, encoding: Int, bufferBytes: Int): AudioSink? {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        return if (track.state == AudioTrack.STATE_INITIALIZED) {
            DefaultAudioSink(track)
        } else {
            track.release()
            null
        }
    }
}

class DefaultAudioSink(private val track: AudioTrack) : AudioSink {
    override val state: Int get() = track.state
    override val playbackHeadPosition: Int get() = track.playbackHeadPosition
    override fun write(samples: FloatArray, offsetInFloats: Int, sizeInFloats: Int, writeMode: Int): Int =
        track.write(samples, offsetInFloats, sizeInFloats, writeMode)
    override fun write(samples: ShortArray, offsetInShorts: Int, sizeInShorts: Int, writeMode: Int): Int =
        track.write(samples, offsetInShorts, sizeInShorts, writeMode)
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
}
