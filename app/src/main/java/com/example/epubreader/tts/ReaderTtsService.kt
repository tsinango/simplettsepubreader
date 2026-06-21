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
import com.example.epubreader.tts.engine.BertVits2MnnEngine
import com.example.epubreader.tts.engine.EmbeddedTtsEngine
import com.example.epubreader.tts.engine.EngineSwitchGate
import com.example.epubreader.tts.engine.KokoroSherpaEngine
import com.example.epubreader.tts.engine.SherpaVitsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    @Volatile private var activePack: TtsModelPackDescriptor = VitsModelRegistry.WNJ
    @Volatile private var currentEmbeddedSid: Int = 0
    @Volatile private var currentEmbeddedUserRate: Float = 1f
    private val engineGate = EngineSwitchGate()
    private val embeddedSelection by lazy { EmbeddedModelSelectionStore(this) }
    private lateinit var pipeline: EmbeddedPlaybackPipeline
    private var generationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var embeddedGenerationSerial = 0
    private val synthesisMutex = Mutex()
    private val commandMutex = Mutex()
    private var generatedChunks = 0
    private var lastEngineInitMillis = 0L
    private var lastFirstAudioMillis = 0L
    private var playbackRequestedAt = 0L
    private var lastGenerationMillis = 0L
    private var lastRealTimeFactor = 0f
    private var lastChunkFinishedAt = 0L
    private var lastGapMillis = 0L
    @Volatile private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private lateinit var thermalController: ThermalThreadController
    private val engineThreads: Int get() = thermalController.engineThreads
    private var recoveryJob: Job? = null
    private var engineRecreateJob: Job? = null
    private var speechRate = 1f
    private var lastSavedKey: String? = null
    private var lastBroadcastState: String? = null
    private var hasAudioFocus = false
    private val audioPreferences by lazy {
        getSharedPreferences("tts_audio", Context.MODE_PRIVATE)
    }
    private val serviceClock = object : Clock {
        override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
        override fun currentThermalStatus(): Int = powerManager.currentThermalStatus
    }
    private var floatPcmSupported: Boolean
        get() = audioPreferences.getBoolean(KEY_FLOAT_PCM_SUPPORTED, true)
        set(value) = audioPreferences.edit().putBoolean(KEY_FLOAT_PCM_SUPPORTED, value).apply()

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        val action = thermalController.onThermalStatusChanged(status)
        DiagnosticLogger.event(
            "TTS_THERMAL",
            "status=$status currentThreads=${thermalController.engineThreads} " +
                "targetThreads=${thermalController.targetEngineThreads} action=$action",
        )
        when (action) {
            is ThermalAction.Downgrade -> handleThermalDowngrade(action.from, action.to)
            is ThermalAction.StartRecovery -> scheduleRecovery(action.deadlineAt)
            ThermalAction.NoChange -> Unit
            is ThermalAction.StillRecovering -> Unit
            is ThermalAction.Upgrade -> Unit
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

    private fun handleThermalDowngrade(from: Int, to: Int) {
        val reason = TtsThreadPolicy.reason(from, to)
        embeddedGenerationSerial++
        generationJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        pipeline.stopPlayback()
        scope.launch(Dispatchers.Default) {
            synthesisMutex.withLock {
                val startedAt = SystemClock.elapsedRealtime()
                engineGate.release()
                val releaseMs = (SystemClock.elapsedRealtime() - startedAt)
                DiagnosticLogger.event("VITS_ENGINE", "recreate $reason releaseMs=$releaseMs")
            }
            thermalController.commitEngineThreads(to)
            if (playing && activeEngine == MainViewModel.TTS_ENGINE_VITS) {
                withContext(Dispatchers.Main) { speakCurrent() }
            }
        }
    }

    private fun scheduleRecovery(deadlineAt: Long) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch(Dispatchers.Default) {
            val now = SystemClock.elapsedRealtime()
            val waitMs = (deadlineAt - now).coerceAtLeast(0)
            delay(waitMs)
            val action = thermalController.tickRecovery()
            DiagnosticLogger.event(
                "TTS_THERMAL",
                "recovery_tick action=$action threads=${thermalController.engineThreads}",
            )
            if (action is ThermalAction.Upgrade) {
                recreateEngineForUpgrade(action.toThreads)
            }
        }
    }

    private suspend fun recreateEngineForUpgrade(targetThreads: Int) {
        if (engineRecreateJob?.isActive == true) return
        engineRecreateJob = scope.launch(Dispatchers.Default) {
            embeddedGenerationSerial++
            generationJob?.cancel()
            pipeline.stopPlayback()
            synthesisMutex.withLock {
                val startedAt = SystemClock.elapsedRealtime()
                engineGate.release()
                val releaseMs = (SystemClock.elapsedRealtime() - startedAt)
                DiagnosticLogger.event(
                    "VITS_ENGINE",
                    "recreate thermal_upgrade ${engineThreads}t->$targetThreads releaseMs=$releaseMs",
                )
            }
            thermalController.commitEngineThreads(targetThreads)
            if (playing && activeEngine == MainViewModel.TTS_ENGINE_VITS) {
                withContext(Dispatchers.Main) { speakCurrent() }
            }
        }
        engineRecreateJob?.join()
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as ReaderApplication).repository
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        thermalStatus = powerManager.currentThermalStatus
        thermalController = ThermalThreadController(serviceClock)
        thermalController.initialize(thermalStatus)
        powerManager.addThermalStatusListener(mainExecutor, thermalListener)
        performanceStore = TtsPerformanceStore(this)
        pipeline = EmbeddedPlaybackPipeline(
            sinkFactory = DefaultAudioSinkFactory(),
            scope = scope,
            dispatcher = Dispatchers.IO,
        )
        pipeline.onSentenceComplete = { key -> handleSentenceComplete(key) }
        pipeline.onAllPlaybackComplete = { handleAllPlaybackComplete() }
        pipeline.onFallbackToPcm16 = { handleFallbackToPcm16() }
        pipeline.onFallbackToSystem = { msg -> handleFallbackToSystem(msg) }
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
            ACTION_STOP -> {
                val requestId = intent.getIntExtra(EXTRA_STOP_REQUEST_ID, 0)
                scope.launch {
                    commandMutex.withLock {
                        stopPlayback()
                        broadcastStopComplete(requestId)
                    }
                }
            }
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
        val modelId = VitsModelId.fromStableValue(settings?.vitsModelId) ?: VitsModelId.FANCHEN_WNJ
        activePack = EmbeddedModelRegistry.byId(modelId)
        speechRate = TtsRatePolicy.userRate(settings?.speechRate ?: 1f)
        when (activePack.engineKind) {
            TtsEngineKind.SHERPA_KOKORO -> {
                currentEmbeddedSid = embeddedSelection.speakerId(
                    activePack.id.stableValue,
                    KokoroModelRegistry.DEFAULT_CHINESE_FEMALE_SID,
                )
                currentEmbeddedUserRate = TtsRatePolicy.userRate(
                    embeddedSelection.rate(activePack.id.stableValue, KokoroModelRegistry.DEFAULT_USER_RATE),
                )
            }
            TtsEngineKind.BERT_VITS2_MNN -> {
                currentEmbeddedSid = embeddedSelection.speakerId(activePack.id.stableValue, 0)
                currentEmbeddedUserRate = TtsRatePolicy.userRate(
                    embeddedSelection.rate(activePack.id.stableValue, 1f),
                )
            }
            TtsEngineKind.SHERPA_VITS -> {
                currentEmbeddedSid = 0
                currentEmbeddedUserRate = speechRate
            }
        }
        DiagnosticLogger.event(
            "TTS_SETTINGS",
            "engine=$activeEngine model=${activePack.id.stableValue} rate=$speechRate " +
                "embeddedSid=$currentEmbeddedSid embeddedRate=$currentEmbeddedUserRate pitch=${settings?.pitch ?: 1f}",
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

    private fun speakEmbedded(sentence: SentenceRef) {
        val pack = activePack
        val candidate = engineGate.candidate(pack, pack.id.stableValue, ::createEmbeddedEngine)
        if (!candidate.isAvailable(this)) {
            DiagnosticLogger.event("VITS", "model_not_ready model=${pack.id.stableValue}")
            scope.launch { fallbackToSystem("内置语音模型不可用，已切换到系统 TTS") }
            return
        }
        val serial = embeddedGenerationSerial
        pipeline.setFloatSupported(floatPcmSupported)
        pipeline.startPlayback(serial)
        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.Default) {
            try {
                var sentenceIdx = sentenceIndex
                while (pipeline.isCurrentSerial(serial) && playing) {
                    val current = chapterSentences.getOrNull(sentenceIdx) ?: break
                    val chunks = SynthesisChunker.split(current.key(), current.text)
                    if (chunks.isEmpty()) {
                        sentenceIdx++
                        continue
                    }
                    for ((chunkIndex, chunk) in chunks.withIndex()) {
                        if (!pipeline.isCurrentSerial(serial)) return@launch
                        val audio = synthesizeChunk(chunk, serial) ?: return@launch
                        if (!pipeline.isCurrentSerial(serial) || audio.samples.isEmpty()) return@launch
                        pipeline.enqueueAudio(
                            samples = audio.samples,
                            sampleRate = audio.sampleRate,
                            s = serial,
                            sentenceKey = current.key(),
                            isSentenceEnd = chunkIndex == chunks.lastIndex,
                        )
                    }
                    sentenceIdx++
                }
                pipeline.closeChannel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DiagnosticLogger.error("VITS", "pipeline_failed serial=$serial", e)
                if (pipeline.isCurrentSerial(serial) && playing) {
                    pipeline.closeChannel()
                    withContext(Dispatchers.Main) {
                        fallbackToSystem("内置语音生成失败，已切换到系统 TTS")
                    }
                }
            }
        }
    }

    private suspend fun synthesizeChunk(
        chunk: SynthesisChunk,
        serial: Int,
    ): SynthesizedAudio? =
        synthesisMutex.withLock {
            if (!isGenerationCurrent(serial)) return@withLock null
            val engine = withContext(Dispatchers.Default) { ensureEngine() }
            val startedAt = SystemClock.elapsedRealtime()
            val (sid, engineSpeed) = currentSidAndSpeed()
            DiagnosticLogger.event(
                "VITS_GENERATE",
                "start serial=$serial chunk=${chunk.index} length=${chunk.text.length} " +
                    "rate=$speechRate engineSpeed=$engineSpeed sid=$sid kind=${activePack.engineKind}",
            )
            val generated = withContext(Dispatchers.Default) {
                engine.synthesize(chunk.text, sid = sid, speed = engineSpeed)
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
                samples = extendedSamples,
                sampleRate = generated.sampleRate,
                generationMillis = generationMillis,
            )
        }

    private fun currentSidAndSpeed(): Pair<Int, Float> = when (activePack.engineKind) {
        TtsEngineKind.SHERPA_VITS -> 0 to vitsEngineSpeed(speechRate)
        TtsEngineKind.SHERPA_KOKORO -> currentEmbeddedSid to currentEmbeddedUserRate
        TtsEngineKind.BERT_VITS2_MNN -> currentEmbeddedSid to currentEmbeddedUserRate
    }

    private fun isGenerationCurrent(serial: Int): Boolean =
        playing && activeEngine == MainViewModel.TTS_ENGINE_VITS && serial == embeddedGenerationSerial

    private fun ensureEngine(): EmbeddedTtsEngine {
        val pack = activePack
        val engine = engineGate.candidate(pack, pack.id.stableValue, ::createEmbeddedEngine)
        if (engineGate.isBuilt(engine)) return engine
        val numThreads = engineThreads
        val startedAt = SystemClock.elapsedRealtime()
        engine.initialize(this, numThreads)
        lastEngineInitMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        engineGate.markBuilt(engine)
        if (lastFirstAudioMillis == 0L && playbackRequestedAt > 0L) {
            lastFirstAudioMillis = (SystemClock.elapsedRealtime() - playbackRequestedAt).coerceAtLeast(0)
        }
        DiagnosticLogger.event(
            "VITS_ENGINE",
            "ready model=${pack.id.stableValue} kind=${pack.engineKind} initMs=$lastEngineInitMillis threads=$numThreads",
        )
        return engine
    }

    private fun createEmbeddedEngine(pack: TtsModelPackDescriptor): EmbeddedTtsEngine = when (pack) {
        is VitsModelDescriptor -> SherpaVitsEngine(pack)
        is KokoroModelDescriptor -> KokoroSherpaEngine(pack)
        is BertVits2MnnPackDescriptor -> BertVits2MnnEngine(pack)
        else -> error("Unsupported pack kind for descriptor: ${pack::class}")
    }

    private suspend fun handleSentenceComplete(key: String) {
        if (!playing) return
        withContext(Dispatchers.Main) {
            if (!playing) return@withContext
            val idx = chapterSentences.indexOfFirst { it.key() == key }
            if (idx >= 0) {
                sentenceIndex = idx + 1
                val next = chapterSentences.getOrNull(sentenceIndex)
                if (next != null) {
                    val id = bookId
                    if (id != null && lastSavedKey != next.key()) {
                        repository.saveProgress(id, next, "TTS")
                        lastSavedKey = next.key()
                    }
                    broadcastState()
                    updateNotification(next.text.take(80))
                }
            }
        }
    }

    private suspend fun handleAllPlaybackComplete() {
        if (!playing) return
        withContext(Dispatchers.Main) {
            if (!playing) return@withContext
            if (moveIndex(1)) speakCurrent() else stopPlayback()
        }
    }

    private suspend fun handleFallbackToPcm16() {
        withContext(Dispatchers.Main) {
            floatPcmSupported = false
            DiagnosticLogger.event("AUDIO_TRACK", "fallback_to_pcm16_restart")
            if (playing && activeEngine == MainViewModel.TTS_ENGINE_VITS) {
                stopCurrentAudio()
                speakCurrent()
            }
        }
    }

    private suspend fun handleFallbackToSystem(message: String) {
        withContext(Dispatchers.Main) {
            stopCurrentAudio()
            fallbackToSystem(message)
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
            "stop_current engine=$activeEngine serial=$embeddedGenerationSerial pipeline=${pipeline.isPlaying}",
        )
        embeddedGenerationSerial++
        generationJob?.cancel()
        generationJob = null
        pipeline.stopPlayback()
        currentUtteranceId = null
        tts?.stop()
        savePerformanceMetrics()
    }

    private fun savePerformanceMetrics() {
        performanceStore.saveMetrics(
            modelRevision = activePack.revision,
            engineInitMillis = lastEngineInitMillis,
            firstAudioMillis = lastFirstAudioMillis,
            generationMillis = lastGenerationMillis,
            realTimeFactor = lastRealTimeFactor,
            prefetchHitRate = 0f,
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

    private suspend fun stopPlayback() {
        DiagnosticLogger.event("TTS_PLAY", "stop")
        playing = false
        val genJob = generationJob
        val playJob = pipeline.activePlaybackJob
        stopCurrentAudio()
        genJob?.join()
        playJob?.join()
        releaseWakeLock()
        abandonAudioFocus()
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastStopComplete(requestId: Int) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_STOP_COMPLETE, true)
                .putExtra(EXTRA_STOP_REQUEST_ID, requestId)
                .putExtra(EXTRA_PLAYING, false)
        )
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
        playing = false
        stopCurrentAudio()
        releaseWakeLock()
        abandonAudioFocus()
        tts?.shutdown()
        tts = null
        powerManager.removeThermalStatusListener(thermalListener)
        recoveryJob?.cancel()
        engineRecreateJob?.cancel()
        scope.cancel()
        NATIVE_CLEANUP_SCOPE.launch {
            synthesisMutex.withLock {
                engineGate.release()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class SynthesizedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val generationMillis: Long,
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
        const val EXTRA_STOP_REQUEST_ID = "stopRequestId"
        const val EXTRA_STOP_COMPLETE = "stopComplete"
        private const val CHANNEL_ID = "reader_tts"
        private const val NOTIFICATION_ID = 42
        private const val PLAYBACK_POLL_INTERVAL_MS = 20L
        private const val STREAM_WRITE_FRAMES = 4096
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val KEY_FLOAT_PCM_SUPPORTED = "float_pcm_supported"
        private val NATIVE_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
