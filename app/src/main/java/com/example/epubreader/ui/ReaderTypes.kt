package com.example.epubreader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.example.epubreader.ReaderPositionState
import com.example.epubreader.ReaderUiState
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.SentenceRef

sealed class ReadingListItem {
    abstract val key: String

    data class Header(
        val chapterIndex: Int,
        val chapter: Chapter,
    ) : ReadingListItem() {
        override val key: String = "header:${chapter.path}"
    }

    data class Sentence(
        val chapterIndex: Int,
        val localSentenceIndex: Int,
        val sentence: SentenceRef,
    ) : ReadingListItem() {
        override val key: String =
            "sentence:${sentence.chapterPath}:${sentence.paragraphIndex}:${sentence.sentenceIndex}"
    }
}

data class VisibleSentenceSnapshot(
    val sentence: ReadingListItem.Sentence?,
    val isScrollInProgress: Boolean,
)

fun readingListItems(state: ReaderUiState): List<ReadingListItem> {
    val parsed = state.parsed ?: return emptyList()
    val items = mutableListOf<ReadingListItem>()
    fun appendChapter(chapterIndex: Int?, sentences: List<SentenceRef>) {
        if (chapterIndex == null || sentences.isEmpty()) return
        val chapter = parsed.chapters.getOrNull(chapterIndex) ?: return
        items += ReadingListItem.Header(chapterIndex, chapter)
        sentences.forEachIndexed { localIndex, sentence ->
            items += ReadingListItem.Sentence(chapterIndex, localIndex, sentence)
        }
    }
    appendChapter(state.previousChapterIndex, state.previousSentences)
    appendChapter(state.chapterIndex, state.sentences)
    appendChapter(state.nextChapterIndex, state.nextSentences)
    return items
}

fun List<ReadingListItem>.currentSentenceItemIndex(position: ReaderPositionState): Int =
    indexOfFirst {
        it is ReadingListItem.Sentence &&
            it.chapterIndex == position.chapterIndex &&
            it.localSentenceIndex == position.sentenceIndex
    }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
