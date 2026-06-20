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
    private var audioWriteJob: Job? = null
    private var playbackCompletionJob: Job? = null
    @Volatile private var embeddedGenerationSerial = 0
    private var synthesisChunks: List<SynthesisChunk> = emptyList()
    private var synthesisChunkIndex = 0
    private var prefetched: PrefetchedChunk? = null
    private val synthesisMutex = Mutex()
    private var generatedChunks = 0
    private var prefetchHits = 0
    private var lastGenerationMillis = 0L
    private var lastRealTimeFactor = 0f
    private var lastChunkFinishedAt = 0L
    private var lastGapMillis = 0L
    @Volatile private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var speechRate = 1f
    private var lastSavedKey: String? = null
    private var lastBroadcastState: String? = null
    private var hasAudioFocus = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        DiagnosticLogger.event("TTS_THERMAL", "status=$status")
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            prefetched?.audio?.cancel()
            prefetched = null
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
                if (it < 0) pause()
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
                scope.launch { loadAndPlay() }
            }
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> scope.launch { move(1) }
            ACTION_PREVIOUS -> scope.launch { move(-1) }
            ACTION_SETTINGS_CHANGED -> scope.launch { reloadSettings() }
            ACTION_STOP -> stopPlayback()
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
            broadcastState()
            updateNotification("系统朗读引擎不可用")
        } else if (playing && activeEngine == MainViewModel.TTS_ENGINE_SYSTEM) {
            scope.launch { applySettings(); speakCurrent() }
        }
    }

    private suspend fun loadAndPlay() {
        val id = bookId ?: return
        DiagnosticLogger.event("TTS_PLAY", "load book=${DiagnosticLogger.bookToken(id)}")
        val parsedBook = ensureBook(id) ?: return stopPlayback()
        stopCurrentAudio()
        val locator = repository.locator(id)
        positionFromLocator(parsedBook, locator)
        playing = true
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
        speechRate = actualSpeechRate(settings?.speechRate ?: 1f)
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
        if (!ensureAudioFocus()) {
            playing = false
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
            val audio = withContext(Dispatchers.Default) {
                // Chunks are deliberately bounded, so direct generation remains cancellable at
                // chunk boundaries without repeatedly crossing JNI from a native worker thread.
                // Some vendor runtimes terminate the process in that callback path.
                engine.generate(
                    text = chunk.text,
                    speed = engineSpeed,
                )
            }
            if (!isGenerationCurrent(serial) || audio.samples.isEmpty()) return@withLock null
            val generationMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
            val audioMillis = audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
            lastGenerationMillis = generationMillis
            lastRealTimeFactor = generationMillis.toFloat() / audioMillis.coerceAtLeast(1)
            generatedChunks++
            DiagnosticLogger.event(
                "VITS_GENERATE",
                "done serial=$serial chunk=${chunk.index} generationMs=$generationMillis " +
                    "sampleRate=${audio.sampleRate} samples=${audio.samples.size} audioMs=$audioMillis",
            )
            SynthesizedAudio(
                chunk = chunk,
                samples = audio.samples,
                sampleRate = audio.sampleRate,
                generationMillis = generationMillis,
            )
        }

    private fun isGenerationCurrent(serial: Int): Boolean =
        playing && activeEngine == MainViewModel.TTS_ENGINE_VITS && serial == embeddedGenerationSerial

    private fun prefetchNextChunk(serial: Int) {
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return
        val next = nextChunk() ?: return
        prefetched?.audio?.cancel()
        prefetched = PrefetchedChunk(
            chunk = next,
            serial = serial,
            audio = scope.async(Dispatchers.Default) { synthesizeChunk(next, serial) },
        )
    }

    private fun nextChunk(): SynthesisChunk? {
        synthesisChunks.getOrNull(synthesisChunkIndex + 1)?.let { return it }
        val nextSentence = chapterSentences.getOrNull(sentenceIndex + 1) ?: return null
        return SynthesisChunker.split(nextSentence.key(), nextSentence.text).firstOrNull()
    }

    private fun embeddedTts(): OfflineTts = offlineTts ?: OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = VitsModelManager.modelFile(this).absolutePath,
                    tokens = VitsModelManager.tokensFile(this).absolutePath,
                    lexicon = VitsModelManager.lexiconFile(this).absolutePath,
                ),
                numThreads = if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                    2
                } else {
                    performanceStore.cpuThreads()
                },
            ),
        ),
    ).also {
        offlineTts = it
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "created threads=${if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) 2 else performanceStore.cpuThreads()} " +
                "modelReady=${VitsModelManager.isReady(this)} " +
                "modelBytes=${VitsModelManager.modelFile(this).length()} " +
                "tokensBytes=${VitsModelManager.tokensFile(this).length()} " +
                "lexiconBytes=${VitsModelManager.lexiconFile(this).length()}",
        )
    }

    private suspend fun playEmbeddedAudio(audio: SynthesizedAudio, serial: Int) {
        releaseAudioTrack()
        val pcm = audio.samples.toPcm16()
        val minBufferBytes = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "min_buffer_failed serial=$serial sampleRate=${audio.sampleRate} result=$minBufferBytes",
            )
            fallbackToSystem("音频输出失败，已切换到系统 TTS")
            return
        }
        val bufferBytes = minBufferBytes.coerceAtLeast(STREAM_WRITE_FRAMES * Short.SIZE_BYTES)
        DiagnosticLogger.event(
            "AUDIO_TRACK",
            "create serial=$serial sampleRate=${audio.sampleRate} frames=${pcm.size} " +
                "encoding=PCM16 mode=STREAM bufferBytes=$bufferBytes",
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            val state = track.state
            track.runCatching { release() }
            DiagnosticLogger.event(
                "AUDIO_TRACK",
                "init_failed serial=$serial state=$state frames=${pcm.size} bufferBytes=$bufferBytes",
            )
            fallbackToSystem("音频输出失败，已切换到系统 TTS")
            return
        }
        val gapStart = lastChunkFinishedAt
        audioTrack = track
        if (gapStart > 0L) {
            lastGapMillis = (SystemClock.elapsedRealtime() - gapStart).coerceAtLeast(0)
        }
        updateNotification(chapterSentences.getOrNull(sentenceIndex)?.text?.take(80).orEmpty())
        track.play()
        val playbackStartedAt = SystemClock.elapsedRealtime()
        DiagnosticLogger.event("AUDIO_TRACK", "playing serial=$serial frames=${pcm.size}")
        val expectedMillis = pcm.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        audioWriteJob = scope.launch(Dispatchers.IO) {
            var offset = 0
            var writeError = 0
            while (offset < pcm.size && isGenerationCurrent(serial) && audioTrack === track) {
                val frames = minOf(STREAM_WRITE_FRAMES, pcm.size - offset)
                val written = track.write(pcm, offset, frames, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    writeError = written
                    break
                }
                offset += written
            }
            withContext(Dispatchers.Main) {
                if (!isGenerationCurrent(serial) || audioTrack !== track) return@withContext
                audioWriteJob = null
                if (writeError == 0 && offset != pcm.size) writeError = AudioTrack.ERROR
                if (writeError < 0) {
                    DiagnosticLogger.event(
                        "AUDIO_TRACK",
                        "write_failed serial=$serial result=$writeError framesWritten=$offset " +
                            "frames=${pcm.size}",
                    )
                    fallbackToSystem("音频输出失败，已切换到系统 TTS")
                    return@withContext
                }
                DiagnosticLogger.event("AUDIO_TRACK", "write_done serial=$serial frames=$offset")
                val deadline = playbackStartedAt + expectedMillis + PLAYBACK_COMPLETION_GRACE_MS
                playbackCompletionJob = scope.launch {
                    while (isGenerationCurrent(serial) && audioTrack === track) {
                        val playedFrames = track.playbackHeadPosition.toLong() and 0xffffffffL
                        if (playedFrames >= pcm.size) {
                            playbackCompletionJob = null
                            onEmbeddedAudioDone(serial, track, "head")
                            return@launch
                        }
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            playbackCompletionJob = null
                            onEmbeddedAudioDone(serial, track, "timeout")
                            return@launch
                        }
                        delay(PLAYBACK_POLL_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun FloatArray.toPcm16(): ShortArray = ShortArray(size) { index ->
        (this[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
    }

    private suspend fun onEmbeddedAudioDone(
        serial: Int,
        completedTrack: AudioTrack,
        source: String,
    ) {
        if (!isGenerationCurrent(serial) || audioTrack !== completedTrack) return
        // Detach synchronously before the next suspension so stale completion work cannot
        // advance the chunk or sentence a second time.
        releaseAudioTrack()
        DiagnosticLogger.event(
            "AUDIO_TRACK",
            "completed serial=$serial source=$source chunk=$synthesisChunkIndex",
        )
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
            broadcastState()
        }
    }

    private fun actualSpeechRate(userRate: Float): Float =
        (if (userRate <= 1f) userRate else 1f + (userRate - 1f) * 2.5f)
            .coerceIn(0.3f, 4f)

    private fun vitsEngineSpeed(rate: Float): Float =
        (VITS_NORMAL_SPEED / rate.coerceAtLeast(0.1f)).coerceIn(0.05f, 1f)

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
        audioWriteJob?.cancel()
        audioWriteJob = null
        playbackCompletionJob?.cancel()
        playbackCompletionJob = null
        audioTrack?.runCatching { pause() }
        audioTrack?.runCatching { flush() }
        audioTrack?.runCatching { release() }
        audioTrack = null
    }

    private fun savePerformanceMetrics() {
        val hitRate = if (generatedChunks == 0) 0f else prefetchHits.toFloat() / generatedChunks
        performanceStore.saveMetrics(
            generationMillis = lastGenerationMillis,
            realTimeFactor = lastRealTimeFactor,
            prefetchHitRate = hitRate,
            gapMillis = lastGapMillis,
        )
    }

    private fun pause() {
        playing = false
        stopCurrentAudio()
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
        // This model's native speed=1 output is much slower than normal narration. Diagnostics
        // show that speed=0.357 still produces only about 2.5 Chinese characters per second.
        private const val VITS_NORMAL_SPEED = 0.2f
        private val NATIVE_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
