package com.example.epubreader.tts

import android.media.AudioFormat
import android.media.AudioTrack
import com.example.epubreader.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QueuedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val serial: Int,
    val frames: Int,
    val sentenceKey: String,
    val cumulativeEndFrame: Long,
    val isSentenceEnd: Boolean,
)

class EmbeddedPlaybackPipeline(
    private val sinkFactory: AudioSinkFactory,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val streamWriteFrames: Int = 4096,
    private val pollIntervalMs: Long = 20L,
) {
    private var channel: Channel<QueuedAudio>? = null
    private var playbackJob: Job? = null
    private var sink: AudioSink? = null
    private var totalFramesEnqueued = 0L
    private var totalFramesWritten = 0L
    private var currentSampleRate = 0
    private var currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
    @Volatile private var serial = 0
    private var floatSupported = true
    private val pendingSentenceEnds = ArrayDeque<Pair<String, Long>>()

    var onSentenceComplete: (suspend (sentenceKey: String) -> Unit)? = null
    var onAllPlaybackComplete: (suspend () -> Unit)? = null
    var onFallbackToPcm16: (suspend () -> Unit)? = null
    var onFallbackToSystem: (suspend (String) -> Unit)? = null

    val currentSerial: Int get() = serial
    val totalEnqueued: Long get() = totalFramesEnqueued
    val totalWritten: Long get() = totalFramesWritten
    val isFloatSupported: Boolean get() = floatSupported
    val isPlaying: Boolean get() = playbackJob?.isActive == true

    fun setFloatSupported(value: Boolean) {
        floatSupported = value
    }

    fun startPlayback(initialSerial: Int): Channel<QueuedAudio> {
        stopPlayback()
        serial = initialSerial
        totalFramesEnqueued = 0L
        totalFramesWritten = 0L
        pendingSentenceEnds.clear()
        currentSampleRate = 0
        currentEncoding = if (floatSupported) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val ch = Channel<QueuedAudio>(capacity = 2)
        channel = ch
        startConsumer(initialSerial)
        return ch
    }

    suspend fun enqueueAudio(
        samples: FloatArray,
        sampleRate: Int,
        s: Int,
        sentenceKey: String,
        isSentenceEnd: Boolean,
    ) {
        val ch = channel ?: return
        if (!isCurrentSerial(s)) return
        totalFramesEnqueued += samples.size
        val cumulativeEnd = totalFramesEnqueued
        ch.send(
            QueuedAudio(
                samples = samples,
                sampleRate = sampleRate,
                serial = s,
                frames = samples.size,
                sentenceKey = sentenceKey,
                cumulativeEndFrame = cumulativeEnd,
                isSentenceEnd = isSentenceEnd,
            ),
        )
    }

    fun closeChannel() {
        channel?.close()
    }

    fun stopPlayback() {
        serial++
        playbackJob?.cancel()
        playbackJob = null
        channel?.close()
        channel = null
        sink?.runCatching { pause() }
        sink?.runCatching { flush() }
        sink?.runCatching { release() }
        sink = null
        totalFramesEnqueued = 0L
        totalFramesWritten = 0L
        pendingSentenceEnds.clear()
    }

    fun isCurrentSerial(s: Int): Boolean = s == serial

    private fun startConsumer(s: Int) {
        playbackJob = scope.launch(dispatcher) {
            val ch = channel ?: return@launch
            try {
                while (isCurrentSerial(s)) {
                    val entry = ch.receiveCatching().getOrNull() ?: break
                    if (entry.serial != s || !isCurrentSerial(s)) continue
                    if (!ensureSink(entry.sampleRate, s)) return@launch
                    val written = writeSamples(entry, s)
                    if (written < 0) {
                        handleWriteFailure(s)
                        return@launch
                    }
                    if (written > 0) {
                        totalFramesWritten += written
                    }
                    if (entry.isSentenceEnd) {
                        pendingSentenceEnds.addLast(entry.sentenceKey to entry.cumulativeEndFrame)
                    }
                    checkSentenceCompletions(s)
                }
                drainRemainingAudio(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DiagnosticLogger.error("AUDIO_TRACK", "consumer_failed serial=$s", e)
            }
        }
    }

    private suspend fun ensureSink(sampleRate: Int, s: Int): Boolean {
        val currentSink = sink
        if (currentSink != null && currentSampleRate == sampleRate &&
            currentSink.state == AudioTrack.STATE_INITIALIZED
        ) {
            return true
        }
        sink?.runCatching { release() }
        sink = null
        totalFramesWritten = 0L
        pendingSentenceEnds.clear()

        val encoding = currentEncoding
        val minBuffer = sinkFactory.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding,
        )
        if (minBuffer <= 0) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                DiagnosticLogger.event(
                    "AUDIO_TRACK",
                    "float_init_fallback serial=$s stage=minBuffer result=$minBuffer",
                )
                currentEncoding = AudioFormat.ENCODING_PCM_16BIT
                floatSupported = false
                return ensureSink(sampleRate, s)
            }
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "init_failed serial=$s stage=minBuffer encoding=PCM_16BIT result=$minBuffer",
            )
            onFallbackToSystem?.invoke("音频初始化失败（错误码 $minBuffer），已切换到系统 TTS")
            return false
        }
        val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val bufferBytes = minBuffer.coerceAtLeast(streamWriteFrames * bytesPerFrame)
        val newSink = sinkFactory.create(sampleRate, encoding, bufferBytes)
        if (newSink == null) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                DiagnosticLogger.event(
                    "AUDIO_TRACK",
                    "float_init_failed serial=$s stage=create fallback_to=PCM_16BIT",
                )
                currentEncoding = AudioFormat.ENCODING_PCM_16BIT
                floatSupported = false
                return ensureSink(sampleRate, s)
            }
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "init_failed serial=$s stage=create encoding=PCM_16BIT",
            )
            onFallbackToSystem?.invoke("音频初始化失败，已切换到系统 TTS")
            return false
        }
        sink = newSink
        currentSampleRate = sampleRate
        currentEncoding = encoding
        val label = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT"
        DiagnosticLogger.event(
            "AUDIO_TRACK",
            "created serial=$s sampleRate=$sampleRate encoding=$label bufferBytes=$bufferBytes",
        )
        newSink.play()
        return true
    }

    private fun writeSamples(entry: QueuedAudio, s: Int): Int {
        val currentSink = sink ?: return -1
        return if (currentEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            writeFloatLoop(currentSink, entry.samples, s)
        } else {
            writePcm16Loop(currentSink, entry.samples, s)
        }
    }

    private fun writeFloatLoop(sinkObj: AudioSink, samples: FloatArray, s: Int): Int {
        var offset = 0
        while (offset < samples.size && isCurrentSerial(s)) {
            val written = sinkObj.write(
                samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING,
            )
            if (written < 0) {
                DiagnosticLogger.event(
                    "AUDIO_TRACK",
                    "float_write_failed serial=$s offset=$offset result=$written",
                )
                return written
            }
            if (written == 0) break
            offset += written
        }
        return offset
    }

    private fun writePcm16Loop(sinkObj: AudioSink, samples: FloatArray, s: Int): Int {
        var offset = 0
        while (offset < samples.size && isCurrentSerial(s)) {
            val chunkSize = minOf(streamWriteFrames, samples.size - offset)
            val buf = ShortArray(chunkSize)
            for (i in 0 until chunkSize) {
                buf[i] = (samples[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            val written = sinkObj.write(buf, 0, chunkSize, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                DiagnosticLogger.event(
                    "AUDIO_TRACK",
                    "pcm16_write_failed serial=$s offset=$offset result=$written",
                )
                return written
            }
            if (written == 0) break
            offset += written
        }
        return offset
    }

    private suspend fun handleWriteFailure(s: Int) {
        val failedEncoding = currentEncoding
        sink?.runCatching { pause() }
        sink?.runCatching { flush() }
        sink?.runCatching { release() }
        sink = null
        totalFramesWritten = 0L
        pendingSentenceEnds.clear()

        if (failedEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "float_write_fallback serial=$s switching_to=PCM_16BIT",
            )
            currentEncoding = AudioFormat.ENCODING_PCM_16BIT
            floatSupported = false
            onFallbackToPcm16?.invoke()
        } else {
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "pcm16_write_failed serial=$s switching_to=SYSTEM_TTS",
            )
            onFallbackToSystem?.invoke("音频写入失败，已切换到系统 TTS")
        }
    }

    private suspend fun checkSentenceCompletions(s: Int) {
        while (pendingSentenceEnds.isNotEmpty() && isCurrentSerial(s)) {
            val (key, endFrame) = pendingSentenceEnds.first()
            val played = readPlayedFrames() ?: return
            if (played < endFrame) return
            pendingSentenceEnds.removeFirst()
            onSentenceComplete?.invoke(key)
        }
    }

    private suspend fun drainRemainingAudio(s: Int) {
        while (pendingSentenceEnds.isNotEmpty() && isCurrentSerial(s)) {
            val (key, endFrame) = pendingSentenceEnds.first()
            while (isCurrentSerial(s)) {
                val played = readPlayedFrames() ?: return
                if (played >= endFrame) break
                delay(pollIntervalMs)
            }
            if (!isCurrentSerial(s)) return
            pendingSentenceEnds.removeFirst()
            onSentenceComplete?.invoke(key)
        }
        if (isCurrentSerial(s)) {
            onAllPlaybackComplete?.invoke()
        }
    }

    private fun readPlayedFrames(): Long? {
        val s = sink ?: return null
        return s.playbackHeadPosition.toLong() and 0xffffffffL
    }
}
