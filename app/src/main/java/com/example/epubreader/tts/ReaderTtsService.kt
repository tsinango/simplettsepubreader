package com.example.epubreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.epubreader.MainActivity
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.ReaderApplication
import com.example.epubreader.data.ParsedBook
import com.example.epubreader.data.ReaderRepository
import com.example.epubreader.data.ReadingLocatorEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.MainViewModel
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class ReaderTtsService : Service(), TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: ReaderRepository
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var performanceStore: TtsPerformanceStore
    private var tts: TextToSpeech? = null
    private var initialized = false
    @Volatile private var playing = false
    private var bookId: String? = null
    private var loadedBookId: String? = null
    private var parsed: ParsedBook? = null
    private var chapterIndex = 0
    private var chapterSentences: List<SentenceRef> = emptyList()
    private var sentenceIndex = 0
    private var utteranceSerial = 0
    private var currentUtteranceId: String? = null
    @Volatile private var activeEngine = MainViewModel.TTS_ENGINE_SYSTEM
    private var offlineTts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var audioPlaybackJob: Job? = null
    private val audioQueue = ArrayDeque<QueuedAudio>(2)
    private var totalFramesWritten = 0L
    private var sentenceEndFrame = Long.MAX_VALUE
    private var audioEncoding = AudioFormat.ENCODING_PCM_FLOAT
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var embeddedGenerationSerial = 0
    private var synthesisChunks: List<SynthesisChunk> = emptyList()
    private var synthesisChunkIndex = 0
    private var prefetched: PrefetchedChunk? = null
    private val synthesisMutex = Mutex()
    private val commandMutex = Mutex()
    private var generatedChunks = 0
    private var prefetchHits = 0
    private var prefetchRequests = 0
    private var lastEngineInitMillis = 0L
    private var lastFirstAudioMillis = 0L
    private var playbackRequestedAt = 0L
    private var lastGenerationMillis = 0L
    private var lastRealTimeFactor = 0f
    private var lastChunkFinishedAt = 0L
    private var lastGapMillis = 0L
    @Volatile private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var engineThreads = 4
    private var speechRate = 1f
    private var lastSavedKey: String? = null
    private var lastBroadcastState: String? = null
    private var hasAudioFocus = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        val targetThreads = TtsThreadPolicy.threadsForThermal(status)
        DiagnosticLogger.event("TTS_THERMAL", "status=$status currentThreads=$engineThreads targetThreads=$targetThreads")
        if (TtsThreadPolicy.shouldRecreate(engineThreads, targetThreads)) {
            val reason = TtsThreadPolicy.reason(engineThreads, targetThreads)
            engineThreads = targetThreads
            prefetched?.audio?.cancel()
            prefetched = null
            scope.launch(Dispatchers.Default) {
                synthesisMutex.withLock {
                    val startedAt = SystemClock.elapsedRealtime()
                    offlineTts?.runCatching { release() }
                    offlineTts = null
                    val releaseMs = (SystemClock.elapsedRealtime() - startedAt)
                    DiagnosticLogger.event("VITS_ENGINE", "recreate $reason releaseMs=$releaseMs")
                }
            }
        }
    }
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener {
                DiagnosticLogger.event("TTS_FOCUS", "change=$it")
                if (it < 0) scope.launch { commandMutex.withLock { pause() } }
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as ReaderApplication).repository
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalListener)
        performanceStore = TtsPerformanceStore(this)
        DiagnosticLogger.event("TTS_SERVICE", "created thermal=$thermalStatus")
        createChannel()
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                broadcastState()
            }
            override fun onError(utteranceId: String?) {
                handleSystemTtsError(utteranceId, TextToSpeech.ERROR)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSystemTtsError(utteranceId, errorCode)
            }
            override fun onDone(utteranceId: String?) {
                scope.launch {
                    if (!playing || activeEngine != MainViewModel.TTS_ENGINE_SYSTEM ||
                        utteranceId != currentUtteranceId
                    ) return@launch
                    if (moveIndex(1)) speakCurrent() else stopPlayback()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_BOOK_ID)?.let { bookId = it }
        DiagnosticLogger.event(
            "TTS_COMMAND",
            "action=${intent?.action ?: "null"} startId=$startId " +
                "book=${DiagnosticLogger.bookToken(bookId)}",
        )
        when (intent?.action) {
            ACTION_PLAY -> {
                startForeground(NOTIFICATION_ID, notification("正在准备朗读"))
                scope.launch { commandMutex.withLock { loadAndPlay() } }
            }
            ACTION_PAUSE -> scope.launch { commandMutex.withLock { pause() } }
            ACTION_NEXT -> scope.launch { commandMutex.withLock { move(1) } }
            ACTION_PREVIOUS -> scope.launch { commandMutex.withLock { move(-1) } }
            ACTION_SETTINGS_CHANGED -> scope.launch { commandMutex.withLock { reloadSettings() } }
            ACTION_STOP -> scope.launch { commandMutex.withLock { stopPlayback() } }
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val languageStatus = if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
        } else {
            TextToSpeech.ERROR
        }
        initialized = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED &&
            languageStatus != TextToSpeech.ERROR
        DiagnosticLogger.event(
            "SYSTEM_TTS",
            "initialized=$initialized initStatus=$status languageStatus=$languageStatus",
        )
        if (!initialized && playing && activeEngine == MainViewModel.TTS_ENGINE_SYSTEM) {
            playing = false
            releaseWakeLock()
            broadcastState()
            updateNotification("系统朗读引擎不可用")
        } else if (playing && activeEngine == MainViewModel.TTS_ENGINE_SYSTEM) {
            scope.launch { applySettings(); speakCurrent() }
        }
    }

    private suspend fun loadAndPlay() {
        val id = bookId ?: return
        playbackRequestedAt = SystemClock.elapsedRealtime()
        lastFirstAudioMillis = 0
        generatedChunks = 0
        prefetchHits = 0
        prefetchRequests = 0
        DiagnosticLogger.event("TTS_PLAY", "load book=${DiagnosticLogger.bookToken(id)}")
        val parsedBook = ensureBook(id) ?: return stopPlayback()
        stopCurrentAudio()
        val locator = repository.locator(id)
        positionFromLocator(parsedBook, locator)
        playing = true
        acquireWakeLock()
        applySettings()
        broadcastState()
        if (activeEngine == MainViewModel.TTS_ENGINE_VITS || initialized) speakCurrent()
    }

    private suspend fun ensureBook(id: String): ParsedBook? {
        if (loadedBookId == id && parsed != null) return parsed
        val book = repository.book(id) ?: return null
        val parsedBook = repository.parsed(book)
        parsed = parsedBook
        loadedBookId = id
        chapterIndex = 0
        sentenceIndex = 0
        chapterSentences = emptyList()
        lastSavedKey = null
        lastBroadcastState = null
        return parsedBook
    }

    private suspend fun positionFromLocator(parsedBook: ParsedBook, locator: ReadingLocatorEntity?) {
        val locatedChapterIndex = parsedBook.chapters.indexOfFirst { it.path == locator?.chapterPath }
            .coerceAtLeast(0)
        if (!loadChapterAt(locatedChapterIndex)) {
            loadNearestChapter(locatedChapterIndex, 1)
        }
        sentenceIndex = if (locator == null) {
            0
        } else {
            chapterSentences.indexOfFirst {
                it.paragraphIndex == locator.paragraphIndex && it.sentenceIndex == locator.sentenceIndex
            }.coerceAtLeast(0)
        }
    }

    private suspend fun loadChapterAt(index: Int): Boolean {
        val chapter = parsed?.chapters?.getOrNull(index) ?: return false
        chapterSentences = repository.sentences(chapter)
        chapterIndex = index
        sentenceIndex = sentenceIndex.coerceIn(chapterSentences.indicesOrZero())
        return chapterSentences.isNotEmpty()
    }

    private suspend fun loadNearestChapter(startIndex: Int, direction: Int): Boolean {
        val parsedBook = parsed ?: return false
        var nextIndex = startIndex
        while (nextIndex in parsedBook.chapters.indices) {
            if (loadChapterAt(nextIndex)) return true
            nextIndex += direction
        }
        return false
    }


    private suspend fun applySettings() {
        val settings = repository.settings.first()
        activeEngine = settings?.ttsEngine ?: MainViewModel.TTS_ENGINE_SYSTEM
        speechRate = TtsRatePolicy.userRate(settings?.speechRate ?: 1f)
        DiagnosticLogger.event(
            "TTS_SETTINGS",
            "engine=$activeEngine rate=$speechRate pitch=${settings?.pitch ?: 1f}",
        )
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(settings?.pitch ?: 1f)
        settings?.voiceName?.let { name ->
            tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it }
        }
    }

    private suspend fun reloadSettings() {
        stopCurrentAudio()
        applySettings()
        if (playing && (activeEngine == MainViewModel.TTS_ENGINE_VITS || initialized)) speakCurrent()
    }

    private suspend fun speakCurrent() {
        val id = bookId ?: return
        val sentence = chapterSentences.getOrNull(sentenceIndex) ?: return stopPlayback()
        acquireWakeLock()
        if (!ensureAudioFocus()) {
            playing = false
            releaseWakeLock()
            broadcastState()
            return updateNotification("等待音频控制权")
        }
        val sentenceKey = sentence.key()
        if (lastSavedKey != sentenceKey) {
            repository.saveProgress(id, sentence, "TTS")
            lastSavedKey = sentenceKey
        }
        broadcastState()
        updateNotification(sentence.text.take(80))
        DiagnosticLogger.event(
            "TTS_SENTENCE",
            "engine=$activeEngine book=${DiagnosticLogger.bookToken(id)} chapter=$chapterIndex " +
                "paragraph=${sentence.paragraphIndex} sentence=${sentence.sentenceIndex} " +
                "length=${sentence.text.length}",
        )
        if (activeEngine == MainViewModel.TTS_ENGINE_VITS) {
            speakEmbedded(sentence)
        } else {
            val utteranceId = nextUtteranceId(sentence)
            currentUtteranceId = utteranceId
            val result = tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) handleSystemTtsError(utteranceId, result)
        }
    }

    private fun handleSystemTtsError(utteranceId: String?, errorCode: Int) {
        if (activeEngine != MainViewModel.TTS_ENGINE_SYSTEM ||
            utteranceId != currentUtteranceId
        ) return
        playing = false
        currentUtteranceId = null
        releaseWakeLock()
        abandonAudioFocus()
        broadcastState()
        val message = "系统 TTS 朗读失败（错误码 $errorCode），请安装对应语言的语音包或切换到内置 VITS"
        DiagnosticLogger.event("SYSTEM_TTS", "speak_failed code=$errorCode")
        broadcastError(message)
        updateNotification(message)
    }

    private suspend fun speakEmbedded(sentence: SentenceRef) {
        if (!VitsModelManager.isReady(this)) {
            DiagnosticLogger.event("VITS", "model_not_ready")
            fallbackToSystem("内置语音模型不可用，已切换到系统 TTS")
            return
        }
        val sentenceKey = sentence.key()
        if (synthesisChunks.firstOrNull()?.logicalSentenceKey != sentenceKey) {
            synthesisChunks = SynthesisChunker.split(sentenceKey, sentence.text)
            synthesisChunkIndex = 0
        }
        val chunk = synthesisChunks.getOrNull(synthesisChunkIndex) ?: return stopPlayback()
        val serial = embeddedGenerationSerial
        val cached = prefetched?.takeIf { it.chunk.key == chunk.key && it.serial == serial }
        val cacheReady = cached?.audio?.isCompleted == true
        prefetched = null
        updateNotification(if (cacheReady) sentence.text.take(80) else "正在生成语音…")
        try {
            var usedPrefetch = false
            val prefetchedAudio = try {
                cached?.audio?.await()?.also { usedPrefetch = true }
            } catch (e: CancellationException) {
                if (!isGenerationCurrent(serial)) throw e
                null
            }
            val audio = prefetchedAudio ?: synthesizeChunk(chunk, serial)
            if (usedPrefetch) prefetchHits++
            if (!playing || activeEngine != MainViewModel.TTS_ENGINE_VITS ||
                serial != embeddedGenerationSerial || audio == null || audio.samples.isEmpty()
            ) return
            playEmbeddedAudio(audio, serial)
            prefetchNextChunk(serial)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLogger.error(
                "VITS",
                "sentence_failed chunk=$synthesisChunkIndex serial=$serial",
                e,
            )
            if (serial == embeddedGenerationSerial && playing) {
                fallbackToSystem("内置语音生成失败，已切换到系统 TTS")
            }
        }
    }

    private suspend fun synthesizeChunk(
        chunk: SynthesisChunk,
        serial: Int,
    ): SynthesizedAudio? =
        synthesisMutex.withLock {
            if (!isGenerationCurrent(serial)) return@withLock null
            val engine = withContext(Dispatchers.Default) { embeddedTts() }
            val startedAt = SystemClock.elapsedRealtime()
            val engineSpeed = vitsEngineSpeed(speechRate)
            DiagnosticLogger.event(
                "VITS_GENERATE",
                "start serial=$serial chunk=${chunk.index} length=${chunk.text.length} " +
                    "rate=$speechRate engineSpeed=$engineSpeed",
            )
            val genConfig = GenerationConfig(
                silenceScale = 0.2f,
                speed = engineSpeed,
                sid = 0,
            )
            val generated = withContext(Dispatchers.Default) {
                engine.generateWithConfigAndCallback(
                    chunk.text,
                    genConfig,
                ) { if (isGenerationCurrent(serial)) 1 else 0 }
            }
            if (!isGenerationCurrent(serial) || generated.samples.isEmpty()) return@withLock null
            val silenceSamples = chunk.pauseMs * generated.sampleRate / 1000
            val extendedSamples = if (silenceSamples > 0) {
                generated.samples.copyOf(generated.samples.size + silenceSamples)
            } else {
                generated.samples
            }
            val generationMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
            val audioMillis = extendedSamples.size * 1000L / generated.sampleRate.coerceAtLeast(1)
            lastGenerationMillis = generationMillis
            lastRealTimeFactor = generationMillis.toFloat() / audioMillis.coerceAtLeast(1)
            generatedChunks++
            DiagnosticLogger.event(
                "VITS_GENERATE",
                "done serial=$serial chunk=${chunk.index} generationMs=$generationMillis " +
                    "sampleRate=${generated.sampleRate} samples=${generated.samples.size}+${silenceSamples} audioMs=$audioMillis",
            )
            SynthesizedAudio(
                chunk = chunk,
                samples = extendedSamples,
                sampleRate = generated.sampleRate,
                generationMillis = generationMillis,
            )
        }

    private fun isGenerationCurrent(serial: Int): Boolean =
        playing && activeEngine == MainViewModel.TTS_ENGINE_VITS && serial == embeddedGenerationSerial

    private fun prefetchNextChunk(serial: Int) {
        if (TtsThreadPolicy.threadsForThermal(thermalStatus) <= 2) return
        val next = nextChunk() ?: return
        prefetched?.audio?.cancel()
        prefetched = PrefetchedChunk(
            chunk = next,
            serial = serial,
            audio = scope.async(Dispatchers.Default) { synthesizeChunk(next, serial) },
        )
        prefetchRequests++
    }

    private fun nextChunk(): SynthesisChunk? {
        synthesisChunks.getOrNull(synthesisChunkIndex + 1)?.let { return it }
        val nextSentence = chapterSentences.getOrNull(sentenceIndex + 1) ?: return null
        return SynthesisChunker.split(nextSentence.key(), nextSentence.text).firstOrNull()
    }

    private fun embeddedTts(): OfflineTts = offlineTts ?: run {
        val startedAt = SystemClock.elapsedRealtime()
        val numThreads = engineThreads
        val fstPaths = listOf(
            VitsModelManager.phoneFstFile(this).absolutePath,
            VitsModelManager.dateFstFile(this).absolutePath,
            VitsModelManager.numberFstFile(this).absolutePath,
        ).joinToString(",")
        OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = VitsModelManager.modelFile(this).absolutePath,
                    tokens = VitsModelManager.tokensFile(this).absolutePath,
                    lexicon = VitsModelManager.lexiconFile(this).absolutePath,
                ),
                numThreads = numThreads,
            ),
            ruleFsts = fstPaths,
            maxNumSentences = 1,
            silenceScale = 0.2f,
        ),
        ).also {
        lastEngineInitMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        offlineTts = it
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created initMs=$lastEngineInitMillis threads=$numThreads " +
                "ruleFsts=[phone,date,number] maxNumSentences=1 silenceScale=0.2 " +
                "modelReady=${VitsModelManager.isReady(this)} " +
                "modelBytes=${VitsModelManager.modelFile(this).length()} " +
                "tokensBytes=${VitsModelManager.tokensFile(this).length()} " +
                "lexiconBytes=${VitsModelManager.lexiconFile(this).length()}",
        )
    }
    }

    private suspend fun playEmbeddedAudio(audio: SynthesizedAudio, serial: Int) {
        val track = ensureAudioTrack(audio.sampleRate, serial) ?: return
        val frames = audio.samples.size
        audioQueue.addLast(QueuedAudio(audio.samples, audio.sampleRate, serial, frames, audio.chunk))
        if (audioPlaybackJob == null) {
            startAudioPlayback(serial, audio.sampleRate)
        }
        if (synthesisChunkIndex + 1 >= synthesisChunks.size) {
            sentenceEndFrame = totalFramesWritten + frames
        }
    }

    private fun ensureAudioTrack(sampleRate: Int, serial: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) return existing
        releaseAudioTrack()

        val encoding = audioEncoding
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding,
        )
        if (minBufferBytes <= 0 && encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            DiagnosticLogger.event("AUDIO_TRACK", "float_init_fallback serial=$serial")
            audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            return ensureAudioTrack(sampleRate, serial)
        }
        if (minBufferBytes <= 0) {
            DiagnosticLogger.event("AUDIO_TRACK", "min_buffer_failed serial=$serial sampleRate=$sampleRate result=$minBufferBytes")
            return null
        }
        val bufferBytes = minBufferBytes.coerceAtLeast(STREAM_WRITE_FRAMES *
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
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
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            val state = track.state
            track.runCatching { release() }
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                DiagnosticLogger.event("AUDIO_TRACK", "float_init_failed serial=$serial state=$state fallback_to_pcm16")
                audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                return ensureAudioTrack(sampleRate, serial)
            }
            DiagnosticLogger.event("AUDIO_TRACK", "init_failed serial=$serial state=$state")
            return null
        }
        audioTrack = track
        audioEncoding = encoding
        totalFramesWritten = 0L
        sentenceEndFrame = Long.MAX_VALUE
        val label = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT"
        DiagnosticLogger.event("AUDIO_TRACK", "created serial=$serial sampleRate=$sampleRate encoding=$label bufferBytes=$bufferBytes")
        track.play()
        if (lastFirstAudioMillis == 0L && playbackRequestedAt > 0L) {
            lastFirstAudioMillis = (SystemClock.elapsedRealtime() - playbackRequestedAt).coerceAtLeast(0)
        }
        return track
    }

    private fun startAudioPlayback(serial: Int, sampleRate: Int) {
        audioPlaybackJob = scope.launch(Dispatchers.IO) {
            val track = audioTrack ?: return@launch
            val isFloat = audioEncoding == AudioFormat.ENCODING_PCM_FLOAT
            val pcm16Buffer = if (isFloat) null else ShortArray(STREAM_WRITE_FRAMES)
            try {
                while (isGenerationCurrent(serial) && audioTrack === track) {
                    val entry = audioQueue.removeFirstOrNull() ?: break
                    if (entry.serial != serial || !isGenerationCurrent(serial)) continue
                    val samples = entry.samples
                    val writeResult = if (isFloat) {
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        val buf = pcm16Buffer!!
                        var offset = 0
                        var error = 0
                        while (offset < samples.size && isGenerationCurrent(serial) && audioTrack === track) {
                            val chunkSize = minOf(STREAM_WRITE_FRAMES, samples.size - offset)
                            for (i in 0 until chunkSize) {
                                buf[i] = (samples[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                            val written = track.write(buf, 0, chunkSize, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) { error = written; break }
                            offset += written
                        }
                        if (error < 0) error else offset
                    }
                    if (writeResult <= 0) {
                        DiagnosticLogger.event("AUDIO_TRACK", "write_failed serial=$serial result=$writeResult")
                        break
                    }
                    totalFramesWritten += samples.size
                    if (totalFramesWritten >= sentenceEndFrame) {
                        withContext(Dispatchers.Main) { onSentenceAudioComplete(serial) }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (audioPlaybackJob?.isActive == true) audioPlaybackJob = null
                }
            }
        }
    }

    private suspend fun onSentenceAudioComplete(serial: Int) {
        if (!isGenerationCurrent(serial)) return
        sentenceEndFrame = Long.MAX_VALUE
        val track = audioTrack
        if (track != null) {
            val playedFrames = track.playbackHeadPosition.toLong() and 0xffffffffL
            while (playedFrames < totalFramesWritten && isGenerationCurrent(serial)) {
                delay(PLAYBACK_POLL_INTERVAL_MS)
            }
        }
        lastChunkFinishedAt = SystemClock.elapsedRealtime()
        if (synthesisChunkIndex + 1 < synthesisChunks.size) {
            synthesisChunkIndex++
            chapterSentences.getOrNull(sentenceIndex)?.let { speakEmbedded(it) }
        } else if (moveIndex(1)) {
            synthesisChunks = emptyList()
            synthesisChunkIndex = 0
            speakCurrent()
        } else {
            stopPlayback()
        }
    }

    private suspend fun fallbackToSystem(message: String) {
        DiagnosticLogger.event("TTS_FALLBACK", message)
        stopCurrentAudio()
        activeEngine = MainViewModel.TTS_ENGINE_SYSTEM
        val settings = repository.settings.first() ?: com.example.epubreader.data.ReaderSettingsEntity()
        repository.saveSettings(settings.copy(ttsEngine = MainViewModel.TTS_ENGINE_SYSTEM))
        broadcastError(message)
        updateNotification(message)
        if (initialized && playing) {
            speakCurrent()
        } else {
            playing = false
            releaseWakeLock()
            broadcastState()
        }
    }

    private fun vitsEngineSpeed(rate: Float): Float =
        TtsRatePolicy.vitsSpeed(rate)

    private fun stopCurrentAudio() {
        DiagnosticLogger.event(
            "TTS_AUDIO",
            "stop_current engine=$activeEngine serial=$embeddedGenerationSerial track=${audioTrack != null}",
        )
        embeddedGenerationSerial++
        prefetched?.audio?.cancel()
        prefetched = null
        synthesisChunks = emptyList()
        synthesisChunkIndex = 0
        currentUtteranceId = null
        tts?.stop()
        releaseAudioTrack()
        savePerformanceMetrics()
    }

    private fun releaseAudioTrack() {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = null
        audioQueue.clear()
        totalFramesWritten = 0L
        sentenceEndFrame = Long.MAX_VALUE
        audioTrack?.runCatching { pause() }
        audioTrack?.runCatching { flush() }
        audioTrack?.runCatching { release() }
        audioTrack = null
    }

    private fun savePerformanceMetrics() {
        val hitRate = if (prefetchRequests == 0) 0f else prefetchHits.toFloat() / prefetchRequests
        performanceStore.saveMetrics(
            engineInitMillis = lastEngineInitMillis,
            firstAudioMillis = lastFirstAudioMillis,
            generationMillis = lastGenerationMillis,
            realTimeFactor = lastRealTimeFactor,
            prefetchHitRate = hitRate,
            gapMillis = lastGapMillis,
        )
    }

    private fun pause() {
        playing = false
        stopCurrentAudio()
        releaseWakeLock()
        abandonAudioFocus()
        broadcastState()
        updateNotification("已暂停")
    }

    private suspend fun move(delta: Int) {
        if (chapterSentences.isEmpty()) {
            loadAndPlay()
            return
        }
        stopCurrentAudio()
        if (!moveIndex(delta)) return
        playing = true
        acquireWakeLock()
        speakCurrent()
    }

    private suspend fun moveIndex(delta: Int): Boolean {
        val localIndex = sentenceIndex + delta
        if (localIndex in chapterSentences.indices) {
            sentenceIndex = localIndex
            return true
        }
        val direction = delta.sign()
        val targetChapter = chapterIndex + direction
        if (!loadNearestChapter(targetChapter, direction)) return false
        sentenceIndex = if (delta > 0) 0 else chapterSentences.lastIndex
        return true
    }

    private fun stopPlayback() {
        DiagnosticLogger.event("TTS_PLAY", "stop")
        playing = false
        stopCurrentAudio()
        releaseWakeLock()
        abandonAudioFocus()
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DiagnosticLogger.event("TTS_FOCUS", "request granted=$hasAudioFocus")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        DiagnosticLogger.event("TTS_FOCUS", "abandoned")
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:tts-playback",
        ).apply { setReferenceCounted(false) }.also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun utteranceId(ref: SentenceRef) =
        ref.key()

    private fun nextUtteranceId(ref: SentenceRef): String {
        utteranceSerial++
        return "${utteranceId(ref)}:$utteranceSerial"
    }

    private fun SentenceRef.key() = "$chapterPath:$paragraphIndex:$sentenceIndex"

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private fun List<SentenceRef>.indicesOrZero(): IntRange =
        if (isEmpty()) 0..0 else indices

    private fun broadcastState() {
        val sentence = chapterSentences.getOrNull(sentenceIndex)
        val stateKey = listOf(bookId, playing, sentence?.key()).joinToString("|")
        if (stateKey == lastBroadcastState) return
        lastBroadcastState = stateKey
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_PLAYING, playing)
        if (sentence != null) {
            intent.putExtra(EXTRA_CHAPTER_PATH, sentence.chapterPath)
            intent.putExtra(EXTRA_PARAGRAPH_INDEX, sentence.paragraphIndex)
            intent.putExtra(EXTRA_SENTENCE_INDEX, sentence.sentenceIndex)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_ERROR, message),
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TTS Reader")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "上一句", action(ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续",
                action(if (playing) ACTION_PAUSE else ACTION_PLAY, 2),
            )
            .addAction(android.R.drawable.ic_media_next, "下一句", action(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 4))
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ReaderTtsService::class.java).setAction(name)
        bookId?.let { intent.putExtra(EXTRA_BOOK_ID, it) }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        DiagnosticLogger.event("TTS_SERVICE", "destroy")
        stopCurrentAudio()
        releaseWakeLock()
        tts?.shutdown()
        powerManager.removeThermalStatusListener(thermalListener)
        scope.cancel()
        // generate() is a blocking JNI call and cannot be cancelled while native code is active.
        // Releasing OfflineTts concurrently can invalidate its native handle and terminate the
        // process, so cleanup must acquire the same mutex used by every generation.
        NATIVE_CLEANUP_SCOPE.launch {
            synthesisMutex.withLock {
                offlineTts?.runCatching { release() }
                    ?.onSuccess { DiagnosticLogger.event("VITS_ENGINE", "released") }
                    ?.onFailure { DiagnosticLogger.error("VITS_ENGINE", "release_failed", it) }
                offlineTts = null
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class SynthesizedAudio(
        val chunk: SynthesisChunk,
        val samples: FloatArray,
        val sampleRate: Int,
        val generationMillis: Long,
    )

    private data class PrefetchedChunk(
        val chunk: SynthesisChunk,
        val serial: Int,
        val audio: Deferred<SynthesizedAudio?>,
    )

    private data class QueuedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val serial: Int,
        val frames: Int,
        val chunk: SynthesisChunk,
    )

    companion object {
        const val ACTION_PLAY = "reader.PLAY"
        const val ACTION_PAUSE = "reader.PAUSE"
        const val ACTION_NEXT = "reader.NEXT"
        const val ACTION_PREVIOUS = "reader.PREVIOUS"
        const val ACTION_STOP = "reader.STOP"
        const val ACTION_SETTINGS_CHANGED = "reader.SETTINGS_CHANGED"
        const val ACTION_STATE_CHANGED = "reader.STATE_CHANGED"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_CHAPTER_PATH = "chapterPath"
        const val EXTRA_PARAGRAPH_INDEX = "paragraphIndex"
        const val EXTRA_SENTENCE_INDEX = "sentenceIndex"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "reader_tts"
        private const val NOTIFICATION_ID = 42
        private const val PLAYBACK_COMPLETION_GRACE_MS = 500L
        private const val PLAYBACK_POLL_INTERVAL_MS = 20L
        private const val STREAM_WRITE_FRAMES = 4096
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private val NATIVE_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
