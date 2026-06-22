package com.example.epubreader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ParsedBook
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.ReadingLocatorEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.tts.EmbeddedModelRegistry
import com.example.epubreader.tts.EmbeddedModelSelectionStore
import com.example.epubreader.tts.ReaderTtsService
import com.example.epubreader.tts.TtsModelPackDescriptor
import com.example.epubreader.tts.TtsPerformanceSnapshot
import com.example.epubreader.tts.TtsPerformanceStore
import com.example.epubreader.tts.VitsModelDescriptor
import com.example.epubreader.tts.VitsModelId
import com.example.epubreader.tts.VitsModelManager
import com.example.epubreader.tts.VitsModelRegistry
import com.example.epubreader.tts.VitsModelState
import com.example.epubreader.tts.VitsModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val book: BookEntity? = null,
    val parsed: ParsedBook? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val sentences: List<SentenceRef> = emptyList(),
    val previousChapterIndex: Int? = null,
    val previousSentences: List<SentenceRef> = emptyList(),
    val nextChapterIndex: Int? = null,
    val nextSentences: List<SentenceRef> = emptyList(),
    val progress: Float = 0f,
    val isSpeaking: Boolean = false,
    val sleepTimerMinutes: Int = 20,
    val sleepTimerEndAtMillis: Long? = null,
    val sleepTimerRemainingMinutes: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReaderPositionState(
    val bookId: String? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val progress: Float = 0f,
    val isSpeaking: Boolean = false,
)

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val bookProgress: Map<String, Float> = emptyMap(),
    val deletingBookIds: Set<String> = emptySet(),
    val loadingImport: Boolean = false,
    val deleteResult: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ReaderApplication).repository
    private val modelManagers: Map<VitsModelId, VitsModelManager> =
        EmbeddedModelRegistry.all.associate { it.id to VitsModelManager(application, it) }
    private val ttsPerformanceStore = TtsPerformanceStore(application)
    val embeddedSelectionStore = EmbeddedModelSelectionStore(application)

    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )
    val vitsModelStates: StateFlow<Map<VitsModelId, VitsModelState>> =
        combine(
            *EmbeddedModelRegistry.all.map { modelManagers.getValue(it.id).state }.toTypedArray(),
        ) { states ->
            EmbeddedModelRegistry.all.mapIndexed { i, d -> d.id to states[i] }.toMap()
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            EmbeddedModelRegistry.all.associate { it.id to modelManagers.getValue(it.id).currentState() },
        )
    private val _ttsPerformance = MutableStateFlow(
        ttsPerformanceStore.snapshot(VitsModelRegistry.WNJ.revision, VitsModelRegistry.WNJ.id.stableValue),
    )
    val ttsPerformance: StateFlow<TtsPerformanceSnapshot> = _ttsPerformance
    private val _reader = MutableStateFlow(ReaderUiState())
    val reader: StateFlow<ReaderUiState> = _reader

    private val _readerPosition = MutableStateFlow(ReaderPositionState())
    val readerPosition: StateFlow<ReaderPositionState> = _readerPosition

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library

    private var currentBookId: String? = null
    private var openToken = 0
    private var importToken = 0
    private var stopRequestId = 0
    private var sleepTimerJob: Job? = null

    private val ttsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReaderTtsService.ACTION_STATE_CHANGED) return
            if (intent.getBooleanExtra(ReaderTtsService.EXTRA_STOP_COMPLETE, false)) {
                val receivedId = intent.getIntExtra(ReaderTtsService.EXTRA_STOP_REQUEST_ID, -1)
                stopCompleteDeferred?.let { d ->
                    if (receivedId == d.first) {
                        d.second.complete(Unit)
                    }
                }
            }
            applyTtsState(intent)
        }
    }
    private var stopCompleteDeferred: Pair<Int, kotlinx.coroutines.CompletableDeferred<Unit>>? = null

    init {
        val filter = IntentFilter(ReaderTtsService.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            application,
            ttsStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        viewModelScope.launch {
            repository.books.collectLatest { books ->
                val progressMap = mutableMapOf<String, Float>()
                books.forEach { book ->
                    runCatching {
                        val parsed = withContext(Dispatchers.Default) { repository.parsed(book) }
                        val locator = repository.locator(book.id)
                        repository.progressPercent(parsed, locator)
                    }.onSuccess { p -> progressMap[book.id] = p }
                }
                _library.value = _library.value.copy(
                    books = books,
                    bookProgress = progressMap,
                )
            }
        }
        viewModelScope.launch {
            vitsModelStates.collect { states ->
                val pendingId = modelManagers.values.firstNotNullOfOrNull { it.pendingSwitchTarget() }
                if (pendingId == null) return@collect
                val pendingDescriptor: TtsModelPackDescriptor = EmbeddedModelRegistry.byId(pendingId)
                val pendingManager = modelManagers.getValue(pendingId)
                if (states[pendingId]?.status == VitsModelStatus.READY) {
                    pendingManager.clearPendingSwitch()
                    val value = settings.value ?: ReaderSettingsEntity()
                    repository.saveSettings(
                        value.copy(ttsEngine = TTS_ENGINE_VITS, vitsModelId = pendingDescriptor.id.stableValue),
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.collect { s ->
                val modelId = VitsModelId.fromStableValue(s?.vitsModelId) ?: VitsModelId.FANCHEN_WNJ
                val descriptor = EmbeddedModelRegistry.byId(modelId)
                _ttsPerformance.value =
                    ttsPerformanceStore.snapshot(descriptor.revision, descriptor.id.stableValue)
            }
        }

        if (DiagnosticLogger.wasPreviousExitNativeCrash(application)) {
            viewModelScope.launch {
                val current = repository.settings.first()
                if (current?.ttsEngine == TTS_ENGINE_VITS) {
                    repository.saveSettings(current.copy(ttsEngine = TTS_ENGINE_SYSTEM))
                    DiagnosticLogger.event("TTS_FALLBACK", "auto_switch_to_system_after_native_crash")
                }
            }
        }
    }

    fun import(uri: Uri) {
        val token = ++importToken
        _library.value = _library.value.copy(loadingImport = true)
        viewModelScope.launch {
            runCatching { repository.import(uri) }
                .onSuccess {
                    if (token == importToken) {
                        _library.value = _library.value.copy(loadingImport = false)
                        open(it.id)
                    }
                }
                .onFailure {
                    if (token == importToken) {
                        _library.value = _library.value.copy(loadingImport = false)
                        _reader.value = ReaderUiState(error = it.message ?: "导入失败")
                    }
                }
        }
    }

    fun deleteBook(bookId: String) {
        val current = _library.value
        if (bookId in current.deletingBookIds) return
        _library.value = current.copy(deletingBookIds = current.deletingBookIds + bookId)
        viewModelScope.launch {
            try {
                val currentBookId = _reader.value.book?.id
                if (bookId == currentBookId) {
                    val requestId = ++stopRequestId
                    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                    stopCompleteDeferred = requestId to deferred
                    sendAction(ReaderTtsService.ACTION_STOP, requestId)
                    try {
                        kotlinx.coroutines.withTimeout(5_000L) { deferred.await() }
                    } catch (_: TimeoutCancellationException) {
                        stopCompleteDeferred = null
                        _library.value = _library.value.copy(
                            deleteResult = "TTS 停止超时，请稍后重试",
                        )
                        return@launch
                    } finally {
                        if (stopCompleteDeferred?.first == requestId) {
                            stopCompleteDeferred = null
                        }
                    }
                    _reader.value = ReaderUiState()
                    _readerPosition.value = ReaderPositionState()
                    this@MainViewModel.currentBookId = null
                }
                repository.deleteBook(bookId)
                _library.value = _library.value.copy(
                    deleteResult = "删除成功",
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _library.value = _library.value.copy(
                    deleteResult = e.message ?: "删除失败",
                )
            } finally {
                _library.value = _library.value.copy(
                    deletingBookIds = _library.value.deletingBookIds - bookId,
                )
            }
        }
    }

    fun clearDeleteResult() {
        _library.value = _library.value.copy(deleteResult = null)
    }

    fun clearError() {
        _reader.value = _reader.value.copy(error = null)
    }

    fun open(bookId: String) {
        val token = ++openToken
        currentBookId = bookId
        _reader.value = ReaderUiState(loading = true)
        _readerPosition.value = ReaderPositionState(bookId = bookId)
        viewModelScope.launch {
            if (token != openToken) return@launch
            runCatching {
                val book = repository.book(bookId) ?: error("书籍未找到")
                val parsed = withContext(Dispatchers.Default) { repository.parsed(book) }
                val locator = repository.locator(bookId)
                val chapterIndex = parsed.chapters.indexOfFirst { it.path == locator?.chapterPath }
                    .coerceAtLeast(0)
                val chapter = parsed.chapters[chapterIndex]
                val restored = repository.restore(chapter, locator)
                val sentences = repository.sentences(chapter)
                val sentenceIndex = sentences.indexOf(restored).coerceAtLeast(0)
                val progress = repository.progressPercent(parsed, chapterIndex, sentenceIndex)
                ReaderUiState(book, parsed, chapterIndex, sentenceIndex, sentences, progress = progress)
                    .withAdjacentChapters()
            }.onSuccess { state ->
                if (token != openToken) return@launch
                _reader.value = state
                _readerPosition.value = state.toPositionState()
            }.onFailure {
                if (token != openToken) return@launch
                _reader.value = ReaderUiState(error = it.message ?: "打开失败")
                _readerPosition.value = ReaderPositionState()
                currentBookId = null
            }
        }
    }

    fun selectChapter(index: Int) = viewModelScope.launch {
        val state = _reader.value
        val parsed = state.parsed ?: return@launch
        val chapter = parsed.chapters.getOrNull(index) ?: return@launch
        val sentences = repository.sentences(chapter)
        val progress = repository.progressPercent(parsed, index, 0)
        val next = state.copy(
            chapterIndex = index,
            sentenceIndex = 0,
            sentences = sentences,
            progress = progress,
        ).withAdjacentChapters()
        _reader.value = next
        _readerPosition.value = next.toPositionState()
        persistCurrent("UI")
    }

    fun visibleSentence(index: Int) {
        val state = _reader.value
        val position = _readerPosition.value
        if (position.isSpeaking || index == position.sentenceIndex || state.sentences.isEmpty()) return
        val nextIndex = index.coerceIn(state.sentences.indices)
        val parsed = state.parsed ?: return
        val progress = repository.progressPercent(parsed, state.chapterIndex, nextIndex)
        val next = position.copy(
            chapterIndex = state.chapterIndex,
            sentenceIndex = nextIndex,
            progress = progress,
        )
        _readerPosition.value = next
        persistCurrent("UI")
    }

    fun visibleSentenceInCurrentChapter(index: Int) {
        val state = _reader.value
        val position = _readerPosition.value
        if (position.isSpeaking || state.sentences.isEmpty()) return
        val nextIndex = index.coerceIn(state.sentences.indices)
        if (nextIndex == position.sentenceIndex && state.chapterIndex == position.chapterIndex) return
        val parsed = state.parsed ?: return
        val progress = repository.progressPercent(parsed, state.chapterIndex, nextIndex)
        val next = position.copy(
            chapterIndex = state.chapterIndex,
            sentenceIndex = nextIndex,
            progress = progress,
        )
        _readerPosition.value = next
        persistCurrent("UI")
    }

    fun visibleSentence(sentence: SentenceRef) {
        val state = _reader.value
        val parsed = state.parsed ?: return
        val position = _readerPosition.value
        if (position.isSpeaking) return
        val chapterIndex = parsed.chapters.indexOfFirst { it.path == sentence.chapterPath }
        if (chapterIndex < 0) return
        val sentences = when (chapterIndex) {
            state.previousChapterIndex -> state.previousSentences
            state.chapterIndex -> state.sentences
            state.nextChapterIndex -> state.nextSentences
            else -> repository.cachedSentences(parsed.chapters[chapterIndex])
        }
        val sentenceIndex = sentences.indexOfFirst {
            it.paragraphIndex == sentence.paragraphIndex && it.sentenceIndex == sentence.sentenceIndex
        }
        if (sentenceIndex < 0) return
        if (chapterIndex == position.chapterIndex && sentenceIndex == position.sentenceIndex) return
        if (chapterIndex == state.chapterIndex) {
            visibleSentenceInCurrentChapter(sentenceIndex)
            return
        }
        val progress = repository.progressPercent(parsed, chapterIndex, sentenceIndex)
        _reader.value = state.copy(
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            sentences = sentences,
            progress = progress,
        ).withAdjacentChapters()
        val nextPosition = position.copy(
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            progress = progress,
        )
        _readerPosition.value = nextPosition
        persistCurrent("UI")
    }

    fun persistCurrent(source: String = "UI") = viewModelScope.launch {
        persistCurrentNow(source)
    }

    private suspend fun persistCurrentNow(source: String) {
        val state = _reader.value
        val position = _readerPosition.value
        val bookId = state.book?.id ?: return
        val parsed = state.parsed ?: return
        if (position.chapterIndex != state.chapterIndex) return
        val sentence = state.sentences.getOrNull(position.sentenceIndex) ?: return
        repository.saveProgress(bookId, sentence, source)
        val progress = repository.progressPercent(parsed, position.chapterIndex, position.sentenceIndex)
        val currentPosition = _readerPosition.value
        if (_reader.value.book?.id != bookId ||
            currentPosition.chapterIndex != position.chapterIndex ||
            currentPosition.sentenceIndex != position.sentenceIndex
        ) {
            return
        }
        val next = _reader.value.copy(progress = progress)
        _reader.value = next
        _readerPosition.value = currentPosition.copy(progress = progress)
    }

    fun updateSettings(value: ReaderSettingsEntity) = viewModelScope.launch {
        repository.saveSettings(value)
        if (_readerPosition.value.isSpeaking) sendAction(ReaderTtsService.ACTION_SETTINGS_CHANGED)
    }

    fun useSystemTts() {
        modelManagers.values.forEach { it.clearPendingSwitch() }
        updateSettings((settings.value ?: ReaderSettingsEntity()).copy(ttsEngine = TTS_ENGINE_SYSTEM))
    }

    fun useVitsModel(id: VitsModelId) {
        val manager = modelManagers.getValue(id)
        val descriptor = EmbeddedModelRegistry.byId(id)
        if (VitsModelManager.isReady(getApplication(), descriptor)) {
            manager.clearPendingSwitch()
            updateSettings(
                (settings.value ?: ReaderSettingsEntity()).copy(
                    ttsEngine = TTS_ENGINE_VITS,
                    vitsModelId = id.stableValue,
                ),
            )
        } else {
            // requestSwitchAfterDownload overwrites the single shared pending
            // id, so the latest model choice always wins over any prior one.
            manager.requestSwitchAfterDownload()
            manager.download()
        }
    }

    fun cancelVitsDownload(id: VitsModelId) {
        val manager = modelManagers.getValue(id)
        manager.clearPendingSwitch(id)
        manager.cancel()
    }

    fun setEmbeddedSpeakerId(id: VitsModelId, sid: Int) {
        embeddedSelectionStore.setSpeakerId(id.stableValue, sid)
        if (_readerPosition.value.isSpeaking) sendAction(ReaderTtsService.ACTION_SETTINGS_CHANGED)
    }

    fun setEmbeddedRate(id: VitsModelId, rate: Float) {
        embeddedSelectionStore.setRate(id.stableValue, rate.coerceIn(0.5f, 2f))
        if (_readerPosition.value.isSpeaking) sendAction(ReaderTtsService.ACTION_SETTINGS_CHANGED)
    }

    private fun applyImportedSettings(modelId: VitsModelId) {
        val descriptor = EmbeddedModelRegistry.byId(modelId)
        if (VitsModelManager.isReady(getApplication(), descriptor)) {
            modelManagers.getValue(modelId).clearPendingSwitch()
            updateSettings(
                (settings.value ?: ReaderSettingsEntity()).copy(
                    ttsEngine = TTS_ENGINE_VITS,
                    vitsModelId = modelId.stableValue,
                ),
            )
        }
    }

    fun deleteVitsModel(id: VitsModelId) {
        val manager = modelManagers.getValue(id)
        manager.clearPendingSwitch(id)
        val current = settings.value
        if (current?.ttsEngine == TTS_ENGINE_VITS && current.vitsModelId == id.stableValue) {
            useSystemTts()
        }
        manager.delete()
    }

    fun refreshTtsPerformance() {
        val modelId = VitsModelId.fromStableValue(settings.value?.vitsModelId) ?: VitsModelId.FANCHEN_WNJ
        val descriptor = EmbeddedModelRegistry.byId(modelId)
        _ttsPerformance.value = ttsPerformanceStore.snapshot(descriptor.revision, descriptor.id.stableValue)
    }

    fun togglePlayPause() {
        if (_readerPosition.value.isSpeaking) pause() else play()
    }

    fun play() {
        val timerEnd = _reader.value.sleepTimerEndAtMillis
        if (timerEnd != null && System.currentTimeMillis() >= timerEnd) {
            expireSleepTimer()
            return
        }
        viewModelScope.launch {
            persistCurrentNow("UI")
            val bookId = _reader.value.book?.id ?: return@launch
            val intent = Intent(getApplication(), ReaderTtsService::class.java).apply {
                action = ReaderTtsService.ACTION_PLAY
                putExtra(ReaderTtsService.EXTRA_BOOK_ID, bookId)
            }
            getApplication<Application>().startForegroundService(intent)
        }
    }

    fun pause() = sendAction(ReaderTtsService.ACTION_PAUSE)

    fun nextSentence() {
        if (_readerPosition.value.isSpeaking) {
            sendAction(ReaderTtsService.ACTION_NEXT)
        } else {
            moveLocalSentence(1)
        }
    }

    fun previousSentence() {
        if (_readerPosition.value.isSpeaking) {
            sendAction(ReaderTtsService.ACTION_PREVIOUS)
        } else {
            moveLocalSentence(-1)
        }
    }

    fun moveChapterFromScroll(direction: Int) = viewModelScope.launch {
        val state = _reader.value
        if (_readerPosition.value.isSpeaking || direction == 0) return@launch
        val move = findReadableChapter(state, direction.sign()) ?: return@launch
        val sentenceIndex = if (direction > 0) 0 else move.sentences.lastIndex
        val progress = state.parsed?.let { repository.progressPercent(it, move.chapterIndex, sentenceIndex) } ?: state.progress
        val next = state.copy(
            chapterIndex = move.chapterIndex,
            sentenceIndex = sentenceIndex,
            sentences = move.sentences,
            progress = progress,
        ).withAdjacentChapters()
        _reader.value = next
        _readerPosition.value = next.toPositionState()
        persistCurrent("UI")
    }

    fun startSleepTimer() {
        val minutes = _reader.value.sleepTimerMinutes.coerceIn(MIN_SLEEP_TIMER_MINUTES, MAX_SLEEP_TIMER_MINUTES)
        val endAt = System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE
        _reader.value = _reader.value.copy(
            sleepTimerMinutes = minutes,
            sleepTimerEndAtMillis = endAt,
            sleepTimerRemainingMinutes = minutes,
        )
        scheduleSleepTimer(endAt)
    }

    fun adjustSleepTimer(deltaMinutes: Int) {
        val state = _reader.value
        val minutes = (state.sleepTimerMinutes + deltaMinutes)
            .coerceIn(MIN_SLEEP_TIMER_MINUTES, MAX_SLEEP_TIMER_MINUTES)
        val active = state.sleepTimerEndAtMillis != null
        val endAt = if (active) System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE else null
        _reader.value = state.copy(
            sleepTimerMinutes = minutes,
            sleepTimerEndAtMillis = endAt,
            sleepTimerRemainingMinutes = if (active) minutes else null,
        )
        if (endAt != null) scheduleSleepTimer(endAt)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _reader.value = _reader.value.copy(
            sleepTimerEndAtMillis = null,
            sleepTimerRemainingMinutes = null,
        )
    }

    private fun sendAction(actionName: String, requestId: Int? = null) {
        val intent = Intent(getApplication(), ReaderTtsService::class.java)
            .setAction(actionName)
            .putExtra(ReaderTtsService.EXTRA_BOOK_ID, _reader.value.book?.id)
        if (requestId != null) {
            intent.putExtra(ReaderTtsService.EXTRA_STOP_REQUEST_ID, requestId)
        }
        getApplication<Application>().startService(intent)
    }

    private fun moveLocalSentence(delta: Int) = viewModelScope.launch {
        val state = _reader.value
        val position = _readerPosition.value
        val parsed = state.parsed ?: return@launch
        if (state.sentences.isEmpty()) return@launch
        val localIndex = position.sentenceIndex + delta
        if (localIndex in state.sentences.indices) {
            visibleSentenceInCurrentChapter(localIndex)
            persistCurrent("UI")
            return@launch
        }

        val move = findReadableChapter(state, delta.sign()) ?: return@launch
        val sentenceIndex = if (delta > 0) 0 else move.sentences.lastIndex
        val progress = repository.progressPercent(parsed, move.chapterIndex, sentenceIndex)
        _reader.value = state.copy(
            chapterIndex = move.chapterIndex,
            sentenceIndex = sentenceIndex,
            sentences = move.sentences,
            progress = progress,
        ).withAdjacentChapters()
        _readerPosition.value = _reader.value.toPositionState()
        persistCurrent("UI")
    }

    private suspend fun findReadableChapter(state: ReaderUiState, direction: Int): ChapterMove? {
        val parsed = state.parsed ?: return null
        var nextChapterIndex = state.chapterIndex + direction
        while (true) {
            val nextChapter = parsed.chapters.getOrNull(nextChapterIndex) ?: return null
            val nextSentences = repository.sentences(nextChapter)
            if (nextSentences.isNotEmpty()) {
                return ChapterMove(nextChapterIndex, nextSentences)
            }
            nextChapterIndex += direction
        }
    }

    private data class ChapterMove(
        val chapterIndex: Int,
        val sentences: List<SentenceRef>,
    )

    private fun scheduleSleepTimer(endAt: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remainingMillis = endAt - System.currentTimeMillis()
                if (remainingMillis <= 0) {
                    expireSleepTimer()
                    return@launch
                }
                _reader.value = _reader.value.copy(
                    sleepTimerRemainingMinutes = remainingMinutes(remainingMillis),
                )
                delay(nextSleepTimerTickMillis(remainingMillis))
            }
        }
    }

    private fun expireSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        persistCurrent("SLEEP_TIMER")
        pause()
        _reader.value = _reader.value.copy(
            sleepTimerEndAtMillis = null,
            sleepTimerRemainingMinutes = null,
        )
    }

    private fun remainingMinutes(remainingMillis: Long): Int =
        ((remainingMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt().coerceAtLeast(1)

    private fun nextSleepTimerTickMillis(remainingMillis: Long): Long {
        val millisUntilMinuteChanges = remainingMillis % MILLIS_PER_MINUTE
        return when {
            remainingMillis <= MILLIS_PER_MINUTE -> remainingMillis
            millisUntilMinuteChanges == 0L -> MILLIS_PER_MINUTE
            else -> millisUntilMinuteChanges
        }
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private fun applyTtsState(intent: Intent) {
        val state = _reader.value
        val position = _readerPosition.value
        val currentBookId = state.book?.id ?: return
        if (intent.getStringExtra(ReaderTtsService.EXTRA_BOOK_ID) != currentBookId) return
        val error = intent.getStringExtra(ReaderTtsService.EXTRA_ERROR)
        if (error != null) _reader.value = state.copy(error = error)
        val playing = intent.getBooleanExtra(ReaderTtsService.EXTRA_PLAYING, false)
        val chapterPath = intent.getStringExtra(ReaderTtsService.EXTRA_CHAPTER_PATH)
        val paragraphIndex = intent.getIntExtra(ReaderTtsService.EXTRA_PARAGRAPH_INDEX, -1)
        val sentenceIndex = intent.getIntExtra(ReaderTtsService.EXTRA_SENTENCE_INDEX, -1)
        val parsed = state.parsed
        if (parsed == null || chapterPath == null || paragraphIndex < 0 || sentenceIndex < 0) {
            _reader.value = _reader.value.copy(isSpeaking = playing)
            _readerPosition.value = position.copy(isSpeaking = playing)
            return
        }
        val chapterIndex = parsed.chapters.indexOfFirst { it.path == chapterPath }
        if (chapterIndex < 0) {
            _reader.value = _reader.value.copy(isSpeaking = playing)
            _readerPosition.value = position.copy(isSpeaking = playing)
            return
        }
        val sentenceRefs = repository.cachedSentences(parsed.chapters[chapterIndex])
        val localSentenceIndex = sentenceRefs.indexOfFirst {
            it.paragraphIndex == paragraphIndex && it.sentenceIndex == sentenceIndex
        }
        if (localSentenceIndex < 0) {
            _reader.value = _reader.value.copy(isSpeaking = playing)
            _readerPosition.value = position.copy(isSpeaking = playing)
            return
        }
        val progress = repository.progressPercent(parsed, chapterIndex, localSentenceIndex)
        val nextPosition = position.copy(
            bookId = currentBookId,
            chapterIndex = chapterIndex,
            sentenceIndex = localSentenceIndex,
            progress = progress,
            isSpeaking = playing,
        )
        if (chapterIndex == state.chapterIndex) {
            _readerPosition.value = nextPosition
            if (state.isSpeaking != playing) {
                _reader.value = state.copy(isSpeaking = playing)
            }
            return
        }
        _reader.value = state.copy(
            chapterIndex = chapterIndex,
            sentenceIndex = localSentenceIndex,
            sentences = sentenceRefs,
            isSpeaking = playing,
            progress = progress,
        ).withAdjacentChapters()
        _readerPosition.value = nextPosition
    }

    private fun ReaderUiState.withAdjacentChapters(): ReaderUiState {
        val parsed = parsed ?: return copy(
            previousChapterIndex = null,
            previousSentences = emptyList(),
            nextChapterIndex = null,
            nextSentences = emptyList(),
        )
        val previous = findReadableChapterSync(parsed, chapterIndex, -1)
        val next = findReadableChapterSync(parsed, chapterIndex, 1)
        return copy(
            previousChapterIndex = previous?.chapterIndex,
            previousSentences = previous?.sentences.orEmpty(),
            nextChapterIndex = next?.chapterIndex,
            nextSentences = next?.sentences.orEmpty(),
        )
    }

    private fun findReadableChapterSync(parsed: ParsedBook, currentChapterIndex: Int, direction: Int): ChapterMove? {
        var nextChapterIndex = currentChapterIndex + direction
        while (true) {
            val nextChapter = parsed.chapters.getOrNull(nextChapterIndex) ?: return null
            val nextSentences = repository.cachedSentences(nextChapter)
            if (nextSentences.isNotEmpty()) {
                return ChapterMove(nextChapterIndex, nextSentences)
            }
            nextChapterIndex += direction
        }
    }

    private fun ReaderUiState.toPositionState(): ReaderPositionState =
        ReaderPositionState(
            bookId = book?.id,
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            progress = progress,
            isSpeaking = isSpeaking,
        )

    fun currentChapter(): Chapter? = _reader.value.parsed?.chapters?.getOrNull(_reader.value.chapterIndex)

    override fun onCleared() {
        sleepTimerJob?.cancel()
        getApplication<Application>().unregisterReceiver(ttsStateReceiver)
        super.onCleared()
    }

    companion object {
        const val TTS_ENGINE_SYSTEM = "SYSTEM"
        const val TTS_ENGINE_VITS = "VITS"
        private const val MIN_SLEEP_TIMER_MINUTES = 5
        private const val MAX_SLEEP_TIMER_MINUTES = 120
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
