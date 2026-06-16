package com.example.epubreader.data

import android.content.Context
import android.net.Uri
import com.example.epubreader.epub.EpubParser
import com.example.epubreader.epub.SentenceSplitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

class ReaderRepository(
    private val context: Context,
    private val dao: ReaderDao,
    private val parser: EpubParser = EpubParser(),
) {
    private val progressMutex = Mutex()
    val books: Flow<List<BookEntity>> = dao.books()
    val settings: Flow<ReaderSettingsEntity?> = dao.settings()

    suspend fun import(uri: Uri): BookEntity {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选文件")
        val id = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(24)
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(dir, "$id.epub")
        if (!file.exists()) file.writeBytes(bytes)
        val parsed = parser.parse(file)
        return BookEntity(
            id = id,
            title = parsed.title,
            author = parsed.author,
            localPath = file.absolutePath,
            importedAt = System.currentTimeMillis(),
        ).also { dao.saveBook(it) }
    }

    suspend fun book(id: String) = dao.book(id)
    suspend fun parsed(book: BookEntity) = parser.parse(File(book.localPath))
    suspend fun locator(bookId: String) = dao.locator(bookId)
    suspend fun saveSettings(value: ReaderSettingsEntity) = dao.saveSettings(value)

    suspend fun saveProgress(
        bookId: String,
        sentence: SentenceRef,
        source: String,
    ) = progressMutex.withLock {
        val current = dao.locator(bookId)
        val now = System.currentTimeMillis()
        if (current == null || now >= current.updatedAt) {
            dao.saveLocator(
                ReadingLocatorEntity(
                    bookId = bookId,
                    chapterPath = sentence.chapterPath,
                    paragraphIndex = sentence.paragraphIndex,
                    sentenceIndex = sentence.sentenceIndex,
                    characterOffset = 0,
                    context = sentence.text.take(96),
                    source = source,
                    updatedAt = now,
                ),
            )
        }
    }

    fun sentences(chapter: Chapter): List<SentenceRef> = chapter.paragraphs.flatMapIndexed { p, text ->
        SentenceSplitter.split(text).mapIndexed { s, sentence ->
            SentenceRef(chapter.path, p, s, sentence)
        }
    }

    fun restore(chapter: Chapter, locator: ReadingLocatorEntity?): SentenceRef? {
        if (locator == null) return null
        val sentences = sentences(chapter)
        val exact = sentences.firstOrNull {
            it.paragraphIndex == locator.paragraphIndex && it.sentenceIndex == locator.sentenceIndex
        }
        if (exact != null && exact.text.take(96) == locator.context) return exact
        return sentences.maxByOrNull { commonPrefix(it.text, locator.context) }
            ?.takeIf { commonPrefix(it.text, locator.context) >= 8 }
    }

    private fun commonPrefix(a: String, b: String): Int =
        a.zip(b).takeWhile { (left, right) -> left == right }.size
}
