package com.example.epubreader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ParsedBook
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.ReadingLocatorEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.tts.ReaderTtsService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReaderUiState(
    val book: BookEntity? = null,
    val parsed: ParsedBook? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val sentences: List<SentenceRef> = emptyList(),
    val progress: Float = 0f,
    val isSpeaking: Boolean = false,
    val sleepTimerMinutes: Int = 20,
    val sleepTimerEndAtMillis: Long? = null,
    val sleepTimerRemainingMinutes: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ReaderApplication).repository

    val books = repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )

    private val _reader = MutableStateFlow(ReaderUiState())
    val reader: StateFlow<ReaderUiState> = _reader

    private val _bookProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val bookProgress: StateFlow<Map<String, Float>> = _bookProgress

    private var sleepTimerJob: Job? = null
    private val ttsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReaderTtsService.ACTION_STATE_CHANGED) return
            applyTtsState(intent)
        }
    }

    init {
        val filter = IntentFilter(ReaderTtsService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            application.registerReceiver(ttsStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(ttsStateReceiver, filter)
        }

        viewModelScope.launch {
            books.collect { refreshLibraryProgress(it) }
        }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        _reader.value = _reader.value.copy(loading = true, error = null)
        runCatching { repository.import(uri) }
            .onSuccess { open(it.id) }
            .onFailure { _reader.value = ReaderUiState(error = it.message ?: "导入失败") }
    }

    fun clearError() {
        _reader.value = _reader.value.copy(error = null)
    }

    fun open(bookId: String) = viewModelScope.launch {
        _reader.value = ReaderUiState(loading = true)
        runCatching {
            val book = requireNotNull(repository.book(bookId))
            val parsed = repository.parsed(book)
            val locator = repository.locator(bookId)
            val chapterIndex = parsed.chapters.indexOfFirst { it.path == locator?.chapterPath }
                .coerceAtLeast(0)
            val chapter = parsed.chapters[chapterIndex]
            val restored = repository.restore(chapter, locator)
            val sentences = repository.sentences(chapter)
            val sentenceIndex = sentences.indexOf(restored).coerceAtLeast(0)
            val progress = progressForSentence(parsed, sentences.getOrNull(sentenceIndex))
            ReaderUiState(book, parsed, chapterIndex, sentenceIndex, sentences, progress = progress)
        }.onSuccess {
            _reader.value = it
            updateBookProgressFromReader(it)
        }.onFailure {
            _reader.value = ReaderUiState(error = it.message ?: "打开失败")
        }
    }

    fun selectChapter(index: Int) = viewModelScope.launch {
        val state = _reader.value
        val parsed = state.parsed ?: return@launch
        val chapter = parsed.chapters.getOrNull(index) ?: return@launch
        val sentences = repository.sentences(chapter)
        val progress = progressForSentence(parsed, sentences.firstOrNull())
        val next = state.copy(
            chapterIndex = index,
            sentenceIndex = 0,
            sentences = sentences,
            progress = progress,
        )
        _reader.value = next
        updateBookProgressFromReader(next)
        persistCurrent("UI")
    }

    fun visibleSentence(index: Int) {
        val state = _reader.value
        if (state.isSpeaking || index == state.sentenceIndex || state.sentences.isEmpty()) return
        _reader.value = state.copy(sentenceIndex = index.coerceIn(state.sentences.indices))
        updateReaderProgress()
        persistCurrent("UI")
    }

    fun persistCurrent(source: String = "UI") = viewModelScope.launch {
        val state = _reader.value
        val bookId = state.book?.id ?: return@launch
        val parsed = state.parsed ?: return@launch
        val sentence = state.sentences.getOrNull(state.sentenceIndex) ?: return@launch
        repository.saveProgress(bookId, sentence, source)
        val progress = progressForSentence(parsed, sentence)
        val next = _reader.value.copy(progress = progress)
        _reader.value = next
        updateBookProgressFromReader(next)
    }

    fun updateSettings(value: ReaderSettingsEntity) = viewModelScope.launch {
        repository.saveSettings(value)
        if (_reader.value.isSpeaking) sendAction(ReaderTtsService.ACTION_SETTINGS_CHANGED)
    }

    fun togglePlayPause() {
        if (_reader.value.isSpeaking) pause() else play()
    }

    fun play() {
        val timerEnd = _reader.value.sleepTimerEndAtMillis
        if (timerEnd != null && System.currentTimeMillis() >= timerEnd) {
            expireSleepTimer()
            return
        }
        persistCurrent()
        val state = _reader.value
        val bookId = state.book?.id ?: return
        val intent = Intent(getApplication(), ReaderTtsService::class.java).apply {
            action = ReaderTtsService.ACTION_PLAY
            putExtra(ReaderTtsService.EXTRA_BOOK_ID, bookId)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun pause() = sendAction(ReaderTtsService.ACTION_PAUSE)

    fun nextSentence() {
        if (_reader.value.isSpeaking) {
            sendAction(ReaderTtsService.ACTION_NEXT)
        } else {
            moveLocalSentence(1)
        }
    }

    fun previousSentence() {
        if (_reader.value.isSpeaking) {
            sendAction(ReaderTtsService.ACTION_PREVIOUS)
        } else {
            moveLocalSentence(-1)
        }
    }

    fun moveChapterFromScroll(direction: Int) = viewModelScope.launch {
        val state = _reader.value
        if (state.isSpeaking || direction == 0) return@launch
        val move = findReadableChapter(state, direction.sign()) ?: return@launch
        val next = state.copy(
            chapterIndex = move.chapterIndex,
            sentenceIndex = if (direction > 0) 0 else move.sentences.lastIndex,
            sentences = move.sentences,
        )
        _reader.value = next
        updateReaderProgress()
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

    private fun sendAction(actionName: String) {
        val intent = Intent(getApplication(), ReaderTtsService::class.java)
            .setAction(actionName)
            .putExtra(ReaderTtsService.EXTRA_BOOK_ID, _reader.value.book?.id)
        getApplication<Application>().startService(intent)
    }

    private fun moveLocalSentence(delta: Int) = viewModelScope.launch {
        val state = _reader.value
        if (state.parsed == null) return@launch
        if (state.sentences.isEmpty()) return@launch
        val localIndex = state.sentenceIndex + delta
        if (localIndex in state.sentences.indices) {
            _reader.value = state.copy(sentenceIndex = localIndex)
            updateReaderProgress()
            persistCurrent("UI")
            return@launch
        }

        val move = findReadableChapter(state, delta.sign()) ?: return@launch
        _reader.value = state.copy(
            chapterIndex = move.chapterIndex,
            sentenceIndex = if (delta > 0) 0 else move.sentences.lastIndex,
            sentences = move.sentences,
        )
        updateReaderProgress()
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
        val currentBookId = state.book?.id ?: return
        if (intent.getStringExtra(ReaderTtsService.EXTRA_BOOK_ID) != currentBookId) return
        val playing = intent.getBooleanExtra(ReaderTtsService.EXTRA_PLAYING, false)
        val chapterPath = intent.getStringExtra(ReaderTtsService.EXTRA_CHAPTER_PATH)
        val paragraphIndex = intent.getIntExtra(ReaderTtsService.EXTRA_PARAGRAPH_INDEX, -1)
        val sentenceIndex = intent.getIntExtra(ReaderTtsService.EXTRA_SENTENCE_INDEX, -1)
        val parsed = state.parsed
        if (parsed == null || chapterPath == null || paragraphIndex < 0 || sentenceIndex < 0) {
            _reader.value = state.copy(isSpeaking = playing)
            return
        }
        val chapterIndex = parsed.chapters.indexOfFirst { it.path == chapterPath }
        if (chapterIndex < 0) {
            _reader.value = state.copy(isSpeaking = playing)
            return
        }
        val sentenceRefs = repository.cachedSentences(parsed.chapters[chapterIndex])
        val localSentenceIndex = sentenceRefs.indexOfFirst {
            it.paragraphIndex == paragraphIndex && it.sentenceIndex == sentenceIndex
        }
        if (localSentenceIndex < 0) {
            _reader.value = state.copy(isSpeaking = playing)
            return
        }
        _reader.value = state.copy(
            chapterIndex = chapterIndex,
            sentenceIndex = localSentenceIndex,
            sentences = sentenceRefs,
            isSpeaking = playing,
        )
        updateReaderProgress()
    }

    private suspend fun refreshLibraryProgress(items: List<BookEntity>) {
        val progressMap = mutableMapOf<String, Float>()
        items.forEach { book ->
            runCatching {
                val parsed = repository.parsed(book)
                val locator = repository.locator(book.id)
                progressForLocator(parsed, locator)
            }.onSuccess { progress ->
                progressMap[book.id] = progress
            }
        }
        _bookProgress.value = progressMap
    }

    private fun updateReaderProgress() = viewModelScope.launch {
        val state = _reader.value
        val parsed = state.parsed ?: return@launch
        val sentence = state.sentences.getOrNull(state.sentenceIndex) ?: return@launch
        val progress = progressForSentence(parsed, sentence)
        val next = _reader.value.copy(progress = progress)
        _reader.value = next
        updateBookProgressFromReader(next)
    }

    private fun updateBookProgressFromReader(state: ReaderUiState) {
        val id = state.book?.id ?: return
        _bookProgress.value = _bookProgress.value + (id to state.progress)
    }

    private suspend fun progressForLocator(
        parsed: ParsedBook,
        locator: ReadingLocatorEntity?,
    ): Float {
        if (locator == null) return 0f
        return progressForSentence(
            parsed,
            SentenceRef(
                chapterPath = locator.chapterPath,
                paragraphIndex = locator.paragraphIndex,
                sentenceIndex = locator.sentenceIndex,
                text = locator.context,
            ),
        )
    }

    private suspend fun progressForSentence(
        parsed: ParsedBook,
        target: SentenceRef?,
    ): Float {
        if (target == null) return 0f

        var total = 0
        var read = 0
        var found = false

        for (chapter in parsed.chapters) {
            val refs = repository.sentences(chapter)
            val count = refs.size
            if (count == 0) continue

            total += count

            if (found) continue

            if (chapter.path == target.chapterPath) {
                val index = refs.indexOfFirst {
                    it.paragraphIndex == target.paragraphIndex &&
                        it.sentenceIndex == target.sentenceIndex
                }
                if (index >= 0) {
                    read += index + 1
                }
                found = true
            } else {
                read += count
            }
        }

        if (total <= 0 || !found) return 0f
        return (read * 100f / total).coerceIn(0f, 100f)
    }

    fun currentChapter(): Chapter? = _reader.value.parsed?.chapters?.getOrNull(_reader.value.chapterIndex)

    override fun onCleared() {
        sleepTimerJob?.cancel()
        getApplication<Application>().unregisterReceiver(ttsStateReceiver)
        super.onCleared()
    }

    companion object {
        private const val MIN_SLEEP_TIMER_MINUTES = 5
        private const val MAX_SLEEP_TIMER_MINUTES = 120
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
