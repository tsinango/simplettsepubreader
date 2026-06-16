package com.example.epubreader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ParsedBook
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.tts.ReaderTtsService
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
            val sentenceIndex = repository.sentences(chapter).indexOf(restored).coerceAtLeast(0)
            ReaderUiState(book, parsed, chapterIndex, sentenceIndex)
        }.onSuccess { _reader.value = it }
            .onFailure { _reader.value = ReaderUiState(error = it.message ?: "打开失败") }
    }

    fun selectChapter(index: Int) {
        val state = _reader.value
        if (index !in (state.parsed?.chapters?.indices ?: IntRange.EMPTY)) return
        _reader.value = state.copy(chapterIndex = index, sentenceIndex = 0)
        persistCurrent("UI")
    }

    fun visibleSentence(index: Int) {
        if (index == _reader.value.sentenceIndex) return
        _reader.value = _reader.value.copy(sentenceIndex = index)
        persistCurrent("UI")
    }

    fun persistCurrent(source: String = "UI") = viewModelScope.launch {
        val state = _reader.value
        val bookId = state.book?.id ?: return@launch
        val chapter = state.parsed?.chapters?.getOrNull(state.chapterIndex) ?: return@launch
        val sentence = repository.sentences(chapter).getOrNull(state.sentenceIndex) ?: return@launch
        repository.saveProgress(bookId, sentence, source)
    }

    fun updateSettings(value: ReaderSettingsEntity) = viewModelScope.launch {
        repository.saveSettings(value)
    }

    fun play() {
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
    fun nextSentence() = sendAction(ReaderTtsService.ACTION_NEXT)
    fun previousSentence() = sendAction(ReaderTtsService.ACTION_PREVIOUS)

    private fun sendAction(actionName: String) {
        val intent = Intent(getApplication(), ReaderTtsService::class.java)
            .setAction(actionName)
            .putExtra(ReaderTtsService.EXTRA_BOOK_ID, _reader.value.book?.id)
        getApplication<Application>().startService(intent)
    }

    fun currentChapter(): Chapter? =
        _reader.value.parsed?.chapters?.getOrNull(_reader.value.chapterIndex)
}
